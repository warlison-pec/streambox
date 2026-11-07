package com.pecbox.streambox.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly

/** "Minha lista" (favoritos do Jellyfin), com resposta imediata na tela enquanto o servidor confirma. */
object Favorites {
    private val overrides = mutableStateMapOf<String, Boolean>()

    fun isFavorite(item: Item): Boolean = overrides[item.id] ?: (item.userData?.isFavorite == true)

    /** Item que vai para a lista: episódio vira a série. */
    fun target(item: Item): String = if (item.isEpisode) item.seriesId ?: item.id else item.id

    suspend fun toggle(ctx: Context, item: Item): Boolean? {
        val id = target(item)
        val fav = !(overrides[id] ?: isFavorite(item))
        val before = overrides.toMap()
        overrides[id] = fav
        overrides[item.id] = fav
        return try {
            App.session.jellyfin.setFavorite(id, fav)
            MemCache.map.keys.removeAll { !it.startsWith("live") }
            Toast.makeText(ctx, if (fav) "Adicionado à Minha lista" else "Removido da Minha lista", Toast.LENGTH_SHORT).show()
            fav
        } catch (e: Exception) {
            overrides.clear(); overrides.putAll(before)
            Toast.makeText(ctx, "Não foi possível atualizar: ${e.friendly()}", Toast.LENGTH_LONG).show()
            null
        }
    }
}
