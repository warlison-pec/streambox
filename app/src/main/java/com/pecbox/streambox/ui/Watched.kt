package com.pecbox.streambox.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.friendly

/**
 * Estado "assistido" com resposta imediata na tela: guarda a escolha do usuário enquanto o
 * servidor confirma, e limpa o cache das telas para elas recarregarem com os dados novos.
 */
object Watched {
    private val overrides = mutableStateMapOf<String, Boolean>()

    fun isPlayed(item: Item): Boolean = overrides[item.id] ?: (item.userData?.played == true)

    /** O player terminou este item: some das fileiras na hora, antes de o servidor confirmar. */
    fun markLocal(id: String) { overrides[id] = true }

    /**
     * Marca/desmarca [item]. [children] são itens já exibidos que mudam junto
     * (episódios de uma temporada ou série). Devolve o novo estado ou null se falhou.
     */
    suspend fun set(ctx: Context, item: Item, played: Boolean, children: List<Item> = emptyList()): Boolean? {
        val before = overrides.toMap()
        overrides[item.id] = played
        children.forEach { overrides[it.id] = played }
        return try {
            App.session.jellyfin.setPlayed(item.id, played)
            MemCache.map.keys.removeAll { !it.startsWith("live") }
            Toast.makeText(ctx, if (played) "Marcado como assistido" else "Marcado como não assistido", Toast.LENGTH_SHORT).show()
            played
        } catch (e: Exception) {
            overrides.clear(); overrides.putAll(before)
            Toast.makeText(ctx, "Não foi possível atualizar: ${e.friendly()}", Toast.LENGTH_LONG).show()
            null
        }
    }

    suspend fun toggle(ctx: Context, item: Item, children: List<Item> = emptyList()): Boolean? =
        set(ctx, item, !isPlayed(item), children)
}

/** Avisa as telas quando o player fecha, para recarregarem "Continuar" e "Próximos episódios". */
object PlaybackEvents {
    var version by mutableIntStateOf(0)
        private set

    fun playerClosed() {
        MemCache.map.keys.removeAll { !it.startsWith("live") }
        version++
    }
}
