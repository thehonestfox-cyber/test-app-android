package it.mio.iptv

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

/**
 * Rettangolo colorato della home (stile "Browse all": cerchio nero con icona, titolo, freccia),
 * uno per riga a tutta larghezza.
 *
 * Animazione alla pressione (la stessa quando si tocca e quando si entra nella sezione):
 * il rettangolo si rimpicciolisce, un velo chiaro lo attraversa da sinistra a destra partendo dal punto
 * toccato, poi tutto torna com'era con un piccolo rimbalzo. Se si entra nella sezione, il passaggio alla
 * schermata successiva parte a metà dell'animazione.
 */
class HomeTile(
    context: Context,
    iconRes: Int,
    title: String,
    sub: String,
    private val tileColor: Int,
    private val onTap: () -> Unit
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    // M3: angoli "extra large" 28dp per il rettangolo, "large" 16dp per il quadrato dell'icona
    private val corner = 28 * d
    private val iconCorner = 16 * d

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileColor }
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * d
        color = focusColor(context)
    }
    private val rect = RectF()
    private val clip = Path()

    // stato dell'animazione
    private var press = 0f            // 0 = a riposo, 1 = premuto (rimpicciolito)
    private var veilProgress = 0f     // quanto il velo ha attraversato il rettangolo
    private var veilAlpha = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var downAt = 0L
    private var cancelled = false
    private var clicking = false
    private var pressAnim: ValueAnimator? = null
    private var veilAnim: ValueAnimator? = null
    private var fadeAnim: ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        contentDescription = title
        minimumHeight = (80 * d).toInt()

        // tema chiaro: quadrato icona bianco con icona nera; tema scuro: quadrato nero con icona grigio chiaro
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val boxColor = if (night) Color.BLACK else Color.WHITE
        val iconColor = if (night) Color.rgb(190, 190, 190) else Color.BLACK
        val chevronColor = if (night) Color.rgb(190, 190, 190) else Color.WHITE

        val medium: Typeface =
            if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false)
            else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        // cerchio nero con l'icona
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = iconCorner
                setColor(boxColor)
            }
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(iconColor)
            }, LayoutParams((24 * d).toInt(), (24 * d).toInt(), Gravity.CENTER))
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = medium
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (sub.isNotBlank()) addView(TextView(context).apply {
                text = sub
                textSize = 13f
                setTextColor(Color.argb(220, 255, 255, 255))
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        // la freccia è quella "indietro" ruotata
        val chevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_left)
            rotation = 180f
            imageTintList = ColorStateList.valueOf(chevronColor)
        }

        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
            addView(circle, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply {
                rightMargin = (12 * d).toInt()
            })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ---------- Disegno ----------

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, bgPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        // velo chiaro: un cerchio che si allarga dal punto toccato, tagliato sul bordo arrotondato
        if (veilAlpha > 0f && veilProgress > 0f) {
            val save = canvas.save()
            clip.reset()
            clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
            canvas.clipPath(clip)
            val far = max(hypot(touchX, touchY), hypot(width - touchX, touchY))
                .coerceAtLeast(hypot(touchX, height - touchY))
                .coerceAtLeast(hypot(width - touchX, height - touchY))
            veilPaint.alpha = (255 * 0.5f * veilAlpha).toInt()
            canvas.drawCircle(touchX, touchY, far * veilProgress, veilPaint)
            canvas.restoreToCount(save)
        }
        // bordo giallo del focus (telecomando / tastiera)
        if (isFocused) {
            val inset = focusPaint.strokeWidth / 2
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, corner - inset, corner - inset, focusPaint)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        pressAnim?.cancel(); veilAnim?.cancel(); fadeAnim?.cancel()
        press = 0f; veilAlpha = 0f; veilProgress = 0f
        applyPress()
        super.onDetachedFromWindow()
    }

    // ---------- Animazione ----------

    private fun applyPress() {
        // si stringe di più in altezza che in larghezza, come nel video
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = 1f - 0.08f * press
        scaleY = 1f - 0.12f * press
    }

    private fun animatePress(to: Float, duration: Long, delay: Long) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(press, to).apply {
            this.duration = duration
            startDelay = delay
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { press = it.animatedValue as Float; applyPress() }
            start()
        }
    }

    private fun pressIn(x: Float, y: Float) {
        cancelled = false
        downAt = System.currentTimeMillis()
        touchX = x.coerceIn(0f, width.toFloat())
        touchY = y.coerceIn(0f, height.toFloat())
        fadeAnim?.cancel()
        veilAnim?.cancel()
        veilAlpha = 1f
        veilProgress = 0f
        veilAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { veilProgress = it.animatedValue as Float; invalidate() }
            start()
        }
        animatePress(1f, 110, 0)
    }

    private fun release() {
        val held = System.currentTimeMillis() - downAt
        // il rettangolo resta un attimo premuto e il velo finisce la corsa, poi torna a riposo senza rimbalzo
        animatePress(0f, 260, max(0L, 110 - held))
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(veilAlpha, 0f).apply {
            duration = 240
            startDelay = max(0L, 260 - held)
            addUpdateListener { veilAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    // ---------- Input ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressIn(e.x, e.y)
            MotionEvent.ACTION_MOVE -> {
                val slop = 24 * d
                val outside = e.x < -slop || e.y < -slop || e.x > width + slop || e.y > height + slop
                if (outside && !cancelled) {
                    cancelled = true
                    release()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!cancelled) performClick()
            }
            MotionEvent.ACTION_CANCEL -> if (!cancelled) {
                cancelled = true
                release()
            }
        }
        return true
    }

    /** Tocco, tasto OK del telecomando o accessibilità: stessa animazione, poi si entra. */
    override fun performClick(): Boolean {
        if (clicking) return true
        clicking = true
        // da tastiera/telecomando non c'è stato un "premuto": parte da sinistra, come nel video
        if (press == 0f && veilAlpha == 0f) pressIn(0f, height / 2f)
        release()
        playSoundEffect(SoundEffectConstants.CLICK)
        postDelayed({
            clicking = false
            onTap()
        }, 280)
        return true
    }
}
