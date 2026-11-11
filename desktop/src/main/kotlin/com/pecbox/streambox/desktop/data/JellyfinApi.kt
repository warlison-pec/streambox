package com.pecbox.streambox.desktop.data

import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.ApiException
import com.pecbox.streambox.data.AuthResult
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.ItemsResult
import com.pecbox.streambox.data.JSON_TYPE
import com.pecbox.streambox.data.MediaStream
import com.pecbox.streambox.data.PlaybackInfo
import com.pecbox.streambox.data.PublicInfo
import com.pecbox.streambox.data.QuickConnectResult
import com.pecbox.streambox.data.fetch
import com.pecbox.streambox.desktop.BuildConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cliente da API do Jellyfin (mesmas rotas do app Android, só o que o desktop usa por enquanto).
 */
class JellyfinApi(private val s: Session) {

    companion object {
        const val FIELDS = "Overview,Genres,ProductionYear,ChildCount,CommunityRating,OfficialRating,PrimaryImageAspectRatio"
        const val CLIENT = "StreamBox Desktop"

        val deviceName: String =
            (System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME") ?: System.getProperty("os.name") ?: "Desktop")
                .replace("\"", "")
    }

    private fun authHeader(token: String = s.token): String {
        val base = "MediaBrowser Client=\"$CLIENT\", Device=\"$deviceName\", DeviceId=\"${s.deviceId}\", Version=\"${BuildConfig.VERSION_NAME}\""
        return if (token.isBlank()) base else "$base, Token=\"$token\""
    }

    private fun url(path: String, query: Map<String, Any?> = emptyMap(), server: String = s.server): HttpUrl {
        val builder = (server.trimEnd('/') + path).toHttpUrl().newBuilder()
        for ((k, v) in query) if (v != null && v.toString().isNotEmpty()) builder.addQueryParameter(k, v.toString())
        return builder.build()
    }

    private suspend fun getText(path: String, query: Map<String, Any?> = emptyMap(), server: String = s.server, token: String = s.token): String =
        s.http.fetch(Request.Builder().url(url(path, query, server)).header("Authorization", authHeader(token)).build())

    private suspend fun postText(path: String, json: String, query: Map<String, Any?> = emptyMap(), server: String = s.server, token: String = s.token): String =
        s.http.fetch(
            Request.Builder().url(url(path, query, server))
                .header("Authorization", authHeader(token))
                .post(json.toRequestBody(JSON_TYPE)).build()
        )

    private suspend fun items(path: String, query: Map<String, Any?>): ItemsResult =
        AppJson.decodeFromString(ItemsResult.serializer(), getText(path, query))

    /* ---------------- login ---------------- */

    suspend fun publicInfo(server: String): PublicInfo {
        val text = s.http.fetch(Request.Builder().url(url("/System/Info/Public", server = server)).build())
        return AppJson.decodeFromString(PublicInfo.serializer(), text)
    }

    /** Aceita o Jellyfin direto ou o StreamBox Web (Jellyfin em /api). */
    suspend fun resolveServer(address: String): String {
        val first = runCatching { publicInfo(address) }
        if (first.isSuccess) return address
        if (!address.endsWith("/api")) {
            val alt = "$address/api"
            if (runCatching { publicInfo(alt) }.isSuccess) return alt
        }
        throw first.exceptionOrNull() ?: IllegalStateException("Servidor não encontrado")
    }

    /** Sem protocolo, tenta https e depois http (ex.: 192.168.0.10:8096). */
    suspend fun connect(raw: String): String {
        val t = raw.trim().trimEnd('/')
        if (t.isBlank()) throw IllegalStateException("Informe o endereço do servidor.")
        if (t.startsWith("http://") || t.startsWith("https://")) return resolveServer(t)
        return runCatching { resolveServer("https://$t") }.getOrElse { first ->
            runCatching { resolveServer("http://$t") }.getOrElse { throw first }
        }
    }

    suspend fun login(server: String, username: String, password: String): AuthResult {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        return AppJson.decodeFromString(AuthResult.serializer(), postText("/Users/AuthenticateByName", body, server = server, token = ""))
    }

    suspend fun quickConnectEnabled(server: String): Boolean =
        runCatching { getText("/QuickConnect/Enabled", server = server, token = "").trim().equals("true", ignoreCase = true) }
            .getOrDefault(false)

    suspend fun quickConnectInitiate(server: String): QuickConnectResult {
        val text = try {
            postText("/QuickConnect/Initiate", "{}", server = server, token = "")
        } catch (e: ApiException) {
            if (e.code == 405 || e.code == 404) getText("/QuickConnect/Initiate", server = server, token = "") else throw e
        }
        return AppJson.decodeFromString(QuickConnectResult.serializer(), text)
    }

    suspend fun quickConnectState(server: String, secret: String): QuickConnectResult =
        AppJson.decodeFromString(
            QuickConnectResult.serializer(),
            getText("/QuickConnect/Connect", mapOf("secret" to secret), server = server, token = "")
        )

    suspend fun quickConnectLogin(server: String, secret: String): AuthResult {
        val body = buildJsonObject { put("Secret", secret) }.toString()
        return AppJson.decodeFromString(AuthResult.serializer(), postText("/Users/AuthenticateWithQuickConnect", body, server = server, token = ""))
    }

    /* ---------------- catálogo ---------------- */

    suspend fun views(): List<Item> = items("/UserViews", mapOf("userId" to s.userId)).items

    suspend fun resume(limit: Int = 20): List<Item> = items(
        "/UserItems/Resume",
        mapOf("userId" to s.userId, "limit" to limit, "mediaTypes" to "Video", "fields" to FIELDS, "enableTotalRecordCount" to false)
    ).items

    suspend fun nextUp(limit: Int = 20): List<Item> = items(
        "/Shows/NextUp",
        mapOf("userId" to s.userId, "limit" to limit, "fields" to FIELDS, "enableTotalRecordCount" to false)
    ).items

    suspend fun featured(limit: Int = 8): List<Item> = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "recursive" to true, "includeItemTypes" to "Movie,Series",
            "sortBy" to "Random", "imageTypes" to "Backdrop", "limit" to limit,
            "fields" to FIELDS, "enableTotalRecordCount" to false,
        )
    ).items

    suspend fun latest(parentId: String, types: String = "Movie,Series", limit: Int = 24): List<Item> = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "parentId" to parentId, "recursive" to true,
            "includeItemTypes" to types, "sortBy" to "DateCreated", "sortOrder" to "Descending",
            "limit" to limit, "fields" to FIELDS, "enableTotalRecordCount" to false,
        )
    ).items

    suspend fun library(parentId: String, types: String, start: Int, limit: Int, sortBy: String, descending: Boolean): ItemsResult = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "parentId" to parentId, "recursive" to true,
            "includeItemTypes" to types, "sortBy" to sortBy,
            "sortOrder" to if (descending) "Descending" else "Ascending",
            "startIndex" to start, "limit" to limit, "fields" to FIELDS,
        )
    )

    suspend fun item(id: String): Item =
        AppJson.decodeFromString(Item.serializer(), getText("/Items/$id", mapOf("userId" to s.userId)))

    suspend fun seasons(seriesId: String): List<Item> =
        items("/Shows/$seriesId/Seasons", mapOf("userId" to s.userId, "fields" to FIELDS)).items

    suspend fun episodes(seriesId: String, seasonId: String): List<Item> =
        items("/Shows/$seriesId/Episodes", mapOf("userId" to s.userId, "seasonId" to seasonId, "fields" to FIELDS)).items

    suspend fun nextEpisode(episode: Item): Item? {
        val seriesId = episode.seriesId ?: return null
        if (!episode.isEpisode) return null
        return items(
            "/Shows/$seriesId/Episodes",
            mapOf("userId" to s.userId, "startItemId" to episode.id, "limit" to 2, "fields" to FIELDS)
        ).items.firstOrNull { it.id != episode.id }
    }

    suspend fun search(term: String): List<Item> = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "searchTerm" to term, "recursive" to true,
            "includeItemTypes" to "Movie,Series", "limit" to 60, "fields" to FIELDS
        )
    ).items

    /* ---------------- imagens ---------------- */

    fun primaryImage(item: Item, height: Int = 450): String? {
        val tag = item.imageTags["Primary"]
        return when {
            tag != null -> "${s.server}/Items/${item.id}/Images/Primary?fillHeight=$height&quality=85&tag=$tag"
            item.seriesId != null && item.seriesPrimaryTag != null ->
                "${s.server}/Items/${item.seriesId}/Images/Primary?fillHeight=$height&quality=85&tag=${item.seriesPrimaryTag}"
            else -> null
        }
    }

    fun posterImage(item: Item, height: Int = 450): String? =
        if (item.isEpisode && item.seriesId != null && item.seriesPrimaryTag != null)
            "${s.server}/Items/${item.seriesId}/Images/Primary?fillHeight=$height&quality=85&tag=${item.seriesPrimaryTag}"
        else primaryImage(item, height)

    fun backdropImage(item: Item, width: Int = 1920): String? = when {
        item.backdropTags.isNotEmpty() ->
            "${s.server}/Items/${item.id}/Images/Backdrop/0?fillWidth=$width&quality=85&tag=${item.backdropTags[0]}"
        item.parentBackdropId != null && item.parentBackdropTags.isNotEmpty() ->
            "${s.server}/Items/${item.parentBackdropId}/Images/Backdrop/0?fillWidth=$width&quality=85&tag=${item.parentBackdropTags[0]}"
        else -> null
    }

    fun landscapeImage(item: Item): String? =
        if (item.isEpisode && item.imageTags["Primary"] != null) primaryImage(item, 360)
        else backdropImage(item, 720) ?: primaryImage(item, 360)

    fun logoImage(item: Item, maxWidth: Int = 600): String? {
        val tag = item.imageTags["Logo"]
        val id = if (tag != null) item.id else item.parentLogoId
        val logoTag = tag ?: item.parentLogoTag
        return if (id != null && logoTag != null) "${s.server}/Items/$id/Images/Logo?maxWidth=$maxWidth&quality=90&tag=$logoTag" else null
    }

    /* ---------------- reprodução (sempre o arquivo original) ---------------- */

    /** Arquivo original, sem conversão. Funciona também para itens .strm (o servidor repassa o link). */
    fun directStreamUrl(itemId: String, mediaSourceId: String = itemId): String =
        url(
            "/Videos/$itemId/stream",
            mapOf("static" to true, "mediaSourceId" to mediaSourceId, "deviceId" to s.deviceId, "ApiKey" to s.token)
        ).toString()

    /** Legenda externa no formato original (o mpv lê srt/ass/vtt/sup direto). */
    fun externalSubtitleUrl(itemId: String, sourceId: String, stream: MediaStream): String {
        val ext = when (stream.codec?.lowercase()) {
            "subrip", "srt" -> "srt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            "webvtt", "vtt" -> "vtt"
            "pgssub", "pgs" -> "sup"
            else -> "srt"
        }
        return url("/Videos/$itemId/$sourceId/Subtitles/${stream.index}/0/Stream.$ext", mapOf("ApiKey" to s.token)).toString()
    }

    /* ---------------- TV ao vivo ---------------- */

    suspend fun liveChannels(): List<Item> = items(
        "/LiveTv/Channels",
        mapOf(
            "userId" to s.userId, "addCurrentProgram" to true, "enableImages" to true,
            "enableUserData" to false, "limit" to 1000, "fields" to "ChannelInfo"
        )
    ).items

    fun channelLogo(channel: Item): String? =
        channel.imageTags["Primary"]?.let { "${s.server}/Items/${channel.id}/Images/Primary?fillHeight=120&quality=90&tag=$it" }

    /** Transmissão aberta pelo servidor (sintonizador). Vale enquanto o canal não for fechado. */
    fun liveStreamUrl(channelId: String, mediaSourceId: String, liveStreamId: String?): String =
        url(
            "/Videos/$channelId/stream",
            mapOf(
                "static" to true, "mediaSourceId" to mediaSourceId, "liveStreamId" to liveStreamId,
                "deviceId" to s.deviceId, "ApiKey" to s.token,
            )
        ).toString()

    /** Fecha a transmissão: cada canal aberto ocupa um sintonizador/conexão no Jellyfin. */
    suspend fun closeLiveStream(liveStreamId: String) {
        postText("/LiveStreams/Close", "{}", mapOf("liveStreamId" to liveStreamId))
    }

    /**
     * Faixas e legendas do item. O perfil diz ao servidor que este aparelho toca tudo
     * (o mpv decodifica qualquer formato), então ele nunca oferece conversão.
     */
    suspend fun playbackInfo(itemId: String, live: Boolean = false): PlaybackInfo {
        val body = """
            {"UserId":"${s.userId}","MaxStreamingBitrate":1000000000,"AutoOpenLiveStream":$live,
             "EnableDirectPlay":true,"EnableDirectStream":true,"EnableTranscoding":false,
             "DeviceProfile":{"Name":"StreamBox Desktop (mpv)","MaxStreamingBitrate":1000000000,"MaxStaticBitrate":1000000000,
               "DirectPlayProfiles":[{"Type":"Video"},{"Type":"Audio"}],
               "TranscodingProfiles":[],
               "SubtitleProfiles":[
                 {"Format":"srt","Method":"Embed"},{"Format":"subrip","Method":"Embed"},{"Format":"ass","Method":"Embed"},
                 {"Format":"ssa","Method":"Embed"},{"Format":"vtt","Method":"Embed"},{"Format":"pgssub","Method":"Embed"},
                 {"Format":"dvdsub","Method":"Embed"},{"Format":"dvbsub","Method":"Embed"},
                 {"Format":"srt","Method":"External"},{"Format":"ass","Method":"External"},{"Format":"ssa","Method":"External"},
                 {"Format":"vtt","Method":"External"},{"Format":"pgssub","Method":"External"}]}}
        """.trimIndent()
        val text = postText("/Items/$itemId/PlaybackInfo", body, mapOf("userId" to s.userId, "autoOpenLiveStream" to live))
        return AppJson.decodeFromString(PlaybackInfo.serializer(), text)
    }

    private fun playbackBody(itemId: String, sourceId: String, positionTicks: Long, paused: Boolean, session: String, method: String = "DirectPlay"): String =
        buildJsonObject {
            put("ItemId", itemId)
            put("MediaSourceId", sourceId)
            put("PositionTicks", positionTicks)
            put("IsPaused", paused)
            put("CanSeek", true)
            put("PlayMethod", method)
            put("PlaySessionId", session)
        }.toString()

    suspend fun reportStart(itemId: String, sourceId: String, ticks: Long, session: String, method: String = "DirectPlay") {
        postText("/Sessions/Playing", playbackBody(itemId, sourceId, ticks, false, session, method))
    }

    suspend fun reportProgress(itemId: String, sourceId: String, ticks: Long, paused: Boolean, session: String) {
        postText("/Sessions/Playing/Progress", playbackBody(itemId, sourceId, ticks, paused, session))
    }

    suspend fun reportStopped(itemId: String, sourceId: String, ticks: Long, session: String, method: String = "DirectPlay") {
        postText("/Sessions/Playing/Stopped", playbackBody(itemId, sourceId, ticks, false, session, method))
    }
}
