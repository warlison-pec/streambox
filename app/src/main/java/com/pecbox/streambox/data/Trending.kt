package com.pecbox.streambox.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fileira "Em alta" do Início: só títulos que já estão no catálogo (dá para dar play na hora).
 * Com Seerr configurado, usa as tendências do TMDB da semana; o que faltar é completado com os
 * lançamentos recentes mais bem avaliados do próprio Jellyfin.
 */
object Trending {
    private const val WANT = 20

    suspend fun load(s: Session): List<Item> = coroutineScope {
        val jf = s.jellyfin
        val fromTmdb = if (!s.seerr.enabled) emptyList() else withTimeoutOrNull(6_000) {
            val trending = (1..2).map { p -> async { runCatching { s.seerr.trending(p) }.getOrDefault(emptyList()) } }
                .awaitAll().flatten().distinctBy { it.mediaType + it.id }
            // Só procura no catálogo o que o Seerr já diz estar disponível (ou que ele não conhece)
            trending.filter { it.status == 1 || it.status >= 4 }.take(30).map { t ->
                async { runCatching { jf.findByTmdb(t.id, if (t.mediaType == "tv") "series" else "movie") }.getOrNull() }
            }.awaitAll().filterNotNull()
        }.orEmpty()

        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val local = if (fromTmdb.size >= WANT) emptyList()
            else runCatching { jf.topRatedRecent(sinceYear = year - 1) }.getOrDefault(emptyList())

        (fromTmdb + local)
            .filter { it.userData?.played != true }   // o que já foi visto não é sugestão
            .distinctBy { it.id }
            .take(WANT)
    }
}
