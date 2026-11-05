package com.pecbox.streambox.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.Context
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.play
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.Watched
import com.pecbox.streambox.ui.components.ErrorBox
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.MediaCard
import com.pecbox.streambox.ui.components.MediaRow
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.rememberLoader
import com.pecbox.streambox.ui.theme.Black
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Success
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private class HomeData(
    val featured: List<Item>,
    val resume: List<Item>,
    val nextUp: List<Item>,
    val latest: List<Pair<String, List<Item>>>,
)

@Composable
fun HomeScreen(nav: Nav) {
    val jf = App.session.jellyfin
    val home = rememberLoader("home") {
        coroutineScope {
            val resume = async { runCatching { jf.resume() }.getOrDefault(emptyList()) }
            val next = async { runCatching { jf.nextUp() }.getOrDefault(emptyList()) }
            val movies = async { runCatching { jf.featured("Movie", 6) }.getOrDefault(emptyList()) }
            val series = async { runCatching { jf.featured("Series", 6) }.getOrDefault(emptyList()) }
            val libs = jf.views().filter { libraryItemTypes(it.collectionType) != null && it.collectionType != "boxsets" }
            val latest = libs.map { v ->
                v.name to async { runCatching { jf.latest(v.id, libraryItemTypes(v.collectionType) ?: "Movie,Series") }.getOrDefault(emptyList()) }
            }
            // Intercala filmes e séries nos destaques (até 12)
            val m = movies.await(); val sr = series.await()
            val featured = (0 until maxOf(m.size, sr.size)).flatMap { i -> listOfNotNull(m.getOrNull(i), sr.getOrNull(i)) }
            HomeData(featured, resume.await(), next.await(), latest.map { (n, d) -> n to d.await() })
        }
    }
    val data = home.data
    when {
        data == null && home.error != null -> ErrorBox(home.error ?: "", onRetry = { home.reload() })
        data == null -> Loading()
        else -> {
            val jf2 = App.session.jellyfin
            val slides = remember(data) {
                data.featured.ifEmpty { (data.resume + data.latest.flatMap { it.second }).filter { jf2.backdropImage(it) != null }.take(8) }
            }
            if (LocalIsTv.current) TvHome(data, slides, nav, onChanged = { home.reload() })
            else PhoneHome(data, slides, nav, onChanged = { home.reload() })
        }
    }
}

/* ============================== ações comuns ============================== */

private fun Context.playOrOpen(item: Item, nav: Nav) {
    if (item.isSeries || item.type == "BoxSet") nav.push(Route.Details(item.id)) else play(item)
}

private fun kindLabel(item: Item) = when {
    item.isSeries -> "SÉRIE"
    item.isEpisode -> "EPISÓDIO"
    item.type == "BoxSet" -> "COLEÇÃO"
    else -> "FILME"
}

/** Lambda de foco para um cartão (só na TV). */
private fun focusOf(onFocus: ((Item) -> Unit)?, item: Item): (() -> Unit)? =
    if (onFocus == null) null else ({ onFocus(item) })

/** Fileiras da tela inicial. [onFocus] recebe o item em foco (TV). */
/**
 * Tamanho das fileiras na TV: [poster] = altura da capa; [rowFocus] recebe o índice da fileira
 * que ganhou foco (para subir essa fileira até o topo da área de baixo, sem cortar as capas).
 */
private class TvRows(val poster: Dp, val endSpace: Dp, val rowFocus: (Int) -> Unit)

private fun Modifier.rowFocus(tv: TvRows?, index: Int): Modifier =
    if (tv == null) this else onFocusChanged { if (it.hasFocus) tv.rowFocus(index) }

