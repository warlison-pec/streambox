package com.pecbox.streambox.data

import android.os.Build
import com.pecbox.streambox.BuildConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Cliente mínimo da API REST do Jellyfin (rotas do 10.9+ / 12).
 * Sem SDK: só OkHttp + kotlinx.serialization, para manter o APK leve.
 */
class JellyfinApi(private val s: Session) {

    companion object {
        const val FIELDS = "Overview,Genres,ProductionYear,ChildCount,CommunityRating,OfficialRating,PrimaryImageAspectRatio"
        const val CLIENT = "StreamBox"
    }

    private fun authHeader(token: String = s.token): String {
        val device = (Build.MODEL ?: "Android").replace("\"", "")
        val base = "MediaBrowser Client=\"$CLIENT\", Device=\"$device\", DeviceId=\"${s.deviceId}\", Version=\"${BuildConfig.VERSION_NAME}\""
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

    /**
     * Descobre o endereço real do Jellyfin. Aceita o servidor direto
     * (https://jellyfin.exemplo) ou o StreamBox Web, que expõe o Jellyfin em /api
     * (ex.: https://midia.exemplo.com -> https://midia.exemplo.com/api).
     */
    suspend fun resolveServer(address: String): String {
        val first = runCatching { publicInfo(address) }
        if (first.isSuccess) return address
        if (!address.endsWith("/api")) {
            val alt = "$address/api"
            if (runCatching { publicInfo(alt) }.isSuccess) return alt
        }
        throw first.exceptionOrNull() ?: IllegalStateException("Servidor não encontrado")
    }

    suspend fun login(server: String, username: String, password: String): AuthResult {
        val body = buildJsonObject {
            put("Username", username)
            put("Pw", password)
        }.toString()
        val text = postText("/Users/AuthenticateByName", body, server = server, token = "")
        return AppJson.decodeFromString(AuthResult.serializer(), text)
    }

    /* ---------------- conexão rápida (Quick Connect) ---------------- */

    suspend fun quickConnectEnabled(server: String): Boolean =
        runCatching { getText("/QuickConnect/Enabled", server = server, token = "").trim().equals("true", ignoreCase = true) }
            .getOrDefault(false)

    suspend fun quickConnectInitiate(server: String): QuickConnectResult {
        val text = try {
            postText("/QuickConnect/Initiate", "{}", server = server, token = "")
        } catch (e: ApiException) {
            // Servidores antigos usavam GET
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
        val text = postText("/Users/AuthenticateWithQuickConnect", body, server = server, token = "")
        return AppJson.decodeFromString(AuthResult.serializer(), text)
    }

    /**
     * Autoriza o código de conexão rápida exibido em OUTRO aparelho,
     * usando a sessão já logada deste app.
     */
    suspend fun quickConnectAuthorize(code: String) {
        try {
            postText("/QuickConnect/Authorize", "{}", mapOf("code" to code, "userId" to s.userId))
        } catch (e: ApiException) {
            throw when (e.code) {
                404 -> ApiException(404, "Código inválido ou expirado. Gere um novo no outro aparelho.")
                401, 403 -> ApiException(e.code, "A conexão rápida está desativada no servidor ou sua sessão expirou.")
                else -> e
            }
        }
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

    /** Destaques aleatórios com imagem de fundo (para o carrossel). */
    suspend fun featured(type: String, limit: Int): List<Item> = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "recursive" to true, "includeItemTypes" to type,
            "sortBy" to "Random", "imageTypes" to "Backdrop", "limit" to limit,
            "fields" to FIELDS, "enableTotalRecordCount" to false
        )
    ).items

    suspend fun latest(parentId: String, types: String = "Movie,Series", limit: Int = 20): List<Item> = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "parentId" to parentId, "recursive" to true,
            "includeItemTypes" to types, "sortBy" to "DateCreated", "sortOrder" to "Descending",
            "limit" to limit, "fields" to FIELDS, "enableTotalRecordCount" to false
        )
    ).items

    suspend fun library(parentId: String, types: String, start: Int, limit: Int, sortBy: String, descending: Boolean): ItemsResult = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "parentId" to parentId, "recursive" to true,
            "includeItemTypes" to types, "sortBy" to sortBy,
            "sortOrder" to if (descending) "Descending" else "Ascending",
            "startIndex" to start, "limit" to limit, "fields" to FIELDS
        )
    )

    suspend fun item(id: String): Item =
        AppJson.decodeFromString(Item.serializer(), getText("/Items/$id", mapOf("userId" to s.userId)))

    suspend fun children(parentId: String): List<Item> =
        items("/Items", mapOf("userId" to s.userId, "parentId" to parentId, "sortBy" to "ProductionYear,SortName", "fields" to FIELDS)).items

    /** Imagem da própria biblioteca (cartões da tela Bibliotecas). */
    fun libraryImage(view: Item): String? =
        view.imageTags["Primary"]?.let { "${s.server}/Items/${view.id}/Images/Primary?fillWidth=480&quality=85&tag=$it" }

    suspend fun seasons(seriesId: String): List<Item> =
        items("/Shows/$seriesId/Seasons", mapOf("userId" to s.userId, "fields" to FIELDS)).items

    suspend fun episodes(seriesId: String, seasonId: String): List<Item> =
        items("/Shows/$seriesId/Episodes", mapOf("userId" to s.userId, "seasonId" to seasonId, "fields" to FIELDS)).items

    suspend fun search(term: String): List<Item> = items(
        "/Items",
        mapOf(
            "userId" to s.userId, "searchTerm" to term, "recursive" to true,
            "includeItemTypes" to "Movie,Series", "limit" to 40, "fields" to FIELDS
        )
    ).items

    /* ---------------- imagens (públicas, com cache por tag) ---------------- */

    fun primaryImage(item: Item, height: Int = 450): String? {
        val tag = item.imageTags["Primary"]
        return when {
            tag != null -> "${s.server}/Items/${item.id}/Images/Primary?fillHeight=$height&quality=85&tag=$tag"
            item.seriesId != null && item.seriesPrimaryTag != null ->
                "${s.server}/Items/${item.seriesId}/Images/Primary?fillHeight=$height&quality=85&tag=${item.seriesPrimaryTag}"
            else -> null
        }
    }

    /** Pôster vertical (para episódios usa o da série). */
    fun posterImage(item: Item, height: Int = 450): String? =
        if (item.isEpisode && item.seriesId != null && item.seriesPrimaryTag != null)
            "${s.server}/Items/${item.seriesId}/Images/Primary?fillHeight=$height&quality=85&tag=${item.seriesPrimaryTag}"
        else primaryImage(item, height)

    fun backdropImage(item: Item, width: Int = 1280): String? = when {
        item.backdropTags.isNotEmpty() ->
            "${s.server}/Items/${item.id}/Images/Backdrop/0?fillWidth=$width&quality=80&tag=${item.backdropTags[0]}"
        item.parentBackdropId != null && item.parentBackdropTags.isNotEmpty() ->
            "${s.server}/Items/${item.parentBackdropId}/Images/Backdrop/0?fillWidth=$width&quality=80&tag=${item.parentBackdropTags[0]}"
        else -> null
    }

    /** Imagem horizontal para "Continuar assistindo": miniatura do episódio ou fundo do filme. */
    fun landscapeImage(item: Item): String? =
        if (item.isEpisode && item.imageTags["Primary"] != null) primaryImage(item, 360)
        else backdropImage(item, 720) ?: primaryImage(item, 360)

    /* ---------------- reprodução ---------------- */

    /** Arquivo original via servidor (sem conversão). Funciona também para itens .strm. */
    fun directStreamUrl(itemId: String): String =
        url(
            "/Videos/$itemId/stream",
            mapOf("static" to true, "mediaSourceId" to itemId, "deviceId" to s.deviceId, "ApiKey" to s.token)
        ).toString()

    /** Conversão HLS no servidor (H.264/AAC) quando o aparelho não consegue tocar o original. */
    fun hlsUrl(itemId: String, playSessionId: String, maxBitrate: Int = 20_000_000, maxHeight: Int? = null, audioIndex: Int? = null): String =
        url(
            "/Videos/$itemId/master.m3u8",
            mapOf(
                "mediaSourceId" to itemId, "deviceId" to s.deviceId, "ApiKey" to s.token,
                "playSessionId" to playSessionId, "videoCodec" to "h264", "audioCodec" to "aac,mp3",
                "maxStreamingBitrate" to maxBitrate, "videoBitRate" to (maxBitrate - 192_000).coerceAtLeast(300_000),
                "audioBitRate" to 192_000, "maxHeight" to maxHeight, "audioStreamIndex" to audioIndex,
                "transcodingMaxAudioChannels" to 2, "segmentContainer" to "ts", "breakOnNonKeyFrames" to true
            )
        ).toString()

    /** Informações de reprodução (faixas, legendas). Para canais ao vivo abre o stream no servidor. */
    suspend fun playbackInfo(itemId: String, live: Boolean = false): PlaybackInfo {
        val body = """
            {"UserId":"${s.userId}","MaxStreamingBitrate":40000000,"AutoOpenLiveStream":$live,
             "EnableDirectPlay":true,"EnableDirectStream":true,"EnableTranscoding":true,
             "DeviceProfile":{"MaxStreamingBitrate":40000000,
               "DirectPlayProfiles":[{"Type":"Video","Container":"mp4,m4v,mkv,webm,ts,mpegts,mov","VideoCodec":"h264,hevc,vp9,av1,mpeg2video","AudioCodec":"aac,mp3,ac3,eac3,opus,flac,vorbis"}],
               "TranscodingProfiles":[{"Type":"Video","Container":"ts","Protocol":"hls","VideoCodec":"h264","AudioCodec":"aac,mp3","Context":"Streaming","MaxAudioChannels":"2","BreakOnNonKeyFrames":true}],
               "SubtitleProfiles":[{"Format":"vtt","Method":"External"},{"Format":"srt","Method":"External"},{"Format":"ass","Method":"External"},{"Format":"ssa","Method":"External"}]}}
        """.trimIndent()
        val text = postText("/Items/$itemId/PlaybackInfo", body, mapOf("userId" to s.userId, "autoOpenLiveStream" to live))
        return AppJson.decodeFromString(PlaybackInfo.serializer(), text)
    }

    /** Legenda convertida para WebVTT pelo servidor (funciona com srt/ass/vtt externos ou embutidos de texto). */
    fun subtitleUrl(itemId: String, sourceId: String, index: Int): String =
        url("/Videos/$itemId/$sourceId/Subtitles/$index/0/Stream.vtt", mapOf("ApiKey" to s.token)).toString()

    /** Busca legendas na internet pelos provedores configurados no Jellyfin (ex.: OpenSubtitles). */
    suspend fun searchSubtitles(itemId: String, lang: String): List<RemoteSubtitle> {
        val text = getText("/Items/$itemId/RemoteSearch/Subtitles/$lang")
        return AppJson.decodeFromString(ListSerializer(RemoteSubtitle.serializer()), text)
    }

    suspend fun downloadSubtitle(itemId: String, subtitleId: String) {
        val encoded = java.net.URLEncoder.encode(subtitleId, "UTF-8").replace("+", "%20")
        postText("/Items/$itemId/RemoteSearch/Subtitles/$encoded", "{}")
    }

    /** URL absoluta a partir de um caminho relativo devolvido pelo servidor (ex.: TranscodingUrl). */
    fun absolute(path: String): String {
        val full = if (path.startsWith("http")) path else s.server.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
        // o player não envia cabeçalho de autorização: a chave vai na URL (Jellyfin 12 só aceita "ApiKey")
        return if (!full.startsWith(s.server) || Regex("[?&](api_key|ApiKey)=", RegexOption.IGNORE_CASE).containsMatchIn(full)) full
        else full + (if ('?' in full) "&" else "?") + "ApiKey=" + s.token
    }

    /* ---------------- visto / não visto ---------------- */

    /** Marca (ou desmarca) como assistido. Em séries e temporadas vale para todos os episódios. */
    suspend fun setPlayed(itemId: String, played: Boolean) {
        val req = Request.Builder()
            .url(url("/UserPlayedItems/$itemId", mapOf("userId" to s.userId)))
            .header("Authorization", authHeader())
        s.http.fetch(if (played) req.post("".toRequestBody(JSON_TYPE)).build() else req.delete().build())
    }

    /* ---------------- abertura, créditos e próximo episódio ---------------- */

    /**
     * Trechos para pular. Usa os segmentos do servidor (plugin de detecção de abertura/créditos)
     * e, se não houver, os capítulos com nomes como "Abertura", "Intro", "Créditos", "Ending".
     */
    suspend fun mediaSegments(itemId: String, runTimeTicks: Long?, chapters: List<Chapter>): List<MediaSegment> {
        val fromServer = runCatching {
            AppJson.decodeFromString(MediaSegmentsResult.serializer(), getText("/MediaSegments/$itemId")).items
        }.getOrDefault(emptyList()).filter { it.endTicks > it.startTicks }
        if (fromServer.isNotEmpty()) return fromServer
        val intro = Regex("intro|opening|abertura|recap|resumo|^op\\b", RegexOption.IGNORE_CASE)
        val outro = Regex("credit|cr[eé]dito|ending|encerramento|outro|^ed\\b", RegexOption.IGNORE_CASE)
        val sorted = chapters.sortedBy { it.startTicks }
        return sorted.mapIndexedNotNull { i, ch ->
            val name = ch.name.orEmpty()
            val end = sorted.getOrNull(i + 1)?.startTicks ?: runTimeTicks ?: return@mapIndexedNotNull null
            when {
                outro.containsMatchIn(name) -> MediaSegment("Outro", ch.startTicks, runTimeTicks ?: end)
                intro.containsMatchIn(name) -> MediaSegment("Intro", ch.startTicks, end)
                else -> null
            }
        }
    }

    /** Episódio seguinte da série (passa para a próxima temporada quando acaba a atual). */
    suspend fun nextEpisode(episode: Item): Item? {
        val seriesId = episode.seriesId ?: return null
        if (!episode.isEpisode) return null
        return items(
            "/Shows/$seriesId/Episodes",
            mapOf("userId" to s.userId, "startItemId" to episode.id, "limit" to 2, "fields" to FIELDS)
        ).items.firstOrNull { it.id != episode.id }
    }

    /* ---------------- TV ao vivo (Jellyfin) ---------------- */

    suspend fun liveChannels(): List<Item> = items(
        "/LiveTv/Channels",
        mapOf(
            "userId" to s.userId, "addCurrentProgram" to true, "enableImages" to true,
            "enableUserData" to false, "limit" to 1000, "fields" to "ChannelInfo"
        )
    ).items

    suspend fun closeLiveStream(liveStreamId: String) {
        postText("/LiveStreams/Close", "{}", mapOf("liveStreamId" to liveStreamId))
    }

    fun channelLogo(channel: Item): String? =
        channel.imageTags["Primary"]?.let { "${s.server}/Items/${channel.id}/Images/Primary?fillHeight=120&quality=90&tag=$it" }

    private fun playbackBody(itemId: String, positionTicks: Long, paused: Boolean, method: String, session: String): String =
        buildJsonObject {
            put("ItemId", itemId)
            put("MediaSourceId", itemId)
            put("PositionTicks", positionTicks)
            put("IsPaused", paused)
            put("CanSeek", true)
            put("PlayMethod", method)
            put("PlaySessionId", session)
        }.toString()

    suspend fun reportStart(itemId: String, ticks: Long, method: String, session: String) {
        postText("/Sessions/Playing", playbackBody(itemId, ticks, false, method, session))
    }

    suspend fun reportProgress(itemId: String, ticks: Long, paused: Boolean, method: String, session: String) {
        postText("/Sessions/Playing/Progress", playbackBody(itemId, ticks, paused, method, session))
    }

    suspend fun reportStopped(itemId: String, ticks: Long, method: String, session: String) {
        postText("/Sessions/Playing/Stopped", playbackBody(itemId, ticks, false, method, session))
    }
}
