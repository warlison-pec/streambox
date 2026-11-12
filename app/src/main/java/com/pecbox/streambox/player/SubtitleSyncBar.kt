package com.pecbox.streambox.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Barra de sincronia da legenda, no estilo do volume: o traço do meio é o tempo original da legenda,
 * à esquerda fica adiantada e à direita atrasada. Mostra até ±[RANGE_MS]; além disso o marcador
 * para na ponta, mas o valor continua aparecendo no texto.
 */
class SubtitleSyncBar(context: Context) : View(context) {
    /** Valor atual: positivo = adiantada (como em [SubtitleSync.offsetMs]). */
    var offsetMs = 0L
        set(v) { field = v; invalidate() }

    /** Linha de ajuda no rodapé (TV: teclas; celular: toque). */
    var hintText = ""

    /** Toque no celular: metade esquerda adianta, metade direita atrasa. */
    var onTap: ((advance: Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val orange = 0xFFFF7A00.toInt()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0121212.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND }
    private val minorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = dp(1.5f) }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = orange; style = Paint.Style.STROKE; strokeWidth = dp(3f) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBDBDBD.toInt(); textSize = dp(13f); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.1f }
    private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(22f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val side = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBDBDBD.toInt(); textSize = dp(13f) }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(11f); textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = dp(12f); textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(560f).toInt())
        setMeasuredDimension(w, dp(150f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, dp(18f), dp(18f), bg)
        rect.inset(dp(0.5f), dp(0.5f))
        canvas.drawRoundRect(rect, dp(18f), dp(18f), border)

        val pad = dp(28f)
        canvas.drawText("SINCRONIA DA LEGENDA", pad, dp(30f), title)
        canvas.drawText(valueText(), w / 2, dp(62f), value)

        // Trilho com o traço do meio (original) e marcas a cada segundo
        val left = pad
        val right = w - pad
        val cx = (left + right) / 2
        val y = dp(92f)
        rect.set(left, y - dp(3f), right, y + dp(3f))
        canvas.drawRoundRect(rect, dp(3f), dp(3f), track)
        val perSecond = (right - left) / 2 / (RANGE_MS / 1000f)
        for (s in 1 until (RANGE_MS / 1000).toInt()) {
            canvas.drawLine(cx - s * perSecond, y - dp(6f), cx - s * perSecond, y + dp(6f), minorTick)
            canvas.drawLine(cx + s * perSecond, y - dp(6f), cx + s * perSecond, y + dp(6f), minorTick)
        }
        // Direita = atrasada (offset negativo)
        val fraction = (-offsetMs).coerceIn(-RANGE_MS, RANGE_MS) / RANGE_MS.toFloat()
        val x = cx + fraction * (right - cx)
        rect.set(minOf(cx, x), y - dp(3f), maxOf(cx, x), y + dp(3f))
        canvas.drawRect(rect, fill)
        canvas.drawLine(cx, y - dp(13f), cx, y + dp(13f), tick)
        canvas.drawCircle(x, y, dp(10f), knob)
        canvas.drawCircle(x, y, dp(10f), knobRing)

        val labelY = y + dp(30f)
        side.textAlign = Paint.Align.LEFT
        canvas.drawText("◀  Adiantar", left, labelY, side)
        side.textAlign = Paint.Align.RIGHT
        canvas.drawText("Atrasar  ▶", right, labelY, side)
        canvas.drawText("original", cx, labelY, center)
        canvas.drawText(hintText, cx, h - dp(10f), hint)
    }

    private fun valueText(): String {
        if (offsetMs == 0L) return "No tempo original"
        val secs = "%.2f".format(abs(offsetMs) / 1000.0).trimEnd('0').trimEnd(',', '.')
        return (if (offsetMs > 0) "Adiantada " else "Atrasada ") + secs + " s"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTap?.invoke(event.x < width / 2f)
        return true
    }

    companion object {
        const val RANGE_MS = 5_000L
    }
}
