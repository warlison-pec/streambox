package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.IndexerItem
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.SeerrResult
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.data.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CELL = 170.dp

/**
 * Busca em três fontes, como no app Android: a biblioteca do Jellyfin, o STRM Indexer
 * ("Disponível para solicitar") e o Seerr ("Pedir no Seerr"). As duas últimas só aparecem se configuradas.
 * O termo fica guardado em [query] (no Shell), para não se perder ao abrir um título e voltar.
 */
@Composable
fun SearchScreen(s: Session, query: String, onQuery: (String) -> Unit, open: (Item) -> Unit) {
    var library by remember { mutableStateOf<List<Item>>(emptyList()) }
    var available by remember { mutableStateOf<List<IndexerItem>>(emptyList()) }
    var seerr by remember { mutableStateOf<List<SeerrResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

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

    LazyVerticalGrid(
        columns = GridCells.Adaptive(CELL),
        contentPadding = PaddingValues(horizontal = 40.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        fullLine {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                placeholder = { Text("Buscar filmes e séries…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.trim().length < 2 && !s.indexer.enabled && !s.seerr.enabled) {
            fullLine {
                Text(
                    "Para buscar também títulos fora da biblioteca, configure o STRM Indexer ou o Seerr em Ajustes.",
                    color = TextMuted,
                )
            }
        }
        if (loading) fullLine { Loading(Modifier.fillMaxWidth().padding(12.dp)) }
        error?.let { msg -> fullLine { Text(msg, color = Danger) } }

        if (library.isNotEmpty()) {
            fullLine { SectionTitle("Na sua biblioteca", "${library.size} resultado(s)") }
            items(library, key = { "lib:" + it.id }) { item ->
                MediaCard(
                    title = item.name,
                    subtitle = listOfNotNull(if (item.isSeries) "Série" else "Filme", item.year?.toString()).joinToString(" · "),
                    image = s.jellyfin.posterImage(item), width = CELL, progress = item.progress, rating = item.rating,
                    onClick = { open(item) }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (available.isNotEmpty()) {
            fullLine { SectionTitle("Disponível para solicitar", "Ainda fora da biblioteca · adicionado automaticamente se a fonte responder") }
            items(available, key = { "req:" + it.key }) { item -> RequestCard(s, item, CELL) }
        }

        if (seerr.isNotEmpty()) {
            fullLine { SectionTitle("Pedir no Seerr", "Não está nas listas? Peça para baixar · ${s.seerrUser}") }
            items(seerr, key = { "seerr:" + it.mediaType + it.id }) { item -> SeerrCard(s, item, CELL) }
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

/** Título do STRM Indexer: pedir (filme, série inteira ou uma temporada) e acompanhar até entrar na biblioteca. */
@Composable
private fun RequestCard(s: Session, item: IndexerItem, width: Dp) {
    val idx = s.indexer
    val scope = rememberCoroutineScope()
    var state by remember(item.key) { mutableStateOf(if (item.requested) "queued" else "") }
    var message by remember(item.key) { mutableStateOf("") }
    var requestId by remember(item.key) { mutableStateOf<String?>(null) }
    var seasonIndex by remember(item.key) { mutableIntStateOf(-1) } // -1 = todas

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

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaCard(title = item.title, subtitle = subtitle, image = idx.art(item.poster), width = width, onClick = {}, badge = "STRM", modifier = Modifier.fillMaxWidth())
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
                message, style = MaterialTheme.typography.bodyMedium, maxLines = 3,
                color = when (state) {
                    "available" -> Success
                    "", "failed", "unavailable" -> Danger
                    else -> Orange
                },
            )
        }
    }
}

@Composable
private fun SeerrCard(s: Session, item: SeerrResult, width: Dp) {
    val seerr = s.seerr
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
    var seasonIndex by remember(item.mediaType, item.id) { mutableIntStateOf(-1) }
    val isTv = item.mediaType == "tv"

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MediaCard(
            title = item.displayTitle,
            subtitle = listOfNotNull(if (isTv) "Série" else "Filme", item.year).joinToString(" · "),
            image = item.poster, width = width, onClick = {}, badge = "SEERR", modifier = Modifier.fillMaxWidth(),
        )
        if (isTv && (state == "" || state == "partial")) {
            val list = seasons
            val label = if (list == null || seasonIndex < 0) "Todas" else "T${list[seasonIndex]}"
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
            Text(message, style = MaterialTheme.typography.bodyMedium, maxLines = 3, color = if (state == "requested") Success else Danger)
        }
    }
}
