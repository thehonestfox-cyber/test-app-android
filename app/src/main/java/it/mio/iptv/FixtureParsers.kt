package it.mio.iptv

import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId

/** Una partita presa dai siti ufficiali delle leghe. [start] = inizio in millisecondi; [round] = giornata (0 = non nota). */
data class Fixture(val league: String, val round: Int, val home: String, val away: String, val start: Long)

/** Una riga della guida Sky alla Serie C: "LIVORNO-GROSSETO" e i canali Sky dove si vede (es. "Sky Sport Arena", "Sky Sport 251"). */
data class SkyMatch(val teams: String, val channels: List<String>)

/**
 * Lettura delle pagine dei siti ufficiali (solo testo/HTML, nessuna API):
 *  - Serie A: sport.sky.it/calcio/serie-a/calendario-risultati
 *  - Serie B: legab.it/seriebkt
 *  - Serie C: seriec.com (partite) + sport.sky.it (canale Sky di ogni partita)
 * Niente di Android qui dentro, cosi' si puo' provare anche su un computer.
 */
object FixtureParsers {
    val ROME: ZoneId = ZoneId.of("Europe/Rome")

    /** Dopo questo tempo dall'inizio una partita si considera finita. */
    const val FINISH_MS = 3 * 3_600_000L

    private const val IMG = "§IMG§"

    private val scriptRe = Regex("""(?is)<(script|style)\b.*?</\1>""")
    private val commentRe = Regex("""(?s)<!--.*?-->""")
    private val tagRe = Regex("""(?s)<[^>]*>""")
    private val imgRe = Regex("""(?is)<img\b[^>]*>""")
    private val numEntRe = Regex("""&#(x?)([0-9a-fA-F]+);""")
    private val spaceRe = Regex("""\s+""")
    private val accentRe = Regex("""\p{M}+""")

