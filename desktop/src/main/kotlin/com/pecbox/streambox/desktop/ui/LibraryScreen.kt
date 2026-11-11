package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.desktop.data.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

private val Sorts = listOf(
    Triple("A–Z", "SortName", false),
    Triple("Adicionados", "DateCreated", true),
    Triple("Lançamento", "PremiereDate,ProductionYear", true),
    Triple("Avaliação", "CommunityRating", true),
)

/** Grade da biblioteca com ordenação; carrega mais ao chegar no fim. */
@Composable
fun LibraryScreen(s: Session, view: Item, open: (Item) -> Unit) {
    val jf = s.jellyfin
    val types = libraryItemTypes(view.collectionType) ?: "Movie,Series"
    var sort by remember(view.id) { mutableStateOf(Sorts[1]) }
    var list by remember(view.id, sort) { mutableStateOf<List<Item>>(emptyList()) }
    var total by remember(view.id, sort) { mutableStateOf(-1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val grid = rememberLazyGridState()

    suspend fun loadMore() {
        if (loading || (total >= 0 && list.size >= total)) return
        loading = true
        try {
            val r = jf.library(view.id, types, list.size, 120, sort.second, sort.third)
            list = list + r.items
            total = r.total
            error = null
        } catch (e: Exception) {
            error = e.friendly()
        } finally {
            loading = false
        }
    }

    LaunchedEffect(view.id, sort, retry) {
        if (list.isEmpty()) grid.scrollToItem(0)
        loadMore()
    }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > list.size - 30 } }
    LaunchedEffect(view.id, sort) {
        snapshotFlow { nearEnd }.filter { it }.collect { loadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        state = grid,
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(view.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    if (total > 0) Text("$total títulos", color = TextMuted)
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sorts.forEach { o -> Chip(o.first, selected = sort == o, onClick = { sort = o }) }
                }
            }
        }
        items(list, key = { it.id }) { item ->
            MediaCard(
                title = item.name, subtitle = item.year?.toString(), image = jf.posterImage(item),
                played = item.userData?.played == true, rating = item.rating, width = 170.dp,
                onClick = { open(item) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (error != null) item(span = { GridItemSpan(maxLineSpan) }) {
            ErrorBox(error ?: "", onRetry = { error = null; retry++ }, Modifier.fillMaxWidth())
        }
        if (loading) item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.fillMaxWidth().padding(24.dp)) }
    }
}

