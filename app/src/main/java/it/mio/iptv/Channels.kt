package it.mio.iptv

import android.content.Context
import java.text.Normalizer

/** Un canale già "pulito": nome senza FHD/HD, categoria e loghi candidati. */
data class Channel(
    val name: String,        // es. "RAI 1"
    val rawName: String,     // es. "RAI 1 FHD" (come scritto nella playlist)
    val quality: String,     // "4K", "FHD", "HD", "SD" oppure ""
    val category: String,    // es. "Canali TV", "SKY"
    val url: String,
    val logos: List<String>, // URL da provare in ordine
    val time: String = ""    // orario di inizio scritto a mano (es. "20:45"); vuoto = si prende da Google
)

/** Legge una playlist M3U e crea i Channel (con categoria e logo automatici). */
object M3U {
    private val groupRe = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val logoRe = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""
        var group = ""
        var logo = ""
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF")) {
                name = if (l.contains(',')) l.substringAfterLast(',').trim() else ""
                group = groupRe.find(l)?.groupValues?.get(1)?.trim() ?: ""
                logo = logoRe.find(l)?.groupValues?.get(1)?.trim() ?: ""
            } else if (!l.startsWith("#")) {
                out.add(Categorizer.build(name.ifEmpty { l }, group, logo, l))
                name = ""
                group = ""
                logo = ""
            }
        }
        return out
    }
}

/**
 * Pulisce i nomi e assegna la categoria in automatico.
 * Per aggiungere/cambiare categorie basta modificare la lista `rules` qui sotto:
 * vince la prima regola che corrisponde al nome del canale.
 */
object Categorizer {
    const val ALTRI = "Altri"

    // Ordine in cui compaiono le categorie (quelle non elencate vanno dopo, in ordine alfabetico)
    private val ORDER = listOf(
        "Canali TV", "SKY", "Sport", "Cinema e Serie", "Notizie", "Bambini", "Musica", "Documentari"
    )

    private val rules: List<Pair<String, Regex>> = listOf(
        "SKY" to Regex("""\bSKY\b"""),
        "Canali TV" to Regex(
            """^(RAI|CANALE ?5|ITALIA ?1|RETE ?4|LA ?7D?|LA ?7 ?CINEMA|LA ?5|TV ?8|NOVE|20|IRIS|MEDIASET|CIELO|""" +
                """REAL ?TIME|DMAX|GIALLO|TOP ?CRIME|FOCUS|WARNER ?TV|K2|FRISBEE|BOING|PARAMOUNT|TGCOM ?24|""" +
                """CINE ?34|CARTOONITO|SUPER ?TENNIS|TWENTY ?SEVEN|CANALE ?DIECI|CANALE ?OTTO|DEEJAY ?TV|""" +
                """CANALE ITALIA|RTL ?102\.?5|MTV|COMEDY CENTRAL|FOOD NETWORK|HGTV|MOTOR ?TREND|TV ?2000)\b"""
        ),
        "Sport" to Regex(
            """\b(DAZN|EUROSPORT|SPORT|CALCIO|SERIE A|SERIE B|F1|MOTOGP|TENNIS|BASKET|NBA|NFL|ARENA|""" +
                """PREMIER LEAGUE|LA LIGA|CHAMPIONS|ZONA|BEIN|RACING|GOLF)\b"""
        ),
        "Cinema e Serie" to Regex(
            """\b(CINEMA|MOVIE|MOVIES|FILM|PREMIUM|CRIME|SERIE|SERIES|FOX|AXN|COMEDY|HBO|ACTION|HORROR|""" +
                """THRILLER|DRAMA|ROMANCE|CINE)\b"""
        ),
        "Notizie" to Regex("""\b(NEWS|TG|TG24|NOTIZIE|EURONEWS|CNN|BBC|AL JAZEERA|BLOOMBERG|CNBC)\b"""),
        "Bambini" to Regex(
            """\b(KIDS|BAMBINI|CARTOON|CARTOONS|DISNEY|NICK|NICKELODEON|BABY|JUNIOR|DEAKIDS|DEA KIDS|""" +
                """GIM|BOOMERANG|TOONS)\b"""
        ),
        "Musica" to Regex(
            """\b(MTV|MUSIC|MUSICA|RADIO|VH1|HIT|HITS|DEEJAY|RTL|KISS|VIRGIN|CAPITAL|M2O|TRACE|HIP HOP)\b"""
        ),
        "Documentari" to Regex(
            """\b(DISCOVERY|HISTORY|NATIONAL GEOGRAPHIC|NAT GEO|DOCUMENTARIES|DOCUMENTARI|NATURE|GEO|""" +
                """ANIMAL|SCIENCE|CACCIA|PESCA)\b"""
        )
    )

    private val genericGroups = setOf("undefined", "uncategorized", "other", "others", "altri", "general", "")

