package com.pecbox.streambox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.pecbox.streambox.ui.components.SbTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.data.IndexerItem
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.SeerrResult
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.MediaCard
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.theme.Danger
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Success
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(nav: Nav) {
    val s = App.session
    val tv = LocalIsTv.current
    var query by rememberSaveable { mutableStateOf("") }
    var library by remember { mutableStateOf<List<Item>>(emptyList()) }
    var available by remember { mutableStateOf<List<IndexerItem>>(emptyList()) }
    var seerr by remember { mutableStateOf<List<SeerrResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            library = emptyList(); available = emptyList(); seerr = emptyList(); error = null; loading = false
            return@LaunchedEffect
        }
        delay(400) // espera parar de digitar
        loading = true
        error = null
        coroutineScope {
            val lib = async { runCatching { s.jellyfin.search(q) } }
            val idx = async { runCatching { s.indexer.search(q) } }
            val sr = async { runCatching { s.seerr.search(q) } }
            lib.await().onSuccess { library = it }.onFailure { if (it is CancellationException) throw it; error = it.friendly() }
            idx.await().onSuccess { available = it }.onFailure { if (it is CancellationException) throw it; available = emptyList() }
            // Esconde do Seerr o que já está disponível (aparece em "Na sua biblioteca")
            sr.await().onSuccess { r -> seerr = r.filter { it.status != 5 } }.onFailure { if (it is CancellationException) throw it; seerr = emptyList() }
        }
        loading = false
    }

    val cell: Dp = if (tv) 150.dp else 108.dp
    LazyVerticalGrid(
        columns = GridCells.Adaptive(cell),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = if (tv) 40.dp else 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 16.dp),
    ) {
        fullLine {
            SbTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Buscar filmes e séries…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
            )
        }
        if (loading) fullLine { Loading(Modifier.fillMaxWidth().padding(12.dp)) }
        error?.let { msg -> fullLine { Text(msg, color = Danger) } }

        if (library.isNotEmpty()) {
            fullLine { SectionTitle("Na sua biblioteca", "${library.size} resultado(s)") }
            items(library, key = { "lib:" + it.id }) { item ->
                MediaCard(
                    title = item.name, subtitle = listOfNotNull(if (item.isSeries) "Série" else "Filme", item.year?.toString()).joinToString(" · "),
                    image = s.jellyfin.posterImage(item), width = cell, progress = item.progress,
                    onClick = { nav.push(Route.Details(item.id)) },
                )
            }
        }

        if (available.isNotEmpty()) {
            fullLine { SectionTitle("Disponível para solicitar", "Ainda fora da biblioteca · adicionado automaticamente se a fonte responder") }
            items(available, key = { "req:" + it.key }) { item -> RequestCard(item, cell) }
        }

        if (seerr.isNotEmpty()) {
            fullLine { SectionTitle("Pedir no Seerr", "Não está nas listas? Peça para baixar · ${App.session.seerrUser}") }
            items(seerr, key = { "seerr:" + it.mediaType + it.id }) { item -> SeerrCard(item, cell) }
        }

        if (!loading && query.trim().length >= 2 && library.isEmpty() && available.isEmpty() && seerr.isEmpty() && error == null) {
            fullLine { Text("Nada encontrado para “${query.trim()}”.", color = TextMuted) }
        }
    }
}

