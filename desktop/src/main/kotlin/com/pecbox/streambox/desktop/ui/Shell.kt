package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.desktop.BuildConfig
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.player.PlayerController
import com.pecbox.streambox.desktop.player.PlayerState

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Settings : Screen
    data class Library(val view: Item) : Screen
    data class Details(val itemId: String) : Screen
}

/** Navegação pelo menu lateral (igual ao modo TV do app Android). */
class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()
    val root get() = stack.first()
    fun go(root: Screen) { stack.clear(); stack.add(root) }
    fun push(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
}

@Composable
fun Shell(s: Session, player: PlayerController, nav: Nav) {
    var views by remember { mutableStateOf<List<Item>>(emptyList()) }
    // Sobe quando a janela do player fecha: atualiza progresso e "Continuar assistindo"
    var refreshKey by remember { mutableIntStateOf(0) }
    // Termo da busca fica aqui: abrir um título e voltar não apaga a pesquisa
    var searchQuery by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        player.onFinished = { refreshKey++ }
        views = runCatching { s.jellyfin.views() }.getOrDefault(emptyList()).filter { libraryItemTypes(it.collectionType) != null }
    }
    val open: (Item) -> Unit = { nav.push(Screen.Details(if (it.isEpisode) it.seriesId ?: it.id else it.id)) }
    val play: (Item, Boolean) -> Unit = { item, fromStart -> player.play(item, fromStart) }

    Row(Modifier.fillMaxSize().background(Black)) {
        Column(
            Modifier.width(230.dp).fillMaxHeight().background(Surface).padding(vertical = 22.dp, horizontal = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.padding(start = 8.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Orange), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("StreamBox", style = MaterialTheme.typography.titleLarge)
                    if (BuildConfig.EDICAO == "comunidade") Text("Comunidade", color = Orange, style = MaterialTheme.typography.labelMedium)
                }
            }
            RailItem(Icons.Filled.Home, "Início", nav.root == Screen.Home) { nav.go(Screen.Home) }
            RailItem(Icons.Filled.Search, "Buscar", nav.root == Screen.Search) { nav.go(Screen.Search) }
            if (views.isNotEmpty()) {
                Text("Bibliotecas", color = TextMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp))
            }
            views.forEach { v ->
                val icon = when (v.collectionType) {
                    "movies" -> Icons.Filled.Movie
                    "tvshows" -> Icons.Filled.Tv
                    else -> Icons.Filled.VideoLibrary
                }
                RailItem(icon, v.name, (nav.root as? Screen.Library)?.view?.id == v.id) { nav.go(Screen.Library(v)) }
            }
            Spacer(Modifier.weight(1f).height(24.dp))
            Text(s.userName, color = TextMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 4.dp))
            RailItem(Icons.Filled.Settings, "Ajustes", nav.root == Screen.Settings) { nav.go(Screen.Settings) }
            RailItem(Icons.AutoMirrored.Filled.Logout, "Sair", false) { player.stop(); s.logout() }
            Text("v${BuildConfig.VERSION_NAME}", color = Surface3, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        }

        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (val sc = nav.current) {
                Screen.Home -> HomeScreen(s, refreshKey, open, play)
                Screen.Search -> SearchScreen(s, searchQuery, { searchQuery = it }, open)
                Screen.Settings -> SettingsScreen(s, logout = { player.stop(); s.logout() })
                is Screen.Library -> LibraryScreen(s, sc.view, open)
                is Screen.Details -> DetailsScreen(s, sc.itemId, refreshKey, back = { nav.back() }, play = play)
            }
            PlayerBar(player, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Orange else if (hovered) Surface2 else Color.Transparent)
            .hoverable(interaction).clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (selected) Color.Black else TextMain
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Barra inferior com o estado do player (o vídeo abre em uma janela própria). */
@Composable
private fun PlayerBar(player: PlayerController, modifier: Modifier) {
    val st = player.state
    if (st == PlayerState.Idle) return
    Row(
        modifier.padding(20.dp).widthIn(max = 760.dp).clip(RoundedCornerShape(14.dp)).background(Surface2)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (st) {
            is PlayerState.Opening -> {
                CircularProgressIndicator(color = Orange, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Text("Abrindo ${st.title}…", modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is PlayerState.Playing -> {
                Icon(Icons.Filled.PlayArrow, null, tint = Orange)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(st.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text("Arquivo original, sem conversão · Espaço pausa, F tela cheia, Q fecha", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                SbButton("Parar", onClick = { player.stop() }, icon = Icons.Filled.Stop, primary = false)
            }
            is PlayerState.Failed -> {
                Text(st.message, color = Danger, modifier = Modifier.weight(1f, fill = false))
                SbButton("Fechar", onClick = { player.dismissError() }, icon = Icons.Filled.Close, primary = false)
            }
            PlayerState.Idle -> {}
        }
    }
}
