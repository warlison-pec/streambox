package com.pecbox.streambox.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.pecbox.streambox.App
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Estado global de inatividade (atualizado pelo MainActivity a cada tecla/toque). */
object Idle {
    var lastInteraction by mutableLongStateOf(System.currentTimeMillis())
    var active by mutableStateOf(false)
    var resumed by mutableStateOf(true)

    fun touch() { lastInteraction = System.currentTimeMillis() }
}

/** Envolve o app: liga a proteção de tela após o tempo configurado sem interação. */
@Composable
fun ScreenSaverHost(content: @Composable () -> Unit) {
    val s = App.session
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            val minutes = s.screensaverMinutes
            if (minutes > 0 && Idle.resumed && !Idle.active &&
                System.currentTimeMillis() - Idle.lastInteraction >= minutes * 60_000L
            ) {
                Idle.active = true
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        content()
        if (Idle.active) ScreenSaver()
    }
}

private const val SLIDE_MS = 9_000L

@Composable
private fun ScreenSaver() {
    val jf = App.session.jellyfin
    val ctx = LocalContext.current
    val tv = LocalIsTv.current
    // Nova seleção aleatória a cada vez que a proteção liga
    val items by produceState(initialValue = emptyList<Item>()) {
        value = runCatching { jf.featured("Movie,Series", 40) }.getOrDefault(emptyList())
            .filter { jf.backdropImage(it) != null }
    }
    var index by remember { mutableIntStateOf(0) }
    var clock by remember { mutableStateOf(now()) }

    LaunchedEffect(items) {
        if (items.isEmpty()) return@LaunchedEffect
        while (true) {
            // Pré-carrega a próxima imagem para a troca ser suave
            val next = items[(index + 1) % items.size]
            jf.backdropImage(next, 1920)?.let { ctx.imageLoader.enqueue(ImageRequest.Builder(ctx).data(it).build()) }
            delay(SLIDE_MS)
            index = (index + 1) % items.size
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = now()
            delay(20_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isNotEmpty()) {
            Crossfade(targetState = index, animationSpec = tween(1600), label = "slides") { i ->
                KenBurns(items[i % items.size], i)
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.85f)))))

        Text(
            clock, color = Color.White, fontSize = if (tv) 44.sp else 32.sp, fontWeight = FontWeight.Light,
            modifier = Modifier.align(Alignment.TopEnd).padding(if (tv) 48.dp else 24.dp)
        )

        val item = items.getOrNull(index % maxOf(items.size, 1))
        Column(
            Modifier.align(Alignment.BottomStart).padding(if (tv) 56.dp else 24.dp).widthIn(max = 760.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("STREAMBOX", color = Orange, style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp)
            if (item != null) {
                Text(item.name, color = Color.White, fontSize = if (tv) 46.sp else 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(if (item.isSeries) "Série" else "Filme", item.year?.toString(), item.genres.take(2).joinToString(", ").ifBlank { null })
                Text(meta.joinToString("  ·  "), color = TextMuted, fontSize = if (tv) 18.sp else 14.sp)
            }
            Text("Pressione qualquer tecla para voltar", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/** Imagem com zoom e deslocamento lentos (efeito "Ken Burns"). */
@Composable
private fun KenBurns(item: Item, i: Int) {
    val jf = App.session.jellyfin
    val progress = remember(item.id, i) { Animatable(0f) }
    LaunchedEffect(item.id, i) { progress.animateTo(1f, tween((SLIDE_MS + 2_000).toInt(), easing = LinearEasing)) }
    val dir = if (i % 2 == 0) 1f else -1f
    AsyncImage(
        model = jf.backdropImage(item, 1920),
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val p = progress.value
            val scale = 1.04f + 0.12f * p
            scaleX = scale
            scaleY = scale
            translationX = dir * 40f * (p - 0.5f)
            translationY = -20f * (p - 0.5f)
        }
    )
}

private fun now(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
