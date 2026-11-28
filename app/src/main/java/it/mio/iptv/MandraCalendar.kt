package it.mio.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Il calendario multisport di MandraKodi: tutti gli eventi (Motori, Calcio, Tennis, Basket, Volley, Altri sport)
 * con i canali che li trasmettono.
 *
 * La fonte è `output/calendario_sport.json` di github.com/campipaolo/Livesoccer, la stessa che usa la wiki di
 * MandraKodi. Non la legge l'app: la legge scripts/aggiorna_eventi.py su GitHub Actions (ogni 10 minuti) e la
 * pubblica dentro eventi.json, chiave "sport"; l'app scarica quel file solo (vedi [LiveFixtures]).
 *
 * Nella sezione "MandraKodi" ogni categoria è uno sport e si riempie da sola: ogni evento diventa una card con il
 * suo orario e, quando c'è, il canale delle playlist che lo trasmette (abbinato per nome: "Sky Sport MotoGP",
 * "TV8", "DAZN 1"...). Se nessuna playlist ha quel canale l'evento resta in elenco e la card dice dove va in onda.
 *
 * Come per le partite, l'admin può modificare (nome, canale) o rimuovere ogni evento: le scelte si ricordano per
 * evento (chiave "titolo|inizio") e restano anche quando il calendario si aggiorna da solo.
 */
object MandraCalendar {
    private const val TAG = "Mandra"   // diagnostica: Logcat, filtro "Mandra"

    /** Un evento del calendario. [minutes] = durata stimata, per sapere quando è finito. */
    data class Event(
        val title: String,
        val sport: String,      // "Motori", "Calcio", ... (la cartella del calendario)
        val sub: String,        // "MotoGP", "Serie A", ... (la competizione dentro lo sport)
        val start: Long,        // inizio in millisecondi
        val minutes: Int,       // durata stimata in minuti
        val tv: List<String>    // canali che lo trasmettono, nell'ordine della guida
    )

    /** Le categorie della sezione MandraKodi: una per ogni sport del calendario. */
    val SPORTS = listOf("Motori", "Calcio", "Tennis", "Basket", "Volley", "Altri sport")

    private const val PREFS = "mandra_v1"
    private const val K_EVENTS = "events"
    private const val K_AT = "events_at"
    private const val DEL = "del_sport"      // eventi tolti dall'admin
    private const val OVR = "ovr_sport"      // nome/canale scelti dall'admin
    private const val GRACE_MS = 30 * 60_000L      // un evento finito resta in elenco mezz'ora
    private const val AHEAD_MS = 7 * 86_400_000L   // e ci sono quelli dei prossimi 7 giorni
    private const val MEMO_MS = 60L * 86_400_000L  // modifiche/rimozioni dell'admin: si dimenticano dopo 60 giorni

    private var prefs: SharedPreferences? = null
    @Volatile private var list: List<Event>? = null

    /** Cosa è successo all'ultimo aggiornamento (si vede nell'Admin quando una categoria è vuota). */
    @Volatile var diag = "calendario MandraKodi: non ancora scaricato"
        private set

    fun init(ctx: Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** true se la categoria è uno sport del calendario ("altri sport" e "Altri Sport" sono lo stesso). */
    fun isSport(categoryName: String) = sportOf(categoryName) != null

    private fun sportOf(categoryName: String): String? {
        val n = FixtureParsers.fold(categoryName).trim()
        return SPORTS.firstOrNull { FixtureParsers.fold(it) == n }
    }

    // ---- dati (li scrive [LiveFixtures] dopo aver scaricato eventi.json) ----

    /** Legge la chiave "sport" di eventi.json. Da chiamare fuori dal thread UI. */
    fun applyData(root: JSONObject, now: Long) {
        val o = root.optJSONObject("sport") ?: return
        val arr = o.optJSONArray("eventi") ?: JSONArray()
        val out = ArrayList<Event>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            val t = e.optString("t", "")
            val start = e.optLong("i", 0L)
            if (t.isEmpty() || start <= 0L) continue
            val tv = e.optJSONArray("tv") ?: JSONArray()
            out.add(
                Event(
                    title = t,
                    sport = e.optString("c", ""),
                    sub = e.optString("s", ""),
                    start = start,
                    minutes = e.optInt("d", 150),
                    tv = (0 until tv.length()).map { tv.optString(it, "") }.filter { it.isNotBlank() }
                )
            )
        }
        val stato = o.optString("stato", "ok").ifEmpty { "ok" }
        diag = "calendario MandraKodi: $stato (${out.size} eventi)"
        Log.d(TAG, "$stato, ${out.size} eventi da eventi.json")
        // file vuoto o non leggibile: gli eventi già scaricati restano (spariscono da soli quando sono vecchi)
        if (out.isNotEmpty()) {
            list = out
            prefs?.edit()?.putString(K_EVENTS, toJson(out).toString())?.putLong(K_AT, now)?.apply()
        }
    }

