package com.pecbox.streambox.player

import android.content.Context
import android.content.Intent

/**
 * Lista de canais que estava na tela da TV ao vivo quando o usuário abriu um canal.
 * O player usa para trocar de canal (↑/↓, números, lista lateral) sem voltar para a tela de canais.
 */
object LiveQueue {
    data class Entry(
        val name: String,
        val logo: String?,
        val now: String?,               // programa atual (se houver guia)
        val jfId: String? = null,       // canal do Jellyfin
        val url: String? = null,        // canal da lista M3U
        val userAgent: String? = null,
        val referer: String? = null,
        val number: String? = null,     // número do canal (tvg-chno / Jellyfin)
        val group: String? = null,
        val nowStart: Long = 0,         // horário do programa atual (para a barra de progresso)
        val nowEnd: Long = 0,
        val next: String? = null,       // próximo programa ("21:00 Jornal")
    )

    var entries: List<Entry> = emptyList()

    /** Número exibido: o da lista ou a posição na fila. */
    fun numberOf(index: Int): String = entries.getOrNull(index)?.number?.ifBlank { null } ?: (index + 1).toString()

    private const val PREFS = "live_last"

    /** Guarda o último canal assistido (para abrir direto nele depois). */
    fun saveLast(ctx: Context, e: Entry) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", e.name).putString("logo", e.logo)
            .putString("jfId", e.jfId).putString("url", e.url)
            .putString("ua", e.userAgent).putString("referer", e.referer)
            .putString("number", e.number).putString("group", e.group)
            .apply()
    }

    fun loadLast(ctx: Context): Entry? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = p.getString("name", null) ?: return null
        val jfId = p.getString("jfId", null)
        val url = p.getString("url", null)
        if (jfId == null && url == null) return null
        return Entry(
            name, p.getString("logo", null), null, jfId = jfId, url = url,
            userAgent = p.getString("ua", null), referer = p.getString("referer", null),
            number = p.getString("number", null), group = p.getString("group", null),
        )
    }

    /** Intent que abre o player neste canal. */
    fun intent(ctx: Context, e: Entry): Intent? {
        val i = Intent(ctx, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_TITLE, e.name)
        return when {
            e.jfId != null -> i.putExtra(PlayerActivity.EXTRA_ID, e.jfId).putExtra(PlayerActivity.EXTRA_JF_LIVE, true)
            e.url != null -> i.putExtra(PlayerActivity.EXTRA_URL, e.url)
                .putExtra(PlayerActivity.EXTRA_UA, e.userAgent).putExtra(PlayerActivity.EXTRA_REFERER, e.referer)
            else -> null
        }
    }
}
