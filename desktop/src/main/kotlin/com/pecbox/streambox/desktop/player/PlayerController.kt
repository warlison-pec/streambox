package com.pecbox.streambox.desktop.player

import kotlin.math.abs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.MediaSource
import com.pecbox.streambox.data.SubtitleStyle
import com.pecbox.streambox.data.TrackChoice
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.data.dataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.PrintWriter
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

sealed interface PlayerState {
    data object Idle : PlayerState
    data class Opening(val title: String) : PlayerState
    data class Playing(val title: String, val live: Boolean = false) : PlayerState
    data class Failed(val message: String) : PlayerState
}

/** Canal da TV ao vivo (Jellyfin ou lista M3U), como a LiveQueue do app Android. */
@Serializable
data class LiveEntry(
    val name: String,
    val logo: String? = null,
    val now: String? = null,           // programa atual (se houver guia)
    val jfId: String? = null,          // canal do Jellyfin
    val url: String? = null,           // canal da lista M3U
    val userAgent: String? = null,
    val referer: String? = null,
    val number: String? = null,
    val group: String? = null,
)

/** O que tocar: um vídeo do Jellyfin ou um canal de uma fila de canais. */
sealed interface PlayRequest {
    data class Vod(val item: Item, val fromStart: Boolean) : PlayRequest
    data class Live(val queue: List<LiveEntry>, val index: Int) : PlayRequest
}

/**
 * Reprodução com o mpv: sempre o arquivo original (Direct Play), nada de conversão no servidor.
 * O vídeo é desenhado dentro da janela do app (opção wid do mpv), ocupando a janela inteira como no
 * app Android; os controles (voltar, barra de progresso, áudio, legenda…) são desenhados pelo próprio mpv
 * com o script streambox_osc.lua, no visual do StreamBox.
 *
 * Mouse e teclado ficam com o app (o mpv embutido não recebe cliques no Windows): a área de vídeo repassa
 * movimento, cliques e rolagem ao script, e o script devolve o que o usuário escolheu (menus de legenda,
 * áudio, episódios, canais…), que é tratado aqui.
 *
 * Uma thread por sessão de reprodução lê os eventos do mpv, envia o progresso ao Jellyfin,
 * emenda o próximo episódio na mesma janela e, na TV ao vivo, troca de canal (↑/↓, PgUp/PgDn),
 * reconecta se o sinal cair e fecha o canal no Jellyfin para não prender o sintonizador.
 */
class PlayerController(private val s: Session, private val scope: CoroutineScope) {

    var state by mutableStateOf<PlayerState>(PlayerState.Idle)
        private set

    /** Chamado quando o player fecha (a tela inicial atualiza "Continuar assistindo"). */
    var onFinished: () -> Unit = {}

    /** Player aberto: a área de vídeo cobre a janela do app. */
    var active by mutableStateOf(false)
        private set

    /** Janela do app em tela cheia enquanto o vídeo toca (botão do player, F, duplo clique). */
    var fullscreen by mutableStateOf(false)

    /** Identificador nativo da área de vídeo (HWND no Windows, janela X11 no Linux). */
    @Volatile private var surface = CompletableFuture<Long>()

    fun attachSurface(wid: Long) { surface.complete(wid) }

    fun detachSurface() { if (surface.isDone) surface = CompletableFuture() }

    /** Controles do player na tela (sem eles, o cursor do mouse some em cima do vídeo). */
    var controlsVisible by mutableStateOf(true)
        private set

    /** Algum menu do player (legendas, áudio…) está aberto: o Esc fecha o menu primeiro. */
    @Volatile private var menuOpen = false

    /** Tecla apertada com o foco na janela do app: repassa ao mpv (que não recebe teclas direto). */
    fun keypress(name: String) { mpv?.commandAsync("keypress", name) }

    /** Esc: fecha o menu; senão sai da tela cheia; se já estiver em janela, volta (fecha o player), como o voltar do Android. */
    fun escape() {
        when {
            menuOpen -> mpv?.commandAsync("script-message", "sb-close-menu")
            fullscreen -> fullscreen = false
            else -> stop()
        }
    }

    /* Mouse em cima da área de vídeo (coordenadas do componente; o script converte para as do vídeo) */

    private var lastMove = 0L

    fun mouseMove(x: Int, y: Int, w: Int, h: Int) {
        val now = System.currentTimeMillis()
        if (now - lastMove < 25) return
        lastMove = now
        mpv?.commandAsync("script-message", "sb-mouse", "$x", "$y", "$w", "$h")
    }

    fun mouseClick(x: Int, y: Int, w: Int, h: Int, count: Int) {
        mpv?.commandAsync("script-message", if (count >= 2) "sb-dbl" else "sb-click", "$x", "$y", "$w", "$h")
    }

    fun mouseWheel(direction: Int) { mpv?.commandAsync("script-message", "sb-wheel", "$direction") }

    fun mouseLeave() { mpv?.commandAsync("script-message", "sb-leave") }

    @Volatile private var mpv: Mpv? = null
    @Volatile private var pending: PlayRequest? = null

    private class Current(
        val title: String,
        val url: String,
        val playSession: String,
        val item: Item? = null,            // vídeo do Jellyfin
        val source: MediaSource? = null,
        val sourceId: String = "",
        val startSeconds: Double = 0.0,
        val live: PlayRequest.Live? = null, // canal
        val reportId: String? = null,       // id informado ao Jellyfin (vídeo ou canal do Jellyfin)
        val liveStreamId: String? = null,   // transmissão aberta no Jellyfin (fechar ao sair)
        val userAgent: String? = null,
        val referer: String? = null,
    ) {
        /** Legendas externas do servidor já carregadas no mpv (índice do Jellyfin). */
        val addedSubs: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val isLive get() = live != null
        val method get() = if (isLive) "DirectStream" else "DirectPlay"
    }

    fun play(item: Item, fromStart: Boolean = false) = request(PlayRequest.Vod(item, fromStart))

    fun playLive(queue: List<LiveEntry>, index: Int) {
        queue.getOrNull(index)?.let { s.lastChannelJson = AppJson.encodeToString(LiveEntry.serializer(), it) }
        request(PlayRequest.Live(queue, index))
    }

    fun lastChannel(): LiveEntry? =
        s.lastChannelJson.takeIf { it.isNotBlank() }?.let { runCatching { AppJson.decodeFromString(LiveEntry.serializer(), it) }.getOrNull() }

