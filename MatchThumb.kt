package it.mio.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Thumb della partita ricreata dagli stemmi: sfondo diviso in due con i colori sociali delle squadre (vedi [TeamColors]),
 * stemma di casa a sinistra, logo del campionato al centro, stemma ospite a destra.
 */
object MatchThumb {
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val BASE_L = 0xFF3A3F4B.toInt()
    private const val BASE_R = 0xFF272B34.toInt()

    fun build(ctx: Context, home: String, away: String, league: String, eventName: String): View {
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

        // sfondo coi colori sociali delle due squadre (non estratti dallo stemma); squadra sconosciuta = grigio
        val teams = SportsDb.teams(eventName)
        val nameL = teams?.first ?: ""
        val nameR = teams?.second ?: ""
        var colL = TeamColors.background(nameL)
        var colR = TeamColors.background(nameR)
        if (colL != null && colL == colR) colR = TeamColors.alternative(nameR)   // stesso colore: l'altro colore sociale
        colL?.let { bgL.setBackgroundColor(shade(it, 0.95f)) }
        colR?.let { bgR.setBackgroundColor(shade(it, 0.78f)) }
        return root
    }

    private fun shade(c: Int, f: Float): Int =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())
}
