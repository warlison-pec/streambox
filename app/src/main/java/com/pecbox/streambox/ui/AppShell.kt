package com.pecbox.streambox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.pecbox.streambox.data.MyRequests
import com.pecbox.streambox.ui.screens.RequestsScreen
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.R
import com.pecbox.streambox.ui.screens.DetailsScreen
import com.pecbox.streambox.ui.screens.HomeScreen
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.ui.screens.LibrariesScreen
import com.pecbox.streambox.ui.screens.LibraryScreen
import com.pecbox.streambox.ui.screens.LiveTvScreen
import com.pecbox.streambox.ui.screens.NoticesScreen
import com.pecbox.streambox.ui.screens.SearchScreen
import com.pecbox.streambox.ui.screens.SettingsScreen
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.TextMain
import com.pecbox.streambox.ui.theme.TextMuted

sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data class Library(val id: String, val name: String, val collectionType: String?) : Route { override val key = "lib:$id" }
    data object Libraries : Route { override val key = "libraries" }
    data class Details(val id: String) : Route { override val key = "details:$id" }
    data object Search : Route { override val key = "search" }
    data object LiveTv : Route { override val key = "livetv" }
    data object Settings : Route { override val key = "settings" }
    data object Notices : Route { override val key = "notices" }
    data object Requests : Route { override val key = "requests" }
}

/** Pilha de navegação simples (sem biblioteca extra). */
class Nav(private val stack: MutableList<Route>) {
    fun push(route: Route) { stack.add(route) }
    fun root(route: Route) { stack.clear(); stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

private data class TopItem(val label: String, val icon: ImageVector, val route: Route, val badge: Int = 0)

/** Ícone com um pontinho laranja quando há aviso não lido. */
@Composable
private fun BadgedIcon(icon: ImageVector, label: String, badge: Int, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Box {
        Icon(icon, contentDescription = label, tint = tint, modifier = modifier)
        if (badge > 0) Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(RoundedCornerShape(4.dp)).background(Orange))
    }
}

@Composable
fun AppShell() {
    val jf = App.session.jellyfin
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember { Nav(stack) }
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = stack.size > 1) { nav.back() }
    LaunchedEffect(App.session.userId) {
        MyRequests.ensureLoaded()
        // Pedidos feitos pelo Seerr em outro aparelho também liberam a aba "Pedidos"
        if (App.session.seerr.enabled) runCatching { MyRequests.views() }
    }

    val views = rememberLoader("views") { jf.views() }
    // Todas as bibliotecas que o app sabe exibir (filmes, séries, coleções, vídeos, mistas)
    val libs = views.data.orEmpty().filter { libraryItemTypes(it.collectionType) != null }
    val tv = LocalIsTv.current
    val unread = Notices.unread

    val top = buildList {
        add(TopItem("Início", Icons.Filled.Home, Route.Home))
        if (tv) {
            libs.forEach { add(TopItem(it.name, libraryIcon(it), it.toRoute())) }
        } else {
            add(TopItem("Bibliotecas", Icons.Filled.VideoLibrary, Route.Libraries))
        }
        add(TopItem(if (tv) "TV ao vivo" else "Ao vivo", Icons.Filled.LiveTv, Route.LiveTv))
        add(TopItem("Buscar", Icons.Filled.Search, Route.Search))
        // Só aparece para quem já pediu algo
        if (MyRequests.hasAny) add(TopItem("Pedidos", Icons.Filled.Inbox, Route.Requests))
        if (tv) add(TopItem("Avisos", Icons.Filled.Notifications, Route.Notices, badge = unread))
        add(TopItem("Ajustes", Icons.Filled.Settings, Route.Settings, badge = if (tv) 0 else unread))
    }
    val current = stack.last()
    val rootKey = stack.first().key

