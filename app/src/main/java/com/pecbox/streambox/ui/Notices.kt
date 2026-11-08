package com.pecbox.streambox.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.SystemUpdate
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.data.AppJson
import com.pecbox.streambox.data.Notice
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer

/** Histórico de avisos e fila dos que ainda vão aparecer na tela. */
object Notices {
    private const val MAX_HISTORY = 40
    private val serializer = ListSerializer(Notice.serializer())
    private val main = Handler(Looper.getMainLooper())

    val all = mutableStateListOf<Notice>()      // mais recente primeiro
    val pending = mutableStateListOf<Notice>()  // aguardando o banner

    /** Com o player aberto, as mensagens do painel aparecem nele; o resto espera o usuário voltar. */
    var playerSink: ((Notice) -> Unit)? = null

    val unread: Int get() = all.count { !it.read }

    fun load() {
        val saved = App.session.noticesJson.ifBlank { return }
        all.addAll(runCatching { AppJson.decodeFromString(serializer, saved) }.getOrDefault(emptyList()))
    }

    private fun save() {
        App.session.noticesJson = AppJson.encodeToString(serializer, all.toList())
    }

    fun post(notice: Notice) = main.post {
        all.add(0, notice)
        while (all.size > MAX_HISTORY) all.removeAt(all.lastIndex)
        save()
        val sink = playerSink
        if (notice.kind == Notice.MESSAGE && sink != null) sink(notice) else pending.add(notice)
    }

    fun markAllRead() {
        if (unread == 0) return
        all.indices.forEach { all[it] = all[it].copy(read = true) }
        save()
    }

    fun clear() {
        all.clear()
        pending.clear()
        save()
    }
}

fun noticeIcon(kind: String): ImageVector = when (kind) {
    Notice.MESSAGE -> Icons.Filled.Campaign
    Notice.UPDATE -> Icons.Filled.SystemUpdate
    Notice.REQUESTED -> Icons.Filled.CheckCircle
    else -> Icons.Filled.NewReleases
}

/** Faixa pequena no canto da tela. Não recebe foco: nunca atrapalha o controle remoto. */
@Composable
fun NoticeBanner(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val current by remember { derivedStateOf { Notices.pending.firstOrNull() } }
    var last by remember { mutableStateOf<Notice?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(current, Idle.resumed, Idle.active) {
        val notice = current ?: return@LaunchedEffect
        if (!Idle.resumed || Idle.active) { visible = false; return@LaunchedEffect }
        last = notice
        visible = true
        delay(7_000)
        visible = false
        delay(400)
        Notices.pending.remove(notice)
    }

    AnimatedVisibility(
        visible = visible, modifier = modifier,
        enter = fadeIn() + slideInHorizontally { it / 3 },
        exit = fadeOut() + slideOutHorizontally { it / 3 },
    ) {
        val n = last ?: return@AnimatedVisibility
        Row(
            Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xE6161616))
                .pointerInput(Unit) { detectTapGestures { onOpen() } }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(noticeIcon(n.kind), contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp))
            Column {
                Text(n.title, color = TextMain, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.text.isNotBlank()) Text(n.text, color = TextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
