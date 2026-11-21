package com.pecbox.streambox.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer

/**
 * Guia de eventos esportivos montado a partir do EPG dos canais de esporte.
 * Só entra o que parece um evento: jogos ("Time A x Time B") e transmissões marcadas "Ao Vivo"
 * de corridas, lutas, ligas etc. Programas de debate e noticiário ficam de fora.
 */
object SportsGuide {

    /** Um programa do EPG de um canal de esporte (Jellyfin ou lista M3U). */
    class Source(
        val title: String, val start: Long, val stop: Long,
        val channelKey: String, val channelName: String, val channelLogo: String?,
        val jfChannel: Item? = null, val extChannel: Channel? = null,
    )

    class EventChannel(val key: String, val name: String, val logo: String?, val jfChannel: Item?, val extChannel: Channel?)

    data class Event(
        val id: String,
        val key: String,            // título normalizado (para juntar e comparar sem refazer a conta)
        val title: String,          // "Flamengo x Palmeiras" ou "MotoGP: GP de Portugal"
        val home: String?,          // times (só em jogos)
        val away: String?,
        val info: String?,          // fase / campeonato ("Final", "Campeonato Brasileiro")
        val start: Long,
        val stop: Long,
        val live: Boolean,          // o EPG marca "Ao Vivo"
        val replay: Boolean,        // "VT", reprise
        val channels: List<EventChannel>,
    ) {
        val isMatch get() = home != null && away != null
        fun onAir(now: Long) = start <= now && stop > now
        fun progress(now: Long): Float = if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
    }

