package com.pecbox.streambox.ui.screens

import androidx.compose.material.icons.filled.PlayArrow
import com.pecbox.streambox.player.LiveQueue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.pecbox.streambox.ui.components.SbTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Channel
import com.pecbox.streambox.data.ExternalGuide
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.playExternalChannel
import com.pecbox.streambox.playJellyfinChannel
import com.pecbox.streambox.ui.MemCache
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.ErrorBox
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.rememberLoader
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.Surface3
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun LiveTvScreen(nav: Nav) {
    val s = App.session
    val tv = LocalIsTv.current
    val pad = if (tv) 40.dp else 16.dp
    val hasExternal = s.m3uUrl.isNotBlank()

    val jfLoader = rememberLoader("live:jf") { s.jellyfin.liveChannels() }
    val jfChannels = jfLoader.data.orEmpty()

    var tabChoice by rememberSaveable { mutableStateOf(-1) } // -1 = automático
    val tab = when {
        tabChoice >= 0 -> tabChoice
        jfChannels.isEmpty() && hasExternal && !jfLoader.loading -> 1
        else -> 0
    }
    var group by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("") }

    // Relógio para atualizar "agora / depois" e as barras de progresso
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = pad, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("TV ao vivo", style = MaterialTheme.typography.headlineMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("Jellyfin", selected = tab == 0, onClick = { tabChoice = 0; group = "" })
                Chip("Minha lista", selected = tab == 1, onClick = { tabChoice = 1; group = "" })
                // Último canal assistido: abre direto nele (como ligar o decodificador)
                val ctx = LocalContext.current
                val last = remember { LiveQueue.loadLast(ctx) }
                if (last != null) {
                    SbButton("Último canal: ${last.name}", onClick = {
                        if (LiveQueue.entries.none { it.url == last.url && it.jfId == last.jfId }) LiveQueue.entries = listOf(last)
                        LiveQueue.intent(ctx, last)?.let { ctx.startActivity(it) }
                    }, icon = Icons.Filled.PlayArrow, primary = false)
                }
            }
            SbTextField(
                value = filter, onValueChange = { filter = it }, singleLine = true,
                placeholder = { Text("Filtrar canais…") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            )
        }

        if (tab == 0) {
            JellyfinChannels(jfLoader.data, jfLoader.error, jfLoader.loading, filter, now, pad, onRetry = { jfLoader.reload() })
        } else if (!hasExternal) {
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Nenhuma lista configurada.", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Em Ajustes você pode informar o endereço de uma lista M3U de canais e, se quiser, de um guia de programação (EPG/XMLTV).",
                    color = TextMuted
                )
                SbButton("Abrir Ajustes", onClick = { nav.root(Route.Settings) })
            }
        } else {
            ExternalChannels(group, { group = it }, filter, now, pad)
        }
    }
}

/* ------------------------------ Jellyfin ------------------------------ */