    private val brackets = Regex("""[\[(][^\])]*[\])]""")
    private val langPrefix = Regex("""^\s*[|\[]?\s*(IT|ITA|ITALIA|ITALY)\s*[|\]:\-]+\s*""", RegexOption.IGNORE_CASE)
    private val q4k = Regex("""\b(4K|UHD)\b""", RegexOption.IGNORE_CASE)
    private val qFhd = Regex("""\b(FHD|FULL HD)\b""", RegexOption.IGNORE_CASE)
    private val qHd = Regex("""\b(HD|720P?)\b""", RegexOption.IGNORE_CASE)
    private val qSd = Regex("""\bSD\b""", RegexOption.IGNORE_CASE)
    private val junk = Regex(
        """\b(4K|UHD|FHD|FULL HD|HD|SD|HEVC|H265|H264|HDR|1080P?|720P?|50FPS|60FPS)\b""",
        RegexOption.IGNORE_CASE
    )
    private val spaces = Regex("""\s+""")
    private val tok = Regex("""\d+|\D+""")

    private fun stripAccents(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")

    /** "IT: RAI 1 FHD [50fps]" -> ("RAI 1", "FHD") */
    fun clean(raw: String): Pair<String, String> {
        var s = raw
        val quality = when {
            q4k.containsMatchIn(s) -> "4K"
            qFhd.containsMatchIn(s) -> "FHD"
            qHd.containsMatchIn(s) -> "HD"
            qSd.containsMatchIn(s) -> "SD"
            else -> ""
        }
        s = langPrefix.replace(s, "")
        s = junk.replace(s, " ")
        s = brackets.replace(s, " ")
        s = spaces.replace(s.replace('_', ' '), " ").trim().trim('-', '|', ':', ' ')
        return (if (s.isEmpty()) raw.trim() else s) to quality
    }

    private fun categorize(cleanName: String, group: String): String {
        val n = stripAccents(cleanName).uppercase()
        for ((cat, re) in rules) if (re.containsMatchIn(n)) return cat
        val g = group.trim()
        return if (g.lowercase() in genericGroups) ALTRI else g
    }

    fun build(rawName: String, group: String, logo: String, url: String): Channel {
        val (name, quality) = clean(rawName)
        val logos = mutableListOf<String>()
        if (logo.startsWith("http", ignoreCase = true)) logos.add(logo)
        Logos.repoUrl(name)?.let { logos.add(it) }
        return Channel(name, rawName, quality, categorize(name, group), url, logos)
    }

    fun sortCategories(cats: Collection<String>): List<String> =
        cats.sortedWith(compareBy<String>(
            { if (it == ALTRI) 2 else if (it in ORDER) 0 else 1 },
            { ORDER.indexOf(it) },
            { it.lowercase() }
        ))

    private fun qualityRank(q: String) = when (q) { "4K" -> 5; "FHD" -> 4; "HD" -> 3; "" -> 2; else -> 1 }

    private fun tokens(s: String) = tok.findAll(s.lowercase()).map { it.value }.toList()

    /** Ordine "naturale": RAI 1, RAI 2, ... RAI 10 (e a parità di nome, qualità migliore prima). */
    val channelOrder = Comparator<Channel> { a, b ->
        val c = natural(tokens(a.name), tokens(b.name))
        if (c != 0) c else qualityRank(b.quality) - qualityRank(a.quality)
    }

    /** Come channelOrder ma veloce: spezza i nomi una volta sola (da usare per liste grandi). */
    fun sortChannels(list: List<Channel>): List<Channel> =
        list.map { it to tokens(it.name) }
            .sortedWith(Comparator { a, b ->
                val c = natural(a.second, b.second)
                if (c != 0) c else qualityRank(b.first.quality) - qualityRank(a.first.quality)
            })
            .map { it.first }

    private fun natural(x: List<String>, y: List<String>): Int {
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val t = p.trimStart('0')
                val u = q.trimStart('0')
                if (t.length != u.length) t.length - u.length else t.compareTo(u)
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

/**
 * Loghi di riserva: se la playlist non ha tvg-logo, cerca il canale nell'elenco
 * (assets/logos_it.txt) dell'archivio pubblico github.com/tv-logo/tv-logos.
 */
object Logos {
    private const val BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/italy/"

    @Volatile
    private var index: Map<String, String> = emptyMap()

    private val aliases = mapOf(
        "raiuno" to "rai1", "raidue" to "rai2", "raitre" to "rai3",
        "raiquattro" to "rai4", "raicinque" to "rai5", "20mediaset" to "20"
    )
    private val hdTag = Regex("""-(hd|hz)(?=-|$)""")

    private fun compact(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun init(ctx: Context) {
        if (index.isNotEmpty()) return
        val files = ctx.assets.open("logos_it.txt").bufferedReader().readLines()
            .map { it.trim() }.filter { it.endsWith(".png") }
        fun key(path: String): String {
            val base = path.substringAfterLast('/').removeSuffix(".png").removeSuffix("-it")
            return compact(hdTag.replace(base, ""))
        }
        val map = mutableMapOf<String, String>()
        // prima le versioni normali, poi quelle HD solo se mancano
        files.filter { !it.startsWith("hd/") }.forEach { map.putIfAbsent(key(it), it) }
        files.filter { it.startsWith("hd/") }.forEach { map.putIfAbsent(key(it), it) }
        index = map
    }

    fun repoUrl(cleanName: String): String? {
        val k = compact(cleanName)
        val path = index[aliases[k] ?: k] ?: return null
        return BASE + path
    }
}
