package com.pecbox.streambox.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.desktop.data.HomeExtras
import com.pecbox.streambox.desktop.data.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private class HomeData(
    val featured: List<Item>,
    val resume: List<Item>,
    val nextUp: List<Item>,
    val latest: List<Pair<Item, List<Item>>>,
)

/** Fileiras que dependem de mais consultas: chegam depois, sem atrasar o resto do Início. */
private class HomeExtrasData(
    val because: List<Pair<String, List<Item>>>,   // "Para você que assistiu X"
    val arrived: List<Item>,                       // "Seu pedido chegou"
)

@Composable
fun HomeScreen(s: Session, refreshKey: Int, open: (Item) -> Unit, play: (Item, Boolean) -> Unit) {
    val jf = s.jellyfin
    var data by remember { mutableStateOf<HomeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var extras by remember { mutableStateOf<HomeExtrasData?>(null) }

    LaunchedEffect(refreshKey, retry) {
        error = null
        try {
            data = coroutineScope {
                val featured = async { runCatching { jf.featured() }.getOrDefault(emptyList()) }
                val resume = async { runCatching { jf.resume() }.getOrDefault(emptyList()) }
                val nextUp = async { runCatching { jf.nextUp() }.getOrDefault(emptyList()) }
                val views = jf.views().filter { libraryItemTypes(it.collectionType) != null }
                val latest = views.map { v -> async { v to runCatching { jf.latest(v.id) }.getOrDefault(emptyList()) } }
                HomeData(featured.await(), resume.await(), nextUp.await(), latest.map { it.await() })
            }
        } catch (e: Exception) {
            if (data == null) error = e.friendly()
        }
        // Sugestões pelo histórico e pedidos que chegaram (sem histórico/pedidos, as fileiras não aparecem)
        val resume = data?.resume ?: return@LaunchedEffect
        extras = coroutineScope {
            val because = async { withTimeoutOrNull(15_000) { runCatching { HomeExtras.forYou(jf, resume) }.getOrNull() }.orEmpty() }
            val arrived = async { withTimeoutOrNull(15_000) { runCatching { HomeExtras.arrived(s) }.getOrNull() }.orEmpty() }
            HomeExtrasData(because.await(), arrived.await())
        }
    }

    val d = data
    when {
        d != null -> LazyColumn(Modifier.fillMaxSize()) {
            if (d.featured.isNotEmpty()) item { Hero(s, d.featured, open, play) }
            item {
                MediaRow("Continuar assistindo", d.resume) { item ->
                    MediaCard(
                        title = if (item.isEpisode) item.seriesName ?: item.name else item.name,
                        subtitle = if (item.isEpisode) item.episodeLabel else item.year?.toString(),
                        image = jf.landscapeImage(item), landscape = true, width = 300.dp,
                        progress = item.progress, onClick = { play(item, false) },
                    )
                }
            }
            item {
                MediaRow("Próximos episódios", d.nextUp) { item ->
                    MediaCard(
                        title = item.seriesName ?: item.name, subtitle = item.episodeLabel,
                        image = jf.landscapeImage(item), landscape = true, width = 300.dp,
                        onClick = { play(item, false) },
                    )
                }
            }
            extras?.let { x ->
                for ((name, list) in x.because) {
                    item {
                        MediaRow("Para você que assistiu $name", list) { item ->
                            MediaCard(
                                title = item.name, subtitle = item.year?.toString(), image = jf.posterImage(item),
                                rating = item.rating, onClick = { open(item) },
                            )
                        }
                    }
                }
                if (x.arrived.isNotEmpty()) item {
                    MediaRow("Seu pedido chegou", x.arrived) { item ->
                        MediaCard(
                            title = item.name, subtitle = item.year?.toString(), image = jf.posterImage(item),
                            badge = "CHEGOU", rating = item.rating, onClick = { open(item) },
                        )
                    }
                }
            }
            for ((view, list) in d.latest) {
                item {
                    MediaRow("Adicionados em ${view.name}", list) { item ->
                        MediaCard(
                            title = item.name, subtitle = item.year?.toString(), image = jf.posterImage(item),
                            played = item.userData?.played == true, rating = item.rating, onClick = { open(item) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
        error != null -> ErrorBox(error ?: "", onRetry = { retry++ })
        else -> Loading()
    }
}

/** Destaque no topo: fundo do filme/série com logo, sinopse e botões. Troca sozinho a cada 12 s. */
@Composable
private fun Hero(s: Session, items: List<Item>, open: (Item) -> Unit, play: (Item, Boolean) -> Unit) {
    val jf = s.jellyfin
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items) {
        while (items.size > 1) {
            delay(12_000)
            index = (index + 1) % items.size
        }
    }
    Crossfade(items[index], label = "hero") { item ->
        Box(Modifier.fillMaxWidth().height(460.dp)) {
            AsyncImage(
                model = jf.backdropImage(item), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.horizontalGradient(0f to Black, 0.45f to Black.copy(alpha = 0.75f), 1f to Color.Transparent)
                )
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Black)))
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = 40.dp, top = 30.dp).widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val logo = jf.logoImage(item)
                if (logo != null) {
                    AsyncImage(
                        model = logo, contentDescription = item.name, contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = 110.dp).widthIn(max = 380.dp),
                    )
                } else {
                    Text(item.name, style = MaterialTheme.typography.headlineLarge, maxLines = 2)
                }
                MetaLine(item)
                item.overview?.let {
                    Text(it, color = TextMain.copy(alpha = 0.85f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!item.isSeries) SbButton("Assistir", onClick = { play(item, false) }, icon = Icons.Filled.PlayArrow)
                    SbButton("Detalhes", onClick = { open(item) }, icon = Icons.Filled.Info, primary = item.isSeries)
                }
            }
            if (items.size > 1) {
                Row(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items.indices.forEach { i ->
                        Box(
                            Modifier.size(width = if (i == index) 22.dp else 8.dp, height = 8.dp).clip(CircleShape)
                                .background(if (i == index) Orange else Surface3)
                        )
                    }
                }
            }
        }
    }
}

/** "2023 · ★ 7.8 · 16 · 2h 10min" */
@Composable
fun MetaLine(item: Item) {
    val parts = listOfNotNull(
        item.year?.toString(),
        item.rating?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
        item.officialRating,
        item.runtimeLabel,
        item.genres.take(3).takeIf { it.isNotEmpty() }?.joinToString(", "),
    )
    if (parts.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            parts.forEachIndexed { i, p ->
                if (i > 0) Text("  ·  ", color = TextMuted)
                Text(p, color = if (p.startsWith("★")) Color(0xFFFFC94D) else TextMuted, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
