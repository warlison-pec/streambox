package com.pecbox.streambox.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.BuildConfig
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Preferências + clientes HTTP compartilhados pelo app inteiro. */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("streambox", Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString("device_id", null)
        ?: UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("device_id", it).apply() }

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v.trim().trimEnd('/')).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(v) = prefs.edit().putString("user_id", v).apply()

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v).apply()

    /** Ponte do STRM Indexer (vazio = desativado). */
    var indexerUrl: String
        get() = prefs.getString("indexer_url", BuildConfig.DEFAULT_INDEXER) ?: ""
        set(v) = prefs.edit().putString("indexer_url", v.trim().trimEnd('/')).apply()

    /** TV ao vivo externa (opcional): lista M3U e guia XMLTV. */
    var m3uUrl: String
        get() = prefs.getString("m3u_url", "") ?: ""
        set(v) = prefs.edit().putString("m3u_url", v.trim()).apply()

    var epgUrl: String
        get() = prefs.getString("epg_url", "") ?: ""
        set(v) = prefs.edit().putString("epg_url", v.trim()).apply()

    /** Qualidade preferida: 0 = original; senão bitrate máximo em bps. */
    var maxBitrate: Int
        get() = prefs.getInt("max_bitrate", 0)
        set(v) = prefs.edit().putInt("max_bitrate", v).apply()

    /** Idioma preferido de legendas (código de 3 letras do Jellyfin). */
    var subtitleLang: String
        get() = prefs.getString("sub_lang", "por") ?: "por"
        set(v) = prefs.edit().putString("sub_lang", v).apply()

    /** Aparência da legenda (índices de SubtitleStyle). Padrão: médio, branco, contorno. */
    var subSize: Int
        get() = prefs.getInt("sub_size", 1)
        set(v) = prefs.edit().putInt("sub_size", v).apply()

    var subColor: Int
        get() = prefs.getInt("sub_color", 0)
        set(v) = prefs.edit().putInt("sub_color", v).apply()

    var subBackground: Int
        get() = prefs.getInt("sub_bg", 0)
        set(v) = prefs.edit().putInt("sub_bg", v).apply()

    /** Idioma preferido do áudio (código de 3 letras do Jellyfin). */
    var audioLang: String
        get() = prefs.getString("audio_lang", "por") ?: "por"
        set(v) = prefs.edit().putString("audio_lang", v).apply()

    /** Faixa lembrada (kind = "sub" ou "audio"): a escolhida nesta série ou, se ainda não houver, a última do app. */
    fun trackChoice(kind: String, seriesId: String?): TrackChoice? {
        val saved = seriesId?.let { prefs.getString("${kind}_choice_$it", null) } ?: prefs.getString("${kind}_choice", null)
        return saved?.let { runCatching { AppJson.decodeFromString(TrackChoice.serializer(), it) }.getOrNull() }
    }

    fun saveTrackChoice(kind: String, seriesId: String?, choice: TrackChoice) {
        val json = AppJson.encodeToString(TrackChoice.serializer(), choice)
        prefs.edit().apply {
            seriesId?.let { putString("${kind}_choice_$it", json) }
            if (!choice.off) putString("${kind}_choice", json)  // "Desligadas" vale só para a própria série
        }.apply()
    }

    /** Histórico de avisos (JSON). */
    var noticesJson: String
        get() = prefs.getString("notices", "") ?: ""
        set(v) = prefs.edit().putString("notices", v).apply()

    /** Última versão do app já avisada. */
    var updateNotified: String
        get() = prefs.getString("update_notified", "") ?: ""
        set(v) = prefs.edit().putString("update_notified", v).apply()

    /** Títulos pedidos pelo STRM Indexer neste aparelho (normalizados), para avisar quando chegarem. */
    val requestedTitles: Set<String> get() = prefs.getStringSet("requested_titles", emptySet()).orEmpty()

    fun addRequestedTitle(normalized: String) =
        prefs.edit().putStringSet("requested_titles", (requestedTitles + normalized).toList().takeLast(200).toSet()).apply()

    /** Marca até onde o catálogo já foi visto: data do item mais novo e os ids com essa mesma data. */
    fun contentBaseline(): Pair<String, Set<String>> =
        prefs.getString("content_base_$userId", "").orEmpty() to prefs.getStringSet("content_seen_$userId", emptySet()).orEmpty()

    fun saveContentBaseline(stamp: String, ids: Set<String>) =
        prefs.edit().putString("content_base_$userId", stamp).putStringSet("content_seen_$userId", ids).apply()

    /** Tela inicial: esconder filmes e séries já assistidos (vem ligado; o usuário pode desligar em Ajustes). */
    var hideWatched: Boolean
        get() = prefs.getBoolean("hide_watched_home", true)
        set(v) = prefs.edit().putBoolean("hide_watched_home", v).apply()

    /** Ordem das fileiras da tela inicial (chaves de [HomeSections]); as que faltarem vão para o fim. */
    var homeOrder: List<String>
        get() {
            val saved = prefs.getString("home_order", null)?.split(',')?.filter { it in HomeSections.DEFAULT_ORDER }.orEmpty()
            return (saved + HomeSections.DEFAULT_ORDER).distinct()
        }
        set(v) = prefs.edit().putString("home_order", v.joinToString(",")).apply()

    /** Fileiras da tela inicial que o usuário desligou. */
    var homeHidden: Set<String>
        get() = prefs.getStringSet("home_hidden", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("home_hidden", v).apply()

    fun homeShows(section: String) = section !in homeHidden

    /** Avisos: faixa que aparece no canto da tela. */
    var noticeBanner: Boolean
        get() = prefs.getBoolean("notice_banner", true)
        set(v) = prefs.edit().putBoolean("notice_banner", v).apply()

    /** Tipos de aviso desligados (Notice.NEW, REQUESTED, MESSAGE, UPDATE). */
    var noticeMuted: Set<String>
        get() = prefs.getStringSet("notice_muted", emptySet()).orEmpty()
        set(v) = prefs.edit().putStringSet("notice_muted", v).apply()

    /** Pedidos feitos neste aparelho (JSON), por usuário. */
    var myRequestsJson: String
        get() = prefs.getString("my_requests_$userId", "") ?: ""
        set(v) = prefs.edit().putString("my_requests_$userId", v).apply()

    /** Quantos pedidos o Seerr tinha na última consulta (para mostrar a aba Pedidos logo ao abrir). */
    var remoteRequestCount: Int
        get() = prefs.getInt("remote_requests_$userId", 0)
        set(v) = prefs.edit().putInt("remote_requests_$userId", v).apply()

    /** Proteção de tela: minutos sem uso até iniciar (0 = desligada). */
    var screensaverMinutes: Int
        get() = prefs.getInt("screensaver_min", 5)
        set(v) = prefs.edit().putInt("screensaver_min", v).apply()

    /** Modo vertical do player no celular (gira para retrato e amplia), lembrado por série ou filme. */
    fun verticalVideo(key: String): Boolean = prefs.getBoolean("vertical_$key", false)

    fun saveVerticalVideo(key: String, on: Boolean) =
        prefs.edit().apply { if (on) putBoolean("vertical_$key", true) else remove("vertical_$key") }.apply()

    /** Seerr/Jellyseerr (opcional). Autentica com cookie de sessão (conta Jellyfin) ou chave de API. */
    var seerrUrl: String
        get() = prefs.getString("seerr_url", "") ?: ""
        set(v) = prefs.edit().putString("seerr_url", v.trim().trimEnd('/')).apply()

    var seerrApiKey: String
        get() = prefs.getString("seerr_key", "") ?: ""
        set(v) = prefs.edit().putString("seerr_key", v.trim()).apply()

    var seerrCookie: String
        get() = prefs.getString("seerr_cookie", "") ?: ""
        set(v) = prefs.edit().putString("seerr_cookie", v).apply()

    var seerrUser: String
        get() = prefs.getString("seerr_user", "") ?: ""
        set(v) = prefs.edit().putString("seerr_user", v).apply()

    /** Estado observável pela interface: muda para false ao sair. */
    var loggedIn by mutableStateOf(server.isNotBlank() && token.isNotBlank() && userId.isNotBlank())
        private set

    fun saveLogin(server: String, auth: AuthResult) {
        this.server = server
        token = auth.accessToken
        userId = auth.user.id
        userName = auth.user.name
        loggedIn = true
    }

    fun logout() {
        token = ""
        userId = ""
        loggedIn = false
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val jellyfin = JellyfinApi(this)
    val indexer = IndexerApi(this)
    val externalTv = ExternalLiveTv(this, context.cacheDir)
    val seerr = SeerrApi(this)
}
