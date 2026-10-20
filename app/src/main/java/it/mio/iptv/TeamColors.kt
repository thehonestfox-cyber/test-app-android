package it.mio.iptv

import android.graphics.Color
import java.text.Normalizer

/**
 * Colori sociali delle squadre (Serie A, B e C): [principale, secondario].
 * Lo sfondo della thumb usa il principale; se e' troppo chiaro (es. il giallo del Verona, il bianco di Juventus/Udinese)
 * usa il secondario, cosi' lo stemma non si perde sul suo stesso colore.
 * Una squadra che non e' nell'elenco NON riceve un colore inventato: la thumb resta grigia.
 * Per aggiungerne una basta una riga: "nome" to (0xRRGGBB, 0xRRGGBB).
 */
object TeamColors {
    private val accentRe = Regex("""\p{M}+""")
    private val nonAlnumRe = Regex("""[^a-z0-9]+""")
    private val stop = setOf("fc", "ac", "as", "ss", "ssc", "us", "afc", "cf", "sc", "bc", "ud", "cfc", "asd", "calcio", "football", "club", "1907", "1909", "1913", "1919", "1927", "1928", "1898", "1905", "1903", "1893")

    private const val BIANCO = 0xFFFFFF
    private const val NERO = 0x1A1A1A
    private const val ROSSO = 0xC8102E
    private const val GRANATA = 0x7B1E2B
    private const val AZZURRO = 0x12A0D7
    private const val CELESTE = 0x5BA4D8
    private const val BLU = 0x0B2A6B
    private const val GIALLO = 0xFFD100
    private const val VERDE = 0x00853E
    private const val VIOLA = 0x5B2C83

