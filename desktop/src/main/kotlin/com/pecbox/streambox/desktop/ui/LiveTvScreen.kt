package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.data.ExternalGuide
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.player.LiveEntry
import com.pecbox.streambox.desktop.player.PlayerController
import com.pecbox.streambox.ui.ChannelGenres
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Guia da lista M3U já carregado (a tela pode ser aberta várias vezes sem ler os arquivos de novo). */
private object GuideCache {
    var key: String? = null
    var guide: ExternalGuide? = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiveTvScreen(s: Session, player: PlayerController, openSettings: () -> Unit) {
    val hasExternal = s.m3uUrl.isNotBlank()
    var jfChannels by remember { mutableStateOf<List<Item>?>(null) }
    var jfError by remember { mutableStateOf<String?>(null) }
    var jfRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(jfRetry) {
        jfError = null
        try {
            jfChannels = s.jellyfin.liveChannels()
        } catch (e: Exception) {
            jfError = e.friendly()
            if (jfChannels == null) jfChannels = null
        }
    }

    var tabChoice by remember { mutableIntStateOf(-1) } // -1 = automático
    val tab = when {
        tabChoice >= 0 -> tabChoice
        jfChannels?.isEmpty() == true && hasExternal -> 1
        jfError != null && hasExternal -> 1
        else -> 0
    }
    var group by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }

    // Relógio para atualizar "Agora / Depois" e as barras de progresso
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 40.dp, end = 40.dp, top = 24.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TV ao vivo", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Jellyfin", selected = tab == 0, onClick = { tabChoice = 0; group = ""; genre = "" })
                Chip("Minha lista", selected = tab == 1, onClick = { tabChoice = 1; group = ""; genre = "" })
                // Último canal assistido: abre direto nele (como ligar o decodificador)
                val last = remember { player.lastChannel() }
                if (last != null) {
                    SbButton("Último canal: ${last.name}", onClick = { player.playLive(listOf(last), 0) }, icon = Icons.Filled.PlayArrow, primary = false)
                }
            }
            OutlinedTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Filtrar canais…") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            )
        }

        when {
            tab == 0 -> JellyfinChannels(s, player, jfChannels, jfError, filter, genre, { genre = it }, now) { jfRetry++ }
            !hasExternal -> Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nenhuma lista configurada.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Em Ajustes você pode informar o endereço de uma lista M3U de canais e, se quiser, de um guia de programação (EPG/XMLTV).",
                    color = TextMuted,
                )
                SbButton("Abrir Ajustes", onClick = openSettings)
            }
            else -> ExternalChannels(s, player, group, { group = it }, genre, { genre = it }, filter, now)
        }
    }
}

/* ------------------------------ Jellyfin ------------------------------ */

