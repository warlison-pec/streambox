package com.pecbox.streambox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted

private const val DOUBLE_BACK_MS = 2500L

/**
 * Voltar no Início: o 1º toque avisa, o 2º (em até 2,5 s) pergunta se quer sair.
 * "Sair" fecha o app de verdade (some também da lista de recentes), em vez de só ir para o fundo.
 */
@Composable
fun ExitOnDoubleBack(enabled: Boolean) {
    val ctx = LocalContext.current
    var lastBack by remember { mutableLongStateOf(0L) }
    var ask by remember { mutableStateOf(false) }

    BackHandler(enabled = enabled && !ask) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack <= DOUBLE_BACK_MS) {
            lastBack = 0L
            ask = true
        } else {
            lastBack = now
            Toast.makeText(ctx, "Aperte Voltar de novo para sair", Toast.LENGTH_SHORT).show()
        }
    }

    if (ask) ExitDialog(
        onStay = { ask = false },
        onExit = { ask = false; ctx.findActivity()?.finishAndRemoveTask() },
    )
}

@Composable
private fun ExitDialog(onStay: () -> Unit, onExit: () -> Unit) {
    val focus = remember { FocusRequester() }
    Dialog(onDismissRequest = onStay, properties = DialogProperties(dismissOnClickOutside = true, dismissOnBackPress = true)) {
        Column(
            Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = Orange, modifier = Modifier.size(44.dp))
            Text("Fechar o StreamBox?", color = TextMain, style = MaterialTheme.typography.titleLarge)
            Text(
                "O app será fechado. Voltar mantém você aqui.",
                color = TextMuted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SbButton("Continuar", onClick = onStay, primary = false, modifier = Modifier.weight(1f))
                SbButton("Sair", onClick = onExit, modifier = Modifier.weight(1f).focusRequester(focus))
            }
        }
    }
    // No controle: OK já confirma (a pessoa apertou Voltar duas vezes de propósito)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
