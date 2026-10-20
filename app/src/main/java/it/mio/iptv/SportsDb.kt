package it.mio.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Thumb delle partite a partire dal nome dell'evento (es. "Udinese - Lecce").
 *
 *  - Stemmi delle squadre e logo del campionato: dal repo GitHub fp-pez8/pez8-images (index.json).
 *  - Gli orari NON si cercano più qui: arrivano dai siti delle leghe (vedi [LiveFixtures]).
 *
 * prefetch() scarica in anticipo gli stemmi di tutti gli eventi indicati.
 */
object SportsDb {
    private const val TAG = "SportsDb"   // diagnostica: Logcat, filtro "SportsDb"
    private val net = Executors.newCachedThreadPool()   // richieste di rete in parallelo (repo GitHub)
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** Stemmi trovati nel repo per una partita. */
    private class Info(val home: String = "", val away: String = "", val league: String = "")

    private val splitRe = Regex("""\s+(?:-|–|—|vs\.?|v)\s+""", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("""\b\d{1,2}[:.]\d{2}\b""")
    private val bracketRe = Regex("""[(\[].*?[)\]]""")
    private val qualityRe = Regex("""\b(4K|FHD|HD|SD|LIVE)\b""", RegexOption.IGNORE_CASE)
    private val accentRe = Regex("""\p{M}+""")
    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val yearRe = Regex("""\d{4}""")

    private val stopWords = setOf("fc", "ac", "as", "ss", "ssc", "us", "afc", "cf", "sc", "bc", "ud", "cfc", "calcio", "football", "club")
    private val aliases = mapOf(
        "juve" to "juventus",
        "inter" to "inter milan",
        "internazionale" to "inter milan"
    )

    /** Da chiamare una volta all'avvio. */
    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    /** Ricava le due squadre dal nome dell'evento; null se non sembra una partita. */
    fun teams(eventName: String): Pair<String, String>? {
        var s = bracketRe.replace(eventName, " ")
        s = timeRe.replace(s, " ")
        s = qualityRe.replace(s, " ")
        // toglie prefissi tipo "Serie A:" o "DAZN |"
        val cut = s.indexOfFirst { it == ':' || it == '|' }
        if (cut in 0 until s.length - 1) s = s.substring(cut + 1)
        val parts = splitRe.split(s.trim()).map { it.trim(' ', '-', '–', '—', '.') }.filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[0] to parts[1] else null
    }

    // ---- rete ----

    // Immagini dei vecchi link TheSportsDB: la versione ridotta pesa una frazione dell'originale
    private val imgExtRe = Regex("""\.(jpe?g|png)$""", RegexOption.IGNORE_CASE)
    private fun sized(url: String, size: String = "medium"): String {
        val u = url.trim()
        return if (u.contains("thesportsdb.com") && imgExtRe.containsMatchIn(u)) "$u/$size" else u
    }

    // Scarica un testo (repo GitHub). null = errore di rete.
    private fun http(url: String): String? {
        val t0 = System.currentTimeMillis()
        val what = url.removePrefix(REPO)
        for (attempt in 0..1) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                try {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "GET $what -> ${c.responseCode}, ${text.length / 1024} KB, ${System.currentTimeMillis() - t0} ms")
                    return text
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "GET $what ERRORE ${e.javaClass.simpleName} dopo ${System.currentTimeMillis() - t0} ms (tentativo ${attempt + 1})")
            }
        }
        return null
    }

    // ---- stemmi dal repo GitHub: github.com/fp-pez8/pez8-images ----
    // index.json = elenco dei campionati con le squadre e il file dello stemma di ognuna (cartella/squadre/Nome.png)

    private const val REPO = "https://raw.githubusercontent.com/fp-pez8/pez8-images/main/"

    private class RepoTeam(val n: String, val file: String, val league: String, val name: String)
    private class RepoData(val at: Long, val teams: List<RepoTeam>)
    @Volatile private var repo: RepoData? = null
    @Volatile private var repoFailAt = 0L
    private val repoLock = Any()
    private val repoCache = ConcurrentHashMap<String, Info>()   // nome evento -> stemmi trovati

    // Scarica index.json una volta (poi resta in memoria 15 minuti)
    private fun repoTeams(): List<RepoTeam> {
        fun valid(r: RepoData?) = r != null && System.currentTimeMillis() - r.at < 15 * 60_000L
        repo?.let { if (valid(it)) return it.teams }
        synchronized(repoLock) {
            repo?.let { if (valid(it)) return it.teams }
            if (System.currentTimeMillis() - repoFailAt < 60_000L) return repo?.teams ?: emptyList()
            val text = http(REPO + "index.json")
            if (text == null) {
                repoFailAt = System.currentTimeMillis()
                return repo?.teams ?: emptyList()
            }
            return try {
                val arr = JSONArray(text)
                val list = ArrayList<RepoTeam>()
                for (i in 0 until arr.length()) {
                    val l = arr.getJSONObject(i)
                    val league = l.optString("campionato", "")
                    val sq = l.optJSONArray("squadre") ?: continue
                    for (j in 0 until sq.length()) {
                        val t = sq.getJSONObject(j)
                        val f = t.optString("file", "")
                        if (f.isNotEmpty()) list.add(RepoTeam(norm(t.optString("nome", "")), f, league, t.optString("nome", "")))
                    }
                }
                repo = RepoData(System.currentTimeMillis(), list)
                list
            } catch (_: Exception) {
                repoFailAt = System.currentTimeMillis()
                emptyList()
            }
        }
    }

    // Stemmi delle due squadre e logo del campionato, dal nome dell'evento (es. "Udinese - Lecce"); null se non li trova
    private fun repoInfo(eventName: String): Info? {
        repoCache[eventName]?.let { return it }
        val (a, b) = teams(eventName) ?: return null
        val list = repoTeams()
        if (list.isEmpty()) return null
        val na = norm(a)
        val nb = norm(b)
        val ca = list.filter { sc(it.n, na) > 0 }
        val cb = list.filter { sc(it.n, nb) > 0 }
        var home: RepoTeam? = null
        var away: RepoTeam? = null
        var best = 0
        for (x in ca) for (y in cb) {
            // meglio nome uguale (2) che parziale (1), e due squadre dello stesso campionato
            val score = sc(x.n, na) + sc(y.n, nb) + (if (x.league == y.league) 3 else 0)
            if (score > best) { best = score; home = x; away = y }
        }
        if (home == null && away == null) {
            home = ca.maxByOrNull { sc(it.n, na) }
            away = cb.maxByOrNull { sc(it.n, nb) }
        }
        val league = (home ?: away)?.league ?: return null
        val info = Info(home?.let { REPO + it.file } ?: "", away?.let { REPO + it.file } ?: "", REPO + league)
        repoCache[eventName] = info
        return info
    }

    /**
     * Nome della squadra ESATTAMENTE come e' scritto nel repo pez8-images (campo "nome" di index.json), cosi' lo stemma
     * si trova sempre. [league] = "A", "B" o "C": si cerca prima nella cartella di quel campionato.
     * null se il repo non e' raggiungibile o la squadra non c'e'. Da chiamare su un thread a parte (usa la rete).
     */
    private val under23Re = Regex("""(?i)\bunder\s*23\b""")

    fun canonicalName(team: String, league: String): String? {
        val list = repoTeams()
        if (list.isEmpty()) return null
        val nt = norm(under23Re.replace(team, "U23"))
        if (nt.isEmpty()) return null
        val folder = when (league) { "A" -> "serie-a"; "B" -> "serie-b"; else -> "serie-c" }
        val wt = nt.split(' ').toSet()
        fun find(pool: List<RepoTeam>): String? {
            pool.firstOrNull { sc(it.n, nt) == 2 }?.let { return it.name }
            pool.filter { r ->
                sc(r.n, nt) == 1 && (r.n.split(' ').toSet() intersect wt).any { it.length >= 4 }
            }.minByOrNull { Math.abs(it.n.length - nt.length) }?.let { return it.name }
            // abbreviazioni: "Guidonia M." -> "Guidonia Montecelio" (ogni parola uguale, o iniziale di una parola del repo)
            val qt = nt.split(' ')
            return pool.filter { r ->
                val rt = r.n.split(' ')
                qt.all { q -> rt.any { it == q || (q.length <= 3 && it.startsWith(q)) } } && qt.any { q -> q.length >= 4 && q in rt }
            }.minByOrNull { Math.abs(it.n.length - nt.length) }?.name
        }
        // prima nella cartella del campionato, poi in tutto il repo (es. Pisa e Cremonese stanno in serie-b)
        return find(list.filter { it.file.startsWith(folder) }) ?: find(list)
    }

    // Cerca gli stemmi su un altro thread e dà il risultato a [cb] sul thread principale
    private fun repoBadges(eventName: String, cb: (Info?) -> Unit) {
        net.execute {
            val r = try { repoInfo(eventName) } catch (_: Exception) { null }
            main.post { cb(r) }
        }
    }

    // ---- confronto dei nomi ----

    private fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accentRe, "")
        val joined = n.replace(nonAlnumRe, " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in stopWords && !it.matches(yearRe) }
            .joinToString(" ")
        return aliases[joined] ?: joined
    }

    /** 2 = nomi uguali, 1 = uno contiene le parole dell'altro, 0 = diversi */
    private fun sc(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 2
        if (a.replace(" ", "") == b.replace(" ", "")) return 2   // "ostia mare" = "ostiamare"
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        return if (wa.containsAll(wb) || wb.containsAll(wa)) 1 else 0
    }

    // Gli stemmi sono nel repo GitHub; si scaricano gia' ridotti (BADGE_PX) cosi' pesano poco in memoria
    private const val BADGE_PX = 256
    private val warmed = ConcurrentHashMap.newKeySet<String>()

    private fun warmBadge(url: String) {
        val a = app ?: return
        if (url.isEmpty() || !warmed.add(url)) return    // una volta sola per stemma
        a.imageLoader.enqueue(ImageRequest.Builder(a).data(sized(url, "small")).size(BADGE_PX).build())
    }

    /** Stemma di una squadra (versione ridotta) in un ImageView. */
    fun loadBadge(iv: ImageView, url: String) {
        iv.load(badgeUrl(url)) {
            size(BADGE_PX)          // parte subito, senza aspettare che la vista sia misurata, e decodifica piccolo
            crossfade(false)
        }
    }

    fun badgeUrl(url: String): String = sized(url, "small")

    /** Scarica in anticipo gli stemmi degli eventi indicati. */
    fun prefetch(channels: List<Channel>) {
        channels.distinctBy { it.name }.forEach { ch ->
            repoBadges(ch.name) { r ->
                if (r != null) {
                    warmBadge(r.home); warmBadge(r.away); warmBadge(r.league)
                }
            }
        }
    }

    /**
     * Cerca gli stemmi delle due squadre e il logo del campionato (repo GitHub pez8-images) e li dà a [onBadges]
     * sul thread principale, se la card [iv] è ancora quella dell'evento [ch].
     */
    fun loadThumb(iv: ImageView, ch: Channel, onBadges: (String, String, String) -> Unit) {
        repoBadges(ch.name) { r ->
            if (r != null && iv.tag == ch.url && (r.home.isNotEmpty() || r.away.isNotEmpty())) onBadges(r.home, r.away, r.league)
        }
    }
}
