package com.pecbox.streambox.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import com.pecbox.streambox.ui.Notices
import com.pecbox.streambox.ui.components.SbTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.ui.theme.Danger
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.App
import com.pecbox.streambox.BuildConfig
import com.pecbox.streambox.data.SubtitleStyle
import com.pecbox.streambox.ui.MemCache
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.components.SbToggle
import com.pecbox.streambox.ui.components.tvFocus
import com.pecbox.streambox.data.HomeSections
import com.pecbox.streambox.data.Notice
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.TextMain
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.vector.ImageVector
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Success
import com.pecbox.streambox.ui.theme.TextMuted

@Composable
fun SettingsScreen(onNotices: () -> Unit = {}) {
    val s = App.session
    val tv = LocalIsTv.current
    var indexer by rememberSaveable { mutableStateOf(s.indexerUrl) }
    var saved by remember { mutableStateOf(false) }
    var m3u by rememberSaveable { mutableStateOf(s.m3uUrl) }
    var epg by rememberSaveable { mutableStateOf(s.epgUrl) }
    var tvSaved by remember { mutableStateOf(false) }
    var subLang by rememberSaveable { mutableStateOf(s.subtitleLang) }
    var audioLang by rememberSaveable { mutableStateOf(s.audioLang) }
    var subSize by rememberSaveable { mutableStateOf(s.subSize) }
    var subColor by rememberSaveable { mutableStateOf(s.subColor) }
    var subBg by rememberSaveable { mutableStateOf(s.subBackground) }
    var saver by rememberSaveable { mutableStateOf(s.screensaverMinutes) }
    var hideWatched by rememberSaveable { mutableStateOf(s.hideWatched) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(if (tv) 40.dp else 16.dp).widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

        Info("Servidor", s.server)
        Info("Usuário", s.userName)
        Info("Versão do app", BuildConfig.VERSION_NAME)
        if (!tv) SbButton(if (Notices.unread > 0) "Avisos (${Notices.unread})" else "Avisos", onClick = onNotices, primary = false)

        Text("Tela inicial", style = MaterialTheme.typography.titleLarge)
        Text("Ligue, desligue e mude a ordem das fileiras do Início.", color = TextMuted)
        ToggleLine("Ocultar assistidos", "Esconde dos destaques, da Minha lista e de \"Adicionados em…\" o que você já viu.", hideWatched) {
            hideWatched = it; s.hideWatched = it
            MemCache.map.remove("home")   // recarrega o início com a nova escolha
        }
        HomeSectionsEditor()

        NoticeSettings()

        QuickConnectAuthorize()

        Text("Solicitações (STRM Indexer)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Endereço da ponte de busca/solicitação. Deixe vazio para desativar a seção \"Disponível para solicitar\".",
            color = TextMuted
        )
        SbTextField(
            value = indexer, onValueChange = { indexer = it; saved = false },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("URL da ponte") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SbButton("Salvar", onClick = { s.indexerUrl = indexer; saved = true }, icon = Icons.Filled.Check)
            SbButton("Padrão", onClick = { indexer = BuildConfig.DEFAULT_INDEXER }, primary = false)
        }
        if (saved) Text("Salvo.", color = Success)

        SeerrSettings()

        Text("TV ao vivo · Minha lista (opcional)", style = MaterialTheme.typography.titleLarge)
        Text(
            "Use uma lista M3U de canais que você tem direito de acessar. O guia (EPG) no formato XMLTV também é opcional (aceita .xml ou .xml.gz).",
            color = TextMuted
        )
        SbTextField(
            value = m3u, onValueChange = { m3u = it; tvSaved = false }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("URL da lista M3U") },
        )
        SbTextField(
            value = epg, onValueChange = { epg = it; tvSaved = false }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("URL do guia EPG/XMLTV (opcional)") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SbButton("Salvar", onClick = {
                s.m3uUrl = m3u; s.epgUrl = epg
                s.externalTv.clearCache()
                MemCache.map.keys.removeAll { it.startsWith("live:ext:") }
                tvSaved = true
            }, icon = Icons.Filled.Check)
            SbButton("Remover", onClick = { m3u = ""; epg = ""; s.m3uUrl = ""; s.epgUrl = ""; s.externalTv.clearCache(); tvSaved = true }, primary = false)
        }
        if (tvSaved) Text("Salvo.", color = Success)

        Text("Legendas", style = MaterialTheme.typography.titleLarge)
        Text("Idioma usado para escolher e baixar legendas automaticamente.", color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("por" to "Português", "eng" to "Inglês", "spa" to "Espanhol").forEach { (code, label) ->
                Chip(label, selected = subLang == code, onClick = { subLang = code; s.subtitleLang = code })
            }
        }

        Text("Áudio", style = MaterialTheme.typography.titleLarge)
        Text("Idioma preferido. Se você trocar o áudio no player, o app lembra da sua escolha.", color = TextMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("por" to "Português", "eng" to "Inglês", "spa" to "Espanhol", "jpn" to "Japonês").forEach { (code, label) ->
                Chip(label, selected = audioLang == code, onClick = { audioLang = code; s.audioLang = code })
            }
        }

        Text("Aparência da legenda", style = MaterialTheme.typography.titleLarge)
        SubtitlePreview(subSize, subColor, subBg)
        OptionRow("Tamanho", SubtitleStyle.SIZES.map { it.first }, subSize) { subSize = it; s.subSize = it }
        OptionRow("Cor", SubtitleStyle.COLORS.map { it.first }, subColor) { subColor = it; s.subColor = it }
        OptionRow("Fundo", SubtitleStyle.BACKGROUNDS, subBg) { subBg = it; s.subBackground = it }
        Text("Também dá para mudar durante o vídeo: ícone de legendas → Aparência da legenda.", color = TextMuted)

        Text("Proteção de tela", style = MaterialTheme.typography.titleLarge)
        Text("Mostra imagens do catálogo quando o app fica parado (sem nada tocando).", color = TextMuted)
        OptionRow("Iniciar após", SAVER_OPTIONS.map { it.first }, SAVER_OPTIONS.indexOfFirst { it.second == saver }.coerceAtLeast(0)) {
            saver = SAVER_OPTIONS[it].second; s.screensaverMinutes = saver
        }
        SbButton("Ver agora", onClick = { com.pecbox.streambox.ui.Idle.active = true }, primary = false)

        Text("Manutenção", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SbButton("Limpar cache", onClick = { MemCache.clear() }, icon = Icons.Filled.Refresh, primary = false)
            SbButton("Sair", onClick = { MemCache.clear(); Notices.clear(); s.logout() }, icon = Icons.AutoMirrored.Filled.Logout, primary = false)
        }
    }
}

