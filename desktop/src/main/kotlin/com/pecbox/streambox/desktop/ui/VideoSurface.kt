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

/**
 * Área onde o mpv desenha o vídeo, dentro da janela do app. É um componente nativo (heavyweight):
 * fica por cima de tudo, então os controles são desenhados pelo próprio mpv (streambox_osc.lua).
 */
@Composable
fun VideoSurface(player: PlayerController, modifier: Modifier = Modifier) {
    SwingPanel(
        background = Color.Black,
        modifier = modifier,
        factory = {
            object : Canvas() {
                init {
                    background = java.awt.Color.BLACK
                    isFocusable = false   // o foco fica na janela do app, que repassa as teclas ao mpv
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
    )
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
