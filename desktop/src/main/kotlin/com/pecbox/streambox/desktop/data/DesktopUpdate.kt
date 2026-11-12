package com.pecbox.streambox.desktop.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pecbox.streambox.data.fetch
import com.pecbox.streambox.desktop.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlin.system.exitProcess

/**
 * Nova versão do app para computador: confere a pasta de downloads (a mesma do APK, subpasta desktop/),
 * baixa o instalador desta edição e sistema (.msi no Windows, .deb no Linux) e abre a instalação.
 */
object DesktopUpdate {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val linux = System.getProperty("os.name").orEmpty().startsWith("Linux")
    private const val MAX_BYTES = 600L * 1024 * 1024

    /** Nome do instalador desta edição: StreamBox-0.1.7.msi / streambox_0.1.7-1_amd64.deb (e os da Comunidade). */
    private val fileName: Regex? = when {
        windows -> Regex("""(?<=")${Regex.escape(BuildConfig.APP_NAME)}-(\d+(?:\.\d+)+)\.msi""")
        linux -> Regex("""(?<=")${Regex.escape(BuildConfig.DATA_DIR)}_(\d+(?:\.\d+)+)-\d+_amd64\.deb""")
        else -> null
    }

    class Available(val version: String, val file: String)

    /** Não-null = mostra a caixa de atualização na frente da tela. */
    var available by mutableStateOf<Available?>(null)
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Linux: instalou; falta abrir o app de novo. */
    var installed by mutableStateOf(false)
        private set

    fun dismiss() { if (!busy) { available = null; status = null } }

    private fun compare(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    suspend fun check(http: OkHttpClient) {
        val pattern = fileName ?: return
        if (installed) return
        val listing = http.fetch(Request.Builder().url(BuildConfig.UPDATE_INDEX).build())
        val newest = pattern.findAll(listing).maxWithOrNull { a, b -> compare(a.groupValues[1], b.groupValues[1]) } ?: return
        val version = newest.groupValues[1]
        if (compare(version, BuildConfig.VERSION_NAME) <= 0) return
        withContext(Dispatchers.Main) {
            if (available?.version != version) available = Available(version, newest.value)
        }
    }

    /** "Atualizar agora": baixa e abre o instalador. Chamado na thread da interface. */
    suspend fun start(http: OkHttpClient, onBeforeExit: () -> Unit) {
        val update = available ?: return
        if (busy) return
        busy = true
        status = "Baixando…"
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = download(http, update.file)
                if (windows) runMsi(file) else installDeb(file)
            }
        }
        busy = false
        result.onSuccess { done ->
            if (windows) { onBeforeExit(); exitProcess(0) }   // o instalador substitui os arquivos com o app fechado
            if (done) { installed = true; status = "Pronto! Feche e abra o StreamBox para usar a versão ${update.version}." }
            else status = "Abrimos o instalador do sistema. Depois de instalar, abra o StreamBox de novo."
        }.onFailure { status = "Não foi possível atualizar: ${it.message ?: "erro de conexão"}" }
    }

    private fun download(http: OkHttpClient, name: String): File {
        val url = BuildConfig.UPDATE_INDEX + URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val dir = File(System.getProperty("java.io.tmpdir"), "streambox-update").apply { mkdirs() }
        val target = File(dir, name)
        val partial = File(dir, "$name.part")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("servidor respondeu ${response.code}")
            val body = response.body ?: throw IOException("resposta vazia")
            val total = body.contentLength()
            if (total > MAX_BYTES) throw IOException("arquivo grande demais")
            var done = 0L
            var shown = -1L
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        if (done > MAX_BYTES) throw IOException("arquivo grande demais")
                        out.write(buffer, 0, read)
                        val percent = if (total > 0) done * 100 / total else -1
                        if (percent != shown && percent >= 0) { shown = percent; status = "Baixando… $percent%" }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("download incompleto")
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("não foi possível salvar o arquivo")
        return target
    }

    /** Windows: o próprio instalador .msi (mesmo upgradeUuid) troca a versão antiga pela nova. */
    private fun runMsi(file: File): Boolean {
        ProcessBuilder("msiexec", "/i", file.absolutePath).start()
        return true
    }

    /**
     * Linux: pede a senha pela janela do sistema (pkexec) e instala com o apt.
     * Sem pkexec, ou se a pessoa cancelar, abre o .deb no instalador gráfico do sistema.
     * Devolve true se ficou instalado.
     */
    private fun installDeb(file: File): Boolean {
        status = "Instalando… (o sistema vai pedir sua senha)"
        val ok = runCatching {
            ProcessBuilder("pkexec", "apt-get", "install", "-y", "--allow-downgrades", file.absolutePath)
                .redirectErrorStream(true).start().let { p -> p.inputStream.readAllBytes(); p.waitFor() == 0 }
        }.getOrDefault(false)
        if (ok) return true
        val opened = runCatching { ProcessBuilder("xdg-open", file.absolutePath).start(); true }.getOrDefault(false) ||
            runCatching { Desktop.getDesktop().open(file); true }.getOrDefault(false)
        if (!opened) throw IOException("instale manualmente o arquivo ${file.absolutePath}")
        return false
    }
}
