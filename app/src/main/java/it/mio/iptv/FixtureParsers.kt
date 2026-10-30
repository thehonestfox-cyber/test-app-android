package it.mio.iptv

import java.text.Normalizer
import java.time.ZoneId

/** Una partita presa dai siti ufficiali delle leghe. [start] = inizio in millisecondi; [round] = giornata (0 = non nota). */
data class Fixture(val league: String, val round: Int, val home: String, val away: String, val start: Long)

/** Una riga della guida Sky alla Serie C: "LIVORNO-GROSSETO" e i canali Sky dove si vede (es. "Sky Sport Arena", "Sky Sport 251"). */
data class SkyMatch(val teams: String, val channels: List<String>)

/**
 * Le partite non si leggono piu' dai siti nell'app: le legge scripts/aggiorna_eventi.py su GitHub Actions e
 * l'app scarica eventi.json (vedi [LiveFixtures]). Qui restano solo i nomi e la scelta della giornata in corso.
 */
object FixtureParsers {
    val ROME: ZoneId = ZoneId.of("Europe/Rome")

    /** Dopo questo tempo dall'inizio una partita si considera finita. */
    const val FINISH_MS = 3 * 3_600_000L

    private val accentRe = Regex("""\p{M}+""")

    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(accentRe, "")

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
