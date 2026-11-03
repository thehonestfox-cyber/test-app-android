package it.mio.iptv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max

private fun isNight(c: Context) =
    (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private fun mediumFont(): Typeface =
    if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false)
    else Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

/**
 * Base dei pulsanti con l'animazione alla pressione (la stessa quando si tocca e quando si entra):
 * il pulsante si rimpicciolisce, un velo chiaro lo attraversa da sinistra a destra partendo dal punto
 * toccato, poi torna com'era, senza rimbalzo. Se si entra da qualche parte, il passaggio alla schermata
 * successiva parte a metà dell'animazione.
 *
 * [radii]: raggi degli angoli in px (alto-sx, alto-dx, basso-dx, basso-sx).
 */
open class PressTile(
    context: Context,
    tileColor: Int,
    radii: FloatArray,
    veilColor: Int = Color.WHITE,
    private val veilStrength: Float = 0.5f,
    private val onTap: () -> Unit
) : FrameLayout(context) {

    protected val d = resources.displayMetrics.density

    private val corners = floatArrayOf(
        radii[0], radii[0], radii[1], radii[1], radii[2], radii[2], radii[3], radii[3]
    )
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tileColor }
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = veilColor }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8 * d          // metà finisce fuori dal clip: 4dp visibili
        color = focusColor(context)
    }
    private val rect = RectF()
    private val shape = Path()

    // stato dell'animazione
    private var press = 0f            // 0 = a riposo, 1 = premuto (rimpicciolito)
    private var veilProgress = 0f     // quanto il velo ha attraversato il pulsante
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
    }

    // ---------- Disegno ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        // il perno resta fisso al centro: non va riassegnato a ogni frame dell'animazione
        pivotX = w / 2f
        pivotY = h / 2f
        shape.reset()
        shape.addRoundRect(rect, corners, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(shape, bgPaint)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // velo chiaro: un cerchio che si allarga dal punto toccato, tagliato sul bordo arrotondato
        if (veilAlpha > 0f && veilProgress > 0f) {
            val save = canvas.save()
            canvas.clipPath(shape)
            val far = max(
                max(hypot(touchX, touchY), hypot(w - touchX, touchY)),
                max(hypot(touchX, h - touchY), hypot(w - touchX, h - touchY))
            )
            veilPaint.alpha = (255 * veilStrength * veilAlpha).toInt()
            canvas.drawCircle(touchX, touchY, far * veilProgress, veilPaint)
            canvas.restoreToCount(save)
        }
        // bordo giallo del focus (telecomando / tastiera)
        if (isFocused) {
            val save = canvas.save()
            canvas.clipPath(shape)
            canvas.drawPath(shape, focusPaint)
            canvas.restoreToCount(save)
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
        setLayerType(LAYER_TYPE_NONE, null)
        super.onDetachedFromWindow()
    }

    // ---------- Animazione ----------

    // Durante l'animazione il pulsante diventa un livello GPU (si ridimensiona senza ridisegnare i figli);
    // a riposo il livello viene tolto, così non occupa memoria grafica per ogni pulsante della lista.
    private fun layerOn() {
        if (layerType != LAYER_TYPE_HARDWARE) setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun layerOffIfIdle() {
        if (press == 0f && veilAlpha == 0f &&
            pressAnim?.isRunning != true && veilAnim?.isRunning != true && fadeAnim?.isRunning != true
        ) setLayerType(LAYER_TYPE_NONE, null)
    }

    private val endListener = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            post { layerOffIfIdle() }   // dopo la fine vera dell'animazione
        }
    }

    private fun applyPress() {
        // si stringe di più in altezza che in larghezza, come nel video
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
            addListener(endListener)
            start()
        }
    }

    private fun pressIn(x: Float, y: Float) {
        layerOn()
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
            addListener(endListener)
            start()
        }
        animatePress(1f, 110, 0)
    }

    private fun release() {
        val held = System.currentTimeMillis() - downAt
        // resta un attimo premuto e il velo finisce la corsa, poi torna a riposo senza rimbalzo
        animatePress(0f, 260, max(0L, 110 - held))
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(veilAlpha, 0f).apply {
            duration = 240
            startDelay = max(0L, 260 - held)
            addUpdateListener { veilAlpha = it.animatedValue as Float; invalidate() }
            addListener(endListener)
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

private fun uniform(c: Context, dp: Int): FloatArray {
    val v = dp * c.resources.displayMetrics.density
    return floatArrayOf(v, v, v, v)
}

/**
 * Rettangolo colorato della home (cerchio nero con icona, titolo, freccia), uno per riga a tutta larghezza.
 * M3: angoli "extra large" 28dp per il rettangolo, "large" 16dp per il quadrato dell'icona.
 */
class HomeTile(
    context: Context,
    iconRes: Int,
    title: String,
    sub: String,
    tileColor: Int,
    onTap: () -> Unit
) : PressTile(context, tileColor, uniform(context, 28), onTap = onTap) {

    init {
        contentDescription = title
        minimumHeight = (80 * d).toInt()

        // tema chiaro: quadrato icona bianco con icona nera; tema scuro: quadrato nero con icona grigio chiaro
        val night = isNight(context)
        val boxColor = if (night) Color.BLACK else Color.WHITE
        val iconColor = if (night) Color.rgb(190, 190, 190) else Color.BLACK
        val chevronColor = if (night) Color.rgb(190, 190, 190) else Color.WHITE

        val box = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * d
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
                typeface = mediumFont()
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
            addView(box, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply {
                rightMargin = (12 * d).toInt()
            })
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }
}

/**
 * Pulsante del menu (icona + nome), a gruppi uno sotto l'altro: gli angoli esterni del gruppo sono
 * grandi (20dp), quelli tra un pulsante e l'altro piccoli (4dp), come nel video.
 * I colori arrivano da fuori, così il menu segue il tema chiaro/scuro.
 */
class MenuButton(
    context: Context,
    iconRes: Int,
    label: String,
    fillColor: Int,
    textColor: Int,
    first: Boolean,
    last: Boolean,
    onTap: () -> Unit
) : PressTile(
    context, fillColor,
    context.resources.displayMetrics.density.let { dd ->
        val big = 20 * dd
        val small = 4 * dd
        val top = if (first) big else small
        val bottom = if (last) big else small
        floatArrayOf(top, top, bottom, bottom)
    },
    // velo bianco sui pulsanti scuri, velo scuro e leggero su quelli chiari
    veilColor = if (isNight(context)) Color.WHITE else Color.BLACK,
    veilStrength = if (isNight(context)) 0.14f else 0.10f,
    onTap = onTap
) {
    init {
        contentDescription = label
        minimumHeight = (60 * d).toInt()
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(textColor)
            }, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()).apply {
                rightMargin = (16 * d).toInt()
            })
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(textColor)
                typeface = mediumFont()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }
}
