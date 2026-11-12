package com.pecbox.streambox.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.pecbox.streambox.App
import com.pecbox.streambox.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Baixa o APK novo a partir do aviso de atualização e abre o instalador do Android. */
object AppUpdate {
    val FILE_NAME = Regex("""StreamBox(?:-Comunidade)?-(\d+(?:\.\d+)+)\.apk""")
    private const val MAX_BYTES = 200L * 1024 * 1024

    /** Texto do andamento, mostrado no aviso; null = nada em curso. */
    var status by mutableStateOf<String?>(null)
        private set
    private var busy by mutableStateOf(false)

    /** Versão mais nova encontrada no servidor; não-null = mostra a caixa de atualização na frente da tela. */
    class Available(val version: String, val url: String)
    var available by mutableStateOf<Available?>(null)

    val downloading get() = busy

    /** "Depois": some até o app ser aberto de novo (a próxima conferência traz a caixa de volta). */
    fun dismiss() { if (!busy) { available = null; status = null } }

    /** Só a pasta oficial de atualizações pode instalar: avisos também vêm de mensagens do servidor. */
    fun isUpdateUrl(url: String?): Boolean =
        url != null && url.startsWith(BuildConfig.UPDATE_INDEX) && FILE_NAME.matches(url.removePrefix(BuildConfig.UPDATE_INDEX))

    fun start(context: Context, url: String) {
        if (busy || !isUpdateUrl(url)) return
        val ctx = context.applicationContext
        if (needsAuthorization(ctx)) {
            Toast.makeText(ctx, "Autorize o StreamBox a instalar apps e volte para tocar em Atualizar", Toast.LENGTH_LONG).show()
            if (openAuthorization(ctx)) return     // sem essa tela (algumas TVs), tenta instalar assim mesmo
        }
        busy = true
        status = "Baixando…"
        App.scope.launch {
            val result = runCatching { download(ctx, url) }.mapCatching { verify(ctx, it); it }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { status = null; openInstaller(ctx, it) }
                    .onFailure { status = "Não foi possível atualizar: ${it.message ?: "erro de conexão"}" }
            }
        }
    }

    private fun needsAuthorization(ctx: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()

    private fun openAuthorization(ctx: Context): Boolean = try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** O Android 6 só instala de file://, e o instalador não lê o cache interno. */
    private fun updateDir(ctx: Context): File {
        val base = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) ctx.externalCacheDir ?: ctx.cacheDir else ctx.cacheDir
        return File(base, "updates").apply { mkdirs() }
    }

    private fun download(ctx: Context, url: String): File {
        val target = File(updateDir(ctx), "StreamBox-update.apk")
        val partial = File(target.path + ".part")
        App.session.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("servidor respondeu ${response.code}")
            val body = response.body ?: throw IOException("resposta vazia")
            val total = body.contentLength()
            if (total > MAX_BYTES) throw IOException("arquivo grande demais")
            var done = 0L
            var shown = -1L
            partial.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        done += read
                        if (done > MAX_BYTES) throw IOException("arquivo grande demais")
                        out.write(buffer, 0, read)
                        val percent = if (total > 0) done * 100 / total else -1
                        if (percent != shown) { shown = percent; if (percent >= 0) status = "Baixando… $percent%" }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("download incompleto")
        }
        target.delete()
        if (!partial.renameTo(target)) throw IOException("não foi possível salvar o arquivo")
        return target
    }

    /** A assinatura é conferida pelo próprio Android ao instalar por cima. */
    private fun verify(ctx: Context, apk: File) {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0) ?: throw IOException("arquivo inválido")
        if (info.packageName != ctx.packageName) throw IOException("o arquivo é de outro app")
        if (PackageInfoCompat.getLongVersionCode(info) <= BuildConfig.VERSION_CODE) throw IOException("esta versão já está instalada")
    }

    private fun openInstaller(ctx: Context, apk: File) {
        val uri = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) Uri.fromFile(apk)
        else FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: ActivityNotFoundException) {
            status = "Este aparelho não abriu o instalador. Use a página de downloads."
        }
    }
}
