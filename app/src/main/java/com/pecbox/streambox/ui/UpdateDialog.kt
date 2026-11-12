package com.pecbox.streambox.ui

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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
import com.pecbox.streambox.BuildConfig
import com.pecbox.streambox.data.AppUpdate
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.Surface3
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted

/** Caixa na frente da tela quando há versão nova: "Atualizar agora" baixa o APK e abre o instalador. */
@Composable
fun UpdateDialog() {
    val update = AppUpdate.available ?: return
    val ctx = LocalContext.current
    val busy = AppUpdate.downloading
    val focus = remember { FocusRequester() }

    Dialog(
        onDismissRequest = { AppUpdate.dismiss() },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = !busy),
    ) {
        Column(
            Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Surface).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Orange, modifier = Modifier.size(44.dp))
            Text("Nova versão disponível", color = TextMain, style = MaterialTheme.typography.titleLarge)
            Text(
                "O StreamBox ${update.version} já está pronto (você está na ${BuildConfig.VERSION_NAME}). " +
                    "Atualize para ter as correções e novidades.",
                color = TextMuted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
            AppUpdate.status?.let { status ->
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange, trackColor = Surface3)
                Text(status, color = if (busy) TextMain else Orange, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SbButton("Depois", onClick = { AppUpdate.dismiss() }, primary = false, enabled = !busy, modifier = Modifier.weight(1f))
                SbButton(
                    if (busy) "Baixando…" else "Atualizar agora",
                    onClick = { AppUpdate.start(ctx, update.url) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        }
    }
    // No controle remoto o foco já começa em "Atualizar agora"
    LaunchedEffect(update) { runCatching { focus.requestFocus() } }
}