private fun LazyListScope.homeRows(data: HomeData, nav: Nav, ctx: Context, onFocus: ((Item) -> Unit)?, tvRows: TvRows? = null) {
    val jf = App.session.jellyfin
    val text = tvRows == null
    val topPad = if (tvRows != null) 6.dp else 18.dp
    // capas: altura calculada pela tela na TV; no celular, larguras fixas como antes
    val posterW = tvRows?.let { it.poster * 2f / 3f }
    val wideW = tvRows?.let { it.poster * 0.82f * 16f / 9f }
    item {
        val tv = LocalIsTv.current
        MediaRow("Continuar assistindo", data.resume, Modifier.rowFocus(tvRows, 0), topPad) {
            MediaCard(
                title = if (it.isEpisode) it.seriesName ?: it.name else it.name,
                subtitle = if (it.isEpisode) it.episodeLabel else it.year?.toString(),
                image = jf.landscapeImage(it), landscape = true, progress = it.progress,
                width = wideW ?: if (tv) 260.dp else 200.dp, showText = text,
                onFocus = focusOf(onFocus, it),
                onClick = { ctx.play(it) },
            )
        }
    }
    item {
        val tv = LocalIsTv.current
        MediaRow("Próximos episódios", data.nextUp, Modifier.rowFocus(tvRows, 1), topPad) {
            MediaCard(
                title = it.seriesName ?: it.name, subtitle = it.episodeLabel,
                image = jf.landscapeImage(it), landscape = true,
                width = wideW ?: if (tv) 260.dp else 200.dp, showText = text,
                played = Watched.isPlayed(it),
                onFocus = focusOf(onFocus, it),
                onClick = { if (tv) ctx.play(it) else nav.push(Route.Details(it.id)) },
            )
        }
    }
    itemsIndexed(data.latest) { i, (name, list) ->
        MediaRow("Adicionados em $name", list, Modifier.rowFocus(tvRows, 2 + i), topPad) {
            MediaCard(
                title = it.name, subtitle = it.year?.toString(), image = jf.posterImage(it),
                showText = text, width = posterW ?: if (LocalIsTv.current) 150.dp else 112.dp,
                badge = it.userData?.unplayed?.takeIf { n -> n > 0 && !Watched.isPlayed(it) }?.toString(),
                played = Watched.isPlayed(it),
                onFocus = focusOf(onFocus, it),
                onClick = { nav.push(Route.Details(it.id)) },
            )
        }
    }
    // espaço no fim para que a última fileira também consiga subir até o topo
    item { Spacer(Modifier.height(tvRows?.endSpace ?: 48.dp)) }
}

/** Botão "visto / não visto". */
@Composable
private fun WatchedButton(item: Item, onChanged: () -> Unit, compact: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val played = Watched.isPlayed(item)
    val label = if (played) "Assistido" else "Marcar como visto"
    if (compact) {
        RoundIcon(if (played) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle, label, tint = if (played) Success else Color.White) {
            scope.launch { if (Watched.toggle(ctx, item) != null) onChanged() }
        }
    } else {
        SbButton(
            label, primary = false,
            icon = if (played) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
            onClick = { scope.launch { if (Watched.toggle(ctx, item) != null) onChanged() } },
        )
    }
}

/* ============================== TV: estilo spotlight ============================== */

/**
 * Fundo grande que acompanha o item em foco (como o Wholphin): informações e botões no topo,
 * fileiras embaixo. Antes de o usuário navegar, os destaques giram sozinhos.
 */
@Composable
private fun TvHome(data: HomeData, slides: List<Item>, nav: Nav, onChanged: () -> Unit) {
    val jf = App.session.jellyfin
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf<Item?>(null) }
    var pending by remember { mutableStateOf<Item?>(null) }
    var slide by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    // Rotação automática enquanto nenhum cartão recebeu foco
    LaunchedEffect(focused, slides.size) {
        if (focused != null || slides.size < 2) return@LaunchedEffect
        while (true) { delay(8000); slide = (slide + 1) % slides.size }
    }
    // Pequeno atraso para não trocar o fundo a cada toque rápido nas setas
    LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        delay(220)
        focused = p
    }

    val spot = focused ?: slides.getOrNull(slide) ?: data.resume.firstOrNull() ?: data.latest.firstOrNull()?.second?.firstOrNull()

    BoxWithConstraints(Modifier.fillMaxSize().background(Black)) {
        // Divide a tela: destaque em cima, uma fileira inteira de capas embaixo (qualquer TV/densidade)
        val spotH = (maxHeight * 0.5f).coerceIn(230.dp, 360.dp)
        val rowsH = maxHeight - spotH
        // fileira = título (~30) + margens do foco (~30) + capa
        val posterH = (rowsH - 66.dp).coerceIn(120.dp, 300.dp)
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val tvRows = remember(posterH, rowsH) {
            TvRows(posterH, rowsH) { index ->
                scope.launch { runCatching { listState.animateScrollToItem(index) } }
            }
        }
        Crossfade(targetState = spot?.let { jf.backdropImage(it, 1920) }, animationSpec = tween(650), label = "spotBg") { url ->
            Box(Modifier.fillMaxSize()) {
                if (url != null) {
                    AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.76f).fillMaxHeight(0.74f)
                    )
                }
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Black, 0.26f to Black.copy(alpha = 0.95f), 0.55f to Black.copy(alpha = 0.35f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.42f to Black.copy(alpha = 0.45f), 0.66f to Black, 1f to Black)
            )
        )

        Column(Modifier.fillMaxSize()) {
            if (spot != null) Spotlight(spot, nav, playFocus, onChanged, Modifier.height(spotH))
            LazyColumn(Modifier.weight(1f), state = listState) {
                homeRows(data, nav, ctx, onFocus = { pending = it }, tvRows = tvRows)
            }
        }
    }
}