    private val VERSUS = Regex("""^(.+?)\s+(?:x|×|vs\.?|versus)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val LIVE = Regex("""[\s\-–:(]*\bao vivo\b[\s)]*""", RegexOption.IGNORE_CASE)
    private val REPLAY = Regex("""^\s*(?:vt|reprise|compacto|melhores momentos)\s*[-–:]\s*|\s*[-–(]\s*(?:vt|reprise)\s*\)?\s*$""", RegexOption.IGNORE_CASE)
    private val EVENT_WORDS = Regex(
        """\b(gp|grande pr[eê]mio|f[oó]rmula|f1|f2|f3|motogp|moto ?[23]|stock car|nascar|indy|rally|nfl|nba|nhl|mlb|wnba|ncaa|ufc|""" +
            """boxe|luta|mma|bellator|pfl|t[eê]nis|atp|wta|v[oô]lei|basquete|handebol|futsal|corrida|etapa|final|semifinal|""" +
            """quartas|copa|campeonato|liga|ta[cç]a|torneio|grand slam|open|golfe|pga|surf|wsl|ciclismo|tour|maratona|""" +
            """ol[ií]mpi\\w*|mundial|jogo|partida|rodada|supercopa|libertadores|sul-?americana|champions|premier|brasileir[aã]o)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val SHOWS = Regex(
        """\b(sportscenter|news|resenha|linha de passe|reda[cç][aã]o|giro|abre o jogo|pr[eé]-jogo|p[oó]s-jogo|depois do jogo|""" +
            """aquecimento|t[aá] on|bola da vez|troca de passes|sele[cç][aã]o sportv|fechamento|esporte total|tempo t[eé]cnico|""" +
            """momentos?|ol[eé]+ s\.?a|equipe f|baú|bau do esporte|programa[cç][aã]o|stories|inside line|this week|action|""" +
            """especial|document[aá]rio|filmes?|film|mesa-redonda|debate|entrevista|bate-papo|show|replay|highlights|redzone)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val SPLIT = Regex("""\s+[-–|]\s+|\s*:\s+""")
    private val NOT_TEAM = Regex("""^(d[aeo]s?|em|no|na|para|com)\s""", RegexOption.IGNORE_CASE)
    private val PAREN_END = Regex("""\s*\((?:[^)]*)\)\s*$""")
    private val MARKS = Regex("\\p{M}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")

    class Parsed(val title: String, val home: String?, val away: String?, val info: String?, val live: Boolean, val replay: Boolean)

    /** Lê o título do EPG. Null quando não é um evento (programa de estúdio, noticiário…). */
    fun parse(raw: String): Parsed? {
        var t = raw.trim()
        val live = LIVE.containsMatchIn(t)
        t = LIVE.replace(t, " ").trim()
        val replay = REPLAY.containsMatchIn(t)
        t = REPLAY.replace(t, "").trim().trim('-', '–', ':', ' ')
        if (t.isEmpty()) return null
        // "Final, Jogo 2: Brusque x Pinhalzinho", "Palmeiras x Flamengo - Campeonato Brasileiro : Palmeiras x Flamengo"
        val parts = t.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        val matchPart = parts.firstOrNull { VERSUS.matches(it) && it.length <= 80 }
        if (matchPart != null) {
            val m = VERSUS.find(matchPart)!!
            val home = cleanTeam(m.groupValues[1])
            val away = cleanTeam(m.groupValues[2])
            // "Raio X do Brasileirão" não é jogo
            if (home.isNotEmpty() && away.isNotEmpty() && home.length <= 40 && away.length <= 40 && !NOT_TEAM.containsMatchIn(away)) {
                val key = norm(matchPart)
                val homeKey = norm(home)
                val info = parts.filter { val n = norm(it); n != key && !n.contains(homeKey) }.distinct()
                    .joinToString(" · ").ifBlank { null }
                return Parsed("$home x $away", home, away, info, live, replay)
            }
        }
        // Sem "x": só transmissões ao vivo de algo que parece competição
        if (SHOWS.containsMatchIn(t) || !EVENT_WORDS.containsMatchIn(t)) return null
        return Parsed(t, null, null, null, live = live, replay = replay)
    }

    private fun cleanTeam(s: String) = s.replace(PAREN_END, "").trim().trim('-', '–', ':', ',', ' ')

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(NON_ALNUM, "")

    /**
     * Monta os eventos: junta blocos seguidos do mesmo evento no mesmo canal, junta o mesmo evento em
     * vários canais e marca como reprise o que já passou antes (os canais nem sempre escrevem "VT").
     * Mande também as últimas 24 h do guia, para saber o que já passou. Só voltam os que não terminaram.
     */
    fun build(sources: List<Source>, now: Long = System.currentTimeMillis()): List<Event> {
        val out = ArrayList<Event>()
        val firstSeen = HashMap<String, Long>()
        val lastOnChannel = HashMap<String, Int>()      // canal + evento → índice do último bloco em [out]
        val byKey = HashMap<String, MutableList<Int>>()  // evento → índices em [out] (sem varrer a lista toda)
        for (src in sources.sortedBy { it.start }) {
            val p = parse(src.title) ?: continue
            val key = norm(p.title)
            val chKey = src.channelKey + "|" + key
            val idx = lastOnChannel[chKey]
            if (idx != null && src.start - out[idx].stop <= 5 * 60_000L) {
                val prev = out[idx]
                out[idx] = prev.copy(stop = maxOf(prev.stop, src.stop), live = prev.live || p.live)
                continue
            }
            val first = firstSeen[key]
            if (first == null) firstSeen[key] = src.start
            val replay = p.replay || (!p.live && first != null && src.start - first > 45 * 60_000L)
            val ch = EventChannel(src.channelKey, src.channelName, src.channelLogo, src.jfChannel, src.extChannel)
            val i = byKey[key]?.firstOrNull {
                out[it].replay == replay && kotlin.math.abs(out[it].start - src.start) <= 45 * 60_000L
            } ?: -1
            if (i >= 0) {
                val e = out[i]
                out[i] = e.copy(
                    channels = if (e.channels.any { it.key == ch.key }) e.channels else e.channels + ch,
                    live = e.live || p.live, info = e.info ?: p.info, stop = maxOf(e.stop, src.stop),
                )
                lastOnChannel[chKey] = i
            } else {
                out += Event(
                    id = "$key@${src.start}", key = key, title = p.title, home = p.home, away = p.away, info = p.info,
                    start = src.start, stop = src.stop, live = p.live, replay = replay, channels = listOf(ch),
                )
                lastOnChannel[chKey] = out.lastIndex
                byKey.getOrPut(key) { ArrayList() } += out.lastIndex
            }
        }
        return out.filter { it.stop > now }.sortedWith(compareBy<Event> { it.start }.thenBy { it.title })
    }
}

/**
 * Escudos dos times pelo TheSportsDB (gratuito). A chave pública aceita poucas consultas por minuto,
 * então elas vão uma de cada vez e o resultado fica guardado no aparelho (escudo para sempre,
 * "não achei" por 7 dias).
 */
class TeamBadges(context: Context, private val http: OkHttpClient) {
    private val prefs = context.getSharedPreferences("team_badges", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var lastCall = 0L
    private var pausedUntil = 0L

    /** Escudos já conhecidos (a tela redesenha quando um chega). Valor "" = sem escudo. */
    val known = mutableStateMapOf<String, String>()

    companion object {
        private const val API = "https://www.thesportsdb.com/api/v1/json/123/searchteams.php"
        private const val GAP_MS = 2_200L
        private const val MISS_TTL = 7 * 24 * 3600_000L

        /** Nomes como aparecem nos guias brasileiros → nome no TheSportsDB. */
        private val ALIASES = mapOf(
            "atleticomg" to "Atletico Mineiro", "atleticomineiro" to "Atletico Mineiro", "galo" to "Atletico Mineiro",
            "atleticopr" to "Athletico Paranaense", "athleticopr" to "Athletico Paranaense", "athletico" to "Athletico Paranaense",
            "atleticogo" to "Atletico Goianiense", "americamg" to "America Mineiro", "americarn" to "America RN",
            "bragantino" to "Bragantino", "rbbragantino" to "Bragantino", "redbullbragantino" to "Bragantino",
            "vasco" to "Vasco da Gama", "sport" to "Sport Recife", "sportrecife" to "Sport Recife",
            "botafogosp" to "Botafogo SP", "botafogorj" to "Botafogo", "internacional" to "Internacional", "inter" to "Internacional",
            "intermilao" to "Inter Milan", "internazionale" to "Inter Milan", "milan" to "AC Milan", "juventus" to "Juventus",
            "bayern" to "Bayern Munich", "bayerndemunique" to "Bayern Munich", "psg" to "Paris SG", "parissaintgermain" to "Paris SG",
            "manchesterunited" to "Manchester United", "manutd" to "Manchester United", "tottenham" to "Tottenham Hotspur",
            "brighton" to "Brighton and Hove Albion", "brightonhovealbion" to "Brighton and Hove Albion",
            "wolverhampton" to "Wolverhampton Wanderers", "wolves" to "Wolverhampton Wanderers", "newcastle" to "Newcastle United",
            "westham" to "West Ham United", "leeds" to "Leeds United", "nottinghamforest" to "Nottingham Forest",
            "atleticodemadri" to "Atletico Madrid", "atleticomadri" to "Atletico Madrid", "atleticodemadrid" to "Atletico Madrid",
            "barcelona" to "Barcelona", "realmadri" to "Real Madrid", "sevilha" to "Sevilla", "betis" to "Real Betis",
            "roma" to "AS Roma", "napoles" to "Napoli", "lazio" to "Lazio", "fiorentina" to "Fiorentina",
            "borussiadortmund" to "Borussia Dortmund", "dortmund" to "Borussia Dortmund", "leverkusen" to "Bayer Leverkusen",
            "benfica" to "Benfica", "porto" to "FC Porto", "sporting" to "Sporting CP", "ajax" to "Ajax",
            "riverplate" to "River Plate", "bocajuniors" to "Boca Juniors", "penarol" to "Penarol", "nacional" to "Nacional",
            "ldu" to "LDU Quito", "olimpia" to "Olimpia", "cerroporteno" to "Cerro Porteno", "independientedelvalle" to "Independiente del Valle",
            "brasil" to "Brazil", "argentina" to "Argentina", "alemanha" to "Germany", "franca" to "France", "inglaterra" to "England",
            "espanha" to "Spain", "italia" to "Italy", "holanda" to "Netherlands", "paisesbaixos" to "Netherlands",
            "uruguai" to "Uruguay", "colombia" to "Colombia", "chile" to "Chile", "paraguai" to "Paraguay", "peru" to "Peru",
            "equador" to "Ecuador", "venezuela" to "Venezuela", "bolivia" to "Bolivia", "mexico" to "Mexico", "estadosunidos" to "USA",
            "eua" to "USA", "japao" to "Japan", "coreiadosul" to "South Korea", "marrocos" to "Morocco", "belgica" to "Belgium",
            "croacia" to "Croatia", "suica" to "Switzerland", "dinamarca" to "Denmark", "suecia" to "Sweden", "noruega" to "Norway",
            "polonia" to "Poland", "escocia" to "Scotland", "gales" to "Wales", "irlanda" to "Ireland", "turquia" to "Turkey",
            "senegal" to "Senegal", "camaroes" to "Cameroon", "nigeria" to "Nigeria", "gana" to "Ghana", "egito" to "Egypt",
            "australia" to "Australia", "canada" to "Canada", "arabiasaudita" to "Saudi Arabia", "catar" to "Qatar",
        )

        private fun plain(s: String) =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()
    }

    init {
        val now = System.currentTimeMillis()
        for ((k, v) in prefs.all) {
            val s = v as? String ?: continue
            val url = s.substringBefore('|')
            val at = s.substringAfter('|', "0").toLongOrNull() ?: 0L
            if (url.isNotEmpty() || now - at < MISS_TTL) known[k] = url
        }
    }

    fun cached(team: String): String? = known[SportsGuide.norm(team)]?.ifEmpty { null }

    /** Procura o escudo (respeitando o limite da API). Null se não houver. */
    suspend fun lookup(team: String): String? {
        val key = SportsGuide.norm(team)
        if (key.isEmpty()) return null
        known[key]?.let { return it.ifEmpty { null } }
        return mutex.withLock {
            known[key]?.let { return@withLock it.ifEmpty { null } }
            val tries = buildList {
                ALIASES[key]?.let { add(it) }
                add(plain(team))
                // "Atlético-GO" → "Atletico"; "Fortaleza EC" → "Fortaleza"
                val base = plain(team).replace(Regex("""[-\s]+[A-Za-z]{2}$"""), "").replace(Regex("""\s+(EC|FC|SC|AC|CF)$""", RegexOption.IGNORE_CASE), "")
                if (base != plain(team)) add(base)
            }.distinct()
            var found: String? = null
            for (q in tries) {
                val r = query(q) ?: return@withLock null   // limite atingido: tenta de novo mais tarde
                if (r.isNotEmpty()) { found = r; break }
            }
            val value = found.orEmpty()
            known[key] = value
            prefs.edit().putString(key, "$value|${System.currentTimeMillis()}").apply()
            found
        }
    }

    /** "" = não achou; null = não deu para consultar agora. */
    private suspend fun query(name: String): String? = withContext(Dispatchers.IO) {
        val wait = maxOf(lastCall + GAP_MS, pausedUntil) - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastCall = System.currentTimeMillis()
        val url = API.toHttpUrl().newBuilder().addQueryParameter("t", name).build()
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "StreamBox").build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || !body.trimStart().startsWith("{")) {
                    pausedUntil = System.currentTimeMillis() + 60_000
                    return@use null
                }
                val teams = AppJson.parseToJsonElement(body).jsonObject["teams"]
                val list = runCatching { teams!!.jsonArray.map { it.jsonObject } }.getOrNull().orEmpty()
                val best = list.firstOrNull { it["strSport"]?.jsonPrimitive?.content == "Soccer" } ?: list.firstOrNull()
                val badge = best?.get("strBadge")?.jsonPrimitive?.content?.takeIf { it.startsWith("http") }
                badge?.let { "$it/small" } ?: ""
            }
        }.getOrNull()
    }
}
