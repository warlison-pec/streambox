package com.pecbox.streambox.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pecbox.streambox.App
import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.Notice
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Success
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer

/** Histórico de avisos e fila dos que ainda vão aparecer na tela. */
object Notices {
    private const val MAX_HISTORY = 60
    private val serializer = ListSerializer(Notice.serializer())
    private val main = Handler(Looper.getMainLooper())
    private val episodeCode = Regex("\\bS\\d{2}E\\d{2}\\b")

    val all = mutableStateListOf<Notice>()      // mais recente primeiro
    val pending = mutableStateListOf<Notice>()  // aguardando o banner

    /** Com o player aberto, as mensagens do painel aparecem nele; o resto espera o usuário voltar. */
    var playerSink: ((Notice) -> Unit)? = null

    val unread: Int get() = all.count { !it.read }

    fun load() {
        val saved = App.session.noticesJson.ifBlank { return }
        all.addAll(runCatching { AppJson.decodeFromString(serializer, saved) }.getOrDefault(emptyList()).map(::fixOld))
    }

    /** Versões antigas anunciavam episódio como "Novo filme": corrige o histórico já salvo. */
    private fun fixOld(n: Notice): Notice =
        if (n.kind == Notice.NEW && n.title == "Novo filme" && episodeCode.containsMatchIn(n.text)) n.copy(title = "Novo episódio")
        else if (n.kind == Notice.NEW && n.title == "Novo no catálogo") n.copy(title = if (episodeCode.containsMatchIn(n.text)) "Novo episódio" else "Novos episódios")
        else n

    private fun save() {
        App.session.noticesJson = AppJson.encodeToString(serializer, all.toList())
    }

    fun post(notice: Notice) = main.post {
        all.add(0, notice)
        while (all.size > MAX_HISTORY) all.removeAt(all.lastIndex)
        save()
        val sink = playerSink
        when {
            notice.kind == Notice.MESSAGE && sink != null -> sink(notice)
            // Mensagens do administrador sempre aparecem; o resto respeita a escolha em Ajustes
            App.session.noticeBanner || notice.kind == Notice.MESSAGE -> pending.add(notice)
        }
    }

    fun markRead(id: String) {
        val i = all.indexOfFirst { it.id == id }
        if (i < 0 || all[i].read) return
        all[i] = all[i].copy(read = true)
        save()
    }

    fun markAllRead() {
        if (unread == 0) return
        all.indices.forEach { all[it] = all[it].copy(read = true) }
        save()
    }

    fun remove(id: String) {
        all.removeAll { it.id == id }
        pending.removeAll { it.id == id }
        save()
    }

    fun clear() {
        all.clear()
        pending.clear()
        save()
    }
}

/* ---------------- aparência de cada tipo ---------------- */

val NoticeBlue = Color(0xFF4DA3FF)
val NoticePurple = Color(0xFFB57BFF)

fun noticeIcon(n: Notice): ImageVector = when (n.kind) {
    Notice.MESSAGE -> Icons.Filled.Campaign
    Notice.UPDATE -> Icons.Filled.SystemUpdate
    Notice.REQUESTED -> Icons.Filled.CheckCircle
    else -> if (n.title.contains("filme", ignoreCase = true)) Icons.Filled.Movie else Icons.Filled.Tv
}

fun noticeColor(kind: String): Color = when (kind) {
    Notice.MESSAGE -> NoticeBlue
    Notice.UPDATE -> NoticePurple
    Notice.REQUESTED -> Success
    else -> Orange
}

fun noticeKindLabel(kind: String): String = when (kind) {
    Notice.MESSAGE -> "MENSAGEM"
    Notice.UPDATE -> "ATUALIZAÇÃO"
    Notice.REQUESTED -> "PEDIDO"
    else -> "NOVIDADE"
}

/** Capa do aviso (pôster do título) ou ícone colorido do tipo. */
@Composable
fun NoticeThumb(n: Notice, width: Dp) {
    val color = noticeColor(n.kind)
    val shape = RoundedCornerShape(8.dp)
    if (n.image != null) {
        Box(Modifier.width(width).height(width * 1.5f).clip(shape).background(Surface2)) {
            AsyncImage(n.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    } else {
        Box(
            Modifier.size(width).clip(shape).background(color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon(noticeIcon(n), contentDescription = null, tint = color, modifier = Modifier.size(width * 0.5f)) }
    }
}

/**
 * Cartão no canto da tela. Não recebe foco: nunca atrapalha o controle remoto.
 * Uma barrinha mostra quanto tempo falta para sumir.
 */
@Composable
fun NoticeBanner(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val current by remember { derivedStateOf { Notices.pending.firstOrNull() } }
    var last by remember { mutableStateOf<Notice?>(null) }
    var visible by remember { mutableStateOf(false) }
    val progress = remember { Animatable(1f) }
    val tv = LocalIsTv.current

    LaunchedEffect(current, Idle.resumed, Idle.active) {
        val notice = current ?: return@LaunchedEffect
        if (!Idle.resumed || Idle.active) { visible = false; return@LaunchedEffect }
        last = notice
        progress.snapTo(1f)
        visible = true
        progress.animateTo(0f, tween(8_000, easing = LinearEasing))
        visible = false
        delay(400)
        Notices.pending.remove(notice)
    }

    AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
    ) {
        val n = last ?: return@AnimatedVisibility
        val color = noticeColor(n.kind)
        val shape = RoundedCornerShape(14.dp)
        Column(
            Modifier.widthIn(max = if (tv) 420.dp else 360.dp).clip(shape)
                .background(Color(0xF2151515))
                .border(1.dp, color.copy(alpha = 0.35f), shape)
                .pointerInput(Unit) { detectTapGestures { onOpen() } }
        ) {
            Row(
                Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NoticeThumb(n, if (n.image != null) 44.dp else 40.dp)
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(noticeKindLabel(n.kind), color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(n.title, color = TextMain, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (n.text.isNotBlank()) Text(n.text, color = TextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (tv) Text("Veja em Avisos, no menu à esquerda", color = TextMuted.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.06f))) {
                Box(Modifier.fillMaxWidth(progress.value).height(3.dp).background(color))
            }
        }
    }
}
