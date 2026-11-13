package com.pecbox.streambox.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.data.MyRequests
import com.pecbox.streambox.data.RequestState
import com.pecbox.streambox.data.RequestView
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.Artwork
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.ErrorBox
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.rememberLoader
import com.pecbox.streambox.ui.theme.Danger
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Success
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.TextMuted

private val WaitingBlue = Color(0xFF4DA3FF)

private fun RequestState.color() = when (this) {
    RequestState.ARRIVED, RequestState.PARTIAL -> Success
    RequestState.PROGRESS -> Orange
    RequestState.WAITING -> WaitingBlue
    RequestState.FAILED -> Danger
}

/** Aba "Pedidos": tudo o que a pessoa pediu e em que pé está cada pedido. */
@Composable
fun RequestsScreen(nav: Nav) {
    val tv = LocalIsTv.current
    var nonce by rememberSaveable { mutableStateOf(0) }
    val loader = rememberLoader("requests") { MyRequests.views(force = nonce > 0) }
    var filter by rememberSaveable { mutableStateOf(0) }   // 0 todos · 1 chegaram · 2 aguardando
    val side = if (tv) 40.dp else 16.dp

    val all = loader.data
    when {
        all == null && loader.error != null -> ErrorBox(loader.error ?: "", onRetry = { loader.reload() })
        all == null -> Loading()
        else -> {
            val arrived = all.filter { it.item != null }
            val waiting = all.filter { it.item == null }
            val shown = when (filter) { 1 -> arrived; 2 -> waiting; else -> all }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (tv) 380.dp else 320.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = side, vertical = if (tv) 32.dp else 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Meus pedidos", style = MaterialTheme.typography.headlineMedium)
                                Text(
                                    "${all.size} no total · ${arrived.size} já chegaram",
                                    color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            SbButton(
                                if (loader.loading) "Atualizando…" else "Atualizar", primary = false, icon = Icons.Filled.Refresh,
                                enabled = !loader.loading, onClick = { nonce++; loader.reload() },
                            )
                        }
                        if (all.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Todos · ${all.size}", selected = filter == 0, onClick = { filter = 0 })
                            Chip("Chegaram · ${arrived.size}", selected = filter == 1, onClick = { filter = 1 })
                            Chip("Aguardando · ${waiting.size}", selected = filter == 2, onClick = { filter = 2 })
                        }
                    }
                }
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyRequests(all.isEmpty()) }
                items(shown, key = { it.request.key }) { r ->
                    RequestCard(r, onOpen = { r.item?.let { nav.push(Route.Details(it.id)) } })
                }
            }
        }
    }
}

@Composable
private fun RequestCard(r: RequestView, onOpen: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val jf = App.session.jellyfin
    val color = r.state.color()
    val image = r.item?.let { jf.posterImage(it, 300) } ?: r.request.poster
    Row(
        Modifier.fillMaxWidth().tvFocus(shape, 1.02f).clip(shape).background(Surface)
            .clickable(enabled = r.item != null, onClick = onOpen).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(image, r.request.title, Modifier.width(76.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.request.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    if (r.request.kind == "series") "Série" else "Filme",
                    r.request.year?.toString(),
                    if (r.request.source == "seerr") "Seerr" else "STRM",
                ).joinToString(" · "),
                color = TextMuted, style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Text(r.label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            if (r.request.time > 0) Text(
                "Pedido " + DateUtils.getRelativeTimeSpanString(r.request.time).toString().replaceFirstChar { it.lowercase() },
                color = TextMuted, style = MaterialTheme.typography.labelMedium,
            )
            if (r.item != null) Text(if (LocalIsTv.current) "OK para abrir" else "Toque para abrir", color = Success, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyRequests(none: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Inbox, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
        }
        Text(if (none) "Nenhum pedido ainda" else "Nada aqui", style = MaterialTheme.typography.titleLarge)
        Text(
            if (none) "Busque um título que não está no catálogo e toque em Solicitar." else "Nenhum pedido neste filtro.",
            color = TextMuted,
        )
    }
}
