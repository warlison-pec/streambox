package com.pecbox.streambox.ui.screens

import com.pecbox.streambox.ui.Watched
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.pecbox.streambox.ui.components.FilterBar
import com.pecbox.streambox.ui.components.FilterMenu
import com.pecbox.streambox.ui.Categories
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.ui.MemCache
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.ErrorBox
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.MediaCard
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.launch

private const val PAGE = 60

private data class Sort(val label: String, val field: String, val desc: Boolean)

private val SORTS = listOf(
    Sort("A–Z", "SortName", false),
    Sort("Adicionados", "DateCreated", true),
    Sort("Lançamento", "PremiereDate,ProductionYear", true),
    Sort("Avaliação", "CommunityRating", true),
)

/** Filtro de ano: chave → anos para o Jellyfin (separados por vírgula). */
private fun yearOptions(): List<Triple<String, String, String?>> {
    val now = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    fun span(a: Int, b: Int) = (a..b).joinToString(",")
    return buildList {
        add(Triple("", "Todos", null))
        for (y in now downTo now - 2) add(Triple("$y", "$y", "$y"))
        val dec = now / 10 * 10
        fun decade(d: Int) = "Anos ${if (d >= 2000) d else d % 100}"     // Anos 2020 … Anos 90, Anos 80
        add(Triple("d$dec", decade(dec), span(dec, now)))
        for (d in (dec - 10) downTo 1950 step 10) add(Triple("d$d", decade(d), span(d, d + 9)))
        add(Triple("old", "Antes de 1950", span(1900, 1949)))
    }
}

/** Estado paginado guardado em memória para não recarregar ao voltar dos detalhes. */
private class LibraryPage(val items: List<Item>, val total: Int)

@Suppress("UNCHECKED_CAST")
@Composable
fun LibraryScreen(id: String, name: String, collectionType: String?, nav: Nav) {
    val jf = App.session.jellyfin
    val types = libraryItemTypes(collectionType) ?: "Movie,Series"
    val tv = LocalIsTv.current
    var sortIndex by rememberSaveable(id) { mutableStateOf(0) }
    val sort = SORTS[sortIndex]
    // "" = todos, Categories.ANIME / DORAMA, ou o id de um gênero do Jellyfin
    var filter by rememberSaveable(id) { mutableStateOf("") }
    val special = filter == Categories.ANIME || filter == Categories.DORAMA
    var yearKey by rememberSaveable(id) { mutableStateOf("") }
    var studio by rememberSaveable(id) { mutableStateOf("") }
    val yearOpts = remember { yearOptions() }
    val years = yearOpts.firstOrNull { it.first == yearKey }?.third
    val cacheKey = "lib:$id:${sort.field}:$filter:$yearKey:$studio"

    val genresKey = "genres:$id"
    var genres by remember(id) { mutableStateOf(MemCache.map[genresKey] as List<Item>?) }
    val studiosKey = "studios:$id"
    var studios by remember(id) { mutableStateOf(MemCache.map[studiosKey] as List<Item>?) }
    LaunchedEffect(id) {
        if (genres == null) runCatching { jf.genres(id, types) }.onSuccess { genres = it; MemCache.map[genresKey] = it }
        if (studios == null) runCatching { jf.studios(id, types) }.onSuccess { studios = it; MemCache.map[studiosKey] = it }
    }

    var page by remember(cacheKey) { mutableStateOf(MemCache.map[cacheKey] as LibraryPage?) }
    var loading by remember(cacheKey) { mutableStateOf(false) }
    var error by remember(cacheKey) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun loadMore() {
        val current = page
        if (loading || (current != null && current.items.size >= current.total)) return
        loading = true
        scope.launch {
            try {
                val merged = if (special) {
                    val all = jf.libraryAll(id, types, sort.field, sort.desc, years, studio.ifEmpty { null })
                        .filter { Categories.matches(filter, it) }
                    LibraryPage(all, all.size)
                } else {
                    val start = current?.items?.size ?: 0
                    val result = jf.library(id, types, start, PAGE, sort.field, sort.desc, filter.ifEmpty { null },
                        years, studio.ifEmpty { null })
                    LibraryPage((current?.items ?: emptyList()) + result.items, result.total)
                }
                page = merged
                MemCache.map[cacheKey] = merged
            } catch (e: Exception) {
                if (page == null) error = e.friendly()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(cacheKey) { if (page == null) loadMore() }

    val grid = rememberLazyGridState()
    LaunchedEffect(grid, cacheKey) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val p = page ?: return@collect
            if (last >= p.items.size - 18 && p.items.size < p.total) loadMore()
        }
    }

    val current = page
    when {
        current == null && error != null -> ErrorBox(error ?: "", onRetry = { error = null; loadMore() })
        current == null -> Loading()
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(if (tv) 150.dp else 108.dp),
            state = grid,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = if (tv) 40.dp else 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    Text(name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (current.total == 0 && (filter + yearKey + studio).isNotEmpty()) "Nenhum título com esses filtros"
                        else "${current.total} títulos",
                        color = TextMuted,
                    )
                    // Filtros compactos: cada um abre a lista só no clique
                    val cats = buildList {
                        add("" to "Todos")
                        add(Categories.ANIME to "Anime")
                        if (types.contains("Series")) add(Categories.DORAMA to "Dorama")
                        genres.orEmpty()
                            .filterNot { it.name.equals("Anime", true) }
                            .forEach { add(it.id to it.name) }
                    }
                    FilterBar(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        FilterMenu("Ordenar", SORTS.mapIndexed { i, s -> "$i" to s.label }, "$sortIndex",
                            { sortIndex = it.toInt() }, defaultKey = "0", alwaysShowValue = true)
                        FilterMenu("Ano de lançamento", yearOpts.map { it.first to it.second }, yearKey, { yearKey = it })
                        FilterMenu("Gêneros", cats, filter, { filter = it })
                        if (!studios.isNullOrEmpty()) {
                            FilterMenu("Estúdios", listOf("" to "Todos") + studios.orEmpty().map { it.id to it.name }, studio, { studio = it })
                        }
                        if ((filter + yearKey + studio).isNotEmpty()) {
                            Chip("Limpar", selected = false, onClick = { filter = ""; yearKey = ""; studio = "" })
                        }
                    }
                }
            }
            items(current.items, key = { it.id }) { item ->
                MediaCard(
                    title = item.name,
                    subtitle = item.year?.toString(),
                    image = jf.posterImage(item),
                    played = Watched.isPlayed(item),
                    width = if (tv) 150.dp else 108.dp,
                    progress = item.progress,
                    rating = item.rating,
                    badge = item.userData?.unplayed?.takeIf { it > 0 }?.toString(),
                    onClick = { nav.push(Route.Details(item.id)) },
                )
            }
            if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.fillMaxWidth().padding(16.dp)) }
        }
    }
}
