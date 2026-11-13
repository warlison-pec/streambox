package com.pecbox.streambox.data

import androidx.compose.runtime.snapshotFlow
import com.pecbox.streambox.App
import com.pecbox.streambox.BuildConfig
import com.pecbox.streambox.ui.Notices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.Normalizer
import java.util.UUID

/**
 * Enquanto o app está na tela: recebe as mensagens do painel do Jellyfin (WebSocket) e confere
 * novidades do catálogo e novas versões do app. Tudo vira um aviso discreto em [Notices].
 */
object Notifier {
    private const val CONTENT_INTERVAL_MS = 15 * 60_000L
    private const val UPDATE_INTERVAL_MS = 6 * 3_600_000L
    private const val MAX_SINGLE_NOTICES = 3

    private val s get() = App.session
    private val checkLock = Mutex()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = App.scope.launch {
            // Nova versão: confere toda vez que o app volta para a tela (até no login) e depois a cada 6 h
            launch {
                while (isActive) {
                    runCatching { checkUpdate() }
                    delay(UPDATE_INTERVAL_MS)
                }
            }
            snapshotFlow { s.loggedIn }.distinctUntilChanged().collectLatest { logged ->
                if (logged) coroutineScope {
                    launch { socketLoop() }
                    while (isActive) {
                        runCatching { checkContent() }
                        delay(CONTENT_INTERVAL_MS)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Para comparar títulos: sem acento, sem pontuação e em minúsculas. */
    fun normalizeTitle(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("[^\\p{L}\\p{Nd}]"), "").lowercase()

    /* ---------------- tempo real ---------------- */

    private suspend fun socketLoop() = coroutineScope {
        var wait = 5_000L
        while (isActive) {
            val closed = CompletableDeferred<Unit>()
            var keepAlive: Job? = null
            var libraryCheck: Job? = null
            val socket = s.http.newWebSocket(s.jellyfin.socketRequest(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    wait = 5_000L
                    launch { runCatching { s.jellyfin.reportCapabilities() } }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val root = runCatching { AppJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                    val data = root["Data"]
                    when (root["MessageType"].asText()) {
                        // O servidor diz de quantos em quantos segundos quer um sinal de vida
                        "ForceKeepAlive" -> {
                            val seconds = runCatching { data!!.jsonPrimitive.int }.getOrDefault(60).coerceAtLeast(2)
                            keepAlive?.cancel()
                            keepAlive = launch {
                                while (isActive) {
                                    webSocket.send("""{"MessageType":"KeepAlive"}""")
                                    delay(seconds * 500L)
                                }
                            }
                        }
                        "GeneralCommand" -> showMessage(data as? JsonObject)
                        "LibraryChanged" -> if (hasAddedItems(data)) {
                            // Uma varredura manda vários eventos seguidos: confere só depois que acalmar
                            libraryCheck?.cancel()
                            libraryCheck = launch { delay(60_000); runCatching { checkContent() } }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.complete(Unit) }
            })
            try {
                closed.await()
            } finally {
                keepAlive?.cancel()
                libraryCheck?.cancel()
                socket.cancel()
            }
            delay(wait)
            wait = (wait * 2).coerceAtMost(300_000L)
        }
    }

    private fun JsonElement?.asText(): String? = runCatching { this?.jsonPrimitive?.content }.getOrNull()

    private fun hasAddedItems(data: JsonElement?): Boolean =
        runCatching { data!!.jsonObject["ItemsAdded"]!!.jsonArray.isNotEmpty() }.getOrDefault(false)

    private fun showMessage(command: JsonObject?) {
        if (command?.get("Name").asText() != "DisplayMessage") return
        val args = command?.get("Arguments") as? JsonObject ?: return
        val text = args["Text"].asText().orEmpty()
        val header = args["Header"].asText().orEmpty()
        if (text.isBlank() && header.isBlank()) return
        // O painel envia só o texto e o servidor completa com um título padrão em inglês
        val title = if (header.isBlank() || header.equals("Message from Server", ignoreCase = true)) "Mensagem do servidor" else header
        post(Notice.MESSAGE, title, text)
    }

    private fun post(kind: String, title: String, text: String, itemId: String? = null, url: String? = null, image: String? = null) {
        if (kind in s.noticeMuted) return
        Notices.post(Notice(UUID.randomUUID().toString(), kind, title, text, System.currentTimeMillis(), itemId, url, image = image))
    }

    /* ---------------- novidades do catálogo ---------------- */

    private fun stamp(item: Item) = item.dateCreated.orEmpty().take(19)   // até os segundos: o resto varia de tamanho

    /**
     * Episódio mesmo quando o servidor ainda não ligou o arquivo à série (acontece logo depois
     * da importação, principalmente com .strm): antes ele era anunciado como "Novo filme".
     */
    private fun isEpisodeLike(item: Item) =
        item.isEpisode || item.seriesId != null || !item.seriesName.isNullOrBlank() || item.type == "Season" || item.type == "Series"

    private fun groupKey(item: Item) =
        item.seriesId ?: item.seriesName?.takeIf { it.isNotBlank() }?.let { "name:" + normalizeTitle(it) } ?: item.id

    private suspend fun checkContent() = checkLock.withLock {
        val latest = s.jellyfin.recentlyAdded()
        val newest = latest.maxOfOrNull(::stamp).orEmpty()
        if (newest.isEmpty()) return@withLock
        val (baseline, seen) = s.contentBaseline()
        // Primeira vez neste aparelho: só marca o ponto de partida, sem avisar do catálogo inteiro
        val fresh = if (baseline.isEmpty()) emptyList() else latest.filter { stamp(it) >= baseline && it.id !in seen }
        s.saveContentBaseline(newest, latest.filter { stamp(it) == newest }.map { it.id }.toSet())
        if (fresh.isNotEmpty()) announce(fresh.groupBy(::groupKey).values.map { describeGroup(it, baseline) })
    }

    /** O que chegou, já com o tipo certo: filme, série nova, episódio novo ou vários episódios. */
    private class Arrival(
        val items: List<Item>, val owner: Item, val isSeries: Boolean, val newSeries: Boolean,
        val title: String, val text: String,
    )

    private suspend fun describeGroup(group: List<Item>, baseline: String): Arrival {
        val first = group.first()
        if (!isEpisodeLike(first)) {
            val text = listOfNotNull(first.name, first.year?.toString()).joinToString(" · ")
            return Arrival(group, first, isSeries = false, newSeries = false, title = "Novo filme", text = text)
        }
        // Episódio: busca a série para ter nome, capa e saber se ela própria é nova no catálogo
        val series = first.seriesId?.let { runCatching { s.jellyfin.item(it) }.getOrNull() }
        val owner = series ?: first
        val name = series?.name ?: first.seriesName ?: first.name
        val newSeries = series != null && stamp(series).isNotEmpty() && stamp(series) >= baseline
        val episodes = group.filter { it.isEpisode }.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.indexNumber ?: 0 }))
        val seasons = episodes.mapNotNull { it.seasonNumber }.distinct()
        val seasonLabel = when (seasons.size) {
            0 -> null
            1 -> "Temporada ${seasons[0]}"
            else -> "Temporadas ${seasons.first()}–${seasons.last()}"
        }
        val (title, detail) = when {
            newSeries -> "Nova série" to listOfNotNull(seasonLabel, episodes.size.takeIf { it > 0 }?.let { if (it == 1) "1 episódio" else "$it episódios" }).joinToString(" · ")
            episodes.size == 1 -> "Novo episódio" to episodes[0].episodeLabel
            else -> "Novos episódios" to listOfNotNull("${episodes.size.coerceAtLeast(group.size)} episódios", seasonLabel).joinToString(" · ")
        }
        return Arrival(group, owner, isSeries = true, newSeries = newSeries, title = title, text = listOf(name, detail).filter { it.isNotBlank() }.joinToString(" · "))
    }

    private suspend fun announce(arrivals: List<Arrival>) {
        val tmdbIds = if (s.seerr.enabled) runCatching { s.seerr.requestedTmdbIds() }.getOrDefault(emptySet()) else emptySet()
        val requested = arrivals.filter { isRequested(it, tmdbIds) }
        requested.forEach { a ->
            post(Notice.REQUESTED, "Seu pedido chegou", a.text, itemId = a.owner.id, image = poster(a.owner))
        }
        val others = arrivals - requested.toSet()
        if (others.size <= MAX_SINGLE_NOTICES) {
            others.forEach { a -> post(Notice.NEW, a.title, a.text, itemId = a.owner.id, image = poster(a.owner)) }
        } else {
            val movies = others.count { !it.isSeries }
            val shows = others.size - movies
            val counts = listOfNotNull(
                movies.takeIf { it > 0 }?.let { if (it == 1) "1 filme" else "$it filmes" },
                shows.takeIf { it > 0 }?.let { if (it == 1) "1 série" else "$it séries" },
            ).joinToString(" e ")
            val names = others.take(3).joinToString(", ") { titleOf(it.owner) }
            val rest = (others.size - 3).takeIf { it > 0 }?.let { " e mais $it" }.orEmpty()
            post(Notice.NEW, "Novidades no catálogo", "$counts · $names$rest", image = poster(others.first().owner))
        }
    }

    private fun titleOf(item: Item) = item.seriesName ?: item.name

    private fun poster(item: Item) = runCatching { s.jellyfin.posterImage(item, 240) }.getOrNull()

    private suspend fun isRequested(a: Arrival, tmdbIds: Set<Int>): Boolean {
        if (normalizeTitle(titleOf(a.owner)) in s.requestedTitles) return true
        if (tmdbIds.isEmpty()) return false
        val tmdb = a.owner.providerIds["Tmdb"]
            ?: a.owner.takeIf { it.isEpisode }?.seriesId?.let { runCatching { s.jellyfin.item(it) }.getOrNull()?.providerIds?.get("Tmdb") }
        return tmdb?.toIntOrNull() in tmdbIds
    }

    /* ---------------- nova versão do app ---------------- */

    private fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    private suspend fun checkUpdate() {
        val listing = s.http.fetch(Request.Builder().url(BuildConfig.UPDATE_INDEX).build())
        val file = AppUpdate.FILE_NAME.findAll(listing)
            .maxWithOrNull { a, b -> compareVersions(a.groupValues[1], b.groupValues[1]) } ?: return
        val newest = file.groupValues[1]
        if (compareVersions(newest, BuildConfig.VERSION_NAME) <= 0) return
        val url = BuildConfig.UPDATE_INDEX + file.value
        // A caixa aparece sempre que o app abre desatualizado; o aviso no histórico, uma vez só
        withContext(Dispatchers.Main) {
            if (AppUpdate.available?.version != newest) AppUpdate.available = AppUpdate.Available(newest, url)
        }
        if (newest == s.updateNotified) return
        s.updateNotified = newest
        post(Notice.UPDATE, "Nova versão disponível", "StreamBox $newest está pronto. Toque para baixar e instalar.", url = url)
    }
}
