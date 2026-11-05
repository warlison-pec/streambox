package com.pecbox.streambox.ui.screens

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.pecbox.streambox.ui.Watched
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.play
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.components.Artwork
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.ErrorBox
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.MediaCard
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.rememberLoader
import com.pecbox.streambox.ui.theme.Black
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Success
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.TextMuted

@Composable
fun DetailsScreen(id: String, nav: Nav) {
    val jf = App.session.jellyfin
    val loader = rememberLoader("item:$id") { jf.item(id) }
    val item = loader.data
    when {
        item == null && loader.error != null -> ErrorBox(loader.error ?: "", onRetry = { loader.reload() })
        item == null -> Loading()
        else -> DetailsContent(item, nav, onChanged = { loader.reload() })
    }
}

@Composable
private fun DetailsContent(item: Item, nav: Nav, onChanged: () -> Unit) {
    val jf = App.session.jellyfin
    val ctx = LocalContext.current
    val tv = LocalIsTv.current
    val pad = if (tv) 40.dp else 16.dp
    val playFocus = remember { FocusRequester() }
    if (tv) LaunchedEffect(item.id) { runCatching { playFocus.requestFocus() } }

    // Séries: temporadas, episódios e o próximo episódio a assistir
    val scope = rememberCoroutineScope()
    val seasonsLoader = if (item.isSeries) rememberLoader("seasons:${item.id}") { jf.seasons(item.id) } else null
    val seasons = seasonsLoader?.data.orEmpty()
    var seasonId by rememberSaveable(item.id) { mutableStateOf<String?>(null) }
    val selectedSeason = seasons.firstOrNull { it.id == seasonId } ?: seasons.firstOrNull { (it.userData?.unplayed ?: 0) > 0 } ?: seasons.firstOrNull()
    val episodesLoader = if (selectedSeason != null)
        rememberLoader("episodes:${selectedSeason.id}") { jf.episodes(item.id, selectedSeason.id) }
    else null
    val episodes = episodesLoader?.data.orEmpty()
    val nextEpisode = episodes.firstOrNull { !Watched.isPlayed(it) } ?: episodes.firstOrNull()

    /** Marca/desmarca e recarrega o que está na tela. */
    fun toggle(target: Item, children: List<Item> = emptyList()) {
        scope.launch {
            if (Watched.toggle(ctx, target, children) != null) {
                onChanged(); seasonsLoader?.reload(); episodesLoader?.reload()
            }
        }
    }
    // Coleções (BoxSet): lista os filmes/séries da coleção
    val isCollection = item.type == "BoxSet"
    val children = if (isCollection) rememberLoader("children:${item.id}") { jf.children(item.id) }.data.orEmpty() else emptyList()

    val title = if (item.isEpisode) item.seriesName ?: item.name else item.name
    val position = item.userData?.positionTicks ?: 0L

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (tv) 440.dp else 300.dp)) {
                AsyncImage(
                    model = jf.backdropImage(item), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Black.copy(alpha = 0.3f), Black))))
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Black, Color.Transparent))))
                Row(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = pad, vertical = 16.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (tv || !item.isEpisode) {
                        Artwork(
                            jf.posterImage(item), title,
                            Modifier.width(if (tv) 180.dp else 110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when {
                                isCollection -> "COLEÇÃO"
                                item.isSeries -> "SÉRIE"
                                item.isEpisode -> "EPISÓDIO"
                                else -> "FILME"
                            },
                            color = Orange, style = MaterialTheme.typography.labelMedium
                        )
                        Text(title, style = if (tv) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (item.isEpisode) Text(item.episodeLabel, style = MaterialTheme.typography.titleMedium)
                        MetaLine(item)
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        isCollection -> Text("${children.size} títulos nesta coleção", color = TextMuted)
                        item.isSeries -> SbButton(
                            if (nextEpisode != null) "Assistir ${nextEpisode.episodeLabel.substringBefore(" ·")}" else "Assistir",
                            onClick = { nextEpisode?.let { ctx.play(it) } },
                            icon = Icons.Filled.PlayArrow, enabled = nextEpisode != null,
                            modifier = Modifier.focusRequester(playFocus)
                        )
                        position > 0 -> {
                            SbButton("Continuar", onClick = { ctx.play(item) }, icon = Icons.Filled.PlayArrow, modifier = Modifier.focusRequester(playFocus))
                            SbButton("Do início", onClick = { ctx.play(item, fromStart = true) }, icon = Icons.Filled.Replay, primary = false)
                        }
                        else -> SbButton("Assistir", onClick = { ctx.play(item) }, icon = Icons.Filled.PlayArrow, modifier = Modifier.focusRequester(playFocus))
                    }
                    if (!isCollection) {
                        val played = Watched.isPlayed(item)
                        SbButton(
                            when {
                                played && item.isSeries -> "Série assistida"
                                played -> "Assistido"
                                item.isSeries -> "Marcar série como vista"
                                else -> "Marcar como visto"
                            },
                            onClick = { toggle(item, if (item.isSeries) episodes else emptyList()) },
                            icon = if (played) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                            primary = false,
                        )
                    }
                }
                if (!item.overview.isNullOrBlank()) {
                    Text(item.overview, color = TextMuted, modifier = Modifier.widthIn(max = 900.dp))
                }
                if (item.genres.isNotEmpty()) {
                    Text(item.genres.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        if (isCollection && children.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = pad, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 12.dp)
                ) {
                    items(children, key = { it.id }) { child ->
                        MediaCard(
                            title = child.name, subtitle = child.year?.toString(), image = jf.posterImage(child), played = Watched.isPlayed(child),
                            progress = child.progress, onClick = { nav.push(Route.Details(child.id)) }
                        )
                    }
                }
            }
        }

        if (item.isSeries && seasons.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = pad, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(seasons, key = { it.id }) { s ->
                        Chip(s.name, selected = s.id == selectedSeason?.id, onClick = { seasonId = s.id })
                    }
                }
            }
            if (selectedSeason != null && episodes.isNotEmpty()) {
                item {
                    val allSeen = episodes.all { Watched.isPlayed(it) }
                    Row(Modifier.padding(horizontal = pad).padding(bottom = 6.dp)) {
                        SbButton(
                            if (allSeen) "Desmarcar ${selectedSeason.name}" else "Marcar ${selectedSeason.name} como vista",
                            onClick = { toggleSeason(scope, ctx, selectedSeason, episodes, !allSeen) { onChanged(); seasonsLoader?.reload(); episodesLoader?.reload() } },
                            icon = if (allSeen) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                            primary = false,
                        )
                    }
                }
            }
            items(episodes, key = { it.id }) { ep ->
                EpisodeRow(ep, Modifier.padding(horizontal = pad, vertical = 5.dp), onToggle = { toggle(ep) }) { ctx.play(ep) }
            }
        }
    }
}

