package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.data.ArrivedRequest
import com.pecbox.streambox.desktop.data.MyRequests
import com.pecbox.streambox.desktop.data.Session

/** Aba "Pedidos": só os pedidos atendidos que estão no catálogo agora (dá para limpar), igual ao Android. */
@Composable
fun RequestsScreen(s: Session, open: (Item) -> Unit) {
    val jf = s.jellyfin
    var list by remember { mutableStateOf<List<ArrivedRequest>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            list = MyRequests.listed(s, force = reload > 0)
        } catch (e: Exception) {
            error = e.friendly()
        } finally {
            loading = false
        }
    }

    val all = list
    when {
        all == null && error != null -> ErrorBox(error ?: "", onRetry = { reload++ })
        all == null -> Loading()
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Meus pedidos", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (all.size == 1) "1 pedido disponível no catálogo" else "${all.size} pedidos disponíveis no catálogo",
                            color = TextMuted, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (all.isNotEmpty()) SbButton(
                        "Limpar", icon = Icons.Filled.ClearAll, primary = false, enabled = !loading,
                        onClick = { MyRequests.clear(s, all.map { it.key }); list = emptyList() },
                    )
                    SbButton(
                        if (loading) "Atualizando…" else "Atualizar", icon = Icons.Filled.Refresh, primary = false,
                        enabled = !loading, onClick = { reload++ },
                    )
                }
            }
            if (all.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyRequests() }
            items(all, key = { it.key }) { r ->
                MediaCard(
                    title = r.item.name,
                    subtitle = if (r.partial) "Chegou em parte" else r.item.year?.toString(),
                    image = jf.posterImage(r.item), badge = "CHEGOU",
                    played = r.item.userData?.played == true, rating = r.item.rating, width = 170.dp,
                    onClick = { open(r.item) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyRequests() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Inbox, contentDescription = null, tint = TextMuted, modifier = Modifier.size(36.dp))
        }
        Text("Nenhum pedido disponível", style = MaterialTheme.typography.titleLarge)
        Text("Quando um pedido seu chegar ao catálogo, ele aparece aqui.", color = TextMuted)
    }
}
