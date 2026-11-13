package com.pecbox.streambox.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.AppUpdate
import com.pecbox.streambox.data.Notice
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.NoticeThumb
import com.pecbox.streambox.ui.Notices
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.noticeColor
import com.pecbox.streambox.ui.noticeKindLabel
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted
import java.util.Calendar

private val FILTERS = listOf(
    null to "Todos",
    Notice.NEW to "Novidades",
    Notice.REQUESTED to "Pedidos",
    Notice.MESSAGE to "Mensagens",
    Notice.UPDATE to "Atualizações",
)

/** Histórico de avisos: filtros por tipo, agrupado por dia, com capa e indicação de não lido. */
@Composable
fun NoticesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tv = LocalIsTv.current
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    // Os não lidos ficam destacados enquanto a tela está aberta; viram lidos ao sair
    DisposableEffect(Unit) { onDispose { Notices.markAllRead() } }

    fun open(n: Notice) {
        Notices.markRead(n.id)
        n.itemId?.let { nav.push(Route.Details(it)); return }
        n.url?.let { url ->
            if (AppUpdate.isUpdateUrl(url)) AppUpdate.start(ctx, url)
            else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            return
        }
        if (n.kind == Notice.NEW) nav.root(Route.Home)
    }

    val kinds = Notices.all.map { it.kind }.toSet()
    val shown = Notices.all.filter { filter == null || it.kind == filter }
    val groups = shown.groupBy { dayGroup(it.time) }
    val side = if (tv) 40.dp else 16.dp

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = side, vertical = if (tv) 32.dp else 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Avisos", style = MaterialTheme.typography.headlineMedium)
                    val unread = Notices.unread
                    Text(
                        when {
                            Notices.all.isEmpty() -> "Novidades, pedidos e mensagens aparecem aqui"
                            unread == 0 -> "Tudo lido"
                            unread == 1 -> "1 não lido"
                            else -> "$unread não lidos"
                        },
                        color = if (unread > 0) Orange else TextMuted, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (Notices.unread > 0) SbButton(if (tv) "Marcar como lidos" else "Lidos", onClick = { Notices.markAllRead() }, icon = Icons.Filled.DoneAll, primary = false)
                if (Notices.all.isNotEmpty()) SbButton(if (tv) "Limpar tudo" else "Limpar", onClick = { Notices.clear() }, icon = Icons.Outlined.DeleteSweep, primary = false)
            }
        }
        if (kinds.size > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(FILTERS.filter { it.first == null || it.first in kinds }) { (kind, label) ->
                    val count = Notices.all.count { (kind == null || it.kind == kind) && !it.read }
                    Chip(if (count > 0) "$label · $count" else label, selected = filter == kind, onClick = { filter = kind })
                }
            }
        }
        if (shown.isEmpty()) item { EmptyNotices() }
        groups.forEach { (label, list) ->
            item(key = "h:$label") {
                Text(label, color = TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            }
            items(list, key = { it.id }) { n -> NoticeRow(n, onOpen = { open(n) }, onRemove = { Notices.remove(n.id) }) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun NoticeRow(n: Notice, onOpen: () -> Unit, onRemove: () -> Unit) {
    val tv = LocalIsTv.current
    val shape = RoundedCornerShape(14.dp)
    val color = noticeColor(n.kind)
    val opens = n.itemId != null || n.url != null || n.kind == Notice.NEW
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.weight(1f).widthIn(max = 900.dp)
                .tvFocus(shape, 1.015f).clip(shape)
                .background(if (n.read) Surface else Surface2)
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NoticeThumb(n, if (n.image != null) 48.dp else 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(noticeKindLabel(n.kind), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text("·  " + DateUtils.getRelativeTimeSpanString(n.time).toString(), color = TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                Text(n.title, color = TextMain, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val text = AppUpdate.status?.takeIf { AppUpdate.isUpdateUrl(n.url) } ?: n.text
                if (text.isNotBlank()) Text(text, color = TextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (!n.read) Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            if (opens) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TextMuted)
        }
        // Botão separado para apagar: no controle remoto basta ir para a direita
        Box(
            Modifier.size(if (tv) 44.dp else 36.dp).tvFocus(CircleShape, 1.1f).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f)).clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Close, contentDescription = "Apagar aviso", tint = TextMuted, modifier = Modifier.size(18.dp)) }
    }
}

@Composable
private fun EmptyNotices() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.NotificationsNone, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
        }
        Text("Tudo em dia", style = MaterialTheme.typography.titleLarge)
        Text(
            "Quando chegar um filme, um episódio novo ou o seu pedido, você vê aqui.",
            color = TextMuted, textAlign = TextAlign.Center,
        )
    }
}

private fun dayGroup(time: Long): String {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val day = 24 * 3_600_000L
    return when {
        time >= today -> "Hoje"
        time >= today - day -> "Ontem"
        time >= today - 6 * day -> "Esta semana"
        else -> "Anteriores"
    }
}
