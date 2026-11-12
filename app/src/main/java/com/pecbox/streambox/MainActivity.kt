package com.pecbox.streambox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import com.pecbox.streambox.ui.Idle
import com.pecbox.streambox.ui.ScreenSaverHost
import com.pecbox.streambox.ui.UpdateDialog
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.player.PlayerActivity
import com.pecbox.streambox.ui.AppShell
import com.pecbox.streambox.ui.screens.LoginScreen
import com.pecbox.streambox.ui.theme.StreamBoxTheme

class MainActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var swallowKeyUp = false
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StreamBoxTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (App.session.loggedIn) ScreenSaverHost { AppShell() } else LoginScreen()
                }
                UpdateDialog()
            }
        }
        // Mantém a tela ligada enquanto a proteção roda (no máximo 1 h; depois o sistema pode desligar a tela)
        lifecycleScope.launch {
            snapshotFlow { Idle.active }.collect { active ->
                handler.removeCallbacks(releaseScreen)
                if (active) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    handler.postDelayed(releaseScreen, 60 * 60 * 1000L)
                } else {
                    releaseScreen.run()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Idle.resumed = true
        Idle.touch()
    }

    override fun onPause() {
        Idle.resumed = false
        Idle.active = false
        super.onPause()
    }

    /** Qualquer tecla: fecha a proteção de tela (sem repassar a tecla) ou reinicia a contagem. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Idle.active) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Idle.active = false
                Idle.touch()
                swallowKeyUp = true
            }
            return true
        }
        if (swallowKeyUp && event.action == KeyEvent.ACTION_UP) {
            swallowKeyUp = false
            return true
        }
        Idle.touch()
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (Idle.active) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                Idle.active = false
                Idle.touch()
            }
            return true
        }
        Idle.touch()
        return super.dispatchTouchEvent(ev)
    }
}

/** Abre o player. fromStart = ignora a posição salva. */
fun Context.play(item: Item, fromStart: Boolean = false) {
    val title = if (item.isEpisode) "${item.seriesName ?: ""} · ${item.episodeLabel}" else item.name
    startActivity(
        Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ID, item.id)
            .putExtra(PlayerActivity.EXTRA_TITLE, title)
            .putExtra(PlayerActivity.EXTRA_START_TICKS, if (fromStart) 0L else item.userData?.positionTicks ?: 0L)
    )
}

/** Canal ao vivo do Jellyfin. */
fun Context.playJellyfinChannel(channel: Item) {
    startActivity(
        Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ID, channel.id)
            .putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
            .putExtra(PlayerActivity.EXTRA_JF_LIVE, true)
    )
}

/** Canal de lista M3U externa. */
fun Context.playExternalChannel(name: String, url: String, userAgent: String? = null, referer: String? = null) {
    startActivity(
        Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URL, url)
            .putExtra(PlayerActivity.EXTRA_TITLE, name)
            .putExtra(PlayerActivity.EXTRA_UA, userAgent)
            .putExtra(PlayerActivity.EXTRA_REFERER, referer)
    )
}
