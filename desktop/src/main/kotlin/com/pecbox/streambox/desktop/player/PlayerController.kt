package com.pecbox.streambox.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.MediaSource
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.data.dataDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.PrintWriter
import java.time.LocalTime
import java.util.UUID

sealed interface PlayerState {
    data object Idle : PlayerState
    data class Opening(val title: String) : PlayerState
    data class Playing(val title: String) : PlayerState
    data class Failed(val message: String) : PlayerState
}

/**
 * Reprodução com o mpv: sempre o arquivo original (Direct Play), nada de conversão no servidor.
 * O mpv abre a própria janela (funciona igual no Windows, no X11 e no Wayland) com os controles na tela.
 *
 * Uma thread por sessão de reprodução lê os eventos do mpv, envia o progresso ao Jellyfin
 * e, ao terminar um episódio, emenda o próximo na mesma janela.
 */
class PlayerController(private val s: Session, private val scope: CoroutineScope) {

    var state by mutableStateOf<PlayerState>(PlayerState.Idle)
        private set

    /** Chamado quando a janela do player fecha (a tela inicial atualiza "Continuar assistindo"). */
    var onFinished: () -> Unit = {}

    @Volatile private var mpv: Mpv? = null

    private class Current(
        val item: Item,
        val source: MediaSource?,
        val sourceId: String,
        val playSession: String,
        val url: String,
        val startSeconds: Double,
    )

    fun play(item: Item, fromStart: Boolean = false) {
        if (mpv != null) {
            // Já tem uma janela aberta: troca o vídeo nela
            pending = item to fromStart
            mpv?.command("stop")
            return
        }
        val lib = LibMpv.instance
        if (lib == null) {
            state = PlayerState.Failed(missingMpvMessage())
            return
        }
        state = PlayerState.Opening(title(item))
        Thread({ runSession(lib, item, fromStart) }, "mpv-eventos").apply { isDaemon = true }.start()
    }

    fun stop() {
        mpv?.command("quit")
    }

    fun dismissError() {
        if (state is PlayerState.Failed) state = PlayerState.Idle
    }

    @Volatile private var pending: Pair<Item, Boolean>? = null

    private fun title(item: Item): String =
        if (item.isEpisode) "${item.seriesName ?: ""} · ${item.episodeLabel}".trim(' ', '·') else item.name

    private fun prepare(item: Item, fromStart: Boolean): Current {
        val jf = s.jellyfin
        val info = runCatching { runBlocking { jf.playbackInfo(item.id) } }.getOrNull()
        val source = info?.sources?.firstOrNull()
        val sourceId = source?.id?.ifBlank { null } ?: item.id
        val resumeTicks = item.userData?.positionTicks ?: 0L
        return Current(
            item = item,
            source = source,
            sourceId = sourceId,
            playSession = info?.playSessionId ?: UUID.randomUUID().toString().replace("-", ""),
            url = jf.directStreamUrl(item.id, sourceId),
            startSeconds = if (fromStart) 0.0 else resumeTicks / 10_000_000.0,
        )
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
        m.option("fullscreen", if (s.fullscreen) "yes" else "no")
        m.option("autofit-larger", "90%x90%")
        m.option("title", "\${media-title} · StreamBox")
        m.option("osc", "yes")
        m.option("script-opts", "osc-layout=bottombar,osc-seekbarstyle=bar,osc-hidetimeout=1500")
        m.option("input-default-bindings", "yes")
        m.option("input-vo-keyboard", "yes")
        m.option("ytdl", "no")
        m.option("cache", "yes")
        m.option("demuxer-max-bytes", "300MiB")
        m.option("demuxer-max-back-bytes", "100MiB")
        m.option("network-timeout", "60")
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

    private fun load(m: Mpv, c: Current) {
        m.set("force-media-title", title(c.item))
        m.set("start", if (c.startSeconds > 5) "+%.3f".format(java.util.Locale.US, c.startSeconds) else "none")
        m.command("loadfile", c.url, "replace")
    }

    /** Legendas externas do Jellyfin (arquivos .srt/.ass ao lado do vídeo ou baixadas da internet). */
    private fun addExternalSubs(m: Mpv, c: Current) {
        val source = c.source ?: return
        for (st in source.subtitles.filter { it.isExternal }) {
            val url = s.jellyfin.externalSubtitleUrl(c.item.id, c.sourceId, st)
            m.command("sub-add", url, "auto", st.label, st.language ?: "")
        }
    }

    private fun report(block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) { runCatching { block() } }
    }

