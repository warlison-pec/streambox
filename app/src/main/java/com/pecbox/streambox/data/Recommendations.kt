package com.pecbox.streambox.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * "Para você que assistiu X": parte do que a pessoa viu por último (filmes e séries) e junta
 * os títulos parecidos do Jellyfin (gêneros, tags, estúdios, elenco). Se vierem poucos,
 * completa com títulos não vistos dos mesmos gêneros. Sem histórico, não devolve nada.
 */
object Recommendations {
    private const val MIN_ITEMS = 4

    private class Source(val id: String, val name: String, val type: String, val genres: List<String>)

    suspend fun forYou(jf: JellyfinApi, resume: List<Item>, maxRows: Int = 2, perRow: Int = 16): List<Pair<String, List<Item>>> = coroutineScope {
        val played = runCatching { jf.recentlyPlayed() }.getOrDefault(emptyList())
        // Mais recente primeiro, juntando o que já terminou e o que está pela metade
        val history = (played + resume).sortedByDescending { it.userData?.lastPlayed.orEmpty() }
        val sources = LinkedHashMap<String, Source>()
        for (i in history) {
            val src = if (i.isEpisode) {
                val id = i.seriesId ?: continue
                Source(id, i.seriesName ?: continue, "Series", emptyList())
            } else if (i.type == "Movie") Source(i.id, i.name, "Movie", i.genres) else continue
            sources.putIfAbsent(src.id, src)
        }
        if (sources.isEmpty()) return@coroutineScope emptyList()

        // Nunca sugerir o que a pessoa já viu ou está vendo
        val seen = sources.keys + resume.map { it.seriesId ?: it.id }
        val rows = sources.values.take(maxRows + 1).map { src ->
            async { src.name to runCatching { similarTo(jf, src, perRow * 2) }.getOrDefault(emptyList()) }
        }.awaitAll()

        val used = HashSet<String>()
        rows.map { (name, list) ->
            name to list.filter { it.id !in seen && it.userData?.played != true && used.add(it.id) }.take(perRow)
        }.filter { it.second.size >= MIN_ITEMS }.take(maxRows)
    }

    private suspend fun similarTo(jf: JellyfinApi, src: Source, limit: Int): List<Item> {
        val similar = runCatching { jf.similar(src.id, limit) }.getOrDefault(emptyList())
            .filter { (it.type == "Movie" || it.type == "Series") && it.userData?.played != true }
        if (similar.size >= 10) return similar
        val genres = src.genres.ifEmpty { runCatching { jf.item(src.id).genres }.getOrDefault(emptyList()) }
        if (genres.isEmpty()) return similar
        val byGenre = runCatching { jf.byGenres(genres.take(3), src.type, limit) }.getOrDefault(emptyList())
        return (similar + byGenre).distinctBy { it.id }
    }
}