@Composable
private fun Spotlight(item: Item, nav: Nav, playFocus: FocusRequester, onChanged: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val title = if (item.isEpisode) item.seriesName ?: item.name else item.name
    Column(
        modifier.padding(start = 40.dp, end = 40.dp, top = 22.dp).widthIn(max = 660.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(kindLabel(item), color = Orange, style = MaterialTheme.typography.labelMedium)
        Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.isEpisode) Text(item.episodeLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MetaRow(item)
        Text(
            item.overview.orEmpty(), color = TextMuted, maxLines = if (item.isEpisode) 2 else 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(if (item.isEpisode) 44.dp else 66.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            SbButton(
                when {
                    item.isSeries -> "Ver episódios"
                    (item.userData?.positionTicks ?: 0) > 0 -> "Continuar"
                    else -> "Assistir"
                },
                onClick = { ctx.playOrOpen(item, nav) },
                icon = Icons.Filled.PlayArrow, modifier = Modifier.focusRequester(playFocus)
            )
            SbButton("Detalhes", onClick = { nav.push(Route.Details(if (item.isEpisode) item.seriesId ?: item.id else item.id)) }, icon = Icons.Filled.Info, primary = false)
            WatchedButton(item, onChanged)
        }
    }
}

@Composable
private fun MetaRow(item: Item) {
    val parts = listOfNotNull(
        item.year?.toString(), item.runtimeLabel, item.officialRating,
        item.rating?.let { "★ %.1f".format(it) },
        item.genres.take(3).joinToString(" · ").ifBlank { null },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (parts.isNotEmpty()) Text(parts.joinToString("   ·   "), color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (Watched.isPlayed(item)) Text("✓ Assistido", color = Success)
    }
}

/* ============================== Celular: carrossel ============================== */

@Composable
private fun PhoneHome(data: HomeData, slides: List<Item>, nav: Nav, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        if (slides.isNotEmpty()) item { HeroCarousel(slides, nav, onChanged) }
        homeRows(data, nav, ctx, onFocus = null)
    }
}

@Composable
private fun HeroCarousel(slides: List<Item>, nav: Nav, onChanged: () -> Unit) {
    val jf = App.session.jellyfin
    val ctx = LocalContext.current
    val pager = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()

    // Troca de slide automática a cada 7 s
    LaunchedEffect(pager.settledPage, slides.size) {
        if (slides.size < 2) return@LaunchedEffect
        delay(7000)
        pager.animateScrollToPage((pager.settledPage + 1) % slides.size)
    }
    fun go(delta: Int) {
        scope.launch { pager.animateScrollToPage((pager.currentPage + delta + slides.size) % slides.size) }
    }

    Box(Modifier.fillMaxWidth().height(300.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = jf.backdropImage(slides[page]), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Black, Black.copy(alpha = 0.65f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Black), startY = 320f)))

        val item = slides[pager.currentPage.coerceIn(0, slides.lastIndex)]
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 18.dp).widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (item.isSeries) "SÉRIE EM DESTAQUE" else "FILME EM DESTAQUE", color = Orange, style = MaterialTheme.typography.labelMedium)
            Text(item.name, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetaRow(item)
            if (!item.overview.isNullOrBlank()) {
                Text(item.overview, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextMuted)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                SbButton(
                    if ((item.userData?.positionTicks ?: 0) > 0) "Continuar" else "Assistir",
                    onClick = { ctx.playOrOpen(item, nav) }, icon = Icons.Filled.PlayArrow,
                )
                SbButton("Detalhes", onClick = { nav.push(Route.Details(item.id)) }, icon = Icons.Filled.Info, primary = false)
                WatchedButton(item, onChanged, compact = true)
                if (slides.size > 1) {
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Anterior") { go(-1) }
                    RoundIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Próximo") { go(1) }
                }
            }
            if (slides.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    slides.indices.forEach { i ->
                        val on = i == pager.currentPage
                        Box(
                            Modifier.height(4.dp).width(if (on) 22.dp else 8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (on) Orange else Color.White.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).tvFocus(CircleShape, 1.08f).clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = label, tint = tint) }
}
