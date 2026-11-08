package com.pecbox.streambox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Integração opcional com Seerr/Jellyseerr: buscar no TMDB e pedir filmes/séries.
 * Login com a conta do Jellyfin (cookie de sessão do Seerr) ou com chave de API.
 */
class SeerrApi(private val s: Session) {

    val enabled: Boolean get() = s.seerrUrl.isNotBlank() && (s.seerrApiKey.isNotBlank() || s.seerrCookie.isNotBlank())

    private fun request(path: String, base: String = s.seerrUrl): Request.Builder {
        val b = Request.Builder().url(base.trimEnd('/') + "/api/v1" + path).header("Accept", "application/json")
        if (s.seerrApiKey.isNotBlank()) b.header("X-Api-Key", s.seerrApiKey)
        else if (s.seerrCookie.isNotBlank()) b.header("Cookie", s.seerrCookie)
        return b
    }

    /** Entra com usuário/senha do Jellyfin. Guarda o cookie da sessão do Seerr. */
    suspend fun loginWithJellyfin(url: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("username", username); put("password", password) }.toString()
        val req = Request.Builder().url(url.trimEnd('/') + "/api/v1/auth/jellyfin")
            .header("Accept", "application/json")
            .post(body.toRequestBody(JSON_TYPE)).build()
        s.http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, if (resp.code == 401 || resp.code == 403) "Usuário ou senha inválidos no Seerr" else "Seerr: HTTP ${resp.code}")
            }
            val cookie = resp.headers("Set-Cookie").map { it.substringBefore(';') }.firstOrNull { it.startsWith("connect.sid=") }
                ?: throw ApiException(0, "O Seerr não devolveu a sessão")
            s.seerrUrl = url
            s.seerrApiKey = ""
            s.seerrCookie = cookie
            val user = runCatching { AppJson.decodeFromString(SeerrUser.serializer(), text) }.getOrNull()
            (user?.label ?: username).also { s.seerrUser = it }
        }
    }

    /** Testa a conexão atual (cookie ou chave) e devolve o nome do usuário. */
    suspend fun me(): String {
        val text = s.http.fetch(request("/auth/me").build())
        return AppJson.decodeFromString(SeerrUser.serializer(), text).label.also { s.seerrUser = it }
    }

    /** Códigos TMDB de tudo que este usuário pediu no Seerr. */
    suspend fun requestedTmdbIds(): Set<Int> {
        val me = AppJson.decodeFromString(SeerrUser.serializer(), s.http.fetch(request("/auth/me").build()))
        val text = s.http.fetch(request("/request?take=200&sort=modified&requestedBy=${me.id}").build())
        return AppJson.decodeFromString(SeerrRequests.serializer(), text).results.mapNotNull { it.media?.tmdbId }.toSet()
    }

    fun logout() {
        s.seerrCookie = ""
        s.seerrApiKey = ""
        s.seerrUser = ""
    }

    suspend fun search(query: String): List<SeerrResult> {
        if (!enabled || query.trim().length < 2) return emptyList()
        val url = (s.seerrUrl.trimEnd('/') + "/api/v1/search").toHttpUrl().newBuilder()
            .addQueryParameter("query", query.trim())
            .addQueryParameter("page", "1")
            .addQueryParameter("language", "pt-BR")
            .build()
        val b = request("/search").url(url)
        val text = s.http.fetch(b.build())
        return AppJson.decodeFromString(SeerrSearch.serializer(), text).results
            .filter { it.mediaType == "movie" || it.mediaType == "tv" }
    }

    suspend fun seasons(tvId: Int): List<Int> {
        val text = s.http.fetch(request("/tv/$tvId?language=pt-BR").build())
        return AppJson.decodeFromString(SeerrTv.serializer(), text).seasons.map { it.seasonNumber }.filter { it > 0 }
    }

    /** seasons = null pede todas as temporadas. */
    suspend fun request(item: SeerrResult, seasons: List<Int>?) {
        val body = buildJsonObject {
            put("mediaType", item.mediaType)
            put("mediaId", item.id)
            if (item.mediaType == "tv") {
                if (seasons == null) put("seasons", "all")
                else put("seasons", JsonArray(seasons.map { JsonPrimitive(it) }))
            }
        }.toString()
        s.http.fetch(request("/request").post(body.toRequestBody(JSON_TYPE)).build())
    }
}
