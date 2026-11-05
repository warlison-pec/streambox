package com.pecbox.streambox.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.pecbox.streambox.data.friendly
import kotlinx.coroutines.CancellationException

/** Cache em memória: ao voltar para uma tela, os dados aparecem na hora e são atualizados em segundo plano. */
object MemCache {
    val map = HashMap<String, Any?>()
    fun clear() = map.clear()
}

class Loader<T>(initial: T?) {
    var data by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(initial == null)
    internal var nonce by mutableIntStateOf(0)
    fun reload() { nonce++ }
}

@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoader(key: String, load: suspend () -> T): Loader<T> {
    val loader = remember(key) { Loader(MemCache.map[key] as T?) }
    LaunchedEffect(key, loader.nonce) {
        loader.error = null
        loader.loading = loader.data == null
        try {
            val value = load()
            MemCache.map[key] = value
            loader.data = value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (loader.data == null) loader.error = e.friendly()
        } finally {
            loader.loading = false
        }
    }
    return loader
}
