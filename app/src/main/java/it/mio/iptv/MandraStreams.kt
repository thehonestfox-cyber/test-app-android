package it.mio.iptv

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Motore automatico dei canali in stile MandraKodi: scarica da solo il catalogo dei canali italiani
 * e ne risolve i flussi al momento della riproduzione (con token/firme aggiornati e fallback multipli),
 * senza bisogno di conoscere o aggiungere playlist M3U a mano.
 *
 * Fonti combinate in automatico:
 *  1. Digitale terrestre gratuito ufficiale (API Sky Free per TV8/Cielo/SkyTG24, HLS Mediaset, IPTV-Italia per Rai,
 *     Sportitalia, SuperTennis, NOVE, DMAX, Motor Trend, LA7…) -> sourceRank = 0
 *  2. Catalogo live Vavoo Italia (mediahubmx-catalog + mediahubmx-resolve / lokke fallback) -> sourceRank = 2
 *  3. Catalogo live Kool.to (canali italiani + resolver dinamico) -> sourceRank = 2
 *  4. Tabella integrata DaddyLive (Sky Sport Uno/Calcio/F1/MotoGP/Tennis/Arena/Max/Basket/251..254, DAZN 1,
 *     Eurosport 1/2, Rai Sport…) pronta subito anche prima del primo download di rete -> sourceRank = 2
 */
object MandraStreams {
    private const val TAG = "MandraStreams"
    private const val SCHEME = "mandra://"
    private const val CACHE_FILE = "mandra_auto_v3.json"
    private const val LEGACY_V1_CACHE = "mandra_auto_v1.json"
    private const val BROKEN_V2_CACHE = "mandra_auto_v2.json"
    private const val REFRESH_MS = 15 * 60_000L
    private const val SIG_TTL_MS = 8 * 60_000L

    private const val URL_DTT_M3U =
        "https://raw.githubusercontent.com/Tundrak/IPTV-Italia/main/iptvitaplus.m3u"
    private const val URL_VAVOO_PING = "https://www.vavoo.tv/api/app/ping"
    private const val URL_VAVOO_CATALOG = "https://vavoo.to/mediahubmx-catalog.json"
    private const val URL_VAVOO_RESOLVE = "https://vavoo.to/mediahubmx-resolve.json"
    private const val URL_LOKKE_PING = "https://www.lokke.app/api/box/ping2"
    private const val URL_KOOL_CHANNELS = "https://vavoo.papadipeppe.workers.dev/channels"
    private const val URL_KOOL_RESOLVE = "https://vavoo.papadipeppe.workers.dev/resolve?url=https://kool.to/live/play/"

    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"
    private const val DADDY_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 OPR/130.0.0.0"
    private const val VAVOO_PING_UA =
        "electron-fetch/1.0 electron (+https://github.com/arantes555/electron-fetch)"

    // Vettore per la firma TS di fallback (stesso di VavooResolver in myResolver.py di MandraKodi)
    private const val LOKKE_VEC =
        "9frjpxPjxSNilxJPCJ0XGYs6scej3dW/h/VWlnKUiLSG8IP7mfyDU7NirOlld+VtCKGj03XjetfliDMhIev7wcARo+YTU8KPFuVQP9E2DVXzY2BFo1NhE6qEmPfNDnm74eyl/7iFJ0EETm6XbYyz8IKBkAqPN/Spp3PZ2ulKg3QBSDxcVN4R5zRn7OsgLJ2CNTuWkd/h451lDCp+TtTuvnAEhcQckdsydFhTZCK5IiWrrTIC/d4qDXEd+GtOP4hPdoIuCaNzYfX3lLCwFENC6RZoTBYLrcKVVgbqyQZ7DnLqfLqvf3z0FVUWx9H21liGFpByzdnoxyFkue3NzrFtkRL37xkx9ITucepSYKzUVEfyBh+/3mtzKY26VIRkJFkpf8KVcCRNrTRQn47Wuq4gC7sSwT7eHCAydKSACcUMMdpPSvbvfOmIqeBNA83osX8FPFYUMZsjvYNEE3arbFiGsQlggBKgg1V3oN+5ni3Vjc5InHg/xv476LHDFnNdAJx448ph3DoAiJjr2g4ZTNynfSxdzA68qSuJY8UjyzgDjG0RIMv2h7DlQNjkAXv4k1BrPpfOiOqH67yIarNmkPIwrIV+W9TTV/yRyE1LEgOr4DK8uW2AUtHOPA2gn6P5sgFyi68w55MZBPepddfYTQ+E1N6R/hWnMYPt/i0xSUeMPekX47iucfpFBEv9Uh9zdGiEB+0P3LVMP+q+pbBU4o1NkKyY1V8wH1Wilr0a+q87kEnQ1LWYMMBhaP9yFseGSbYwdeLsX9uR1uPaN+u4woO2g8sw9Y5ze5XMgOVpFCZaut02I5k0U4WPyN5adQjG8sAzxsI3KsV04DEVymj224iqg2Lzz53Xz53Xz9yEy+7/85ILQpJ6llCyqpHLFyHq/kJxYPhDUF755WaHJEaFRPxUqbparNX+mCE9Xzy7Q/KTgAPiRS41FHXXv+7XSPp4cy9jli0BVnYf13Xsp28OGs/D8Nl3NgEn3/eUcMN80JRdsOrV62fnBVMBNf36+LbISdvsFAFr0xyuPGmlIETcFyxJkrGZnhHAxwzsvZ+Uwf8lffBfZFPRrNv+tgeeLpatVcHLHZGeTgWWml6tIHwWUqv2TVJeMkAEL5PPS4Gtbscau5HM+FEjtGS+KClfX1CNKvgYJl7mLDEf5ZYQv5kHaoQ6RcPaR6vUNn02zpq5/X3EPIgUKF0r/0ctmoT84B2J1BKfCbctdFY9br7JSJ6DvUxyde68jB+Il6qNcQwTFj4cNErk4x719Y42NoAnnQYC2/qfL/gAhJl8TKMvBt3Bno+va8ve8E0z8yEuMLUqe8OXLce6nCa+L5LYK1aBdb60BYbMeWk1qmG6Nk9OnYLhzDyrd9iHDd7X95OM6X5wiMVZRn5ebw4askTTc50xmrg4eic2U1w1JpSEjdH/u/hXrWKSMWAxaj34uQnMuWxPZEXoVxzGyuUbroXRfkhzpqmqqqOcypjsWPdq5BOUGL/Riwjm6yMI0x9kbO8+VoQ6RYfjAbxNriZ1cQ+AW1fqEgnRWXmjt4Z1M0ygUBi8w71bDML1YG6UHeC2cJ2CCCxSrfycKQhpSdI1QIuwd2eyIpd4LgwrMiY3xNWreAF+qobNxvE7ypKTISNrz0iYIhU0aKNlcGwYd0FXIRfKVBzSBe4MRK2pGLDNO6ytoHxvJweZ8h1XG8RWc4aB5gTnB7Tjiqym4b64lRdj1DPHJnzD4aqRixpXhzYzWVDN2kONCR5i2quYbnVFN4sSfLiKeOwKX4JdmzpYixNZXjLkG14seS6KR0Wl8Itp5IMIWFpnNokjRH76RYRZAcx0jP0V5/GfNNTi5QsEU98en0SiXHQGXnROiHpRUDXTl8FmJORjwXc0AjrEMuQ2FDJDmAIlKUSLhjbIiKw3iaqp5TVyXuz0ZMYBhnqhcwqULqtFSuIKpaW8FgF8QJfP2frADf4kKZG1bQ99MrRrb2A="

