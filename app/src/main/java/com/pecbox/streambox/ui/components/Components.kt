package com.pecbox.streambox.ui.components

import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.Surface3
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted

/**
 * Destaque de foco para controle remoto: borda laranja + leve zoom.
 * Deve vir ANTES do clickable na cadeia de modificadores.
 */
fun Modifier.tvFocus(shape: Shape = RoundedCornerShape(12.dp), zoom: Float = 1.06f): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) zoom else 1f, label = "focusScale")
    this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (focused) Modifier.border(3.dp, Orange, shape) else Modifier)
}

/** Botão compatível com toque e controle remoto. */
@Composable
fun SbButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(10.dp)
    val bg = when {
        !enabled -> Surface3
        primary -> Orange
        else -> Surface2
    }
    val fg = if (primary && enabled) Color.Black else TextMain
    Row(
        modifier = modifier
            .tvFocus(shape, 1.04f)
            .clip(shape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Pílula de seleção (temporadas, ordenação). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .tvFocus(shape, 1.05f)
            .clip(shape)
            .background(if (selected) Orange else Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) Color.Black else TextMain, style = MaterialTheme.typography.labelLarge)
    }
}

/** Imagem com fundo e iniciais enquanto carrega (ou se não houver capa). */
@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.verticalGradient(listOf(Surface3, Surface2))), contentAlignment = Alignment.Center) {
        Text(
            initials(title), color = Orange.copy(alpha = 0.75f),
            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun initials(t: String): String =
    t.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** Cartão de pôster vertical (2:3) ou horizontal (16:9). */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = if (LocalIsTv.current) 150.dp else 112.dp,
    landscape: Boolean = false,
    progress: Float = 0f,
    badge: String? = null,
    played: Boolean = false,
    onFocus: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .then(if (onFocus != null) Modifier.onFocusChanged { if (it.isFocused) onFocus() } else Modifier)
                .tvFocus(shape)
                .clip(shape)
                .clickable(onClick = onClick)
        ) {
            Artwork(image, title, Modifier.fillMaxSize())
            if (badge != null) {
                Box(
                    Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Orange)
                        .padding(horizontal = 6.dp, vertical = 2.dp).align(Alignment.TopStart)
                ) { Text(badge, color = Color.Black, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
            }
            if (played) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Orange),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Check, contentDescription = "Assistido", tint = Color.Black, modifier = Modifier.size(16.dp)) }
            }
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Orange))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fileira horizontal com título. */
@Composable
fun <T> MediaRow(title: String, list: List<T>, card: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    val tv = LocalIsTv.current
    Column(Modifier.padding(top = 18.dp)) {
        Text(
            title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = if (tv) 40.dp else 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = if (tv) 40.dp else 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 12.dp),
        ) {
            items(list) { card(it) }
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Orange) }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = TextMuted, textAlign = TextAlign.Center)
        if (onRetry != null) {
            Spacer(Modifier.height(16.dp))
            SbButton("Tentar de novo", onRetry)
        }
    }
}
