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

/**
 * Evidenzia con un bordo la card che ha il focus (telecomando / tastiera).
 * Con il tocco non cambia nulla: il bordo compare solo quando il focus arriva dai tasti direzionali.
 */
fun MaterialCardView.enableFocusRing(scale: Float = 1f) {
    val px = (3 * resources.displayMetrics.density).toInt()
    strokeColor = MaterialColors.getColor(this, MR.attr.colorPrimary, Color.WHITE)
    strokeWidth = 0
    onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
        (v as MaterialCardView).strokeWidth = if (focused) px else 0
        if (scale != 1f) {
            val s = if (focused) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
    }
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
