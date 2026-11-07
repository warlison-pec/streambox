package com.pecbox.streambox.ui

/**
 * Gênero do canal (Abertos, Notícias, Esportes…), com as mesmas regras do "Organizar por categoria" do
 * STRM Indexer: primeiro pelo grupo da lista, depois pelo nome ("Band Sports" é esporte, não a Band).
 */
object ChannelGenres {
    private class Cat(val name: String, group: String, channel: String) {
        val group = Regex(group, RegexOption.IGNORE_CASE)
        val channel = Regex(channel, RegexOption.IGNORE_CASE)
    }

    private val CATS = listOf(
        Cat("Abertos", "abert|nacionais|regionais|locais|\\bgeneral\\b|variedades|entertainment",
            "\\b(globo|sbt|record|band|bandeirantes|rede ?tv|cultura|tv brasil|gazeta|cnt|rede ?vida|tv aparecida|" +
                "tv camara|tv senado|tv justi|canal sete|chroma|futura)\\b(?!.*news)"),
        Cat("Notícias", "not[ií]cia|\\bnews\\b|jornal|jornalismo",
            "news|cnn|bandnews|globonews|jovem pan|record news|sbt news|cnbc|bloomberg|euronews|dw\\b|bbc world|al jazeera"),
        Cat("Esportes", "esporte|sport|futebol|football|soccer|\\bufc\\b|luta|combate|premiere",
            "espn|sportv|premiere|combate|band ?sports|fox sports|tnt sports|dazn|eleven|goat|cazé|caze|nba|nfl|xsports|sport"),
        Cat("Filmes e Séries", "filme|s[eé]rie|movie|cinema|\\btelecine\\b|\\bhbo\\b",
            "hbo|telecine|cinemax|megapix|\\btnt\\b|\\baxn\\b|warner|sony|universal|paramount|space|\\bfx\\b|studio|" +
                "star channel|amc|film|cine|runtime|a&e|\\bae\\b|lifetime|tcm|prime ?box"),
        Cat("Infantil", "kid|infantil|crian[cç]a|anima[cç]|cartoon|desenho|anime",
            "cartoon|disney|nick|gloob|discovery kids|boomerang|baby|tooncast|zoomoo|kids|ra-tim-bum|gloobinho|otaku|anime"),
        Cat("Documentários", "document|educa|ci[eê]ncia|natureza|hist[oó]ri|cultura",
            "discovery|history|nat ?geo|national geographic|animal planet|curiosity|smithsonian|tlc|h2\\b|science"),
        Cat("Música", "m[uú]sic|music|\\bclipes?\\b|r[aá]dio", "mtv|music|vh1|bis\\b|multishow|clubbing|trace|kiss|hits"),
        Cat("Religiosos", "relig|gospel|cat[oó]lic|evang|crist[aã]o",
            "can[cç][aã]o nova|aparecida|rede vida|gospel|novo tempo|rit\\b|boa vontade|evang|igreja|catol"),
    )
    const val OTHER = "Outros"

    /** Ordem de exibição dos gêneros. */
    val ORDER: List<String> = CATS.map { it.name } + OTHER

    fun classify(name: String?, group: String? = null): String {
        val g = group.orEmpty()
        val n = name.orEmpty()
        CATS.firstOrNull { g.trim().equals(it.name, ignoreCase = true) }?.let { return it.name }
        for (c in CATS) {
            if (c.group.containsMatchIn(g) && !(c.name == "Abertos" && CATS[1].channel.containsMatchIn(n))) return c.name
        }
        for (c in CATS.drop(1) + CATS.first()) if (c.channel.containsMatchIn(n)) return c.name
        return OTHER
    }
}
