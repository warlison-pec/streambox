@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.pecbox.streambox.ui.screens

import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import com.pecbox.streambox.ui.Favorites
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
import com.pecbox.streambox.ui.PlaybackEvents
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

private data class HomeData(
    val featured: List<Item>,
    val resume: List<Item>,
    val nextUp: List<Item>,
    val favorites: List<Item>,
    val latest: List<Pair<String, List<Item>>>,
)

@Composable
fun HomeScreen(nav: Nav) {
    val jf = App.session.jellyfin
    val home = rememberLoader("home") {
        coroutineScope {
            val resume = async { runCatching { jf.resume() }.getOrDefault(emptyList()) }
            val next = async { runCatching { jf.nextUp() }.getOrDefault(emptyList()) }
            val favs = async { runCatching { jf.favorites() }.getOrDefault(emptyList()) }
            val movies = async { runCatching { jf.featured("Movie", 6) }.getOrDefault(emptyList()) }
            val series = async { runCatching { jf.featured("Series", 6) }.getOrDefault(emptyList()) }
            val libs = jf.views().filter { libraryItemTypes(it.collectionType) != null && it.collectionType != "boxsets" }
            val latest = libs.map { v ->
                v.name to async { runCatching { jf.latest(v.id, libraryItemTypes(v.collectionType) ?: "Movie,Series") }.getOrDefault(emptyList()) }
            }
            // Intercala filmes e séries nos destaques (até 12)
            val m = movies.await(); val sr = series.await()
            val featured = (0 until maxOf(m.size, sr.size)).flatMap { i -> listOfNotNull(m.getOrNull(i), sr.getOrNull(i)) }
            HomeData(featured, resume.await(), next.await(), favs.await(), latest.map { (n, d) -> n to d.await() })
        }
    }
    // Player fechou: recarrega (o episódio terminado sai de "Próximos episódios" e de "Continuar")
    val closed = PlaybackEvents.version
    var seenClose by remember { mutableIntStateOf(closed) }
    LaunchedEffect(closed) {
        if (closed == seenClose) return@LaunchedEffect
        seenClose = closed
        home.reload()
        delay(2500)          // o Jellyfin às vezes demora a atualizar o "Próximos": confere de novo
        home.reload()
    }
    // "Ocultar assistidos" (Ajustes): o que for marcado como visto agora some na hora, sem recarregar
    val hide = App.session.hideWatched
    fun List<Item>.unseen() = if (hide) filterNot { Watched.isPlayed(it) } else this
    val raw = home.data
    val homeData = raw?.let { d ->
        d.copy(
            featured = d.featured.unseen(), favorites = d.favorites.unseen(), latest = d.latest.map { (n, l) -> n to l.unseen() },
            // Episódio já assistido nunca fica em "Continuar" nem em "Próximos episódios"
            resume = d.resume.filterNot { Watched.isPlayed(it) },
            nextUp = d.nextUp.filterNot { Watched.isPlayed(it) },
        )
    }
    val data = homeData
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
@OptIn(ExperimentalFoundationApi::class)
private class TvRows(
    val poster: Dp, val rowH: Dp, val endSpace: Dp,
    val spec: BringIntoViewSpec,            // rolagem padrão, devolvida às fileiras horizontais
    val rowFocus: (Int) -> Unit,
)

/** Na TV: fileira com altura fixa (cabe inteira) e que avisa quando ganha foco. */
private fun Modifier.rowFocus(tv: TvRows?, index: Int): Modifier =
    if (tv == null) this else height(tv.rowH).onFocusChanged { if (it.hasFocus) tv.rowFocus(index) }

/** Devolve a rolagem automática padrão dentro da fileira (os cartões seguem o foco para os lados). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowSpec(tv: TvRows?, content: @Composable () -> Unit) {
    if (tv == null) content() else CompositionLocalProvider(LocalBringIntoViewSpec provides tv.spec, content = content)
}

private fun LazyListScope.homeRows(data: HomeData, nav: Nav, ctx: Context, onFocus: ((Item) -> Unit)?, tvRows: TvRows? = null) {
    val jf = App.session.jellyfin
    val text = tvRows == null
    val topPad = if (tvRows != null) 6.dp else 18.dp
    // capas: altura calculada pela tela na TV; no celular, larguras fixas como antes
    val posterW = tvRows?.let { it.poster * 2f / 3f }
    val wideW = tvRows?.let { it.poster * 0.82f * 16f / 9f }
    item { RowSpec(tvRows) {
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
    } }
    item { RowSpec(tvRows) {
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
    } }
    item { RowSpec(tvRows) {
        MediaRow("Minha lista", data.favorites.filter { Favorites.isFavorite(it) }, Modifier.rowFocus(tvRows, 2), topPad) {
            MediaCard(
                title = it.name, subtitle = it.year?.toString(), image = jf.posterImage(it), rating = it.rating,
                showText = text, width = posterW ?: if (LocalIsTv.current) 150.dp else 112.dp,
                badge = it.userData?.unplayed?.takeIf { n -> n > 0 && !Watched.isPlayed(it) }?.toString(),
                played = Watched.isPlayed(it),
                onFocus = focusOf(onFocus, it),
                onClick = { nav.push(Route.Details(it.id)) },
            )
        }
    } }
    itemsIndexed(data.latest) { i, (name, list) -> RowSpec(tvRows) {
        MediaRow("Adicionados em $name", list, Modifier.rowFocus(tvRows, 3 + i), topPad) {
            MediaCard(
                title = it.name, subtitle = it.year?.toString(), image = jf.posterImage(it), rating = it.rating,
                showText = text, width = posterW ?: if (LocalIsTv.current) 150.dp else 112.dp,
                badge = it.userData?.unplayed?.takeIf { n -> n > 0 && !Watched.isPlayed(it) }?.toString(),
                played = Watched.isPlayed(it),
                onFocus = focusOf(onFocus, it),
                onClick = { nav.push(Route.Details(it.id)) },
            )
        }
    } }
    // espaço no fim para que a última fileira também consiga subir até o topo
    item { Spacer(Modifier.height(tvRows?.endSpace ?: 48.dp)) }
}

/** Botão "Minha lista" (favoritos do Jellyfin). */
@Composable
private fun FavoriteButton(item: Item, onChanged: () -> Unit, compact: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val fav = Favorites.isFavorite(item)
    val label = if (fav) "Na Minha lista" else "Minha lista"
    val icon = if (fav) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder
    if (compact) {
        RoundIcon(icon, label, tint = if (fav) Success else Color.White) {
            scope.launch { if (Favorites.toggle(ctx, item) != null) onChanged() }
        }
    } else {
        SbButton(label, primary = false, icon = icon,
            onClick = { scope.launch { if (Favorites.toggle(ctx, item) != null) onChanged() } })
    }
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
        /* Dois estados (como sugerido para a TV):
           - foco no destaque: destaque grande com os botões e as fileiras espiando embaixo;
           - foco nas fileiras: o destaque vira uma faixa (título, dados e sinopse do item em foco)
             e as fileiras ganham o resto da tela; a fileira em foco fica inteira no topo. */
        var browsing by remember { mutableStateOf(false) }
        var curRow by remember { mutableIntStateOf(-1) }
        val firstRow = when { data.resume.isNotEmpty() -> 0; data.nextUp.isNotEmpty() -> 1; data.favorites.isNotEmpty() -> 2; else -> 3 }
        val fullH = (maxHeight * 0.5f).coerceIn(230.dp, 380.dp)
        val compactH = (maxHeight * 0.33f).coerceIn(170.dp, 250.dp)
        val headerH by animateDpAsState(if (browsing) compactH else fullH, tween(300), label = "header")
        // fileira = título + margens do foco (~72) + capa; sobra um pedaço da próxima fileira
        val posterH = ((maxHeight - compactH - 72.dp) * 0.82f).coerceIn(120.dp, 330.dp)
        val rowH = posterH + 72.dp
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val defaultSpec = LocalBringIntoViewSpec.current
        val tvRows = remember(posterH, defaultSpec) {
            TvRows(posterH, rowH, maxHeight, defaultSpec) { index ->
                curRow = index
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
            // os botões continuam no lugar (cortados) na faixa compacta: ↑ na primeira fileira volta para eles
            Box(Modifier.fillMaxWidth().height(headerH).clipToBounds()) {
                if (spot != null) Spotlight(
                    spot, nav, playFocus, onChanged,
                    Modifier.wrapContentHeight(Alignment.Top, unbounded = true).height(fullH), compact = browsing,
                )
            }
            // sem rolagem automática na vertical: quem posiciona é a fileira em foco (sempre no topo)
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                LazyColumn(
                    Modifier
                        .weight(1f)
                        .onFocusChanged { browsing = it.hasFocus }
                        .onPreviewKeyEvent {
                            // ↑ na primeira fileira: volta para os botões do destaque
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionUp && curRow == firstRow) {
                                runCatching { playFocus.requestFocus() }; true
                            } else false
                        },
                    state = listState,
                ) {
                    homeRows(data, nav, ctx, onFocus = { pending = it }, tvRows = tvRows)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private val NoAutoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun Spotlight(item: Item, nav: Nav, playFocus: FocusRequester, onChanged: () -> Unit, modifier: Modifier, compact: Boolean = false) {
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
            item.overview.orEmpty(), color = TextMuted, maxLines = if (item.isEpisode || compact) 2 else 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.height(if (item.isEpisode || compact) 44.dp else 66.dp)
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
            FavoriteButton(item, onChanged)
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
                FavoriteButton(item, onChanged, compact = true)
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
