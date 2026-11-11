package com.pecbox.streambox.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import com.pecbox.streambox.desktop.player.PlayerController
import com.sun.jna.Native
import java.awt.Canvas
import java.awt.Cursor
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.image.BufferedImage
import kotlin.math.sign

/**
 * Área onde o mpv desenha o vídeo, dentro da janela do app. É um componente nativo (heavyweight):
 * fica por cima de tudo, então os controles são desenhados pelo próprio mpv (streambox_osc.lua).
 *
 * O mouse é tratado aqui e repassado ao script dos controles: o mpv embutido não recebe cliques
 * (no Windows ele desativa a própria janela; no X11 o app pede para ele não pegar o mouse).
 */
@Composable
fun VideoSurface(player: PlayerController, modifier: Modifier = Modifier) {
    val hideCursor = !player.controlsVisible
    SwingPanel(
        background = Color.Black,
        modifier = modifier,
        factory = {
            object : Canvas() {
                init {
                    background = java.awt.Color.BLACK
                    isFocusable = false   // o foco fica na janela do app, que repassa as teclas ao mpv
                    val mouse = object : MouseAdapter() {
                        override fun mouseMoved(e: MouseEvent) = player.mouseMove(e.x, e.y, width, height)
                        override fun mouseDragged(e: MouseEvent) = player.mouseMove(e.x, e.y, width, height)
                        override fun mouseExited(e: MouseEvent) = player.mouseLeave()
                        override fun mousePressed(e: MouseEvent) {
                            if (e.button != MouseEvent.BUTTON1 || e.clickCount > 2) return
                            player.mouseClick(e.x, e.y, width, height, e.clickCount)
                        }
                        override fun mouseWheelMoved(e: MouseWheelEvent) {
                            val d = e.wheelRotation.sign
                            if (d != 0) player.mouseWheel(d)
                        }
                    }
                    addMouseListener(mouse)
                    addMouseMotionListener(mouse)
                    addMouseWheelListener(mouse)
                }

                override fun addNotify() {
                    super.addNotify()
                    player.attachSurface(Native.getComponentID(this))
                }

                override fun removeNotify() {
                    player.detachSurface()
                    super.removeNotify()
                }
            }
        },
        update = { canvas -> canvas.cursor = if (hideCursor) blankCursor else Cursor.getDefaultCursor() },
    )
}

/** Cursor invisível (controles escondidos durante o vídeo). */
private val blankCursor: Cursor by lazy {
    Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "vazio")
}

/** Repassa a tecla ao mpv com o nome que ele usa (input.conf). Devolve true se tratou. */
fun forwardKeyToPlayer(e: KeyEvent, player: PlayerController): Boolean {
    if (e.type != KeyEventType.KeyDown) return true
    val name = when (e.key) {
        Key.Escape -> { player.escape(); return true }
        Key.F11 -> { player.fullscreen = !player.fullscreen; return true }
        Key.Spacebar -> "SPACE"
        Key.DirectionLeft -> "LEFT"
        Key.DirectionRight -> "RIGHT"
        Key.DirectionUp -> "UP"
        Key.DirectionDown -> "DOWN"
        Key.PageUp -> "PGUP"
        Key.PageDown -> "PGDWN"
        Key.Enter, Key.NumPadEnter -> "ENTER"
        Key.Backspace -> "BS"
        Key.MoveHome -> "HOME"
        Key.MoveEnd -> "END"
        else -> {
            val cp = e.utf16CodePoint
            if (cp >= 33 && cp != 127) String(Character.toChars(cp)) else null
        }
    }
    if (name != null) player.keypress(name)
    return true
}
