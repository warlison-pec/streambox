package com.pecbox.streambox.ui

import com.pecbox.streambox.data.Item

/**
 * Anime e Dorama não são gêneros no TMDB, então são reconhecidos pelo país de produção e pelas tags
 * (palavras-chave do TMDB que o Jellyfin importa).
 */
object Categories {
    const val ANIME = "anime"
    const val DORAMA = "dorama"

    private val animation = Regex("anima|anime", RegexOption.IGNORE_CASE)
    private val japan = Regex("^(jp|japan|jap[aã]o)$", RegexOption.IGNORE_CASE)
    private val asia = Regex(
        "^(jp|kr|cn|tw|th|hk|japan|jap[aã]o|.*korea.*|.*coreia.*|china|taiwan|thailand|tail[aâ]ndia|hong kong)$",
        RegexOption.IGNORE_CASE,
    )
    private val animeTag = Regex("^anime$|^animes$|japanese animation|anima[cç][aã]o japonesa", RegexOption.IGNORE_CASE)
    private val doramaTag = Regex("dorama|k-?drama|c-?drama|j-?drama|thai drama|korean drama|asian drama|drama coreano", RegexOption.IGNORE_CASE)

    private fun isAnimation(i: Item) = i.genres.any { animation.containsMatchIn(it) }

    fun isAnime(i: Item): Boolean =
        i.tags.any { animeTag.containsMatchIn(it) } ||
            i.genres.any { it.equals("Anime", true) } ||
            (isAnimation(i) && i.productionLocations.any { japan.matches(it.trim()) })

    fun isDorama(i: Item): Boolean =
        i.tags.any { doramaTag.containsMatchIn(it) } ||
            (i.isSeries && !isAnimation(i) && !isAnime(i) && i.productionLocations.any { asia.matches(it.trim()) })

    fun matches(key: String, i: Item): Boolean = when (key) {
        ANIME -> isAnime(i)
        DORAMA -> isDorama(i)
        else -> true
    }
}
