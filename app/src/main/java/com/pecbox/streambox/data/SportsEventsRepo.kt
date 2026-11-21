package com.pecbox.streambox.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.App
import com.pecbox.streambox.ui.ChannelGenres
import com.pecbox.streambox.ui.MemCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Guia de eventos esportivos carregado em segundo plano, assim que o app abre: quando a pessoa
 * entra na aba, ele já está pronto. Todo o trabalho (baixar, ler o JSON, montar os eventos) roda
 * fora da thread da tela, para não travar a navegação em TV box fraca.
 */
object SportsEventsRepo {
    private const val REFRESH_MS = 30 * 60_000L
    private const val START_DELAY_MS = 8_000L      // deixa o Início carregar primeiro
    private const val PREFETCH_TEAMS = 40          // escudos buscados antes de abrir a aba

    var events by mutableStateOf<List<SportsGuide.Event>>(emptyList())
        private set
    /** Canais de esporte do Jellyfin (fila do player: ↓ mostra os outros canais). */
    var sportChannels by mutableStateOf<List<Item>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var loop: Job? = null
    private var prefetch: Job? = null
    private var lastLoad = 0L
    private val mutex = Mutex()

    /** Começa (uma vez por sessão do app) a carregar e a manter o guia atualizado. */
    fun start() {
        if (loop?.isActive == true) return
        loop = App.scope.launch {
            delay(START_DELAY_MS)
            while (isActive) {
                refresh()
                delay(REFRESH_MS)
            }
        }
    }

    /** Recarrega se o guia estiver velho (ou se [force]). Seguro chamar várias vezes. */
    fun refreshIfStale(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - lastLoad < REFRESH_MS) return
        App.scope.launch { refresh() }
    }

    suspend fun refresh() {
        if (mutex.isLocked) return                 // já está carregando
        mutex.withLock {
            val s = App.session
            if (!s.loggedIn) return
            withContext(Dispatchers.Main) { loading = true }
            try {
                val (chs, evs) = withContext(Dispatchers.Default) { load(s) }
                lastLoad = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    sportChannels = chs
                    events = evs
                    error = null
                    loaded = true
                }
                prefetchBadges(evs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {          // inclui falta de memória: nunca derruba o app
                withContext(Dispatchers.Main) { error = (e as? Exception)?.friendly() ?: "Memória insuficiente"; loaded = true }
            } finally {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    private suspend fun load(s: Session): Pair<List<Item>, List<SportsGuide.Event>> {
        val sport = s.jellyfin.liveChannels().filter { ChannelGenres.classify(it.name) == "Esportes" }
        val byId = sport.associateBy { it.id }
        val sources = ArrayList<SportsGuide.Source>()
        if (sport.isNotEmpty()) {
            for (p in s.jellyfin.programs(sport.map { it.id }, pastHours = 24)) {
                val ch = p.channelId?.let { byId[it] } ?: continue
                val a = parseUtc(p.startDate) ?: continue
                val b = parseUtc(p.endDate) ?: continue
                sources += SportsGuide.Source(p.name, a, b, "jf:${ch.id}", ch.name, s.jellyfin.channelLogo(ch), jfChannel = ch)
            }
        }
        // "Minha lista": só se a aba já carregou o guia (ler um XMLTV grande aqui pesaria demais na TV box)
        val ext = MemCache.map["live:ext:${s.m3uUrl}|${s.epgUrl}"] as? ExternalGuide
        ext?.let { g ->
            for (ch in g.channels) {
                if (ChannelGenres.classify(ch.name, ch.group) != "Esportes") continue
                for (p in g.programmes[ch.id].orEmpty()) {
                    sources += SportsGuide.Source(p.title, p.start, p.stop, "ext:${ch.url}", ch.name, ch.logo, extChannel = ch)
                }
            }
        }
        return sport to SportsGuide.build(sources)
    }

    /** Escudos dos próximos jogos, devagar (a API tem limite), para já aparecerem ao abrir a aba. */
    private fun prefetchBadges(evs: List<SportsGuide.Event>) {
        if (App.session.indexerUrl.isNotBlank()) return   // o servidor já tem os escudos prontos
        if (prefetch?.isActive == true) return
        val teams = evs.asSequence().filter { it.isMatch && !it.replay }
            .flatMap { sequenceOf(it.home!!, it.away!!) }.distinct().take(PREFETCH_TEAMS).toList()
        prefetch = App.scope.launch {
            for (t in teams) runCatching { App.session.badges.lookup(t) }
        }
    }

    private val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    fun parseUtc(v: String?): Long? = v?.let { runCatching { synchronized(utcFormat) { utcFormat.parse(it.take(19))?.time } }.getOrNull() }
}
