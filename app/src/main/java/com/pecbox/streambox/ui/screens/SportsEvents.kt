package com.pecbox.streambox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pecbox.streambox.App
import com.pecbox.streambox.data.SportsEventsRepo
import com.pecbox.streambox.data.SportsGuide
import com.pecbox.streambox.playExternalChannel
import com.pecbox.streambox.playJellyfinChannel
import com.pecbox.streambox.player.LiveQueue
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.ErrorBox
import com.pecbox.streambox.ui.components.FilterBar
import com.pecbox.streambox.ui.components.Loading
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.theme.Danger
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.Surface3
import com.pecbox.streambox.ui.theme.TextMuted
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Aba "Eventos" da TV ao vivo: jogos e transmissões ao vivo dos canais de esporte, tirados do EPG.
 * Fontes: canais de esporte do Jellyfin e, se houver, da "Minha lista" (M3U + XMLTV).
 */
@Composable
internal fun SportsEvents(filter: String, now: Long, pad: Dp) {
    val s = App.session
    val tv = LocalIsTv.current
    val ctx = LocalContext.current
    val repo = SportsEventsRepo

    // O guia é montado em segundo plano desde que o app abriu; aqui só confere se está velho
    LaunchedEffect(Unit) { repo.start(); repo.refreshIfStale() }

    var onlyMatches by rememberSaveable { mutableStateOf(false) }
    var replays by rememberSaveable { mutableStateOf(false) }

    val events = repo.events
    val sportJf = repo.sportChannels
    val nowBucket = now / 60_000
    // Conta leve (lista já pronta): só filtra e tira o que já acabou
    val shown = remember(events, onlyMatches, replays, filter, nowBucket) {
        events.filter { e ->
            e.stop > now && (!onlyMatches || e.isMatch) && (replays || !e.replay) &&
                (filter.isBlank() || e.title.contains(filter, true) || e.info?.contains(filter, true) == true ||
                    e.channels.any { it.name.contains(filter, true) })
        }
    }

    fun play(e: SportsGuide.Event, ch: SportsGuide.EventChannel = e.channels.first()) {
        // Os canais do evento vão primeiro na fila do player; ↓ mostra os outros canais de esporte
        val main = e.channels.map { c ->
            LiveQueue.Entry(
                c.name, c.logo, if (c.key == ch.key) e.title else c.jfChannel?.currentProgram?.name,
                url = c.extChannel?.url, userAgent = c.extChannel?.userAgent, referer = c.extChannel?.referer,
                jfId = c.jfChannel?.id, number = c.jfChannel?.channelNumber ?: c.extChannel?.number, group = "Esportes",
                nowStart = if (c.key == ch.key && e.onAir(now)) e.start else 0L,
                nowEnd = if (c.key == ch.key && e.onAir(now)) e.stop else 0L,
            )
        }
        val others = sportJf.filter { c -> e.channels.none { it.jfChannel?.id == c.id } }.map { c ->
            LiveQueue.Entry(
                c.name, s.jellyfin.channelLogo(c), c.currentProgram?.name, jfId = c.id, number = c.channelNumber, group = "Esportes",
                nowStart = SportsEventsRepo.parseUtc(c.currentProgram?.startDate) ?: 0L,
                nowEnd = SportsEventsRepo.parseUtc(c.currentProgram?.endDate) ?: 0L,
            )
        }
        LiveQueue.entries = main + others
        ch.jfChannel?.let { ctx.playJellyfinChannel(it); return }
        ch.extChannel?.let { ctx.playExternalChannel(it.name, it.url, it.userAgent, it.referer) }
    }

    val loading = !repo.loaded
    when {
        loading && events.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Loading(Modifier.padding(top = 40.dp))
            Text("Montando o guia de eventos…", color = TextMuted)
        }
        events.isEmpty() && repo.error != null -> ErrorBox("Guia indisponível: ${repo.error}", onRetry = { repo.refreshIfStale(force = true) })
        sportJf.isEmpty() && events.isEmpty() ->
            ErrorBox("Nenhum canal de esportes encontrado. O guia de eventos usa a programação (EPG) dos canais de esporte.")
        else -> Column(Modifier.fillMaxSize()) {
            FilterBar(Modifier.padding(horizontal = pad)) {
                Chip("Todos os eventos", selected = !onlyMatches, onClick = { onlyMatches = false })
                Chip("Só jogos", selected = onlyMatches, onClick = { onlyMatches = true })
                Chip(if (replays) "Reprises: mostrando" else "Reprises: ocultas", selected = replays, onClick = { replays = !replays })
            }
            // Crédito exigido pela licença do football-logos.cc
            if (s.indexerUrl.isNotBlank()) {
                Text("Escudos: football-logos.cc", color = TextMuted, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = pad, vertical = 2.dp))
            }
            if (shown.isEmpty()) {
                Text(
                    "Nenhum evento nos próximos dias" + (if (filter.isNotBlank()) " para \"$filter\"" else "") + ".",
                    color = TextMuted, modifier = Modifier.padding(pad)
                )
            }
            val sections = remember(shown, nowBucket) { sectionsOf(shown, now) }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (tv) 330.dp else 300.dp),
                contentPadding = PaddingValues(horizontal = pad, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for ((label, list) in sections) {
                    item(key = "h:$label", span = { GridItemSpan(maxLineSpan) }) {
                        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    }
                    items(list, key = { it.id }, contentType = { "event" }) { e -> EventCard(e, now, onPlay = { ch -> play(e, ch) }) }
                }
            }
        }
    }
}