    private fun request(r: PlayRequest) {
        if (mpv != null) {
            // Já tem uma janela aberta: troca o vídeo/canal nela
            pending = r
            mpv?.command("stop")
            mpv?.wakeup()   // se nada estiver tocando (canal que falhou), o laço pega o pedido na hora
            return
        }
        val lib = LibMpv.instance
        if (lib == null) {
            state = PlayerState.Failed(missingMpvMessage())
            return
        }
        state = PlayerState.Opening(titleOf(r))
        if (!active) {
            surface = CompletableFuture()
            fullscreen = s.fullscreen
            active = true
        }
        Thread({ runSession(lib, r) }, "mpv-eventos").apply { isDaemon = true }.start()
    }

    fun stop() {
        mpv?.command("quit")
    }

    fun dismissError() {
        if (state is PlayerState.Failed) state = PlayerState.Idle
    }

    private fun title(item: Item): String =
        if (item.isEpisode) "${item.seriesName ?: ""} · ${item.episodeLabel}".trim(' ', '·') else item.name

    private fun titleOf(r: PlayRequest): String = when (r) {
        is PlayRequest.Vod -> title(r.item)
        is PlayRequest.Live -> r.queue.getOrNull(r.index)?.let { ch -> listOfNotNull(ch.number, ch.name).joinToString("  ") } ?: "Canal"
    }

    private fun newSession() = UUID.randomUUID().toString().replace("-", "")

    /** Busca o que for preciso para tocar (rede: roda na thread do mpv). */
    private fun prepare(r: PlayRequest): Current = when (r) {
        is PlayRequest.Vod -> {
            val jf = s.jellyfin
            val item = r.item
            val info = runCatching { runBlocking { jf.playbackInfo(item.id) } }.getOrNull()
            val source = info?.sources?.firstOrNull()
            val sourceId = source?.id?.ifBlank { null } ?: item.id
            val resumeTicks = item.userData?.positionTicks ?: 0L
            Current(
                title = title(item), url = jf.directStreamUrl(item.id, sourceId),
                playSession = info?.playSessionId ?: newSession(),
                item = item, source = source, sourceId = sourceId,
                startSeconds = if (r.fromStart) 0.0 else resumeTicks / 10_000_000.0,
                reportId = item.id,
            )
        }
        is PlayRequest.Live -> {
            val ch = r.queue[r.index]
            val jfId = ch.jfId
            if (jfId != null) {
                // Canal do Jellyfin: o servidor sintoniza e devolve a transmissão aberta
                val jf = s.jellyfin
                val info = runBlocking { jf.playbackInfo(jfId, live = true) }
                val src = info.sources.firstOrNull() ?: throw IllegalStateException("Canal sem fonte")
                val path = src.path
                val url = if (src.isRemote && path != null && path.startsWith("http")) path
                else jf.liveStreamUrl(jfId, src.id.ifBlank { jfId }, src.liveStreamId)
                Current(
                    title = titleOf(r), url = url, playSession = info.playSessionId ?: newSession(),
                    sourceId = src.id.ifBlank { jfId }, live = r, reportId = jfId, liveStreamId = src.liveStreamId,
                )
            } else {
                Current(
                    title = titleOf(r), url = ch.url ?: throw IllegalStateException("Canal sem endereço"),
                    playSession = newSession(), live = r, userAgent = ch.userAgent, referer = ch.referer,
                )
            }
        }
    }

    private fun langs(code: String): String = when (code) {
        "por" -> "por,pt,pob,pt-BR,pt-PT"
        "eng" -> "eng,en"
        "spa" -> "spa,es"
        "jpn" -> "jpn,ja"
        else -> code
    }

