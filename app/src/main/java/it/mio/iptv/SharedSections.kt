package it.mio.iptv

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sezioni condivise a tutti: stanno nel file sezioni.json del repo GitHub (come eventi.json).
 * - Chi ha il token GitHub (l'admin) le pubblica con il pulsante di condivisione.
 * - Tutte le installazioni le scaricano da sole e le tengono allineate.
 */
object SharedSections {
    private const val REPO = "thehonestfox-cyber/test-app-android"
    private const val BRANCH = "main"
    private const val FILE = "sezioni.json"
    private const val RAW = "https://raw.githubusercontent.com/$REPO/$BRANCH/$FILE"
    private const val API = "https://api.github.com/repos/$REPO/contents/$FILE"
    private const val SYNC_EVERY_MS = 3 * 60_000L
    private const val K_TOKEN = "gh_token"
    private const val K_OWNED = "shared_owned"     // sezioni pubblicate da questo dispositivo
    private const val K_COPIES = "shared_copies"   // sezioni ricevute dagli altri

    class Result(val ok: Boolean, val message: String = "")
    private class Remote(val section: Section, val cats: List<FeaturedCategory>)
    private class Resp(val code: Int, val body: String)

    @Volatile private var lastSync = 0L

    // ---------- token e stato ----------

    fun token(p: SharedPreferences): String = (p.getString(K_TOKEN, "") ?: "").trim()
    fun setToken(p: SharedPreferences, t: String) = p.edit().putString(K_TOKEN, t.trim()).apply()

    private fun ids(p: SharedPreferences, key: String): MutableSet<String> =
        HashSet(p.getStringSet(key, emptySet()) ?: emptySet())

    private fun putIds(p: SharedPreferences, key: String, set: Set<String>) =
        p.edit().putStringSet(key, set).apply()

    fun isOwned(p: SharedPreferences, id: String) = id in ids(p, K_OWNED)
    fun isShared(p: SharedPreferences, id: String) = isOwned(p, id) || id in ids(p, K_COPIES)

    // ---------- rete ----------

    private fun call(
        method: String, url: String, token: String? = null, body: String? = null,
        accept: String = "application/vnd.github+json"
    ): Resp? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "PEZ8")
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        Resp(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
    } catch (_: Exception) {
        null
    }

    // ---------- formato del file ----------

    private fun sectionJson(s: Section, cats: List<FeaturedCategory>) = JSONObject()
        .put("id", s.id).put("name", s.name).put("icon", s.icon)
        .put("description", s.description).put("type", s.type)
        .put("cats", Featured.toJson(cats))

    private fun parse(text: String): List<Remote>? = try {
        val arr = JSONObject(text).optJSONArray("sections") ?: JSONArray()
        val out = ArrayList<Remote>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val name = o.getString("name")
            if (id.isBlank() || name.isBlank()) continue
            val sec = Section(
                id, name, o.optString("icon", "star"), o.optString("description", ""),
                o.optString("type", Section.CHANNELS)
            )
            out.add(Remote(sec, Featured.fromJson(o.optJSONArray("cats") ?: JSONArray())))
        }
        out
    } catch (_: Exception) {
        null
    }

    // ---------- ricezione (tutte le installazioni) ----------

    /** Controlla le sezioni condivise al massimo ogni 3 minuti; onChanged parte solo se qualcosa è cambiato. */
    fun syncIfDue(p: SharedPreferences, onChanged: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastSync < SYNC_EVERY_MS) return
        lastSync = now
        Thread { try { if (sync(p)) onChanged() } catch (_: Throwable) {} }.start()
    }

    private fun fetchRemote(): List<Remote>? {
        val r = call("GET", "$RAW?t=${System.currentTimeMillis() / 60_000L}", accept = "*/*") ?: return null
        // 404 = file non ancora creato (o repo non leggibile): non tocco niente
        if (r.code !in 200..299) return null
        return parse(r.body)
    }

    private fun sync(p: SharedPreferences): Boolean {
        val remote = fetchRemote() ?: return false      // errore di rete: resta tutto com'è
        val owned = ids(p, K_OWNED)
        val copies = ids(p, K_COPIES)
        val local = Sections.load(p)
        val remoteIds = remote.map { it.section.id }.toSet()
        var changed = false

        // sezioni tolte dalla condivisione: sparisce anche la copia ricevuta
        for (id in copies.toList()) {
            if (id !in remoteIds && id !in owned) {
                local.removeAll { it.id == id }
                Featured.delete(p, id)
                copies.remove(id)
                changed = true
            }
        }
        // sezioni nuove o aggiornate (quelle pubblicate da questo dispositivo non si toccano)
        for (r in remote) {
            val id = r.section.id
            if (id in owned) continue
            val idx = local.indexOfFirst { it.id == id }
            if (idx < 0) {
                local.add(r.section)
                changed = true
            } else if (local[idx] != r.section) {
                local[idx] = r.section
                changed = true
            }
            val old = Featured.toJson(Featured.load(p, id)).toString()
            if (old != Featured.toJson(r.cats).toString()) {
                Featured.save(p, r.cats, id)
                changed = true
            }
            copies.add(id)
        }
        if (changed) Sections.save(p, local)
        putIds(p, K_COPIES, copies)
        return changed
    }

    // ---------- pubblicazione (solo chi ha il token) ----------

    /** Pubblica o aggiorna la sezione per tutti. Da chiamare fuori dal thread principale. */
    fun publish(p: SharedPreferences, sec: Section, cats: List<FeaturedCategory>): Result {
        val r = update(p, "Condividi sezione ${sec.name}") { list ->
            val o = sectionJson(sec, cats)
            val i = list.indexOfFirst { it.optString("id") == sec.id }
            if (i >= 0) list[i] = o else list.add(o)
        }
        if (r.ok) {
            putIds(p, K_OWNED, ids(p, K_OWNED).apply { add(sec.id) })
            putIds(p, K_COPIES, ids(p, K_COPIES).apply { remove(sec.id) })
        }
        return r
    }

    /** Toglie la sezione dalla condivisione: sparisce dalle altre installazioni. */
    fun unpublish(p: SharedPreferences, id: String, name: String): Result {
        val r = update(p, "Smetti di condividere $name") { list ->
            list.removeAll { it.optString("id") == id }
        }
        if (r.ok) putIds(p, K_OWNED, ids(p, K_OWNED).apply { remove(id) })
        return r
    }

    /** Legge sezioni.json dal repo, applica la modifica e lo riscrive (con una ripetizione se c'è un conflitto). */
    private fun update(p: SharedPreferences, message: String, mutate: (MutableList<JSONObject>) -> Unit): Result {
        val tk = token(p)
        if (tk.isEmpty()) return Result(false, "Manca il token GitHub")
        try {
            for (attempt in 0..1) {
                val get = call("GET", "$API?ref=$BRANCH", tk) ?: return Result(false, "Nessuna connessione")
                var sha: String? = null
                val list = ArrayList<JSONObject>()
                when {
                    get.code == 404 -> {}   // il file non esiste ancora: lo creo
                    get.code == 401 -> return Result(false, "Token non valido o scaduto")
                    get.code in 200..299 -> {
                        val j = JSONObject(get.body)
                        sha = j.optString("sha")
                        val b64 = j.optString("content").replace("\n", "")
                        val text = if (b64.isNotEmpty()) String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                        else (call("GET", "$API?ref=$BRANCH", tk, accept = "application/vnd.github.raw+json")?.body ?: "")
                        if (text.isNotBlank()) {
                            val arr = JSONObject(text).optJSONArray("sections") ?: JSONArray()
                            for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                        }
                    }
                    else -> return Result(false, "GitHub ha risposto ${get.code}")
                }
                mutate(list)
                val out = JSONObject().put("v", 1).put("sections", JSONArray(list))
                val body = JSONObject().put("message", message).put("branch", BRANCH)
                    .put("content", Base64.encodeToString(out.toString(1).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                if (!sha.isNullOrEmpty()) body.put("sha", sha)
                val put = call("PUT", API, tk, body.toString()) ?: return Result(false, "Nessuna connessione")
                when (put.code) {
                    200, 201 -> return Result(true)
                    401 -> return Result(false, "Token non valido o scaduto")
                    403, 404 -> return Result(false, "Il token non può scrivere nel repo (serve Contents: Read and write)")
                    409, 422 -> if (attempt == 0) continue
                    else -> return Result(false, "GitHub ha risposto ${put.code}")
                }
            }
            return Result(false, "Il file è stato modificato nel frattempo: riprova")
        } catch (e: Exception) {
            return Result(false, "Errore: ${e.message}")
        }
    }
}
