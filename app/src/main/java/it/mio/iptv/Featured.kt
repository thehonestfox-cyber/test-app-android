package it.mio.iptv

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Categoria creata dall'admin (es. "Canali TV") con i suoi canali. */
data class FeaturedCategory(val name: String, val channels: List<Channel>)

/** Salva/legge le categorie e i canali delle sezioni; li esporta/importa come codice di testo. */
object Featured {
    private const val KEY = "featured"
    const val PREFIX = "IPTV1-"

    private fun toJson(list: List<FeaturedCategory>): JSONArray {
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
                            if (ch.time.isNotBlank()) put("time", ch.time)
                        })
                    }
                })
            })
        }
        return arr
    }

    private fun fromJson(arr: JSONArray): MutableList<FeaturedCategory> {
        val out = mutableListOf<FeaturedCategory>()
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
                    logos = List(lg.length()) { lg.getString(it) },
                    time = c.optString("time", "")
                )
            }
            out.add(FeaturedCategory(cat, list))
        }
        return out
    }

    /** Chiave di salvataggio di una sezione (la vecchia "In evidenza" usava quella senza suffisso). */
    private fun key(section: String) = if (section == Sections.DEFAULT_ID) KEY else "${KEY}_$section"

    fun load(p: SharedPreferences, section: String = Sections.DEFAULT_ID): MutableList<FeaturedCategory> = try {
        fromJson(JSONArray(p.getString(key(section), null) ?: "[]"))
    } catch (_: Exception) {
        mutableListOf()
    }

    fun save(p: SharedPreferences, list: List<FeaturedCategory>, section: String = Sections.DEFAULT_ID) {
        p.edit().putString(key(section), toJson(list).toString()).apply()
    }

    /** Legge, modifica e risalva in un colpo solo. */
    fun edit(
        p: SharedPreferences,
        section: String = Sections.DEFAULT_ID,
        block: (MutableList<FeaturedCategory>) -> Unit
    ) {
        val l = load(p, section)
        block(l)
        save(p, l, section)
    }

    /** Cancella tutte le categorie e i canali di una sezione. */
    fun delete(p: SharedPreferences, section: String) {
        p.edit().remove(key(section)).apply()
    }

    /** Trasforma categorie e canali in un codice di testo (JSON compresso + Base64). */
    fun exportCode(list: List<FeaturedCategory>): String {
        val raw = toJson(list).toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        return PREFIX + Base64.encodeToString(
            bos.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /** Legge un codice; null se non è valido. */
    fun importCode(code: String): List<FeaturedCategory>? = try {
        val t = code.filter { !it.isWhitespace() }
        if (!t.startsWith(PREFIX)) null
        else {
            val bytes = Base64.decode(t.removePrefix(PREFIX), Base64.URL_SAFE)
            val text = GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
            fromJson(JSONArray(text)).filter { it.name.isNotBlank() }
        }
    } catch (_: Exception) {
        null
    }

    /** Aggiunge le categorie importate: se il nome esiste già unisce i canali (senza doppioni). */
    fun merge(into: MutableList<FeaturedCategory>, add: List<FeaturedCategory>) {
        for (c in add) {
            val k = into.indexOfFirst { it.name.equals(c.name, ignoreCase = true) }
            if (k < 0) into.add(c)
            else {
                val have = into[k].channels.map { it.url }.toSet()
                into[k] = into[k].copy(channels = into[k].channels + c.channels.filter { it.url !in have })
            }
        }
    }
}