    val content: @Composable () -> Unit = {
        holder.SaveableStateProvider(current.key) {
            when (current) {
                Route.Home -> HomeScreen(nav)
                is Route.Library -> LibraryScreen(current.id, current.name, current.collectionType, nav)
                Route.Libraries -> LibrariesScreen(libs, nav)
                is Route.Details -> DetailsScreen(current.id, nav)
                Route.Search -> SearchScreen(nav)
                Route.LiveTv -> LiveTvScreen(nav)
                Route.Settings -> SettingsScreen(onNotices = { nav.push(Route.Notices) })
                Route.Notices -> NoticesScreen(nav)
                Route.Requests -> RequestsScreen(nav)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (tv) {
            TvDrawerShell(top, rootKey, showUnread = current == Route.Home, onSelect = { nav.root(it) }, content = content)
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = Surface) {
                        top.forEach { t ->
                            NavigationBarItem(
                                selected = t.route.key == rootKey,
                                onClick = { nav.root(t.route) },
                                icon = { BadgedIcon(t.icon, t.label, t.badge) },
                                label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                alwaysShowLabel = top.size <= 5,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black,
                                    indicatorColor = Orange,
                                    selectedTextColor = Orange,
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted,
                                )
                            )
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { content() }
            }
        }
        NoticeBanner(Modifier.align(Alignment.TopEnd).padding(16.dp)) { nav.push(Route.Notices) }
    }
}

/**
 * TV: a tela usa a largura toda e o menu fica escondido. Ele só abre quando a pessoa aperta ←
 * e não há mais nada à esquerda (início de uma fileira, botões do destaque etc.).
 * → ou Voltar fecham o menu e devolvem o foco para onde estava.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvDrawerShell(
    items: List<TopItem>, selectedKey: String, showUnread: Boolean,
    onSelect: (Route) -> Unit, content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val contentFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }

    fun close() {
        open = false
        // devolve o foco ao último item da tela (focusRestorer); se não der, entra no primeiro item
        runCatching { contentFocus.requestFocus() }
        scope.launch {
            withFrameNanos { }
            if (!contentHasFocus) focusManager.moveFocus(FocusDirection.Enter)
        }
    }

    BackHandler(enabled = open) { close() }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        withFrameNanos { }   // espera o menu entrar na tela
        if (runCatching { selectedFocus.requestFocus() }.isFailure) runCatching { firstFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusRequester(contentFocus)
                .focusRestorer()
                .focusGroup()
                // Depois que a tela tratou a tecla: ← sem destino abre o menu
                .onKeyEvent { e ->
                    if (!open && e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) {
                        if (!focusManager.moveFocus(FocusDirection.Left)) open = true
                        true
                    } else false
                }
        ) { content() }

        // Dica discreta na borda: o menu está ali
        if (!open) Box(
            Modifier.align(Alignment.CenterStart).padding(start = 3.dp).width(4.dp).height(56.dp)
                .clip(RoundedCornerShape(2.dp)).background(Orange.copy(alpha = 0.45f))
        )
        if (!open && showUnread && Notices.unread > 0) UnreadPill(Notices.unread, Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 14.dp))

        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.85f), 0.45f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.25f))))
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(tween(220)) { -it } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(200)) { -it } + fadeOut(tween(200)),
        ) {
            DrawerPanel(
                items, selectedKey, selectedFocus, firstFocus, focusable = open,
                onSelect = { r -> onSelect(r); close() },
                onClose = { close() },
                onFocusLost = { if (open) open = false },
            )
        }
    }
}

@Composable
private fun UnreadPill(count: Int, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Notifications, contentDescription = null, tint = Orange, modifier = Modifier.size(16.dp))
        Text(if (count > 9) "9+" else count.toString(), color = TextMain, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun DrawerPanel(
    items: List<TopItem>, selectedKey: String,
    selectedFocus: FocusRequester, firstFocus: FocusRequester, focusable: Boolean,
    onSelect: (Route) -> Unit, onClose: () -> Unit, onFocusLost: () -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxHeight().width(272.dp)
            .background(Brush.horizontalGradient(listOf(Surface, Surface.copy(alpha = 0.96f))))
            .onFocusChanged {
                if (it.hasFocus) hadFocus = true
                else if (hadFocus) onFocusLost()
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { onClose(); true }
                    Key.DirectionLeft -> true
                    else -> false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
            // A arte do ícone tem margem transparente: por isso a imagem é maior que o logo visível
            Image(painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(64.dp))
            Column {
                Text("StreamBox", style = MaterialTheme.typography.titleLarge)
                if (App.session.userName.isNotBlank()) Text(App.session.userName, color = TextMuted, style = MaterialTheme.typography.labelMedium)
            }
        }
        items.forEachIndexed { i, t ->
            val selected = t.route.key == selectedKey
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (selected) Modifier.focusRequester(selectedFocus) else Modifier)
                    .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    // enquanto o menu some, nenhum item dele pode voltar a receber foco
                    .focusProperties { canFocus = focusable }
                    .onFocusChanged { focused = it.isFocused }
                    .clip(shape)
                    .background(
                        when {
                            focused -> Orange
                            selected -> Orange.copy(alpha = 0.14f)
                            else -> Color.Transparent
                        }
                    )
                    .clickable { onSelect(t.route) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val fg = when { focused -> Color.Black; selected -> Orange; else -> TextMain }
                BadgedIcon(t.icon, t.label, t.badge, tint = fg, modifier = Modifier.size(24.dp))
                Text(
                    t.label, color = fg, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (t.badge > 0) Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (focused) Color.Black else Orange)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) { Text(if (t.badge > 9) "9+" else t.badge.toString(), color = if (focused) Orange else Color.Black, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

fun Item.toRoute() = Route.Library(id, name, collectionType)

fun libraryIcon(view: Item): ImageVector = when (view.collectionType) {
    "movies" -> Icons.Filled.Movie
    "tvshows" -> Icons.Filled.Tv
    "boxsets" -> Icons.Filled.Collections
    else -> Icons.Filled.Folder
}