    // Canali sportivi e nazionali DaddyLive di riserva, subito disponibili anche senza rete
    private val DADDY_CHANNELS = listOf(
        "Sky Sport Uno" to "461",
        "Sky Sport Calcio" to "870",
        "Sky Sport F1" to "577",
        "Sky Sport MotoGP" to "575",
        "Sky Sport Tennis" to "576",
        "Sky Sport Arena" to "462",
        "Sky Sport Max" to "460",
        "Sky Sport Basket" to "875",
        "Sky Sport Golf" to "574",
        "Sky Sport 24" to "869",
        "Sky Sport 251" to "871",
        "Sky Sport 252" to "872",
        "Sky Sport 253" to "873",
        "Sky Sport 254" to "874",
        "DAZN 1" to "877",
        "Eurosport 1" to "878",
        "Eurosport 2" to "879",
        "Rai Sport" to "882",
        "Rai 1" to "850",
        "Rai 2" to "851",
        "Rai 3" to "852",
        "Canale 5" to "853",
        "Italia 1" to "854",
        "LA7" to "855",
        "20 Mediaset" to "857",
        "Sky Uno" to "881"
    )

    private val refreshLock = Any()
    private val sigLock = Any()
    @Volatile private var memCatalog: List<Channel>? = null
    @Volatile private var memFetchedAt = 0L
    @Volatile private var vavooSig: String? = null
    @Volatile private var vavooSigAt = 0L
    private val premiumTokens = ConcurrentHashMap<String, Pair<String, Long>>()

    private val serverSuffixRe = Regex("""\s*\.[a-z0-9]{1,3}$""", RegexOption.IGNORE_CASE)
    private val trailingItRe = Regex("""\s+\b(IT|ITALY)\b$""", RegexOption.IGNORE_CASE)
    private val skyCalcioNumRe = Regex("""\bSKY\s+CALCIO\s+(25[1-9]|[1-9])\b""", RegexOption.IGNORE_CASE)
    private val skyCalcioPlainRe = Regex("""^SKY\s+CALCIO$""", RegexOption.IGNORE_CASE)
    private val plusShiftRe = Regex("""\+(\d{1,2})\b""")
    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val tokRe = Regex("""\d+|\D+""")
    private val expParamRe = Regex("""[?&]exp=(\d{9,12})\b""")
    private val qualityTok = setOf("fhd", "hd", "sd", "uhd", "4k", "hevc", "h265", "h264", "1080p", "720p")
    private val numWords = mapOf("uno" to "1", "due" to "2", "tre" to "3")

