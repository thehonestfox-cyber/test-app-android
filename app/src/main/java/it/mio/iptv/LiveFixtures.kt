package it.mio.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Eventi live creati da soli, giornata per giornata. I siti non si leggono piu' nell'app: un job di GitHub Actions
 * (scripts/aggiorna_eventi.py, ogni 10 minuti) li legge e pubblica eventi.json; l'app scarica solo quello.
 *  - Serie A: sport.sky.it, Serie B: legab.it, Serie C: seriec.com + guida Sky (canali)
 *
 * Una categoria di una sezione "Eventi live" il cui nome e' "Serie A", "Serie B" o "Serie C" si riempie da sola:
 * ogni partita diventa un evento "Casa - Ospite" con il suo orario. Per Serie A e B il canale e' quello della
 * playlist col nome della squadra di casa: FHD, oppure HD solo se l'FHD non c'e'.
 * Per la Serie C il canale e' quello che Sky indica per la partita (Sky Sport 251, 252...) se c'e' nella playlist;
 * altrimenti quello della squadra.
 *
 * L'admin puo' modificare (nome, canale) o rimuovere ogni evento automatico: le scelte si ricordano per partita
 * (chiave "casa|ospite|giornata") e restano anche quando l'elenco si aggiorna da solo.
 */
object LiveFixtures {
    private const val TAG = "Fixtures"   // diagnostica: Logcat, filtro "Fixtures"

    private const val URL_DATA =
        "https://raw.githubusercontent.com/thehonestfox-cyber/test-app-android/main/eventi.json"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val REFRESH_MS = 5 * 60_000L
    private const val KEEP_MS = 5 * 86_400_000L      // la giornata dura dal venerdi al lunedi: le partite gia' iniziate restano 5 giorni

    // guida Sky alla Serie C: quanto resta valida
    private const val SKY_VALID_MS = 10 * 86_400_000L     // una guida piu' vecchia di cosi' non si usa piu'
    private const val MEMO_MS = 60L * 86_400_000L          // modifiche/rimozioni dell'admin: si dimenticano dopo 60 giorni

    private var prefs: SharedPreferences? = null
    private val lastFetch = HashMap<String, Long>()
    private val memory = HashMap<String, List<Fixture>>()
    private var sky: List<SkyMatch>? = null

    /** Cosa e' successo all'ultimo aggiornamento di ogni campionato (si vede nell'Admin quando non ci sono eventi). */
    val diag = java.util.concurrent.ConcurrentHashMap<String, String>()

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

    /** Scarica (al massimo ogni 5 minuti) eventi.json con le partite dei tre campionati. Da chiamare su un thread a parte. */
    @Synchronized
    fun refresh(): Map<String, List<Fixture>> {
        val now = System.currentTimeMillis()
        if (now - (lastFetch["DATA"] ?: 0L) >= REFRESH_MS) {
            val text = http("$URL_DATA?t=${now / 60_000L}")   // il parametro evita la copia vecchia in cache di GitHub
            if (text == null) {
                lastFetch["DATA"] = now - REFRESH_MS + 10_000L    // errore di rete: si riprova tra 10 secondi
                for (l in listOf("A", "B", "C")) diag[l] = "eventi.json: errore di rete"
            } else {
                lastFetch["DATA"] = now
                try {
                    applyData(JSONObject(text), now)
                } catch (e: Exception) {
                    Log.d(TAG, "eventi.json: errore di lettura ${e.message}")
                    for (l in listOf("A", "B", "C")) diag[l] = "eventi.json: formato non valido"
                }
            }
        }
        return current()
    }