    /** eventi.json non è arrivato (rete o formato): lo dico nell'Admin, gli eventi già noti restano. */
    fun fail(why: String) {
        diag = "calendario MandraKodi: $why"
    }

    private fun toJson(l: List<Event>) = JSONArray().also { arr ->
        l.forEach {
            arr.put(
                JSONObject().put("t", it.title).put("c", it.sport).put("s", it.sub)
                    .put("i", it.start).put("d", it.minutes).put("tv", JSONArray(it.tv))
            )
        }
    }

    private fun stored(): List<Event> {
        list?.let { return it }
        val l = try {
            val arr = JSONArray(prefs?.getString(K_EVENTS, null) ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tv = o.optJSONArray("tv") ?: JSONArray()
                Event(
                    o.getString("t"), o.optString("c", ""), o.optString("s", ""),
                    o.optLong("i", 0L), o.optInt("d", 150),
                    (0 until tv.length()).map { tv.optString(it, "") }.filter { it.isNotBlank() }
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        list = l
        return l
    }

    /** Gli eventi da mostrare adesso (nessuna rete): in corso, appena finiti e quelli dei prossimi 7 giorni. */
    fun current(): List<Event> {
        val now = System.currentTimeMillis()
        return stored()
            .filter { it.start + it.minutes * 60_000L + GRACE_MS > now && it.start < now + AHEAD_MS }
            .sortedBy { it.start }
    }

    // ---- abbinamento coi canali delle playlist e del catalogo automatico Mandra ----

    private val plusShiftRe = Regex("""\+(\d{1,2})\b""")
    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val tok = Regex("""\d+|\D+""")
    private val qualityTok = setOf("fhd", "hd", "sd", "uhd", "4k", "hevc", "h265", "h264", "1080p", "720p")
    private val numWords = mapOf("uno" to "1", "due" to "2", "tre" to "3")

    // Piattaforme solo-streaming / estere senza canale lineare italiano diretto
    private val appOnlyTv = setOf(
        "sky go italia", "sky go", "mediaset infinity", "raiplay", "rai play",
        "dazn canada", "fubotv", "fubotv canada", "disney+ premium caribbean",
        "peacock", "paramount+", "lab channel", "discovery+", "eurosport player"
    )
    private val nowApps = setOf("now", "now tv")
    private val genericDazn = setOf("dazn", "dazn italia")

    private val f1Re = Regex("""\b(formula ?[123]|f[123]|porsche)\b""", RegexOption.IGNORE_CASE)
    private val motoRe = Regex("""\b(moto ?gp|moto ?[23e]|superbike|wsbk|supersport)\b""", RegexOption.IGNORE_CASE)
    private val skyCalcioNumRe = Regex("""\bsky\s+calcio\s+(25[1-9]|[1-9])\b""")

    /** "SKY SPORT MOTOGP FHD" -> [sky, sport, motogp]; "DAZN1" e "DAZN 1" diventano entrambi [dazn, 1]. */
    private fun chanKey(s: String): List<String> {
        val shifted = plusShiftRe.replace(s) { " plus${it.groupValues[1]} " }
        val folded = skyCalcioNumRe.replace(FixtureParsers.fold(shifted)) { m ->
            val n = m.groupValues[1]
            "sky sport " + if (n.length == 1) "25$n" else n
        }
        return nonAlnumRe.replace(folded, " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in qualityTok }              // via la qualità ("FHD", "4K", ...)
            .flatMap { w -> tok.findAll(w).map { it.value }.toList() }    // "dazn1" -> [dazn, 1]
            .filter { it.isNotEmpty() }
            .map { numWords[it] ?: it }
            .toList()
    }

    private class Idx(
        val exact: Map<String, List<Channel>>,            // "sky sport motogp" -> canali ordinati per fonte/qualità
        val joined: Map<String, List<Channel>>,           // "sportitalia" (senza spazi) -> canali ordinati
        val size: Int,                                    // quanti canali ha il catalogo
        val byToken: Map<String, List<Pair<List<String>, Channel>>>   // per cercare solo dove serve
    )

    @Volatile private var idxFor: List<Channel>? = null
    @Volatile private var idxVal: Idx? = null

    private fun qualityRank(q: String) = when (q.uppercase()) {
        "FHD" -> 0; "HD" -> 1; "4K" -> 2; "" -> 3; else -> 4
    }

    private val chanComparator = compareBy<Channel>({ it.sourceRank }, { qualityRank(it.quality) })

    // L'indice si rifà solo quando cambia la lista canali: le categorie della sezione lo chiedono una dopo l'altra
    @Synchronized
    private fun index(playlist: List<Channel>): Idx {
        idxVal?.let { if (idxFor === playlist) return it }
        val exact = HashMap<String, MutableList<Channel>>()
        val joined = HashMap<String, MutableList<Channel>>()
        val byToken = HashMap<String, MutableList<Pair<List<String>, Channel>>>()
        var n = 0
        for (ch in playlist) {
            val t = chanKey(ch.name)
            if (t.isEmpty()) continue
            n++
            exact.getOrPut(t.joinToString(" ")) { ArrayList() }.add(ch)
            joined.getOrPut(t.joinToString("")) { ArrayList() }.add(ch)
            val pair = t to ch
            t.distinct().forEach { byToken.getOrPut(it) { ArrayList() }.add(pair) }
        }
        exact.values.forEach { it.sortWith(chanComparator) }
        joined.values.forEach { it.sortWith(chanComparator) }
        val r = Idx(exact, joined, n, byToken)
        idxFor = playlist
        idxVal = r
        return r
    }

    /**
     * Espande e ordina i nomi dei canali della guida TV per massimizzare l'abbinamento automatico:
     *  - prima i canali lineari specifici (es. "Sky Sport Calcio", "Sky Sport 253", "Rai 2", "TV8", "Sportitalia")
     *  - poi gli alias noti ("Sky Sport Basket" -> "Sky Sport NBA", "Eurosport" -> "Eurosport 1"/"2")
     *  - poi i canali generici ("DAZN" -> "DAZN 1", "Zona DAZN") e 4K
     *  - se il calendario non riporta l'emittente (es. prove F2/F3 o Moto2/Moto3), deduce il canale dallo sport.
     */
    private fun candidateBroadcasters(e: Event): List<String> {
        val primary = ArrayList<String>()
        val secondary = ArrayList<String>()
        var sawNow = false

        for (raw in e.tv) {
            val norm = FixtureParsers.fold(raw).trim()
            if (norm.isEmpty() || norm in appOnlyTv) continue
            if (norm in nowApps) {
                sawNow = true
                continue
            }
            when {
                norm in genericDazn -> {
                    secondary.add("DAZN 1")
                    secondary.add("Zona DAZN")
                    secondary.add("DAZN")
                }
                norm == "sky sport basket" -> {
                    primary.add("Sky Sport Basket")
                    primary.add("Sky Sport NBA")
                }
                norm == "sky sport mix" -> {
                    primary.add("Sky Sport Mix")
                    secondary.add("Sky Sport Action")
                    secondary.add("Sky Sport Max")
                }
                norm == "eurosport" -> {
                    primary.add("Eurosport 1")
                    primary.add("Eurosport 2")
                    primary.add("Eurosport")
                }
                norm.endsWith("4k") -> secondary.add(raw)
                else -> primary.add(raw)
            }
        }

        val combined = (primary + secondary).distinct()
        if (combined.isNotEmpty()) return combined
        if (sawNow) return listOf("Sky Sport Uno", "Sky Sport Arena")

        // Emittente non indicata nel calendario (frequente per sessioni F2/F3, Moto2/Moto3, Superbike, Tennis)
        val probe = "${e.sub} ${e.title}"
        return when (e.sport) {
            "Motori" -> when {
                f1Re.containsMatchIn(probe) -> listOf("Sky Sport F1")
                motoRe.containsMatchIn(probe) -> listOf("Sky Sport MotoGP")
                else -> emptyList()
            }
            "Tennis" -> listOf("Sky Sport Tennis", "SuperTennis")
            else -> emptyList()
        }
    }

    /**
     * Tutti i canali disponibili per l'evento (in ordine di priorità: playlist utente / DTT ufficiale /
     * risolutori automatici Mandra / playlist predefinita), così se un flusso fallisce il player passa al successivo.
     */
    private fun findChannels(e: Event, idx: Idx, playlist: List<Channel>): List<Channel> {
        if (idx.size == 0) return emptyList()
        val out = ArrayList<Channel>()

        // Per Serie A/B/C controlla anche eventuali canali dedicati alla squadra di casa nelle playlist utente
        val isSerieABC = e.sport == "Calcio" && (
            e.sub.equals("Serie A", true) || e.sub.equals("Serie B", true) || e.sub.equals("Serie C", true) ||
                e.title.startsWith("Serie A:", true) || e.title.startsWith("Serie B:", true) ||
                e.title.startsWith("Serie C:", true)
            )
        val isSerieC = e.sub.equals("Serie C", true) || e.title.startsWith("Serie C:", true)
        val teamChannels = if (isSerieABC) {
            val home = SportsDb.teams(e.title)?.first
            if (home != null) LiveFixtures.findChannels(home, playlist, partial = !isSerieC) else emptyList()
        } else emptyList()

        // I canali dedicati alla squadra provenienti da una playlist utente (sourceRank <= 1) hanno priorità massima
        out.addAll(teamChannels.filter { it.sourceRank <= 1 })

        val keys = candidateBroadcasters(e).map { chanKey(it) }.filter { it.isNotEmpty() }
        for (t in keys) {
            val ex = idx.exact[t.joinToString(" ")]
            val jn = idx.joined[t.joinToString("")]
            if (!ex.isNullOrEmpty() || !jn.isNullOrEmpty()) {
                ex?.let { out.addAll(it) }
                jn?.let { out.addAll(it) }
            }
        }
        if (out.isEmpty()) {
            for (t in keys) {
                if (t.size < 2 && t.joinToString("").length < 4) continue
                val ts = t.toSet()
                val pool = t.mapNotNull { idx.byToken[it] }.minByOrNull { it.size } ?: continue
                val found = pool.filter { (ct, _) -> ct.toSet().containsAll(ts) }
                    .sortedWith(
                        compareBy<Pair<List<String>, Channel>>(
                            { it.second.sourceRank },
                            { it.first.size },
                            { qualityRank(it.second.quality) }
                        )
                    )
                    .map { it.second }
                out.addAll(found)
            }
        }

        // In coda eventuali canali squadra della playlist di backup (sourceRank >= 2)
        out.addAll(teamChannels.filter { it.sourceRank >= 2 })
        return out
    }

    /** Dove va in onda l'evento (al massimo due canali): privilegia i nomi dei canali TV rispetto alle app. */
    private fun onTv(e: Event): String {
        val linear = e.tv.filter {
            val n = FixtureParsers.fold(it).trim()
            n.isNotEmpty() && n !in appOnlyTv && n !in nowApps
        }
        return (if (linear.isNotEmpty()) linear else e.tv).take(2).joinToString(", ")
    }

    private fun key(e: Event) = "${FixtureParsers.fold(e.title)}|${e.start}"

    // ---- modifiche e rimozioni dell'admin sugli eventi automatici (per evento) ----

    private fun memo(prefKey: String): JSONObject {
        val o = try { JSONObject(prefs?.getString(prefKey, null) ?: "{}") } catch (_: Exception) { JSONObject() }
        val cut = System.currentTimeMillis() - MEMO_MS
        o.keys().asSequence().filter { (o.optJSONObject(it)?.optLong("t", 0L) ?: 0L) < cut }.toList()
            .forEach { o.remove(it) }
        return o
    }

    private fun saveMemo(prefKey: String, o: JSONObject) {
        prefs?.edit()?.putString(prefKey, o.toString())?.apply()
    }

    /** Toglie l'evento dall'elenco (anche dopo gli aggiornamenti del calendario). */
    fun removeEvent(evKey: String) {
        val o = memo(DEL)
        o.put(evKey, JSONObject().put("t", System.currentTimeMillis()))
        saveMemo(DEL, o)
    }

    /** Quanti eventi tolti ci sono ancora tra quelli in elenco. */
    fun removedCount(): Int {
        val valid = current().map { key(it) }.toSet()
        return memo(DEL).keys().asSequence().count { it in valid }
    }

    /** Rimette tutti gli eventi tolti. */
    fun restoreRemoved() {
        prefs?.edit()?.remove(DEL)?.apply()
    }

    fun hasOverride(evKey: String): Boolean = memo(OVR).has(evKey)

    /** Ricorda nome e/o canale scelti per l'evento; null = quella parte resta automatica. */
    fun editEvent(evKey: String, newName: String?, picked: Channel?) {
        val all = memo(OVR)
        val o = all.optJSONObject(evKey) ?: JSONObject()
        if (newName != null) o.put("n", newName)
        if (picked != null) {
            o.put("u", picked.url).put("rn", picked.rawName).put("q", picked.quality).put("l", JSONArray(picked.logos))
        }
        o.put("t", System.currentTimeMillis())
        all.put(evKey, o)
        saveMemo(OVR, all)
    }

    /** L'evento torna com'è generato da solo. */
    fun resetEvent(evKey: String) {
        val all = memo(OVR)
        all.remove(evKey)
        saveMemo(OVR, all)
    }

    private fun applyOverride(ch: Channel, o: JSONObject?): Channel {
        if (o == null) return ch
        val n = o.optString("n", "").ifEmpty { ch.name }
        if (!o.has("u")) return ch.copy(name = n)
        val lg = o.optJSONArray("l") ?: JSONArray()
        val u = o.getString("u")
        val fb = (listOf(ch.url) + ch.fallbacks).filter { it.isNotEmpty() && it != u }.distinct()
        return ch.copy(
            name = n,
            rawName = o.optString("rn", ch.rawName),
            quality = o.optString("q", ch.quality),
            url = u,
            logos = List(lg.length()) { lg.getString(it) },
            fallbacks = fb
        )
    }

    // ---- API ----

    /**
     * Gli eventi (come Channel) di una categoria/sport; chi non ha un canale ha url vuoto e porta
     * in [Channel.tv] i nomi dei canali della guida. Le card si vedono comunque, senza riproduzione.
     */
    fun events(category: String, playlist: List<Channel>): List<Channel> {
        val sport = sportOf(category) ?: return emptyList()
        val removed = memo(DEL)
        val edits = memo(OVR)
        val idx = index(playlist)
        return current().filter { it.sport == sport && !removed.has(key(it)) }.map { e ->
            val matches = findChannels(e, idx, playlist)
            val allUrls = matches.flatMap { listOf(it.url) + it.fallbacks }.filter { it.isNotEmpty() }.distinct()
            val ch = matches.firstOrNull()
            val base = Channel(
                name = e.title,
                rawName = ch?.rawName ?: e.title,
                quality = ch?.quality ?: "",
                category = category,
                url = allUrls.firstOrNull() ?: "",
                logos = ch?.logos ?: emptyList(),
                start = e.start,
                evKey = key(e),
                tv = onTv(e),
                fallbacks = allUrls.drop(1)
            )
            applyOverride(base, edits.optJSONObject(key(e)))
        }
    }
}