@Composable
private fun JellyfinChannels(
    s: Session, player: PlayerController, data: List<Item>?, error: String?, filter: String,
    genre: String, onGenre: (String) -> Unit, now: Long, onRetry: () -> Unit,
) {
    val jf = s.jellyfin
    when {
        data == null && error != null -> ErrorBox("TV ao vivo do Jellyfin indisponível: $error", onRetry)
        data == null -> Loading()
        data.isEmpty() -> ErrorBox("Nenhum canal configurado no Jellyfin (Painel → TV ao vivo).")
        else -> {
            val genreOf = remember(data) { data.associate { it.id to ChannelGenres.classify(it.name) } }
            val list = data.filter {
                (genre.isBlank() || genreOf[it.id] == genre) && (filter.isBlank() || it.name.contains(filter, ignoreCase = true))
            }
            Column(Modifier.fillMaxSize()) {
                GenreChips(genreOf.values, genre, onGenre)
                LazyColumn(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { ch ->
                        val prog = ch.currentProgram
                        val start = parseIso(prog?.startDate)
                        val end = parseIso(prog?.endDate)
                        ChannelRow(
                            number = ch.channelNumber, name = ch.name, logo = jf.channelLogo(ch),
                            nowTitle = prog?.name,
                            nowTime = if (start != null && end != null) "${hhmm(start)} – ${hhmm(end)}" else null,
                            progress = if (start != null && end != null && end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f,
                            nextTitle = null,
                            onClick = {
                                // A lista visível vai junto: no player ↑/↓ trocam entre esses canais
                                val queue = list.map { c ->
                                    LiveEntry(c.name, jf.channelLogo(c), c.currentProgram?.name, jfId = c.id, number = c.channelNumber, group = genreOf[c.id])
                                }
                                player.playLive(queue, list.indexOf(ch))
                            },
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ Lista M3U ------------------------------ */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExternalChannels(
    s: Session, player: PlayerController, group: String, onGroup: (String) -> Unit,
    genre: String, onGenre: (String) -> Unit, filter: String, now: Long,
) {
    val key = "${s.m3uUrl}|${s.epgUrl}"
    var guide by remember(key) { mutableStateOf(GuideCache.guide.takeIf { GuideCache.key == key }) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, reload) {
        if (guide != null && reload == 0) return@LaunchedEffect
        error = null
        try {
            val g = s.externalTv.load(s.m3uUrl, s.epgUrl, force = reload > 0)
            GuideCache.key = key
            GuideCache.guide = g
            guide = g
        } catch (e: Exception) {
            error = e.friendly()
        }
    }
    val g = guide
    when {
        g == null && error != null -> ErrorBox(error ?: "", onRetry = { reload++ })
        g == null -> Column(Modifier.fillMaxSize().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Loading(Modifier.padding(bottom = 12.dp))
            Text("Baixando lista e guia… (a primeira vez pode demorar)", color = TextMuted)
        }
        else -> {
            val groups = remember(g) { g.channels.groupingBy { it.group }.eachCount().toList().sortedBy { it.first.lowercase() } }
            val genreOf = remember(g) { g.channels.associateWith { ChannelGenres.classify(it.name, it.group) } }
            val list = remember(g, group, genre, filter) {
                g.channels.filter {
                    (group.isBlank() || it.group == group) && (genre.isBlank() || genreOf[it] == genre) &&
                        (filter.isBlank() || it.name.contains(filter, ignoreCase = true))
                }
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.padding(start = 40.dp, end = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (groups.size > 1) {
                        GroupMenu(listOf("" to "Todos os grupos · ${g.channels.size}") + groups.map { (name, count) -> name to "$name · $count" }, group, onGroup)
                    }
                    SbButton("Atualizar lista", onClick = { s.externalTv.clearCache(); reload++ }, icon = Icons.Filled.Refresh, primary = false)
                    Text("${list.size} canais" + if (g.programmes.isNotEmpty()) " · com guia" else "", color = TextMuted)
                }
                GenreChips(genreOf.values, genre, onGenre)
                LazyColumn(contentPadding = PaddingValues(horizontal = 40.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.url + it.name }) { ch ->
                        val cur = g.now(ch, now)
                        val next = g.next(ch, now)
                        ChannelRow(
                            number = ch.number, name = ch.name, logo = ch.logo,
                            nowTitle = cur?.title,
                            nowTime = cur?.let { "${hhmm(it.start)} – ${hhmm(it.stop)}" },
                            progress = cur?.progress(now) ?: 0f,
                            nextTitle = next?.let { "${hhmm(it.start)} ${it.title}" },
                            onClick = {
                                val queue = list.map { c ->
                                    LiveEntry(
                                        c.name, c.logo, g.now(c, now)?.title, url = c.url, userAgent = c.userAgent, referer = c.referer,
                                        number = c.number, group = genreOf[c] ?: c.group,
                                    )
                                }
                                player.playLive(queue, list.indexOf(ch))
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Filtro de gênero (Abertos, Notícias, Esportes…): só os que existem na lista, com a contagem. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(all: Collection<String>, selected: String, onSelect: (String) -> Unit) {
    val counts = all.groupingBy { it }.eachCount()
    val present = ChannelGenres.ORDER.filter { (counts[it] ?: 0) > 0 }
    if (present.size < 2) return
    FlowRow(
        Modifier.padding(horizontal = 40.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Chip("Todos · ${all.size}", selected = selected.isBlank(), onClick = { onSelect("") })
        present.forEach { gname -> Chip("$gname · ${counts[gname]}", selected = selected == gname, onClick = { onSelect(gname) }) }
    }
}

@Composable
private fun GroupMenu(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SbButton(
            options.firstOrNull { it.first == selected }?.second ?: "Grupo",
            onClick = { open = true }, icon = Icons.Filled.ArrowDropDown, primary = selected.isNotBlank(),
        )
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 520.dp)) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (value == selected) Orange else TextMain) },
                    onClick = { onSelect(value); open = false },
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: String?, name: String, logo: String?,
    nowTitle: String?, nowTime: String?, progress: Float, nextTitle: String?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .highlight(interaction, shape, 1.01f)
            .clip(shape)
            .background(Surface)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.width(110.dp).height(62.dp).clip(RoundedCornerShape(8.dp)).background(Surface3),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(2).uppercase(), color = Orange, style = MaterialTheme.typography.titleMedium)
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(listOfNotNull(number, name).joinToString("  "), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nowTitle != null) {
                Text(
                    "Agora: $nowTitle" + (nowTime?.let { "  ·  $it" } ?: ""),
                    color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                )
                Box(Modifier.widthIn(max = 520.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Surface3)) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Orange))
                }
            }
            if (nextTitle != null) {
                Text("Depois: $nextTitle", color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Icon(Icons.Filled.PlayArrow, null, tint = TextMuted)
    }
}

/* ------------------------------ datas ------------------------------ */

private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun parseIso(v: String?): Long? = v?.let { runCatching { isoFormat.parse(it.take(19))?.time }.getOrNull() }

private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
