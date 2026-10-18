package it.mio.iptv

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import coil.load
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Trova su TheSportsDB l'immagine (thumb) della partita a partire dal nome dell'evento
 * (es. "Udinese - Lecce") e la mostra al posto del logo del canale.
 *
 * Come funziona:
 *  1. scarica UNA volta (e tiene in cache) tutte le partite di calcio di ieri/oggi/domani;
 *  2. trova la partita confrontando i nomi delle squadre in modo tollerante ("Milan" = "AC Milan",
 *     "Juve" = "Juventus", casa/trasferta anche invertiti);
 *  3. se non la trova prova la ricerca per nome, ma accetta solo partite di questi giorni
 *     (mai partite vecchie con lo stesso nome);
 *  4. ricorda il risultato (anche dopo la chiusura dell'app) per non rifare le richieste.
 */
object SportsDb {
    // Chiave gratuita di test di TheSportsDB (ha un limite di richieste: per questo si usa la cache)
    private const val KEY = "123"
    private const val API = "https://www.thesportsdb.com/api/v1/json/$KEY/"
    private const val WINDOW_DAYS = 2L          // distanza massima (in giorni) della partita da oggi
    private const val MISS_RETRY_MS = 5 * 60_000L

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null

    private val found = ConcurrentHashMap<String, String>()    // "nome|data" -> URL
    private val misses = ConcurrentHashMap<String, Long>()     // "nome|data" -> quando è fallita
    private class Day(val at: Long, val events: List<JSONObject>)
    private val days = ConcurrentHashMap<LocalDate, Day>()

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

    /** Da chiamare una volta all'avvio: serve a ricordare i thumb trovati anche dopo la chiusura dell'app. */
    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("sportsdb", Context.MODE_PRIVATE)
        prefs = p
        val suffix = "|" + LocalDate.now()
        val e = p.edit()
        p.all.keys.filter { !it.endsWith(suffix) }.forEach { e.remove(it) }   // tiene solo quelli di oggi
        e.apply()
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

    private fun get(path: String): List<JSONObject>? = try {
        val c = (URL(API + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        val text = try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val j = JSONObject(text)
        val arr = j.optJSONArray("events") ?: j.optJSONArray("event")
        if (arr == null) emptyList() else List(arr.length()) { arr.getJSONObject(it) }
    } catch (_: Exception) {
        null   // errore di rete: non va messo in cache
    }

    private fun fresh(d: Day) =
        System.currentTimeMillis() - d.at < (if (d.events.isEmpty()) 2 else 10) * 60_000L

    /** Tutte le partite di calcio di un giorno (una sola richiesta, poi cache). */
    private fun day(d: LocalDate): List<JSONObject> {
        days[d]?.let { if (fresh(it)) return it.events }
        synchronized(this) {
            days[d]?.let { if (fresh(it)) return it.events }
            val ev = get("eventsday.php?d=$d&s=Soccer") ?: return days[d]?.events ?: emptyList()
            days[d] = Day(System.currentTimeMillis(), ev)
            return ev
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
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        return if (wa.containsAll(wb) || wb.containsAll(wa)) 1 else 0
    }

    private fun thumbOf(e: JSONObject): String =
        listOf("strThumb", "strPoster", "strSquare", "strBanner")
            .map { e.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    /** La partita che corrisponde meglio alle due squadre (vicina a oggi); null se non c'è. */
    private fun pick(events: List<JSONObject>, a: String, b: String): String? {
        val na = norm(a)
        val nb = norm(b)
        val today = LocalDate.now()
        var best: JSONObject? = null
        var bestScore = 0
        var bestDist = Long.MAX_VALUE
        for (e in events) {
            val h = norm(e.optString("strHomeTeam", ""))
            val aw = norm(e.optString("strAwayTeam", ""))
            val d1 = sc(h, na)
            val d2 = sc(aw, nb)
            val s1 = sc(h, nb)
            val s2 = sc(aw, na)
            val direct = if (d1 > 0 && d2 > 0) d1 + d2 else 0
            val swapped = if (s1 > 0 && s2 > 0) s1 + s2 else 0
            val score = maxOf(direct, swapped)
            if (score == 0) continue
            val dist = try {
                Math.abs(ChronoUnit.DAYS.between(today, LocalDate.parse(e.optString("dateEvent", ""))))
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            if (dist > WINDOW_DAYS) continue
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                best = e
                bestScore = score
                bestDist = dist
            }
        }
        return best?.let { thumbOf(it) }
    }

    private fun lookup(eventName: String): String {
        val (a, b) = teams(eventName) ?: return ""
        val today = LocalDate.now()
        // 1) partite di ieri/oggi/domani (poche richieste, uguali per tutte le card)
        val nearby = (-1L..1L).flatMap { day(today.plusDays(it)) }
        pick(nearby, a, b)?.takeIf { it.isNotEmpty() }?.let { return it }
        // 2) ricerca per nome, ma solo partite di questi giorni
        for (q in listOf("${a}_vs_${b}", "${b}_vs_${a}")) {
            val ev = get("searchevents.php?e=" + URLEncoder.encode(q.replace(' ', '_'), "UTF-8")) ?: continue
            pick(ev, a, b)?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    /**
     * Se TheSportsDB ha l'immagine della partita la mostra nell'ImageView.
     * onFound: viene chiamato prima di mostrarla (per adattare il layout); onFail: se l'immagine non si carica.
     */
    fun loadThumb(iv: ImageView, ch: Channel, onFound: (() -> Unit)? = null, onFail: (() -> Unit)? = null) {
        val k = "${ch.name}|${LocalDate.now()}"
        fun show(url: String) {
            if (url.isEmpty() || iv.tag != ch.url) return
            onFound?.invoke()
            iv.load(url) {
                crossfade(true)
                listener(onError = { _, _ ->
                    found.remove(k)
                    prefs?.edit()?.remove(k)?.apply()
                    if (iv.tag == ch.url) onFail?.invoke()
                })
            }
        }
        val known = found[k] ?: prefs?.getString(k, null)
        if (known != null) {
            found[k] = known
            show(known)
            return
        }
        val t = misses[k]
        if (t != null && System.currentTimeMillis() - t < MISS_RETRY_MS) return
        pool.execute {
            val url = try {
                lookup(ch.name)
            } catch (_: Exception) {
                ""
            }
            if (url.isNotEmpty()) {
                found[k] = url
                prefs?.edit()?.putString(k, url)?.apply()
            } else {
                misses[k] = System.currentTimeMillis()
            }
            main.post { show(url) }
        }
    }
}