    private val italianKoolRe = Regex(
        """\b(RAI|MEDIASET|CANALE ?5|ITALIA ?1|RETE ?4|LA ?7|TV ?8|NOVE|CIELO|DMAX|SPORT ?ITALIA|""" +
            """SUPER ?TENNIS|SKY ?SPORT|SKY ?CINEMA|SKY ?UNO|SKY ?TG|SKY ?ARTE|DAZN|ZONA ?DAZN|EUROSPORT|""" +
            """MOTOR ?TREND|REAL ?TIME|GIALLO|TOP ?CRIME|IRIS|FOCUS|BOING|K2|FRISBEE|CARTOONITO|CINE ?34)\b""",
        RegexOption.IGNORE_CASE
    )
    private val foreignKoolRe = Regex(
        """\b(DE|DEUTSCHLAND|AUSTRIA|BUNDESLIGA|PREMIER|UK|FR|FRANCE|ES|SPAIN|ESPANA|PT|PL|TR|ARAB)\b""",
        RegexOption.IGNORE_CASE
    )

    /** true se l'URL va risolto al volo da [resolve] prima di passarlo a ExoPlayer. */
    fun isDynamic(url: String): Boolean = url.startsWith(SCHEME)

    /** true se il catalogo remoto è già stato scaricato di recente in questa sessione. */
    fun isFresh(): Boolean = memCatalog != null && System.currentTimeMillis() - memFetchedAt < REFRESH_MS

    /** Unisce elenchi provenienti da più fonti (Mandra + playlist) in un solo canale per nome, ordinando i fallback per priorità. */
    fun mergeChannels(list: List<Channel>): List<Channel> = mergeByChannel(list)

    /** Restituisce tutti gli URL (principale + fallback) noti nel catalogo Mandra per il nome canale indicato. */
    fun fallbacksForName(cacheDir: File, channelName: String): List<String> {
        val target = mergeKey(channelName)
        if (target.isEmpty()) return emptyList()
        val ch = cached(cacheDir).firstOrNull { mergeKey(it.name) == target } ?: return emptyList()
        return (listOf(ch.url) + ch.fallbacks).filter { it.isNotEmpty() }.distinct()
    }

    // ---------- Catalogo (cache su disco + aggiornamento in background) ----------

    /** Restituisce subito i canali già noti (memoria -> disco -> tabella integrata), senza rete. */
    fun cached(cacheDir: File): List<Channel> {
        memCatalog?.let { return it }
        try {
            val bad = File(cacheDir, BROKEN_V2_CACHE)
            if (bad.exists()) bad.delete()
        } catch (_: Exception) {
        }
        for (fileName in listOf(CACHE_FILE, LEGACY_V1_CACHE)) {
            val f = File(cacheDir, fileName)
            if (f.exists()) {
                try {
                    val parsed = fromCacheJson(JSONArray(f.readText(Charsets.UTF_8)))
                    if (parsed.isNotEmpty()) {
                        memCatalog = parsed
                        return parsed
                    }
                } catch (_: Exception) {
                }
            }
        }
        return mergeByChannel(builtinFreeDtt() + builtinDaddy())
    }

    /** Scarica e unisce i cataloghi automatici (al massimo ogni 15 minuti). Da chiamare fuori dal thread UI. */
    fun refresh(cacheDir: File): List<Channel> {
        val now = System.currentTimeMillis()
        memCatalog?.let { if (now - memFetchedAt < REFRESH_MS) return it }
        synchronized(refreshLock) {
            val now2 = System.currentTimeMillis()
            memCatalog?.let { if (now2 - memFetchedAt < REFRESH_MS) return it }

            val pool = Executors.newFixedThreadPool(3)
            val fDtt = pool.submit(Callable { fetchFreeDtt() })
            val fVavoo = pool.submit(Callable { fetchVavooItaly() })
            val fKool = pool.submit(Callable { fetchKoolItaly() })
            val dtt = try { fDtt.get() } catch (_: Exception) { emptyList() }
            val vavoo = try { fVavoo.get() } catch (_: Exception) { emptyList() }
            val kool = try { fKool.get() } catch (_: Exception) { emptyList() }
            pool.shutdown()

            Log.d(TAG, "Catalogo automatico: DTT=${dtt.size}, Vavoo=${vavoo.size}, Kool=${kool.size}")
            val remoteOk = dtt.isNotEmpty() || vavoo.isNotEmpty() || kool.isNotEmpty()
            if (!remoteOk) {
                return cached(cacheDir)
            }
            val merged = mergeByChannel(builtinFreeDtt() + dtt + vavoo + kool + builtinDaddy())
            memCatalog = merged
            memFetchedAt = System.currentTimeMillis()
            try {
                File(cacheDir, CACHE_FILE).writeText(toCacheJson(merged).toString(), Charsets.UTF_8)
            } catch (_: Exception) {
            }
            return merged
        }
    }

    private fun builtinFreeDtt(): List<Channel> = listOf(
        Categorizer.build("TV8 HD", "Canali TV", "", "${SCHEME}skytv/7", sourceRank = 0),
        Categorizer.build("Cielo HD", "Canali TV", "", "${SCHEME}skytv/2", sourceRank = 0),
        Categorizer.build("Sky TG24 HD", "Notizie", "", "${SCHEME}skytv/1", sourceRank = 0),
        Categorizer.build(
            "Canale 5 HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-c5/c5-clr.isml/index.m3u8", sourceRank = 0
        ),
        Categorizer.build(
            "Italia 1 HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-i1/i1-clr.isml/index.m3u8", sourceRank = 0
        ),
        Categorizer.build(
            "Rete 4 HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-r4/r4-clr.isml/index.m3u8", sourceRank = 0
        ),
        Categorizer.build(
            "20 Mediaset HD", "Canali TV", "",
            "https://live02-seg.msf.cdn.mediaset.net/live/ch-lb/lb-clr.isml/index.m3u8", sourceRank = 0
        )
    )

