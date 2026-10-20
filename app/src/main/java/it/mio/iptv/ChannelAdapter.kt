package it.mio.iptv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java.util.concurrent.ConcurrentHashMap
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/** Sfondo chiaro dietro ai loghi, così si vedono anche col tema scuro. */
fun logoBackground(ctx: Context, radiusDp: Int) = GradientDrawable().apply {
    cornerRadius = radiusDp * ctx.resources.displayMetrics.density
    setColor(0xFFF1F1F1.toInt())
}

private val skyRe = Regex("""\bSKY\b""", RegexOption.IGNORE_CASE)
private const val LIST_LOGOS = "https://raw.githubusercontent.com/tv-logo/tv-logos/"

/** Loghi da provare: per i canali Sky solo quelli della lista (assets/logos_it.txt), mai da altri siti. */
private fun logoCandidates(ch: Channel): List<String> =
    if (ch.category == "SKY" || skyRe.containsMatchIn(ch.name)) ch.logos.filter { it.startsWith(LIST_LOGOS) }
    else ch.logos

private val logoGood = ConcurrentHashMap<String, String>()   // canale -> logo che si e' caricato
private val logoBad = ConcurrentHashMap<String, Long>()       // logo -> quando e' fallito
private const val BAD_MS = 10 * 60_000L

/**
 * Carica il logo del canale provando gli URL uno dopo l'altro; se nessuno va, mostra l'icona TV.
 * Ricorda il logo che ha funzionato (si riparte da quello) e salta per 10 minuti quelli falliti,
 * cosi' non si rifanno richieste inutili a ogni riga o card che si ricostruisce.
 */
fun loadChannelLogo(iv: ImageView, ch: Channel) {
    iv.tag = ch.url
    val good = logoGood[ch.url]
    val now = System.currentTimeMillis()
    val list = if (good != null) listOf(good)
    else logoCandidates(ch).filter { (logoBad[it] ?: 0L) + BAD_MS < now }
    showLogo(iv, ch, list, 0)
}

private fun showLogo(iv: ImageView, ch: Channel, list: List<String>, idx: Int) {
    if (idx >= list.size) {
        iv.dispose()
        iv.imageTintList = ColorStateList.valueOf(0xFF777777.toInt())
        iv.setImageResource(R.drawable.ic_tv)
        return
    }
    iv.imageTintList = null
    val url = list[idx]
    iv.load(url) {
        crossfade(false)
        listener(
            onSuccess = { _, _ -> logoGood[ch.url] = url },
            onError = { _, _ ->
                logoBad[url] = System.currentTimeMillis()
                if (logoGood[ch.url] == url) logoGood.remove(ch.url)
                if (iv.tag == ch.url) showLogo(iv, ch, list, idx + 1)
            }
        )
    }
}

/** Lista con intestazioni di categoria e righe di canali (logo + nome). */
class ChannelAdapter(
    private val ctx: Context,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val title: String, val count: Int) : Row()
        data class Item(val ch: Channel) : Row()
    }

    private class HeaderHolder(val tv: TextView) : RecyclerView.ViewHolder(tv)
    private class ItemHolder(
        val card: MaterialCardView, val logo: ImageView,
        val name: TextView, val sub: TextView
    ) : RecyclerView.ViewHolder(card)

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private var rows: List<Row> = emptyList()
    private var selectedUrl: String? = null

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun col(attr: Int) = MaterialColors.getColor(ctx, attr, Color.MAGENTA)

    fun submit(r: List<Row>) {
        rows = r
        notifyDataSetChanged()
    }

    // Aggiorna solo le righe che cambiano (così il focus del telecomando non salta in cima alla lista
    // e non si ricaricano tutti i loghi mentre parte il video)
    fun select(url: String?) {
        val old = selectedUrl
        if (old == url) return
        selectedUrl = url
        rows.forEachIndexed { i, r ->
            if (r is Row.Item && (r.ch.url == old || r.ch.url == url)) notifyItemChanged(i)
        }
    }

    fun positionOf(url: String): Int = rows.indexOfFirst { it is Row.Item && it.ch.url == url }

    // Se sopra la riga con il focus c'è un'intestazione tagliata o fuori vista, la lista scorre fino a mostrarla
    private fun revealHeaderAbove(v: View) {
        val rv = v.parent as? RecyclerView ?: return
        val pos = rv.getChildAdapterPosition(v)
        if (pos <= 0 || rows.getOrNull(pos - 1) !is Row.Header) return
        rv.post {
            val lm = rv.layoutManager as? LinearLayoutManager ?: return@post
            val header = lm.findViewByPosition(pos - 1)
            if (header == null || header.top < 0) lm.scrollToPositionWithOffset(pos - 1, 0)
        }
    }

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == 0) {
            val t = TextView(ctx).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(col(MR.attr.colorPrimary))
                setPadding(dp(4), dp(16), dp(4), dp(4))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            return HeaderHolder(t)
        }
        val logo = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = logoBackground(ctx, 8)
        }
        val name = TextView(ctx).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(col(MR.attr.colorOnSurface))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = TextView(ctx).apply {
            textSize = 13f
            setTextColor(col(MR.attr.colorOnSurfaceVariant))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(sub)
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(logo, LinearLayout.LayoutParams(dp(64), dp(44)).apply { rightMargin = dp(14) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        val card = MaterialCardView(ctx).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            enableFocusRing()
            addView(row)
            layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        // Le intestazioni di categoria non prendono il focus: salendo col telecomando la lista non le
        // scopriva più e la prima categoria restava nascosta sopra. Qui la si riporta in vista.
        val ring = card.onFocusChangeListener
        card.onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            ring?.onFocusChange(v, has)
            if (has) revealHeaderAbove(v)
        }
        return ItemHolder(card, logo, name, sub)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val r = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).tv.text = "${r.title} · ${r.count}"
            is Row.Item -> {
                val h = holder as ItemHolder
                val ch = r.ch
                h.name.text = ch.name
                h.sub.text = ch.quality.ifEmpty { "Diretta" }
                h.card.setCardBackgroundColor(
                    if (ch.url == selectedUrl) col(MR.attr.colorPrimaryContainer)
                    else col(MR.attr.colorSurfaceVariant)
                )
                h.card.setOnClickListener { onClick(ch) }
                loadChannelLogo(h.logo, ch)
            }
        }
    }
}
