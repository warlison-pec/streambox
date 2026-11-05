package com.pecbox.streambox.player

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.pecbox.streambox.App
import com.pecbox.streambox.R
import com.pecbox.streambox.data.SubtitleStyle
import com.pecbox.streambox.data.MediaSource
import com.pecbox.streambox.data.MediaStream
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.MediaSegment
import android.content.Intent
import com.pecbox.streambox.data.friendly
import kotlinx.coroutines.delay
import android.view.FocusFinder
import android.widget.HorizontalScrollView
import coil.imageLoader
import coil.request.ImageRequest
import com.pecbox.streambox.ui.Watched
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Player nativo (Media3/ExoPlayer).
 *  - Filmes/séries do Jellyfin: arquivo original; se o aparelho não suportar, HLS convertido no servidor.
 *  - Botões Legendas / Áudio / Qualidade (também pelo botão Menu do controle remoto).
 *  - Canais ao vivo do Jellyfin ou de uma lista M3U externa.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_START_TICKS = "start"
        const val EXTRA_URL = "url"          // canal de lista M3U externa
        const val EXTRA_JF_LIVE = "jf_live"  // canal ao vivo do Jellyfin
        const val EXTRA_UA = "ua"            // cabeçalhos exigidos pelo canal (lista M3U)
        const val EXTRA_REFERER = "referer"
        private const val TICKS_PER_MS = 10_000L

        /** (rótulo, bitrate máximo, altura máxima). bitrate 0 = original. */
        val QUALITIES = listOf(
            Triple("Original (sem conversão)", 0, null),
            Triple("1080p · 10 Mbps", 10_000_000, 1080),
            Triple("720p · 4 Mbps", 4_000_000, 720),
            Triple("480p · 1,5 Mbps", 1_500_000, 480),
            Triple("360p · 700 kbps", 700_000, 360),
        )
    }

    private val session get() = App.session
    private val jf get() = session.jellyfin

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var optionsBar: LinearLayout

    private var itemId: String? = null
    private var externalUrl: String? = null
    private var jfLive = false
    private val isLive get() = externalUrl != null || jfLive
    private var title = ""

    private var method = "DirectStream"
    private var triedHls = false
    private var started = false
    private var reconnects = 0                 // TV ao vivo: tentativas seguidas de reconexão
    private val stallTask = Runnable { if (isLive && player?.playbackState == Player.STATE_BUFFERING) reconnect("O canal parou de responder") }
    private var userPaused = false             // pausa pedida pelo usuário: a única que o ao vivo respeita
    private var reconnectPending = false
    private var lastPos = -1L
    private var frozenMs = 0L

    /* Ao vivo: o vídeo não pode ficar parado sem o usuário pedir.
       - pausou sozinho → volta a tocar;
       - ficou parado (sem avançar) por ~20 s → reconecta a fonte. */
    private val liveWatchdog = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (isLive && !userPaused && !reconnectPending) {
                if (!p.playWhenReady) p.playWhenReady = true
                if (p.playbackState == Player.STATE_IDLE) { p.prepare() }
                val pos = p.currentPosition
                frozenMs = if (pos == lastPos && !p.isPlaying) frozenMs + 2000 else 0
                lastPos = pos
                if (frozenMs >= 20_000) { frozenMs = 0; reconnect("O canal parou de responder") }
            } else frozenMs = 0
            handler.postDelayed(this, 2000)
        }
    }
    private var quality = 0                 // índice em QUALITIES
    private var currentUrl = ""
    private var currentIsHls = false
    private var source: MediaSource? = null // faixas do Jellyfin (legendas, áudio)
    private var liveStreamId: String? = null
    private var externalSub: MediaStream? = null // legenda do Jellyfin carregada como arquivo VTT
    private var audioIndex: Int? = null
    private val playSession = UUID.randomUUID().toString().replace("-", "")
    private val handler = Handler(Looper.getMainLooper())

    // Abertura / créditos / próximo episódio
    private var current: Item? = null
    private var segments: List<MediaSegment> = emptyList()
    private var next: Item? = null
    private var nextDismissed = false
    private var nextDeadline = 0L          // quando o próximo episódio começa sozinho (0 = cartão fechado)
    private var activeSkip: MediaSegment? = null
    private lateinit var skipButton: TextView
    private lateinit var nextCard: LinearLayout
    private lateinit var nextTitle: TextView
    private lateinit var nextCountdown: TextView
    private lateinit var nextPlay: TextView
    private lateinit var nextCancel: TextView
    private var overlaySel = 0             // botão destacado no cartão (controle remoto)

    // Episódios da série: anterior / próximo e a lista que abre com ↓ abaixo dos controles
    private var episodes: List<Item> = emptyList()
    private var prevEp: Item? = null
    private lateinit var episodesPanel: LinearLayout
    private lateinit var episodesTitle: TextView
    private lateinit var episodesRow: LinearLayout
    private lateinit var seekOsd: TextView
    private var seekTarget = -1L           // posição acumulada enquanto o usuário aperta ←/→
    private val seekCommit = Runnable { seekTarget = -1L }
    private val hideOsd = Runnable { if (::seekOsd.isInitialized) seekOsd.visibility = View.GONE }

    private val segmentTask = object : Runnable {
        override fun run() {
            updateOverlays()
            handler.postDelayed(this, 400)
        }
    }

    private val progressTask = object : Runnable {
        override fun run() {
            report { id, ticks, paused -> jf.reportProgress(id, ticks, paused, method, playSession) }
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        itemId = intent.getStringExtra(EXTRA_ID)
        externalUrl = intent.getStringExtra(EXTRA_URL)
        jfLive = intent.getBooleanExtra(EXTRA_JF_LIVE, false)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (itemId == null && externalUrl == null) { finish(); return }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        view = PlayerView(this).apply {
            keepScreenOn = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 5000
            setShowSubtitleButton(false) // usamos o nosso botão de legendas
        }
        applySubtitleStyle()
        optionsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                optionsBar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                    .apply { bottomMargin = dp(64); marginEnd = dp(12) }
            )
        }
        buildOverlays(root)
        setContentView(root)
        installControls()

        // HTTP com User-Agent e redirecionamento http↔https (comum em listas IPTV)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(intent.getStringExtra(EXTRA_UA)?.ifBlank { null } ?: "StreamBox/Android ExoPlayer")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        intent.getStringExtra(EXTRA_REFERER)?.ifBlank { null }?.let { ref ->
            val origin = runCatching { Uri.parse(ref).let { "${it.scheme}://${it.authority}" } }.getOrNull()
            http.setDefaultRequestProperties(buildMap { put("Referer", ref); if (origin != null) put("Origin", origin) })
        }
        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)).apply {
                    // ao vivo: insiste mais antes de considerar a lista/pedaço perdido
                    if (isLive) setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))
                }
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(session.subtitleLang.take(2))
            .setPreferredAudioLanguage("pt")
            .build()
        exo.addListener(listener)
        player = exo
        view.player = EpisodePlayer(exo)
        if (isLive) handler.postDelayed(liveWatchdog, 2000)

        when {
            externalUrl != null -> load(externalUrl!!, 0, hls = externalUrl!!.contains(".m3u8"))
            jfLive -> startJellyfinLive()
            else -> startVod()
        }
    }

    /* ---------------------------- início ---------------------------- */

    private fun startVod() {
        val id = itemId ?: return
        val startMs = intent.getLongExtra(EXTRA_START_TICKS, 0L) / TICKS_PER_MS
        val pref = session.maxBitrate
        quality = QUALITIES.indexOfFirst { it.second == pref }.coerceAtLeast(0)
        if (quality == 0) {
            load(jf.directStreamUrl(id), startMs, hls = false)
        } else {
            method = "Transcode"
            val q = QUALITIES[quality]
            load(jf.hlsUrl(id, playSession, q.second, q.third), startMs, hls = true)
        }
        // Faixas e legendas em segundo plano (não atrasa o início)
        lifecycleScope.launch {
            source = runCatching { jf.playbackInfo(id).sources.firstOrNull() }.getOrNull()
        }
        // Abertura, créditos e próximo episódio (também em segundo plano)
        lifecycleScope.launch {
            val item = runCatching { jf.item(id) }.getOrNull() ?: return@launch
            current = item
            segments = runCatching { jf.mediaSegments(id, item.runTimeTicks, item.chapters) }.getOrDefault(emptyList())
            val sid = item.seriesId
            if (item.isEpisode && sid != null) {
                episodes = runCatching { jf.seriesEpisodes(sid) }.getOrDefault(emptyList())
                val idx = episodes.indexOfFirst { it.id == item.id }
                if (idx >= 0) {
                    prevEp = episodes.getOrNull(idx - 1)
                    next = episodes.getOrNull(idx + 1)
                } else {
                    next = runCatching { jf.nextEpisode(item) }.getOrNull()
                }
                buildEpisodesPanel(item)
            }
            // mostra/ativa ⏮ e ⏭ nos controles
            view.setShowPreviousButton(prevEp != null)
            view.setShowNextButton(next != null)
            handler.removeCallbacks(segmentTask)
            handler.post(segmentTask)
        }
    }

    private fun startJellyfinLive() {
        val id = itemId ?: return
        lifecycleScope.launch {
            try {
                val info = jf.playbackInfo(id, live = true)
                val src = info.sources.firstOrNull() ?: throw IllegalStateException("Canal sem fonte")
                source = src
                liveStreamId = src.liveStreamId
                val transcode = src.transcodingUrl
                val path = src.path
                when {
                    transcode != null -> { method = "Transcode"; load(jf.absolute(transcode), 0, hls = true) }
                    src.isRemote && path != null && path.startsWith("http") -> load(path, 0, hls = path.contains(".m3u8"))
                    else -> load(jf.directStreamUrl(id), 0, hls = false)
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Canal indisponível: ${e.friendly()}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun load(url: String, positionMs: Long, hls: Boolean) {
        val p = player ?: return
        currentUrl = url
        currentIsHls = hls
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (hls) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        val sub = externalSub
        val id = itemId
        val src = source
        if (sub != null && id != null && src != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(jf.subtitleUrl(id, src.id, sub.index)))
                        .setMimeType(MimeTypes.TEXT_VTT)
                        .setLanguage(sub.language)
                        .setLabel(sub.label)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        p.setMediaItem(builder.build(), positionMs)
        p.prepare()
        p.playWhenReady = true
    }

    /** Recarrega a mesma mídia (troca de qualidade/legenda) mantendo a posição. */
    private fun reload() {
        val id = itemId ?: return
        val pos = player?.currentPosition ?: 0
        val q = QUALITIES[quality]
        if (q.second == 0 && !triedHls) {
            method = "DirectStream"
            load(jf.directStreamUrl(id), pos, hls = false)
        } else {
            method = "Transcode"
            val bitrate = if (q.second == 0) 20_000_000 else q.second
            load(jf.hlsUrl(id, playSession, bitrate, q.third, audioIndex), pos, hls = true)
        }
    }

    /* ---------------------------- eventos ---------------------------- */

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (isLive) {
                    handler.removeCallbacks(stallTask)
                    handler.postDelayed(stallTask, 15_000)
                }
                Player.STATE_READY -> if (!started) {
                    started = true
                    if (!isLive) {
                        report { id, ticks, _ -> jf.reportStart(id, ticks, method, playSession) }
                        handler.postDelayed(progressTask, 10_000)
                    }
                }
                Player.STATE_ENDED -> if (!isLive) {
                    if (next != null && !nextDismissed) playNext() else finish()
                } else {
                    reconnect("A transmissão foi interrompida")   // fonte fechou a conexão: abre de novo
                }
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Só conta como pausa do usuário o que veio do controle/botões (ou fone desconectado / ligação)
            userPaused = !playWhenReady && reason in setOf(
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY,
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) { reconnects = 0; handler.removeCallbacks(stallTask) }
            if (started && !isLive) report { id, ticks, paused -> jf.reportProgress(id, ticks, paused, method, playSession) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            if (isLive) { reconnect(error.errorCodeName); return }
            val id = itemId
            if (!isLive && id != null && !triedHls && !currentIsHls) {
                // Formato não suportado pelo aparelho: pede conversão ao servidor e continua do mesmo ponto.
                triedHls = true
                method = "Transcode"
                val pos = p.currentPosition.coerceAtLeast(intent.getLongExtra(EXTRA_START_TICKS, 0L) / TICKS_PER_MS)
                Toast.makeText(this@PlayerActivity, "Convertendo no servidor…", Toast.LENGTH_SHORT).show()
                val q = QUALITIES[quality]
                load(jf.hlsUrl(id, playSession, if (q.second == 0) 20_000_000 else q.second, q.third, audioIndex), pos, hls = true)
            } else {
                Toast.makeText(this@PlayerActivity, "Não foi possível reproduzir: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    /** Sinal ao vivo caiu: reconecta sem desistir, com espera crescente (até 15 s entre tentativas). */
    private fun reconnect(reason: String) {
        handler.removeCallbacks(stallTask)
        if (reconnectPending || player == null) return
        reconnectPending = true
        reconnects++
        val msg = if (reconnects <= 3) "Sinal perdido. Reconectando…" else "Sem sinal ($reason). Tentando de novo ($reconnects)…"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            reconnectPending = false
            if (player != null) { userPaused = false; load(currentUrl, 0, currentIsHls) }
        }, (1500L * reconnects).coerceAtMost(15_000L))
    }

    private fun report(block: suspend (id: String, ticks: Long, paused: Boolean) -> Unit) {
        val p = player ?: return
        val id = itemId ?: return
        val ticks = p.currentPosition * TICKS_PER_MS
        val paused = !p.isPlaying
        App.scope.launch { runCatching { block(id, ticks, paused) } }
    }

    /* ---------------------------- botões e menus ---------------------------- */

    /**
     * Coloca os ícones de Legendas, Áudio e Qualidade na barra inferior do player, ao lado dos
     * controles padrão. Se o layout do Media3 mudar, cai para uma barra própria no canto inferior.
     */
    private fun installControls() {
        val buttons = buildList {
            add(iconButton(R.drawable.ic_sb_subtitles, "Legendas") { showSubtitles() })
            add(iconButton(R.drawable.ic_sb_audio, "Áudio") { showAudio() })
            if (!isLive) add(iconButton(R.drawable.ic_sb_hd, "Qualidade") { showQuality() })
        }
        val basicId = resources.getIdentifier("exo_basic_controls", "id", packageName)
        val basic: ViewGroup? = if (basicId != 0) view.findViewById(basicId) else null
        if (basic != null) {
            buttons.reversed().forEach { basic.addView(it, 0) }
        } else {
            buttons.forEach { optionsBar.addView(it) }
            view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> optionsBar.visibility = v })
        }
    }

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(icon)
        contentDescription = label
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isFocusable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), circle(0xFFFF7A00.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), circle(0x66FF7A00))
            addState(intArrayOf(), circle(Color.TRANSPARENT))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    /** Aplica tamanho, cor e fundo escolhidos para a legenda. */
    private fun applySubtitleStyle() {
        val sv = view.subtitleView ?: return
        val size = SubtitleStyle.SIZES.getOrElse(session.subSize) { SubtitleStyle.SIZES[1] }.second
        val color = SubtitleStyle.COLORS.getOrElse(session.subColor) { SubtitleStyle.COLORS[0] }.second
        val (bg, edge) = when (session.subBackground) {
            1 -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            2 -> 0x99000000.toInt() to CaptionStyleCompat.EDGE_TYPE_NONE
            3 -> Color.BLACK to CaptionStyleCompat.EDGE_TYPE_NONE
            else -> Color.TRANSPARENT to CaptionStyleCompat.EDGE_TYPE_OUTLINE
        }
        sv.setApplyEmbeddedStyles(false)
        sv.setApplyEmbeddedFontSizes(false)
        sv.setStyle(CaptionStyleCompat(color, bg, Color.TRANSPARENT, edge, Color.BLACK, Typeface.DEFAULT_BOLD))
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f * size)
        sv.setBottomPaddingFraction(0.08f)
    }

    private fun showAppearance() {
        val items = listOf(
            "Tamanho: " + SubtitleStyle.SIZES.getOrElse(session.subSize) { SubtitleStyle.SIZES[1] }.first,
            "Cor: " + SubtitleStyle.COLORS.getOrElse(session.subColor) { SubtitleStyle.COLORS[0] }.first,
            "Fundo: " + SubtitleStyle.BACKGROUNDS.getOrElse(session.subBackground) { SubtitleStyle.BACKGROUNDS[0] },
        )
        choose("Aparência da legenda", items, -1) { i ->
            when (i) {
                0 -> choose("Tamanho", SubtitleStyle.SIZES.map { it.first }, session.subSize) { v -> session.subSize = v; applySubtitleStyle(); showAppearance() }
                1 -> choose("Cor", SubtitleStyle.COLORS.map { it.first }, session.subColor) { v -> session.subColor = v; applySubtitleStyle(); showAppearance() }
                else -> choose("Fundo", SubtitleStyle.BACKGROUNDS, session.subBackground) { v -> session.subBackground = v; applySubtitleStyle(); showAppearance() }
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun choose(title: String, labels: List<String>, checked: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which -> d.dismiss(); onPick(which) }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showMenu() {
        val options = mutableListOf("Legendas", "Áudio")
        if (!isLive) options += "Qualidade"
        choose("Opções", options, -1) { i ->
            when (options[i]) {
                "Legendas" -> showSubtitles()
                "Áudio" -> showAudio()
                "Qualidade" -> showQuality()
            }
        }
    }

    private fun showQuality() {
        choose("Qualidade", QUALITIES.map { it.first }, quality) { i ->
            if (i == quality) return@choose
            quality = i
            session.maxBitrate = QUALITIES[i].second
            if (i == 0) triedHls = false
            reload()
        }
    }

    /** Opção da lista de legendas. */
    private sealed interface SubOption {
        val label: String
        data object Off : SubOption { override val label = "Desligadas" }
        data class Embedded(val group: Tracks.Group, val index: Int, override val label: String) : SubOption
        data class FromServer(val stream: MediaStream, override val label: String) : SubOption
        data object Download : SubOption { override val label = "Baixar legenda da internet…" }
        data object Appearance : SubOption { override val label = "Aparência da legenda…" }
    }

    private fun showSubtitles() {
        val p = player ?: return
        val options = mutableListOf<SubOption>(SubOption.Off)
        var checked = 0
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        // Faixas que o player já conhece (embutidas no arquivo ou a VTT carregada)
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val label = listOfNotNull(f.label, f.language?.uppercase()).joinToString(" · ").ifBlank { "Legenda ${options.size}" }
                options += SubOption.Embedded(group, i, label)
                if (!textDisabled && group.isTrackSelected(i)) checked = options.lastIndex
            }
        }
        // Legendas de texto do servidor ainda não carregadas (externas, ou todas quando há conversão)
        val src = source
        if (src != null && itemId != null) {
            val loaded = externalSub?.index
            src.subtitles
                .filter { (it.isExternal || currentIsHls) && (it.isText || it.isExternal) && it.index != loaded }
                .forEach { options += SubOption.FromServer(it, "${it.label} (servidor)") }
        }
        if (itemId != null && !isLive) options += SubOption.Download
        options += SubOption.Appearance
        if (options.size <= 2 && src == null && itemId != null && !isLive) {
            Toast.makeText(this, "Carregando lista de legendas…", Toast.LENGTH_SHORT).show()
        }

        choose("Legendas", options.map { it.label }, checked) { i ->
            when (val o = options[i]) {
                SubOption.Off -> {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
                is SubOption.Embedded -> {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                        .build()
                }
                is SubOption.FromServer -> {
                    externalSub = o.stream
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                    reloadKeepingSource()
                }
                SubOption.Download -> searchOnline()
                SubOption.Appearance -> showAppearance()
            }
        }
    }

    /** Recarrega a fonte atual (mesma URL) para anexar a legenda escolhida. */
    private fun reloadKeepingSource() {
        val pos = player?.currentPosition ?: 0
        load(currentUrl, pos, currentIsHls)
    }

    private fun searchOnline() {
        val id = itemId ?: return
        Toast.makeText(this, "Procurando legendas…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val lang = session.subtitleLang
            val found = runCatching { jf.searchSubtitles(id, lang) }
            found.onFailure {
                Toast.makeText(this@PlayerActivity, "Busca indisponível: ${it.friendly()}. Confira o plugin de legendas no Jellyfin.", Toast.LENGTH_LONG).show()
            }
            val list = found.getOrNull() ?: return@launch
            if (list.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Nenhuma legenda encontrada.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val top = list.sortedByDescending { it.downloads ?: 0 }.take(25)
            choose("Legendas encontradas", top.map { "${it.name} · ${it.provider}" }, -1) { i -> download(id, top[i].id) }
        }
    }

    private fun download(id: String, subtitleId: String) {
        Toast.makeText(this, "Baixando legenda…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val before = source?.subtitles?.map { it.index }?.toSet().orEmpty()
            try {
                jf.downloadSubtitle(id, subtitleId)
                // O servidor leva alguns segundos para registrar a nova legenda
                var added: MediaStream? = null
                repeat(6) {
                    if (added != null) return@repeat
                    delay(2000)
                    val src = runCatching { jf.playbackInfo(id).sources.firstOrNull() }.getOrNull() ?: return@repeat
                    source = src
                    added = src.subtitles.filter { it.index !in before }.maxByOrNull { it.index }
                }
                val sub = added
                if (sub == null) {
                    Toast.makeText(this@PlayerActivity, "Legenda baixada. Abra o menu Legendas em instantes.", Toast.LENGTH_LONG).show()
                } else {
                    externalSub = sub
                    player?.let { p ->
                        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
                    }
                    reloadKeepingSource()
                    Toast.makeText(this@PlayerActivity, "Legenda aplicada.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@PlayerActivity, "Não foi possível baixar: ${e.friendly()}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAudio() {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        var checked = -1
        for (g in groups) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val ch = when (f.channelCount) { 1 -> "Mono"; 2 -> "Estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
            val label = listOfNotNull(f.label, f.language?.uppercase(), ch).joinToString(" · ").ifBlank { "Faixa ${entries.size + 1}" }
            if (g.isTrackSelected(i)) checked = entries.size
            val action: () -> Unit = {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
            }
            entries += label to action
        }
        // Na conversão HLS só vem 1 faixa: oferece as do servidor
        val src = source
        if (currentIsHls && src != null && !isLive) {
            src.streams.filter { it.type == "Audio" }.forEach { a ->
                val action: () -> Unit = { audioIndex = a.index; reload() }
                entries += "${a.label} (servidor)" to action
            }
        }
        if (entries.isEmpty()) {
            Toast.makeText(this, "Só há uma faixa de áudio.", Toast.LENGTH_SHORT).show()
            return
        }
        choose("Áudio", entries.map { it.first }, checked) { i -> entries[i].second() }
    }

    /* ------------------- pular abertura / próximo episódio ------------------- */

    private fun pill(text: String, primary: Boolean) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(11), dp(18), dp(11))
        isClickable = true
        tag = primary
        background = pillBg(false, primary)
    }

    private fun pillBg(selected: Boolean, primary: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        when {
            selected -> setColor(0xFFFF7A00.toInt())
            primary -> { setColor(0xE6202020.toInt()); setStroke(dp(2), 0xFFFF7A00.toInt()) }
            else -> { setColor(0xCC202020.toInt()); setStroke(dp(1), 0x55FFFFFF) }
        }
    }

    private fun buildOverlays(root: FrameLayout) {
        skipButton = pill("Pular abertura", true).apply {
            visibility = View.GONE
            setOnClickListener { activeSkip?.let { seg -> player?.seekTo(seg.endTicks / TICKS_PER_MS) }; activeSkip = null; visibility = View.GONE }
        }
        root.addView(skipButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { bottomMargin = dp(110); marginEnd = dp(32) })

        nextTitle = TextView(this).apply { setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD; maxLines = 2 }
        nextCountdown = TextView(this).apply { setTextColor(0xFFBDBDBD.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        nextPlay = pill("▶  Assistir agora", true).apply { setOnClickListener { playNext() } }
        nextCancel = pill("Cancelar", false).apply { setOnClickListener { nextDismissed = true; hideNext() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(nextPlay)
            addView(nextCancel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) })
        }
        nextCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xF0121212.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            addView(TextView(this@PlayerActivity).apply {
                text = "PRÓXIMO EPISÓDIO"; setTextColor(0xFFFF7A00.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.12f
            })
            addView(nextTitle)
            addView(nextCountdown)
            addView(row)
        }
        root.addView(nextCard, FrameLayout.LayoutParams(dp(360), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { bottomMargin = dp(110); marginEnd = dp(32) })

        // Aviso de avanço/recuo pelas setas (sem abrir os controles)
        seekOsd = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xCC000000.toInt()) }
        }
        root.addView(seekOsd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(40) })

        // Lista de episódios (abre com ↓ abaixo dos controles)
        episodesTitle = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(40), 0, dp(40), dp(10))
        }
        episodesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(36), dp(6), dp(36), dp(6)) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(episodesRow)
        }
        episodesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(48), 0, dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xE6000000.toInt(), 0xF5000000.toInt()))
            addView(episodesTitle)
            addView(scroll)
        }
        root.addView(episodesPanel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** Cartões dos episódios da temporada atual (o atual fica marcado). */
    private fun buildEpisodesPanel(cur: Item) {
        val season = episodes.filter { it.seasonId == cur.seasonId }.ifEmpty { episodes }
        episodesTitle.text = listOfNotNull(cur.seriesName, cur.seasonNumber?.let { "Temporada $it" }).joinToString(" · ")
        episodesRow.removeAllViews()
        val w = dp(230)
        for (ep in season) {
            val isCurrent = ep.id == cur.id
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF1E1E1E.toInt())
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) }
                }
            }
            jf.landscapeImage(ep)?.let { url -> imageLoader.enqueue(ImageRequest.Builder(this).data(url).target(img).build()) }
            val label = TextView(this).apply {
                text = buildString {
                    append("E%02d".format(ep.indexNumber ?: 0))
                    if (isCurrent) append("  ·  ASSISTINDO") else if (Watched.isPlayed(ep)) append("  ·  ✓ visto")
                }
                setTextColor(0xFFFF7A00.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(8), 0, 0)
            }
            val name = TextView(this).apply {
                text = ep.name; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); maxLines = 2
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isFocusable = true
                isClickable = true
                background = StateListDrawable().apply {
                    val on = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0x33FFFFFF); setStroke(dp(3), 0xFFFF7A00.toInt()) }
                    val off = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (isCurrent) 0x22FF7A00 else Color.TRANSPARENT) }
                    addState(intArrayOf(android.R.attr.state_focused), on)
                    addState(intArrayOf(android.R.attr.state_pressed), on)
                    addState(intArrayOf(), off)
                }
                addView(img, LinearLayout.LayoutParams(w, w * 9 / 16))
                addView(label)
                addView(name, LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT))
                tag = if (isCurrent) "current" else null
                setOnClickListener { if (isCurrent) hideEpisodes() else playEpisode(ep) }
            }
            episodesRow.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        }
    }

    private val episodesOpen get() = ::episodesPanel.isInitialized && episodesPanel.visibility == View.VISIBLE

    private fun showEpisodes() {
        if (episodesRow.childCount == 0) return
        view.hideController()
        episodesPanel.visibility = View.VISIBLE
        val target = (0 until episodesRow.childCount).map { episodesRow.getChildAt(it) }.firstOrNull { it.tag == "current" }
            ?: episodesRow.getChildAt(0)
        target.post { target.requestFocus() }
    }

    private fun hideEpisodes(showControls: Boolean = false) {
        episodesPanel.visibility = View.GONE
        if (showControls) view.showController()
    }

    /** ←/→ com os controles escondidos: recua/avança 10 s (segurando, 30 s por vez). */
    private fun seekBy(forward: Boolean, held: Boolean) {
        val p = player ?: return
        val dur = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val step = if (held) 30_000L else 10_000L
        val base = if (seekTarget >= 0) seekTarget else p.currentPosition
        seekTarget = (base + if (forward) step else -step).coerceIn(0L, (dur - 1_000L).coerceAtLeast(0L))
        p.seekTo(seekTarget)
        seekOsd.text = (if (forward) "⏩  " else "⏪  ") + fmtTime(seekTarget) + (if (dur != Long.MAX_VALUE) "  /  " + fmtTime(dur) else "")
        seekOsd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd); handler.postDelayed(hideOsd, 1500)
        handler.removeCallbacks(seekCommit); handler.postDelayed(seekCommit, 900)
    }

    private fun fmtTime(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
    }

    /** Player entregue aos controles: ⏮/⏭ trocam de episódio da série. */
    @OptIn(UnstableApi::class)
    private inner class EpisodePlayer(p: Player) : ForwardingPlayer(p) {
        private fun extra(): List<Int> = buildList {
            if (prevEp != null) { add(Player.COMMAND_SEEK_TO_PREVIOUS); add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) }
            if (next != null) { add(Player.COMMAND_SEEK_TO_NEXT); add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
        }
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra().toIntArray()).build()
        override fun isCommandAvailable(command: Int): Boolean = command in extra() || super.isCommandAvailable(command)
        override fun hasNextMediaItem(): Boolean = next != null || super.hasNextMediaItem()
        override fun hasPreviousMediaItem(): Boolean = prevEp != null || super.hasPreviousMediaItem()
        override fun seekToNext() { next?.let { playEpisode(it) } ?: super.seekToNext() }
        override fun seekToNextMediaItem() { next?.let { playEpisode(it) } ?: super.seekToNextMediaItem() }
        override fun seekToPrevious() { prevEp?.let { playEpisode(it) } ?: super.seekToPrevious() }
        override fun seekToPreviousMediaItem() { prevEp?.let { playEpisode(it) } ?: super.seekToPreviousMediaItem() }
    }

    /** Botões do overlay visíveis agora, na ordem da navegação ←/→. */
    private fun overlayButtons(): List<View> = when {
        ::nextCard.isInitialized && nextCard.visibility == View.VISIBLE -> listOf(nextPlay, nextCancel)
        ::skipButton.isInitialized && skipButton.visibility == View.VISIBLE -> listOf(skipButton)
        else -> emptyList()
    }

    private fun highlight() {
        val buttons = overlayButtons()
        val selIndex = if (buttons.isEmpty()) -1 else overlaySel.coerceIn(0, buttons.lastIndex)
        listOf(skipButton, nextPlay, nextCancel).forEach { b ->
            val sel = buttons.indexOf(b).let { it >= 0 && it == selIndex }
            b.background = pillBg(sel, b.tag == true)
            b.setTextColor(if (sel) Color.BLACK else Color.WHITE)
        }
    }

    private fun skipLabel(type: String) = when (type) {
        "Intro" -> "Pular abertura"
        "Recap" -> "Pular resumo"
        "Preview" -> "Pular prévia"
        "Commercial" -> "Pular comercial"
        else -> "Pular créditos"
    }

    private fun updateOverlays() {
        val p = player ?: return
        if (isLive || !started) return
        val pos = p.currentPosition * TICKS_PER_MS
        val dur = if (p.duration > 0) p.duration * TICKS_PER_MS else current?.runTimeTicks ?: 0L
        val outro = segments.firstOrNull { it.type == "Outro" }
        val nextItem = next

        // Próximo episódio: no início dos créditos ou nos últimos 30 s
        val inNextZone = nextItem != null && !nextDismissed && dur > 0 &&
            (if (outro != null) pos >= outro.startTicks else dur - pos <= 30_000 * TICKS_PER_MS && dur > 5 * 60_000 * TICKS_PER_MS)
        if (inNextZone && nextItem != null) {
            if (nextCard.visibility != View.VISIBLE) {
                nextTitle.text = nextItem.episodeLabel
                nextDeadline = System.currentTimeMillis() + 10_000
                skipButton.visibility = View.GONE
                nextCard.visibility = View.VISIBLE
                overlaySel = 0
                highlight()
            }
            val left = ((nextDeadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            nextCountdown.text = if (p.isPlaying) "Começa em $left s" else "Pausado"
            if (!p.isPlaying) nextDeadline += 400       // pausado: o tempo não corre
            if (left <= 0 && p.isPlaying) playNext()
            return
        } else if (nextCard.visibility == View.VISIBLE) {
            hideNext()                                  // voltou para antes dos créditos
        }

        // Botão "Pular" enquanto estiver dentro de um trecho (para créditos, só se não houver próximo)
        val seg = segments.firstOrNull { s ->
            pos >= s.startTicks && pos < s.endTicks - 1_000 * TICKS_PER_MS &&
                (s.type != "Outro" || nextItem == null || nextDismissed) && s.type != "Unknown"
        }
        if (seg != null) {
            if (activeSkip != seg) {
                activeSkip = seg
                skipButton.text = skipLabel(seg.type)
                skipButton.visibility = View.VISIBLE
                overlaySel = 0
                highlight()
            }
        } else if (activeSkip != null) {
            activeSkip = null
            skipButton.visibility = View.GONE
        }
    }

    private fun hideNext() {
        nextCard.visibility = View.GONE
        nextDeadline = 0
    }

    private fun playNext() {
        val n = next ?: return
        playEpisode(n)
    }

    /** Abre outro episódio no lugar deste (o atual registra onde parou ao fechar). */
    private fun playEpisode(n: Item) {
        next = null
        val label = "${n.seriesName ?: current?.seriesName ?: ""} · ${n.episodeLabel}"
        val resume = if (Watched.isPlayed(n)) 0L else n.userData?.positionTicks ?: 0L
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, n.id)
                .putExtra(EXTRA_TITLE, label)
                .putExtra(EXTRA_START_TICKS, resume)
        )
        finish()
    }

    /* ---------------------------- teclas e ciclo de vida ---------------------------- */

    /**
     * Com o botão "Pular" ou o cartão do próximo episódio na tela (e os controles escondidos),
     * OK aciona o botão destacado e ←/→ troca entre "Assistir agora" e "Cancelar".
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        // Lista de episódios aberta: ↑ ou Voltar fecham (↑ volta aos controles)
        if (episodesOpen) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down && event.repeatCount == 0) hideEpisodes(showControls = code == KeyEvent.KEYCODE_DPAD_UP)
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Controles abertos e ↓ sem mais nada abaixo: abre a lista de episódios
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && view.isControllerFullyVisible && episodesRow.childCount > 0) {
            val focus = currentFocus
            val below = focus?.let { FocusFinder.getInstance().findNextFocus(window.decorView as ViewGroup, it, View.FOCUS_DOWN) }
            if (below == null || !isInside(below, view)) {
                if (down && event.repeatCount == 0) showEpisodes()
                return true
            }
        }
        val buttons = overlayButtons()
        if (buttons.isNotEmpty() && !view.isControllerFullyVisible) {
            val ok = event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
            val side = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            if (ok || (side && buttons.size > 1)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (ok) buttons[overlaySel.coerceIn(0, buttons.lastIndex)].performClick()
                    else { overlaySel = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else 0; highlight() }
                }
                return true
            }
        }
        // ←/→ com os controles escondidos: avança/recua o vídeo (filmes e episódios)
        if (!isLive && !view.isControllerFullyVisible && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (down) seekBy(forward = code == KeyEvent.KEYCODE_DPAD_RIGHT, held = event.repeatCount > 2)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isInside(v: View, parent: View): Boolean {
        var cur: View? = v
        while (cur != null) {
            if (cur === parent) return true
            cur = cur.parent as? View
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showMenu(); return true }
            KeyEvent.KEYCODE_CAPTIONS -> { showSubtitles(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                player?.let { it.playWhenReady = !it.playWhenReady }
                return true
            }
        }
        if (!view.isControllerFullyVisible && keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            view.showController()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        if (!isLive) player?.pause()
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) release()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        val p = player ?: return
        handler.removeCallbacks(progressTask)
        handler.removeCallbacks(segmentTask)
        handler.removeCallbacks(stallTask)
        handler.removeCallbacks(liveWatchdog)
        handler.removeCallbacks(seekCommit)
        handler.removeCallbacks(hideOsd)
        if (started && !isLive) report { id, ticks, _ -> jf.reportStopped(id, ticks, method, playSession) }
        liveStreamId?.let { live -> App.scope.launch { runCatching { jf.closeLiveStream(live) } } }
        p.removeListener(listener)
        p.release()
        player = null
    }
}
