package com.pecbox.streambox.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val logo: String?,
    val url: String,
    val tvgId: String?,
)

data class Programme(val title: String, val start: Long, val stop: Long) {
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

data class ExternalGuide(val channels: List<Channel>, val programmes: Map<String, List<Programme>>) {
    fun now(ch: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmes[ch.id]?.firstOrNull { it.start <= at && it.stop > at }

    fun next(ch: Channel, at: Long = System.currentTimeMillis()): Programme? =
        programmes[ch.id]?.firstOrNull { it.start > at }
}

/**
 * TV ao vivo a partir de uma lista M3U e de um guia XMLTV escolhidos pelo usuário.
 * Os arquivos ficam em cache por 6 horas; o guia guarda só as próximas 12 horas dos canais da lista.
 */
class ExternalLiveTv(private val s: Session, private val cacheDir: File) {

    companion object {
        private const val CACHE_MS = 6 * 3600 * 1000L
        private const val MAX_CHANNELS = 8000
        private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
        private val VOD_EXT = Regex("""\.(mp4|mkv|avi|mov|wmv|flv)(\?.*)?$""", RegexOption.IGNORE_CASE)
    }

    suspend fun load(m3uUrl: String, epgUrl: String, force: Boolean = false): ExternalGuide = withContext(Dispatchers.IO) {
        val channels = parseM3u(download(m3uUrl, "m3u", force))
        val programmes = if (epgUrl.isBlank()) emptyMap() else runCatching {
            parseXmltv(download(epgUrl, "epg", force), channels)
        }.getOrDefault(emptyMap())
        ExternalGuide(channels, programmes)
    }

    fun clearCache() {
        cacheDir.listFiles { f -> f.name.startsWith("live-") }?.forEach { it.delete() }
    }

    private fun download(url: String, kind: String, force: Boolean): File {
        val name = "live-$kind-" + MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        val file = File(cacheDir, name)
        if (!force && file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_MS) return file
        val tmp = File(cacheDir, "$name.tmp")
        s.http.newCall(Request.Builder().url(url).header("User-Agent", "StreamBox/Android").build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (file.exists()) return file // mantém a versão anterior
                throw ApiException(resp.code, "Não foi possível baixar a ${if (kind == "m3u") "lista" else "guia"} (HTTP ${resp.code})")
            }
            val body = resp.body ?: throw ApiException(0, "Resposta vazia")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        tmp.renameTo(file)
        return file
    }

    private fun parseM3u(file: File): List<Channel> {
        val out = ArrayList<Channel>()
        var attrs: Map<String, String> = emptyMap()
        var title: String? = null
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                when {
                    line.startsWith("#EXTINF") -> {
                        val comma = line.indexOf(',')
                        val header = if (comma >= 0) line.substring(0, comma) else line
                        title = if (comma >= 0) line.substring(comma + 1).trim() else null
                        attrs = ATTR.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    }
                    line.isEmpty() || line.startsWith("#") -> Unit
                    title != null -> {
                        val url = line
                        val name = title?.ifBlank { null } ?: attrs["tvg-name"] ?: "Canal"
                        val isVod = VOD_EXT.containsMatchIn(url) || url.contains("/movie/") || url.contains("/series/")
                        if ((url.startsWith("http://") || url.startsWith("https://")) && !isVod) {
                            out += Channel(
                                id = attrs["tvg-id"]?.ifBlank { null } ?: normalize(name),
                                name = name,
                                group = attrs["group-title"]?.ifBlank { null } ?: "Sem grupo",
                                logo = attrs["tvg-logo"]?.ifBlank { null },
                                url = url,
                                tvgId = attrs["tvg-id"]?.ifBlank { null },
                            )
                        }
                        title = null
                        attrs = emptyMap()
                        if (out.size >= MAX_CHANNELS) return out
                    }
                }
            }
        }
        return out
    }

    private fun openMaybeGzip(file: File): InputStream {
        val input = BufferedInputStream(file.inputStream())
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input) else input
    }

    private fun parseXmltv(file: File, channels: List<Channel>): Map<String, List<Programme>> {
        val wanted = channels.associateBy { it.tvgId?.lowercase() }.keys.filterNotNull().toMutableSet()
        val byName = channels.associate { normalize(it.name) to it.id }
        val xmlIdToChannel = HashMap<String, String>() // id do XMLTV -> Channel.id
        channels.forEach { ch -> ch.tvgId?.let { xmlIdToChannel[it.lowercase()] = ch.id } }

        val now = System.currentTimeMillis()
        val horizon = now + 12 * 3600 * 1000L
        val result = HashMap<String, MutableList<Programme>>()
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val fmtNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        fun time(v: String?): Long? = v?.trim()?.let {
            runCatching { fmt.parse(it)?.time }.getOrNull() ?: runCatching { fmtNoTz.parse(it.take(14))?.time }.getOrNull()
        }

        openMaybeGzip(file).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var ev = p.eventType
            var currentChannelXmlId: String? = null
            var progChannel: String? = null
            var progStart = 0L
            var progStop = 0L
            var inTitle = false
            var progTitle: String? = null
            var inDisplayName = false
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "channel" -> currentChannelXmlId = p.getAttributeValue(null, "id")
                        "display-name" -> inDisplayName = currentChannelXmlId != null
                        "programme" -> {
                            val chXml = p.getAttributeValue(null, "channel")?.lowercase()
                            val start = time(p.getAttributeValue(null, "start"))
                            val stop = time(p.getAttributeValue(null, "stop"))
                            val target = chXml?.let { xmlIdToChannel[it] }
                            if (target != null && start != null && stop != null && stop > now && start < horizon) {
                                progChannel = target; progStart = start; progStop = stop; progTitle = null
                            } else progChannel = null
                        }
                        "title" -> inTitle = progChannel != null && progTitle == null
                    }
                    XmlPullParser.TEXT -> {
                        if (inTitle) progTitle = p.text?.trim()
                        if (inDisplayName) {
                            // Canais sem tvg-id: liga pelo nome exibido
                            val xmlId = currentChannelXmlId?.lowercase()
                            val chId = byName[normalize(p.text ?: "")]
                            if (xmlId != null && chId != null && !xmlIdToChannel.containsKey(xmlId)) {
                                xmlIdToChannel[xmlId] = chId
                                wanted += xmlId
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "title" -> inTitle = false
                        "display-name" -> inDisplayName = false
                        "channel" -> currentChannelXmlId = null
                        "programme" -> {
                            val ch = progChannel
                            val t = progTitle
                            if (ch != null && !t.isNullOrBlank()) {
                                result.getOrPut(ch) { ArrayList() } += Programme(t, progStart, progStop)
                            }
                            progChannel = null
                        }
                    }
                }
                ev = p.next()
            }
        }
        return result.mapValues { (_, v) -> v.sortedBy { it.start } }
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("\\b(hd|fhd|sd|4k|uhd|h265|hevc)\\b"), "")
            .replace(Regex("[^a-z0-9]+"), "")
}