    private fun runSession(lib: LibMpv, first: Item, firstFromStart: Boolean) {
        val log = runCatching { PrintWriter(File(dataDir, "player.log").bufferedWriter(), true) }.getOrNull()
        fun logLine(t: String) = log?.println("${LocalTime.now().withNano(0)} $t")
        val m = try {
            Mpv(lib).also { configure(it); it.initialize() }
        } catch (e: Throwable) {
            state = PlayerState.Failed("Não foi possível iniciar o player: ${e.message}")
            log?.close()
            return
        }
        mpv = m
        m.requestLog("warn")
        logLine("libmpv ${m.get("mpv-version")} · hwdec=${m.get("hwdec")}")

        var current: Current? = null
        var lastPos = 0.0
        var paused = false
        var lastReport = 0L
        var failure: String? = null

        fun start(item: Item, fromStart: Boolean) {
            state = PlayerState.Opening(title(item))
            val c = prepare(item, fromStart)
            current = c
            lastPos = c.startSeconds
            logLine("abrindo ${item.id} (${item.name}) a partir de ${c.startSeconds.toInt()}s")
            load(m, c)
        }

        fun stopped(c: Current, ended: Boolean) {
            val seconds = if (ended) (c.item.runTimeTicks?.div(10_000_000.0) ?: lastPos) else lastPos
            val ticks = (seconds * 10_000_000).toLong()
            report { s.jellyfin.reportStopped(c.item.id, c.sourceId, ticks, c.playSession) }
        }

        start(first, firstFromStart)
        loop@ while (true) {
            val ev = m.waitEvent(1.0)
            when (ev.id) {
                MpvEvent.SHUTDOWN -> {
                    current?.let { stopped(it, ended = false) }
                    current = null
                    break@loop
                }
                MpvEvent.LOG_MESSAGE -> ev.log?.let { logLine(it) }
                MpvEvent.FILE_LOADED -> {
                    val c = current ?: continue@loop
                    addExternalSubs(m, c)
                    state = PlayerState.Playing(title(c.item))
                    logLine("tocando: vídeo=${m.get("video-codec")} ${m.get("width")}x${m.get("height")} áudio=${m.get("audio-codec-name")} hwdec=${m.get("hwdec-current")}")
                    val tracks = m.get("track-list/count")?.toIntOrNull() ?: 0
                    for (t in 0 until tracks) {
                        val p = "track-list/$t"
                        logLine(
                            "  faixa ${m.get("$p/type")} #${m.get("$p/id")} ${m.get("$p/codec")} lang=${m.get("$p/lang")} " +
                                (if (m.flag("$p/external")) "externa " else "") + (if (m.flag("$p/selected")) "[ativa]" else "")
                        )
                    }
                    val ticks = (c.startSeconds * 10_000_000).toLong()
                    report { s.jellyfin.reportStart(c.item.id, c.sourceId, ticks, c.playSession) }
                    lastReport = System.currentTimeMillis()
                }
                MpvEvent.END_FILE -> {
                    val c = current
                    current = null
                    val reason = ev.endReason
                    logLine("fim do arquivo: motivo=$reason erro=${ev.endError}")
                    if (c != null) stopped(c, ended = reason == MpvEndReason.EOF)
                    val next = pending
                    pending = null
                    when {
                        // Pedido de outro vídeo enquanto a janela estava aberta
                        next != null -> start(next.first, next.second)
                        reason == MpvEndReason.ERROR -> {
                            val err = ev.endError?.let { m.errorString(it) } ?: "erro desconhecido"
                            failure = "Não foi possível tocar \"${c?.item?.name ?: "o vídeo"}\" ($err). " +
                                "Detalhes em ${File(dataDir, "player.log").absolutePath}"
                            m.command("quit")
                        }
                        reason == MpvEndReason.EOF && c != null && c.item.isEpisode -> {
                            val nextEp = runCatching { runBlocking { s.jellyfin.nextEpisode(c.item) } }.getOrNull()
                            if (nextEp != null) {
                                m.command("show-text", "Próximo: ${nextEp.episodeLabel}", "4000")
                                start(nextEp, fromStart = true)
                            } else m.command("quit")
                        }
                        reason == MpvEndReason.EOF || reason == MpvEndReason.STOP -> m.command("quit")
                    }
                }
            }
            // Posição atual e progresso a cada 10 s
            val c = current ?: continue
            m.double("time-pos")?.let { lastPos = it }
            paused = m.flag("pause")
            val now = System.currentTimeMillis()
            if (state is PlayerState.Playing && now - lastReport >= 10_000) {
                lastReport = now
                val ticks = (lastPos * 10_000_000).toLong()
                val p = paused
                report { s.jellyfin.reportProgress(c.item.id, c.sourceId, ticks, p, c.playSession) }
            }
        }

        mpv = null
        m.destroy()
        log?.close()
        state = failure?.let { PlayerState.Failed(it) } ?: PlayerState.Idle
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
