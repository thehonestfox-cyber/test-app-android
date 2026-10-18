package it.mio.iptv

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator

/**
 * Stato del multi-schermo (BETA) per le sezioni "eventi live": quanti player (1-4),
 * quale evento c'è in ciascuno e di quale player si sente l'audio.
 * Lo usano sia la lista degli eventi (per assegnare gli eventi ai player) sia la schermata dei player.
 */
class MultiState {
    companion object {
        const val MAX = 4
    }

    var count = 1
        private set
    val slots = arrayOfNulls<Channel>(MAX)
    var audio = 0          // player di cui si sente l'audio
    var target = 0         // player che riceverà il prossimo evento scelto dalla lista

    fun changeCount(n: Int) {
        count = n.coerceIn(1, MAX)
        if (target >= count) target = 0
        fixAudio()
    }

    /** L'audio deve stare su un player visibile e non vuoto (se ce n'è uno). */
    fun fixAudio() {
        if (audio !in 0 until count || slots[audio] == null) {
            audio = (0 until count).firstOrNull { slots[it] != null } ?: 0
        }
    }

    /** Stesso evento = stesso URL oppure stesso nome (es. la stessa partita presente due volte nella lista). */
    fun same(a: Channel, b: Channel) =
        a.url == b.url || a.name.trim().equals(b.name.trim(), ignoreCase = true)

    /** True se l'evento è già in riproduzione in un player visibile diverso da `except`. */
    fun isPlaying(ch: Channel, except: Int = -1): Boolean =
        (0 until count).any { it != except && slots[it]?.let { s -> same(s, ch) } == true }

    /** Mette l'evento nel player evidenziato e sposta l'evidenziazione sul prossimo vuoto. */
    fun assign(ch: Channel): Int {
        // un evento già in riproduzione non si mette due volte: si porta solo l'audio su quel player
        val dup = (0 until count).firstOrNull { slots[it]?.let { s -> same(s, ch) } == true }
        if (dup != null) {
            audio = dup
            return dup
        }
        val i = target.coerceIn(0, count - 1)
        slots[i] = ch
        target = (0 until count).firstOrNull { slots[it] == null } ?: i
        fixAudio()
        return i
    }

    fun clear(i: Int) {
        slots[i] = null
        fixAudio()
    }

    /** Eventi assegnati ai player visibili. */
    fun active(): List<Channel> = (0 until count).mapNotNull { slots[it] }

    /** Svuota i player (il numero di player scelto resta). */
    fun reset() {
        for (i in slots.indices) slots[i] = null
        audio = 0
        target = 0
    }
}

/**
 * Schermata a tutto schermo con da 1 a 4 player affiancati.
 * Tocca un riquadro per ascoltare quel player (gli altri restano muti);
 * il menu ⋮ di ogni riquadro permette di cambiare o svuotare l'evento.
 */
