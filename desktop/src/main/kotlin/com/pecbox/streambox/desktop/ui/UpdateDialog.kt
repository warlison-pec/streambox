package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.desktop.BuildConfig
import com.pecbox.streambox.desktop.data.DesktopUpdate
import com.pecbox.streambox.desktop.data.Session
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

/** Caixa na frente da tela quando há versão nova: "Atualizar agora" baixa e abre o instalador. */
@Composable
fun UpdateDialog(session: Session, onBeforeExit: () -> Unit) {
    val update = DesktopUpdate.available ?: return
    val scope = rememberCoroutineScope()
    val busy = DesktopUpdate.busy
    // Fundo escurecido que segura os cliques (a caixa fica na frente de tudo)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(440.dp).clip(RoundedCornerShape(18.dp)).background(Surface).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Orange, modifier = Modifier.size(48.dp))
            Text("Nova versão disponível", color = TextMain, style = MaterialTheme.typography.titleLarge)
            Text(
                "O ${BuildConfig.APP_NAME} ${update.version} já está pronto (você está na ${BuildConfig.VERSION_NAME}). " +
                    "Atualize para ter as correções e novidades.",
                color = TextMuted, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
            DesktopUpdate.status?.let { status ->
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Orange, trackColor = Surface3)
                Text(status, color = if (busy) TextMain else Orange, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (DesktopUpdate.installed) {
                    SbButton("Fechar o app", onClick = { onBeforeExit(); exitProcess(0) }, modifier = Modifier.weight(1f))
                } else {
                    SbButton("Depois", onClick = { DesktopUpdate.dismiss() }, primary = false, enabled = !busy, modifier = Modifier.weight(1f))
                    SbButton(
                        if (busy) "Aguarde…" else "Atualizar agora",
                        onClick = { scope.launch { DesktopUpdate.start(session.http, onBeforeExit) } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
