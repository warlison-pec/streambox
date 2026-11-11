package com.pecbox.streambox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.desktop.BuildConfig
import com.pecbox.streambox.desktop.data.Session
import com.pecbox.streambox.desktop.data.dataDir
import kotlinx.coroutines.launch

private val LANGS = listOf("por" to "Português", "eng" to "Inglês", "spa" to "Espanhol", "jpn" to "Japonês")

@Composable
fun SettingsScreen(s: Session, logout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 40.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)
        Section { IndexerSettings(s) }
        Section { SeerrSettings(s) }
        Section { LiveTvSettings(s) }
        Section { PlayerSettings(s) }
        Section {
            Text("Conta", style = MaterialTheme.typography.titleLarge)
            Text("Servidor: ${s.server}", color = TextMuted)
            Text("Usuário: ${s.userName}", color = TextMuted)
            Text("${BuildConfig.APP_NAME} ${BuildConfig.VERSION_NAME} · dados em ${dataDir.absolutePath}", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            SbButton("Sair", onClick = logout, icon = Icons.AutoMirrored.Filled.Logout, primary = false)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Surface).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun IndexerSettings(s: Session) {
    var indexer by remember { mutableStateOf(s.indexerUrl) }
    var saved by remember { mutableStateOf(false) }
    Text("Solicitações (STRM Indexer)", style = MaterialTheme.typography.titleLarge)
    Text(
        "Mostra a seção \"Disponível para solicitar\" na busca, com títulos que ainda não estão na biblioteca. Deixe em branco para desativar.",
        color = TextMuted,
    )
    OutlinedTextField(
        value = indexer, onValueChange = { indexer = it; saved = false }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("Endereço da ponte do indexador") },
        placeholder = { Text("ex.: https://strm.seudominio.com/strm-api") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SbButton("Salvar", onClick = { s.indexerUrl = indexer; indexer = s.indexerUrl; saved = true }, icon = Icons.Filled.Check)
        if (BuildConfig.DEFAULT_INDEXER.isNotBlank()) {
            SbButton("Padrão", onClick = { indexer = BuildConfig.DEFAULT_INDEXER; saved = false }, primary = false)
        }
    }
    if (saved) Text(if (indexer.isBlank()) "Solicitações desativadas." else "Salvo.", color = Success)
}

/** Configuração opcional do Seerr/Jellyseerr (igual à do app Android). */
@Composable
private fun SeerrSettings(s: Session) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.seerrUrl.ifBlank { BuildConfig.DEFAULT_SEERR }) }
    var useKey by remember { mutableStateOf(s.seerrApiKey.isNotBlank()) }
    var user by remember { mutableStateOf(s.userName) }
    var pass by remember { mutableStateOf("") }
    var key by remember { mutableStateOf(s.seerrApiKey) }
    var status by remember { mutableStateOf(if (s.seerr.enabled) "Conectado como ${s.seerrUser}" else "") }
    var ok by remember { mutableStateOf(s.seerr.enabled) }
    var busy by remember { mutableStateOf(false) }

    fun normalize(raw: String): String {
        val t = raw.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    Text("Seerr / Jellyseerr (opcional)", style = MaterialTheme.typography.titleLarge)
    Text("Mostra a seção \"Pedir no Seerr\" na busca, para pedir títulos que não estão na biblioteca nem nas listas.", color = TextMuted)
    OutlinedTextField(
        value = url, onValueChange = { url = it }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("Endereço do Seerr") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Conta do Jellyfin", selected = !useKey, onClick = { useKey = false })
        Chip("Chave de API", selected = useKey, onClick = { useKey = true })
    }
    if (useKey) {
        OutlinedTextField(
            value = key, onValueChange = { key = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Chave de API (Seerr → Configurações → Geral)") },
        )
        Text("Atenção: com a chave, todos os pedidos saem como administrador do Seerr.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
    } else {
        OutlinedTextField(
            value = user, onValueChange = { user = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Usuário do Jellyfin") },
        )
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Senha") },
            visualTransformation = PasswordVisualTransformation(),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SbButton(if (busy) "Conectando…" else "Conectar", enabled = !busy && url.isNotBlank(), icon = Icons.Filled.Check, onClick = {
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

/** "Minha lista" da TV ao vivo: lista M3U e guia XMLTV (opcionais). */
@Composable
private fun LiveTvSettings(s: Session) {
    var m3u by remember { mutableStateOf(s.m3uUrl) }
    var epg by remember { mutableStateOf(s.epgUrl) }
    var saved by remember { mutableStateOf<String?>(null) }
    Text("TV ao vivo (Minha lista)", style = MaterialTheme.typography.titleLarge)
    Text(
        "Use uma lista M3U de canais que você tem direito de acessar. O guia (EPG) no formato XMLTV também é opcional (aceita .xml ou .xml.gz).",
        color = TextMuted,
    )
    OutlinedTextField(
        value = m3u, onValueChange = { m3u = it; saved = null }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("URL da lista M3U") },
    )
    OutlinedTextField(
        value = epg, onValueChange = { epg = it; saved = null }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), label = { Text("URL do guia EPG/XMLTV (opcional)") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SbButton("Salvar", onClick = {
            s.m3uUrl = m3u; s.epgUrl = epg; s.externalTv.clearCache()
            saved = if (m3u.isBlank()) "Lista removida." else "Salvo. Abra TV ao vivo → Minha lista."
        }, icon = Icons.Filled.Check)
        if (s.m3uUrl.isNotBlank() || m3u.isNotBlank()) {
            SbButton("Remover", onClick = {
                m3u = ""; epg = ""; s.m3uUrl = ""; s.epgUrl = ""; s.externalTv.clearCache(); saved = "Lista removida."
            }, primary = false)
        }
    }
    saved?.let { Text(it, color = Success) }
}

@Composable
private fun PlayerSettings(s: Session) {
    var audio by remember { mutableStateOf(s.audioLang) }
    var sub by remember { mutableStateOf(s.subtitleLang) }
    var full by remember { mutableStateOf(s.fullscreen) }
    Text("Player", style = MaterialTheme.typography.titleLarge)
    Text("Idioma preferido do áudio", color = TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGS.forEach { (code, name) -> Chip(name, selected = audio == code, onClick = { audio = code; s.audioLang = code }) }
    }
    Text("Idioma preferido da legenda (não liga sozinha quando o áudio já está nesse idioma)", color = TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LANGS.forEach { (code, name) -> Chip(name, selected = sub == code, onClick = { sub = code; s.subtitleLang = code }) }
    }
    Text("Ao abrir um vídeo", color = TextMuted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Tela cheia", selected = full, onClick = { full = true; s.fullscreen = true })
        Chip("Janela", selected = !full, onClick = { full = false; s.fullscreen = false })
    }
}