    // sourceRank = 3 così i cataloghi live Vavoo/Kool (sourceRank = 2) hanno sempre precedenza sul fallback DaddyLive
    private fun builtinDaddy(): List<Channel> =
        DADDY_CHANNELS.map { (name, code) ->
            Categorizer.build("$name HD", "Sport", "", "${SCHEME}daddy/$code", sourceRank = 3)
        }

    private fun fetchFreeDtt(): List<Channel> {
        val text = httpGet(URL_DTT_M3U, mapOf("User-Agent" to CHROME_UA), 10_000) ?: return emptyList()
        val nowSec = System.currentTimeMillis() / 1000L
        return M3U.parse(text, sourceRank = 0).filter { ch ->
            val exp = expParamRe.find(ch.url)?.groupValues?.get(1)?.toLongOrNull()
            exp == null || exp > nowSec + 60L
        }
    }

    private fun cleanCatalogName(raw: String): String {
        var s = serverSuffixRe.replace(raw.trim(), "").trim()
        if (!s.startsWith("BBC", ignoreCase = true)) {
            s = trailingItRe.replace(s, "").trim()
        }
        s = skyCalcioNumRe.replace(s) { m ->
            val n = m.groupValues[1]
            "Sky Sport " + if (n.length == 1) "25$n" else n
        }
        if (skyCalcioPlainRe.matches(s)) {
            s = "Sky Sport Calcio"
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    private fun fetchVavooItaly(): List<Channel> {
        val sig = getVavooSignature(force = false)
        val out = fetchVavooCatalogWithSig(sig)
        if (out.isNotEmpty()) return out
        // Se la firma era scaduta o non accettata, riprova una volta senza firma (Origin/Referer vavoo.to)
        return fetchVavooCatalogWithSig(null)
    }

    private fun fetchVavooCatalogWithSig(sig: String?): List<Channel> {
        val out = ArrayList<Channel>()
        var cursor: Any? = 0
        for (page in 0 until 20) {
            val body = JSONObject()
                .put("language", "de")
                .put("region", "AT")
                .put("catalogId", "iptv")
                .put("id", "iptv")
                .put("adult", false)
                .put("search", "")
                .put("sort", "name")
                .put("filter", JSONObject().put("group", "Italy"))
                .put("cursor", cursor ?: JSONObject.NULL)
                .put("clientVersion", "3.0.2")
                .toString()
            val headers = LinkedHashMap<String, String>().apply {
                put("User-Agent", if (sig != null) "okhttp/4.11.0" else CHROME_UA)
                put("Accept", "application/json")
                put("Origin", "https://vavoo.to")
                put("Referer", "https://vavoo.to/")
                if (!sig.isNullOrEmpty()) put("mediahubmx-signature", sig)
            }
            val resp = httpPostJson(URL_VAVOO_CATALOG, body, headers, 12_000) ?: break
            val json = try { JSONObject(resp) } catch (_: Exception) { break }
            val items = json.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val rawName = it.optString("name", "").trim()
                val rawUrl = it.optString("url", "").trim()
                if (rawName.isEmpty() || rawUrl.isEmpty()) continue
                val cleaned = cleanCatalogName(rawName)
                if (cleaned.isEmpty()) continue
                val enc = Base64.encodeToString(
                    rawUrl.toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                )
                out.add(Categorizer.build(cleaned, "", "", "${SCHEME}vavoo/$enc", sourceRank = 2))
            }
            if (json.isNull("nextCursor")) break
            val next = json.opt("nextCursor")
            val nextStr = next?.toString()?.trim().orEmpty()
            if (next == null || nextStr.isEmpty() || nextStr == "false" || nextStr == "0" || nextStr == "null") break
            cursor = next
        }
        return out
    }

    private fun fetchKoolItaly(): List<Channel> {
        val resp = httpGet(URL_KOOL_CHANNELS, mapOf("User-Agent" to "Mozilla/5.0"), 10_000) ?: return emptyList()
        val arr = try { JSONObject(resp).optJSONArray("items") } catch (_: Exception) { null } ?: return emptyList()
        val out = ArrayList<Channel>()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val id = it.optString("id", "").trim()
            val rawName = it.optString("name", "").trim()
            if (id.isEmpty() || rawName.isEmpty()) continue
            val country = (it.optString("country", "") + " " + it.optString("group", "")).trim()
            val isIt = if (country.isNotEmpty()) {
                country.equals("IT", true) || country.contains("ital", true)
            } else {
                !foreignKoolRe.containsMatchIn(rawName) &&
                    (trailingItRe.containsMatchIn(rawName) || italianKoolRe.containsMatchIn(rawName))
            }
            if (!isIt) continue
            val cleaned = cleanCatalogName(rawName)
            if (cleaned.isEmpty()) continue
            out.add(Categorizer.build(cleaned, "", "", "${SCHEME}kool/$id", sourceRank = 2))
        }
        return out
    }

