package com.pecbox.streambox.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Destaque do app (borda laranja + leve zoom), ativado pelo mouse (hover) ou pelo teclado (foco).
 * É o mesmo efeito do foco do controle remoto na TV.
 */
@Composable
fun Modifier.highlight(
    interaction: MutableInteractionSource,
    shape: Shape = RoundedCornerShape(12.dp),
    zoom: Float = 1.05f,
): Modifier {
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val on = hovered || focused
    val scale by animateFloatAsState(if (on) zoom else 1f, label = "highlight")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (on) Modifier.border(3.dp, Orange, shape) else Modifier)
        .pointerHoverIcon(PointerIcon.Hand)
}

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
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        !enabled -> Surface3
        primary -> Orange
        else -> Surface2
    }
    val fg = if (primary && enabled) Color.Black else TextMain
    Row(
        modifier = modifier
            .highlight(interaction, shape, 1.03f)
            .clip(shape)
            .background(bg)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
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

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .highlight(interaction, shape, 1.04f)
            .clip(shape)
            .background(if (selected) Orange else Surface2)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) Color.Black else TextMain, style = MaterialTheme.typography.labelLarge)
    }
}

private val RatingGold = Color(0xFFFFC94D)

@Composable
fun Artwork(url: String?, title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(Brush.verticalGradient(listOf(Surface3, Surface2))), contentAlignment = Alignment.Center) {
        Text(
            initials(title), color = Orange.copy(alpha = 0.75f),
            style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

private fun initials(t: String): String =
    t.split(' ', '-', ':').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()

/** Cartão de pôster vertical (2:3) ou horizontal (16:9), igual ao do app Android. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 160.dp,
    landscape: Boolean = false,
    progress: Float = 0f,
    badge: String? = null,
    played: Boolean = false,
    rating: Double? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    Column(modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .highlight(interaction, shape)
                .clip(shape)
                .hoverable(interaction)
                .clickable(interaction, indication = null, onClick = onClick)
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
            if (rating != null && rating > 0) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = if (progress > 0f) 10.dp else 6.dp)
                        .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.72f))
                        .border(1.dp, RatingGold.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("★", color = RatingGold, style = MaterialTheme.typography.labelMedium)
                    Text(
                        String.format(java.util.Locale.US, " %.1f", rating), color = Color.White,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                    Box(Modifier.fillMaxWidth(progress).height(4.dp).background(Orange))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fileira horizontal com título e setas para rolar com o mouse. */
@Composable
fun <T> MediaRow(title: String, list: List<T>, modifier: Modifier = Modifier, card: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier.padding(top = 22.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            ArrowButton(Icons.Filled.ChevronLeft) {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex - 5).coerceAtLeast(0)) }
            }
            Spacer(Modifier.width(6.dp))
            ArrowButton(Icons.Filled.ChevronRight) {
                scope.launch { state.animateScrollToItem((state.firstVisibleItemIndex + 5).coerceAtMost(list.lastIndex)) }
            }
        }
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = 40.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(list) { card(it) }
        }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(30.dp).highlight(interaction, CircleShape, 1.08f).clip(CircleShape).background(Surface2)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = TextMain, modifier = Modifier.size(20.dp)) }
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
