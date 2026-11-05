package com.pecbox.streambox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.MediaCard
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.toRoute

/** Lista de todas as bibliotecas (no celular, onde a barra inferior não comporta todas). */
@Composable
fun LibrariesScreen(libs: List<Item>, nav: Nav) {
    val jf = App.session.jellyfin
    val tv = LocalIsTv.current
    if (libs.isEmpty()) {
        Loading()
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (tv) 260.dp else 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 40.dp else 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Bibliotecas", style = MaterialTheme.typography.headlineMedium)
        }
        items(libs, key = { it.id }) { lib ->
            MediaCard(
                title = lib.name,
                subtitle = when (lib.collectionType) {
                    "movies" -> "Filmes"
                    "tvshows" -> "Séries"
                    "boxsets" -> "Coleções"
                    else -> "Vídeos"
                },
                image = jf.libraryImage(lib),
                landscape = true,
                width = if (tv) 260.dp else 160.dp,
                onClick = { nav.push(lib.toRoute()) },
            )
        }
    }
}
