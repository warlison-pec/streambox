package com.pecbox.streambox.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pecbox.streambox.R

/**
 * Aviso flutuante do player (avançar / recuar / pausa / número do canal).
 * Cartão escuro com ícone vetorial no círculo laranja, tempo atual, duração
 * e uma barra fina de progresso — no lugar dos emojis ⏩ ⏸, que no Android TV
 * viram ícones coloridos de teclado.
 */
class PlayerOsd(context: Context) : LinearLayout(context) {

    private val accent = 0xFFFF7A00.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val iconWrap = FrameLayout(context)
    private val icon = ImageView(context)
    private val label = TextView(context)       // "+30 s", "PAUSADO", "CANAL"
    private val time = TextView(context)
    private val total = TextView(context)
    private val bar = ProgressLine(context)
    private var hiding = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(dp(12), dp(10), dp(22), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(30).toFloat()
            setColor(0xE6101010.toInt())
            setStroke(dp(1), 0x26FFFFFF)
        }
        elevation = dp(8).toFloat()

        icon.setColorFilter(Color.WHITE)
        iconWrap.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        iconWrap.addView(icon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        addView(iconWrap, LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })

        label.apply {
            setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f
        }
        time.apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"            // dígitos de largura fixa: o tempo não "pula"
        }
        total.apply {
            setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            fontFeatureSettings = "tnum"
        }
        val timeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(time)
            addView(total, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6); bottomMargin = dp(2) })
        }
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label)
            addView(timeRow)
            addView(bar, LayoutParams(dp(240), dp(4)).apply { topMargin = dp(6) })
        }
        addView(column)
    }

    /** ←/→: direção, posição de destino, duração e quanto já andou desde o primeiro toque. */
    fun showSeek(forward: Boolean, pos: Long, dur: Long?, deltaMs: Long) {
        val s = Math.abs(deltaMs) / 1000
        val step = if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "$s s"
        render(if (forward) R.drawable.ic_sb_forward else R.drawable.ic_sb_rewind,
            (if (deltaMs >= 0) "+" else "−") + step, pos, dur)
    }

    fun showPause(paused: Boolean, pos: Long, dur: Long?) =
        render(if (paused) R.drawable.ic_sb_pause else R.drawable.ic_sb_play,
            if (paused) "PAUSADO" else "REPRODUZINDO", pos, dur)

    /** Número do canal sendo digitado na TV ao vivo. */
    fun showChannel(digits: String) {
        iconWrap.visibility = View.GONE
        bar.visibility = View.GONE
        total.visibility = View.GONE
        label.text = "CANAL"
        time.text = "$digits–"
        appear()
    }

    private fun render(iconRes: Int, tag: String, pos: Long, dur: Long?) {
        iconWrap.visibility = View.VISIBLE
        icon.setImageResource(iconRes)
        label.text = tag
        time.text = fmt(pos)
        if (dur != null && dur > 0) {
            total.visibility = View.VISIBLE; total.text = "/ " + fmt(dur)
            bar.visibility = View.VISIBLE; bar.fraction = (pos.toFloat() / dur).coerceIn(0f, 1f)
        } else {
            total.visibility = View.GONE; bar.visibility = View.GONE
        }
        appear()
    }

    private fun appear() {
        if (visibility == View.VISIBLE && !hiding) return
        hiding = false
        animate().cancel()
        if (visibility != View.VISIBLE) { alpha = 0f; translationY = -dp(10).toFloat(); scaleX = 0.96f; scaleY = 0.96f }
        visibility = View.VISIBLE
        animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    /** Some com fade (em vez de desaparecer de uma vez). */
    fun hide() {
        if (visibility != View.VISIBLE || hiding) return
        hiding = true
        animate().cancel()
        animate().alpha(0f).translationY(-dp(6).toFloat()).setDuration(200)
            .withEndAction { if (hiding) { visibility = View.GONE; hiding = false } }.start()
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    /** Barra de progresso fina com cantos arredondados. */
    private inner class ProgressLine(context: Context) : View(context) {
        var fraction = 0f
            set(v) { field = v; invalidate() }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat(); val rad = h / 2
            r.set(0f, 0f, width.toFloat(), h); canvas.drawRoundRect(r, rad, rad, track)
            r.right = (width * fraction).coerceAtLeast(h); canvas.drawRoundRect(r, rad, rad, fill)
        }
    }
}
