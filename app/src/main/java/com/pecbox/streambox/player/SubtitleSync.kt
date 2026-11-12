package com.pecbox.streambox.player

import android.content.Context
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.TextOutput
import java.util.ArrayList
import kotlin.math.abs

/**
 * Sincronia da legenda: o renderizador de legendas "enxerga" o vídeo deslocado.
 * Positivo = legenda adiantada (aparece antes); negativo = atrasada (aparece depois).
 * Vale para legendas embutidas, as do servidor e as baixadas.
 */
@OptIn(UnstableApi::class)
object SubtitleSync {
    const val STEP_MS = 250L
    private const val LIMIT_MS = 60_000L

    /** Lido na thread de reprodução a cada quadro. */
    @Volatile var offsetMs = 0L
        private set

    fun set(ms: Long) { offsetMs = ms.coerceIn(-LIMIT_MS, LIMIT_MS) }

    fun label(ms: Long = offsetMs): String = when {
        ms == 0L -> "sincronizada"
        else -> "%s %s s".format(if (ms > 0) "adiantada" else "atrasada", "%.2f".format(abs(ms) / 1000.0).trimEnd('0').trimEnd(',', '.'))
    }

    /** Fábrica de renderizadores com a legenda deslocável (o resto igual ao padrão). */
    class Renderers(context: Context) : DefaultRenderersFactory(context) {
        override fun buildTextRenderers(
            context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>,
        ) {
            val start = out.size
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
            for (i in start until out.size) out[i] = Shifted(out[i])
        }
    }

    private class Shifted(renderer: Renderer) : ForwardingRenderer(renderer) {
        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            super.render((positionUs + offsetMs * 1000).coerceAtLeast(0), elapsedRealtimeUs)
        }
    }
}