    private val entities = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&quot;" to "\"", "&apos;" to "'", "&ordf;" to "ª", "&deg;" to "°",
        "&agrave;" to "à", "&egrave;" to "è", "&eacute;" to "é", "&igrave;" to "ì", "&ograve;" to "ò",
        "&ugrave;" to "ù", "&ouml;" to "ö", "&uuml;" to "ü", "&auml;" to "ä"
    )

    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accentRe, "")

    private fun decode(s: String): String {
        var t = numEntRe.replace(s) { m ->
            try {
                String(Character.toChars(m.groupValues[2].toInt(if (m.groupValues[1].isEmpty()) 10 else 16)))
            } catch (_: Exception) {
                " "
            }
        }
        for ((k, v) in entities) t = t.replace(k, v)
        return t
    }

    /** Testo visibile: via script/stili/commenti; ogni tag diventa un a-capo; le immagini lasciano un segnaposto. */
    private fun visibleWords(html: String, keepImages: Boolean): List<String> {
        var t = scriptRe.replace(html, " ")
        t = commentRe.replace(t, " ")
        t = imgRe.replace(t, if (keepImages) "\n$IMG\n" else "\n")
        t = tagRe.replace(t, "\n")
        return spaceRe.split(decode(t)).filter { it.isNotEmpty() }
    }

    /** Come l'HTML ma con i tag (e gli script) sostituiti da spazi: stessa lunghezza, quindi le posizioni coincidono. */
    private fun blankTags(html: String): String {
        fun blank(m: MatchResult) = " ".repeat(m.value.length)
        var t = scriptRe.replace(html, ::blank)
        t = commentRe.replace(t, ::blank)
        return tagRe.replace(t, ::blank)
    }

    /** "ATALANTA UNDER 23" -> "Atalanta Under 23" */
    fun pretty(s: String): String =
        s.trim().split(spaceRe).filter { it.isNotEmpty() }.joinToString(" ") { w ->
            val l = w.lowercase()
            l.replaceFirstChar { it.titlecase() }
        }

    private fun toMs(d: LocalDate, h: Int, m: Int): Long =
        d.atTime(h, m).atZone(ROME).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ Serie C (seriec.com)

    private fun isCode(w: String) = w.length in 2..4 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() }

    private val cMarkers = setOf("Risultati", "GIRONE", "Prossime", "Classifiche", "Completa", "Caricamento...")

    /**
     * Home di seriec.com: per ogni girone "Prossime Partite" con righe tipo
     *   "11ª | 24/10 14:30", stemma, "TREVISO TRE", loghi TV, "DOL DOLOMITI BELLUNESI", stemma.
     * La squadra di casa e' "NOME SIGLA", quella ospite "SIGLA NOME".
     */
    fun parseSerieC(html: String, today: LocalDate): List<Fixture> {
        val words = visibleWords(html, keepImages = true)
        val dateRe = Regex("""^(\d{1,2})/(\d{1,2})$""")
        val timeRe = Regex("""^(\d{1,2}):(\d{2})$""")
        val roundRe = Regex("""^(\d{1,2})[ªº°]$""")
        val idx = ArrayList<Int>()
        for (i in 0 until words.size - 1) if (dateRe.matches(words[i]) && timeRe.matches(words[i + 1])) idx.add(i)

        val out = ArrayList<Fixture>()
        for ((k, i) in idx.withIndex()) {
            var round = 0
            for (b in 1..3) {
                val w = words.getOrNull(i - b) ?: break
                roundRe.find(w)?.let { round = it.groupValues[1].toInt() }
                if (round > 0) break
            }
            val dm = dateRe.find(words[i])!!
            val tm = timeRe.find(words[i + 1])!!
            val day = dm.groupValues[1].toInt()
            val mon = dm.groupValues[2].toInt()
            val h = tm.groupValues[1].toInt()
            val mi = tm.groupValues[2].toInt()
            if (mon !in 1..12 || day !in 1..31 || h > 23 || mi > 59) continue
            val date = (-1..1).mapNotNull { dy ->
                try { LocalDate.of(today.year + dy, mon, day) } catch (_: Exception) { null }
            }.minByOrNull { Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, it)) } ?: continue

            var seg = words.subList(i + 2, if (k + 1 < idx.size) idx[k + 1] else words.size).toMutableList()
            while (seg.isNotEmpty() && (seg.last() == "|" || roundRe.matches(seg.last()))) seg.removeAt(seg.size - 1)
            val cut = seg.indexOfFirst { it in cMarkers }
            if (cut >= 0) seg = seg.subList(0, cut).toMutableList()

            val teams = splitC(seg) ?: continue
            out.add(Fixture("C", round, pretty(teams.first), pretty(teams.second), toMs(date, h, mi)))
        }
        return out.distinct()
    }

    private fun splitC(seg: List<String>): Pair<String, String>? {
        // 1) dai segnaposto delle immagini: [stemma] CASA [loghi TV] OSPITE [stemma]
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (w in seg) {
            if (w == IMG) {
                if (cur.isNotEmpty()) groups.add(cur)
                cur = ArrayList()
            } else cur.add(w)
        }
        if (cur.isNotEmpty()) groups.add(cur)
        if (groups.size == 2) {
            val home = groups[0].let { if (it.size > 1 && isCode(it.last())) it.dropLast(1) else it }
            val away = groups[1].let { if (it.size > 1 && isCode(it[0])) it.drop(1) else it }
            if (home.isNotEmpty() && away.isNotEmpty()) return home.joinToString(" ") to away.joinToString(" ")
        }
        // 2) senza immagini: due sigle di fila (quella di casa e quella ospite) separano i due nomi
        val s = seg.filter { it != IMG }
        for (i in 1 until s.size - 2) {
            if (isCode(s[i]) && isCode(s[i + 1]) && !isCode(s[i + 2])) {
                return s.subList(0, i).joinToString(" ") to s.subList(i + 2, s.size).joinToString(" ")
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Serie B (legab.it)

    private val months = listOf(
        "gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
        "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"
    )

    private fun slugTeam(s: String): String = pretty(
        s.split('-').joinToString(" ") { w ->
            when (w) {
                "lr" -> "L.R."
                "sudtirol" -> "Südtirol"
                else -> w
            }
        }
    ).replace("L.r.", "L.R.")

    /**
     * Home di legab.it: blocchi "venerdi 23 ottobre 2026, ore 20:30 ... Ore 20:30" con il link della partita
     * (.../partita/2026-2027/stagione-regolare/empoli-vs-lr-vicenza). Le squadre si leggono dal link.
     * Nel blocco "Prossimo turno" la frase ha l'ora in UTC e il campo "Ore" quella locale: si usa "Ore".
     */
    fun parseSerieB(html: String): List<Fixture> {
        val flat = blankTags(html)
        val linkRe = Regex("""/seriebkt/partita/[^"'\s/<>]+/[^"'\s/<>]+/([a-z0-9\-]+)""")
        val links = linkRe.findAll(html).map { it.range.first to it.groupValues[1] }.toList()
        val dateRe = Regex("""(\d{1,2})\s+(${months.joinToString("|")})\s+(20\d\d)""", RegexOption.IGNORE_CASE)
        val dates = dateRe.findAll(flat).toList()
        val roundRe = Regex("""(\d{1,2})\s*(?:a|ª|º|°|&ordf;)\s+giornata""", RegexOption.IGNORE_CASE)
        val rounds = roundRe.findAll(flat).map { it.range.first to it.groupValues[1].toInt() }.toList()
        if (links.isEmpty() || dates.isEmpty()) return emptyList()

        // il link sta dopo la data (o prima): si sceglie la regola per cui ogni data ha esattamente un link
        fun bounds(i: Int) = dates[i].range.first to (if (i + 1 < dates.size) dates[i + 1].range.first else Int.MAX_VALUE)
        val after = dates.indices.map { i ->
            val (a, b) = bounds(i)
            links.filter { it.first in a until b }
        }
        val before = dates.indices.map { i ->
            val a = if (i > 0) dates[i - 1].range.first else -1
            val b = dates[i].range.first
            links.filter { it.first in (a + 1) until b }
        }
        val chosen = when {
            after.all { it.size == 1 } -> after
            before.all { it.size == 1 } -> before
            else -> after
        }

        val out = ArrayList<Fixture>()
        val tzTimeRe = Regex("""\bOre\s+(\d{1,2})[:.](\d{2})""")
        val sentenceTimeRe = Regex("""ore:?\s*(\d{1,2})[:.](\d{2})""", RegexOption.IGNORE_CASE)
        for (i in dates.indices) {
            val link = chosen[i].firstOrNull() ?: continue
            val d = dates[i]
            val end = if (i + 1 < dates.size) dates[i + 1].range.first else minOf(flat.length, d.range.first + 900)
            val win = spaceRe.replace(flat.substring(d.range.first, minOf(end, flat.length)), " ")
            val tm = tzTimeRe.find(win) ?: sentenceTimeRe.find(win) ?: continue
            val h = tm.groupValues[1].toInt()
            val mi = tm.groupValues[2].toInt()
            if (h > 23 || mi > 59) continue
            val mon = months.indexOf(d.groupValues[2].lowercase()) + 1
            val date = try {
                LocalDate.of(d.groupValues[3].toInt(), mon, d.groupValues[1].toInt())
            } catch (_: Exception) {
                continue
            }
            val slug = link.second
            val cut = slug.indexOf("-vs-")
            if (cut <= 0) continue
            val round = rounds.lastOrNull { it.first < d.range.first }?.second ?: 0
            out.add(
                Fixture("B", round, slugTeam(slug.substring(0, cut)), slugTeam(slug.substring(cut + 4)), toMs(date, h, mi))
            )
        }
        return out.distinct()
    }

    // ------------------------------------------------------------------ Serie A (sport.sky.it)

    private val itMonths = mapOf(
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6,
        "lug" to 7, "ago" to 8, "set" to 9, "ott" to 10, "nov" to 11, "dic" to 12
    )

    /** "Torino Torino" (nome ripetuto per le due versioni dell'immagine) -> "Torino". */
    private fun undouble(w: List<String>): List<String> {
        if (w.size >= 2 && w.size % 2 == 0 && w.subList(0, w.size / 2) == w.subList(w.size / 2, w.size)) return w.subList(0, w.size / 2)
        return w
    }

    /**
     * Calendario di sport.sky.it/calcio/serie-a/calendario-risultati (la pagina della Lega e' fatta in JavaScript e
     * nel codice non ha le partite). Ogni partita ha un link .../partite/2026/giornata-8/torino-monza; dopo il link
     * ci sono casa, orario (se non e' ancora iniziata, altrimenti il risultato) e ospite; la data e' nel titolo del
     * giorno ("Sabato 24 Ott 2026") che precede le partite. Le partite gia' giocate (con risultato) si saltano.
     */
    fun parseSerieA(html: String): List<Fixture> {
        val flat = blankTags(html)
        val linkRe = Regex("""/calcio/serie-a/partite/\d{4}/giornata-(\d+)/([a-z0-9\-]+)""")
        val links = linkRe.findAll(html).toList()
        if (links.isEmpty()) return emptyList()
        val headRe = Regex("""[\p{L}&;]{6,14}\s+(\d{1,2})\s+([A-Za-z]{3})\s+(20\d\d)""")
        val heads = headRe.findAll(flat).filter { itMonths.containsKey(it.groupValues[2].lowercase()) }.toList()
        val timeRe = Regex("""^(\d{1,2}):(\d{2})$""")
        val out = LinkedHashMap<String, Fixture>()
        for ((k, m) in links.withIndex()) {
            val start = m.range.first
            val round = m.groupValues[1].toInt()
            val key = "$round|${m.groupValues[2]}"
            if (out.containsKey(key)) continue
            val head = heads.lastOrNull { it.range.first < start } ?: continue
            var end = if (k + 1 < links.size) links[k + 1].range.first else flat.length
            heads.firstOrNull { it.range.first > start }?.let { if (it.range.first < end) end = it.range.first }
            val words = spaceRe.split(decode(flat.substring(start, minOf(end, flat.length)))).filter { it.isNotEmpty() }
            val cut = words.indexOfFirst { it == "Giornata" || it == "Link" }
            val w = if (cut >= 0) words.subList(0, cut) else words
            val ti = w.indexOfFirst { timeRe.matches(it) }
            if (ti <= 0) continue
            val home = undouble(w.subList(0, ti)).joinToString(" ")
            val away = undouble(w.subList(ti + 1, w.size)).joinToString(" ")
            if (home.isEmpty() || away.isEmpty()) continue
            val tm = timeRe.find(w[ti])!!
            val h = tm.groupValues[1].toInt()
            val mi = tm.groupValues[2].toInt()
            if (h > 23 || mi > 59) continue
            val date = try {
                LocalDate.of(head.groupValues[3].toInt(), itMonths[head.groupValues[2].lowercase()]!!, head.groupValues[1].toInt())
            } catch (_: Exception) {
                continue
            }
            out[key] = Fixture("A", round, home, away, toMs(date, h, mi))
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ Canali Sky della Serie C (sport.sky.it)

    private val skyLinkRe =
        Regex("""/calcio/serie-c/(\d{4})/(\d{2})/(\d{2})/serie-c-partite-sky-[a-z0-9\-]*giornata-(\d+)""")
    private val blockEndRe = Regex("""(?is)</(?:li|p|h[1-6]|div|tr|ul|ol)\s*>|<br\s*/?>""")
    private val skyLineRe = Regex("""(?i)([^\n,]{3,70}?)\s*,\s*in diretta su\s+([^\n]+)""")
    private val skyChanRe = Regex("""(?i)\bSky\s+Sp(?:ort|ot)\s+([A-Za-zÀ-ÿ]+|\d{1,3})\b""")
    private val bulletRe = Regex("""[*•]""")

    /** Indirizzi degli articoli "Serie C, il calendario della N^ giornata su Sky" trovati in una pagina di sport.sky.it. */
    fun skyGuideLinks(html: String): List<String> =
        skyLinkRe.findAll(html).map { "https://sport.sky.it" + it.value }.distinct().toList()

    /** Per mettere gli articoli dal piu' recente: data (aaaammgg) e numero della giornata. */
    fun skyLinkStamp(url: String): Long {
        val m = skyLinkRe.find(url) ?: return 0L
        return (m.groupValues[1] + m.groupValues[2] + m.groupValues[3]).toLong() * 100L + m.groupValues[4].toLong()
    }

    /**
     * Articolo della guida Sky: righe tipo "LIVORNO-GROSSETO, in diretta su Sky Sport Arena e Sky Sport 251".
     * Ogni riga diventa una partita con l'elenco dei canali Sky (anche se la pagina scrive "Sky Spot 252").
     * Le righe senza i due nomi o senza canali Sky si saltano.
     */
    fun parseSkyGuide(html: String): List<SkyMatch> {
        var t = scriptRe.replace(html, " ")
        t = commentRe.replace(t, " ")
        t = blockEndRe.replace(t, "\n")
        t = tagRe.replace(t, "")
        t = decode(t)
        val out = LinkedHashMap<String, SkyMatch>()
        for (line in t.lines()) {
            val m = skyLineRe.find(line) ?: continue
            val teams = bulletRe.replace(m.groupValues[1], "").replace(spaceRe, " ").trim().trim('-', ' ')
            if (!teams.contains('-') && !teams.contains('\u2013')) continue
            val chans = skyChanRe.findAll(m.groupValues[2]).map { "Sky Sport " + pretty(it.groupValues[1]) }.distinct().toList()
            if (chans.isEmpty()) continue
            out.putIfAbsent(fold(teams), SkyMatch(teams, chans))
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------------ "giornata per giornata"

    /**
     * Da tutte le partite note sceglie quelle della giornata in corso: la prima giornata con almeno una partita
     * non ancora finita (le giornate con una sola partita, tipo i recuperi, non contano come "giornata").
     * Dentro la giornata restano anche le partite gia' finite (card grigie); si aggiungono i recuperi che cadono
     * nei suoi stessi giorni. Se la giornata non e' nota (round = 0) valgono i prossimi 7 giorni.
     */
    fun pickCurrent(all: List<Fixture>, now: Long): List<Fixture> {
        if (all.isEmpty()) return emptyList()
        val byRound = all.filter { it.round > 0 }.groupBy { it.round }
        if (byRound.isEmpty()) {
            return all.filter { it.start + FINISH_MS > now - 12 * 3_600_000L && it.start < now + 7 * 86_400_000L }
                .sortedBy { it.start }
        }
        val candidates = byRound.filter { it.value.size >= 2 }.ifEmpty { byRound }
        val main = candidates.entries
            .filter { e -> e.value.any { it.start + FINISH_MS > now } }
            .minByOrNull { e -> e.value.minOf { it.start } }
        if (main == null) {
            val last = candidates.entries.maxByOrNull { e -> e.value.maxOf { it.start } } ?: return emptyList()
            return if (last.value.maxOf { it.start } > now - 24 * 3_600_000L) last.value.sortedBy { it.start } else emptyList()
        }
        val lo = main.value.minOf { it.start }
        val hi = main.value.maxOf { it.start }
        val extra = all.filter { it.round != main.key && it.start in lo..hi }
        return (main.value + extra).sortedBy { it.start }
    }
}