/** Linha com título, explicação e interruptor. */
@Composable
private fun ToggleLine(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, color = TextMuted, style = MaterialTheme.typography.bodyMedium)
        }
        SbToggle(checked, onChange)
    }
}

/** Fileiras do Início: liga/desliga e sobe/desce. A ordem vale na hora. */
@Composable
private fun HomeSectionsEditor() {
    val s = App.session
    var order by remember { mutableStateOf(s.homeOrder) }
    var hidden by remember { mutableStateOf(s.homeHidden) }

    fun save(newOrder: List<String> = order, newHidden: Set<String> = hidden) {
        val reload = newHidden != hidden
        order = newOrder; hidden = newHidden
        s.homeOrder = newOrder; s.homeHidden = newHidden
        if (reload) MemCache.map.remove("home")
    }

    fun move(i: Int, delta: Int) {
        val j = i + delta
        if (j !in order.indices) return
        save(newOrder = order.toMutableList().apply { add(j, removeAt(i)) })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEachIndexed { i, section ->
            key(section) {
                val on = section !in hidden
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(if (on) Orange.copy(alpha = 0.16f) else Surface2),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", color = if (on) Orange else TextMuted, style = MaterialTheme.typography.labelLarge) }
                    Column(Modifier.weight(1f)) {
                        Text(HomeSections.label(section), style = MaterialTheme.typography.titleMedium, color = if (on) TextMain else TextMuted)
                        HomeSections.hint(section)?.let { Text(it, color = TextMuted, style = MaterialTheme.typography.bodyMedium) }
                    }
                    MoveButton(Icons.Filled.KeyboardArrowUp, "Subir", enabled = i > 0) { move(i, -1) }
                    MoveButton(Icons.Filled.KeyboardArrowDown, "Descer", enabled = i < order.lastIndex) { move(i, 1) }
                    SbToggle(on, onChange = { v -> save(newHidden = if (v) hidden - section else hidden + section) })
                }
            }
        }
        SbButton("Restaurar padrão", primary = false, icon = Icons.Filled.Refresh, onClick = {
            save(newOrder = HomeSections.DEFAULT_ORDER, newHidden = emptySet())
        })
    }
}

