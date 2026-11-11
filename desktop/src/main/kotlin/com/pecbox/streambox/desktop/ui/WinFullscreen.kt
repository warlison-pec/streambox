package com.pecbox.streambox.desktop.ui

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Window

/**
 * Tela cheia de verdade no Windows: só a imagem, sem barra de título, bordas nem barra de tarefas.
 *
 * O "Fullscreen" do Compose no Windows só estica a janela decorada até o tamanho da tela (a barra de título
 * e os botões continuam lá). Recriar a janela sem decoração destruiria a área nativa onde o mpv desenha,
 * então aqui tiramos a moldura direto na janela do Windows e a esticamos sobre o monitor inteiro,
 * do mesmo jeito que os navegadores fazem. Uma janela cobrindo o monitor todo faz o Windows esconder a barra de tarefas.
 */
object WinFullscreen {
    val supported: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun GetWindowRect(hwnd: Pointer, rect: Rect): Boolean
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: MonitorInfo): Boolean
        fun IsZoomed(hwnd: Pointer): Boolean
        fun SendMessageW(hwnd: Pointer, msg: Int, wParam: Long, lParam: Long): Long
    }

    @Structure.FieldOrder("left", "top", "right", "bottom")
    class Rect : Structure() {
        @JvmField var left = 0
        @JvmField var top = 0
        @JvmField var right = 0
        @JvmField var bottom = 0
    }

    @Structure.FieldOrder("cbSize", "rcMonitor", "rcWork", "dwFlags")
    class MonitorInfo : Structure() {
        @JvmField var cbSize = size()
        @JvmField var rcMonitor = Rect()
        @JvmField var rcWork = Rect()
        @JvmField var dwFlags = 0
    }

    private const val GWL_STYLE = -16
    private const val GWL_EXSTYLE = -20
    private const val WS_CAPTION = 0x00C00000L
    private const val WS_THICKFRAME = 0x00040000L
    private const val WS_EX_DLGMODALFRAME = 0x00000001L
    private const val WS_EX_WINDOWEDGE = 0x00000100L
    private const val WS_EX_CLIENTEDGE = 0x00000200L
    private const val WS_EX_STATICEDGE = 0x00020000L
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
    private const val MONITOR_DEFAULTTONEAREST = 2
    private const val WM_SYSCOMMAND = 0x0112
    private const val SC_MAXIMIZE = 0xF030L
    private const val SC_RESTORE = 0xF120L

    private val user32: User32? by lazy {
        if (!supported) null else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    private class Saved(val style: Long, val exStyle: Long, val rect: Rect, val maximized: Boolean)

    private var saved: Saved? = null

    val active: Boolean get() = saved != null

    /** Entra na tela cheia. Devolve false se não deu (aí quem chamou usa a tela cheia do Compose). */
    fun enter(window: Window): Boolean {
        val u = user32 ?: return false
        if (saved != null) return true
        return runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return false
            val maximized = u.IsZoomed(hwnd)
            // Maximizada não aceita outro tamanho: restaura antes (e maximiza de novo ao sair)
            if (maximized) u.SendMessageW(hwnd, WM_SYSCOMMAND, SC_RESTORE, 0)
            val style = u.GetWindowLongPtrW(hwnd, GWL_STYLE)
            val exStyle = u.GetWindowLongPtrW(hwnd, GWL_EXSTYLE)
            val rect = Rect().also { u.GetWindowRect(hwnd, it) }
            saved = Saved(style, exStyle, rect, maximized)

            u.SetWindowLongPtrW(hwnd, GWL_STYLE, style and (WS_CAPTION or WS_THICKFRAME).inv())
            u.SetWindowLongPtrW(
                hwnd, GWL_EXSTYLE,
                exStyle and (WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE).inv()
            )
            val monitor = u.MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) ?: error("sem monitor")
            val info = MonitorInfo()
            check(u.GetMonitorInfoW(monitor, info)) { "GetMonitorInfo falhou" }
            val m = info.rcMonitor
            u.SetWindowPos(hwnd, null, m.left, m.top, m.right - m.left, m.bottom - m.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            relayout(window)
            true
        }.getOrElse {
            System.err.println("tela cheia (Windows) falhou: ${it.message}")
            exit(window)
            false
        }
    }

    /** Sai da tela cheia: moldura, tamanho e posição de antes (e maximizada, se estava). */
    fun exit(window: Window) {
        val u = user32 ?: return
        val s = saved ?: return
        saved = null
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            u.SetWindowLongPtrW(hwnd, GWL_STYLE, s.style)
            u.SetWindowLongPtrW(hwnd, GWL_EXSTYLE, s.exStyle)
            val r = s.rect
            u.SetWindowPos(hwnd, null, r.left, r.top, r.right - r.left, r.bottom - r.top, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED)
            if (s.maximized) u.SendMessageW(hwnd, WM_SYSCOMMAND, SC_MAXIMIZE, 0)
            relayout(window)
        }.onFailure { System.err.println("sair da tela cheia (Windows) falhou: ${it.message}") }
    }

    /** A moldura mudou: o Java recalcula as margens da janela e redistribui o conteúdo. */
    private fun relayout(window: Window) {
        java.awt.EventQueue.invokeLater {
            window.invalidate()
            window.validate()
            window.repaint()
        }
    }
}
