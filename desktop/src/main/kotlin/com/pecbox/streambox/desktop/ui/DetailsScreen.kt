package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch
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
import com.pecbox.streambox.desktop.data.Session

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(s: Session, itemId: String, refreshKey: Int, back: () -> Unit, play: (Item, Boolean) -> Unit) {
    val jf = s.jellyfin
    var item by remember(itemId) { mutableStateOf<Item?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var seasons by remember(itemId) { mutableStateOf<List<Item>>(emptyList()) }
    var season by remember(itemId) { mutableStateOf<Item?>(null) }
    var episodes by remember(itemId) { mutableStateOf<List<Item>?>(null) }
    // Todos os episódios da série: acha onde a pessoa parou mesmo em outra temporada
    var allEpisodes by remember(itemId) { mutableStateOf<List<Item>?>(null) }
    // Marcado/desmarcado agora, enquanto o servidor confirma
    val watched = remember(itemId) { mutableStateMapOf<String, Boolean>() }
    fun isPlayed(e: Item) = watched[e.id] ?: (e.userData?.played == true)
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }

    LaunchedEffect(itemId, refreshKey, retry) {
        error = null
        try {
            val it = jf.item(itemId)
            item = it
            if (it.isSeries) {
                val list = jf.seasons(it.id)
                seasons = list
                val all = runCatching { jf.seriesEpisodes(it.id) }.getOrNull()
                if (all != null) { allEpisodes = all; watched.clear() }
                if (season == null) {
                    // Abre na temporada de onde a pessoa parou (ou do próximo episódio a assistir)
                    val resumeSeason = all?.let { e -> resumePoint(e) { x -> x.userData?.played == true } }?.seasonId
                    season = list.firstOrNull { s -> s.id == resumeSeason }
                        ?: list.firstOrNull { s -> (s.userData?.unplayed ?: 0) > 0 } ?: list.firstOrNull()
                }
            }
        } catch (e: Exception) {
            if (item == null) error = e.friendly()
        }
    }
    LaunchedEffect(season, refreshKey) {
        val it = item ?: return@LaunchedEffect
        val se = season ?: return@LaunchedEffect
        episodes = runCatching { jf.episodes(it.id, se.id) }.getOrNull() ?: episodes
    }

    val i = item
    if (i == null) {
        if (error != null) ErrorBox(error ?: "", onRetry = { retry++ }) else Loading()
        return
    }
    // Foco no botão principal ao abrir: Enter já assiste (ou continua de onde parou)
    LaunchedEffect(i.id) {
        repeat(6) {
            withFrameNanos { }
            if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }

    /** ✓ de um episódio: muda na hora e confirma no servidor (desfaz se falhar). */
    fun toggle(ep: Item) {
        val now = !isPlayed(ep)
        watched[ep.id] = now
        scope.launch {
            if (runCatching { jf.setPlayed(ep.id, now) }.isFailure) watched.remove(ep.id)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillMaxWidth().height(520.dp)) {
                AsyncImage(
                    model = jf.backdropImage(i), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Black, 0.55f to Black.copy(alpha = 0.7f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Black)))
                Row(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 70.dp, bottom = 20.dp)) {
                    Artwork(jf.posterImage(i, 600), i.name, Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val logo = jf.logoImage(i)
                        if (logo != null) {
                            AsyncImage(
                                model = logo, contentDescription = i.name, contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart, modifier = Modifier.height(100.dp).width(420.dp),
                            )
                        } else {
                            Text(i.name, style = MaterialTheme.typography.headlineLarge, maxLines = 2)
                        }
                        MetaLine(i)
                        i.overview?.let {
                            Text(it, color = TextMain.copy(alpha = 0.88f), maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val focusMain = Modifier.focusRequester(playFocus)
                            if (i.isSeries) {
                                // Onde a pessoa parou, olhando todas as temporadas
                                val next = allEpisodes?.let { resumePoint(it, ::isPlayed) }
                                    ?: episodes?.firstOrNull { !isPlayed(it) } ?: episodes?.firstOrNull()
                                SbButton(
                                    when {
                                        next == null -> "Assistir"
                                        (next.userData?.positionTicks ?: 0L) > 0L && !isPlayed(next) -> "Continuar ${next.episodeLabel.substringBefore(" ·")}"
                                        else -> "Assistir ${next.episodeLabel.substringBefore(" ·")}"
                                    },
                                    onClick = { next?.let { play(it, false) } }, icon = Icons.Filled.PlayArrow, modifier = focusMain,
                                )
                            } else if (i.progress > 0f || (i.userData?.positionTicks ?: 0) > 0) {
                                SbButton("Continuar de ${formatTicks(i.userData?.positionTicks ?: 0)}", onClick = { play(i, false) }, icon = Icons.Filled.PlayArrow, modifier = focusMain)
                                SbButton("Do início", onClick = { play(i, true) }, icon = Icons.Filled.Replay, primary = false)
                            } else {
                                SbButton("Assistir", onClick = { play(i, true) }, icon = Icons.Filled.PlayArrow, modifier = focusMain)
                            }
                        }
                    }
                }
                Box(Modifier.padding(start = 24.dp, top = 16.dp)) {
                    SbButton("Voltar", onClick = back, icon = Icons.AutoMirrored.Filled.ArrowBack, primary = false)
                }
            }
        }

        if (i.isSeries) {
            item {
                FlowRow(
                    Modifier.padding(horizontal = 40.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { se -> Chip(se.name, selected = se.id == season?.id, onClick = { season = se; episodes = null }) }
                }
            }
            val eps = episodes
            if (eps == null) item { Loading(Modifier.fillMaxWidth().height(200.dp)) }
            else items(eps, key = { it.id }) { ep -> EpisodeRow(s, ep, isPlayed(ep), onToggle = { toggle(ep) }) { play(ep, false) } }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Composable
private fun EpisodeRow(s: Session, ep: Item, played: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp)
            .highlight(interaction, shape, 1.01f).clip(shape).background(Surface)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Artwork(s.jellyfin.primaryImage(ep, 360), ep.name, Modifier.fillMaxSize())
            if (ep.progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(ep.progress).height(4.dp).background(Orange))
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ep.episodeLabel, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (played) Text("  ✓ Assistido", color = Orange, style = MaterialTheme.typography.labelMedium)
            }
            ep.runtimeLabel?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.labelMedium) }
            ep.overview?.let { Text(it, color = TextMuted, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
        }
        Spacer(Modifier.width(16.dp))
        // ✓ marca/desmarca só este episódio
        val check = remember { MutableInteractionSource() }
        Box(
            Modifier.size(44.dp).highlight(check, CircleShape, 1.1f).clip(CircleShape)
                .background(if (played) Orange.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                .hoverable(check).clickable(check, indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (played) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                contentDescription = if (played) "Desmarcar episódio" else "Marcar episódio como visto",
                tint = if (played) Orange else TextMuted,
            )
        }
    }
}

/**
 * Onde a pessoa parou na série: o episódio começado mais recente; senão o primeiro não visto
 * depois do último assistido; senão o primeiro não visto; senão o primeiro da série.
 */
private fun resumePoint(all: List<Item>, isPlayed: (Item) -> Boolean): Item? {
    if (all.isEmpty()) return null
    val inProgress = all.filter { !isPlayed(it) && (it.userData?.positionTicks ?: 0L) > 0L }
        .maxByOrNull { it.userData?.lastPlayed.orEmpty() }
    if (inProgress != null) return inProgress
    val lastSeen = all.indices.filter { isPlayed(all[it]) }.maxByOrNull { all[it].userData?.lastPlayed.orEmpty() }
    if (lastSeen != null) all.drop(lastSeen + 1).firstOrNull { !isPlayed(it) }?.let { return it }
    return all.firstOrNull { !isPlayed(it) } ?: all.first()
}

fun formatTicks(ticks: Long): String {
    val total = ticks / 10_000_000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
