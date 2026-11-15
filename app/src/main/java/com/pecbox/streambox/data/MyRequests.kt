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
    private const val MAX_DISMISSED = 300
    private const val SEERR_TAKE = 100
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

    /** Tira da lista os pedidos que já chegaram (eles continuam no catálogo, só somem daqui). */
    suspend fun clearArrived() = lock.withLock {
        val gone = cache.orEmpty().filter { it.item != null }.map { it.request.key }.toSet()
        if (gone.isEmpty()) return@withLock
        s.dismissedRequests = (s.dismissedRequests + gone).toList().takeLast(MAX_DISMISSED).toSet()
        forget(gone)
        cache = cache?.filterNot { it.request.key in gone }
    }

    /** Apaga pedidos guardados neste aparelho. */
    private fun forget(keys: Set<String>) {
        if (keys.isEmpty() || local.none { it.key in keys }) return
        local.removeAll { it.key in keys }
        s.myRequestsJson = AppJson.encodeToString(serializer, local.toList())
    }

    private suspend fun load(): List<RequestView> = coroutineScope {
        val merged = LinkedHashMap<String, Triple<MyRequest, SeerrRequestFull?, IndexerRequest?>>()
        local.toList().forEach { merged[it.key] = Triple(it, null, null) }

        // Os dois servidores devolvem só os pedidos desta pessoa (de qualquer aparelho).
        // null = não respondeu (ou não está configurado): aí nada é apagado por engano.
        val askStrm = s.indexer.enabled
        val askSeerr = s.seerr.identifiesUser
        val strmJob = async { if (askStrm) runCatching { s.indexer.mine() }.getOrNull() else null }
        val seerrJob = async { if (askSeerr) runCatching { s.seerr.myRequests(SEERR_TAKE) }.getOrNull() else null }
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

        // Pedido excluído no servidor: o servidor respondeu e não o conhece mais
        val strmIds = strm?.map { it.id }?.toSet()
        val seerrComplete = seerr != null && seerr.size < SEERR_TAKE
        val removed = merged.values.filter { (req, se, ix) ->
            se == null && ix == null && when (req.source) {
                "strm" -> strmIds != null && req.remoteId != null && req.remoteId !in strmIds
                "seerr" -> seerrComplete
                else -> false
            }
        }.map { it.first.key }.toSet()
        removed.forEach { merged.remove(it) }

        // Só conta o que os servidores responderam; se um falhou, mantém a contagem anterior
        if ((strm != null || !askStrm) && (seerr != null || !askSeerr)) {
            remoteCount = strm.orEmpty().size + seerr.orEmpty().size
            s.remoteRequestCount = remoteCount
        }

        val dismissed = s.dismissedRequests
        val views = merged.values.filter { it.first.key !in dismissed }
            .map { (req, se, ix) -> async { resolve(req, se, ix) } }.awaitAll()

        // Recusado ou que falhou também sai da lista e do aparelho
        val failed = views.filter { it.state == RequestState.FAILED }.map { it.request.key }.toSet()
        forget(removed + failed)

        // Guarda qual item do Jellyfin é cada pedido: se o nome mudar depois, continua achando
        val found = views.mapNotNull { v -> v.item?.let { v.request.key to it.id } }.toMap()
        if (local.any { r -> found[r.key]?.let { it != r.itemId } == true }) {
            val updated = local.map { r -> found[r.key]?.let { r.copy(itemId = it) } ?: r }
            local.clear(); local.addAll(updated)
            s.myRequestsJson = AppJson.encodeToString(serializer, updated)
        }

        views.filter { it.state != RequestState.FAILED }.sortedByDescending { it.request.time }
    }

    /** Acha o item no catálogo: pelo id já conhecido, pelo que o Seerr informa, pelo TMDB e, por último, pelo nome. */
    private suspend fun findItem(req: MyRequest, seerr: SeerrRequestFull?): Item? {
        val jf = s.jellyfin
        req.itemId?.let { id -> runCatching { jf.item(id) }.getOrNull()?.let { return it } }
        seerr?.media?.jellyfinMediaId?.let { id -> runCatching { jf.item(id) }.getOrNull()?.let { return it } }
        (req.tmdbId ?: seerr?.media?.tmdbId)?.let { tmdb -> runCatching { jf.findByTmdb(tmdb, req.kind) }.getOrNull()?.let { return it } }
        return runCatching { jf.findTitle(req.title, req.kind, req.year) }.getOrNull()
    }

    private suspend fun resolve(req: MyRequest, seerr: SeerrRequestFull?, strm: IndexerRequest?): RequestView {
        val item = findItem(req, seerr)
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