    /** Mette nel ricordo dell'app le partite e la guida Sky lette dal file. */
    private fun applyData(o: JSONObject, now: Long) {
        val fx = o.optJSONObject("fixtures")
        val stato = o.optJSONObject("stato")
        for (league in listOf("A", "B", "C")) {
            val arr = fx?.optJSONArray(league)
            val parsed = ArrayList<Fixture>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val f = arr.optJSONObject(i) ?: continue
                    val h = f.optString("h", "")
                    val a = f.optString("a", "")
                    if (h.isEmpty() || a.isEmpty() || !f.has("s")) continue
                    parsed.add(Fixture(league, f.optInt("r", 0), h, a, f.optLong("s")))
                }
            }
            diag[league] = "eventi.json: ${stato?.optString(league, "ok") ?: "ok"} (${parsed.size} partite)"
            Log.d(TAG, "Serie $league: ${parsed.size} partite da eventi.json")
            if (parsed.isNotEmpty()) store(league, parsed)
        }
        val skyArr = o.optJSONArray("sky")
        if (skyArr != null && skyArr.length() > 0) {
            val l = ArrayList<SkyMatch>()
            for (i in 0 until skyArr.length()) {
                val m = skyArr.optJSONObject(i) ?: continue
                val c = m.optJSONArray("c") ?: continue
                val t = m.optString("t", "")
                if (t.isEmpty()) continue
                l.add(SkyMatch(t, List(c.length()) { k -> c.getString(k) }))
            }
            if (l.isNotEmpty()) {
                sky = l
                prefs?.edit()?.putString("sky_c", skyToJson(l).toString())
                    ?.putLong("sky_c_at", o.optLong("sky_at", now))?.apply()
            }
        }
    }

    /** Le partite della giornata in corso, da quanto e' gia' stato scaricato/ricordato (nessuna rete). */
    fun current(): Map<String, List<Fixture>> {
        val now = System.currentTimeMillis()
        return listOf("A", "B", "C").associateWith { FixtureParsers.pickCurrent(stored(it), now) }
    }

    // ---- guida Sky alla Serie C: su quale canale Sky si vede ogni partita ----

    private fun skyToJson(l: List<SkyMatch>) = JSONArray().also { arr ->
        l.forEach { arr.put(JSONObject().put("t", it.teams).put("c", JSONArray(it.channels))) }
    }

    private fun skyStored(): List<SkyMatch> {
        sky?.let { return it }
        val fresh = System.currentTimeMillis() - (prefs?.getLong("sky_c_at", 0L) ?: 0L) < SKY_VALID_MS
        val l: List<SkyMatch> = try {
            if (!fresh) emptyList()
            else {
                val arr = JSONArray(prefs?.getString("sky_c", null) ?: "[]")
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val c = o.getJSONArray("c")
                    SkyMatch(o.getString("t"), List(c.length()) { k -> c.getString(k) })
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        sky = l
        return l
    }

    // "Under 23" e "U23", "Next Gen" e "NextGen" sono la stessa cosa
    private val underRe = Regex("""\bunder\s*(\d{2})\b""")
    private val nextGenRe = Regex("""\bnext\s*gen(?:eration)?\b""")
    private val youthTok = Regex("""u\d{2}|nextgen|primavera""")
    private val numberedRe = Regex("""\d{3}$""")
    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val qualityTok = setOf("fhd", "hd", "sd", "uhd", "4k", "hevc", "h265", "it", "ita")

    private fun teamWords(s: String): Set<String> {
        var t = FixtureParsers.fold(s)
        t = underRe.replace(t) { "u" + it.groupValues[1] }
        t = nextGenRe.replace(t, "nextgen")
        return words(t).toSet()
    }

    /** Stessa squadra anche se scritta piu' corta o piu' lunga ("Ospitaletto" / "Ospitaletto Franciacorta"); le seconde squadre non si confondono con le prime. */
    private fun sameTeam(a: String, b: String): Boolean {
        val ta = teamWords(a)
        val tb = teamWords(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        if (ta.any { youthTok.matches(it) } != tb.any { youthTok.matches(it) }) return false
        if (!(ta.containsAll(tb) || tb.containsAll(ta))) return false
        return (ta intersect tb).any { it.length >= 4 || youthTok.matches(it) }
    }

    /** I canali Sky indicati per la partita (nell'ordine di Sky), o vuoto se la guida non la elenca. */
    fun skyChannelNames(f: Fixture): List<String> {
        for (m in skyStored()) {
            val t = m.teams
            for (i in t.indices) {
                if (t[i] != '-' && t[i] != '\u2013') continue
                if (sameTeam(f.home, t.substring(0, i)) && sameTeam(f.away, t.substring(i + 1))) return m.channels
            }
        }
        return emptyList()
    }

    /** Nome del canale senza qualita'/lingua e con "Uno" = "1", per confrontare "Sky Sport 251" con "SKY SPORT 251 FHD". */
    private fun skyKey(s: String): String =
        FixtureParsers.fold(s).replace(nonAlnumRe, " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in qualityTok }
            .map { if (it == "uno") "1" else it }
            .joinToString(" ")

    /** Canali Sky della playlist per nome (FHD, o HD se manca l'FHD). */
    private fun skyIndex(playlist: List<Channel>): Map<String, Channel> =
        playlist.filter { FixtureParsers.fold(it.name).contains("sky") }
            .groupBy { skyKey(it.name) }
            .mapValues { e -> e.value.minByOrNull { qualityRank(it.quality) }!! }

    /** Il canale Sky della partita che c'e' nella playlist: prima i numerati (251, 252... dedicati alla Serie C), poi gli altri. */
    private fun skyChannel(f: Fixture, idx: Map<String, Channel>): Channel? {
        if (idx.isEmpty()) return null
        val names = skyChannelNames(f)
        if (names.isEmpty()) return null
        for (n in names.sortedBy { if (numberedRe.containsMatchIn(it)) 0 else 1 }) {
            idx[skyKey(n)]?.let { return it }
        }
        return null
    }

    // ---- modifiche e rimozioni dell'admin sugli eventi automatici (per partita) ----

    private fun memo(prefKey: String): JSONObject {
        val o = try { JSONObject(prefs?.getString(prefKey, null) ?: "{}") } catch (_: Exception) { JSONObject() }
        val cut = System.currentTimeMillis() - MEMO_MS
        val old = o.keys().asSequence().filter { (o.optJSONObject(it)?.optLong("t", 0L) ?: 0L) < cut }.toList()
        old.forEach { o.remove(it) }
        return o
    }

    private fun saveMemo(prefKey: String, o: JSONObject) {
        prefs?.edit()?.putString(prefKey, o.toString())?.apply()
    }

    /** Toglie dall'elenco la partita (anche dopo gli aggiornamenti). */
    fun removeEvent(league: String, evKey: String) {
        val o = memo("del_$league")
        o.put(evKey, JSONObject().put("t", System.currentTimeMillis()))
        saveMemo("del_$league", o)
    }

    /** Quante partite tolte ci sono ancora tra quelle note. */
    fun removedCount(league: String): Int {
        val valid = stored(league).map { key(it) }.toSet()
        val o = memo("del_$league")
        return o.keys().asSequence().count { it in valid }
    }

    /** Rimette tutte le partite tolte. */
    fun restoreRemoved(league: String) {
        prefs?.edit()?.remove("del_$league")?.apply()
    }

    fun hasOverride(league: String, evKey: String): Boolean = memo("ovr_$league").has(evKey)

    /** Ricorda nome e/o canale scelti per la partita; null = quella parte resta automatica (o com'era gia' stata scelta). */
    fun editEvent(league: String, evKey: String, newName: String?, picked: Channel?) {
        val all = memo("ovr_$league")
        val o = all.optJSONObject(evKey) ?: JSONObject()
        if (newName != null) o.put("n", newName)
        if (picked != null) {
            o.put("u", picked.url).put("rn", picked.rawName).put("q", picked.quality).put("l", JSONArray(picked.logos))
        }
        o.put("t", System.currentTimeMillis())
        all.put(evKey, o)
        saveMemo("ovr_$league", all)
    }

    /** La partita torna com'e' generata da sola. */
    fun resetEvent(league: String, evKey: String) {
        val all = memo("ovr_$league")
        all.remove(evKey)
        saveMemo("ovr_$league", all)
    }

    private fun applyOverride(ch: Channel, o: JSONObject?): Channel {
        if (o == null) return ch
        val n = o.optString("n", "").ifEmpty { ch.name }
        if (!o.has("u")) return ch.copy(name = n)
        val lg = o.optJSONArray("l") ?: JSONArray()
        return ch.copy(
            name = n,
            rawName = o.optString("rn", ch.rawName),
            quality = o.optString("q", ch.quality),
            url = o.getString("u"),
            logos = List(lg.length()) { lg.getString(it) }
        )
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

    /**
     * Gli eventi (come Channel) di una categoria automatica; chi non ha un canale ha url vuoto.
     * Serie C: prima il canale Sky indicato da Sky per la partita, poi quello della squadra di casa.
     * Le partite tolte dall'admin non ci sono; quelle modificate hanno il nome/canale scelto.
     */
    fun events(league: String, fixtures: Map<String, List<Fixture>>, playlist: List<Channel>, category: String): List<Channel> {
        val removed = memo("del_$league")
        val edits = memo("ovr_$league")
        val skyIdx = if (league == "C") skyIndex(playlist) else emptyMap()
        return (fixtures[league] ?: emptyList()).filter { !removed.has(key(it)) }.map { f ->
            val ch = (if (league == "C") skyChannel(f, skyIdx) else null)
                ?: findChannel(f.home, playlist, partial = league != "C")
            // nomi ESATTAMENTE come nel repo degli stemmi (pez8-images); se la squadra non c'e' resta il nome della fonte
            val home = SportsDb.canonicalName(f.home, league) ?: f.home
            val away = SportsDb.canonicalName(f.away, league) ?: f.away
            val name = "$home - $away"
            val base = Channel(
                name = name,
                rawName = ch?.rawName ?: name,
                quality = ch?.quality ?: "",
                category = category,
                url = ch?.url ?: "",
                logos = ch?.logos ?: emptyList(),
                start = f.start,
                evKey = key(f)
            )
            applyOverride(base, edits.optJSONObject(key(f)))
        }
    }
}
