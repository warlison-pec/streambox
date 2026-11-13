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
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("LastPlayedDate") val lastPlayed: String? = null,
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
    @SerialName("ProductionLocations") val productionLocations: List<String> = emptyList(),
    @SerialName("Tags") val tags: List<String> = emptyList(),
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
    @SerialName("ParentLogoItemId") val parentLogoId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoTag: String? = null,
    @SerialName("DateCreated") val dateCreated: String? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String> = emptyMap(),
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
    @SerialName("Channels") val channels: Int? = null,
) {
    val label: String get() = displayTitle ?: title ?: language ?: "Faixa $index"
}

/** Faixa (legenda ou áudio) escolhida pelo usuário, descrita de um jeito que também a encontre em outro episódio. */
@Serializable
data class TrackChoice(
    val off: Boolean = false,
    val language: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
)

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
    @SerialName("Bitrate") val bitrate: Int? = null,
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
data class SourceSwitch(val switched: Boolean = false, val reason: String? = null, val quality: String? = null)

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
data class SeerrRequestMedia(val tmdbId: Int? = null)

@Serializable
data class SeerrRequestEntry(val media: SeerrRequestMedia? = null, val requestedBy: SeerrUser? = null)

@Serializable
data class SeerrRequests(val results: List<SeerrRequestEntry> = emptyList())

/** Pedido do Seerr com status. request.status: 1 aguardando · 2 aprovado · 3 recusado. */
@Serializable
data class SeerrRequestFull(
    val id: Int = 0,
    val status: Int = 1,
    val requestedBy: SeerrUser? = null,
    val type: String = "",
    val createdAt: String? = null,
    val media: SeerrMediaFull? = null,
)

/** media.status: 1 desconhecido · 2 pendente · 3 processando · 4 parcialmente disponível · 5 disponível */
@Serializable
data class SeerrMediaFull(
    val tmdbId: Int? = null,
    val mediaType: String = "",
    val status: Int = 1,
    val jellyfinMediaId: String? = null,
)

@Serializable
data class SeerrRequestsFull(val results: List<SeerrRequestFull> = emptyList())

/** Pedido do STRM Indexer (rota /mine: só os do próprio usuário do Jellyfin). */
@Serializable
data class IndexerRequest(
    val id: String = "",
    val kind: String = "",
    val title: String = "",
    val year: Int? = null,
    val season: Int? = null,
    val state: String = "",
    val message: String = "",
    @SerialName("created_at") val createdAt: Double = 0.0,
)

@Serializable
data class IndexerMine(val items: List<IndexerRequest> = emptyList())

/* ----------------------------- Meus pedidos ----------------------------- */

/** Pedido feito neste aparelho (STRM Indexer ou Seerr), guardado para a aba "Pedidos". */
@Serializable
data class MyRequest(
    val key: String,                    // origem:tipo:identificador (sem repetição)
    val source: String,                 // "strm" | "seerr"
    val kind: String,                   // "movie" | "series"
    val title: String,
    val year: Int? = null,
    val poster: String? = null,
    val time: Long = 0,
    val remoteId: String? = null,       // id do pedido no STRM Indexer
    val tmdbId: Int? = null,
)

@Serializable
data class SeerrSeason(val seasonNumber: Int = 0)

@Serializable
data class SeerrTv(val seasons: List<SeerrSeason> = emptyList())

/* ----------------------------- Avisos ----------------------------- */

@Serializable
data class Notice(
    val id: String,
    val kind: String,                   // ver Notice.MESSAGE etc.
    val title: String,
    val text: String,
    val time: Long,
    val itemId: String? = null,         // abre os detalhes deste item
    val url: String? = null,            // abre esta página
    val read: Boolean = false,
    val image: String? = null,          // capa (novidades e pedidos)
) {
    companion object {
        const val MESSAGE = "message"   // enviada pelo painel do Jellyfin
        const val UPDATE = "update"
        const val NEW = "new"
        const val REQUESTED = "requested"
    }
}
