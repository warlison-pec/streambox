package com.pecbox.streambox.desktop.data

import java.text.Normalizer

/** Título sem acentos, espaços e pontuação, para comparar nomes (igual ao Notifier.normalizeTitle do Android). */
fun normalizeTitle(title: String): String =
    Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("[^\\p{L}\\p{Nd}]"), "").lowercase()