    private val map: Map<String, Pair<Int, Int>> = mapOf(
        // ---- Serie A ----
        "atalanta" to (NERO to 0x1E71B8),
        "bologna" to (0x1A2F6B to ROSSO),
        "cagliari" to (ROSSO to BLU),
        "como" to (0x1B4F9C to BIANCO),
        "cremonese" to (ROSSO to 0x8C8C8C),
        "fiorentina" to (VIOLA to BIANCO),
        "genoa" to (ROSSO to BLU),
        "hellas verona" to (GIALLO to BLU),
        "verona" to (GIALLO to BLU),
        "inter" to (0x0B3C7D to NERO),
        "inter milan" to (0x0B3C7D to NERO),
        "internazionale" to (0x0B3C7D to NERO),
        "juventus" to (BIANCO to NERO),
        "lazio" to (CELESTE to BIANCO),
        "lecce" to (ROSSO to GIALLO),
        "milan" to (ROSSO to NERO),
        "napoli" to (AZZURRO to BIANCO),
        "parma" to (GIALLO to BLU),
        "pisa" to (NERO to 0x2E86C1),
        "roma" to (GRANATA to 0xF0BC42),
        "sassuolo" to (VERDE to NERO),
        "torino" to (GRANATA to BIANCO),
        "udinese" to (BIANCO to NERO),
        // ---- Serie B ----
        "avellino" to (VERDE to BIANCO),
        "bari" to (ROSSO to BIANCO),
        "carrarese" to (0xF2C400 to BLU),
        "catanzaro" to (ROSSO to GIALLO),
        "cesena" to (NERO to BIANCO),
        "empoli" to (AZZURRO to BIANCO),
        "virtus entella" to (CELESTE to BIANCO),
        "entella" to (CELESTE to BIANCO),
        "frosinone" to (GIALLO to AZZURRO),
        "juve stabia" to (GIALLO to BLU),
        "mantova" to (ROSSO to BIANCO),
        "modena" to (GIALLO to BLU),
        "monza" to (ROSSO to BIANCO),
        "padova" to (ROSSO to BIANCO),
        "palermo" to (0xF4A6B8 to NERO),
        "pescara" to (CELESTE to BIANCO),
        "reggiana" to (GRANATA to BIANCO),
        "sampdoria" to (0x1B5FAA to BIANCO),
        "spezia" to (NERO to BIANCO),
        "sudtirol" to (ROSSO to BIANCO),
        "venezia" to (NERO to VERDE),
        "lr vicenza" to (ROSSO to BIANCO),
        "vicenza" to (ROSSO to BIANCO),
        "cittadella" to (GRANATA to BIANCO),
        "bolzano" to (ROSSO to BIANCO),
        // ---- Serie C ----
        "albinoleffe" to (0x1B7FC4 to BIANCO),
        "arezzo" to (GRANATA to BIANCO),
        "ascoli" to (NERO to BIANCO),
        "benevento" to (ROSSO to GIALLO),
        "brescia" to (0x1B7FC4 to BIANCO),
        "catania" to (ROSSO to AZZURRO),
        "cosenza" to (ROSSO to BLU),
        "crotone" to (ROSSO to BLU),
        "foggia" to (ROSSO to NERO),
        "lecco" to (BLU to CELESTE),
        "livorno" to (GRANATA to BIANCO),
        "novara" to (AZZURRO to BIANCO),
        "perugia" to (ROSSO to BIANCO),
        "picerno" to (ROSSO to BLU),
        "pontedera" to (GRANATA to NERO),
        "potenza" to (ROSSO to BLU),
        "ravenna" to (ROSSO to GIALLO),
        "salernitana" to (GRANATA to BIANCO),
        "siracusa" to (AZZURRO to BIANCO),
        "ternana" to (ROSSO to VERDE),
        "torres" to (ROSSO to BLU),
        "trapani" to (GRANATA to BIANCO),
        "treviso" to (CELESTE to BIANCO),
        "triestina" to (GRANATA to BIANCO),
        "vis pesaro" to (ROSSO to BIANCO),
        "carpi" to (ROSSO to BIANCO),
        "forli" to (ROSSO to BIANCO),
        "rimini" to (ROSSO to BIANCO),
        "sorrento" to (ROSSO to NERO),
        "lucchese" to (ROSSO to NERO),
        "messina" to (ROSSO to GIALLO),
        "latina" to (NERO to AZZURRO),
        "gubbio" to (ROSSO to BLU),
        "campobasso" to (ROSSO to BLU),
        "casertana" to (ROSSO to BLU),
        "sambenedettese" to (ROSSO to BLU),
        "altamura" to (ROSSO to BIANCO),
        "casarano" to (ROSSO to GIALLO),
        "monopoli" to (VERDE to BIANCO),
        "milan futuro" to (ROSSO to NERO),
        "atalanta u23" to (NERO to 0x1E71B8),
        "atalanta under 23" to (NERO to 0x1E71B8),
        "inter u23" to (0x0B3C7D to NERO),
        "inter under 23" to (0x0B3C7D to NERO),
        "juventus next gen" to (BIANCO to NERO),
        "juventus u23" to (BIANCO to NERO),
        "juventus under 23" to (BIANCO to NERO)
    ).mapKeys { norm(it.key) }

    private val youthRe = Regex("""\b(u23|u21|under|next|gen|futuro|primavera)\b""")

    private fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accentRe, "")
        return n.replace(nonAlnumRe, " ").trim().split(' ')
            .filter { it.isNotEmpty() && it !in stop }
            .joinToString(" ")
    }

    private fun lookup(team: String): Pair<Int, Int>? {
        val n = norm(team)
        if (n.isEmpty()) return null
        map[n]?.let { return it }
        if (youthRe.containsMatchIn(n)) return null              // squadre giovanili: solo se elencate
        // nome piu' lungo che contiene un nome elencato come parole intere (es. "Calcio Padova" -> "padova")
        val words = " $n "
        return map.entries.filter { (k, _) -> " $k " in words }.maxByOrNull { it.key.length }?.value
    }

    private fun luminance(rgb: Int): Float =
        0.2126f * Color.red(rgb) / 255f + 0.7152f * Color.green(rgb) / 255f + 0.0722f * Color.blue(rgb) / 255f

    private fun argb(rgb: Int) = 0xFF000000.toInt() or rgb

    /** Colore di sfondo per la squadra, dai suoi colori sociali; null se la squadra non e' nell'elenco. */
    fun background(team: String): Int? {
        val (main, second) = lookup(team) ?: return null
        return argb(if (luminance(main) > 0.62f) second else main)
    }

    /** Colore alternativo (l'altro colore sociale), per quando le due squadre avrebbero lo stesso sfondo. */
    fun alternative(team: String): Int? {
        val (main, second) = lookup(team) ?: return null
        return argb(if (luminance(main) > 0.62f) main else second)
    }
}
