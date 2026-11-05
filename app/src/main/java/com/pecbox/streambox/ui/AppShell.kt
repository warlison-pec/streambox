package com.pecbox.streambox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.ui.screens.DetailsScreen
import com.pecbox.streambox.ui.screens.HomeScreen
import com.pecbox.streambox.data.Item
import com.pecbox.streambox.data.libraryItemTypes
import com.pecbox.streambox.ui.screens.LibrariesScreen
import com.pecbox.streambox.ui.screens.LibraryScreen
import com.pecbox.streambox.ui.screens.LiveTvScreen
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
}

/** Pilha de navegação simples (sem biblioteca extra). */
class Nav(private val stack: MutableList<Route>) {
    fun push(route: Route) { stack.add(route) }
    fun root(route: Route) { stack.clear(); stack.add(route) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

private data class TopItem(val label: String, val icon: ImageVector, val route: Route)

@Composable
fun AppShell() {
    val jf = App.session.jellyfin
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav = remember { Nav(stack) }
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = stack.size > 1) { nav.back() }

    val views = rememberLoader("views") { jf.views() }
    // Todas as bibliotecas que o app sabe exibir (filmes, séries, coleções, vídeos, mistas)
    val libs = views.data.orEmpty().filter { libraryItemTypes(it.collectionType) != null }
    val tv = LocalIsTv.current

    val top = buildList {
        add(TopItem("Início", Icons.Filled.Home, Route.Home))
        if (tv) {
            libs.forEach { add(TopItem(it.name, libraryIcon(it), it.toRoute())) }
        } else {
            add(TopItem("Bibliotecas", Icons.Filled.VideoLibrary, Route.Libraries))
        }
        add(TopItem(if (tv) "TV ao vivo" else "Ao vivo", Icons.Filled.LiveTv, Route.LiveTv))
        add(TopItem("Buscar", Icons.Filled.Search, Route.Search))
        add(TopItem("Ajustes", Icons.Filled.Settings, Route.Settings))
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
                Route.Settings -> SettingsScreen()
            }
        }
    }

    if (tv) {
        Row(Modifier.fillMaxSize()) {
            SideRail(top, rootKey) { nav.root(it) }
            Box(Modifier.weight(1f).fillMaxHeight()) { content() }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = Surface) {
                    top.forEach { t ->
                        NavigationBarItem(
                            selected = t.route.key == rootKey,
                            onClick = { nav.root(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
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
}

@Composable
private fun SideRail(items: List<TopItem>, selectedKey: String, onSelect: (Route) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(92.dp).background(Surface).verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Orange),
            contentAlignment = Alignment.Center
        ) { Text("SB", color = Color.Black, style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(18.dp))
        items.forEach { t ->
            val selected = t.route.key == selectedKey
            val shape = RoundedCornerShape(14.dp)
            Column(
                Modifier
                    .width(76.dp)
                    .tvFocus(shape, 1.05f)
                    .clip(shape)
                    .background(if (selected) Orange.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(t.route) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, contentDescription = t.label, tint = if (selected) Orange else TextMain, modifier = Modifier.size(26.dp))
                Spacer(Modifier.height(4.dp))
                Text(
                    t.label, style = MaterialTheme.typography.labelMedium,
                    color = if (selected) Orange else TextMuted, maxLines = 2,
                    textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
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
