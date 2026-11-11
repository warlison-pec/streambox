package com.pecbox.streambox.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.AuthResult
import com.pecbox.streambox.data.TrackChoice
import com.pecbox.streambox.desktop.BuildConfig
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/** Pasta de dados do app: %APPDATA%\<app> no Windows, ~/.local/share/<app> no Linux. */
val dataDir: File by lazy {
    val base = System.getenv("APPDATA")?.let(::File)
        ?: System.getenv("XDG_DATA_HOME")?.let(::File)
        ?: File(System.getProperty("user.home"), ".local/share")
    File(base, BuildConfig.DATA_DIR).apply { mkdirs() }
}

/**
 * Preferências + cliente HTTP do app inteiro (no Android ficam no SharedPreferences;
 * aqui no Preferences do Java: registro do Windows ou ~/.java no Linux).
 * Cada edição usa um nó próprio, então PecBox e Comunidade podem ficar instaladas juntas.
 */
class Session {
    private val prefs: Preferences = Preferences.userRoot().node("com/pecbox/${BuildConfig.DATA_DIR}")

    private fun str(key: String, def: String = "") = prefs.get(key, def)
    private fun put(key: String, v: String) { prefs.put(key, v); runCatching { prefs.flush() } }

    val deviceId: String = str("device_id").ifBlank {
        UUID.randomUUID().toString().replace("-", "").also { put("device_id", it) }
    }

    var server: String
        get() = str("server")
        set(v) = put("server", v.trim().trimEnd('/'))

    var token: String
        get() = str("token")
        set(v) = put("token", v)

    var userId: String
        get() = str("user_id")
        set(v) = put("user_id", v)

    var userName: String
        get() = str("user_name")
        set(v) = put("user_name", v)

    /** Idiomas preferidos (código de 3 letras, como no Jellyfin). */
    var audioLang: String
        get() = str("audio_lang", "por")
        set(v) = put("audio_lang", v)

    var subtitleLang: String
        get() = str("sub_lang", "por")
        set(v) = put("sub_lang", v)

    /** Ponte do STRM Indexer (vazio = desativado). */
    var indexerUrl: String
        get() = str("indexer_url", BuildConfig.DEFAULT_INDEXER)
        set(v) = put("indexer_url", v.trim().trimEnd('/'))

    /** Seerr/Jellyseerr (opcional): cookie de sessão (conta Jellyfin) ou chave de API. */
    var seerrUrl: String
        get() = str("seerr_url")
        set(v) = put("seerr_url", v.trim().trimEnd('/'))

    var seerrApiKey: String
        get() = str("seerr_key")
        set(v) = put("seerr_key", v.trim())

    var seerrCookie: String
        get() = str("seerr_cookie")
        set(v) = put("seerr_cookie", v)

    var seerrUser: String
        get() = str("seerr_user")
        set(v) = put("seerr_user", v)

    /** TV ao vivo externa (opcional): lista M3U e guia XMLTV. */
    var m3uUrl: String
        get() = str("m3u_url")
        set(v) = put("m3u_url", v.trim())

    var epgUrl: String
        get() = str("epg_url")
        set(v) = put("epg_url", v.trim())

    /** Último canal assistido (JSON de LiveEntry), para abrir direto nele. */
    var lastChannelJson: String
        get() = str("live_last")
        set(v) = put("live_last", v)

    /** Player abre em tela cheia. */
    var fullscreen: Boolean
        get() = prefs.getBoolean("fullscreen", true)
        set(v) { prefs.putBoolean("fullscreen", v); runCatching { prefs.flush() } }

    /** Aparência da legenda (índices de SubtitleStyle, iguais aos do app Android). */
    var subSize: Int
        get() = prefs.getInt("sub_size", 1)
        set(v) { prefs.putInt("sub_size", v); runCatching { prefs.flush() } }

    var subColor: Int
        get() = prefs.getInt("sub_color", 0)
        set(v) { prefs.putInt("sub_color", v); runCatching { prefs.flush() } }

    var subBackground: Int
        get() = prefs.getInt("sub_bg", 0)
        set(v) { prefs.putInt("sub_bg", v); runCatching { prefs.flush() } }

    /** Legenda/áudio escolhidos no player: lembrados por série (e o último, para o resto). */
    fun trackChoice(kind: String, seriesId: String?): TrackChoice? {
        val saved = seriesId?.let { prefs.get("${kind}_choice_$it", null) } ?: prefs.get("${kind}_choice", null)
        return saved?.let { runCatching { AppJson.decodeFromString(TrackChoice.serializer(), it) }.getOrNull() }
    }

    fun saveTrackChoice(kind: String, seriesId: String?, choice: TrackChoice) {
        val json = AppJson.encodeToString(TrackChoice.serializer(), choice)
        seriesId?.let { prefs.put("${kind}_choice_$it", json) }
        if (!choice.off) prefs.put("${kind}_choice", json)   // "Desligadas" vale só para a própria série
        runCatching { prefs.flush() }
    }

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
    val seerr = SeerrApi(this)
    val externalTv = ExternalLiveTv(this, File(dataDir, "cache"))
}
