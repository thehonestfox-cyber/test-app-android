package it.mio.iptv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

/** Sfondo chiaro dietro ai loghi, così si vedono anche col tema scuro. */
fun logoBackground(ctx: Context, radiusDp: Int) = GradientDrawable().apply {
    cornerRadius = radiusDp * ctx.resources.displayMetrics.density
    setColor(0xFFF1F1F1.toInt())
}

/** Carica il logo del canale provando gli URL uno dopo l'altro; se nessuno va, mostra l'icona TV. */
fun loadChannelLogo(iv: ImageView, ch: Channel) {
    iv.tag = ch.url
    showLogo(iv, ch, 0)
}

private fun showLogo(iv: ImageView, ch: Channel, idx: Int) {
    if (idx >= ch.logos.size) {
        iv.dispose()
        iv.imageTintList = ColorStateList.valueOf(0xFF777777.toInt())
        iv.setImageResource(R.drawable.ic_tv)
        return
    }
    iv.imageTintList = null
    iv.load(ch.logos[idx]) {
        crossfade(true)
        listener(onError = { _, _ ->
            if (iv.tag == ch.url) showLogo(iv, ch, idx + 1)
        })
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

    fun select(url: String?) {
        selectedUrl = url
        notifyDataSetChanged()
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
