package com.pecbox.streambox.desktop.data

/** Fileiras do Início que a pessoa pode ligar, desligar e reordenar em Ajustes (como no app Android). */
object HomeSections {
    const val RESUME = "resume"
    const val NEXT_UP = "next"
    const val TRENDING = "trending"
    const val BECAUSE = "because"
    const val ARRIVED = "arrived"
    const val LATEST = "latest"

    val DEFAULT_ORDER = listOf(RESUME, NEXT_UP, TRENDING, BECAUSE, ARRIVED, LATEST)

    fun label(key: String) = when (key) {
        RESUME -> "Continuar assistindo"
        NEXT_UP -> "Próximos episódios"
        TRENDING -> "Em alta"
        BECAUSE -> "Para você que assistiu…"
        ARRIVED -> "Seu pedido chegou"
        LATEST -> "Adicionados recentemente"
        else -> key
    }

    fun hint(key: String) = when (key) {
        TRENDING -> "Filmes e séries em alta agora (tendências do TMDB, via Seerr) que já estão no catálogo. Sem Seerr, mostra os lançamentos mais bem avaliados."
        BECAUSE -> "Sugestões pelo que você assistiu. Só aparece quando já houver histórico."
        ARRIVED -> "Pedidos seus que já estão no catálogo."
        LATEST -> "Uma fileira por biblioteca."
        else -> null
    }
}