private fun LazyGridScope.fullLine(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

private val ACTIVE = setOf("queued", "validating", "importing")

@Composable
private fun RequestCard(item: IndexerItem, width: Dp) {
    val idx = App.session.indexer
    val scope = rememberCoroutineScope()
    var state by remember(item.key) { mutableStateOf(if (item.requested) "queued" else "") }
    var message by remember(item.key) { mutableStateOf("") }
    var requestId by remember(item.key) { mutableStateOf<String?>(null) }
    var seasonIndex by remember(item.key) { mutableStateOf(-1) } // -1 = todas

    // Acompanha a solicitação até terminar
    LaunchedEffect(requestId) {
        val id = requestId ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            val st = runCatching { idx.status(id) }.getOrNull() ?: continue
            state = st.state
            message = st.message
            if (st.state !in ACTIVE) break
        }
    }

    val subtitle = if (item.kind == "movie") {
        listOfNotNull("Filme", item.year?.toString()).joinToString(" · ")
    } else {
        val have = item.inLibrary ?: 0
        "Série · " + (if (have > 0) "$have/${item.episodes} ep." else "${item.seasons.size} temp.")
    }

    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaCard(title = item.title, subtitle = subtitle, image = idx.art(item.poster), width = width, onClick = {}, badge = "STRM")
        if (item.kind == "series" && item.seasons.size > 1 && state.isEmpty()) {
            val label = if (seasonIndex < 0) "Todas" else "T${item.seasons[seasonIndex]}"
            Chip("Temporada: $label", selected = false, onClick = {
                seasonIndex = if (seasonIndex + 1 >= item.seasons.size) -1 else seasonIndex + 1
            })
        }
        val (label, enabled) = when (state) {
            "" -> "Solicitar" to true
            "queued" -> "Na fila…" to false
            "validating" -> "Validando…" to false
            "importing" -> "Importando…" to false
            "available" -> "✓ Adicionado" to false
            "failed" -> "Falhou" to true
            else -> "Sem fonte agora" to true
        }
        SbButton(label, enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                state = "queued"
                message = ""
                try {
                    val season = when {
                        item.kind != "series" -> null
                        seasonIndex >= 0 -> item.seasons[seasonIndex]
                        item.seasons.size == 1 -> item.seasons[0]
                        else -> null
                    }
                    requestId = idx.request(item, season).id
                } catch (e: Exception) {
                    state = ""
                    message = e.friendly()
                }
            }
        })
        if (message.isNotBlank()) {
            Text(
                message, style = MaterialTheme.typography.bodyMedium,
                color = when (state) {
                    "available" -> Success
                    "", "failed", "unavailable" -> Danger
                    else -> Orange
                },
                maxLines = 3
            )
        }
    }
}

@Composable
private fun SeerrCard(item: SeerrResult, width: Dp) {
    val seerr = App.session.seerr
    val scope = rememberCoroutineScope()
    // 2/3 = já pedido · 4 = parcialmente disponível
    var state by remember(item.mediaType, item.id) {
        mutableStateOf(
            when (item.status) {
                2, 3 -> "requested"
                4 -> "partial"
                else -> ""
            }
        )
    }
    var message by remember(item.mediaType, item.id) { mutableStateOf("") }
    var seasons by remember(item.mediaType, item.id) { mutableStateOf<List<Int>?>(null) }
    var seasonIndex by remember(item.mediaType, item.id) { mutableStateOf(-1) }
    val isTv = item.mediaType == "tv"

    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaCard(
            title = item.displayTitle,
            subtitle = listOfNotNull(if (isTv) "Série" else "Filme", item.year).joinToString(" · "),
            image = item.poster, width = width, onClick = {}, badge = "SEERR"
        )
        if (isTv && (state == "" || state == "partial")) {
            val list = seasons
            val label = when {
                list == null || seasonIndex < 0 -> "Todas"
                else -> "T${list[seasonIndex]}"
            }
            Chip("Temporada: $label", selected = false, onClick = {
                val current = seasons
                if (current == null) {
                    scope.launch {
                        val loaded = runCatching { seerr.seasons(item.id) }.getOrDefault(emptyList())
                        seasons = loaded
                        seasonIndex = if (loaded.isEmpty()) -1 else 0
                    }
                } else {
                    seasonIndex = if (seasonIndex + 1 >= current.size) -1 else seasonIndex + 1
                }
            })
        }
        val (label, enabled) = when (state) {
            "" -> "Pedir" to true
            "partial" -> "Pedir o restante" to true
            "sending" -> "Enviando…" to false
            "requested" -> "✓ Solicitado" to false
            else -> "Tentar de novo" to true
        }
        SbButton(label, enabled = enabled, primary = state != "requested", modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                state = "sending"
                message = ""
                try {
                    val list = seasons
                    val chosen = if (isTv && list != null && seasonIndex >= 0) listOf(list[seasonIndex]) else null
                    seerr.request(item, chosen)
                    state = "requested"
                    message = "Pedido enviado ao Seerr."
                } catch (e: Exception) {
                    state = "error"
                    message = e.friendly()
                }
            }
        })
        if (message.isNotBlank()) {
            Text(
                message, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                color = if (state == "requested") Success else Danger
            )
        }
    }
}
