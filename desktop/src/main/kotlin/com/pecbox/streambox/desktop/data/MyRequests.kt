package com.pecbox.streambox.desktop.data

import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.SeerrRequestFull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pedido atendido que está no catálogo agora. */
class ArrivedRequest(val key: String, val item: Item, val partial: Boolean, val time: Long)

/**
 * Pedidos desta pessoa (STRM Indexer e Seerr, feitos em qualquer aparelho), igual à aba Pedidos do Android:
 * só entra o que está no catálogo. Pendente, recusado, excluído ou removido do catálogo não aparece.
 */
object MyRequests {
    private const val MAX_AGE_MS = 2 * 60_000L
    private val lock = Mutex()
    private var cache: List<ArrivedRequest>? = null
    private var cacheTime = 0L
    private var cacheUser = ""

    /** Dá para ter pedidos: algum dos servidores está configurado com a conta da pessoa. */
    fun available(s: Session) = s.indexer.enabled || s.seerr.identifiesUser

    /** Tudo o que chegou (inclusive o que a pessoa limpou da aba). Usa o resultado anterior se tiver menos de 2 min. */
    suspend fun arrived(s: Session, force: Boolean = false): List<ArrivedRequest> = lock.withLock {
        val fresh = cache?.takeIf { !force && cacheUser == s.userId && System.currentTimeMillis() - cacheTime < MAX_AGE_MS }
        fresh ?: load(s).also { cache = it; cacheTime = System.currentTimeMillis(); cacheUser = s.userId }
    }

    /** O que aparece na aba Pedidos: o que chegou, menos o que a pessoa limpou. */
    suspend fun listed(s: Session, force: Boolean = false): List<ArrivedRequest> {
        val dismissed = s.dismissedRequests
        return arrived(s, force).filter { it.key !in dismissed }
    }

    /** Tira da aba os pedidos mostrados agora (eles continuam no catálogo). */
    fun clear(s: Session, keys: Collection<String>) {
        if (keys.isNotEmpty()) s.dismissedRequests = s.dismissedRequests + keys
    }

    private suspend fun load(s: Session): List<ArrivedRequest> = coroutineScope {
        val jf = s.jellyfin
        val strm = async { if (s.indexer.enabled) runCatching { s.indexer.mine() }.getOrDefault(emptyList()) else emptyList() }
        val seerr = async { runCatching { s.seerr.myRequests() }.getOrDefault(emptyList()) }

        val fromStrm = strm.await().map { r ->
            async {
                val kind = if (r.kind == "movie") "movie" else "series"
                val item = runCatching { jf.findTitle(r.title, kind, r.year) }.getOrNull() ?: return@async null
                ArrivedRequest("strm:$kind:${normalizeTitle(r.title)}", item, false, (r.createdAt * 1000).toLong())
            }
        }
        val fromSeerr = seerr.await().map { e -> async { fromSeerr(s, e) } }
        (fromSeerr + fromStrm).awaitAll().filterNotNull()
            .sortedByDescending { it.time }
            .distinctBy { it.item.id }
    }

    /** Acha no catálogo: pelo item que o Seerr informa, pelo código do TMDB e, por último, pelo nome. */
    private suspend fun fromSeerr(s: Session, e: SeerrRequestFull): ArrivedRequest? {
        val jf = s.jellyfin
        val media = e.media ?: return null
        val tmdb = media.tmdbId ?: return null
        val tv = e.type == "tv" || media.mediaType == "tv"
        val kind = if (tv) "series" else "movie"
        val item = media.jellyfinMediaId?.let { id -> runCatching { jf.item(id) }.getOrNull() }
            ?: runCatching { jf.findByTmdb(tmdb, kind) }.getOrNull()
            ?: if (media.status >= 4) {
                runCatching { s.seerr.details(if (tv) "tv" else "movie", tmdb) }.getOrNull()?.let { d ->
                    runCatching { jf.findTitle(d.displayTitle, kind, d.year?.toIntOrNull()) }.getOrNull()
                }
            } else null
        item ?: return null
        return ArrivedRequest("seerr:$kind:$tmdb", item, media.status == 4, parseTime(e.createdAt))
    }

    private fun parseTime(iso: String?): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(iso!!.take(19))!!.time
    }.getOrDefault(0L)
}
