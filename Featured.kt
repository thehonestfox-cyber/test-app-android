package it.mio.iptv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Categoria "In evidenza" creata dall'admin (es. "Canali TV") con i suoi canali. */
data class FeaturedCategory(val name: String, val channels: List<Channel>)

/** Salva/legge le categorie e i canali in evidenza nelle preferenze dell'app. */
object Featured {
    private const val KEY = "featured"

    fun load(p: SharedPreferences): MutableList<FeaturedCategory> {
        val out = mutableListOf<FeaturedCategory>()
        try {
            val arr = JSONArray(p.getString(KEY, null) ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cat = o.getString("name")
                val chs = o.optJSONArray("channels") ?: JSONArray()
                val list = (0 until chs.length()).map { j ->
                    val c = chs.getJSONObject(j)
                    val lg = c.optJSONArray("logos") ?: JSONArray()
                    Channel(
                        name = c.getString("name"),
                        rawName = c.optString("rawName", c.getString("name")),
                        quality = c.optString("quality", ""),
                        category = cat,
                        url = c.getString("url"),
                        logos = List(lg.length()) { lg.getString(it) }
                    )
                }
                out.add(FeaturedCategory(cat, list))
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun save(p: SharedPreferences, list: List<FeaturedCategory>) {
        val arr = JSONArray()
        list.forEach { cat ->
            arr.put(JSONObject().apply {
                put("name", cat.name)
                put("channels", JSONArray().apply {
                    cat.channels.forEach { ch ->
                        put(JSONObject().apply {
                            put("name", ch.name)
                            put("rawName", ch.rawName)
                            put("quality", ch.quality)
                            put("url", ch.url)
                            put("logos", JSONArray(ch.logos))
                        })
                    }
                })
            })
        }
        p.edit().putString(KEY, arr.toString()).apply()
    }

    /** Legge, modifica e risalva in un colpo solo. */
    fun edit(p: SharedPreferences, block: (MutableList<FeaturedCategory>) -> Unit) {
        val l = load(p)
        block(l)
        save(p, l)
    }
}
