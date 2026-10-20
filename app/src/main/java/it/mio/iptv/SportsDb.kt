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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Thumb e orari delle partite a partire dal nome dell'evento (es. "Udinese - Lecce").
 *
 *  - Stemmi delle squadre e logo del campionato: dal repo GitHub fp-pez8/pez8-images (index.json).
 *  - Data e orario di inizio: da Google (la pagina dei risultati di ricerca della partita).
 *    Google non ha un'API ufficiale per questo, quindi si legge il testo della pagina; le richieste
 *    sono una alla volta e, se Google blocca (429 / pagina di controllo), si aspetta 30 minuti.
 *  - Il risultato si ricorda (anche dopo la chiusura dell'app) per non rifare le richieste.
 *
 * prefetch() fa tutto in anticipo, all'apertura dell'app, per tutte le partite delle sezioni live.
 */
object SportsDb {
    private const val WINDOW_DAYS = 2L          // distanza massima (in giorni) della partita da oggi
    private const val MISS_RETRY_MS = 5 * 60_000L

    private const val TAG = "SportsDb"   // diagnostica: Logcat, filtro "SportsDb"
    private val pool = Executors.newFixedThreadPool(2)
    private val net = Executors.newCachedThreadPool()   // richieste di rete in parallelo (repo GitHub)
    private val main = Handler(Looper.getMainLooper())
    private var prefs: SharedPreferences? = null
    private var app: Context? = null

    /** Quello che si sa di una partita: orario di inizio in millisecondi (0 = sconosciuto) e stemmi dal repo. */
    private class Info(val thumb: String, val start: Long, val home: String = "", val away: String = "", val league: String = "")

    private val found = ConcurrentHashMap<String, Info>()      // "nome|data" -> info
    private val misses = ConcurrentHashMap<String, Long>()     // "nome|data" -> quando è fallita

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

    /** Da chiamare una volta all'avvio: serve a ricordare gli orari trovati anche dopo la chiusura dell'app. */
    fun init(ctx: Context) {
        // file nuovo: gli orari vecchi (di TheSportsDB) restano nel vecchio file e non si usano piu'
        val p = ctx.getSharedPreferences("orari_google", Context.MODE_PRIVATE)
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

    // ---- data e orario da Google ----

    private const val GOOGLE_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val googleLock = Any()
    @Volatile private var googleBlockedUntil = 0L
    private var lastGoogleAt = 0L

    // Una richiesta alla volta, con una pausa tra l'una e l'altra; se Google blocca si smette per 30 minuti.
    // fail[0] diventa true se la richiesta e' fallita (non va scambiata per "orario non trovato").
    private fun googleHtml(query: String, fail: BooleanArray): String? = synchronized(googleLock) {
        val now = System.currentTimeMillis()
        if (now < googleBlockedUntil) {
            fail[0] = true
            return null
        }
        val wait = 1200L - (now - lastGoogleAt)
        if (wait > 0) Thread.sleep(wait)
        lastGoogleAt = System.currentTimeMillis()
        val url = "https://www.google.com/search?hl=it&gl=it&q=" + URLEncoder.encode(query, "UTF-8")
        try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", GOOGLE_UA)
                setRequestProperty("Accept-Language", "it-IT,it;q=0.9")
                setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            }
            try {
                val code = c.responseCode
                val finalUrl = c.url.toString()
                if (code == 429 || finalUrl.contains("/sorry/") || finalUrl.contains("consent.google")) {
                    googleBlockedUntil = System.currentTimeMillis() + 30 * 60_000L
                    Log.d(TAG, "Google ha bloccato la richiesta (HTTP $code), pausa di 30 minuti")
                    fail[0] = true
                    return null
                }
                if (code !in 200..299) {
                    fail[0] = true
                    return null
                }
                c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Google ERRORE ${e.javaClass.simpleName}")
            fail[0] = true
            null
        }
    }

    private val scriptRe = Regex("""(?is)<(script|style)\b.*?</\1>""")
    private val tagRe = Regex("""(?s)<[^>]*>""")
    private val spaceRe = Regex("""\s+""")

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accentRe, "")

    // Testo visibile della pagina, in minuscolo e senza accenti
    private fun visibleText(html: String): String {
        var t = scriptRe.replace(html, " ")
        t = tagRe.replace(t, " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        return fold(spaceRe.replace(t, " "))
    }

    // "oggi 20:45", "domani 18:30", "sab 15:00", "27/10 20:45" ...
    private val whenRe = Regex(
        """\b(oggi|domani|dopodomani|ieri|lun|mar|mer|gio|ven|sab|dom|\d{1,2}/\d{1,2}(?:/\d{2,4})?)\b[.,]?\s*(?:ore\s*)?(\d{1,2})[:.](\d{2})\b"""
    )
    private val weekdays = mapOf(
        "lun" to DayOfWeek.MONDAY, "mar" to DayOfWeek.TUESDAY, "mer" to DayOfWeek.WEDNESDAY,
        "gio" to DayOfWeek.THURSDAY, "ven" to DayOfWeek.FRIDAY, "sab" to DayOfWeek.SATURDAY,
        "dom" to DayOfWeek.SUNDAY
    )

    private fun dateOf(w: String, today: LocalDate): LocalDate? {
        when (w) {
            "oggi" -> return today
            "domani" -> return today.plusDays(1)
            "dopodomani" -> return today.plusDays(2)
            "ieri" -> return today.minusDays(1)
        }
        weekdays[w]?.let { dow -> return (-1L..2L).map { today.plusDays(it) }.firstOrNull { it.dayOfWeek == dow } }
        return try {
            val p = w.split('/')
            val d = p[0].toInt()
            val m = p[1].toInt()
            if (p.size > 2) {
                val y = p[2].toInt().let { if (it < 100) 2000 + it else it }
                LocalDate.of(y, m, d)
            } else {
                // senza anno: quello (di quest'anno, il precedente o il prossimo) piu' vicino a oggi
                (-1..1).map { LocalDate.of(today.year + it, m, d) }
                    .minByOrNull { Math.abs(ChronoUnit.DAYS.between(today, it)) }
            }
        } catch (_: Exception) {
            null
        }
    }

    // Parola piu' lunga del nome di una squadra: basta per riconoscerla nel testo della pagina
    private fun keyToken(name: String): String =
        norm(name).split(' ').filter { it.length >= 3 }.maxByOrNull { it.length } ?: ""

    // Orario di inizio (ms) che compare vicino ai nomi delle due squadre; 0 se non si trova
    private fun googleStart(a: String, b: String, html: String): Long {
        val ka = keyToken(a)
        val kb = keyToken(b)
        if (ka.isEmpty() || kb.isEmpty()) return 0L
        val text = visibleText(html)
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        var best = 0L
        var bestDist = Long.MAX_VALUE
        for (m in whenRe.findAll(text)) {
            val from = (m.range.first - 400).coerceAtLeast(0)
            val to = (m.range.last + 400).coerceAtMost(text.length)
            val near = text.substring(from, to)
            if (!near.contains(ka) || !near.contains(kb)) continue
            val date = dateOf(m.groupValues[1], today) ?: continue
            val h = m.groupValues[2].toInt()
            val mi = m.groupValues[3].toInt()
            if (h > 23 || mi > 59) continue
            val dist = Math.abs(ChronoUnit.DAYS.between(today, date))
            if (dist > WINDOW_DAYS || dist >= bestDist) continue
            bestDist = dist
            best = date.atTime(h, mi).atZone(zone).toInstant().toEpochMilli()
        }
        return best
    }

    private fun lookup(eventName: String, fail: BooleanArray): Info? {
        val t0 = System.currentTimeMillis()
        val (a, b) = teams(eventName) ?: return null
        val html = googleHtml("$a - $b partita orario", fail) ?: return null
        val start = googleStart(a, b, html)
        Log.d(TAG, "'$eventName' -> Google, ${html.length / 1024} KB, ${System.currentTimeMillis() - t0} ms, orario=${start > 0}")
        return if (start > 0) Info("", start) else null
    }

    // Nella memoria del telefono: "orario<TAB>immagine<TAB>..." (i valori senza TAB si ignorano e si rifanno)
    private fun decode(v: String?): Info? {
        if (v == null || v.indexOf('\t') < 0) return null
        val p = v.split('\t')
        return Info(p[1], p[0].toLongOrNull() ?: 0L, p.getOrElse(2) { "" }, p.getOrElse(3) { "" }, p.getOrElse(4) { "" })
    }

    // ---- stemmi dal repo GitHub: github.com/fp-pez8/pez8-images ----
    // index.json = elenco dei campionati con le squadre e il file dello stemma di ognuna (cartella/squadre/Nome.png)

    private const val REPO = "https://raw.githubusercontent.com/fp-pez8/pez8-images/main/"

    private class RepoTeam(val n: String, val file: String, val league: String)
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
                        if (f.isNotEmpty()) list.add(RepoTeam(norm(t.optString("nome", "")), f, league))
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
        val info = Info("", 0L, home?.let { REPO + it.file } ?: "", away?.let { REPO + it.file } ?: "", REPO + league)
        repoCache[eventName] = info
        return info
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

    // Chi aspetta il risultato di una ricerca già partita (card e precaricamento non cercano due volte la stessa partita)
    private val inflight = HashMap<String, MutableList<(Info) -> Unit>>()

    // Gli stemmi sono nel repo GitHub; si scaricano gia' ridotti (BADGE_PX) cosi' pesano poco in memoria
    private const val BADGE_PX = 256
    private val warmed = ConcurrentHashMap.newKeySet<String>()

    private fun warmInfo(i: Info) {
        // niente da scaricare: gli stemmi si scaldano in prefetch()
    }

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
     * Da chiamare all'apertura dell'app: scarica in anticipo gli stemmi (e calcola i colori di sfondo)
     * e cerca l'orario di tutte le partite indicate.
     */
    fun prefetch(channels: List<Channel>) {
        val list = channels.distinctBy { it.name }
        list.forEach { ch ->
            repoBadges(ch.name) { r ->
                if (r != null) {
                    warmBadge(r.home); warmBadge(r.away); warmBadge(r.league)
                    app?.let { MatchThumb.preColor(it, r.home); MatchThumb.preColor(it, r.away) }
                }
            }
        }
        list.forEach { ch -> request(ch, "${ch.name}|${LocalDate.now()}", null) }
    }

    /**
     * Mostra le thumb ricreate con gli stemmi delle due squadre e il logo del campionato (dal repo GitHub pez8-images)
     * tramite onBadges, e comunica l'orario di inizio (da Google) tramite onStart sul thread principale.
     */
    fun loadThumb(
        iv: ImageView, ch: Channel,
        onFound: (() -> Unit)? = null, onFail: (() -> Unit)? = null, onStart: ((Long) -> Unit)? = null,
        onBadges: ((String, String, String) -> Unit)? = null
    ) {
        val k = "${ch.name}|${LocalDate.now()}"
        repoBadges(ch.name) { r ->
            if (r != null && iv.tag == ch.url && (r.home.isNotEmpty() || r.away.isNotEmpty())) onBadges?.invoke(r.home, r.away, r.league)
        }
        request(ch, k, { info -> if (iv.tag == ch.url && info.start > 0) onStart?.invoke(info.start) })
    }
}