@Composable
private fun MetaLine(item: Item) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        item.year?.let { Text(it.toString(), color = TextMuted) }
        item.runtimeLabel?.let { Text(it, color = TextMuted) }
        item.officialRating?.let { Text(it, color = TextMuted) }
        item.rating?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Star, null, tint = Orange, modifier = Modifier.width(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("%.1f".format(it), color = TextMuted)
            }
        }
        if (Watched.isPlayed(item)) Text("✓ Assistido", color = Success)
    }
}

@Composable
private fun EpisodeRow(ep: Item, modifier: Modifier, onToggle: () -> Unit, onClick: () -> Unit) {
    val jf = App.session.jellyfin
    val tv = LocalIsTv.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .tvFocus(shape, 1.02f)
            .clip(shape)
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(if (tv) 220.dp else 140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Artwork(jf.primaryImage(ep, 300), ep.name, Modifier.fillMaxSize())
            if (ep.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(ep.progress).height(4.dp).background(Orange))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${ep.indexNumber ?: "?"}. ${ep.name}",
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(ep.runtimeLabel, if (Watched.isPlayed(ep)) "✓ Assistido" else null).joinToString("  ·  ")
            if (meta.isNotBlank()) Text(meta, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            if (!ep.overview.isNullOrBlank()) {
                Text(ep.overview, color = TextMuted, maxLines = if (tv) 2 else 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        // ✓ marca/desmarca o episódio (na TV: seta → a partir da linha)
        val played = Watched.isPlayed(ep)
        Box(
            Modifier.align(Alignment.CenterVertically).size(44.dp)
                .tvFocus(CircleShape, 1.1f).clip(CircleShape)
                .background(if (played) Success.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (played) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = if (played) "Desmarcar episódio" else "Marcar episódio como visto",
                tint = if (played) Success else TextMuted,
            )
        }
    }
}

/** Temporada inteira: marca a temporada no servidor e atualiza os episódios exibidos. */
private fun toggleSeason(scope: CoroutineScope, ctx: Context, season: Item, episodes: List<Item>, played: Boolean, done: () -> Unit) {
    scope.launch { if (Watched.set(ctx, season, played, episodes) != null) done() }
}
