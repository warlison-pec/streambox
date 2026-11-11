package com.pecbox.streambox.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.data.dataDir
import com.pecbox.streambox.desktop.player.LibMpv
import com.pecbox.streambox.desktop.player.Mpv
import com.pecbox.streambox.desktop.player.PlayerController
import com.pecbox.streambox.desktop.ui.Black
import com.pecbox.streambox.desktop.ui.LoginScreen
import com.pecbox.streambox.desktop.ui.Nav
import com.pecbox.streambox.desktop.ui.Shell
import com.pecbox.streambox.desktop.ui.StreamBoxTheme
import okio.Path.Companion.toOkioPath
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Diagnóstico sem interface: streambox --testar-mpv <arquivo ou URL>
    if (args.firstOrNull() == "--testar-mpv") {
        exitProcess(testMpv(args.getOrNull(1)))
    }
    val session = Session()
    application {
        val windowState = rememberWindowState(width = 1400.dp, height = 860.dp, placement = WindowPlacement.Maximized)
        val icon = remember { runCatching { BitmapPainter(useResource("streambox.png", ::loadImageBitmap)) }.getOrNull() }
        val scope = rememberCoroutineScope()
        val player = remember { PlayerController(session, scope) }
        val nav = remember { Nav() }
        Window(
            onCloseRequest = { player.stop(); exitApplication() },
            state = windowState,
            title = BuildConfig.APP_NAME,
            icon = icon,
            onPreviewKeyEvent = { e ->
                // Esc / Backspace fora de campos de texto: volta uma tela
                if (e.type == KeyEventType.KeyUp && e.key == Key.Escape) nav.back() else false
            },
        ) {
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { session.http })) }
                    .diskCache {
                        DiskCache.Builder()
                            .directory(File(dataDir, "imagens").toOkioPath())
                            .maxSizeBytes(300L * 1024 * 1024)
                            .build()
                    }
                    .build()
            }
            StreamBoxTheme {
                Surface(Modifier.fillMaxSize(), color = Black) {
                    if (session.loggedIn) Shell(session, player, nav) else LoginScreen(session)
                }
            }
        }
    }
}

/** Abre o mpv com a mesma configuração do app e mostra o que foi decodificado (sem precisar de login). */
private fun testMpv(target: String?): Int {
    if (target == null) {
        println("uso: --testar-mpv <arquivo ou URL>")
        return 2
    }
    println("pasta de recursos: ${System.getProperty("compose.application.resources.dir")}")
    val lib = LibMpv.instance ?: run {
        println("libmpv não encontrado (pastas: ${LibMpv.searched}):\n${LibMpv.loadError}")
        return 1
    }
    val m = Mpv(lib)
    m.option("terminal", "no")
    m.option("hwdec", "auto-safe")
    m.option("osc", "yes")
    m.option("force-window", "yes")
    m.option("idle", "no")
    m.option("alang", "por,pt,eng")
    m.option("slang", "por,pt,eng")
    System.getenv("STREAMBOX_MPV_OPTS")?.split(',')?.forEach { kv ->
        val (k, v) = kv.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "yes" } }
        if (k.isNotBlank()) m.option(k.trim(), v.trim())
    }
    m.initialize()
    m.requestLog("warn")
    println("libmpv: ${m.get("mpv-version")}")
    m.command("loadfile", target)
    var code = 0
    var reported = false
    val seekTo = System.getenv("STREAMBOX_TESTE_SEGUNDOS")?.toDoubleOrNull()
    while (true) {
        val ev = m.waitEvent(1.0)
        when (ev.id) {
            2 -> println("log ${ev.log}")
            8 -> {
                println("carregado: ${m.get("file-format")} · ${m.get("duration")} s")
                println("vídeo: ${m.get("video-codec")} ${m.get("width")}x${m.get("height")} · hwdec=${m.get("hwdec-current")}")
                println("áudio: ${m.get("audio-codec-name")} ${m.get("audio-params/channel-count")} canais · faixa=${m.get("current-tracks/audio/lang")}")
                println("legenda: ${m.get("current-tracks/sub/codec")} · faixa=${m.get("current-tracks/sub/lang")} · total de faixas=${m.get("track-list/count")}")
                reported = true
            }
            7 -> {
                println("fim: motivo=${ev.endReason} erro=${ev.endError?.let { m.errorString(it) }}")
                if (ev.endReason == 4) code = 1
                break
            }
            1 -> break
        }
        val pos = m.double("time-pos")
        if (reported && seekTo != null && pos != null && pos >= seekTo) {
            println("posição ${"%.1f".format(pos)} s, quadros perdidos=${m.get("frame-drop-count")} · ok")
            break
        }
    }
    m.destroy()
    return code
}
