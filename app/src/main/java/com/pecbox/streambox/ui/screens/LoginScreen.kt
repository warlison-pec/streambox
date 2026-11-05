package com.pecbox.streambox.ui.screens

import com.pecbox.streambox.data.JellyfinApi
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pecbox.streambox.App
import com.pecbox.streambox.BuildConfig
import com.pecbox.streambox.data.friendly
import com.pecbox.streambox.ui.components.Chip
import com.pecbox.streambox.ui.components.SbButton
import com.pecbox.streambox.ui.theme.Danger
import com.pecbox.streambox.ui.theme.LocalIsTv
import com.pecbox.streambox.ui.theme.Orange
import com.pecbox.streambox.ui.theme.Surface2
import com.pecbox.streambox.ui.theme.TextMuted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val s = App.session
    val jf = s.jellyfin
    val tv = LocalIsTv.current
    // Versão PecBox: servidor fixo. Versão da comunidade: a pessoa informa o próprio Jellyfin.
    var server by rememberSaveable {
        mutableStateOf(if (BuildConfig.LOCK_SERVER) BuildConfig.DEFAULT_SERVER else s.server.ifBlank { BuildConfig.DEFAULT_SERVER })
    }
    val ctx = LocalContext.current
    var user by rememberSaveable { mutableStateOf(s.userName) }
    var pass by rememberSaveable { mutableStateOf("") }
    // Na TV a conexão rápida é o padrão: digitar senha com o controle é ruim.
    var quick by rememberSaveable { mutableStateOf(tv) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var qcCode by remember { mutableStateOf<String?>(null) }
    var qcSecret by remember { mutableStateOf<String?>(null) }
    var qcServer by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun submitPassword() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try {
                val address = jf.connect(server)
                s.saveLogin(address, jf.login(address, user.trim(), pass))
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                busy = false
            }
        }
    }

    fun startQuickConnect() {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            qcCode = null
            qcSecret = null
            try {
                val address = jf.connect(server)
                if (!jf.quickConnectEnabled(address)) {
                    error = "A conexão rápida está desativada no servidor. Ative em Painel → Geral → \"Permitir conexão rápida\"."
                } else {
                    val r = jf.quickConnectInitiate(address)
                    qcServer = address
                    qcCode = r.code
                    qcSecret = r.secret
                }
            } catch (e: Exception) {
                error = e.friendly()
            } finally {
                busy = false
            }
        }
    }

    // Espera alguém autorizar o código em outro aparelho
    LaunchedEffect(qcSecret) {
        val secret = qcSecret ?: return@LaunchedEffect
        while (true) {
            delay(3000)
            val state = runCatching { jf.quickConnectState(qcServer, secret) }.getOrNull() ?: continue
            if (state.authenticated) {
                try {
                    s.saveLogin(qcServer, jf.quickConnectLogin(qcServer, secret))
                } catch (e: Exception) {
                    error = e.friendly()
                    qcCode = null
                }
                break
            }
        }
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Orange),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(40.dp)) }
            Text("StreamBox", style = MaterialTheme.typography.headlineLarge)
            if (BuildConfig.LOCK_SERVER) {
                Text("Entre com o seu usuário.", color = TextMuted)
            } else {
                Text("Conecte ao seu servidor Jellyfin.", color = TextMuted)
                OutlinedTextField(
                    value = server, onValueChange = { server = it; qcCode = null; qcSecret = null },
                    label = { Text("Endereço do servidor") },
                    placeholder = { Text("ex.: https://jellyfin.seudominio.com ou 192.168.0.10:8096") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Conexão rápida", selected = quick, onClick = { quick = true; error = null })
                Chip("Usuário e senha", selected = !quick, onClick = { quick = false; error = null; qcSecret = null; qcCode = null })
            }

            if (quick) {
                val code = qcCode
                if (code == null) {
                    Text(
                        "Gere um código e confirme em outro aparelho onde você já está conectado (celular ou navegador).",
                        color = TextMuted
                    )
                    SbButton(if (busy) "Gerando…" else "Gerar código", onClick = { startQuickConnect() }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                } else {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface2).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Seu código", color = TextMuted)
                        Text(
                            code.chunked(3).joinToString(" "),
                            color = Orange, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp
                        )
                        Text(
                            "No Jellyfin de outro aparelho: abra o seu perfil → Conexão rápida → digite o código.",
                            color = TextMuted, style = MaterialTheme.typography.bodyMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(color = Orange, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Text("Aguardando autorização…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    SbButton("Gerar novo código", onClick = { startQuickConnect() }, icon = Icons.Filled.Refresh, primary = false, modifier = Modifier.fillMaxWidth())
                }
            } else {
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, label = { Text("Usuário") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, label = { Text("Senha") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                )
                SbButton(
                    if (busy) "Entrando…" else "Entrar", onClick = { submitPassword() },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy && user.isNotBlank()
                )
            }
            if (error != null) Text(error ?: "", color = Danger)
            if (BuildConfig.ADMIN_WHATSAPP.isNotBlank()) {
                // Quem não tem conta fala com o administrador (no celular abre o WhatsApp)
                SbButton(
                    "Não tem acesso? WhatsApp ${BuildConfig.ADMIN_WHATSAPP_LABEL}",
                    onClick = {
                        val msg = Uri.encode("Olá! Gostaria de solicitar acesso ao StreamBox.")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${BuildConfig.ADMIN_WHATSAPP}?text=$msg"))) }
                    },
                    primary = false, modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Acha o Jellyfin a partir do que foi digitado. Sem protocolo, tenta https e depois http
 * (comum em servidores da rede local, ex.: 192.168.0.10:8096). Aceita também o /api do StreamBox Web.
 */
private suspend fun JellyfinApi.connect(raw: String): String {
    val t = raw.trim().trimEnd('/')
    if (t.isBlank()) throw IllegalStateException("Informe o endereço do servidor.")
    if (t.startsWith("http://") || t.startsWith("https://")) return resolveServer(t)
    return runCatching { resolveServer("https://$t") }.getOrElse { first ->
        runCatching { resolveServer("http://$t") }.getOrElse { throw first }
    }
}