@OptIn(UnstableApi::class)
class MultiPlayerOverlay(
    context: Context,
    private val st: MultiState,
    private val newPlayer: () -> ExoPlayer,
    private val choices: () -> List<Channel>,
    private val onClose: () -> Unit
) : FrameLayout(context) {

    private class Tile(
        val box: ViewGroup,
        val pv: PlayerView,
        val title: TextView,
        val hint: TextView,
        val spin: CircularProgressIndicator,
        val vol: ImageView,
        val border: View
    )

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val GREEN = 0xFF4CAF50.toInt()

    private val tiles = arrayOfNulls<Tile>(MultiState.MAX)
    private val players = arrayOfNulls<ExoPlayer>(MultiState.MAX)
    private val playingUrl = arrayOfNulls<String>(MultiState.MAX)
    private val triedHls = BooleanArray(MultiState.MAX)
    private val firstErr = arrayOfNulls<PlaybackException>(MultiState.MAX)
    private val countBtns = mutableListOf<TextView>()
    private val FOCUS = 0xFFFFC107.toInt()   // colore dell'anello del focus (telecomando)

    // Android TV / box senza touchscreen: più attenzione al telecomando
    private val isTv: Boolean = run {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
    }
    private val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // Con il telecomando il focus non deve "scappare" sulla schermata che sta sotto il multi-schermo
    override fun focusSearch(focused: View?, direction: Int): View? =
        FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true   // non lascia passare i tocchi alla schermata sotto

        val back = ImageButton(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            foreground = focusRingDrawable(FOCUS, dp(3), true)
            isFocusable = true
            setOnClickListener { onClose() }
        }
        val title = TextView(context).apply {
            text = "Multi-schermo"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val beta = TextView(context).apply {
            text = "BETA"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFFFB8C00.toInt())
            }
        }
        val countRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        for (n in 1..MultiState.MAX) {
            val b = TextView(context).apply {
                text = n.toString()
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                foreground = focusRingDrawable(FOCUS, dp(3), false, dp(16).toFloat())
                isFocusable = true
                setOnClickListener { setCount(n) }
            }
            countBtns.add(b)
            countRow.addView(b, LinearLayout.LayoutParams(dp(36), dp(32)).apply { leftMargin = dp(6) })
        }
        paintCounts()

        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), 0)
            addView(back, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(title, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
            addView(beta, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            if (isTv) addView(TextView(context).apply {
                text = "OK: audio · OK lungo: menu"
                textSize = 12f
                setTextColor(0xB3FFFFFF.toInt())
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(16) })
            addView(countRow, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, dp(44)))
            addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
        rebuild()
        // il telecomando parte dal primo riquadro
        post { tiles[0]?.box?.requestFocus() }
    }

    // ---------- Numero di player ----------

    private fun paintCounts() {
        countBtns.forEachIndexed { k, b ->
            val on = k + 1 == st.count
            b.setTextColor(if (on) Color.BLACK else Color.WHITE)
            b.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(if (on) Color.WHITE else 0x33FFFFFF)
            }
        }
    }

    private fun setCount(n: Int) {
        if (n == st.count) return
        st.changeCount(n)
        paintCounts()
        rebuild()
    }

    // ---------- Griglia dei riquadri ----------

    /** Ricrea i riquadri (1 = pieno, 2 = affiancati, 3-4 = griglia 2x2); i player già attivi non ripartono. */
    private fun rebuild() {
        for (t in tiles) t?.pv?.player = null
        for (i in tiles.indices) tiles[i] = null
        grid.removeAllViews()
        val n = st.count
        // i player oltre il numero scelto vengono chiusi (niente rete sprecata)
        for (i in n until MultiState.MAX) releasePlayer(i)
        for (k in players.indices) players[k]?.let { applyPrefs(k, it) }

        val cols = if (n == 1) 1 else 2
        val rows = (n + cols - 1) / cols
        var idx = 0
        for (r in 0 until rows) {
            val row = LinearLayout(context)
            for (k in 0 until cols) {
                if (idx < n) {
                    val t = makeTile(idx)
                    tiles[idx] = t
                    row.addView(t.box, LinearLayout.LayoutParams(0, MATCH, 1f))
                    idx++
                } else {
                    row.addView(View(context), LinearLayout.LayoutParams(0, MATCH, 1f))
                }
            }
            grid.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        for (i in 0 until n) bind(i)
        refreshAudio()
    }

    private fun makeTile(i: Int): Tile {
        // Con 3-4 player i riquadri sono piccoli: nome e pulsanti stanno in una barra SOPRA il video,
        // così non ne coprono nessuna parte
        val strip = st.count >= 3
        val pv = PlayerView(context).apply {
            useController = false
            keepScreenOn = true
            setBackgroundColor(Color.BLACK)
        }
        val title = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (!strip) {
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(0x99000000.toInt())
                }
            }
        }
        val hint = TextView(context).apply {
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        val spin = CircularProgressIndicator(context).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val vol = ImageView(context).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = oval(0x99000000.toInt())
            setOnClickListener { if (st.slots[i] != null) setAudio(i) }
        }
        val more = ImageButton(context).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = oval(0x99000000.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        more.setOnClickListener { showMenu(i, more) }
        if (isTv) {
            // col telecomando basta un solo punto di focus per riquadro (OK = audio, OK lungo = menu)
            vol.isFocusable = false
            more.isFocusable = false
        }
        val border = View(context).apply {
            background = GradientDrawable().apply {
                setStroke(dp(3), GREEN)
                setColor(Color.TRANSPARENT)
            }
            visibility = View.GONE
        }
        val box: ViewGroup = if (strip) {
            val caption = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF161616.toInt())
                setPadding(dp(4), 0, dp(4), 0)
                addView(vol, LinearLayout.LayoutParams(dp(26), dp(26)))
                addView(title, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(6), 0, dp(6), 0) })
                addView(more, LinearLayout.LayoutParams(dp(26), dp(26)))
            }
            val video = FrameLayout(context).apply {
                addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(hint, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(spin, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                addView(border, FrameLayout.LayoutParams(MATCH, MATCH))
            }
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                setPadding(dp(1), dp(1), dp(1), dp(1))
                addView(caption, LinearLayout.LayoutParams(MATCH, dp(30)))
                addView(video, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        } else {
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                setPadding(dp(1), dp(1), dp(1), dp(1))
                addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(hint, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(spin, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                addView(border, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(title, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(6), dp(6), dp(48), 0)
                })
                addView(vol, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(6), 0, 0, dp(6))
                })
                addView(more, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
                    setMargins(0, dp(4), dp(4), 0)
                })
            }
        }
        // tocco / OK: audio (o scelta evento se vuoto); tocco lungo / OK lungo: menu del riquadro
        box.setOnClickListener { onTileTap(i) }
        box.setOnLongClickListener {
            showMenu(i, box)
            true
        }
        box.isFocusable = true
        box.foreground = focusRingDrawable(FOCUS, dp(3), false)
        return Tile(box, pv, title, hint, spin, vol, border)
    }

    // Riquadro vuoto: sceglie l'evento. Riquadro pieno: porta l'audio su quel player.
    private fun onTileTap(i: Int) {
        if (st.slots[i] == null) pickEvent(i) else setAudio(i)
    }

    /** Allinea il riquadro i con l'evento assegnato (avvia, cambia o ferma il player). */
    private fun bind(i: Int) {
        val t = tiles[i] ?: return
        val ch = st.slots[i]
        if (ch == null) {
            t.pv.player = null
            releasePlayer(i)
            t.title.visibility = View.GONE
            t.spin.visibility = View.GONE
            t.hint.text = "+ Scegli un evento"
            t.hint.visibility = View.VISIBLE
            return
        }
        t.title.text = "${i + 1} · ${ch.name}"
        t.title.visibility = View.VISIBLE
        t.hint.visibility = View.GONE
        val p = players[i] ?: newTilePlayer(i).also { players[i] = it }
        t.pv.player = p
        if (playingUrl[i] != ch.url) {
            firstErr[i] = null
            start(i, ch.url, ch.url.contains(".m3u8", ignoreCase = true))
        }
    }

    // ---------- Player ----------

    private fun newTilePlayer(i: Int): ExoPlayer {
        val p = newPlayer()
        // ogni riquadro è piccolo: 720p bastano e alleggeriscono il telefono con più player accesi
        applyPrefs(i, p)
        p.volume = if (i == st.audio) 1f else 0f
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onTileError(i, error)
            override fun onPlaybackStateChanged(state: Int) {
                tiles[i]?.spin?.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            }
        })
        return p
    }

    /**
     * Alleggerisce i player (su TV niente "lag"): solo il player con l'audio ha qualità piena,
     * gli altri hanno una risoluzione più bassa e non decodificano l'audio.
     */
    private fun applyPrefs(i: Int, p: ExoPlayer) {
        val on = i == st.audio
        val (w, h) = when {
            !isTv -> 1280 to 720
            st.count == 1 -> 1920 to 1080
            st.count == 2 -> if (on) 1280 to 720 else 854 to 480
            else -> if (on) 960 to 540 else 640 to 360
        }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(w, h)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !on)
            .build()
    }

    private fun start(i: Int, url: String, asHls: Boolean) {
        val p = players[i] ?: return
        playingUrl[i] = url
        triedHls[i] = asHls
        val item = MediaItem.Builder().setUri(url).apply {
            if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        tiles[i]?.hint?.visibility = View.GONE
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    // Se il formato non si capisce dall'URL, riprova una volta come HLS (come nel player singolo),
    // ma non per errori del decoder: lì un secondo tentativo non serve e nasconde il problema vero.
    private fun onTileError(i: Int, e: PlaybackException) {
        val url = playingUrl[i] ?: return
        if (!triedHls[i] && !Playback.isDecoderError(e)) {
            firstErr[i] = e
            start(i, url, true)
            return
        }
        val shown = Playback.pickError(firstErr[i], e)
        tiles[i]?.spin?.visibility = View.GONE
        tiles[i]?.hint?.apply {
            text = if (Playback.isDecoderError(shown))
                "Decoder video non disponibile\n(${shown.errorCodeName})\nProva con meno player"
            else "Impossibile riprodurre\n(${shown.errorCodeName})"
            visibility = View.VISIBLE
        }
    }

    private fun releasePlayer(i: Int) {
        players[i]?.release()
        players[i] = null
        playingUrl[i] = null
    }

    // ---------- Audio ----------

    private fun setAudio(i: Int) {
        st.audio = i
        refreshAudio()
    }

    /** Solo il player scelto ha il volume; gli altri sono muti. Il riquadro attivo ha il bordo verde. */
    private fun refreshAudio() {
        st.fixAudio()
        for (i in 0 until MultiState.MAX) players[i]?.let {
            it.volume = if (i == st.audio) 1f else 0f
            applyPrefs(i, it)
        }
        for (i in 0 until st.count) {
            val t = tiles[i] ?: continue
            val filled = st.slots[i] != null
            val on = filled && i == st.audio
            t.vol.setImageResource(if (on) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
            t.vol.imageTintList = ColorStateList.valueOf(if (on) GREEN else Color.WHITE)
            t.vol.visibility = if (filled) View.VISIBLE else View.GONE
            t.border.visibility = if (on) View.VISIBLE else View.GONE
        }
    }

    // ---------- Menu e scelta evento ----------

    // Dialogo al centro dello schermo (il PopupMenu usciva in alto e si vedeva poco con il telecomando)
    private fun showMenu(i: Int, @Suppress("UNUSED_PARAMETER") anchor: View) {
        val filled = st.slots[i] != null
        val labels = mutableListOf<String>()
        val acts = mutableListOf<() -> Unit>()
        if (filled && i != st.audio) {
            labels.add("Ascolta questo player")
            acts.add { setAudio(i) }
        }
        labels.add(if (filled) "Cambia evento" else "Scegli evento")
        acts.add { pickEvent(i) }
        if (filled) {
            labels.add("Svuota player")
            acts.add {
                st.clear(i)
                bind(i)
                refreshAudio()
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Player ${i + 1}")
            .setItems(labels.toTypedArray()) { _, which -> acts[which]() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun pickEvent(i: Int) {
        // gli eventi già in riproduzione negli altri player non si possono scegliere
        val list = choices().filter { !st.isPlaying(it, except = i) }
        if (list.isEmpty()) {
            Toast.makeText(context, "Tutti gli eventi sono già in riproduzione", Toast.LENGTH_SHORT).show()
            return
        }
        val names = Array(list.size) { k -> "${list[k].name}  ·  ${list[k].category}" }
        val cur = list.indexOfFirst { c -> st.slots[i]?.let { st.same(it, c) } == true }
        MaterialAlertDialogBuilder(context)
            .setTitle("Player ${i + 1}")
            .setSingleChoiceItems(names, cur) { d, which ->
                if (st.isPlaying(list[which], except = i)) {
                    d.dismiss()
                    return@setSingleChoiceItems
                }
                st.slots[i] = list[which]
                bind(i)
                refreshAudio()
                d.dismiss()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ---------- Ciclo di vita ----------

    fun pauseAll() {
        for (p in players) p?.pause()
    }

    fun resumeAll() {
        for (p in players) p?.play()
    }

    /** Ferma e libera tutti i player (da chiamare quando si chiude la schermata). */
    fun release() {
        for (t in tiles) t?.pv?.player = null
        for (i in players.indices) releasePlayer(i)
    }
}
