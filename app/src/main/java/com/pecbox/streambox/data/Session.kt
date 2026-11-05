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

    /** Proteção de tela: minutos sem uso até iniciar (0 = desligada). */
    var screensaverMinutes: Int
        get() = prefs.getInt("screensaver_min", 5)
        set(v) = prefs.edit().putInt("screensaver_min", v).apply()

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
