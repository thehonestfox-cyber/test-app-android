package it.mio.iptv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Sezione della home (es. "Sport"): nome, icona e descrizione facoltativa. Le sue categorie e i suoi canali stanno in [Featured]. */
data class Section(
    val id: String,
    val name: String,
    val icon: String,
    val description: String = "",
    val type: String = CHANNELS
) {
    /** true = sezione di eventi live (carosello di card); false = lista di canali. */
    val isLive: Boolean get() = type == LIVE

    companion object {
        const val CHANNELS = "channels"
        const val LIVE = "live"
    }
}

object Sections {
    /** Id della vecchia sezione "In evidenza", ormai rimossa: serve solo per ripulirne i dati. */
    const val DEFAULT_ID = "featured"
    private const val KEY = "sections"
    private const val LEGACY_KEY = "featured"

    /** Icone tra cui scegliere (chiave salvata -> risorsa). */
    val ICONS: List<Pair<String, Int>> = listOf(
        "star" to R.drawable.ic_star,
        "tv" to R.drawable.ic_tv,
        "movie" to R.drawable.ic_sec_movie,
        "soccer" to R.drawable.ic_sec_soccer,
        "music" to R.drawable.ic_sec_music,
        "news" to R.drawable.ic_sec_news,
        "heart" to R.drawable.ic_sec_heart,
        "fire" to R.drawable.ic_sec_fire,
        "globe" to R.drawable.ic_sec_globe,
        "game" to R.drawable.ic_sec_game,
        "school" to R.drawable.ic_sec_school,
        "home" to R.drawable.ic_sec_home,
        "idea" to R.drawable.ic_sec_idea,
        "rocket" to R.drawable.ic_sec_rocket,
        "library" to R.drawable.ic_sec_library
    )

    fun iconRes(key: String): Int = ICONS.firstOrNull { it.first == key }?.second ?: R.drawable.ic_star

    fun load(p: SharedPreferences): MutableList<Section> {
        val out = mutableListOf<Section>()
        try {
            val a = JSONArray(p.getString(KEY, null) ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Section(
                        o.getString("id"), o.getString("name"),
                        o.optString("icon", "star"), o.optString("description", ""),
                        o.optString("type", Section.CHANNELS)
                    )
                )
            }
        } catch (_: Exception) {
            out.clear()
        }
        // La sezione "In evidenza" non esiste più: la tolgo (una volta sola) insieme ai suoi dati
        val hadLegacy = out.removeAll { it.id == DEFAULT_ID }
        if (hadLegacy || p.contains(LEGACY_KEY)) {
            Featured.delete(p, DEFAULT_ID)
            save(p, out)
        }
        return out
    }

    fun save(p: SharedPreferences, list: List<Section>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("icon", s.icon)
                    .put("description", s.description).put("type", s.type)
            )
        }
        p.edit().putString(KEY, arr.toString()).apply()
    }

    fun newId(): String = System.currentTimeMillis().toString(36)
}