    private fun configure(m: Mpv) {
        val conf = File(dataDir, "mpv").apply { mkdirs() }
        // Quem quiser pode pôr mpv.conf / input.conf / scripts nesta pasta
        m.option("config-dir", conf.absolutePath)
        m.option("config", "yes")
        m.option("terminal", "no")
        m.option("vo", "gpu")
        m.option("hwdec", "auto-safe")
        m.option("force-window", "yes")
        m.option("idle", "yes")
        m.option("keep-open", "no")
        m.option("background", "#000000")
        // Controles do StreamBox no lugar dos do mpv
        m.option("osc", "no")
        m.option("load-stats-overlay", "no")
        m.option("load-console", "no")
        m.option("scripts", oscScript().absolutePath)
        m.option("input-default-bindings", "yes")
        // Teclado e mouse vêm do app (input-cursor=no: no X11 os cliques passam para a área de vídeo do app)
        m.option("input-vo-keyboard", "no")
        m.option("input-cursor", "no")
        m.option("cursor-autohide", "no")
        m.option("ytdl", "no")
        m.option("cache", "yes")
        m.option("demuxer-max-bytes", "300MiB")
        m.option("demuxer-max-back-bytes", "100MiB")
        m.option("network-timeout", "60")
        // Canais ao vivo: o ffmpeg reconecta sozinho em quedas curtas da conexão
        m.option("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
        m.option("alang", langs(s.audioLang))
        m.option("slang", langs(s.subtitleLang))
        m.option("subs-with-matching-audio", "no")
        m.option("subs-fallback-forced", "yes")
        m.option("osd-font-size", "40")
        m.option("osd-color", "#FFFF7A00")
        m.option("sub-bold", "yes")
        m.option("screenshot-directory", System.getProperty("user.home"))
        m.option("volume", s.volume.toString())
        m.option("volume-max", "130")
        // Opções extras para testes: STREAMBOX_MPV_OPTS="vo=null,ao=null"
        System.getenv("STREAMBOX_MPV_OPTS")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "yes" } }
            if (k.isNotBlank()) m.option(k.trim(), v.trim())
        }
    }

    /** Copia o script dos controles para a pasta de dados (sempre a versão que veio com o app). */
    private fun oscScript(): File {
        val f = File(File(dataDir, "mpv"), "streambox_osc.lua")
        javaClass.getResourceAsStream("/streambox_osc.lua")?.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    /** Título e linha de baixo mostrados no topo do player. */
    private fun sendInfo(m: Mpv, c: Current) {
        val (title, subtitle) = when {
            c.isLive -> c.title to (c.live?.queue?.getOrNull(c.live.index)?.now?.let { "Agora: $it" } ?: "")
            c.item?.isEpisode == true -> (c.item.seriesName ?: c.item.name) to c.item.episodeLabel
            else -> c.title to listOfNotNull(c.item?.year?.toString(), c.item?.runtimeLabel).joinToString(" · ")
        }
        val episodes = if (c.item?.isEpisode == true && c.item.seriesId != null) "1" else "0"
        m.command("script-message", "sb-info", if (c.isLive) "live" else "vod", title, subtitle, episodes)
    }

    private fun load(m: Mpv, c: Current, defaultUa: String) {
        sendInfo(m, c)
        m.set("force-media-title", c.title)
        m.set("start", if (c.startSeconds > 5) "+%.3f".format(java.util.Locale.US, c.startSeconds) else "none")
        m.set("user-agent", c.userAgent ?: defaultUa)
        m.set("referrer", c.referer ?: "")
        m.command("loadfile", c.url, "replace")
    }

    /** Legendas externas do Jellyfin (arquivos .srt/.ass ao lado do vídeo ou baixadas da internet). */
    private fun addExternalSubs(m: Mpv, c: Current) {
        val source = c.source ?: return
        val item = c.item ?: return
        for (st in source.subtitles.filter { it.isExternal }) {
            val url = s.jellyfin.externalSubtitleUrl(item.id, c.sourceId, st)
            if (m.command("sub-add", url, "auto", st.label, st.language ?: "") >= 0) c.addedSubs += st.index
        }
    }

    private fun report(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) { runCatching { block() } }
    }

    private fun runSession(lib: LibMpv, first: PlayRequest) {
        val log = runCatching { PrintWriter(File(dataDir, "player.log").bufferedWriter(), true) }.getOrNull()
        fun logLine(t: String) = log?.println("${LocalTime.now().withNano(0)} $t")
        val m = try {
            // Espera a área de vídeo aparecer na janela do app
            val wid = surface.get(10, TimeUnit.SECONDS)
            preferX11ForEmbedding()
            Mpv(lib).also {
                configure(it)
                it.option("wid", wid.toString())
                it.initialize()
            }
        } catch (e: Throwable) {
            state = PlayerState.Failed("Não foi possível iniciar o player: ${e.message ?: e.javaClass.simpleName}")
            log?.close()
            active = false
            fullscreen = false
            return
        }
        mpv = m
        m.requestLog("warn")
        applySubtitleStyle(m)
        val defaultUa = m.get("user-agent") ?: "libmpv"
        logLine("libmpv ${m.get("mpv-version")} · hwdec=${m.get("hwdec")}")

        var current: Current? = null
        var lastLive: PlayRequest.Live? = null   // canal atual (mesmo se não sintonizou), para ↑/↓
        var liveRetries = 0
        var lastPos = 0.0
        var lastReport = 0L
        var started = false
        var failure: String? = null
        var toNext = false                       // saiu pelo "próximo episódio": conta como assistido

        /** Sai do item atual: avisa o Jellyfin e, se for canal do Jellyfin, fecha a transmissão. */
        fun leave(c: Current, ended: Boolean) {
            val id = c.reportId
            if (id != null && started) {
                val seconds = when {
                    c.isLive -> 0.0
                    ended || toNext -> c.item?.runTimeTicks?.div(10_000_000.0) ?: lastPos
                    else -> lastPos
                }
                val ticks = (seconds * 10_000_000).toLong()
                report { s.jellyfin.reportStopped(id, c.sourceId, ticks, c.playSession, c.method) }
            }
            c.liveStreamId?.let { live -> report { s.jellyfin.closeLiveStream(live) } }
            started = false
            toNext = false
        }

        fun start(r: PlayRequest) {
            state = PlayerState.Opening(titleOf(r))
            if (r is PlayRequest.Live) {
                if (r != lastLive) liveRetries = 0
                lastLive = r
                r.queue.getOrNull(r.index)?.let { s.lastChannelJson = AppJson.encodeToString(LiveEntry.serializer(), it) }
            } else lastLive = null
            val c = try {
                prepare(r)
            } catch (e: Exception) {
                logLine("não abriu ${titleOf(r)}: ${e.friendly()}")
                current = null
                if (r is PlayRequest.Live && r.queue.size > 1) {
                    // Com outros canais, a janela fica aberta para o usuário trocar
                    m.command("show-text", "Canal indisponível: ${e.friendly()}\n↑/↓ trocam de canal", "600000")
                    state = PlayerState.Failed("Canal indisponível: ${e.friendly()}")
                } else {
                    failure = "Não foi possível abrir ${titleOf(r)}: ${e.friendly()}"
                    m.command("quit")
                }
                return
            }
            current = c
            playing = c
            nextUp = null
            lastPos = c.startSeconds
            logLine("abrindo ${c.title} (${if (c.isLive) "ao vivo" else c.item?.id}) a partir de ${c.startSeconds.toInt()}s")
            load(m, c, defaultUa)
        }

        fun zap(delta: Int) {
            val live = lastLive ?: return
            val n = live.queue.size
            if (n < 2) return
            val next = PlayRequest.Live(live.queue, ((live.index + delta) % n + n) % n)
            val ch = next.queue[next.index]
            m.command("show-text", listOfNotNull(ch.number, ch.name).joinToString("  ") + (ch.now?.let { "\nAgora: $it" } ?: ""), "3000")
            if (current != null) {
                pending = next
                m.command("stop")
            } else start(next)
        }

        start(first)
        loop@ while (true) {
            val ev = m.waitEvent(1.0)
            when (ev.id) {
                MpvEvent.SHUTDOWN -> {
                    current?.let { leave(it, ended = false) }
                    current = null
                    break@loop
                }
                MpvEvent.LOG_MESSAGE -> ev.log?.let { logLine(it) }
                MpvEvent.CLIENT_MESSAGE -> {
                    val msg = ev.message.orEmpty()
                    when (msg.firstOrNull()) {
                        "sb-canal" -> zap(msg.getOrNull(1)?.toIntOrNull() ?: 1)
                        "sb-voltar" -> m.command("quit")
                        "sb-esc" -> scope.launch { escape() }
                        "sb-tela-cheia" -> scope.launch { fullscreen = !fullscreen }
                        "sb-menu-aberto" -> menuOpen = msg.getOrNull(1) == "1"
                        "sb-controles" -> (msg.getOrNull(1) == "1").let { v -> scope.launch { controlsVisible = v } }
                        "sb-abrir" -> openMenu(m, msg.getOrNull(1).orEmpty(), lastLive)
                        "sb-escolha" -> onChoice(m, msg.drop(1), lastLive)
                        "sb-digito" -> typeDigit(m, msg.getOrNull(1).orEmpty(), lastLive)
                        "sb-proximo-agora" -> nextUp?.let { n ->
                            toNext = true
                            pending = PlayRequest.Vod(n, fromStart = n.userData?.played == true)
                            m.command("stop")
                        }
                    }
                }
                MpvEvent.FILE_LOADED -> {
                    val c = current ?: continue@loop
                    m.set("sub-delay", "0")   // a sincronia ajustada vale só para o vídeo em que foi feita
                    addExternalSubs(m, c)
                    restoreTracks(m, c)
                    sendInfo(m, c)   // o script pode ter carregado depois do primeiro aviso
                    loadExtras(m, c)
                    state = PlayerState.Playing(c.title, live = c.isLive)
                    logLine("tocando: vídeo=${m.get("video-codec")} ${m.get("width")}x${m.get("height")} áudio=${m.get("audio-codec-name")} hwdec=${m.get("hwdec-current")}")
                    val tracks = m.get("track-list/count")?.toIntOrNull() ?: 0
                    for (t in 0 until tracks) {
                        val p = "track-list/$t"
                        logLine(
                            "  faixa ${m.get("$p/type")} #${m.get("$p/id")} ${m.get("$p/codec")} lang=${m.get("$p/lang")} " +
                                (if (m.flag("$p/external")) "externa " else "") + (if (m.flag("$p/selected")) "[ativa]" else "")
                        )
                    }
                    if (c.isLive) {
                        liveRetries = 0
                        c.live?.queue?.getOrNull(c.live.index)?.now?.let { m.command("show-text", "${c.title}\nAgora: $it", "4000") }
                    }
                    val id = c.reportId
                    if (id != null) {
                        val ticks = (c.startSeconds * 10_000_000).toLong()
                        report { s.jellyfin.reportStart(id, c.sourceId, ticks, c.playSession, c.method) }
                    }
                    started = true
                    lastReport = System.currentTimeMillis()
                }
                MpvEvent.END_FILE -> {
                    val c = current
                    current = null
                    val reason = ev.endReason
                    logLine("fim do arquivo: motivo=$reason erro=${ev.endError?.let { m.errorString(it) }}")
                    if (c != null) leave(c, ended = reason == MpvEndReason.EOF)
                    val next = pending
                    pending = null
                    when {
                        // Pedido de outro vídeo/canal enquanto a janela estava aberta
                        next != null -> start(next)
                        // Canal caiu ou não abriu: tenta de novo algumas vezes (o Jellyfin sintoniza outra vez)
                        c != null && c.isLive && (reason == MpvEndReason.EOF || reason == MpvEndReason.ERROR) && liveRetries < 3 -> {
                            liveRetries++
                            m.command("show-text", "Reconectando ${c.title}… ($liveRetries/3)", "3000")
                            Thread.sleep(1500L * liveRetries)
                            start(c.live!!)
                        }
                        c != null && c.isLive && (reason == MpvEndReason.EOF || reason == MpvEndReason.ERROR) -> {
                            if ((c.live?.queue?.size ?: 0) > 1) {
                                m.command("show-text", "${c.title} não está respondendo.\n↑/↓ trocam de canal", "600000")
                                state = PlayerState.Failed("${c.title} não está respondendo. Troque de canal ou feche o player.")
                            } else {
                                failure = "${c.title} não está respondendo. Detalhes em ${File(dataDir, "player.log").absolutePath}"
                                m.command("quit")
                            }
                        }
                        reason == MpvEndReason.ERROR -> {
                            val err = ev.endError?.let { m.errorString(it) } ?: "erro desconhecido"
                            failure = "Não foi possível tocar \"${c?.title ?: "o vídeo"}\" ($err). " +
                                "Detalhes em ${File(dataDir, "player.log").absolutePath}"
                            m.command("quit")
                        }
                        reason == MpvEndReason.EOF && c?.item?.isEpisode == true -> {
                            val nextEp = nextUp ?: runCatching { runBlocking { s.jellyfin.nextEpisode(c.item) } }.getOrNull()
                            if (nextEp != null) {
                                m.command("show-text", "Próximo: ${nextEp.episodeLabel}", "4000")
                                start(PlayRequest.Vod(nextEp, fromStart = true))
                            } else m.command("quit")
                        }
                        reason == MpvEndReason.EOF || reason == MpvEndReason.STOP -> m.command("quit")
                    }
                }
            }
            // Janela parada (canal que falhou) e o usuário escolheu outro no app
            if (current == null) pending?.let { pending = null; start(it) }
            // Posição atual e progresso a cada 10 s
            val c = current ?: continue
            m.double("time-pos")?.let { lastPos = it }
            val now = System.currentTimeMillis()
            val id = c.reportId
            if (started && id != null && now - lastReport >= 10_000) {
                lastReport = now
                val ticks = if (c.isLive) 0L else (lastPos * 10_000_000).toLong()
                val paused = m.flag("pause")
                report { s.jellyfin.reportProgress(id, c.sourceId, ticks, paused, c.playSession) }
            }
        }

        m.double("volume")?.let { s.volume = Math.round(it).toInt() }   // o próximo vídeo abre no mesmo volume
        mpv = null
        pending = null
        playing = null
        nextUp = null
        menuOpen = false
        m.destroy()          // antes de tirar a área de vídeo da tela
        log?.close()
        state = failure?.let { PlayerState.Failed(it) } ?: PlayerState.Idle
        fullscreen = false
        active = false
        controlsVisible = true
        scope.launch { onFinished() }
    }

    /* ============================ menus do player ============================ */

    /** O que está tocando agora (para os menus, que chegam pela thread do mpv ou pela rede). */
    @Volatile private var playing: Current? = null

    /** Próximo episódio da série (cartão "Próximo episódio" e emenda no fim). */
    @Volatile private var nextUp: Item? = null

    /** Episódios mostrados no menu (temporada escolhida) e temporadas da série. */
    @Volatile private var menuEpisodes: List<Item> = emptyList()
    @Volatile private var menuSeasons: List<Item> = emptyList()
    @Volatile private var seasonIdx = 0
    @Volatile private var channelGroupIdx = 0

    private var digits = ""
    private var digitJob: Job? = null

    private class Track(
        val id: String, val type: String, val lang: String?, val title: String?,
        val forced: Boolean, val external: Boolean, val selected: Boolean, val codec: String?, val channels: Int?,
    )

    private fun tracks(m: Mpv, type: String): List<Track> {
        val n = m.get("track-list/count")?.toIntOrNull() ?: 0
        return (0 until n).mapNotNull { i ->
            val p = "track-list/$i"
            if (m.get("$p/type") != type) return@mapNotNull null
            Track(
                id = m.get("$p/id") ?: return@mapNotNull null, type = type,
                lang = m.get("$p/lang")?.ifBlank { null }, title = m.get("$p/title")?.ifBlank { null },
                forced = m.flag("$p/forced"), external = m.flag("$p/external"), selected = m.flag("$p/selected"),
                codec = m.get("$p/codec")?.ifBlank { null },
                channels = m.get("$p/demux-channel-count")?.toIntOrNull(),
            )
        }
    }

    private val LANG_NAMES = mapOf(
        "pt" to "Português", "en" to "Inglês", "es" to "Espanhol", "ja" to "Japonês", "fr" to "Francês",
        "de" to "Alemão", "it" to "Italiano", "ko" to "Coreano", "zh" to "Chinês", "ru" to "Russo",
        "ar" to "Árabe", "nl" to "Holandês", "pl" to "Polonês", "tr" to "Turco", "hi" to "Hindi",
        "sv" to "Sueco", "da" to "Dinamarquês", "no" to "Norueguês", "fi" to "Finlandês", "cs" to "Tcheco",
        "hu" to "Húngaro", "el" to "Grego", "he" to "Hebraico", "th" to "Tailandês", "uk" to "Ucraniano", "ro" to "Romeno",
    )

    /** ISO 639-2 (3 letras) -> 639-1 (2 letras), incluindo os códigos bibliográficos (fre, ger, chi…). */
    private val ISO3 by lazy {
        val map = HashMap<String, String>()
        for (l in java.util.Locale.getISOLanguages()) runCatching { map[java.util.Locale(l).isO3Language] = l }
        map += mapOf("fre" to "fr", "ger" to "de", "chi" to "zh", "dut" to "nl", "gre" to "el", "cze" to "cs", "rum" to "ro", "pob" to "pt")
        map
    }

    /** Idioma sem a região ("pt-BR", "por" e "pob" viram "pt"). */
    private fun baseLanguage(lang: String?): String? {
        val l = lang?.trim()?.lowercase()?.ifBlank { null }?.substringBefore('-')?.substringBefore('_') ?: return null
        return if (l.length == 3) ISO3[l] ?: l else l
    }

    private fun langName(lang: String?): String? = lang?.let { LANG_NAMES[baseLanguage(it)] ?: it.uppercase() }

    private fun trackLabel(t: Track): String {
        val name = langName(t.lang)
        val title = t.title
        return when {
            name != null && title != null && !title.contains(name, ignoreCase = true) -> "$name · $title"
            name != null -> name
            title != null -> title
            else -> "Faixa ${t.id}"
        }
    }

    private fun trackDetail(t: Track): String = listOfNotNull(
        if (t.forced) "Forçada" else null,
        if (t.type == "audio") when (t.channels) { 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; null -> null; else -> "${t.channels} canais" } else null,
        t.codec?.uppercase(),
        if (t.external) "Externa" else null,
    ).joinToString(" · ")

    private class MenuItem(
        val label: String, val cmd: List<String>?, val checked: Boolean = false, val detail: String? = null,
        val keep: Boolean = false,   // o menu continua aberto depois da escolha
    )

    private fun sendMenu(
        m: Mpv, id: String, title: String, items: List<MenuItem>,
        group: String? = null, left: List<String>? = null, right: List<String>? = null, sel: Int? = null,
    ) {
        val json = buildJsonObject {
            put("id", id)
            put("title", title)
            if (sel != null) put("sel", sel)
            if (group != null) put("group", group)
            if (left != null) put("left", JsonArray(left.map { JsonPrimitive(it) }))
            if (right != null) put("right", JsonArray(right.map { JsonPrimitive(it) }))
            put("items", buildJsonArray {
                for (it in items) add(buildJsonObject {
                    put("label", it.label)
                    if (it.detail != null) put("detail", it.detail)
                    if (it.checked) put("checked", true)
                    if (it.keep) put("keep", true)
                    if (it.cmd != null) put("cmd", JsonArray(it.cmd.map { a -> JsonPrimitive(a) }))
                })
            })
        }
        m.command("script-message", "sb-menu", json.toString())
    }

    private fun openMenu(m: Mpv, name: String, live: PlayRequest.Live?) {
        when (name) {
            "legendas" -> subtitlesMenu(m)
            "audio" -> audioMenu(m)
            "episodios" -> episodesMenu(m, reload = true)
            "canais" -> channelsMenu(m, live, reset = true)
            "aparencia" -> appearanceMenu(m)
        }
    }

    private fun subtitlesMenu(m: Mpv) {
        val c = playing
        val subs = tracks(m, "sub")
        val items = mutableListOf(MenuItem("Desligadas", listOf("sub", "no"), checked = subs.none { it.selected }))
        subs.forEach { t -> items += MenuItem(trackLabel(t), listOf("sub", t.id), t.selected, trackDetail(t).ifBlank { null }) }
        if (c?.item != null) items += MenuItem("Baixar legenda da internet…", listOf("sub-online"))
        if (subs.any { it.selected }) items += MenuItem("Sincronia da legenda…", listOf("sincronia"), detail = syncLabel(m))
        items += MenuItem("Aparência da legenda…", listOf("aparencia"))
        sendMenu(m, "legendas", "Legendas", items)
    }

    /* Sincronia: no mpv, sub-delay positivo = legenda atrasada. Volta a zero a cada vídeo. */
    private val syncSteps = listOf(
        "Adiantar 0,25 s" to -250, "Atrasar 0,25 s" to 250,
        "Adiantar 1 s" to -1000, "Atrasar 1 s" to 1000,
    )

    private fun syncLabel(m: Mpv): String {
        val ms = Math.round((m.double("sub-delay") ?: 0.0) * 1000)
        if (ms == 0L) return "no tempo original"
        val secs = java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(java.util.Locale.forLanguageTag("pt-BR"))).format(abs(ms) / 1000.0)
        return (if (ms < 0) "adiantada " else "atrasada ") + secs + " s"
    }

    private fun syncMenu(m: Mpv, sel: Int? = null) {
        val items = syncSteps.map { (label, ms) -> MenuItem(label, listOf("sync", "$ms"), keep = true) } +
            MenuItem("Voltar ao original", listOf("sync", "0"), keep = true) +
            MenuItem("Voltar às legendas", listOf("abrir", "legendas"))
        sendMenu(m, "sincronia", "Legenda ${syncLabel(m)}  ·  atalhos G / H", items, sel = sel)
    }

    private fun applySync(m: Mpv, ms: Int) {
        if (ms == 0) m.set("sub-delay", "0") else m.command("add", "sub-delay", "%.3f".format(java.util.Locale.US, ms / 1000.0))
        m.command("show-text", "Legenda ${syncLabel(m)}", "1500")
    }

    private fun audioMenu(m: Mpv) {
        val auds = tracks(m, "audio")
        if (auds.isEmpty()) { m.command("show-text", "Sem faixas de áudio", "2000"); return }
        sendMenu(m, "audio", "Áudio", auds.map { t -> MenuItem(trackLabel(t), listOf("audio", t.id), t.selected, trackDetail(t).ifBlank { null }) })
    }

    private fun appearanceMenu(m: Mpv) {
        sendMenu(
            m, "aparencia", "Aparência da legenda", listOf(
                MenuItem("Tamanho", listOf("ap-menu", "tamanho"), detail = SubtitleStyle.SIZES.getOrElse(s.subSize) { SubtitleStyle.SIZES[1] }.first),
                MenuItem("Cor", listOf("ap-menu", "cor"), detail = SubtitleStyle.COLORS.getOrElse(s.subColor) { SubtitleStyle.COLORS[0] }.first),
                MenuItem("Fundo", listOf("ap-menu", "fundo"), detail = SubtitleStyle.BACKGROUNDS.getOrElse(s.subBackground) { SubtitleStyle.BACKGROUNDS[0] }),
                MenuItem("Voltar às legendas", listOf("abrir", "legendas")),
            )
        )
    }

    private fun appearanceOptions(m: Mpv, which: String) {
        val (title, labels, checked) = when (which) {
            "tamanho" -> Triple("Tamanho", SubtitleStyle.SIZES.map { it.first }, s.subSize)
            "cor" -> Triple("Cor", SubtitleStyle.COLORS.map { it.first }, s.subColor)
            else -> Triple("Fundo", SubtitleStyle.BACKGROUNDS, s.subBackground)
        }
        sendMenu(m, "aparencia-$which", title, labels.mapIndexed { i, l -> MenuItem(l, listOf("ap", which, "$i"), i == checked) })
    }

    /** Tamanho, cor e fundo da legenda (mesmas opções do app Android). */
    private fun applySubtitleStyle(m: Mpv) {
        val scale = SubtitleStyle.SIZES.getOrElse(s.subSize) { SubtitleStyle.SIZES[1] }.second
        val color = SubtitleStyle.COLORS.getOrElse(s.subColor) { SubtitleStyle.COLORS[0] }.second
        m.set("sub-scale", "%.2f".format(java.util.Locale.US, scale))
        m.set("sub-color", "#%06X".format(color and 0xFFFFFF))
        when (s.subBackground) {
            1 -> { // sombra
                m.set("sub-border-style", "outline-and-shadow")
                m.set("sub-border-size", "0")
                m.set("sub-shadow-offset", "2.5")
                m.set("sub-back-color", "#C0000000")      // cor da sombra no mpv novo
                m.set("sub-shadow-color", "#C0000000")    // e no antigo
            }
            2, 3 -> { // fundo translúcido / caixa preta
                val box = if (s.subBackground == 2) "#99000000" else "#FF000000"
                // mpv 0.38+: background-box usa sub-back-color; antes, opaque-box usava a cor da borda
                if (m.set("sub-border-style", "background-box") < 0) m.set("sub-border-style", "opaque-box")
                m.set("sub-back-color", box)
                m.set("sub-border-color", box)
                m.set("sub-border-size", "2")
                m.set("sub-shadow-offset", "0")
            }
            else -> { // contorno
                m.set("sub-border-style", "outline-and-shadow")
                m.set("sub-border-color", "#FF000000")
                m.set("sub-border-size", "3.2")
                m.set("sub-shadow-offset", "0")
                m.set("sub-back-color", "#00000000")
            }
        }
    }

    private fun episodesMenu(m: Mpv, reload: Boolean) {
        val item = playing?.item ?: return
        val seriesId = item.seriesId ?: return
        if (!reload && menuEpisodes.isNotEmpty()) { sendEpisodes(m, item); return }
        sendMenu(m, "episodios", item.seriesName ?: "Episódios", listOf(MenuItem("Carregando episódios…", null)))
        scope.launch(Dispatchers.IO) {
            runCatching {
                val seasons = runCatching { s.jellyfin.seasons(seriesId) }.getOrDefault(emptyList())
                menuSeasons = seasons
                if (reload) seasonIdx = seasons.indexOfFirst { it.id == item.seasonId }.coerceAtLeast(0)
                val seasonId = seasons.getOrNull(seasonIdx)?.id ?: item.seasonId.orEmpty()
                menuEpisodes = s.jellyfin.episodes(seriesId, seasonId)
                sendEpisodes(m, item)
            }.onFailure { e ->
                sendMenu(m, "episodios", item.seriesName ?: "Episódios", listOf(MenuItem("Não foi possível carregar: ${e.friendly()}", null)))
            }
        }
    }

    private fun sendEpisodes(m: Mpv, current: Item) {
        val seasons = menuSeasons
        val season = seasons.getOrNull(seasonIdx)
        val items = menuEpisodes.map { ep ->
            val state = when {
                ep.userData?.played == true -> "Assistido"
                (ep.userData?.playedPercentage ?: 0.0) > 0 -> "${ep.userData?.playedPercentage?.toInt()}% assistido"
                else -> null
            }
            MenuItem(
                "E%02d · %s".format(ep.indexNumber ?: 0, ep.name), listOf("episodio", ep.id),
                checked = ep.id == current.id, detail = listOfNotNull(ep.runtimeLabel, state).joinToString(" · ").ifBlank { null },
            )
        }.ifEmpty { listOf(MenuItem("Nenhum episódio", null)) }
        val multi = seasons.size > 1
        sendMenu(
            m, "episodios", current.seriesName ?: "Episódios", items,
            group = if (multi) season?.name ?: "Temporada" else null,
            left = if (multi && seasonIdx > 0) listOf("temporada", "-1") else null,
            right = if (multi && seasonIdx < seasons.size - 1) listOf("temporada", "1") else null,
        )
    }

    private fun channelsMenu(m: Mpv, live: PlayRequest.Live?, reset: Boolean) {
        val lv = live ?: return
        val groups = listOf("Todos") + lv.queue.mapNotNull { it.group?.ifBlank { null } }.distinct().let { if (it.size <= 1) emptyList() else it }
        if (reset) {
            val g = lv.queue.getOrNull(lv.index)?.group
            channelGroupIdx = if (groups.size > 1) groups.indexOf(g).coerceAtLeast(0) else 0
        }
        channelGroupIdx = channelGroupIdx.coerceIn(0, groups.size - 1)
        val g = groups[channelGroupIdx]
        val items = lv.queue.indices.filter { g == "Todos" || lv.queue[it].group == g }.map { i ->
            val ch = lv.queue[i]
            MenuItem(listOfNotNull(ch.number, ch.name).joinToString("  "), listOf("canal", "$i"), i == lv.index, ch.now?.let { "Agora: $it" })
        }
        sendMenu(
            m, "canais", "Canais", items,
            group = if (groups.size > 1) "$g · ${items.size}" else "${items.size} canais",
            left = if (groups.size > 1) listOf("canais-grupo", "-1") else null,
            right = if (groups.size > 1) listOf("canais-grupo", "1") else null,
        )
    }

    /** Item escolhido num menu do player. */
    private fun onChoice(m: Mpv, cmd: List<String>, live: PlayRequest.Live?) {
        val c = playing
        when (cmd.firstOrNull()) {
            "abrir" -> openMenu(m, cmd.getOrNull(1).orEmpty(), live)
            "sub" -> {
                val id = cmd.getOrNull(1) ?: return
                m.set("sid", id)
                val t = tracks(m, "sub").firstOrNull { it.id == id }
                m.command("show-text", "Legenda: " + (t?.let(::trackLabel) ?: "desligada"), "2000")
                if (c?.item != null && !c.isLive) {
                    val choice = t?.let { TrackChoice(language = it.lang, label = it.title, forced = it.forced) } ?: TrackChoice(off = true)
                    s.saveTrackChoice("sub", c.item.seriesId, choice)
                }
            }
            "audio" -> {
                val id = cmd.getOrNull(1) ?: return
                m.set("aid", id)
                val t = tracks(m, "audio").firstOrNull { it.id == id } ?: return
                m.command("show-text", "Áudio: " + trackLabel(t), "2000")
                if (c?.item != null && !c.isLive) s.saveTrackChoice("audio", c.item.seriesId, TrackChoice(language = t.lang, label = t.title))
            }
            "aparencia" -> appearanceMenu(m)
            "sincronia" -> syncMenu(m)
            "sync" -> {
                val ms = cmd.getOrNull(1)?.toIntOrNull() ?: return
                applySync(m, ms)
                // Pelo menu: reabre no mesmo item; pelos atalhos G/H com o menu fechado, só o aviso
                if (cmd.getOrNull(2) != "tecla") syncMenu(m, sel = (syncSteps.indexOfFirst { it.second == ms }.takeIf { it >= 0 } ?: syncSteps.size) + 1)
            }
            "ap-menu" -> appearanceOptions(m, cmd.getOrNull(1).orEmpty())
            "ap" -> {
                val i = cmd.getOrNull(2)?.toIntOrNull() ?: return
                when (cmd.getOrNull(1)) {
                    "tamanho" -> s.subSize = i
                    "cor" -> s.subColor = i
                    else -> s.subBackground = i
                }
                applySubtitleStyle(m)
                appearanceMenu(m)
            }
            "sub-online" -> searchSubtitles(m)
            "sub-baixar" -> downloadSubtitle(m, cmd.getOrNull(1) ?: return)
            "temporada" -> {
                val d = cmd.getOrNull(1)?.toIntOrNull() ?: return
                seasonIdx = (seasonIdx + d).coerceIn(0, (menuSeasons.size - 1).coerceAtLeast(0))
                menuEpisodes = emptyList()
                episodesMenu(m, reload = false)
            }
            "episodio" -> {
                val ep = menuEpisodes.firstOrNull { it.id == cmd.getOrNull(1) } ?: return
                if (ep.id == c?.item?.id) return
                m.command("show-text", ep.episodeLabel, "3000")
                request(PlayRequest.Vod(ep, fromStart = ep.userData?.played == true))
            }
            "canais-grupo" -> {
                channelGroupIdx += cmd.getOrNull(1)?.toIntOrNull() ?: 0
                val groups = 1 + (live?.queue?.mapNotNull { it.group?.ifBlank { null } }?.distinct()?.size ?: 0)
                channelGroupIdx = ((channelGroupIdx % groups) + groups) % groups
                channelsMenu(m, live, reset = false)
            }
            "canal" -> {
                val lv = live ?: return
                val i = cmd.getOrNull(1)?.toIntOrNull() ?: return
                if (i == lv.index && c != null) return
                playLive(lv.queue, i)
            }
        }
    }

    /** Busca legendas pelos provedores do Jellyfin (OpenSubtitles etc.), no idioma escolhido nos Ajustes. */
    private fun searchSubtitles(m: Mpv) {
        val item = playing?.item ?: return
        val title = "Legendas da internet"
        sendMenu(m, "online", title, listOf(MenuItem("Procurando legendas…", null)))
        scope.launch(Dispatchers.IO) {
            runCatching { s.jellyfin.searchSubtitles(item.id, s.subtitleLang) }
                .onSuccess { found ->
                    if (playing?.item?.id != item.id) return@onSuccess
                    val top = found.sortedByDescending { it.downloads ?: 0 }.take(30)
                    val items = top.map { r ->
                        MenuItem(r.name, listOf("sub-baixar", r.id), detail = listOfNotNull(r.provider, r.format?.uppercase(), r.downloads?.let { "$it downloads" }).joinToString(" · "))
                    }.ifEmpty { listOf(MenuItem("Nenhuma legenda encontrada", null)) }
                    sendMenu(m, "online", title, items + MenuItem("Voltar às legendas", listOf("abrir", "legendas")))
                }
                .onFailure { e ->
                    sendMenu(m, "online", title, listOf(MenuItem("Não foi possível procurar: ${e.friendly()}", null), MenuItem("Voltar às legendas", listOf("abrir", "legendas"))))
                }
        }
    }

    /** Baixa no servidor e carrega no vídeo que está tocando (o Jellyfin leva um instante para listar a nova legenda). */
    private fun downloadSubtitle(m: Mpv, subtitleId: String) {
        val c = playing ?: return
        val item = c.item ?: return
        m.command("show-text", "Baixando legenda…", "10000")
        scope.launch(Dispatchers.IO) {
            try {
                s.jellyfin.downloadSubtitle(item.id, subtitleId)
                repeat(6) { attempt ->
                    if (attempt > 0) delay(1500)
                    val info = runCatching { s.jellyfin.playbackInfo(item.id) }.getOrNull()
                    val src = info?.sources?.firstOrNull { it.id == c.sourceId } ?: info?.sources?.firstOrNull()
                    val fresh = src?.subtitles?.filter { it.isExternal && it.index !in c.addedSubs }.orEmpty()
                    if (fresh.isNotEmpty()) {
                        if (playing !== c) return@launch
                        fresh.forEachIndexed { i, st ->
                            val url = s.jellyfin.externalSubtitleUrl(item.id, c.sourceId, st)
                            if (m.command("sub-add", url, if (i == 0) "select" else "auto", st.label, st.language ?: "") >= 0) c.addedSubs += st.index
                        }
                        m.command("show-text", "Legenda aplicada", "2500")
                        return@launch
                    }
                }
                m.command("show-text", "A legenda foi baixada, mas o servidor ainda não a listou. Tente abrir o vídeo de novo.", "5000")
            } catch (e: Exception) {
                m.command("show-text", "Não foi possível baixar: ${e.friendly()}", "5000")
            }
        }
    }

    /** TV ao vivo: número digitado no teclado; sintoniza 1,5 s depois do último dígito. */
    private fun typeDigit(m: Mpv, d: String, live: PlayRequest.Live?) {
        val lv = live ?: return
        if (d.length != 1 || !d[0].isDigit()) return
        if (digits.length >= 5) digits = ""
        digits += d
        m.command("show-text", "Canal $digits-", "1600")
        digitJob?.cancel()
        digitJob = scope.launch {
            delay(1500)
            val typed = digits
            digits = ""
            val n = typed.toIntOrNull()
            val idx = lv.queue.indexOfFirst { it.number == typed || (n != null && it.number?.toIntOrNull() == n) }
            if (idx >= 0) playLive(lv.queue, idx) else m.command("show-text", "Canal $typed não encontrado", "2500")
        }
    }

    /** Quanto uma faixa se parece com a lembrada (0 = não serve). Forçada só combina com forçada. */
    private fun trackScore(choice: TrackChoice, t: Track, checkForced: Boolean): Int {
        if (checkForced && t.forced != choice.forced) return 0
        val sameLabel = t.title != null && t.title.equals(choice.label, ignoreCase = true)
        val wanted = baseLanguage(choice.language) ?: return if (sameLabel) 1 else 0
        if (baseLanguage(t.lang) != wanted) return 0
        val sameRegion = t.lang.equals(choice.language, ignoreCase = true)
        return 1 + (if (sameRegion) 1 else 0) + (if (sameLabel) 2 else 0)
    }

    /** Legenda e áudio escolhidos antes nesta série (ou no último vídeo), como no app Android. */
    private fun restoreTracks(m: Mpv, c: Current) {
        val item = c.item ?: return
        s.trackChoice("audio", item.seriesId)?.let { choice ->
            tracks(m, "audio").map { it to trackScore(choice, it, checkForced = false) }
                .filter { it.second > 0 }.maxByOrNull { it.second }
                ?.let { (t, _) -> if (!t.selected) m.set("aid", t.id) }
        }
        s.trackChoice("sub", item.seriesId)?.let { choice ->
            if (choice.off) { m.set("sid", "no"); return@let }
            tracks(m, "sub").map { it to trackScore(choice, it, checkForced = true) }
                .filter { it.second > 0 }.maxByOrNull { it.second }
                ?.let { (t, _) -> if (!t.selected) m.set("sid", t.id) }
        }
    }

    /** Trechos para pular e próximo episódio (rede: em segundo plano). */
    private fun loadExtras(m: Mpv, c: Current) {
        val item = c.item ?: return
        scope.launch(Dispatchers.IO) {
            val segs = runCatching { s.jellyfin.mediaSegments(item.id) }.getOrDefault(emptyList())
            if (playing !== c) return@launch
            val json = buildJsonArray {
                for (sg in segs) add(buildJsonObject {
                    put("type", sg.type)
                    put("start", sg.startTicks / 10_000_000.0)
                    put("end", sg.endTicks / 10_000_000.0)
                })
            }
            m.command("script-message", "sb-segmentos", json.toString())
            if (item.isEpisode) {
                val next = runCatching { s.jellyfin.nextEpisode(item) }.getOrNull()
                if (playing !== c) return@launch
                nextUp = next
                m.command("script-message", "sb-proximo", next?.episodeLabel.orEmpty())
            }
        }
    }

    private fun missingMpvMessage(): String {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val installed = System.getProperty("jpackage.app-path") != null
        return when {
            !windows -> "O componente de vídeo (libmpv) não está instalado. No terminal: sudo apt install libmpv2"
            // O arquivo está lá, mas o Windows recusou carregar: quase sempre falta o driver de vídeo
            LibMpv.dllPresent -> "O componente de vídeo não conseguiu iniciar neste computador. Instale ou atualize o driver " +
                "de vídeo (Windows Update > Atualizações opcionais, ou o site do fabricante) e reinicie. " +
                "Detalhe: ${LibMpv.loadError?.lineSequence()?.firstOrNull { "libmpv-2" in it } ?: LibMpv.loadError?.lineSequence()?.firstOrNull().orEmpty()}"
            installed -> "O componente de vídeo (libmpv-2.dll) não foi encontrado na pasta do programa. Reinstale o StreamBox."
            else -> "libmpv-2.dll não encontrada (procurada em: ${LibMpv.searched.ifEmpty { listOf("nenhuma pasta de recursos") }.joinToString()}). " +
                "Rodando pelo código-fonte: feche o app e rode gradlew.bat baixarLibmpv (o run também baixa sozinho)."
        }
    }
}
