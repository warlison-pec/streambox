package com.pecbox.streambox.data

/** Opções de aparência da legenda (compartilhadas entre Ajustes e o player). */
object SubtitleStyle {
    val SIZES = listOf("Pequeno" to 0.8f, "Médio" to 1.0f, "Grande" to 1.25f, "Muito grande" to 1.55f)
    val COLORS = listOf("Branco" to 0xFFFFFFFF.toInt(), "Amarelo" to 0xFFFFE14D.toInt(), "Laranja" to 0xFFFF9A3D.toInt())
    /** 0 contorno, 1 sombra, 2 fundo translúcido, 3 caixa preta */
    val BACKGROUNDS = listOf("Contorno", "Sombra", "Fundo translúcido", "Caixa preta")
}
