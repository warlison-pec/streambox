package com.pecbox.streambox.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.App
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Situação de um pedido, já traduzida para a tela. */
enum class RequestState { ARRIVED, PARTIAL, PROGRESS, WAITING, FAILED }

data class RequestView(
    val request: MyRequest,
    val state: RequestState,
    val label: String,
    val item: Item?,          // filme/série no catálogo, quando já chegou
)

/**
 * Pedidos da pessoa: os feitos neste aparelho (STRM Indexer e Seerr) mais os que o Seerr
 * conhece de outros aparelhos. Cada um é conferido no catálogo para saber se já chegou.
 */
object MyRequests {
    private const val MAX_LOCAL = 100
    private const val MAX_AGE_MS = 2 * 60_000L
    private val serializer = ListSerializer(MyRequest.serializer())
    private val lock = Mutex()
    private val s get() = App.session

    private val local = mutableStateListOf<MyRequest>()
    private var loadedFor: String? = null
    private var remoteCount by mutableIntStateOf(0)

    private var cache: List<RequestView>? = null
    private var cacheTime = 0L
    private var cacheUser = ""

    /** Lê os pedidos salvos do usuário atual (chamar fora da composição: altera estado observável). */
    fun ensureLoaded() {
        if (loadedFor == s.userId) return
        loadedFor = s.userId
        local.clear()
        local.addAll(runCatching { AppJson.decodeFromString(serializer, s.myRequestsJson) }.getOrDefault(emptyList()))
        remoteCount = s.remoteRequestCount
        cache = null
    }

    /** A aba "Pedidos" e a fileira "Seu pedido chegou" só existem para quem já pediu algo. */
    val hasAny: Boolean get() = local.isNotEmpty() || remoteCount > 0

    fun record(request: MyRequest) {
        ensureLoaded()
        local.removeAll { it.key == request.key }
        local.add(0, request)
        while (local.size > MAX_LOCAL) local.removeAt(local.lastIndex)
        s.myRequestsJson = AppJson.encodeToString(serializer, local.toList())
        s.addRequestedTitle(Notifier.normalizeTitle(request.title))
        cache = null
    }

    fun strmKey(kind: String, title: String) = "strm:$kind:${Notifier.normalizeTitle(title)}"
    fun seerrKey(kind: String, tmdbId: Int) = "seerr:$kind:$tmdbId"

    /** Pedidos com a situação de cada um. Usa o resultado anterior se tiver menos de 2 min. */
    suspend fun views(force: Boolean = false): List<RequestView> = lock.withLock {
        ensureLoaded()
        val fresh = cache?.takeIf { !force && cacheUser == s.userId && System.currentTimeMillis() - cacheTime < MAX_AGE_MS }
        fresh ?: load().also { cache = it; cacheTime = System.currentTimeMillis(); cacheUser = s.userId }
    }

    private suspend fun load(): List<RequestView> = coroutineScope {
        val merged = LinkedHashMap<String, Triple<MyRequest, SeerrRequestFull?, IndexerRequest?>>()
        local.toList().forEach { merged[it.key] = Triple(it, null, null) }

        // Os dois servidores devolvem só os pedidos desta pessoa (de qualquer aparelho)
        val strmJob = async { if (s.indexer.enabled) runCatching { s.indexer.mine() }.getOrNull() else emptyList() }
        val seerrJob = async { if (s.seerr.identifiesUser) runCatching { s.seerr.myRequests() }.getOrNull() else emptyList() }
        val strm = strmJob.await()
        val seerr = seerrJob.await()

        strm.orEmpty().forEach { r ->
            val kind = if (r.kind == "movie") "movie" else "series"
            val key = strmKey(kind, r.title)
            val known = merged[key]?.first
            val req = known?.copy(remoteId = r.id) ?: MyRequest(key, "strm", kind, r.title, r.year, null, (r.createdAt * 1000).toLong(), remoteId = r.id)
            merged[key] = Triple(req, null, r)
        }
        seerr.orEmpty().map { e ->
            async {
                val tmdb = e.media?.tmdbId ?: return@async null
                val tv = e.type == "tv" || e.media.mediaType == "tv"
                val kind = if (tv) "series" else "movie"
                val key = seerrKey(kind, tmdb)
                val req = merged[key]?.first ?: runCatching { s.seerr.details(if (tv) "tv" else "movie", tmdb) }.getOrNull()?.let { d ->
                    MyRequest(key, "seerr", kind, d.displayTitle, d.year?.toIntOrNull(), d.poster, parseTime(e.createdAt), tmdbId = tmdb)
                } ?: return@async null
                key to Triple(req, e, null)
            }
        }.awaitAll().filterNotNull().forEach { (k, v) -> merged[k] = v }

        // Só conta o que os servidores responderam; se um falhou, mantém a contagem anterior
        if (strm != null && seerr != null) {
            remoteCount = strm.size + seerr.size
            s.remoteRequestCount = remoteCount
        }

        merged.values.map { (req, se, ix) -> async { resolve(req, se, ix) } }.awaitAll()
            .sortedByDescending { it.request.time }
    }

    private suspend fun resolve(req: MyRequest, seerr: SeerrRequestFull?, strm: IndexerRequest?): RequestView {
        val jf = s.jellyfin
        val item = seerr?.media?.jellyfinMediaId?.let { id -> runCatching { jf.item(id) }.getOrNull() }
            ?: runCatching { jf.findTitle(req.title, req.kind, req.year) }.getOrNull()
        if (item != null) {
            val partial = seerr?.media?.status == 4
            return RequestView(req, if (partial) RequestState.PARTIAL else RequestState.ARRIVED, if (partial) "Chegou em parte" else "Disponível", item)
        }
        if (seerr != null) {
            return when {
                seerr.status == 3 -> RequestView(req, RequestState.FAILED, "Recusado", null)
                seerr.media?.status == 5 -> RequestView(req, RequestState.PROGRESS, "Chegando ao catálogo", null)
                seerr.status == 2 || seerr.media?.status == 3 -> RequestView(req, RequestState.PROGRESS, "Em andamento", null)
                else -> RequestView(req, RequestState.WAITING, "Aguardando aprovação", null)
            }
        }
        val remoteId = req.remoteId
        if (req.source == "strm" && remoteId != null && s.indexer.enabled) {
            val state = strm?.state ?: runCatching { s.indexer.status(remoteId) }.getOrNull()?.state
            if (state != null) return when (state) {
                "queued" -> RequestView(req, RequestState.WAITING, "Na fila", null)
                "validating" -> RequestView(req, RequestState.PROGRESS, "Validando", null)
                "importing" -> RequestView(req, RequestState.PROGRESS, "Importando", null)
                "available" -> RequestView(req, RequestState.PROGRESS, "Chegando ao catálogo", null)
                "failed" -> RequestView(req, RequestState.FAILED, "Falhou", null)
                else -> RequestView(req, RequestState.FAILED, "Sem fonte agora", null)
            }
        }
        return RequestView(req, RequestState.WAITING, "Solicitado", null)
    }

    private fun parseTime(iso: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso!!.take(19))!!.time
    }.getOrDefault(0L)
}
