package com.pecbox.streambox.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ----------------------------- Jellyfin ----------------------------- */

@Serializable
data class PublicInfo(
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
)

@Serializable
data class AuthResult(
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("User") val user: UserDto,
)

@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("UnplayedItemCount") val unplayed: Int? = null,
)

@Serializable
data class Item(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("CommunityRating") val rating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("ParentIndexNumber") val seasonNumber: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropTags: List<String> = emptyList(),
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryTag: String? = null,
    @SerialName("UserData") val userData: UserData? = null,
    // TV ao vivo
    @SerialName("ChannelNumber") val channelNumber: String? = null,
    @SerialName("CurrentProgram") val currentProgram: Item? = null,
    @SerialName("StartDate") val startDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    // capítulos (usados para achar abertura/créditos quando o servidor não tem segmentos)
    @SerialName("Chapters") val chapters: List<Chapter> = emptyList(),
) {
    val isSeries get() = type == "Series"
    val isEpisode get() = type == "Episode"
    val isFolderLike get() = type == "Series" || type == "Season" || type == "BoxSet" || type == "CollectionFolder"

    /** "S01E02 · Nome" para episódios. */
    val episodeLabel: String
        get() = if (isEpisode) "S%02dE%02d".format(seasonNumber ?: 0, indexNumber ?: 0) + " · " + name else name

    val progress: Float
        get() {
            val pct = userData?.playedPercentage ?: return 0f
            return (pct / 100.0).toFloat().coerceIn(0f, 1f)
        }

    val runtimeLabel: String?
        get() {
            val ticks = runTimeTicks ?: return null
            val minutes = ticks / 600_000_000L
            if (minutes <= 0) return null
            return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "${minutes}min"
        }
}

@Serializable
data class Chapter(
    @SerialName("StartPositionTicks") val startTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
)

/** Trecho marcado no servidor (Jellyfin 10.10+): Intro, Outro, Recap, Preview, Commercial. */
@Serializable
data class MediaSegment(
    @SerialName("Type") val type: String = "",
    @SerialName("StartTicks") val startTicks: Long = 0,
    @SerialName("EndTicks") val endTicks: Long = 0,
)

@Serializable
data class MediaSegmentsResult(
    @SerialName("Items") val items: List<MediaSegment> = emptyList(),
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<Item> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable
data class MediaStream(
    @SerialName("Type") val type: String = "",
    @SerialName("Index") val index: Int = 0,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsForced") val isForced: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isText: Boolean = false,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Width") val width: Int? = null,
) {
    val label: String get() = displayTitle ?: title ?: language ?: "Faixa $index"
}

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String = "",
    @SerialName("Path") val path: String? = null,
    @SerialName("IsRemote") val isRemote: Boolean = false,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean = false,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean = false,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("MediaStreams") val streams: List<MediaStream> = emptyList(),
) {
    val subtitles get() = streams.filter { it.type == "Subtitle" }
    val videoHeight get() = streams.firstOrNull { it.type == "Video" }?.height
}

@Serializable
data class PlaybackInfo(
    @SerialName("MediaSources") val sources: List<MediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
)

@Serializable
data class RemoteSubtitle(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String = "",
    @SerialName("ProviderName") val provider: String = "",
    @SerialName("Format") val format: String? = null,
    @SerialName("DownloadCount") val downloads: Int? = null,
)

@Serializable
data class QuickConnectResult(
    @SerialName("Secret") val secret: String = "",
    @SerialName("Code") val code: String = "",
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

/** Tipos de item exibidos em cada tipo de biblioteca (null = biblioteca não suportada). */
fun libraryItemTypes(collectionType: String?): String? = when (collectionType) {
    "movies" -> "Movie"
    "tvshows" -> "Series"
    "boxsets" -> "BoxSet"
    "homevideos", "mixed", null, "" -> "Movie,Series,Video"
    else -> null // music, livetv, playlists, books, photos…
}

/* ----------------------------- Indexador ----------------------------- */

@Serializable
data class IndexerItem(
    val kind: String,
    val title: String,
    val year: Int? = null,
    @SerialName("catalog_year") val catalogYear: Int? = null,
    val sources: Int? = null,
    val episodes: Int? = null,
    @SerialName("in_library") val inLibrary: Int? = null,
    val seasons: List<Int> = emptyList(),
    val poster: String? = null,
    val overview: String? = null,
    val requested: Boolean = false,
) {
    val key get() = "$kind|$title|${catalogYear ?: ""}"
}

@Serializable
data class IndexerSearch(val items: List<IndexerItem> = emptyList())

@Serializable
data class RequestCreated(val id: String, val state: String = "queued", val duplicate: Boolean = false)

@Serializable
data class RequestStatus(
    val id: String,
    val state: String,
    val message: String = "",
)

/* ----------------------------- Seerr / Jellyseerr ----------------------------- */

@Serializable
data class SeerrMediaInfo(val status: Int = 1)

@Serializable
data class SeerrResult(
    val id: Int = 0,
    val mediaType: String = "",
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val posterPath: String? = null,
    val overview: String? = null,
    val mediaInfo: SeerrMediaInfo? = null,
) {
    val displayTitle get() = title ?: name ?: "Sem título"
    val year get() = (releaseDate ?: firstAirDate)?.take(4)?.takeIf { it.length == 4 }
    val poster get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    /** 1 desconhecido · 2 pendente · 3 processando · 4 parcialmente disponível · 5 disponível */
    val status get() = mediaInfo?.status ?: 1
}

@Serializable
data class SeerrSearch(val results: List<SeerrResult> = emptyList())

@Serializable
data class SeerrUser(
    val id: Int = 0,
    val displayName: String? = null,
    val username: String? = null,
    val jellyfinUsername: String? = null,
    val email: String? = null,
) {
    val label get() = displayName ?: jellyfinUsername ?: username ?: email ?: "usuário #$id"
}

@Serializable
data class SeerrSeason(val seasonNumber: Int = 0)

@Serializable
data class SeerrTv(val seasons: List<SeerrSeason> = emptyList())
