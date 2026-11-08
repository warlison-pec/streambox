package com.pecbox.streambox.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import androidx.media3.common.text.Cue

/**
 * Legenda com contorno grosso. O contorno do Media3 tem espessura fixa (2 dp) e fica fino
 * em letras grandes; aqui ele acompanha o tamanho da letra (cerca de 15%) e ganha uma sombra
 * suave por baixo, para continuar legível em cenas claras.
 *
 * Desenha só legendas de texto (SRT, VTT, ASS…). Legendas em imagem (PGS/VobSub) continuam
 * com o SubtitleView padrão.
 */
class OutlineSubtitles(context: Context) : View(context) {
    private var bottomCues: List<CharSequence> = emptyList()
    private var topCues: List<CharSequence> = emptyList()

    var textColor = Color.WHITE
        set(v) { field = v; invalidate() }
    /** Altura da letra em fração da altura do vídeo (mesma conta do SubtitleView). */
    var sizeFraction = 0.0533f
        set(v) { field = v; invalidate() }
    var bottomPaddingFraction = 0.08f
        set(v) { field = v; invalidate() }

    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val stroke = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }

    fun setCues(cues: List<Cue>) {
        val text = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
        // Cues posicionadas na parte de cima da tela (ex.: placas, falas sobre a legenda queimada)
        val isTop = { c: Cue -> c.lineType == Cue.LINE_TYPE_FRACTION && c.line != Cue.DIMEN_UNSET && c.line < 0.4f }
        val newTop = text.filter(isTop).map { clean(it.text!!) }
        val newBottom = text.filterNot(isTop).map { clean(it.text!!) }
        if (newTop == topCues && newBottom == bottomCues) return
        topCues = newTop; bottomCues = newBottom
        invalidate()
    }

    /** Mantém só negrito/itálico/sublinhado; cores embutidas sairiam também no contorno. */
    private fun clean(src: CharSequence): CharSequence {
        val out = SpannableStringBuilder(src.toString().trim())
        if (src is Spanned) {
            val len = out.length
            src.getSpans(0, src.length, Any::class.java).forEach { span ->
                val s = src.getSpanStart(span).coerceIn(0, len)
                val e = src.getSpanEnd(span).coerceIn(0, len)
                if (s >= e) return@forEach
                when (span) {
                    is StyleSpan -> out.setSpan(StyleSpan(span.style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    is UnderlineSpan -> out.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        if (bottomCues.isEmpty() && topCues.isEmpty()) return
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val textSize = sizeFraction * h
        fill.textSize = textSize
        fill.color = textColor
        stroke.textSize = textSize
        stroke.strokeWidth = textSize * 0.15f
        stroke.setShadowLayer(textSize * 0.10f, 0f, textSize * 0.04f, 0xCC000000.toInt())
        val boxW = (w * 0.9f).toInt()
        val left = paddingLeft + (w - boxW) / 2f

        if (bottomCues.isNotEmpty()) {
            val text = join(bottomCues)
            val (outline, body) = layouts(text, boxW)
            val y = paddingTop + h - h * bottomPaddingFraction - body.height
            draw(canvas, outline, body, left, y)
        }
        if (topCues.isNotEmpty()) {
            val text = join(topCues)
            val (outline, body) = layouts(text, boxW)
            draw(canvas, outline, body, left, paddingTop + h * 0.06f)
        }
    }

    private fun join(list: List<CharSequence>): CharSequence {
        val b = SpannableStringBuilder()
        list.forEachIndexed { i, t -> if (i > 0) b.append('\n'); b.append(t) }
        return b
    }

    private fun layouts(text: CharSequence, width: Int): Pair<StaticLayout, StaticLayout> {
        fun make(p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(true)
            .build()
        return make(stroke) to make(fill)
    }

    private fun draw(canvas: Canvas, outline: StaticLayout, body: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        outline.draw(canvas)
        body.draw(canvas)
        canvas.restore()
    }
}
