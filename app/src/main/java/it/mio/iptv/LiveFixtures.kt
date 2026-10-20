package it.mio.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Eventi live creati da soli, giornata per giornata, dai siti ufficiali:
 *  - Serie A: https://www.legaseriea.it/serie-a
 *  - Serie B: https://www.legab.it/seriebkt
 *  - Serie C: https://www.seriec.com/
 *
 * Una categoria di una sezione "Eventi live" il cui nome e' "Serie A", "Serie B" o "Serie C" si riempie da sola:
 * ogni partita diventa un evento "Casa - Ospite" con il suo orario. Per Serie A e B (e Serie C, se c'e') il canale
 * e' quello della playlist col nome della squadra di casa: FHD, oppure HD solo se l'FHD non c'e'.
 */
object LiveFixtures {
    private const val TAG = "Fixtures"   // diagnostica: Logcat, filtro "Fixtures"

    private const val URL_A = "https://www.legaseriea.it/serie-a/calendario-risultati"
    private const val URL_B = "https://www.legab.it/seriebkt"
    private const val URL_C = "https://www.seriec.com/"
    private const val URL_A_DB = "https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=4332"   // Serie A

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val REFRESH_MS = 5 * 60_000L
    private const val KEEP_MS = 36 * 3_600_000L

    private var prefs: SharedPreferences? = null
    private val lastFetch = HashMap<String, Long>()
    private val memory = HashMap<String, List<Fixture>>()

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("fixtures_v1", Context.MODE_PRIVATE)
    }

    /** "A", "B" o "C" se la categoria e' una di quelle automatiche, altrimenti null. */
    fun leagueOf(categoryName: String): String? {
        val n = FixtureParsers.fold(categoryName)
        return when {
            Regex("""\bserie\s*a\b""").containsMatchIn(n) -> "A"
            Regex("""\bserie\s*b(kt)?\b""").containsMatchIn(n) -> "B"
            Regex("""\bserie\s*c\b""").containsMatchIn(n) -> "C"
            else -> null
        }
    }

    fun isAuto(categoryName: String) = leagueOf(categoryName) != null

    // ---- rete ----

    private fun http(url: String): String? {
        val t0 = System.currentTimeMillis()
        for (attempt in 1..2) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept-Language", "it-IT,it;q=0.9")
                }
                try {
                    val text = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    Log.d(TAG, "GET $url -> ${c.responseCode}, ${text.length / 1024} KB, ${System.currentTimeMillis() - t0} ms")
                    return text
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.d(TAG, "GET $url ERRORE ${e.javaClass.simpleName} (tentativo $attempt)")
            }
        }
        return null
    }

    // ---- Serie A da TheSportsDB (se il sito della lega non da' le partite) ----

    private fun serieAFromSportsDb(): List<Fixture> {
        val txt = http(URL_A_DB) ?: return emptyList()
        return try {
            val arr = JSONObject(txt).optJSONArray("events") ?: return emptyList()
            val out = ArrayList<Fixture>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val home = o.optString("strHomeTeam", "")
                val away = o.optString("strAwayTeam", "")
                if (home.isEmpty() || away.isEmpty()) continue
                // gli orari di TheSportsDB sono in UTC
                val ts = o.optString("strTimestamp", "")
                val ms = try {
                    if (ts.isNotEmpty()) LocalDateTime.parse(ts.substringBefore('+').substringBefore('Z')).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
                    else LocalDateTime.parse(o.getString("dateEvent") + "T" + o.optString("strTime", "00:00:00").take(8))
                        .atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    continue
                }
                out.add(Fixture("A", o.optString("intRound", "0").toIntOrNull() ?: 0, home, away, ms))
            }
            out
        } catch (e: Exception) {
            Log.d(TAG, "Serie A: errore di lettura TheSportsDB ${e.message}")
            emptyList()
        }
    }

    // ---- ricordo delle partite gia' viste (restano anche quando il sito non le elenca piu') ----

    private fun toJson(l: List<Fixture>) = JSONArray().also { arr ->
        l.forEach {
            arr.put(JSONObject().put("r", it.round).put("h", it.home).put("a", it.away).put("s", it.start))
        }
    }

    private fun stored(league: String): List<Fixture> {
        memory[league]?.let { return it }
        val l = try {
            val arr = JSONArray(prefs?.getString("fx_$league", null) ?: "[]")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Fixture(league, o.optInt("r", 0), o.getString("h"), o.getString("a"), o.getLong("s"))
            }
        } catch (_: Exception) {
            emptyList()
        }
        memory[league] = l
        return l
    }

    private fun key(f: Fixture) = "${FixtureParsers.fold(f.home)}|${FixtureParsers.fold(f.away)}|${f.round}"

    private fun store(league: String, parsed: List<Fixture>) {
        val now = System.currentTimeMillis()
        // il sito elenca le partite future: quelle vecchie non piu' elencate restano solo se sono gia' iniziate
        val keepOld = stored(league).filter { it.start <= now && it.start > now - KEEP_MS }
        val map = LinkedHashMap<String, Fixture>()
        keepOld.forEach { map[key(it)] = it }
        parsed.forEach { map[key(it)] = it }
        val l = map.values.filter { it.start > now - KEEP_MS }
        memory[league] = l
        prefs?.edit()?.putString("fx_$league", toJson(l).toString())?.apply()
    }

    // ---- API ----

    /** Scarica (al massimo ogni 5 minuti) le partite dei tre campionati. Da chiamare su un thread a parte. */
    @Synchronized
    fun refresh(): Map<String, List<Fixture>> {
        val now = System.currentTimeMillis()
        val today = LocalDate.now(FixtureParsers.ROME)
        val sources = listOf(
            Triple<String, String, (String) -> List<Fixture>>("A", URL_A, { h -> FixtureParsers.parseSerieA(h, today) }),
            Triple<String, String, (String) -> List<Fixture>>("B", URL_B, { h -> FixtureParsers.parseSerieB(h) }),
            Triple<String, String, (String) -> List<Fixture>>("C", URL_C, { h -> FixtureParsers.parseSerieC(h, today) })
        )
        for ((league, url, parse) in sources) {
            if (now - (lastFetch[league] ?: 0L) < REFRESH_MS) continue
            val html = http(url)
            var parsed: List<Fixture> = emptyList()
            if (html != null) {
                parsed = try { parse(html) } catch (e: Exception) { Log.d(TAG, "Serie $league: errore di lettura ${e.message}"); emptyList() }
                Log.d(TAG, "Serie $league: ${parsed.size} partite lette da $url")
            }
            // La pagina della Serie A e' fatta in JavaScript e non sempre ha le partite nel testo: se non se ne legge
            // nessuna si prendono da TheSportsDB (calendario e orari della Serie A).
            if (league == "A" && parsed.isEmpty()) {
                parsed = serieAFromSportsDb()
                Log.d(TAG, "Serie A: ${parsed.size} partite lette da TheSportsDB")
            }
            if (html == null && parsed.isEmpty()) {
                lastFetch[league] = now - REFRESH_MS + 30_000L    // errore di rete: si riprova tra 30 secondi
                continue
            }
            lastFetch[league] = now
            if (parsed.isNotEmpty()) store(league, parsed)
        }
        return current()
    }

    /** Le partite della giornata in corso, da quanto e' gia' stato scaricato/ricordato (nessuna rete). */
    fun current(): Map<String, List<Fixture>> {
        val now = System.currentTimeMillis()
        return listOf("A", "B", "C").associateWith { FixtureParsers.pickCurrent(stored(it), now) }
    }

    // ---- canale della squadra ----

    private val stopWords = setOf("fc", "ac", "as", "ss", "ssc", "us", "afc", "cf", "sc", "bc", "ud", "cfc", "calcio", "football", "club", "hd", "fhd", "sd", "4k")
    private val youthRe = Regex("""\b(under|u23|u21|next|gen|primavera)\b""")

    private val aliases = mapOf("internazionale" to "inter", "juve" to "juventus")

    private fun words(s: String): List<String> =
        FixtureParsers.fold(s).replace(Regex("""[^a-z0-9]+"""), " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in stopWords }
            .map { aliases[it] ?: it }

    private fun qualityRank(q: String) = when (q.uppercase()) {
        "FHD" -> 0
        "HD" -> 1
        "4K" -> 2
        "" -> 3
        else -> 4
    }

    /**
     * Canale della playlist col nome della squadra. Prima il nome uguale, poi (se [partial]) quello che contiene
     * o e' contenuto (es. squadra "L.R. Vicenza", canale "Vicenza"). Tra i canali trovati: FHD, poi HD, poi gli altri.
     */
    fun findChannel(team: String, playlist: List<Channel>, partial: Boolean): Channel? {
        val tw = words(team)
        if (tw.isEmpty()) return null
        val joined = tw.joinToString("")
        val exact = playlist.filter { ch ->
            val cw = words(ch.name)
            cw.isNotEmpty() && (cw == tw || cw.joinToString("") == joined)
        }
        val pool = if (exact.isNotEmpty()) exact
        else if (partial && !youthRe.containsMatchIn(FixtureParsers.fold(team))) {
            val ts = tw.toSet()
            playlist.filter { ch ->
                val cs = words(ch.name).toSet()
                cs.isNotEmpty() && !youthRe.containsMatchIn(FixtureParsers.fold(ch.name)) &&
                    (cs.containsAll(ts) || ts.containsAll(cs)) && (cs intersect ts).any { it.length >= 4 }
            }
        } else emptyList()
        return pool.minByOrNull { qualityRank(it.quality) }   // minByOrNull tiene il primo in caso di parita'
    }

    /** Gli eventi (come Channel) di una categoria automatica; chi non ha un canale ha url vuoto. */
    fun events(league: String, fixtures: Map<String, List<Fixture>>, playlist: List<Channel>, category: String): List<Channel> =
        (fixtures[league] ?: emptyList()).map { f ->
            val ch = findChannel(f.home, playlist, partial = league != "C")
            val name = "${f.home} - ${f.away}"
            Channel(
                name = name,
                rawName = ch?.rawName ?: name,
                quality = ch?.quality ?: "",
                category = category,
                url = ch?.url ?: "",
                logos = ch?.logos ?: emptyList(),
                start = f.start
            )
        }
}
