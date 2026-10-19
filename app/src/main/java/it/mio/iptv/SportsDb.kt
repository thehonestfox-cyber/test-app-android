package it.mio.iptv

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
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
 *  3. se non la trova guarda direttamente i campionati minori elencati in LEAGUE_IDS (Serie C):
 *     2 richieste a campionato (prossime + ultime partite), valide per tutte le card;
 *  4. se ancora niente prova la ricerca per nome, ma accetta solo partite di questi giorni
 *     (mai partite vecchie con lo stesso nome);
 *  5. ricorda il risultato (anche dopo la chiusura dell'app) per non rifare le richieste.
 *
 * prefetch() fa tutto questo in anticipo, all'apertura dell'app, per tutte le partite delle sezioni live.
 */
object SportsDb {
    // Chiave gratuita di test di TheSportsDB (ha un limite di richieste: per questo si usa la cache)
    private const val KEY = "123"
    private const val API = "https://www.thesportsdb.com/api/v1/json/$KEY/"
    private const val WINDOW_DAYS = 2L          // distanza massima (in giorni) della partita da oggi
    private const val MISS_RETRY_MS = 5 * 60_000L

    // Campionati su TheSportsDB da leggere per intero (si aggiungono qui altri, copiando il numero dal link
    // della pagina del campionato, es. thesportsdb.com/league/5340-...): Serie C girone A, B, C
    private val LEAGUE_IDS = listOf("5340", "5339", "4398")

    private const val TAG = "SportsDb"   // diagnostica: Logcat, filtro "SportsDb"
    private val pool = Executors.newFixedThreadPool(2)
    private val net = Executors.newCachedThreadPool()   // richieste di rete in parallelo (giorni, campionati)
    private val main = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var app: Context? = null

    /** Quello che si sa di una partita: immagine (può mancare) e orario di inizio in millisecondi (0 = sconosciuto). */
    private class Info(val thumb: String, val start: Long, val home: String = "", val away: String = "", val league: String = "")

    private val found = ConcurrentHashMap<String, Info>()      // "nome|data" -> info
    private val misses = ConcurrentHashMap<String, Long>()     // "nome|data" -> quando è fallita
    private class Day(val at: Long, val events: List<JSONObject>)
    private val days = ConcurrentHashMap<LocalDate, Day>()
    private val dayLocks = ConcurrentHashMap<LocalDate, Any>()

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
        app = ctx.applicationContext
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

    // Esegue [f] su ogni elemento in parallelo e restituisce i risultati nello stesso ordine
    private fun <A, B> pmap(items: List<A>, f: (A) -> B): List<B> =
        items.map { x -> net.submit(Callable { f(x) }) }.map { it.get() }

    // Immagini TheSportsDB: la versione /medium (500 px) pesa una frazione dell'originale e basta per la card
    private val imgExtRe = Regex("""\.(jpe?g|png)$""", RegexOption.IGNORE_CASE)
    private fun sized(url: String, size: String = "medium"): String {
        val u = url.trim()
        return if (u.contains("thesportsdb.com") && imgExtRe.containsMatchIn(u)) "$u/$size" else u
    }

    // Scarica un testo; se il server risponde "troppe richieste" (429) aspetta un attimo e riprova. null = errore di rete.
    private fun http(url: String): String? {
        val t0 = System.currentTimeMillis()
        val what = url.removePrefix(API)
        for (attempt in 0..2) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                try {
                    val code = c.responseCode
                    if (code == 429) {
                        Log.d(TAG, "HTTP 429 (troppe richieste) $what, attesa e nuovo tentativo ${attempt + 1}")
                        Thread.sleep(1500L * (attempt + 1))
                        continue
                    }
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "GET $what -> $code, ${text.length / 1024} KB, ${System.currentTimeMillis() - t0} ms")
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

    // fail[0] diventa true se la richiesta è fallita per un errore di rete (non va scambiata per "partita non trovata")
    private fun get(path: String, fail: BooleanArray? = null): List<JSONObject>? {
        val text = http(API + path)
        if (text == null) {
            fail?.set(0, true)
            return null   // errore di rete: non va messo in cache
        }
        return try {
            val j = JSONObject(text)
            val arr = j.optJSONArray("events") ?: j.optJSONArray("event") ?: j.optJSONArray("results")
            if (arr == null) emptyList() else List(arr.length()) { arr.getJSONObject(it) }
        } catch (_: Exception) {
            fail?.set(0, true)
            null
        }
    }

    // ---- elenco su GitHub: thumb gia' scaricate (cartella thumbs/ del repo, creata con tools/scarica_thumb.py) ----

    private const val REPO_THUMBS = "https://raw.githubusercontent.com/thehonestfox-cyber/test-app-android/main/thumbs/"

    private class Entry(val h: String, val a: String, val d: String, val t: Long, val file: String, val hb: String, val ab: String, val lb: String)
    private class RepoData(val at: Long, val items: List<Entry>)
    @Volatile private var repo: RepoData? = null
    @Volatile private var repoFailAt = 0L
    private val repoLock = Any()

    // Scarica l'indice una volta (poi resta in memoria 15 minuti). Una sola richiesta, non conta nel limite di TheSportsDB.
    private fun repoItems(fail: BooleanArray): List<Entry> {
        fun valid(r: RepoData?) = r != null && System.currentTimeMillis() - r.at < 15 * 60_000L
        repo?.let { if (valid(it)) return it.items }
        synchronized(repoLock) {
            repo?.let { if (valid(it)) return it.items }
            if (System.currentTimeMillis() - repoFailAt < 60_000L) return repo?.items ?: emptyList()
            val text = http(REPO_THUMBS + "index.json")
            if (text == null) {
                repoFailAt = System.currentTimeMillis()
                return repo?.items ?: emptyList()      // niente rete / file non ancora caricato: si usa il metodo normale
            }
            return try {
                val arr = JSONArray(text)
                val list = List(arr.length()) {
                    val o = arr.getJSONObject(it)
                    Entry(o.optString("h", ""), o.optString("a", ""), o.optString("d", ""), o.optLong("t", 0L),
                        o.optString("f", ""), o.optString("hb", ""), o.optString("ab", ""), o.optString("lb", ""))
                }
                repo = RepoData(System.currentTimeMillis(), list)
                list
            } catch (_: Exception) {
                repoFailAt = System.currentTimeMillis()
                emptyList()
            }
        }
    }

    // La partita dell'elenco su GitHub che corrisponde alle due squadre (vicina a oggi, anche con casa/trasferta invertiti)
    private fun repoPick(items: List<Entry>, a: String, b: String): Info? {
        val na = norm(a)
        val nb = norm(b)
        val today = LocalDate.now()
        var best: Entry? = null
        var bestScore = 0
        var bestDist = Long.MAX_VALUE
        for (e in items) {
            val h = norm(e.h)
            val aw = norm(e.a)
            val d1 = sc(h, na); val d2 = sc(aw, nb)
            val s1 = sc(h, nb); val s2 = sc(aw, na)
            val score = maxOf(if (d1 > 0 && d2 > 0) d1 + d2 else 0, if (s1 > 0 && s2 > 0) s1 + s2 else 0)
            if (score == 0) continue
            val dist = try {
                Math.abs(ChronoUnit.DAYS.between(today, LocalDate.parse(e.d)))
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
            if (dist > WINDOW_DAYS) continue
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                best = e; bestScore = score; bestDist = dist
            }
        }
        return best?.let { Info(if (it.file.isNotEmpty()) REPO_THUMBS + it.file else "", it.t, it.hb, it.ab, it.lb) }
    }

    // ---- campionati interi (es. Serie C): prossime + ultime partite, in cache per tutte le card ----

    private class LeagueData(val at: Long, val events: List<JSONObject>)
    private val leagues = ConcurrentHashMap<String, LeagueData>()
    private val leagueFailAt = ConcurrentHashMap<String, Long>()
    private val leagueLocks = ConcurrentHashMap<String, Any>()

    private fun leagueEvents(id: String, fail: BooleanArray): List<JSONObject> {
        fun valid(l: LeagueData) = System.currentTimeMillis() - l.at < 10 * 60_000L
        leagues[id]?.let { if (valid(it)) return it.events }
        synchronized(leagueLocks.getOrPut(id) { Any() }) {      // una sola richiesta alla volta per campionato
            leagues[id]?.let { if (valid(it)) return it.events }
            val old = leagues[id]?.events ?: emptyList()
            val failedAt = leagueFailAt[id]
            if (failedAt != null && System.currentTimeMillis() - failedAt < 60_000L) {   // appena fallito: non insistere
                fail[0] = true
                return old
            }
            // prossime e ultime partite scaricate insieme
            val nf = net.submit(Callable { get("eventsnextleague.php?id=$id", fail) })
            val pf = net.submit(Callable { get("eventspastleague.php?id=$id", fail) })
            val next = nf.get()
            val past = pf.get()
            val ev = (next ?: emptyList()) + (past ?: emptyList())
            Log.d(TAG, "campionato $id: prossime=${next?.size} ultime=${past?.size} (null = errore)")
            if (next == null || past == null) {
                // elenco incompleto (errore o limite di richieste): lo si usa ma non si tiene in cache, e si riprova tra poco
                leagueFailAt[id] = System.currentTimeMillis()
                fail[0] = true
                return if (ev.isEmpty()) old else ev
            }
            leagues[id] = LeagueData(System.currentTimeMillis(), ev)
            leagueFailAt.remove(id)
            return ev
        }
    }

    // ---- squadre: per le partite che non stanno nell'elenco del giorno (es. Serie C) ----

    private val teamIds = ConcurrentHashMap<String, String>()   // nome normalizzato -> idTeam ("" = non trovata)

    private fun teamId(name: String, fail: BooleanArray): String? {
        val key = norm(name)
        if (key.isEmpty()) return null
        teamIds[key]?.let { return it.ifEmpty { null } }
        val text = http(API + "searchteams.php?t=" + URLEncoder.encode(name, "UTF-8"))
        if (text == null) {
            fail[0] = true   // errore di rete: si riprova la prossima volta
            return null
        }
        return try {
            val arr = JSONObject(text).optJSONArray("teams")
            var best = ""
            var bestScore = 0
            if (arr != null) for (i in 0 until arr.length()) {
                val t = arr.getJSONObject(i)
                if (!t.optString("strSport", "").equals("Soccer", ignoreCase = true)) continue
                var s = sc(norm(t.optString("strTeam", "")), key)
                if (s == 0) continue
                if (t.optString("strCountry", "").equals("Italy", ignoreCase = true)) s += 1   // meglio le squadre italiane
                if (s > bestScore) {
                    best = t.optString("idTeam", "")
                    bestScore = s
                }
            }
            teamIds[key] = best
            best.ifEmpty { null }
        } catch (_: Exception) {
            fail[0] = true
            null
        }
    }

    private fun fresh(d: Day) =
        System.currentTimeMillis() - d.at < (if (d.events.isEmpty()) 2 else 10) * 60_000L

    /** Tutte le partite di calcio di un giorno (una sola richiesta, poi cache). */
    private fun day(d: LocalDate): List<JSONObject> {
        days[d]?.let { if (fresh(it)) return it.events }
        synchronized(dayLocks.getOrPut(d) { Any() }) {
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
        if (a.replace(" ", "") == b.replace(" ", "")) return 2   // "ostia mare" = "ostiamare"
        val wa = a.split(' ').toSet()
        val wb = b.split(' ').toSet()
        return if (wa.containsAll(wb) || wb.containsAll(wa)) 1 else 0
    }

    private fun thumbOf(e: JSONObject): String =
        listOf("strThumb", "strPoster", "strSquare", "strBanner")
            .map { e.optString(it, "") }
            .firstOrNull { it.isNotBlank() && it != "null" } ?: ""

    private fun badgeOf(e: JSONObject, key: String): String =
        e.optString(key, "").let { if (it.isBlank() || it == "null") "" else it }

    // Orario di inizio: TheSportsDB lo dà in UTC (strTimestamp, oppure dateEvent + strTime)
    private fun startOf(e: JSONObject): Long {
        return try {
            val ts = e.optString("strTimestamp", "")
            val iso = if (ts.length >= 19 && ts != "null") ts.take(19).replace(' ', 'T')
            else {
                val d = e.optString("dateEvent", "")
                val tm = e.optString("strTime", "")
                if (d.length < 10 || tm.length < 8 || tm.startsWith("00:00:00")) return 0L
                d.take(10) + "T" + tm.take(8)
            }
            LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    /** La partita che corrisponde meglio alle due squadre (vicina a oggi); null se non c'è. */
    private fun pick(events: List<JSONObject>, a: String, b: String): Info? {
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
        return best?.let { Info(thumbOf(it), startOf(it), badgeOf(it, "strHomeTeamBadge"), badgeOf(it, "strAwayTeamBadge"), badgeOf(it, "strLeagueBadge")) }
    }

    private fun lookup(eventName: String, fail: BooleanArray): Info? {
        val t0 = System.currentTimeMillis()
        fun res(via: String, i: Info?): Info? {
            Log.d(TAG, "'$eventName' -> $via, ${System.currentTimeMillis() - t0} ms, thumb=${i?.thumb?.isNotEmpty()}, orario=${i?.start?.let { it > 0 }}, errori=${fail[0]}")
            return i
        }
        val (a, b) = teams(eventName) ?: return res("non è una partita", null)
        val today = LocalDate.now()
        // 0) elenco gia' pronto su GitHub (thumb scaricate in anticipo): una sola richiesta, immediato
        run {
            val r = repoPick(repoItems(fail), a, b)
            if (r != null && (r.thumb.isNotEmpty() || r.home.isNotEmpty() || r.away.isNotEmpty())) return res("elenco GitHub", r)
        }
        // 1) partite di ieri/oggi/domani (poche richieste, uguali per tutte le card)
        val nearby = pmap((-1L..1L).toList()) { day(today.plusDays(it)) }.flatten()   // i 3 giorni insieme
        if ((-1L..1L).any { days[today.plusDays(it)] == null }) fail[0] = true
        pick(nearby, a, b)?.let { return res("elenco del giorno", it) }
        // 2) ricerca per nome dell'evento (es. "Pescara vs Ostiamare", anche invertito) e gironi di Serie C,
        //    tutto insieme in un solo giro di richieste; il nome dell'evento ha la precedenza
        val searches = listOf("${a}_vs_${b}", "${b}_vs_${a}").map { q ->
            net.submit(Callable { get("searchevents.php?e=" + URLEncoder.encode(q.replace(' ', '_'), "UTF-8"), fail) })
        }
        val leagueJobs = LEAGUE_IDS.map { id -> net.submit(Callable { leagueEvents(id, fail) }) }
        for (j in searches) {
            val ev = j.get() ?: continue
            pick(ev, a, b)?.let { return res("ricerca per nome", it) }
        }
        for ((i, j) in leagueJobs.withIndex()) pick(j.get(), a, b)?.let { return res("campionato ${LEAGUE_IDS[i]}", it) }
        // 4) prossime e ultime partite di ciascuna squadra
        for (name in listOf(a, b)) {
            val id = teamId(name, fail) ?: continue
            val ev = (get("eventsnext.php?id=$id", fail) ?: emptyList()) + (get("eventslast.php?id=$id", fail) ?: emptyList())
            pick(ev, a, b)?.let { return res("squadra $name", it) }
        }
        return res("NON TROVATA", null)
    }

    // Nella memoria del telefono: "orario<TAB>immagine" (i vecchi valori con solo l'immagine si ignorano e si rifanno)
    private fun decode(v: String?): Info? {
        if (v == null || v.indexOf('\t') < 0) return null
        val p = v.split('\t')
        return Info(p[1], p[0].toLongOrNull() ?: 0L, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "" })
    }

    // Chi aspetta il risultato di una ricerca già partita (card e precaricamento non cercano due volte la stessa partita)
    private val inflight = HashMap<String, MutableList<(Info) -> Unit>>()

    private fun warm(url: String) {
        val a = app ?: return
        if (url.isNotEmpty()) a.imageLoader.enqueue(ImageRequest.Builder(a).data(sized(url)).build())   // scarica l'immagine in anticipo
    }

    // Usa le thumb ricreate dagli stemmi (true) oppure l'immagine originale della partita quando c'e' (false)
    private const val COMPOSED = true

    private fun composed(i: Info) = COMPOSED && i.home.isNotEmpty() && i.away.isNotEmpty()

    private fun warmInfo(i: Info) {
        if (composed(i)) {
            warmBadge(i.home); warmBadge(i.away); warmBadge(i.league)
        } else {
            warm(i.thumb); warmBadge(i.home); warmBadge(i.away)
        }
    }

    private fun warmBadge(url: String) {
        val a = app ?: return
        if (url.isNotEmpty()) a.imageLoader.enqueue(ImageRequest.Builder(a).data(sized(url, "small")).build())
    }

    /** Stemma di una squadra (versione ridotta) in un ImageView. */
    fun loadBadge(iv: ImageView, url: String) {
        iv.load(badgeUrl(url))
    }

    fun badgeUrl(url: String): String = sized(url, "small")

    /** Dà l'info (se la trova) a [cb] sul thread principale; senza [cb] serve solo a riscaldare cache e immagine. */
    private fun request(ch: Channel, k: String, cb: ((Info) -> Unit)?, attempt: Int = 0) {
        val known = found[k] ?: decode(prefs?.getString(k, null))
        if (known != null) {
            found[k] = known
            if (cb != null) cb(known) else {
                warmInfo(known)
            }
            return
        }
        val t = misses[k]
        if (t != null && System.currentTimeMillis() - t < MISS_RETRY_MS) {
            // attesa breve = la ricerca era fallita per un errore: la card riprova da sola appena scade
            val wait = MISS_RETRY_MS - (System.currentTimeMillis() - t)
            if (cb != null && wait <= 25_000L && attempt < 4) main.postDelayed({ request(ch, k, cb, attempt + 1) }, wait + 500)
            return
        }
        synchronized(inflight) {
            val waiting = inflight[k]
            if (waiting != null) {
                if (cb != null) waiting.add(cb)
                return
            }
            inflight[k] = mutableListOf<(Info) -> Unit>().also { if (cb != null) it.add(cb) }
        }
        val queuedAt = System.currentTimeMillis()
        pool.execute {
            Log.d(TAG, "'${ch.name}' avvio ricerca (in coda da ${System.currentTimeMillis() - queuedAt} ms)")
            val fail = BooleanArray(1)
            val info = try {
                lookup(ch.name, fail)
            } catch (_: Exception) {
                fail[0] = true
                null
            }
            if (info != null) {
                found[k] = info
                prefs?.edit()?.putString(k, "${info.start}\t${info.thumb}\t${info.home}\t${info.away}\t${info.league}")?.apply()
                warmInfo(info)
            } else {
                // errore di rete / limite di richieste: si riprova tra 20 secondi invece che tra 5 minuti
                misses[k] = if (fail[0]) System.currentTimeMillis() - MISS_RETRY_MS + 20_000L else System.currentTimeMillis()
            }
            val waiting = synchronized(inflight) { inflight.remove(k) }.orEmpty()
            if (info != null && waiting.isNotEmpty()) main.post { waiting.forEach { it(info) } }
            if (info == null && fail[0] && attempt < 4 && waiting.isNotEmpty()) {
                main.postDelayed({ waiting.forEach { w -> request(ch, k, w, attempt + 1) } }, 21_000L)   // riprova dopo l'errore
            }
        }
    }

    /**
     * Da chiamare all'apertura dell'app: cerca in anticipo thumb e orario di tutte le partite indicate
     * (con gli stessi 2 canali di rete di prima, una partita alla volta) e scarica le immagini.
     */
    fun prefetch(channels: List<Channel>) {
        // Serie C: giorni e gironi si scaricano subito, tutti insieme, mentre partono le ricerche delle singole partite
        net.execute {
            try {
                val fail = BooleanArray(1)
                val today = LocalDate.now()
                val tasks = (-1L..1L).map { n -> net.submit(Callable { day(today.plusDays(n)) }) } +
                    LEAGUE_IDS.map { id -> net.submit(Callable { leagueEvents(id, fail) }) }
                tasks.forEach { it.get() }
            } catch (_: Exception) {
            }
        }
        channels.distinctBy { it.name }.forEach { ch -> request(ch, "${ch.name}|${LocalDate.now()}", null) }
    }

    /**
     * Se TheSportsDB conosce la partita: mostra l'immagine nell'ImageView e comunica l'orario di inizio.
     * onFound: viene chiamato prima di mostrare l'immagine (per adattare il layout); onFail: se l'immagine non si carica;
     * onStart: orario di inizio in millisecondi (chiamato sul thread principale).
     */
    fun loadThumb(
        iv: ImageView, ch: Channel,
        onFound: (() -> Unit)? = null, onFail: (() -> Unit)? = null, onStart: ((Long) -> Unit)? = null,
        onBadges: ((String, String, String) -> Unit)? = null
    ) {
        val k = "${ch.name}|${LocalDate.now()}"
        fun show(info: Info) {
            if (iv.tag != ch.url) return
            if (info.start > 0) onStart?.invoke(info.start)
            if (composed(info) || info.thumb.isEmpty()) {
                // thumb ricreata con stemmi delle squadre e logo del campionato (anche quando manca l'immagine della partita)
                if (info.home.isNotEmpty() || info.away.isNotEmpty()) onBadges?.invoke(info.home, info.away, info.league)
                return
            }
            onFound?.invoke()
            fun bad() {
                found.remove(k)
                prefs?.edit()?.remove(k)?.apply()
                if (iv.tag == ch.url) onFail?.invoke()
            }
            val small = sized(info.thumb)
            iv.load(small) {
                listener(onError = { _, _ ->
                    if (small != info.thumb && iv.tag == ch.url) {
                        // la versione ridotta non esiste: si prova l'immagine originale
                        iv.load(info.thumb) { listener(onError = { _, _ -> bad() }) }
                    } else bad()
                })
            }
        }
        request(ch, k, { info -> show(info) })
    }
}