    // ---------- Unione dei doppioni (un solo canale in lista, tutti i server nei fallbacks) ----------

    private fun mergeKey(name: String): String {
        val shifted = plusShiftRe.replace(name) { " plus${it.groupValues[1]} " }
        val tokens = nonAlnumRe.replace(FixtureParsers.fold(shifted), " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in qualityTok }
            .flatMap { w -> tokRe.findAll(w).map { it.value }.toList() }
            .filter { it.isNotEmpty() }
            .map { numWords[it] ?: it }
        val compact = tokens.joinToString("")
        return if (compact == "20mediaset") "20" else compact
    }

    private fun qualityOrder(q: String) = when (q.uppercase()) {
        "FHD" -> 0; "HD" -> 1; "4K" -> 2; "" -> 3; else -> 4
    }

    private fun mergeByChannel(list: List<Channel>): List<Channel> {
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in list) {
            val k = mergeKey(ch.name)
            if (k.isEmpty()) continue
            groups.getOrPut(k) { ArrayList() }.add(ch)
        }
        val out = ArrayList<Channel>(groups.size)
        for ((_, chs) in groups) {
            val sorted = chs.sortedWith(compareBy<Channel>({ it.sourceRank }, { qualityOrder(it.quality) }))
            val best = sorted.first()
            val allUrls = sorted.flatMap { listOf(it.url) + it.fallbacks }.filter { it.isNotEmpty() }.distinct()
            val allLogos = sorted.flatMap { it.logos }.filter { it.isNotEmpty() }.distinct()
            out.add(
                best.copy(
                    logos = allLogos,
                    fallbacks = allUrls.filter { it != best.url }
                )
            )
        }
        return Categorizer.sortChannels(out)
    }

    private fun toCacheJson(list: List<Channel>): JSONArray {
        val arr = JSONArray()
        for (ch in list) {
            arr.put(
                JSONObject()
                    .put("n", ch.name)
                    .put("rn", ch.rawName)
                    .put("q", ch.quality)
                    .put("c", ch.category)
                    .put("u", ch.url)
                    .put("sr", ch.sourceRank)
                    .put("l", JSONArray(ch.logos))
                    .put("fb", JSONArray(ch.fallbacks))
            )
        }
        return arr
    }

