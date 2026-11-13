package com.pecbox.streambox.data

/** Fileiras da tela inicial que o usuário pode ligar, desligar e reordenar em Ajustes. */
object HomeSections {
    const val RESUME = "resume"
    const val NEXT_UP = "next"
    const val BECAUSE = "because"
    const val ARRIVED = "arrived"
    const val FAVORITES = "favorites"
    const val LATEST = "latest"

    val DEFAULT_ORDER = listOf(RESUME, NEXT_UP, BECAUSE, ARRIVED, FAVORITES, LATEST)

    fun label(key: String) = when (key) {
        RESUME -> "Continuar assistindo"
        NEXT_UP -> "Próximo episódio"
        BECAUSE -> "Para você que assistiu…"
        ARRIVED -> "Seu pedido chegou"
        FAVORITES -> "Minha lista"
        LATEST -> "Adicionados recentemente"
        else -> key
    }

    fun hint(key: String) = when (key) {
        BECAUSE -> "Sugestões pelo gênero e por títulos parecidos com o que você assistiu. Só aparece quando já houver histórico."
        ARRIVED -> "Pedidos seus que já estão no catálogo. Só aparece se você pediu algo."
        LATEST -> "Uma fileira por biblioteca."
        else -> null
    }
}
