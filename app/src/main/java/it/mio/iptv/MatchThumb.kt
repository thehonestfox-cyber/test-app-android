package it.mio.iptv

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import coil.imageLoader
import coil.request.ImageRequest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Thumb della partita ricreata dagli stemmi: sfondo diviso in due con i colori delle squadre,
 * stemma di casa a sinistra, logo del campionato al centro, stemma ospite a destra.
 */
object MatchThumb {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val BASE_L = 0xFF3A3F4B.toInt()
    private const val BASE_R = 0xFF272B34.toInt()

    fun build(ctx: Context, home: String, away: String, league: String): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = FrameLayout(ctx)
        root.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(BASE_L)
        }
        root.clipToOutline = true      // arrotonda anche i due colori dello sfondo

        // sfondo: due metà
        val bgL = View(ctx).apply { setBackgroundColor(BASE_L) }
        val bgR = View(ctx).apply { setBackgroundColor(BASE_R) }
        val bg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bgL, LinearLayout.LayoutParams(0, MATCH, 1f))
            addView(bgR, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))

        // stemmi
        fun badge(url: String): ImageView = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            if (url.isNotEmpty()) SportsDb.loadBadge(this, url)
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(badge(home), LinearLayout.LayoutParams(0, MATCH, 1f))
            if (league.isNotEmpty()) {
                val box = FrameLayout(ctx).apply {
                    background = GradientDrawable().apply {
                        cornerRadius = dp(10).toFloat()
                        setColor(0xF2FFFFFF.toInt())
                    }
                    val lg = ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(dp(6), dp(6), dp(6), dp(6))
                    }
                    SportsDb.loadBadge(lg, league)
                    addView(lg, FrameLayout.LayoutParams(MATCH, MATCH))
                }
                addView(box, LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            addView(badge(away), LinearLayout.LayoutParams(0, MATCH, 1f))
        }
        root.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))

        // colori di sfondo presi dagli stemmi (appena sono scaricati)
        dominantColor(ctx, home) { c -> bgL.setBackgroundColor(shade(c, 0.95f)) }
        dominantColor(ctx, away) { c -> bgR.setBackgroundColor(shade(c, 0.78f)) }
        return root
    }

    private fun shade(c: Int, f: Float): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    // Colore dominante di ogni stemma: calcolato una volta sola, in un thread a parte, e ricordato
    // (anche dopo la chiusura dell'app) cosi' lo sfondo e' subito del colore giusto, senza lampi grigi.
    private const val NONE = Int.MIN_VALUE            // stemma senza un colore utile
    private val colors = ConcurrentHashMap<String, Int>()
    private val waiting = HashMap<String, MutableList<(Int) -> Unit>>()   // solo thread principale
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var store: SharedPreferences? = null

    private fun store(ctx: Context): SharedPreferences =
        store ?: ctx.applicationContext.getSharedPreferences("badge_colors", Context.MODE_PRIVATE).also { store = it }

    /** Calcola in anticipo il colore di uno stemma (da chiamare sul thread principale). */
    fun preColor(ctx: Context, url: String) = dominantColor(ctx, url) { }

    private fun dominantColor(ctx: Context, url: String, cb: (Int) -> Unit) {
        if (url.isEmpty()) return
        val st = store(ctx)
        val known = colors[url] ?: if (st.contains(url)) st.getInt(url, NONE).also { colors[url] = it } else null
        if (known != null) {
            if (known != NONE) cb(known)
            return
        }
        val list = waiting[url]
        if (list != null) {      // stesso stemma gia' in elaborazione: aspetta lo stesso risultato
            list.add(cb)
            return
        }
        waiting[url] = mutableListOf(cb)
        val req = ImageRequest.Builder(ctx)
            .data(SportsDb.badgeUrl(url))
            .allowHardware(false)          // serve la bitmap leggibile pixel per pixel
            .size(48)
            .target(onError = { waiting.remove(url) }, onSuccess = { d ->
                val bm = (d as? BitmapDrawable)?.bitmap
                if (bm == null) {
                    waiting.remove(url)
                } else {
                    worker.execute {
                        val c = pickColor(bm)
                        colors[url] = c ?: NONE
                        st.edit().putInt(url, c ?: NONE).apply()
                        main.post { waiting.remove(url)?.forEach { w -> if (c != null) w(c) } }
                    }
                }
            })
            .build()
        ctx.imageLoader.enqueue(req)
    }

    // Colore piu' frequente tra i pixel colorati (ignora trasparenti, bianchi e quasi neri)
    private fun pickColor(bm: Bitmap): Int? {
        val w = bm.width
        val h = bm.height
        val counts = HashMap<Int, Int>()
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val p = bm.getPixel(x, y)
            if (Color.alpha(p) < 200) continue
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            if (r > 225 && g > 225 && b > 225) continue
            if (r < 35 && g < 35 && b < 35) continue
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.maxByOrNull { it.value }?.key ?: return null
        return Color.rgb(((best shr 8) and 15) * 17, ((best shr 4) and 15) * 17, (best and 15) * 17)
    }
}
