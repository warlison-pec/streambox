package com.pecbox.streambox.ui.screens

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.AppUpdate
import com.pecbox.streambox.data.Notice
import com.pecbox.streambox.ui.Nav
import com.pecbox.streambox.ui.Notices
import com.pecbox.streambox.ui.Route
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.noticeIcon
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted

/** Histórico de mensagens, novidades e atualizações. */
@Composable
fun NoticesScreen(nav: Nav) {
    val ctx = LocalContext.current
    LaunchedEffect(Notices.unread) { Notices.markAllRead() }

    fun open(n: Notice) {
        n.itemId?.let { nav.push(Route.Details(it)); return }
        n.url?.let { url ->
            if (AppUpdate.isUpdateUrl(url)) AppUpdate.start(ctx, url)
            else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Avisos", style = MaterialTheme.typography.headlineMedium)
                if (Notices.all.isNotEmpty()) SbButton("Limpar", onClick = { Notices.clear() }, primary = false)
            }
        }
        if (Notices.all.isEmpty()) item { Text("Nenhum aviso por enquanto.", color = TextMuted) }
        items(Notices.all, key = { it.id }) { n ->
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.fillMaxWidth().tvFocus(shape, 1.01f).clip(shape).background(Surface).clickable { open(n) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(noticeIcon(n.kind), contentDescription = null, tint = Orange, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.title, color = TextMain, style = MaterialTheme.typography.titleSmall)
                    val text = AppUpdate.status?.takeIf { AppUpdate.isUpdateUrl(n.url) } ?: n.text
                    if (text.isNotBlank()) Text(text, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                Text(DateUtils.getRelativeTimeSpanString(n.time).toString(), color = TextMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
