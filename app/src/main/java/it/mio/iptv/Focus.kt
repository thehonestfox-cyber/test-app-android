package it.mio.iptv

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR

private val markedCards = java.util.Collections.newSetFromMap(java.util.WeakHashMap<MaterialCardView, Boolean>())

/** Bordo sottile = card "selezionata" (es. evento in riproduzione); bordo più spesso = card con il focus. */
private fun MaterialCardView.updateStroke() {
    val d = resources.displayMetrics.density
    strokeColor = MaterialColors.getColor(this, MR.attr.colorPrimary, Color.WHITE)
    strokeWidth = when {
        isFocused -> (3 * d).toInt()
        this in markedCards -> (2 * d).toInt()
        else -> 0
    }
}

/** Segna/toglie il bordo sottile di selezione, senza disturbare il bordo del focus. */
fun MaterialCardView.setMarked(on: Boolean) {
    if (on) markedCards.add(this) else markedCards.remove(this)
    updateStroke()
}

/**
 * Evidenzia con un bordo la card che ha il focus (telecomando / tastiera), senza cambiarne le dimensioni.
 * Con il tocco non cambia nulla.
 */
fun MaterialCardView.enableFocusRing() {
    onFocusChangeListener = View.OnFocusChangeListener { v, _ -> (v as MaterialCardView).updateStroke() }
}

/** Anello di focus per pulsanti rotondi o rettangoli (da usare come foreground). */
fun focusRingDrawable(color: Int, widthPx: Int, oval: Boolean, radiusPx: Float = 0f): Drawable =
    StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            shape = if (oval) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setStroke(widthPx, color)
            setColor(Color.TRANSPARENT)
        })
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }
