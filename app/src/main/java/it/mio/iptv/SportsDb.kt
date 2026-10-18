package it.mio.iptv

import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import coil.load
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Cerca su TheSportsDB l'immagine (thumb) di una partita a partire dal nome dell'evento
 * (es. "Udinese - Lecce") e la mostra al posto del logo del canale.
 */
object SportsDb {
    // Chiave gratuita di test di TheSportsDB (ha un limite di richieste: per questo c'è la cache)
    private const val KEY = "123"
    private const val BASE = "https://www.thesportsdb.com/api/v1/json/$KEY/searchevents.php?e="

    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** nome evento -> URL del thumb ("" = già cercato, non trovato) */
    private val cache = ConcurrentHashMap<String, String>()

    private val splitRe = Regex("""\s+(?:-|–|—|vs\.?|v)\s+""", RegexOption.IGNORE_CASE)
    private val timeRe = Regex("""\b\d{1,2}[:.]\d{2}\b""")
    private val bracketRe = Regex("""[(\[].*?[)\]]""")
    private val qualityRe = Regex("""\b(4K|FHD|HD|SD|LIVE)\b""", RegexOption.IGNORE_CASE)

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

    private fun fetch(query: String): List<JSONObject> = try {
        val url = URL(BASE + URLEncoder.encode(query, "UTF-8"))
        val c = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONObject(text).optJSONArray("event")
        if (arr == null) emptyList() else List(arr.length()) { arr.getJSONObject(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun thumbOf(e: JSONObject): String =
        listOf("strThumb", "strSquare", "strPoster", "strBanner")
            .map { e.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    /** Tra i risultati sceglie la partita con la data più vicina a oggi e con un'immagine. */
    private fun best(events: List<JSONObject>): String {
        val today = LocalDate.now()
        return events
            .filter { thumbOf(it).isNotEmpty() }
            .minByOrNull {
                try {
                    Math.abs(ChronoUnit.DAYS.between(today, LocalDate.parse(it.optString("dateEvent"))))
                } catch (_: Exception) {
                    Long.MAX_VALUE
                }
            }?.let { thumbOf(it) } ?: ""
    }

    private fun lookup(eventName: String): String {
        val (a, b) = teams(eventName) ?: return ""
        val q1 = "${a}_vs_${b}".replace(' ', '_')
        val q2 = "${b}_vs_${a}".replace(' ', '_')
        return best(fetch(q1)).ifEmpty { best(fetch(q2)) }
    }

    /** Se TheSportsDB ha il thumb della partita lo mostra nell'ImageView; altrimenti non cambia nulla. */
    fun loadThumb(iv: ImageView, ch: Channel, onFound: (() -> Unit)? = null) {
        val key = ch.name
        fun show(url: String) {
            if (url.isEmpty() || iv.tag != ch.url) return
            onFound?.invoke()
            iv.load(url) { crossfade(true) }
        }
        cache[key]?.let { show(it); return }
        pool.execute {
            val url = lookup(key)
            if (url.isNotEmpty()) cache[key] = url      // le mancate non si memorizzano: riprova la volta dopo
            main.post { show(url) }
        }
    }
}