/* ------------------------------ cartão do evento ------------------------------ */

@Composable
private fun EventCard(e: SportsGuide.Event, now: Long, onPlay: (SportsGuide.EventChannel) -> Unit) {
    val tv = LocalIsTv.current
    val shape = RoundedCornerShape(14.dp)
    val onAir = e.onAir(now)
    Column(
        Modifier
            .fillMaxWidth()
            .tvFocus(shape, 1.03f)
            .clip(shape)
            .background(Surface)
            .then(if (onAir) Modifier.border(1.dp, Orange.copy(alpha = 0.6f), shape) else Modifier)
            .clickable { onPlay(e.channels.first()) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // selos
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                onAir -> Badge("AO VIVO", Danger, Color.White)
                e.replay -> Badge("REPRISE", Surface3, TextMuted)
                e.live -> Badge("AO VIVO", Surface3, Orange)
            }
            Spacer(Modifier.weight(1f))
            e.channels.first().logo?.let {
                AsyncImage(it, e.channels.first().name, contentScale = ContentScale.Fit, modifier = Modifier.height(22.dp).width(56.dp))
            }
        }
        if (e.isMatch) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Team(e.home!!, Modifier.weight(1f))
                Text("x", style = MaterialTheme.typography.headlineSmall, color = Orange, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp))
                Team(e.away!!, Modifier.weight(1f))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(if (tv) 64.dp else 56.dp).clip(RoundedCornerShape(10.dp)).background(Surface3), contentAlignment = Alignment.Center) {
                    Text(e.channels.first().name.take(2).uppercase(), color = Orange, style = MaterialTheme.typography.titleMedium)
                    e.channels.first().logo?.let { AsyncImage(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp)) }
                }
                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            e.info?.let { Text(it, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            Text(
                "${dayLabel(e.start, now)} · ${hhmm(e.start)} – ${hhmm(e.stop)}",
                style = MaterialTheme.typography.titleSmall, color = if (onAir) Orange else MaterialTheme.colorScheme.onSurface,
            )
        }
        if (onAir) {
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Surface3)) {
                Box(Modifier.fillMaxWidth(e.progress(now)).height(3.dp).background(Orange))
            }
        }
        // canais: o cartão abre o primeiro; com mais de um, cada um tem seu botão
        if (e.channels.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                e.channels.take(4).forEach { ch ->
                    val cs = RoundedCornerShape(50)
                    Text(
                        ch.name, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        modifier = Modifier.tvFocus(cs, 1.05f).clip(cs).background(Surface2).clickable { onPlay(ch) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        } else {
            Text(e.channels.first().name, color = TextMuted, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Team(name: String, modifier: Modifier) {
    val tv = LocalIsTv.current
    val s = App.session
    // Com o STRM Indexer: escudo do repositório do servidor (pronto em disco, um só download para todos).
    // Sem ele (versão Comunidade): busca no TheSportsDB pelo próprio aparelho.
    val server = s.indexerUrl.isNotBlank()
    var url by remember(name) {
        mutableStateOf(if (server) s.indexerUrl.trimEnd('/') + "/badge?team=" + java.net.URLEncoder.encode(name, "UTF-8") else s.badges.cached(name))
    }
    var ok by remember(name) { mutableStateOf(false) }
    LaunchedEffect(name) { if (!server && url == null) url = s.badges.lookup(name) }
    val size = if (tv) 72.dp else 60.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            if (!ok) {
                // iniciais até o escudo chegar (ou se não houver)
                Box(Modifier.fillMaxSize().clip(CircleShape).background(teamColor(name)), contentAlignment = Alignment.Center) {
                    Text(teamInitials(name), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (url != null) {
                AsyncImage(
                    url, name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    onSuccess = { ok = true }, onError = { ok = false },
                )
            }
        }
        Text(name, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Badge(text: String, bg: Color, fg: Color) {
    Text(
        text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/* ------------------------------ auxiliares ------------------------------ */

private val TEAM_COLORS = listOf(0xFF2E7D32, 0xFF1565C0, 0xFFC62828, 0xFF6A1B9A, 0xFF00838F, 0xFFEF6C00, 0xFF37474F, 0xFF4E342E)
private fun teamColor(name: String) = Color(TEAM_COLORS[(SportsGuide.norm(name).hashCode() and 0x7fffffff) % TEAM_COLORS.size])
private fun teamInitials(name: String) =
    name.split(' ', '-').filter { it.isNotBlank() && it.first().isLetterOrDigit() }.let { w ->
        if (w.size >= 2) "${w[0].first()}${w[1].first()}" else name.take(3)
    }.uppercase()

/** "Agora" (no ar) e depois um grupo por dia. */
private fun sectionsOf(list: List<SportsGuide.Event>, now: Long): List<Pair<String, List<SportsGuide.Event>>> {
    val out = ArrayList<Pair<String, List<SportsGuide.Event>>>()
    val (onAir, later) = list.partition { it.onAir(now) }
    if (onAir.isNotEmpty()) out += "Agora" to onAir
    later.groupBy { dayStart(it.start) }.toSortedMap().forEach { (day, evs) -> out += sectionLabel(day, now) to evs }
    return out
}

private fun dayStart(ms: Long): Long = Calendar.getInstance().apply {
    timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private val PT = Locale("pt", "BR")

private fun sectionLabel(day: Long, now: Long): String {
    val diff = Math.round((day - dayStart(now)) / 86_400_000.0).toInt()
    val date = SimpleDateFormat("EEEE, dd/MM", PT).format(Date(day))
    return when (diff) {
        0 -> "Hoje · $date"
        1 -> "Amanhã · $date"
        else -> date.replaceFirstChar { it.uppercase() }
    }
}

/** "Hoje", "Amanhã" ou "sáb 22/11". */
private fun dayLabel(ms: Long, now: Long): String = when (Math.round((dayStart(ms) - dayStart(now)) / 86_400_000.0).toInt()) {
    0 -> "Hoje"
    1 -> "Amanhã"
    else -> SimpleDateFormat("EEE dd/MM", PT).format(Date(ms)).replace(".", "")
}

private fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