@Composable
private fun MoveButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).tvFocus(CircleShape, 1.1f).clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.08f else 0.03f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (enabled) TextMain else TextMuted.copy(alpha = 0.4f)) }
}

/** O que gera aviso e se ele aparece no canto da tela. */
@Composable
private fun NoticeSettings() {
    val s = App.session
    var banner by remember { mutableStateOf(s.noticeBanner) }
    var muted by remember { mutableStateOf(s.noticeMuted) }
    fun setKind(kind: String, on: Boolean) {
        muted = if (on) muted - kind else muted + kind
        s.noticeMuted = muted
    }
    Text("Avisos", style = MaterialTheme.typography.titleLarge)
    Text("Escolha o que deve gerar aviso. Mensagens do servidor e novas versões do app sempre chegam.", color = TextMuted)
    ToggleLine("Mostrar na tela", "Cartão no canto da tela por alguns segundos. Mensagens do servidor sempre aparecem.", banner) {
        banner = it; s.noticeBanner = it
    }
    ToggleLine("Novidades do catálogo", "Filmes, séries e episódios novos.", Notice.NEW !in muted) { setKind(Notice.NEW, it) }
    ToggleLine("Seus pedidos", "Quando um título que você pediu chega.", Notice.REQUESTED !in muted) { setKind(Notice.REQUESTED, it) }
}

@Composable
private fun Info(label: String, value: String) {
    Column {
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium)
    }
}

private val SAVER_OPTIONS = listOf("Desligada" to 0, "2 min" to 2, "5 min" to 5, "10 min" to 10, "15 min" to 15, "30 min" to 30)

@Composable
private fun OptionRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = TextMuted, style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(options) { i, o -> Chip(o, selected = i == selected, onClick = { onSelect(i) }) }
        }
    }
}

/** Prévia da legenda sobre um "quadro" escuro, igual ao player. */
@Composable
private fun SubtitlePreview(size: Int, color: Int, bg: Int) {
    val fg = Color(SubtitleStyle.COLORS.getOrElse(color) { SubtitleStyle.COLORS[0] }.second)
    val scale = SubtitleStyle.SIZES.getOrElse(size) { SubtitleStyle.SIZES[1] }.second
    val shadow = when (bg) {
        0 -> Shadow(Color.Black, Offset.Zero, 6f)
        1 -> Shadow(Color.Black, Offset(3f, 3f), 4f)
        else -> null
    }
    val box = when (bg) {
        2 -> Color.Black.copy(alpha = 0.6f)
        3 -> Color.Black
        else -> Color.Transparent
    }
    Box(
        Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A4A5A), Color(0xFF1B2430), Color(0xFF5A4636)))),
        contentAlignment = Alignment.BottomCenter
    ) {
        val fontSize = (18 * scale).sp
        Box(Modifier.padding(bottom = 14.dp).background(box).padding(horizontal = 6.dp, vertical = 2.dp)) {
            if (bg == 0) {
                // Contorno grosso, igual ao do player (cerca de 15% do tamanho da letra)
                val strokePx = with(LocalDensity.current) { fontSize.toPx() } * 0.15f
                Text(
                    "Exemplo de legenda no vídeo",
                    style = TextStyle(
                        color = Color.Black, fontSize = fontSize, fontWeight = FontWeight.Bold,
                        shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, strokePx * 0.3f), strokePx * 0.7f),
                        drawStyle = Stroke(width = strokePx, join = StrokeJoin.Round),
                    ),
                )
            }
            Text(
                "Exemplo de legenda no vídeo",
                style = TextStyle(color = fg, fontSize = fontSize, fontWeight = FontWeight.Bold, shadow = if (bg == 0) null else shadow),
            )
        }
    }
}