@Composable
private fun JellyfinChannels(
    data: List<Item>?, error: String?, loading: Boolean, filter: String, now: Long, pad: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit,
) {
    val jf = App.session.jellyfin
    val ctx = LocalContext.current
    when {
        data == null && error != null -> ErrorBox("TV ao vivo do Jellyfin indisponível: $error", onRetry)
        data == null || (loading && data.isEmpty()) -> Loading()
        data.isEmpty() -> ErrorBox("Nenhum canal configurado no Jellyfin (Painel → TV ao vivo).")
        else -> {
            val list = data.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }
            LazyColumn(contentPadding = PaddingValues(horizontal = pad, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { ch ->
                    val prog = ch.currentProgram
                    val start = parseIso(prog?.startDate)
                    val end = parseIso(prog?.endDate)
                    ChannelRow(
                        number = ch.channelNumber,
                        name = ch.name,
                        logo = jf.channelLogo(ch),
                        nowTitle = prog?.name,
                        nowTime = if (start != null && end != null) "${hhmm(start)} – ${hhmm(end)}" else null,
                        progress = if (start != null && end != null && end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f,
                        nextTitle = null,
                        onClick = {
                            // a lista visível vai junto para o player (↓ mostra os outros canais)
                            LiveQueue.entries = list.map { c ->
                                val p = c.currentProgram
                                LiveQueue.Entry(
                                    c.name, jf.channelLogo(c), p?.name, jfId = c.id, number = c.channelNumber,
                                    nowStart = parseIso(p?.startDate) ?: 0L, nowEnd = parseIso(p?.endDate) ?: 0L,
                                )
                            }
                            ctx.playJellyfinChannel(ch)
                        },
                    )
                }
            }
        }
    }
}

/* ------------------------------ Lista M3U externa ------------------------------ */

@Composable
private fun ExternalChannels(group: String, onGroup: (String) -> Unit, filter: String, now: Long, pad: androidx.compose.ui.unit.Dp) {
    val s = App.session
    val ctx = LocalContext.current
    val key = "live:ext:${s.m3uUrl}|${s.epgUrl}"
    val loader = rememberLoader(key) { s.externalTv.load(s.m3uUrl, s.epgUrl) }
    val guide: ExternalGuide? = loader.data
    when {
        guide == null && loader.error != null -> ErrorBox(loader.error ?: "", onRetry = { loader.reload() })
        guide == null -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Loading(Modifier.padding(top = 40.dp))
            Text("Baixando lista e guia… (a primeira vez pode demorar)", color = TextMuted)
        }
        else -> {
            val groups = remember(guide) {
                guide.channels.groupingBy { it.group }.eachCount().toList().sortedBy { it.first.lowercase() }
            }
            val list = remember(guide, group, filter) {
                guide.channels.filter {
                    (group.isBlank() || it.group == group) && (filter.isBlank() || it.name.contains(filter, ignoreCase = true))
                }
            }
            Column(Modifier.fillMaxSize()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = pad),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item { Chip("Todos · ${guide.channels.size}", selected = group.isBlank(), onClick = { onGroup("") }) }
                    items(groups, key = { it.first }) { (name, count) ->
                        Chip("$name · $count", selected = group == name, onClick = { onGroup(name) })
                    }
                    item {
                        SbButton("Atualizar", onClick = {
                            s.externalTv.clearCache()
                            MemCache.map.remove(key)
                            loader.reload()
                        }, icon = Icons.Filled.Refresh, primary = false)
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = pad, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.url + it.name }) { ch: Channel ->
                        val cur = guide.now(ch, now)
                        val next = guide.next(ch, now)
                        ChannelRow(
                            number = null,
                            name = ch.name,
                            logo = ch.logo,
                            nowTitle = cur?.title,
                            nowTime = cur?.let { "${hhmm(it.start)} – ${hhmm(it.stop)}" },
                            progress = cur?.progress(now) ?: 0f,
                            nextTitle = next?.let { "${hhmm(it.start)} ${it.title}" },
                            onClick = {
                                LiveQueue.entries = list.map { c ->
                                    val cur = guide.now(c, now)
                                    val nx = guide.next(c, now)
                                    LiveQueue.Entry(
                                        c.name, c.logo, cur?.title, url = c.url, userAgent = c.userAgent, referer = c.referer,
                                        number = c.number, group = c.group,
                                        nowStart = cur?.start ?: 0L, nowEnd = cur?.stop ?: 0L,
                                        next = nx?.let { "${hhmm(it.start)}  ${it.title}" },
                                    )
                                }
                                ctx.playExternalChannel(ch.name, ch.url, ch.userAgent, ch.referer)
                            },
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ Linha de canal ------------------------------ */

@Composable
private fun ChannelRow(
    number: String?, name: String, logo: String?,
    nowTitle: String?, nowTime: String?, progress: Float, nextTitle: String?,
    onClick: () -> Unit,
) {
    val tv = LocalIsTv.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .tvFocus(shape, 1.015f)
            .clip(shape)
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.width(if (tv) 110.dp else 76.dp).height(if (tv) 62.dp else 44.dp)
                .clip(RoundedCornerShape(8.dp)).background(Surface3),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(2).uppercase(), color = Orange, style = MaterialTheme.typography.titleMedium)
            if (logo != null) {
                AsyncImage(
                    model = logo, contentDescription = name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(6.dp)
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                listOfNotNull(number, name).joinToString("  "),
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (nowTitle != null) {
                Text(
                    "Agora: $nowTitle" + (nowTime?.let { "  ·  $it" } ?: ""),
                    color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium
                )
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Surface3)) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Orange))
                }
            }
            if (nextTitle != null) {
                Text("Depois: $nextTitle", color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/* ------------------------------ datas ------------------------------ */

private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

private fun parseIso(v: String?): Long? = v?.let { runCatching { isoFormat.parse(it.take(19))?.time }.getOrNull() }

private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
