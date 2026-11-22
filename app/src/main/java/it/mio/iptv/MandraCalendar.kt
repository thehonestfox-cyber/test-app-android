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

    // ---- abbinamento coi canali delle playlist ----

    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val tok = Regex("""\d+|\D+""")
    private val qualityTok = setOf("fhd", "hd", "sd", "uhd", "4k", "hevc", "h265", "h264", "1080p", "720p")
    private val numWords = mapOf("uno" to "1", "due" to "2", "tre" to "3")

    /** "SKY SPORT MOTOGP FHD" -> [sky, sport, motogp]; "DAZN1" e "DAZN 1" diventano entrambi [dazn, 1]. */
    private fun chanKey(s: String): List<String> =
        nonAlnumRe.replace(FixtureParsers.fold(s), " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in qualityTok }              // via la qualità ("FHD", "4K", ...)
            .flatMap { w -> tok.findAll(w).map { it.value }.toList() }    // "dazn1" -> [dazn, 1]
            .filter { it.isNotEmpty() }
            .map { numWords[it] ?: it }
            .toList()

    private class Idx(
        val exact: Map<String, Channel>,                  // "sky sport motogp" -> canale migliore con quel nome
        val joined: Map<String, Channel>,                 // "sportitalia" (senza spazi) -> canale migliore
        val size: Int,                                    // quanti canali ha la playlist
        val byToken: Map<String, List<Pair<List<String>, Channel>>>   // per cercare solo dove serve
    )

    @Volatile private var idxFor: List<Channel>? = null
    @Volatile private var idxVal: Idx? = null

    private fun qualityRank(q: String) = when (q.uppercase()) {
        "FHD" -> 0; "HD" -> 1; "4K" -> 2; "" -> 3; else -> 4
    }

    /** Tra due canali con lo stesso nome tiene quello di qualità migliore. */
    private fun better(old: Channel?, ch: Channel): Channel =
        if (old == null || qualityRank(ch.quality) < qualityRank(old.quality)) ch else old

    // L'indice si rifà solo quando cambia la playlist: le categorie della sezione lo chiedono una dopo l'altra
    @Synchronized
    private fun index(playlist: List<Channel>): Idx {
        idxVal?.let { if (idxFor === playlist) return it }
        val exact = HashMap<String, Channel>()
        val joined = HashMap<String, Channel>()
        val byToken = HashMap<String, MutableList<Pair<List<String>, Channel>>>()
        var n = 0
        for (ch in playlist) {
            val t = chanKey(ch.name)
            if (t.isEmpty()) continue
            n++
            // a parità di nome vince la qualità migliore (FHD prima di HD)
            exact[t.joinToString(" ")] = better(exact[t.joinToString(" ")], ch)
            joined[t.joinToString("")] = better(joined[t.joinToString("")], ch)
            val pair = t to ch
            t.distinct().forEach { byToken.getOrPut(it) { ArrayList() }.add(pair) }
        }
        val r = Idx(exact, joined, n, byToken)
        idxFor = playlist
        idxVal = r
        return r
    }

    /**
     * Il canale delle playlist che trasmette l'evento: prima il nome uguale a uno di quelli della guida
     * (anche senza spazi: "Sportitalia" = "Sport Italia"), poi quello che lo contiene ("DAZN" sta in "DAZN 1").
     * I nomi troppo generici ("Sky", "NOW") si abbinano solo se uguali, per non finire su un canale a caso.
     */
    private fun findChannel(e: Event, idx: Idx): Channel? {
        if (idx.size == 0 || e.tv.isEmpty()) return null
        val keys = e.tv.map { chanKey(it) }.filter { it.isNotEmpty() }
        for (t in keys) {
            idx.exact[t.joinToString(" ")]?.let { return it }
            idx.joined[t.joinToString("")]?.let { return it }
        }
        for (t in keys) {
            if (t.size < 2 && t.joinToString("").length < 4) continue
            val ts = t.toSet()
            // confronto solo i canali che hanno almeno una delle parole del nome (parto dalla più rara)
            val pool = t.mapNotNull { idx.byToken[it] }.minByOrNull { it.size } ?: continue
            val found = pool.filter { (ct, _) -> ct.toSet().containsAll(ts) }
                .minWithOrNull(
                    compareBy<Pair<List<String>, Channel>>({ it.first.size }, { qualityRank(it.second.quality) })
                )
            if (found != null) return found.second
        }
        return null
    }

    /** Dove va in onda l'evento (al massimo due canali): si vede nella card quando la playlist non li ha. */
    private fun onTv(e: Event): String = e.tv.take(2).joinToString(", ")

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
        return ch.copy(
            name = n,
            rawName = o.optString("rn", ch.rawName),
            quality = o.optString("q", ch.quality),
            url = o.getString("u"),
            logos = List(lg.length()) { lg.getString(it) }
        )
    }

    // ---- API ----

    /**
     * Gli eventi (come Channel) di una categoria/sport; chi non ha un canale nelle playlist ha url vuoto e porta
     * in [Channel.tv] i nomi dei canali della guida. Le card si vedono comunque, senza riproduzione.
     */
    fun events(category: String, playlist: List<Channel>): List<Channel> {
        val sport = sportOf(category) ?: return emptyList()
        val removed = memo(DEL)
        val edits = memo(OVR)
        val idx = index(playlist)
        return current().filter { it.sport == sport && !removed.has(key(it)) }.map { e ->
            val ch = findChannel(e, idx)
            val base = Channel(
                name = e.title,
                rawName = ch?.rawName ?: e.title,
                quality = ch?.quality ?: "",
                category = category,
                url = ch?.url ?: "",
                logos = ch?.logos ?: emptyList(),
                start = e.start,
                evKey = key(e),
                tv = onTv(e)
            )
            applyOverride(base, edits.optJSONObject(key(e)))
        }
    }
}