/** Conexão rápida: autoriza o código mostrado em outro aparelho (TV, navegador, outro app). */
@Composable
private fun QuickConnectAuthorize() {
    val s = App.session
    val scope = rememberCoroutineScope()
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }

    fun submit() {
        val clean = code.filter { it.isDigit() }
        if (busy || clean.isEmpty()) return
        scope.launch {
            busy = true
            try {
                s.jellyfin.quickConnectAuthorize(clean)
                status = "Aparelho autorizado. Ele vai entrar com a conta ${s.userName} em alguns segundos."
                ok = true
                code = ""
            } catch (e: Exception) {
                status = e.friendly()
                ok = false
            } finally {
                busy = false
            }
        }
    }

    Text("Conexão rápida", style = MaterialTheme.typography.titleLarge)
    Text(
        "Digite o código que aparece em outro aparelho para conectá-lo com a sua conta, sem precisar de senha.",
        color = TextMuted
    )
    SbTextField(
        value = code,
        onValueChange = { v -> code = v.filter { it.isDigit() }.take(10); status = "" },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Código") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
    )
    SbButton(
        if (busy) "Autorizando…" else "Autorizar", onClick = { submit() },
        icon = Icons.Filled.Check, enabled = !busy && code.isNotBlank()
    )
    if (status.isNotBlank()) Text(status, color = if (ok) Success else Danger)
}

/** Configuração opcional do Seerr/Jellyseerr. */
@Composable
private fun SeerrSettings() {
    val s = App.session
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(s.seerrUrl.ifBlank { BuildConfig.DEFAULT_SEERR }) }
    var useKey by rememberSaveable { mutableStateOf(s.seerrApiKey.isNotBlank()) }
    var user by rememberSaveable { mutableStateOf(s.userName) }
    var pass by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf(s.seerrApiKey) }
    var status by remember { mutableStateOf(if (s.seerr.enabled) "Conectado como ${s.seerrUser}" else "") }
    var ok by remember { mutableStateOf(s.seerr.enabled) }
    var busy by remember { mutableStateOf(false) }

    fun normalize(raw: String): String {
        val t = raw.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    Text("Seerr / Jellyseerr (opcional)", style = MaterialTheme.typography.titleLarge)
    Text(
        "Mostra a seção \"Pedir no Seerr\" na busca, para pedir títulos que não estão na biblioteca nem nas listas.",
        color = TextMuted
    )
    SbTextField(
        value = url, onValueChange = { url = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("Endereço do Seerr") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Conta do Jellyfin", selected = !useKey, onClick = { useKey = false })
        Chip("Chave de API", selected = useKey, onClick = { useKey = true })
    }
    if (useKey) {
        SbTextField(
            value = key, onValueChange = { key = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Chave de API (Seerr → Configurações → Geral)") },
        )
        Text("Atenção: com a chave, todos os pedidos saem como administrador do Seerr.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    } else {
        SbTextField(
            value = user, onValueChange = { user = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Usuário do Jellyfin") },
        )
        SbTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Senha") },
            visualTransformation = PasswordVisualTransformation(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SbButton(if (busy) "Conectando…" else "Conectar", enabled = !busy, icon = Icons.Filled.Check, onClick = {
            scope.launch {
                busy = true
                try {
                    val address = normalize(url)
                    val who = if (useKey) {
                        s.seerrUrl = address
                        s.seerrCookie = ""
                        s.seerrApiKey = key
                        s.seerr.me()
                    } else {
                        s.seerr.loginWithJellyfin(address, user.trim(), pass)
                    }
                    pass = ""
                    status = "Conectado como $who"
                    ok = true
                } catch (e: Exception) {
                    if (useKey) s.seerrApiKey = ""
                    status = "Falha: " + e.friendly()
                    ok = false
                } finally {
                    busy = false
                }
            }
        })
        if (s.seerr.enabled || ok) {
            SbButton("Desconectar", primary = false, onClick = { s.seerr.logout(); status = "Desconectado."; ok = false })
        }
    }
    if (status.isNotBlank()) Text(status, color = if (ok) Success else Danger)
}