    private fun fromCacheJson(arr: JSONArray): List<Channel> {
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("n", "")
            val u = o.optString("u", "")
            if (n.isEmpty() || u.isEmpty()) continue
            val lg = o.optJSONArray("l") ?: JSONArray()
            val fb = o.optJSONArray("fb") ?: JSONArray()
            val allUrls = (listOf(u) + List(fb.length()) { fb.optString(it, "") })
                .filter { it.isNotEmpty() && !it.startsWith("${SCHEME}amstaff/") && "pcdn07.cssott02.com" !in it }
                .distinct()
            if (allUrls.isEmpty()) continue
            // Se nella vecchia cache v1 un canale aveva daddy davanti a vavoo/kool, rimette davanti vavoo/kool
            val reordered = allUrls.sortedBy { cand ->
                when {
                    cand.startsWith("${SCHEME}skytv/") -> 0
                    cand.startsWith("${SCHEME}vavoo/") -> 1
                    cand.startsWith("${SCHEME}kool/") -> 2
                    cand.startsWith("${SCHEME}daddy/") -> 4
                    else -> 3
                }
            }
            out.add(
                Channel(
                    name = n,
                    rawName = o.optString("rn", n),
                    quality = o.optString("q", ""),
                    category = o.optString("c", Categorizer.ALTRI),
                    url = reordered.first(),
                    logos = List(lg.length()) { lg.optString(it, "") }.filter { it.isNotEmpty() },
                    fallbacks = reordered.drop(1),
                    sourceRank = o.optInt("sr", 2)
                )
            )
        }
        return out
    }

    // ---------- Risoluzione dinamica al momento del Play ----------

    /**
     * Risolve un indirizzo `mandra://...` in uno o più flussi HTTP diretti (con intestazioni `|Key=Val`).
     * Da chiamare su un thread in background.
     */
    fun resolve(url: String): List<String> {
        if (!isDynamic(url)) return listOf(url)
        val rest = url.removePrefix(SCHEME)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash >= rest.length - 1) return emptyList()
        val kind = rest.substring(0, slash)
        val arg = rest.substring(slash + 1)
        return try {
            when (kind) {
                "skytv" -> resolveSkyTv(arg)
                "vavoo" -> {
                    val rawUrl = String(
                        Base64.decode(arg, Base64.URL_SAFE),
                        Charsets.UTF_8
                    )
                    resolveVavoo(rawUrl)
                }
                "kool" -> resolveKool(arg)
                "daddy" -> resolveDaddy(arg)
                else -> emptyList()
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Errore risoluzione $kind: ${e.message}")
            emptyList()
        }
    }

    private fun withHeaders(baseUrl: String, vararg pairs: Pair<String, String>): String {
        if (pairs.isEmpty()) return baseUrl
        val encoded = pairs.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$baseUrl|$encoded"
    }

    // --- 1) Sky Free API (TV8 = 7, Cielo = 2, Sky TG24 = 1) ---

    private fun resolveSkyTv(id: String): List<String> {
        val out = ArrayList<String>(2)
        val endpoints = listOf(
            "https://apid.sky.it/vdp/v1/getLivestream?id=$id&isMobile=false",
            "https://video.sky.it/api/v1/getLivestream?id=$id&isMobile=false"
        )
        for (ep in endpoints) {
            val resp = httpGet(ep, mapOf("User-Agent" to CHROME_UA), 7_000) ?: continue
            val u = try { JSONObject(resp).optString("streaming_url", "").trim() } catch (_: Exception) { "" }
            if (u.startsWith("http") && u !in out) out.add(u)
        }
        return out
    }

    // --- 2) Vavoo Resolver (identico a VavooResolver di MandraKodi) ---

    private fun getVavooSignature(force: Boolean): String? {
        val now = System.currentTimeMillis()
        if (!force) {
            vavooSig?.let { if (now - vavooSigAt < SIG_TTL_MS) return it }
        }
        synchronized(sigLock) {
            val now2 = System.currentTimeMillis()
            if (!force) {
                vavooSig?.let { if (now2 - vavooSigAt < SIG_TTL_MS) return it }
            }
            val uid = UUID.randomUUID().toString()
            val body = JSONObject()
                .put("token", "")
                .put("reason", "app-focus")
                .put("locale", "de")
                .put("theme", "dark")
                .put(
                    "metadata",
                    JSONObject()
                        .put("device", JSONObject().put("type", "desktop").put("uniqueId", uid))
                        .put(
                            "os",
                            JSONObject().put("name", "win32").put("version", "Windows 10 Pro")
                                .put("abis", JSONArray().put("x64")).put("host", "Lenovo")
                        )
                        .put("app", JSONObject().put("platform", "electron"))
                        .put(
                            "version",
                            JSONObject().put("package", "tv.vavoo.app").put("binary", "1.3.1").put("js", "1.3.1")
                        )
                )
                .put("appFocusTime", 0)
                .put("playerActive", false)
                .put("playDuration", 0)
                .put("devMode", false)
                .put("hasAddon", true)
                .put("castConnected", false)
                .put("package", "tv.vavoo.app")
                .put("version", "1.3.1")
                .put("process", "app")
                .put("firstAppStart", now2 - 86_400_000L)
                .put("lastAppStart", now2)
                .put("ipLocation", JSONObject.NULL)
                .put("adblockEnabled", true)
                .put("migrationApplied", false)
                .put(
                    "proxy",
                    JSONObject().put("supported", JSONArray().put("ss")).put("engine", "Mu")
                        .put("enabled", false).put("autoServer", true)
                )
                .put("iap", JSONObject().put("supported", false).put("error", ""))
                .toString()
            val headers = mapOf(
                "User-Agent" to VAVOO_PING_UA,
                "Accept" to "application/json",
                "Accept-Language" to "de"
            )
            val resp = httpPostJson(URL_VAVOO_PING, body, headers, 8_000) ?: return vavooSig
            val sig = try { JSONObject(resp).optString("addonSig", "").trim() } catch (_: Exception) { "" }
            if (sig.isNotEmpty()) {
                vavooSig = sig
                vavooSigAt = System.currentTimeMillis()
                return sig
            }
            return vavooSig
        }
    }

    private fun resolveVavooOnce(playUrl: String, sig: String): String? {
        val body = JSONObject()
            .put("language", "de")
            .put("region", "AT")
            .put("url", playUrl)
            .put("clientVersion", "3.0.2")
            .toString()
        val headers = mapOf(
            "User-Agent" to "MediaHubMX/2",
            "Accept" to "application/json",
            "mediahubmx-signature" to sig
        )
        val resp = httpPostJson(URL_VAVOO_RESOLVE, body, headers, 8_000)?.trim() ?: return null
        return try {
            if (resp.startsWith("[")) {
                val arr = JSONArray(resp)
                if (arr.length() > 0) arr.optJSONObject(0)?.optString("url", "")?.takeIf { it.startsWith("http") }
                else null
            } else {
                JSONObject(resp).optString("url", "").takeIf { it.startsWith("http") }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveVavooTsFallback(playUrl: String): String? {
        if (playUrl.length <= 12 || !playUrl.contains("vavoo-iptv")) return null
        val form = "vec=" + URLEncoder.encode(LOKKE_VEC, "UTF-8")
        val resp = httpPostForm(URL_LOKKE_PING, form, mapOf("User-Agent" to "MediaHubMX/2"), 7_000) ?: return null
        val signed = try {
            JSONObject(resp).optJSONObject("response")?.optString("signed", "")?.trim()
        } catch (_: Exception) {
            null
        }
        if (signed.isNullOrEmpty()) return null
        val base = playUrl.replace("vavoo-iptv", "live2").dropLast(12)
        return withHeaders("$base.ts?n=1&b=5&vavoo_auth=$signed", "User-Agent" to "VAVOO/2.6")
    }

    private fun resolveVavoo(playUrl: String): List<String> {
        val out = ArrayList<String>(4)
        var resolved: String? = null
        val sig = getVavooSignature(force = false)
        if (!sig.isNullOrEmpty()) {
            resolved = resolveVavooOnce(playUrl, sig)
            if (resolved == null) {
                val freshSig = getVavooSignature(force = true)
                if (!freshSig.isNullOrEmpty() && freshSig != sig) {
                    resolved = resolveVavooOnce(playUrl, freshSig)
                }
            }
        }
        if (resolved != null) {
            out.add(
                withHeaders(
                    resolved,
                    "User-Agent" to "VAVOO/2.6",
                    "Referer" to "https://vavoo.to/",
                    "Origin" to "https://vavoo.to"
                )
            )
            out.add(
                withHeaders(
                    resolved,
                    "User-Agent" to "iPad",
                    "Referer" to "https://vavoo.to/",
                    "Origin" to "https://vavoo.to"
                )
            )
            out.add(resolved)
        }
        resolveVavooTsFallback(playUrl)?.let { out.add(it) }
        return out.distinct()
    }

    // --- 3) Kool.to Resolver ---

    private fun resolveKool(id: String): List<String> {
        val out = ArrayList<String>(3)
        val resp = httpGet(URL_KOOL_RESOLVE + id, mapOf("User-Agent" to "Mozilla/5.0"), 8_000)
        if (resp != null) {
            val u = try { JSONObject(resp).optString("url", "").trim() } catch (_: Exception) { "" }
            if (u.startsWith("http")) {
                out.add(
                    withHeaders(
                        u,
                        "Referer" to "https://www.kool.to/",
                        "Origin" to "https://www.kool.to",
                        "User-Agent" to "ipad"
                    )
                )
                out.add(u)
            }
        }
        out.add(
            withHeaders(
                "https://www.kool.to/play/$id/index.m3u8",
                "Referer" to "https://www.kool.to/",
                "Origin" to "https://www.kool.to",
                "User-Agent" to "ipad"
            )
        )
        out.add(
            withHeaders(
                "https://oha.to/play/$id/index.m3u8",
                "Referer" to "https://oha.to/",
                "Origin" to "https://oha.to",
                "User-Agent" to "ipad"
            )
        )
        return out.distinct()
    }

    // --- 4) DaddyLive Resolver (port di daddyCode + daddyPremium da myResolver.py di MandraKodi) ---

    private val econfigRe = Regex("""window\._econfig\s*=\s*['"]([^'"]{100,})['"]""")
    private val atobRe = Regex("""window\.atob\(['"]([^'"]+)['"]\)""")
    private val srcVarRe = Regex("""(?:const|let|var)\s+SRC\s*=\s*['"](https?://[^'"]+?)['"]""")
    private val m3u8UrlRe = Regex("""https?://[^\s'"<>\\]+\.m3u8[^\s'"<>\\]*""")
    private val iframeRe = Regex("""<iframe[^>]*\bsrc=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val premiumEdgeRe = Regex("""^(https?://[^/]+)/(premium\d+)/index\.m3u8$""")

    private fun decodeBarecropEconfig(enc: String): String? {
        return try {
            val s = String(Base64.decode(enc, Base64.DEFAULT), Charsets.ISO_8859_1)
            val n = 4
            val order = intArrayOf(2, 0, 3, 1)
            val chunkLen = (s.length + n - 1) / n
            val arr = Array(n) { "" }
            for (i in 0 until n) {
                val start = minOf(i * chunkLen, s.length)
                val end = minOf((i + 1) * chunkLen, s.length)
                val part = s.substring(start, end)
                if (part.length < 4) return null
                val stripped = part.substring(0, 3) + part.substring(4)
                arr[order[i]] = String(Base64.decode(stripped, Base64.DEFAULT), Charsets.ISO_8859_1)
            }
            val finalJson = String(Base64.decode(arr.joinToString(""), Base64.DEFAULT), Charsets.UTF_8)
            val cfg = JSONObject(finalJson)
            cfg.optString("stream_url", "").ifEmpty { cfg.optString("stream_url_nop2p", "") }
                .trim().takeIf { it.startsWith("http") }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeDaddyPlayerPage(html: String): String? {
        econfigRe.find(html)?.groupValues?.get(1)?.let { enc ->
            decodeBarecropEconfig(enc)?.let { return it }
        }
        atobRe.find(html)?.groupValues?.get(1)?.let { b64 ->
            try {
                val u = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8).trim()
                if (u.startsWith("http")) return u
            } catch (_: Exception) {
            }
        }
        srcVarRe.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.startsWith("http") }?.let { return it }
        return m3u8UrlRe.find(html)?.value
    }

    private fun attachPremiumToken(playlistUrl: String): String {
        val clean = playlistUrl.substringBefore('?')
        val m = premiumEdgeRe.matchEntire(clean) ?: return playlistUrl
        val edge = m.groupValues[1]
        val slug = m.groupValues[2]
        val key = "$edge|$slug"
        val now = System.currentTimeMillis()
        val cached = premiumTokens[key]
        var tok = if (cached != null && cached.second > now) cached.first else null
        if (tok == null) {
            val hdrs = mapOf(
                "User-Agent" to DADDY_UA,
                "Accept" to "*/*",
                "Referer" to "https://dembed.st/",
                "Origin" to "https://dembed.st"
            )
            val resp = httpGet("$edge/api/playlist-token?slug=$slug", hdrs, 5_000)
            if (resp != null) {
                try {
                    val j = JSONObject(resp)
                    val t = j.optString("token", "").trim()
                    if (t.isNotEmpty()) {
                        val ttlSec = j.optDouble("ttl", 120.0).coerceAtLeast(20.0)
                        premiumTokens[key] = t to (now + (ttlSec * 500).toLong())
                        tok = t
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (tok.isNullOrEmpty()) return playlistUrl
        val sep = if ('?' in playlistUrl) "&" else "?"
        return "$playlistUrl${sep}t=${URLEncoder.encode(tok, "UTF-8")}"
    }

    private fun resolveRelative(baseUrl: String, href: String): String = try {
        when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("//") -> URI(baseUrl).scheme + ":" + href
            else -> URI(baseUrl).resolve(href).toString()
        }
    } catch (_: Exception) {
        href
    }

    private fun originOf(url: String): String? = try {
        val u = URI(url)
        if (u.scheme != null && u.host != null) "${u.scheme}://${u.authority}" else null
    } catch (_: Exception) {
        null
    }

    private fun isDefacedManifest(body: String): Boolean {
        val low = body.take(8000).lowercase()
        return "hackerman" in low || "null367" in low || "tiktokcdn" in low
    }

    private fun resolveDaddy(codeIn: String): List<String> {
        val out = ArrayList<String>(3)
        val origins = listOf("https://thedaddy.li", "https://thedaddy.pk", "https://dlhd.st", "https://dlhd.pk")
        val players = if (codeIn == "861") listOf("stream", "cast", "watch", "plus", "player")
        else listOf("cast", "stream", "watch", "plus", "player")
        val seenIframes = HashSet<String>()

        outer@ for (origin in origins) {
            var originReachable = false
            for (pl in players) {
                val pUrl = "$origin/$pl/stream-$codeIn.php"
                val page = httpGet(
                    pUrl,
                    mapOf("User-Agent" to DADDY_UA, "Accept" to "*/*", "Referer" to "$origin/"),
                    5_000
                ) ?: continue
                originReachable = true
                val rawIframe = iframeRe.find(page)?.groupValues?.get(1) ?: continue
                var iframe = resolveRelative(pUrl, rawIframe)
                if (!seenIframes.add(iframe)) continue

                val iframeHtml = httpGet(
                    iframe,
                    mapOf("User-Agent" to DADDY_UA, "Accept" to "*/*", "Referer" to pUrl, "Origin" to origin),
                    5_000
                ) ?: continue
                var cand = decodeDaddyPlayerPage(iframeHtml)
                if (cand == null) {
                    val rawIframe2 = iframeRe.find(iframeHtml)?.groupValues?.get(1)
                    if (rawIframe2 != null) {
                        val iframe2 = resolveRelative(iframe, rawIframe2)
                        if (seenIframes.add(iframe2)) {
                            val html2 = httpGet(
                                iframe2,
                                mapOf("User-Agent" to DADDY_UA, "Accept" to "*/*", "Referer" to iframe),
                                5_000
                            )
                            if (html2 != null) {
                                cand = decodeDaddyPlayerPage(html2)
                                if (cand != null) iframe = iframe2
                            }
                        }
                    }
                }
                if (cand == null) continue
                val tokenized = attachPremiumToken(cand)
                val refOrigin = originOf(iframe) ?: origin
                val master = httpGet(
                    tokenized,
                    mapOf(
                        "User-Agent" to DADDY_UA,
                        "Accept" to "*/*",
                        "Referer" to "$refOrigin/",
                        "Origin" to refOrigin
                    ),
                    5_000
                )
                if (master != null && "#EXTM3U" in master.take(4096) && !isDefacedManifest(master)) {
                    out.add(
                        withHeaders(
                            tokenized,
                            "Referer" to "$refOrigin/",
                            "Origin" to refOrigin,
                            "User-Agent" to DADDY_UA
                        )
                    )
                    break@outer
                }
            }
            if (originReachable && out.isNotEmpty()) break
        }

        // Fallback daddyPremium (dokoplay server_lookup)
        val lookup = httpGet(
            "https://dokoplay.xyz/server_lookup.php?channel_id=premium$codeIn",
            mapOf("User-Agent" to CHROME_UA),
            5_000
        )
        if (lookup != null) {
            val server = try { JSONObject(lookup).optString("server_key", "").trim() } catch (_: Exception) { "" }
            if (server.isNotEmpty()) {
                val link = "https://${server}new.kiko2.ru/$server/premium$codeIn/mono.m3u8"
                out.add(
                    withHeaders(
                        link,
                        "Referer" to "https://dokoplay.xyz/",
                        "Origin" to "https://dokoplay.xyz",
                        "User-Agent" to CHROME_UA
                    )
                )
            }
        }
        return out.distinct()
    }

    // ---------- Utilità HTTP ----------

    private fun httpGet(url: String, headers: Map<String, String>, timeoutMs: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun httpPostJson(url: String, jsonBody: String, headers: Map<String, String>, timeoutMs: Int): String? =
        httpPost(url, jsonBody, "application/json; charset=utf-8", headers, timeoutMs)

    private fun httpPostForm(url: String, formBody: String, headers: Map<String, String>, timeoutMs: Int): String? =
        httpPost(url, formBody, "application/x-www-form-urlencoded", headers, timeoutMs)

    private fun httpPost(
        url: String, body: String, contentType: String,
        headers: Map<String, String>, timeoutMs: Int
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", contentType)
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
