package com.pecbox.streambox.desktop.data

import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.IndexerItem
import com.pecbox.streambox.data.IndexerSearch
import com.pecbox.streambox.data.JSON_TYPE
import com.pecbox.streambox.data.RequestCreated
import com.pecbox.streambox.data.RequestStatus
import com.pecbox.streambox.data.fetch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Ponte do STRM Indexer (mesmas rotas do app Android). Autentica com o token do próprio usuário do Jellyfin;
 * o indexador confere esse token no servidor. Nenhuma chave fica no app.
 */
class IndexerApi(private val s: Session) {

    val enabled: Boolean get() = s.indexerUrl.isNotBlank()

    private fun request(path: String) = Request.Builder()
        .url(s.indexerUrl.trimEnd('/') + path)
        .header("X-Emby-Token", s.token)

    suspend fun search(query: String): List<IndexerItem> {
        if (!enabled || query.trim().length < 2) return emptyList()
        val url = (s.indexerUrl.trimEnd('/') + "/search").toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim()).build()
        val text = s.http.fetch(Request.Builder().url(url).header("X-Emby-Token", s.token).build())
        return AppJson.decodeFromString(IndexerSearch.serializer(), text).items
    }

    suspend fun request(item: IndexerItem, season: Int?): RequestCreated {
        val body = buildJsonObject {
            put("kind", item.kind)
            put("title", item.title)
            if (item.kind == "movie" && item.catalogYear != null) put("year", item.catalogYear)
            if (season != null) put("season", season)
        }.toString()
        val text = s.http.fetch(request("/request").post(body.toRequestBody(JSON_TYPE)).build())
        return AppJson.decodeFromString(RequestCreated.serializer(), text)
    }

    suspend fun status(id: String): RequestStatus {
        val text = s.http.fetch(request("/status?id=$id").build())
        return AppJson.decodeFromString(RequestStatus.serializer(), text)
    }

    /** Capa servida pelo indexador (caminho relativo "art/<hash>.jpg"). */
    fun art(path: String?): String? = path?.let { s.indexerUrl.trimEnd('/') + "/" + it }
}
