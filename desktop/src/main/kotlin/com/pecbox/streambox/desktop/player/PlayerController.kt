package com.pecbox.streambox.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.MediaSource
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.data.dataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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

    /** Tecla apertada com o foco na janela do app: repassa ao mpv (no Windows o mpv também recebe direto). */
    fun keypress(name: String) { mpv?.command("keypress", name) }

    /** Esc: sai da tela cheia; se já estiver em janela, volta (fecha o player), como o voltar do Android. */
    fun escape() {
        if (fullscreen) fullscreen = false else stop()
    }

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
        m.option("input-vo-keyboard", "yes")
        m.option("input-cursor", "yes")
        m.option("cursor-autohide", "1500")
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
        m.option("screenshot-directory", System.getProperty("user.home"))
        // Opções extras para testes: STREAMBOX_MPV_OPTS="vo=null,ao=null"
        System.getenv("STREAMBOX_MPV_OPTS")?.split(',')?.forEach { kv ->
            val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "yes" } }
            if (k.isNotBlank()) m.option(k.trim(), v.trim())
        }
    }

    /** Atalhos da TV ao vivo: ↑/PgUp = próximo canal, ↓/PgDn = anterior (igual ao app Android). */
    private fun defineLiveKeys(m: Mpv) {
        val keys = listOf("UP" to 1, "PGUP" to 1, "DOWN" to -1, "PGDWN" to -1)
            .joinToString("\n") { (k, d) -> "$k script-message sb-canal $d" }
        m.command("define-section", "streambox-live", keys, "force")
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
        m.command("script-message", "sb-info", if (c.isLive) "live" else "vod", title, subtitle)
    }

    private fun load(m: Mpv, c: Current, defaultUa: String) {
        sendInfo(m, c)
        m.set("force-media-title", c.title)
        m.set("start", if (c.startSeconds > 5) "+%.3f".format(java.util.Locale.US, c.startSeconds) else "none")
        m.set("user-agent", c.userAgent ?: defaultUa)
        m.set("referrer", c.referer ?: "")
        // No ao vivo as setas trocam de canal; nos vídeos voltam a avançar/voltar 1 minuto
        m.command(if (c.isLive) "enable-section" else "disable-section", "streambox-live")
        m.command("loadfile", c.url, "replace")
    }

    /** Legendas externas do Jellyfin (arquivos .srt/.ass ao lado do vídeo ou baixadas da internet). */
    private fun addExternalSubs(m: Mpv, c: Current) {
        val source = c.source ?: return
        val item = c.item ?: return
        for (st in source.subtitles.filter { it.isExternal }) {
            val url = s.jellyfin.externalSubtitleUrl(item.id, c.sourceId, st)
            m.command("sub-add", url, "auto", st.label, st.language ?: "")
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
        defineLiveKeys(m)
        val defaultUa = m.get("user-agent") ?: "libmpv"
        logLine("libmpv ${m.get("mpv-version")} · hwdec=${m.get("hwdec")}")

        var current: Current? = null
        var lastLive: PlayRequest.Live? = null   // canal atual (mesmo se não sintonizou), para ↑/↓
        var liveRetries = 0
        var lastPos = 0.0
        var lastReport = 0L
        var started = false
        var failure: String? = null

        /** Sai do item atual: avisa o Jellyfin e, se for canal do Jellyfin, fecha a transmissão. */
        fun leave(c: Current, ended: Boolean) {
            val id = c.reportId
            if (id != null && started) {
                val seconds = when {
                    c.isLive -> 0.0
                    ended -> c.item?.runTimeTicks?.div(10_000_000.0) ?: lastPos
                    else -> lastPos
                }
                val ticks = (seconds * 10_000_000).toLong()
                report { s.jellyfin.reportStopped(id, c.sourceId, ticks, c.playSession, c.method) }
            }
            c.liveStreamId?.let { live -> report { s.jellyfin.closeLiveStream(live) } }
            started = false
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
                    }
                }
                MpvEvent.FILE_LOADED -> {
                    val c = current ?: continue@loop
                    addExternalSubs(m, c)
                    sendInfo(m, c)   // o script pode ter carregado depois do primeiro aviso
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
                            val nextEp = runCatching { runBlocking { s.jellyfin.nextEpisode(c.item) } }.getOrNull()
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

        mpv = null
        pending = null
        m.destroy()          // antes de tirar a área de vídeo da tela
        log?.close()
        state = failure?.let { PlayerState.Failed(it) } ?: PlayerState.Idle
        fullscreen = false
        active = false
        scope.launch { onFinished() }
    }

    private fun missingMpvMessage(): String {
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
        val installed = System.getProperty("jpackage.app-path") != null
        return when {
            !windows -> "O componente de vídeo (libmpv) não está instalado. No terminal: sudo apt install libmpv2"
            installed -> "O componente de vídeo (libmpv-2.dll) não foi encontrado na pasta do programa. Reinstale o StreamBox."
            else -> "libmpv-2.dll não encontrada (procurada em: ${LibMpv.searched.ifEmpty { listOf("nenhuma pasta de recursos") }.joinToString()}). " +
                "Rodando pelo código-fonte: feche o app e rode gradlew.bat baixarLibmpv (o run também baixa sozinho)."
        }
    }
}
