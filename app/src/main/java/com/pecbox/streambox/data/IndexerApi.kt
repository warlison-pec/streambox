package com.pecbox.streambox.data

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Ponte do STRM Indexer (rotas /strm-api). Autentica com o token do próprio usuário do Jellyfin;
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

    /** A fonte atual não tocou neste aparelho: pede ao indexador que troque por outra do mesmo conteúdo. */
    suspend fun nextSource(itemId: String, codecs: List<String>): SourceSwitch {
        val body = buildJsonObject {
            put("itemId", itemId)
            putJsonArray("codecs") { codecs.forEach { add(it) } }
        }.toString()
        val text = s.http.fetch(request("/source/next").post(body.toRequestBody(JSON_TYPE)).build())
        return AppJson.decodeFromString(SourceSwitch.serializer(), text)
    }

    /** Nenhuma outra fonte tocou: volta para a original. */
    suspend fun restoreSource(itemId: String) {
        val body = buildJsonObject { put("itemId", itemId) }.toString()
        s.http.fetch(request("/source/restore").post(body.toRequestBody(JSON_TYPE)).build())
    }

    /** Capa servida pelo indexador (caminho relativo "art/<hash>.jpg"). */
    fun art(path: String?): String? = path?.let { s.indexerUrl.trimEnd('/') + "/" + it }
}
