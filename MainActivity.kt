package it.mio.iptv

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Rational
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import com.google.android.material.R as MR

/** Punti di focus di una riga dell'admin, da sinistra a destra (serve per navigare bene col telecomando). */
private class FocusRow(val items: List<View>)

// Contenitore che mantiene il rapporto 16:9 (come il player di YouTube)
private class Ratio(ctx: Context) : FrameLayout(ctx) {
    var ratio = true
    var maxH = Int.MAX_VALUE
    override fun onMeasure(w: Int, h: Int) {
        if (ratio) {
            val width = MeasureSpec.getSize(w)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(minOf(width * 9 / 16, maxH), MeasureSpec.EXACTLY)
            )
        } else super.onMeasure(w, h)
    }
}

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val defaultPlaylist =
        "https://gist.githubusercontent.com/alemagno1994alex-glitch/c285620b3b4c62b806b8037c0eaa6a6e/raw/iptv.m3u"
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val prefs by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }
    private val cache = ConcurrentHashMap<String, List<Channel>>()
    private val fetchedAt = ConcurrentHashMap<String, Long>()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var playerBox: Ratio
    private lateinit var controls: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var fsBtn: ImageButton
    private lateinit var pipBtn: ImageButton
    private lateinit var spinner: CircularProgressIndicator
    private lateinit var seekFlash: TextView

    private lateinit var homeView: View
    private lateinit var homeCards: LinearLayout
    private lateinit var userView: LinearLayout
    private lateinit var adminView: View
    private lateinit var listPanel: LinearLayout
    private lateinit var rightColumn: LinearLayout
    private lateinit var infoRow: LinearLayout
    private lateinit var adminTabs: TabLayout
    private lateinit var adminToolbar: MaterialToolbar
    private lateinit var adminHeader: LinearLayout
    private lateinit var adminScroll: ScrollView
    private var scrollToCat = -1
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var errorText: TextView
    private lateinit var nowLogo: ImageView
    private lateinit var listStatus: TextView
    private lateinit var chipGroup: ChipGroup
    private lateinit var chipScroll: HorizontalScrollView
    private lateinit var channelList: RecyclerView
    private lateinit var liveScroll: ScrollView
    private lateinit var liveBox: LinearLayout
    private val liveCards = mutableListOf<Pair<MaterialCardView, String>>()
    private lateinit var searchField: TextInputLayout
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var adminList: LinearLayout
    private lateinit var fab: ExtendedFloatingActionButton

    private var currentView: View? = null
    private var currentChannel: Channel? = null
    private var allChannels = listOf<Channel>()
    private var categories = listOf<String>()
    private var selectedCategory: String? = null
    private var query = ""
    private var currentUrl = ""
    private var triedHls = false
    private var firstPlayError: PlaybackException? = null   // errore del primo tentativo (prima del riprovo come HLS)
    private var fullscreen = false
    private var userLive = false             // la sezione aperta è di tipo "eventi live": niente player, schermo intero alla scelta
    private var userSection: String? = null   // sezione aperta dall'utente (null = Completa)
    private var adminEditing: String? = null  // sezione di cui l'admin sta gestendo le categorie (null = elenco sezioni)
    private val adminSection get() = adminEditing ?: Sections.DEFAULT_ID
    private var inPip = false
    private var loadGen = 0
    private var adminTab = 0
    private var nightApplied = 0              // modalità chiaro/scuro con cui sono costruite le schermate
    private val collapsedCats = mutableSetOf<String>()   // categorie chiuse (sezione/nome) nella gestione admin
    private var lastLand: Boolean? = null
    private val multi = MultiState()                      // multi-schermo (BETA) nelle sezioni "eventi live"
    private var multiOverlay: MultiPlayerOverlay? = null  // schermata con i player affiancati (null = chiusa)
    private var liveHeader: LinearLayout? = null          // intestazione della sezione live con i controlli multi-schermo

    // ---------- Avvio ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(themeMode())
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        nightApplied = nightNow()
        Logos.init(this)
        if (!prefs.contains("playlists")) savePlaylists(listOf(defaultPlaylist))

        root = FrameLayout(this).apply { setBackgroundColor(c(MR.attr.colorSurface)) }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    multiOverlay != null -> closeMulti()
                    fullscreen -> setFullscreen(false)
                    currentView === adminView && adminTab == 1 && adminEditing != null -> {
                        adminEditing = null
                        refreshAdmin()
                    }
                    currentView !== homeView -> goHome()
                    else -> finish()
                }
            }
        })

        SportsDb.init(applicationContext)
        setupPlayer()
        buildHome()
        buildUser()
        buildAdmin()
        // Se l'activity viene ricreata (es. cambio tema deciso dal sistema) torni dove eri:
        // nelle Impostazioni (con la sezione che stavi modificando) o nella sezione che guardavi.
        val saved = savedInstanceState
        when {
            saved != null && saved.getBoolean("admin") -> {
                adminTab = saved.getInt("adminTab", 0)
                adminEditing = saved.getString("adminEditing")
                showAdmin(resetEditing = false)
            }
            saved != null && saved.getBoolean("user") -> showUser(saved.getString("userSection"))
            else -> show(homeView)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("admin", currentView === adminView)
        outState.putInt("adminTab", adminTab)
        outState.putString("adminEditing", adminEditing)
        outState.putBoolean("user", currentView === userView)
        outState.putString("userSection", userSection)
    }

    private fun nightNow() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    /**
     * Applica il tema chiaro/scuro senza ricreare l'activity (molto più veloce):
     * riapplica lo stile, ricostruisce le schermate con i nuovi colori e tiene lo stesso player.
     */
    private fun applyThemeInPlace() {
        nightApplied = nightNow()
        theme.applyStyle(R.style.Theme_IPTV, true)
        DynamicColors.applyToActivityIfAvailable(this)
        val night = nightApplied == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(c(MR.attr.colorSurface)))
        root.setBackgroundColor(c(MR.attr.colorSurface))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        // Da leggere PRIMA di ricostruire: dopo buildHome/buildUser/buildAdmin le schermate sono oggetti nuovi
        // (confrontarle dopo faceva sempre cadere nel "else" e riportava alla home).
        val wasAdmin = currentView === adminView
        val wasUser = currentView === userView
        val section = userSection
        val playing = currentChannel
        (playerBox.parent as? ViewGroup)?.removeView(playerBox)   // il player resta lo stesso
        buildHome()
        buildUser()
        buildAdmin()
        lastLand = null
        when {
            wasAdmin -> {
                applyAdminLayout()
                adminTabs.getTabAt(adminTab)?.select()
                refreshAdmin()
                show(adminView)
            }
            // sezione/lista che stavi guardando: stessa sezione, stesso canale in riproduzione (il player non si ferma)
            wasUser -> showUser(section, playing)
            else -> {
                refreshHome()
                show(homeView)
            }
        }
    }

    // Tema dell'app: "system" (come il telefono), "light" o "dark"
    private fun themeMode() = when (prefs.getString("theme", "system")) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Cambio chiaro/scuro (anche dal sistema): senza ricreare l'activity
        if (nightNow() != nightApplied) {
            applyThemeInPlace()
            return
        }
        if (currentView === userView) applyUserLayout()
        if (currentView === adminView) applyAdminLayout()
    }

    // ---------- Utility UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(h: Int, w: Float = 0f) = LinearLayout.LayoutParams(MATCH, h, w)
    private fun c(attr: Int) = MaterialColors.getColor(this, attr, Color.MAGENTA)
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun selectable(): Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    // Ripple che resta dentro i bordi della vista (quello "borderless" si allarga a tutto lo schermo)
    private fun selectableBounded(): Drawable? {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun iconBtn(res: Int, tint: Int, pad: Int, bg: Int?, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            background = if (bg != null) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bg)
            } else selectable()
            // anello visibile quando il focus arriva dal telecomando
            foreground = focusRingDrawable(
                if (tint == Color.WHITE) Color.WHITE else c(MR.attr.colorPrimary), dp(3), true, fill = true
            )
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun show(v: View) {
        root.removeAllViews()
        root.addView(v, ViewGroup.LayoutParams(MATCH, MATCH))
        multiOverlay?.let { root.addView(it, ViewGroup.LayoutParams(MATCH, MATCH)) }   // il multi-schermo resta sopra
        currentView = v
        updatePip()
    }

    private fun field(hint: String): TextInputLayout {
        val til = TextInputLayout(this)
        til.hint = hint
        til.addView(TextInputEditText(til.context).apply {
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        })
        return til
    }

    private fun confirmDelete(msg: String, ok: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Eliminare?")
            .setMessage(msg)
            .setPositiveButton("Elimina") { _, _ -> ok() }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // Card Material 3 usata per le righe di canali e playlist
    private fun itemCard(
        title: String, sub: String?, selected: Boolean,
        onClick: (() -> Unit)?, onEdit: (() -> Unit)? = null, onDelete: (() -> Unit)?
    ): View {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 16f, c(MR.attr.colorOnSurface), true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (sub != null) addView(tv(sub, 13f, c(MR.attr.colorOnSurfaceVariant)).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        val editBtn = onEdit?.let { f ->
            iconBtn(R.drawable.ic_edit, c(MR.attr.colorOnSurfaceVariant), dp(10), null) { f() }
        }
        val delBtn = onDelete?.let { f ->
            iconBtn(R.drawable.ic_delete, c(MR.attr.colorOnSurfaceVariant), dp(10), null) { f() }
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (editBtn != null) addView(editBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            if (delBtn != null) addView(delBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                if (selected) c(MR.attr.colorPrimaryContainer) else c(MR.attr.colorSurfaceVariant)
            )
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                enableFocusRing()
                setOnClickListener { onClick() }
            }
            addView(row)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        // per il telecomando: la riga (card + matita + cestino) è una fila di punti di focus collegati
        card.tag = FocusRow(listOfNotNull(if (onClick != null) card else null, editBtn, delBtn))
        return card
    }

    private fun homeCard(icon: Int, title: String, sub: String, onClick: () -> Unit): View {
        val onC = c(MR.attr.colorOnSecondaryContainer)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 20f, onC, true))
            if (sub.isNotBlank()) addView(tv(sub, 14f, onC))
        }
        return MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c(MR.attr.colorSecondaryContainer))
            isClickable = true
            isFocusable = true
            enableFocusRing()
            setOnClickListener { onClick() }
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(onC)
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { rightMargin = dp(20) })
                addView(texts)
            })
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) }
        }
    }

    // ---------- Home: sezioni / Completa / Impostazioni ----------

    private fun buildHome() {
        // Barra fissa in alto con il nome dell'app (fuori dalle sezioni)
        val bar = MaterialToolbar(this).apply {
            title = "IPTV Player"
            setNavigationIcon(R.drawable.ic_tv)
            setNavigationIconTint(c(MR.attr.colorPrimary))
            setTitleTextColor(c(MR.attr.colorOnSurface))
        }
        val divider = View(this).apply { setBackgroundColor(c(MR.attr.colorOutlineVariant)) }
        val cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        homeCards = cards
        homeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(divider, LinearLayout.LayoutParams(MATCH, dp(1)))
            addView(ScrollView(context).apply {
                scrollbar()
                isFillViewport = true
                addView(cards, ViewGroup.LayoutParams(MATCH, WRAP))
            }, lp(0, 1f))
        }
        refreshHome()
    }

    // Le card della home: le sezioni create dall'admin, poi Completa e Impostazioni
    // Precarica thumb e orari degli eventi delle sezioni live, così sono già pronti quando le apri
    private fun prefetchLive() {
        Thread {
            val chans = Sections.load(prefs).filter { it.isLive }
                .flatMap { sec -> Featured.load(prefs, sec.id).flatMap { it.channels } }
            if (chans.isNotEmpty()) SportsDb.prefetch(chans)
        }.start()
    }

    private fun refreshHome() {
        prefetchLive()
        homeCards.removeAllViews()
        Sections.load(prefs).forEach { sec ->
            homeCards.addView(
                homeCard(
                    Sections.iconRes(sec.icon), sec.name,
                    sec.description
                ) { showUser(sec.id) }
            )
        }
        homeCards.addView(homeCard(R.drawable.ic_tv, "Completa", "Tutti i canali delle playlist") { showUser(null) })
        homeCards.addView(homeCard(R.drawable.ic_settings, "Impostazioni", "Tema, playlist e sezioni della home") { showAdmin() })
    }

    private fun goHome() {
        player.stop()
        if (multiOverlay != null) closeMulti()
        multi.reset()   // i player del multi-schermo si svuotano (il numero scelto resta)
        if (fullscreen) setFullscreen(false)
        currentChannel = null
        refreshHome()
        show(homeView)
    }

    // ---------- Player in stile YouTube ----------

    // Un nuovo ExoPlayer con le stesse impostazioni di rete (serve anche ai player del multi-schermo).
    // light = true per i player del multi-schermo: buffer più piccoli, così la TV fa meno fatica
    private fun newExo(light: Boolean = false): ExoPlayer {
        // User-Agent da lettore IPTV: molti server rifiutano quello di default.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        // decoder: se quello hardware è occupato prova gli altri; coda asincrona = video più fluido anche su telefoni meno recenti
        val rf = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .forceEnableMediaCodecAsynchronousQueueing()
        val b = ExoPlayer.Builder(this, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
        if (light) b.setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 15000, 1500, 3000).build())
        return b.build()   // qualità lasciata di default; solo il multi-schermo la riduce (vedi MultiView)
    }

    private fun setupPlayer() {
        player = newExo()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                showControls()
                updatePip()
            }
            override fun onPlaybackStateChanged(state: Int) {
                val buffering = state == Player.STATE_BUFFERING
                spinner.visibility = if (buffering) View.VISIBLE else View.GONE
                playBtn.visibility = if (buffering) View.INVISIBLE else View.VISIBLE
            }
        })

        playerView = PlayerView(this).apply {
            useController = false
            keepScreenOn = true
            this.player = this@MainActivity.player
        }

        val white = Color.WHITE
        titleView = tv("", 16f, white, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        playBtn = iconBtn(R.drawable.ic_play, white, dp(14), 0x80000000.toInt()) { togglePlay() }
        fsBtn = iconBtn(R.drawable.ic_fullscreen, white, dp(10), null) { setFullscreen(!fullscreen) }
        pipBtn = iconBtn(R.drawable.ic_pip, white, dp(10), null) { enterPip() }
        if (!pipSupported()) pipBtn.visibility = View.GONE
        val liveDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF0000.toInt())
            }
        }
        val live = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(liveDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
            addView(tv("DIRETTA", 12f, white, true).apply { includeFontPadding = false })
        }
        // Barra in basso: "DIRETTA" e icone sulla stessa riga, centrate in verticale
        val bottomBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(8), dp(4))
            addView(live, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(pipBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(fsBtn, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        controls = FrameLayout(this).apply {
            addView(View(context).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(titleView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
            addView(playBtn, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            addView(bottomBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        spinner = CircularProgressIndicator(this).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        seekFlash = tv("", 18f, white, true).apply {
            visibility = View.GONE
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0x80000000.toInt())
            }
        }
        playerBox = Ratio(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(spinner, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
            addView(seekFlash, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }

        // Tocco singolo: mostra/nasconde i controlli. Doppio tocco: -10 s / +10 s
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                toggleControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                seekBy(if (e.x < playerBox.width / 2f) -10000L else 10000L)
                return true
            }
        })
        playerBox.setOnTouchListener { _, e -> gd.onTouchEvent(e) }

        // Telecomando: con il focus sul player, OK = play/pausa, ◀ ▶ = -10 s / +10 s (se il video è riavvolgibile)
        playerBox.isFocusable = true
        playerBox.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    togglePlay()
                    showControls()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (player.isCurrentMediaItemSeekable) {
                        seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10000L else 10000L)
                        showControls()
                        true
                    } else false
                else -> false
            }
        }
    }

    // Tasti multimediali del telecomando (play/pausa, avanti, indietro) funzionano ovunque
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentChannel != null) when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); showControls(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!player.isPlaying) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (player.isPlaying) togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10000L); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000L); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val hideRun = Runnable {
        controls.animate().alpha(0f).setDuration(200).withEndAction {
            controls.visibility = View.GONE
        }.start()
    }

    private fun showControls() {
        if (inPip) return
        ui.removeCallbacks(hideRun)
        controls.animate().cancel()
        controls.alpha = 1f
        controls.visibility = View.VISIBLE
        if (player.isPlaying) ui.postDelayed(hideRun, 3000)
    }

    private fun toggleControls() {
        if (controls.visibility == View.VISIBLE) {
            ui.removeCallbacks(hideRun)
            hideRun.run()
        } else showControls()
    }

    private fun setError(t: String) {
        errorText.text = t
        errorText.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    private fun seekBy(ms: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0))
        seekFlash.text = if (ms < 0) "−10 s" else "+10 s"
        seekFlash.visibility = View.VISIBLE
        (seekFlash.layoutParams as FrameLayout.LayoutParams).gravity =
            Gravity.CENTER_VERTICAL or (if (ms < 0) Gravity.START else Gravity.END)
        seekFlash.requestLayout()
        ui.postDelayed({ seekFlash.visibility = View.GONE }, 600)
    }

    private fun start(url: String, asHls: Boolean) {
        val item = MediaItem.Builder().setUri(url).apply {
            if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
    }

    // Se il formato non si capisce dall'URL, riprova una volta come HLS.
    private fun onError(e: PlaybackException) {
        if (currentUrl.isEmpty()) return
        // Riprova come HLS solo se il problema può essere il formato (non per errori del decoder)
        if (!triedHls && !Playback.isDecoderError(e)) {
            triedHls = true
            firstPlayError = e
            start(currentUrl, true)
            return
        }
        // mostra l'errore vero, non quello (fuorviante) del secondo tentativo
        val shown = Playback.pickError(firstPlayError, e)
        setError("Impossibile riprodurre questo canale (${shown.errorCodeName}).")
    }

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        applyUserLayout()
        if (on) playerBox.requestFocus()   // a schermo intero il telecomando comanda subito il player
        fsBtn.setImageResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            val last = currentChannel?.url
            ctl.show(WindowInsetsCompat.Type.systemBars())
            if (userLive && currentView === userView) {
                player.stop()
                currentChannel = null
                liveSelect(null)
            }
            restoreFocus(last)
        }
    }

    // Dopo lo schermo intero il telecomando riparte dal canale/evento che stavi guardando (non dall'inizio della lista)
    private fun restoreFocus(url: String?) {
        if (url == null || currentView !== userView) return
        ui.post {
            if (userLive) liveCards.firstOrNull { it.second == url }?.first?.requestFocus()
            else {
                val pos = channelAdapter.positionOf(url)
                if (pos >= 0) {
                    channelList.scrollToPosition(pos)
                    channelList.post { channelList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
                }
            }
        }
    }

    // ---------- Picture in picture ----------

    private fun pipSupported() = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        // Android 12+: entra da solo in PiP quando si esce dall'app mentre il canale è in riproduzione
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pipSupported() && currentView === userView && player.isPlaying)
        }
        return b.build()
    }

    private fun updatePip() {
        if (!pipSupported()) return
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
        }
    }

    private fun enterPip() {
        if (!pipSupported() || currentView !== userView) return
        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: Exception) {
            toast("Picture in picture non disponibile")
        }
    }

    // Android 8-11: si entra in PiP quando l'utente esce dall'app (tasto home)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && currentView === userView && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            ui.removeCallbacks(hideRun)
            controls.visibility = View.GONE
        } else showControls()
        applyUserLayout()
    }

    // ---------- Schermata utente: categorie + canali ----------

    private fun logoBox(): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = logoBackground(this@MainActivity, 8)
    }

    private fun buildUser() {
        nowTitle = tv("Scegli un canale", 20f, c(MR.attr.colorOnSurface), true).apply { includeFontPadding = false }
        nowSub = tv("", 13f, c(MR.attr.colorOnSurfaceVariant)).apply { includeFontPadding = false }
        errorText = tv("", 14f, c(MR.attr.colorError)).apply { visibility = View.GONE }
        nowLogo = logoBox().apply { visibility = View.GONE }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nowTitle)
            addView(nowSub)
            addView(errorText)
        }
        infoRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(nowLogo, LinearLayout.LayoutParams(dp(64), dp(44)).apply { rightMargin = dp(12) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        listStatus = tv("", 13f, c(MR.attr.colorOnSurfaceVariant)).apply {
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
            setPadding(dp(16), 0, dp(16), 0)
        }
        chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipGroup)
        }
        searchField = field("Cerca canale").apply { setPadding(dp(16), dp(4), dp(16), 0) }
        searchField.editText!!.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        channelAdapter = ChannelAdapter(this) { onChannelTap(it) }
        channelList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channelAdapter
            itemAnimator = null   // niente animazioni di cambio: il focus resta sulla riga e la lista è più leggera
            scrollbar()
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), dp(16))
        }

        // Sezioni "eventi live": categorie con carosello orizzontale di card (al posto della lista)
        liveBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveScroll = ScrollView(this).apply {
            scrollbar()
            clipToPadding = false
            setPadding(dp(12), 0, dp(12), dp(16))
            visibility = View.GONE
            addView(liveBox)
        }

        // Pannello con la lista dei canali (a sinistra in orizzontale, sotto in verticale)
        listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(listStatus)
            addView(chipScroll, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(searchField, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(channelList, lp(0, 1f))
            addView(liveScroll, lp(0, 1f))
        }
        // Player + info sul canale (a destra in orizzontale, sopra in verticale)
        rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(playerBox, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(infoRow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // listPanel viene aggiunto da applyUserLayout() nel posto giusto
        userView = LinearLayout(this).apply { addView(rightColumn) }
    }

    /** Verticale: player sopra e canali sotto. Orizzontale: canali a sinistra e player a destra. */
    private fun applyUserLayout() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val playerOnly = fullscreen || inPip
        listPanel.visibility = if (playerOnly) View.GONE else View.VISIBLE
        infoRow.visibility = if (playerOnly || userLive) View.GONE else View.VISIBLE
        // Sezione live: il player compare solo a schermo intero (o in PiP)
        rightColumn.visibility = if (userLive && !playerOnly) View.GONE else View.VISIBLE
        playerBox.ratio = !playerOnly
        playerBox.maxH = if (land) (resources.displayMetrics.heightPixels * 0.7f).toInt() else Int.MAX_VALUE
        playerBox.layoutParams = LinearLayout.LayoutParams(MATCH, if (playerOnly) MATCH else WRAP)
        if (lastLand != land) {
            userView.orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            userView.removeView(listPanel)
            if (land) userView.addView(listPanel, 0, LinearLayout.LayoutParams(0, MATCH, 4f))
            else userView.addView(listPanel, LinearLayout.LayoutParams(MATCH, 0, 1f))
            lastLand = land
        }
        rightColumn.layoutParams =
            if (land) LinearLayout.LayoutParams(0, MATCH, 6f)
            else LinearLayout.LayoutParams(MATCH, if (playerOnly) MATCH else WRAP)
    }

    // Primo tocco: seleziona e riproduce. Tocco sul canale già selezionato: schermo intero.
    private fun onChannelTap(ch: Channel) {
        if (userLive && multi.count > 1) {
            assignToMulti(ch)   // multi-schermo: l'evento va nel player evidenziato
        } else if (userLive) {
            playChannel(ch)
            setFullscreen(true)
        } else if (currentChannel?.url == ch.url) setFullscreen(true) else playChannel(ch)
    }

    // keepPlaying: dopo un cambio tema si rimette la schermata com'era, senza fermare il canale in riproduzione
    private fun showUser(section: String?, keepPlaying: Channel? = null) {
        userSection = section
        val live = section?.let { id -> Sections.load(prefs).firstOrNull { it.id == id } }?.isLive == true
        userLive = live
        loadGen++
        currentChannel = keepPlaying
        nowTitle.text = if (live) "Scegli un evento" else "Scegli un canale"
        chipScroll.visibility = if (live) View.GONE else View.VISIBLE
        searchField.visibility = if (live) View.GONE else View.VISIBLE
        channelList.visibility = if (live) View.GONE else View.VISIBLE
        liveScroll.visibility = if (live) View.VISIBLE else View.GONE
        liveBox.removeAllViews()
        liveCards.clear()
        nowSub.text = ""
        titleView.text = ""
        setError("")
        nowLogo.visibility = View.GONE
        channelAdapter.select(null)
        setChannels(emptyList())   // via subito i canali dell'altra sezione
        listStatus.text = ""
        applyUserLayout()
        if (keepPlaying != null) showNowPlaying(keepPlaying)
        if (section != null) loadFeatured(section) else loadAllChannels()
        show(userView)
    }

    // Sezione: solo i canali scelti dall'admin, nelle sue categorie e nel suo ordine
    private fun loadFeatured(section: String) {
        val cats = Featured.load(prefs, section)
        val all = cats.flatMap { it.channels }
        val sec = Sections.load(prefs).firstOrNull { it.id == section }
        val name = sec?.name ?: "Sezione"
        val live = sec?.isLive == true
        setChannels(all, cats.filter { it.channels.isNotEmpty() }.map { it.name })
        if (live) buildLive(cats)
        val noun = if (live) "eventi" else "canali"
        listStatus.text =
            if (all.isEmpty()) "Nessun ${if (live) "evento" else "canale"} in $name: aggiungili da Impostazioni › Admin."
            else "$name · ${all.size} $noun"
    }

    // ---------- Sezioni "eventi live": carosello di card per categoria ----------

    private fun liveBadge() = TextView(this).apply {
        text = "LIVE"
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(0xFFE53935.toInt())
        }
    }

    // Tag in alto a sinistra: LIVE se l'evento è in corso (o l'orario non si sa), altrimenti l'orario di inizio
    private fun paintTag(t: TextView, start: Long) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val live = start <= 0L || (now >= start && now < start + 3 * 3_600_000L)
        t.text = if (live) "LIVE" else timeLabel(start, zone)
        (t.background as? GradientDrawable)?.setColor(
            when {
                live -> 0xFFE53935.toInt()          // rosso: in corso
                now < start -> 0xFF1E88E5.toInt()   // blu: deve ancora iniziare
                else -> 0xFF757575.toInt()          // grigio: finito
            }
        )
    }

    private fun timeLabel(ms: Long, zone: ZoneId): String {
        val dt = Instant.ofEpochMilli(ms).atZone(zone)
        val hm = dt.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when (ChronoUnit.DAYS.between(LocalDate.now(zone), dt.toLocalDate())) {
            0L -> hm
            1L -> "Domani $hm"
            -1L -> "Ieri $hm"
            else -> dt.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ITALIAN))
        }
    }

    // Orario scritto a mano dall'admin ("20:45", di oggi); 0 = non impostato
    private fun manualStart(time: String): Long {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(time.trim()) ?: return 0L
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return 0L
        return LocalDate.now().atTime(h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun liveCard(ch: Channel): View {
        val badge = liveBadge()
        val manual = manualStart(ch.time)
        if (manual > 0) paintTag(badge, manual)
        val thumb = FrameLayout(this).apply {
            val logo = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(24), dp(16), dp(24), dp(16))
                background = logoBackground(context, 12)
                clipToOutline = true   // arrotonda anche l'immagine a tutto riquadro
            }
            addView(logo, FrameLayout.LayoutParams(MATCH, MATCH))
            loadChannelLogo(logo, ch)   // intanto il logo del canale; se TheSportsDB ha il thumb della partita lo sostituisce
            SportsDb.loadThumb(logo, ch, onFound = {
                logo.scaleType = ImageView.ScaleType.CENTER_CROP
                logo.setPadding(0, 0, 0, 0)
                logo.imageTintList = null
            }, onFail = {
                // l'immagine non si è caricata: torna il logo del canale
                logo.scaleType = ImageView.ScaleType.FIT_CENTER
                logo.setPadding(dp(24), dp(16), dp(24), dp(16))
                loadChannelLogo(logo, ch)
            }, onStart = { start ->
                // l'orario viene da TheSportsDB, a meno che l'admin non ne abbia scritto uno a mano
                if (manual == 0L) paintTag(badge, start)
            })
            addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(8), dp(8), 0, 0)
            })
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(thumb, LinearLayout.LayoutParams(MATCH, dp(124)).apply { setMargins(dp(8), dp(8), dp(8), 0) })
            addView(tv(ch.name, 14f, c(MR.attr.colorOnSurface), true).apply {
                minLines = 2
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(10), dp(12), 0)
            })
            addView(tv(ch.quality.ifEmpty { "Diretta" }, 12f, c(MR.attr.colorOnSurfaceVariant)).apply {
                maxLines = 1
                setPadding(dp(12), dp(2), dp(12), dp(12))
            })
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c(MR.attr.colorSurfaceVariant))
            isClickable = true
            isFocusable = true
            enableFocusRing()
            setOnClickListener { onChannelTap(ch) }
            addView(col)
            layoutParams = LinearLayout.LayoutParams(dp(236), WRAP).apply { rightMargin = dp(12) }
            liveCards.add(this to ch.url)
        }
    }

    private fun buildLive(cats: List<FeaturedCategory>) {
        liveBox.removeAllViews()
        liveCards.clear()
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveHeader = header
        liveBox.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
        refreshLiveHeader()
        cats.filter { it.channels.isNotEmpty() }.forEach { cat ->
            liveBox.addView(tv(cat.name, 20f, c(MR.attr.colorOnSurface), true).apply {
                setPadding(dp(4), dp(16), dp(4), dp(8))
            })
            val row = LinearLayout(this).apply {
                setPadding(dp(4), 0, dp(4), 0)
                cat.channels.forEach { addView(liveCard(it)) }
                // Ai bordi della riga il focus resta fermo: non passa alle altre categorie
                if (childCount > 0) {
                    val first = getChildAt(0)
                    val last = getChildAt(childCount - 1)
                    if (first.id == View.NO_ID) first.id = View.generateViewId()
                    if (last.id == View.NO_ID) last.id = View.generateViewId()
                    first.nextFocusLeftId = first.id
                    last.nextFocusRightId = last.id
                }
            }
            liveBox.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row)
                },
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
        }
        liveSelect(currentChannel?.url)
    }

    // Evidenzia la card dell'evento in riproduzione (con più player: tutti gli eventi assegnati ai player)
    private fun liveSelect(url: String?) {
        val picked: Set<String> =
            if (multi.count > 1) multi.active().map { it.url }.toSet() else setOfNotNull(url)
        liveCards.forEach { (card, u) ->
            card.setMarked(u in picked)
        }
    }

    // ---------- Multi-schermo (BETA): da 1 a 4 player insieme, con l'audio di uno solo ----------

    private fun refreshLiveHeader() {
        val h = liveHeader ?: return
        h.removeAllViews()
        val onS = c(MR.attr.colorOnSurface)
        val onV = c(MR.attr.colorOnSurfaceVariant)
        val ctx = this

        h.addView(LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), 0)
            addView(tv("Multi-schermo", 16f, onS, true))
            addView(TextView(ctx).apply {
                text = "BETA"
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(0xFFFB8C00.toInt())
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        })

        val chips = ChipGroup(ctx).apply {
            isSingleSelection = true
            isSelectionRequired = true
            setPadding(dp(4), 0, dp(4), 0)
        }
        for (n in 1..MultiState.MAX) {
            chips.addView(Chip(ctx).apply {
                ring(10)
                scrollTopOnFocus()
                id = View.generateViewId()
                text = if (n == 1) "1 player" else "$n player"
                isCheckable = true
                isChecked = n == multi.count
                setOnCheckedChangeListener { _, checked ->
                    if (checked && n != multi.count) setMultiCount(n)
                }
            })
        }
        h.addView(chips, LinearLayout.LayoutParams(WRAP, WRAP))

        if (multi.count > 1) {
            h.addView(
                tv(
                    "Tocca un evento per metterlo nel player evidenziato. Tocca l'altoparlante per scegliere l'audio, " +
                        "tieni premuto un player per svuotarlo.",
                    13f, onV
                ).apply { setPadding(dp(4), dp(4), dp(4), dp(8)) }
            )
            val row = LinearLayout(ctx)
            for (i in 0 until multi.count) {
                row.addView(
                    multiSlotCard(i),
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = if (i < multi.count - 1) dp(8) else 0 }
                )
            }
            h.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
            h.addView(
                MaterialButton(ctx).apply {
                    text = "Guarda"
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_play)
                    iconTint = ColorStateList.valueOf(c(MR.attr.colorOnPrimary))
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    isEnabled = multi.active().isNotEmpty()
                    // il tasto è pieno di colorPrimary: il bordo del focus è quello del tasto stesso (stroke Material)
                    // in un colore a contrasto, così si vede sempre
                    strokeColor = ColorStateList.valueOf(c(MR.attr.colorOnPrimary))
                    strokeWidth = 0
                    onFocusChangeListener = View.OnFocusChangeListener { _, has -> strokeWidth = if (has) dp(4) else 0 }
                    scrollTopOnFocus()
                    setOnClickListener { openMulti() }
                },
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
            )
        }
    }

    // Riquadro di un player nella lista: evidenziato se è quello che riceverà il prossimo evento
    private fun multiSlotCard(i: Int): View {
        val ch = multi.slots[i]
        val target = i == multi.target
        val onC = c(if (target) MR.attr.colorOnPrimaryContainer else MR.attr.colorOnSurface)
        val onV = c(if (target) MR.attr.colorOnPrimaryContainer else MR.attr.colorOnSurfaceVariant)
        val hasAudio = ch != null && i == multi.audio
        val speaker = ImageView(this).apply {
            setImageResource(if (hasAudio) R.drawable.ic_volume_on else R.drawable.ic_volume_off)
            imageTintList = ColorStateList.valueOf(if (hasAudio) c(MR.attr.colorPrimary) else onV)
            visibility = if (ch != null) View.VISIBLE else View.INVISIBLE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                multi.audio = i
                refreshLiveHeader()
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv("Player ${i + 1}", 12f, onV))
            addView(tv(ch?.name ?: "Vuoto", 14f, onC, true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(speaker, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        return MaterialCardView(this).apply {
            radius = dp(12).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c(if (target) MR.attr.colorPrimaryContainer else MR.attr.colorSurfaceVariant))
            isClickable = true
            isFocusable = true
            enableFocusRing()
            scrollTopOnFocus()
            setOnClickListener {
                multi.target = i
                refreshLiveHeader()
            }
            setOnLongClickListener {
                if (multi.slots[i] != null) {
                    multi.clear(i)
                    refreshLiveHeader()
                    liveSelect(null)
                }
                true
            }
            addView(row)
        }
    }

    private fun setMultiCount(n: Int) {
        multi.changeCount(n)
        // il ridisegno avviene dopo la callback del chip (non si tolgono viste mentre il chip sta cambiando)
        ui.post {
            refreshLiveHeader()
            liveSelect(null)
            // si risale in cima, così si vede subito "Multi-schermo" con i player
            liveScroll.post { liveScroll.smoothScrollTo(0, 0) }
        }
    }

    private fun assignToMulti(ch: Channel) {
        multi.assign(ch)
        refreshLiveHeader()
        liveSelect(null)
    }

    // Eventi tra cui scegliere dentro la schermata multi-schermo: quelli della sezione aperta
    private fun multiChoices(): List<Channel> =
        userSection?.let { s -> Featured.load(prefs, s).flatMap { it.channels } } ?: emptyList()

    private fun openMulti() {
        if (multiOverlay != null) return
        if (multi.active().isEmpty()) {
            toast("Scegli almeno un evento")
            return
        }
        player.stop()
        currentChannel = null
        val o = MultiPlayerOverlay(this, multi, { newExo(true) }, { multiChoices() }, { closeMulti() })
        multiOverlay = o
        root.addView(o, ViewGroup.LayoutParams(MATCH, MATCH))
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        updatePip()
    }

    private fun closeMulti() {
        val o = multiOverlay ?: return
        multiOverlay = null
        o.release()
        root.removeView(o)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        refreshLiveHeader()
        liveSelect(null)
        updatePip()
    }

    // Carica le playlist in background: mostra subito quanto è già salvato (memoria/disco),
    // poi scarica gli aggiornamenti. cb(canali ordinati, errori, finale?) arriva sul thread UI.
    private fun loadPlaylistChannels(cb: (List<Channel>, Int, Boolean) -> Unit) {
        val urls = loadPlaylists()
        if (urls.isEmpty()) {
            cb(emptyList(), 0, true)
            return
        }
        Thread {
            val cached = urls.map { cachedPlaylist(it) }
            val now = System.currentTimeMillis()
            // se scaricata da meno di 10 minuti non serve riscaricarla
            val fresh = urls.map { cache.containsKey(it) && (fetchedAt[it] ?: 0L) + 10 * 60_000L > now }
            if (cached.any { it != null } && !fresh.all { it }) {
                val first = Categorizer.sortChannels(cached.filterNotNull().flatten())
                runOnUiThread { cb(first, 0, false) }
            }
            val pool = Executors.newFixedThreadPool(minOf(urls.size, 4))
            val futures = urls.mapIndexed { i, u ->
                pool.submit(Callable<List<Channel>?> { if (fresh[i]) cached[i] else (downloadPlaylist(u) ?: cached[i]) })
            }
            var errors = 0
            val lists = futures.map { f ->
                val r = try { f.get() } catch (e: Exception) { null }
                if (r == null) errors++
                r
            }
            pool.shutdown()
            val all = Categorizer.sortChannels(lists.filterNotNull().flatten())
            runOnUiThread { cb(all, errors, true) }
        }.start()
    }

    // "Completa": tutte le playlist, con le categorie create da sole
    private fun loadAllChannels() {
        val gen = loadGen
        if (loadPlaylists().isEmpty()) {
            listStatus.text = "Nessuna playlist: aggiungila da Impostazioni › Admin."
            return
        }
        listStatus.text = "Caricamento canali…"
        var shown: List<Channel>? = null
        loadPlaylistChannels { all, errors, final ->
            if (gen != loadGen) return@loadPlaylistChannels
            if (shown != all) {
                setChannels(all)
                shown = all
            }
            listStatus.text = when {
                !final -> "${all.size} canali · aggiornamento…"
                errors > 0 -> "Errore nel caricamento di $errors playlist · ${all.size} canali"
                all.isEmpty() -> "La playlist è vuota."
                else -> "${all.size} canali"
            }
        }
    }

    // order = ordine delle categorie deciso dall'admin (se null: ordine automatico)
    private fun setChannels(list: List<Channel>, order: List<String>? = null) {
        allChannels = list
        val counts = allChannels.groupingBy { it.category }.eachCount()
        categories = order ?: Categorizer.sortCategories(counts.keys)
        selectedCategory = null
        rebuildChips(counts)
        // svuota la ricerca (il TextWatcher richiama applyFilter)
        query = ""
        if (searchField.editText!!.text.isNotEmpty()) searchField.editText!!.setText("")
        else applyFilter()
    }

    private fun rebuildChips(counts: Map<String, Int>) {
        chipGroup.removeAllViews()
        fun add(label: String, cat: String?) {
            chipGroup.addView(Chip(this).apply {
                ring(10)
                id = View.generateViewId()
                text = label
                isCheckable = true
                isChecked = cat == selectedCategory
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedCategory = cat
                        applyFilter()
                    }
                }
            })
        }
        add("Tutti (${allChannels.size})", null)
        categories.forEach { add("$it (${counts[it]})", it) }
    }

    // Intestazioni di categoria + canali, nell'ordine indicato
    private fun rowsOf(list: List<Channel>, order: List<String>): List<ChannelAdapter.Row> {
        val rows = mutableListOf<ChannelAdapter.Row>()
        list.groupBy { it.category }
            .toSortedMap(compareBy<String> { order.indexOf(it) })
            .forEach { (cat, l) ->
                rows.add(ChannelAdapter.Row.Header(cat, l.size))
                l.forEach { rows.add(ChannelAdapter.Row.Item(it)) }
            }
        return rows
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = allChannels.filter {
            (selectedCategory == null || it.category == selectedCategory) &&
                (q.isEmpty() || it.name.lowercase().contains(q))
        }
        channelAdapter.submit(rowsOf(filtered, categories))
    }

    // Mostra nell'interfaccia il canale scelto (titolo, logo, evidenziazione), senza toccare il player
    private fun showNowPlaying(ch: Channel) {
        nowTitle.text = ch.name
        nowSub.text = "Diretta · ${ch.category}" + if (ch.quality.isNotEmpty()) " · ${ch.quality}" else ""
        titleView.text = ch.name
        setError("")
        nowLogo.visibility = View.VISIBLE
        loadChannelLogo(nowLogo, ch)
        channelAdapter.select(ch.url)
        liveSelect(ch.url)
    }

    private fun playChannel(ch: Channel) {
        currentChannel = ch
        showNowPlaying(ch)
        spinner.visibility = View.VISIBLE
        showControls()
        currentUrl = ch.url
        triedHls = ch.url.contains(".m3u8", ignoreCase = true)
        firstPlayError = null
        start(ch.url, triedHls)
    }

    // ---------- Schermata admin: playlist + sezioni, categorie e canali ----------

    private fun scroll(v: View): View = ScrollView(this).apply { scrollbar(); addView(v) }

    // Contenitore con i margini dei dialog
    private fun padBox(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(8), dp(24), 0)
        views.forEach { addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
    }

    // Dialog che si ridimensiona con la tastiera (serve in orizzontale)
    private fun showDialog(b: MaterialAlertDialogBuilder) = b.create().also {
        it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        it.show()
    }

    // Barra di scorrimento a destra, sempre visibile (anche con il telecomando), per ogni lista che scorre
    private fun <T : View> T.scrollbar(): T {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        scrollBarSize = dp(6)
        if (Build.VERSION.SDK_INT >= 29) {
            val on = c(MR.attr.colorOnSurface) and 0xFFFFFF
            verticalScrollbarThumbDrawable = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(on or 0x99000000.toInt())
            }
            verticalScrollbarTrackDrawable = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(on or 0x1F000000)
            }
        }
        return this
    }

    // Nella sezione live: quando il focus arriva su un controllo del multi-schermo la lista risale in cima,
    // così si vede anche il titolo "Multi-schermo"
    private fun <T : View> T.scrollTopOnFocus(): T {
        val prev = onFocusChangeListener
        onFocusChangeListener = View.OnFocusChangeListener { v, has ->
            prev?.onFocusChange(v, has)
            if (has) liveScroll.post { liveScroll.smoothScrollTo(0, 0) }
        }
        return this
    }

    // Evidenziazione ben visibile (anello + sfondo colorato) per pulsanti, chip e righe quando hanno il focus
    private fun <T : View> T.ring(radiusDp: Int, color: Int = c(MR.attr.colorPrimary)): T {
        val d = focusRingDrawable(color, dp(3), false, dp(radiusDp).toFloat(), fill = true)
        // i pulsanti Material hanno margini interni (inset) sopra/sotto: l'anello li segue, così combacia col pulsante
        foreground = if (this is MaterialButton) InsetDrawable(d, 0, insetTop, 0, insetBottom) else d
        // i chip hanno un'area di tocco più grande del chip: la tolgo, così l'anello è grande quanto il chip
        if (this is Chip) setEnsureMinTouchTargetSize(false)
        return this
    }

    private fun outlinedBtn(label: String, onClick: () -> Unit) =
        MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = label
            setOnClickListener { onClick() }
            ring(24)
        }

    private fun buildAdmin() {
        adminToolbar = MaterialToolbar(this).apply {
            title = "Impostazioni"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(c(MR.attr.colorOnSurface))
            setTitleTextColor(c(MR.attr.colorOnSurface))
            setNavigationOnClickListener { adminBack() }
        }
        adminTabs = TabLayout(this).apply {
            tabGravity = TabLayout.GRAVITY_FILL
            addTab(newTab().setText("Generali"))
            addTab(newTab().setText("Admin"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    adminTab = tab?.position ?: 0
                    refreshAdmin()
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            })
        }
        adminHeader = LinearLayout(this)
        adminList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(96))
        }
        adminScroll = ScrollView(this).apply { scrollbar(); addView(adminList) }
        fab = ExtendedFloatingActionButton(this).apply {
            text = "Nuova sezione"
            setIconResource(R.drawable.ic_add)
            setOnClickListener {
                when {
                    adminTab != 1 -> {}
                    adminEditing == null -> sectionDialog(null)
                    else -> categoryDialog(null)
                }
            }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(adminHeader, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(adminScroll, lp(0, 1f))
        }
        adminView = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(16), dp(16))
            })
        }
        applyAdminLayout()
    }

    // Barra del titolo e schede una sopra l'altra, uguale in verticale e in orizzontale.
    private fun applyAdminLayout() {
        adminHeader.removeAllViews()
        adminHeader.orientation = LinearLayout.VERTICAL
        adminHeader.addView(adminToolbar, LinearLayout.LayoutParams(MATCH, WRAP))
        adminHeader.addView(adminTabs, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun showAdmin(resetEditing: Boolean = true) {
        applyAdminLayout()
        if (resetEditing) adminEditing = null
        adminTabs.getTabAt(adminTab)?.select()
        refreshAdmin()
        show(adminView)
    }

    private fun refreshAdmin() {
        adminList.removeAllViews()
        // Il pulsante flottante serve solo dentro una sezione (nuova categoria)
        fab.visibility = if (adminTab == 1 && adminEditing != null) View.VISIBLE else View.GONE
        if (adminTab == 0) {
            refreshGeneral()
        } else if (adminEditing == null) {
            refreshSections()
            refreshPlaylists()
        } else {
            fab.text = "Nuova categoria"
            refreshFeatured()
        }
        linkRows(adminList)
    }

    private fun hasFocusable(v: View): Boolean =
        v.isFocusable || (v is ViewGroup && (0 until v.childCount).any { hasFocusable(v.getChildAt(it)) })

    /**
     * Telecomando: collega i punti di focus delle righe dell'admin.
     * Destra/sinistra passano da card a matita a cestino; su/giù restano sulla stessa colonna
     * (cestino → cestino, matita → matita), senza "giri" per arrivare ai pulsanti.
     */
    private fun linkRows(container: ViewGroup) {
        fun idOf(v: View): Int {
            if (v.id == View.NO_ID) v.id = View.generateViewId()
            return v.id
        }
        var prev: List<View>? = null
        for (k in 0 until container.childCount) {
            val child = container.getChildAt(k)
            val items = (child.tag as? FocusRow)?.items?.takeIf { it.isNotEmpty() }
            if (items == null) {
                // un pulsante "libero" in mezzo (es. Aggiungi playlist) interrompe il collegamento: resta raggiungibile
                if (hasFocusable(child)) prev = null
                continue
            }
            items.forEachIndexed { n, v ->
                if (n > 0) v.nextFocusLeftId = idOf(items[n - 1])
                if (n < items.size - 1) v.nextFocusRightId = idOf(items[n + 1])
            }
            prev?.let { a ->
                a.forEachIndexed { n, v ->
                    val fromEnd = a.size - 1 - n
                    v.nextFocusDownId = idOf(items[maxOf(items.size - 1 - fromEnd, 0)])
                }
                items.forEachIndexed { n, v ->
                    val fromEnd = items.size - 1 - n
                    v.nextFocusUpId = idOf(a[maxOf(a.size - 1 - fromEnd, 0)])
                }
            }
            prev = items
        }
    }

    // Torna indietro: dalla gestione di una sezione all'elenco, altrimenti alla home
    private fun adminBack() {
        if (adminTab == 1 && adminEditing != null) {
            adminEditing = null
            refreshAdmin()
        } else goHome()
    }

    // --- Tab Admin: elenco delle sezioni della home ---

    private fun refreshSections() {
        adminList.addView(tv("Sezioni della home", 20f, c(MR.attr.colorOnSurface), true).apply {
            setPadding(dp(4), dp(4), 0, dp(4))
        })
        adminList.addView(
            tv(
                "Tocca una sezione per gestire categorie e canali. Con la matita cambi nome, icona, descrizione e tipo; tenendo premuto la sposti su o giù.",
                13f, c(MR.attr.colorOnSurfaceVariant)
            ).apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        )
        adminList.addView(addButton("Nuova sezione") { sectionDialog(null) }, LinearLayout.LayoutParams(WRAP, WRAP))
        val sections = Sections.load(prefs)
        if (sections.isEmpty()) {
            adminList.addView(
                tv("Nessuna sezione. Creane una con \"Nuova sezione\".", 14f, c(MR.attr.colorOnSurfaceVariant))
                    .apply { setPadding(dp(4), dp(16), dp(4), 0) }
            )
        }
        sections.forEachIndexed { i, sec ->
            val n = Featured.load(prefs, sec.id).sumOf { it.channels.size }
            val del = {
                confirmDelete("La sezione \"${sec.name}\" e tutti i suoi canali") { deleteSection(sec.id) }
            }
            val unit = if (sec.isLive) "eventi" else "canali"
            val sub = if (sec.description.isBlank()) "$n $unit" else "${sec.description} · $n $unit"
            val card = itemCard(
                title = sec.name, sub = sub, selected = false,
                onClick = { adminEditing = sec.id; refreshAdmin() },
                onEdit = { sectionDialog(i) },
                onDelete = del
            )
            card.setOnLongClickListener {
                showMoveMenu(card, i, sections.size) { d -> moveSection(i, d) }
                true
            }
            adminList.addView(card)
        }
    }

    // Menu del tocco prolungato: sposta l'elemento di una posizione su o giù (total = quanti elementi ci sono)
    private fun showMoveMenu(anchor: View, i: Int, total: Int, move: (Int) -> Unit) {
        if (total < 2) return
        val m = PopupMenu(this, anchor)
        if (i > 0) m.menu.add(0, 1, 0, "Sposta su")
        if (i < total - 1) m.menu.add(0, 2, 1, "Sposta giù")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> move(-1)
                2 -> move(1)
            }
            true
        }
        m.show()
    }

    // L'ordine delle sezioni vale anche nella home
    private fun moveSection(i: Int, delta: Int) {
        val l = Sections.load(prefs)
        val j = i + delta
        if (i in l.indices && j in l.indices) {
            val x = l.removeAt(i)
            l.add(j, x)
            Sections.save(prefs, l)
        }
        refreshAdmin()
    }

    private fun deleteSection(id: String) {
        val l = Sections.load(prefs)
        l.removeAll { it.id == id }
        Sections.save(prefs, l)
        Featured.delete(prefs, id)
        refreshAdmin()
    }

    // Nome + icona. index == null: nuova sezione; altrimenti modifica
    private fun sectionDialog(index: Int?) {
        val list = Sections.load(prefs)
        val cur = index?.let { list.getOrNull(it) }
        val til = field("Nome sezione (es. Sport)")
        if (cur != null) til.editText!!.setText(cur.name)
        val tilDesc = field("Descrizione (facoltativa)")
        if (cur != null) tilDesc.editText!!.setText(cur.description)
        var icon = cur?.icon ?: Sections.ICONS.first().first
        val label = tv("Icona", 13f, c(MR.attr.colorOnSurfaceVariant)).apply { setPadding(0, dp(16), 0, dp(8)) }
        val picker = iconPicker(icon) { icon = it }
        // Il tipo si può cambiare anche dopo: categorie e canali restano gli stessi, cambia solo come si vedono
        val typeLabel = tv("Tipo di sezione", 13f, c(MR.attr.colorOnSurfaceVariant)).apply { setPadding(0, dp(16), 0, dp(4)) }
        val radioChannels = MaterialRadioButton(this).apply {
            id = View.generateViewId(); text = "Lista di canali"; isChecked = cur?.isLive != true
        }
        val radioLive = MaterialRadioButton(this).apply {
            id = View.generateViewId(); text = "Eventi live"; isChecked = cur?.isLive == true
        }
        val typeGroup = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(radioChannels)
            addView(radioLive)
        }
        val body = mutableListOf<View>(til, tilDesc)
        body.add(typeLabel)
        body.add(typeGroup)
        if (cur != null) body.add(
            tv("Cambiando tipo restano le stesse categorie e gli stessi elementi.", 12f, c(MR.attr.colorOnSurfaceVariant))
        )
        body.add(label)
        body.add(picker)
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(if (cur == null) "Nuova sezione" else "Modifica sezione")
                .setView(scroll(padBox(*body.toTypedArray())))
                .setPositiveButton(if (cur == null) "Aggiungi" else "Salva") { _, _ ->
                    val n = til.editText!!.text.toString().trim()
                    val desc = tilDesc.editText!!.text.toString().trim()
                    if (n.isEmpty()) toast("Scrivi un nome")
                    else if (list.any { it.id != cur?.id && it.name.equals(n, ignoreCase = true) })
                        toast("Esiste già una sezione con questo nome")
                    else {
                        if (cur == null) list.add(
                            Section(Sections.newId(), n, icon, desc, if (radioLive.isChecked) Section.LIVE else Section.CHANNELS)
                        )
                        else list[index!!] = cur.copy(
                            name = n, icon = icon, description = desc,
                            type = if (radioLive.isChecked) Section.LIVE else Section.CHANNELS
                        )
                        Sections.save(prefs, list)
                        refreshAdmin()
                    }
                }
                .setNegativeButton("Annulla", null)
        )
    }

    // Griglia di icone: quella scelta è evidenziata
    private fun iconPicker(selected: String, onPick: (String) -> Unit): View {
        val grid = android.widget.GridLayout(this).apply { columnCount = 5 }
        val cells = mutableListOf<Pair<String, ImageView>>()
        fun paint(sel: String) {
            cells.forEach { (key, v) ->
                val on = key == sel
                v.imageTintList = ColorStateList.valueOf(
                    c(if (on) MR.attr.colorOnPrimaryContainer else MR.attr.colorOnSurfaceVariant)
                )
                v.background = if (on) GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c(MR.attr.colorPrimaryContainer))
                } else selectable()
            }
        }
        Sections.ICONS.forEach { (key, res) ->
            val v = ImageView(this).apply {
                setImageResource(res)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setOnClickListener {
                    onPick(key)
                    paint(key)
                }
            }
            cells.add(key to v)
            grid.addView(v, android.widget.GridLayout.LayoutParams().apply {
                width = dp(52)
                height = dp(52)
            })
        }
        paint(selected)
        return grid
    }

    // --- Tab Generali: aspetto dell'app ---

    private val themeKeys = listOf("system", "light", "dark")
    private val themeLabels = arrayOf("Come il sistema", "Chiaro", "Scuro")

    private fun refreshGeneral() {
        val i = themeKeys.indexOf(prefs.getString("theme", "system")).coerceAtLeast(0)
        adminList.addView(settingRow(R.drawable.ic_theme, "Aspetto", themeLabels[i]) { themeDialog() })
    }

    // Riga di impostazione senza riquadro: icona + titolo + valore attuale
    private fun settingRow(icon: Int, title: String, value: String, onClick: () -> Unit): View {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 16f, c(MR.attr.colorOnSurface), true))
            addView(tv(value, 13f, c(MR.attr.colorOnSurfaceVariant)))
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(14))
            background = selectableBounded()
            ring(12)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(c(MR.attr.colorPrimary))
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(16) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    // Menu con le tre modalità: scegliendone una si applica e si chiude
    private fun themeDialog() {
        val cur = themeKeys.indexOf(prefs.getString("theme", "system")).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Aspetto")
            .setSingleChoiceItems(themeLabels, cur) { d, which ->
                d.dismiss()
                setTheme(themeKeys[which])
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // Risposta immediata: salvo, aggiorno subito la riga e applico il tema al frame successivo,
    // così il menu si chiude senza scatti.
    private fun setTheme(key: String) {
        if (key == prefs.getString("theme", "system")) return
        prefs.edit().putString("theme", key).apply()
        refreshAdmin()
        ui.post {
            AppCompatDelegate.setDefaultNightMode(themeMode())
            // di solito AppCompat chiama già onConfigurationChanged; se non l'ha fatto lo applico io
            if (nightNow() != nightApplied) applyThemeInPlace()
            // sicurezza: se il sistema non ha applicato la modalità attesa, ricreo l'activity
            val expected = when (themeMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> Configuration.UI_MODE_NIGHT_YES
                AppCompatDelegate.MODE_NIGHT_NO -> Configuration.UI_MODE_NIGHT_NO
                else -> android.content.res.Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            }
            if (nightNow() != expected) recreate()
        }
    }

    // Pulsante con bordo e icona + (stesso stile per "Nuova sezione" e "Aggiungi playlist")
    private fun addButton(label: String, onClick: () -> Unit) = outlinedBtn(label, onClick).apply {
        icon = ContextCompat.getDrawable(context, R.drawable.ic_add)
        iconTint = ColorStateList.valueOf(c(MR.attr.colorPrimary))
        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    }

    // --- Playlist (nella scheda Admin) ---

    private fun refreshPlaylists() {
        adminList.addView(tv("Playlist", 20f, c(MR.attr.colorOnSurface), true).apply {
            setPadding(dp(4), dp(28), 0, dp(4))
        })
        adminList.addView(
            tv("Gli indirizzi M3U da cui l'app legge i canali.", 13f, c(MR.attr.colorOnSurfaceVariant))
                .apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        )
        adminList.addView(addButton("Aggiungi playlist") { addPlaylistDialog() }, LinearLayout.LayoutParams(WRAP, WRAP))
        val l = loadPlaylists()
        if (l.isEmpty()) adminList.addView(
            tv("Nessuna playlist.", 14f, c(MR.attr.colorOnSurfaceVariant)).apply { setPadding(dp(4), dp(12), 0, 0) }
        )
        l.forEachIndexed { i, u ->
            adminList.addView(itemCard(u, null, false, null) {
                confirmDelete(u) {
                    val x = loadPlaylists()
                    x.removeAt(i)
                    savePlaylists(x)
                    refreshAdmin()
                }
            })
        }
    }

    private fun addPlaylistDialog() {
        val til = field("URL playlist M3U")
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle("Aggiungi playlist")
                .setView(scroll(padBox(til)))
                .setPositiveButton("Aggiungi") { _, _ ->
                    val u = til.editText!!.text.toString().trim()
                    if (u.startsWith("http")) {
                        val l = loadPlaylists()
                        if (!l.contains(u)) l.add(u)
                        savePlaylists(l)
                        refreshAdmin()
                    } else toast("Inserisci un URL che inizia con http")
                }
                .setNegativeButton("Annulla", null)
        )
    }

    // --- Tab Admin: categorie e canali di una sezione ---

    // Nelle sezioni live i "canali" sono eventi: stessa gestione, solo i testi cambiano
    private val adminNoun: String
        get() = if (Sections.load(prefs).firstOrNull { it.id == adminSection }?.isLive == true) "evento" else "canale"

    private val adminLive: Boolean
        get() = Sections.load(prefs).firstOrNull { it.id == adminSection }?.isLive == true

    // Campo "Orario" (solo negli eventi): vuoto = l'orario si prende da TheSportsDB
    private fun timeField(initial: String): TextInputLayout = field("Orario (es. 20:45)").apply {
        helperText = "Se vuoto, l'orario viene preso da TheSportsDB"
        endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
        editText!!.apply {
            inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_TIME
            setText(initial)
        }
    }

    // "" = vuoto, null = scritto male, altrimenti "HH:mm"
    private fun readTime(til: TextInputLayout): String? {
        val t = til.editText!!.text.toString().trim().replace('.', ':')
        if (t.isEmpty()) return ""
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(t) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        return if (h > 23 || mi > 59) null else "%02d:%02d".format(h, mi)
    }

    private fun refreshFeatured() {
        val cats = Featured.load(prefs, adminSection)
        val secName = Sections.load(prefs).firstOrNull { it.id == adminSection }?.name ?: ""
        adminList.addView(
            outlinedBtn("Sezioni") {
                adminEditing = null
                refreshAdmin()
            }.apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_chevron_left)
                iconSize = dp(32)
                iconTint = ColorStateList.valueOf(c(MR.attr.colorPrimary))
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )
        adminList.addView(tv(secName, 20f, c(MR.attr.colorOnSurface), true).apply {
            setPadding(dp(4), dp(12), 0, dp(4))
        })
        adminList.addView(
            tv("Tocca per modificare, tieni premuto per spostare su o giù.", 13f, c(MR.attr.colorOnSurfaceVariant))
                .apply { setPadding(dp(4), 0, dp(4), dp(8)) }
        )
        adminList.addView(LinearLayout(this).apply {
            addView(
                outlinedBtn("Importa codice") { importDialog() },
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(8) }
            )
            addView(
                outlinedBtn("Esporta tutto") {
                    if (cats.isEmpty()) toast("Niente da esportare") else exportDialog(cats, "Esporta tutto")
                },
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
        })
        if (cats.isEmpty()) {
            adminList.addView(
                tv(
                    "Nessuna categoria. Creane una (es. \"Canali TV\") e poi aggiungi i canali scegliendoli dalla playlist, oppure importa un codice.",
                    14f, c(MR.attr.colorOnSurfaceVariant)
                ).apply { setPadding(dp(4), dp(16), dp(4), 0) }
            )
            return
        }
        val headers = mutableListOf<View>()
        cats.forEachIndexed { i, cat ->
            val h = categoryHeader(i, cat, cats.size)
            headers.add(h)
            adminList.addView(h)
            if (isCollapsed(cat)) return@forEachIndexed
            if (cat.channels.isEmpty()) {
                adminList.addView(
                    tv("Nessun ${adminNoun}: tocca + per aggiungerne.", 13f, c(MR.attr.colorOnSurfaceVariant))
                        .apply { setPadding(dp(16), dp(4), 0, 0) }
                )
            }
            cat.channels.forEachIndexed { j, ch ->
                val card = itemCard(
                    title = ch.name, sub = ch.rawName.takeIf { it != ch.name }, selected = false,
                    onClick = { editChannelDialog(i, j) },
                    onEdit = { editChannelDialog(i, j) },
                    onDelete = {
                        confirmDelete(ch.name) {
                            Featured.edit(prefs, adminSection) { l ->
                                l.getOrNull(i)?.let { cat0 ->
                                    l[i] = cat0.copy(channels = cat0.channels.filterIndexed { k, _ -> k != j })
                                }
                            }
                            refreshAdmin()
                        }
                    }
                )
                card.setOnLongClickListener {
                    showMoveMenu(card, j, cat.channels.size) { d -> moveChannel(i, j, d) }
                    true
                }
                adminList.addView(card)
            }
        }
        // dopo uno spostamento, porta in vista la categoria spostata
        val target = scrollToCat
        scrollToCat = -1
        if (target in headers.indices) {
            val v = headers[target]
            adminScroll.post { adminScroll.smoothScrollTo(0, (v.top - dp(8)).coerceAtLeast(0)) }
        }
    }

    // Sposta un canale/evento su o giù dentro la sua categoria (l'ordine vale anche nella schermata della sezione)
    private fun moveChannel(ci: Int, ji: Int, delta: Int) {
        Featured.edit(prefs, adminSection) { l ->
            val cat = l.getOrNull(ci) ?: return@edit
            val k = ji + delta
            if (ji in cat.channels.indices && k in cat.channels.indices) {
                val chs = cat.channels.toMutableList()
                val x = chs.removeAt(ji)
                chs.add(k, x)
                l[ci] = cat.copy(channels = chs)
            }
        }
        refreshAdmin()
    }

    private fun collapseKey(cat: FeaturedCategory) = "$adminSection/${cat.name}"
    private fun isCollapsed(cat: FeaturedCategory) = collapseKey(cat) in collapsedCats

    private fun categoryHeader(i: Int, cat: FeaturedCategory, total: Int): View {
        val tint = c(MR.attr.colorOnSurfaceVariant)
        val more = iconBtn(R.drawable.ic_more, tint, dp(10), null) {}
        more.setOnClickListener { showCategoryMenu(more, i, cat, total) }
        val collapse = iconBtn(
            if (isCollapsed(cat)) R.drawable.ic_expand_more else R.drawable.ic_expand_less,
            tint, dp(10), null
        ) {
            if (!collapsedCats.remove(collapseKey(cat))) collapsedCats.add(collapseKey(cat))
            refreshAdmin()
        }
        val add = iconBtn(R.drawable.ic_add, tint, dp(10), null) { addChannelDialog(i) }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(20), 0, 0)
            addView(
                tv("${cat.name} · ${cat.channels.size}", 16f, c(MR.attr.colorPrimary), true),
                LinearLayout.LayoutParams(0, WRAP, 1f)
            )
            addView(collapse, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(add, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(more, LinearLayout.LayoutParams(dp(44), dp(44)))
            tag = FocusRow(listOf(collapse, add, more))
        }
    }

    private fun showCategoryMenu(anchor: View, i: Int, cat: FeaturedCategory, total: Int) {
        val m = PopupMenu(this, anchor)
        if (i > 0) m.menu.add(0, 1, 0, "Sposta su")
        if (i < total - 1) m.menu.add(0, 2, 1, "Sposta giù")
        m.menu.add(0, 3, 2, "Rinomina")
        m.menu.add(0, 4, 3, "Esporta codice")
        m.menu.add(0, 5, 4, "Elimina")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> moveCategory(i, -1)
                2 -> moveCategory(i, 1)
                3 -> categoryDialog(i)
                4 -> exportDialog(listOf(cat), "Esporta \"${cat.name}\"")
                5 -> confirmDelete("La categoria \"${cat.name}\" e i suoi ${cat.channels.size} canali") {
                    Featured.edit(prefs, adminSection) { l -> if (i < l.size) l.removeAt(i) }
                    refreshAdmin()
                }
            }
            true
        }
        m.show()
    }

    // Sposta la categoria di una posizione (l'ordine vale anche nella schermata della sezione)
    private fun moveCategory(i: Int, delta: Int) {
        val j = i + delta
        Featured.edit(prefs, adminSection) { l ->
            if (i in l.indices && j in l.indices) {
                val x = l.removeAt(i)
                l.add(j, x)
            }
        }
        scrollToCat = j
        refreshAdmin()
    }

    // index == null: nuova categoria; altrimenti rinomina
    private fun categoryDialog(index: Int?) {
        val til = field("Nome categoria (es. Canali TV)")
        if (index != null) Featured.load(prefs, adminSection).getOrNull(index)?.let { til.editText!!.setText(it.name) }
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(if (index == null) "Nuova categoria" else "Rinomina categoria")
                .setView(scroll(padBox(til)))
                .setPositiveButton(if (index == null) "Crea" else "Salva") { _, _ ->
                    val n = til.editText!!.text.toString().trim()
                    val l = Featured.load(prefs, adminSection)
                    when {
                        n.isEmpty() -> toast("Scrivi un nome")
                        l.withIndex().any { (k, x) -> k != index && x.name.equals(n, ignoreCase = true) } ->
                            toast("Esiste già una categoria con questo nome")
                        else -> {
                            if (index == null) l.add(FeaturedCategory(n, emptyList()))
                            else if (index < l.size) l[index] = l[index].copy(name = n)
                            Featured.save(prefs, l, adminSection)
                        }
                    }
                    refreshAdmin()
                }
                .setNegativeButton("Annulla", null)
        )
    }

    // Ricerca + elenco dei canali della playlist: lo usano sia "Aggiungi" sia "Modifica"
    private class PickerViews(
        val search: TextInputLayout, val status: TextView,
        val recycler: RecyclerView, val adapter: ChannelAdapter
    )

    private fun playlistPicker(onPick: (Channel) -> Unit): PickerViews {
        val search = field("Cerca nella playlist")
        val status = tv("Caricamento canali…", 13f, c(MR.attr.colorOnSurfaceVariant))
            .apply { setPadding(dp(4), dp(8), dp(4), dp(4)) }
        var list = listOf<Channel>()
        var order = listOf<String>()
        lateinit var pickAdapter: ChannelAdapter
        pickAdapter = ChannelAdapter(this) { ch ->
            pickAdapter.select(ch.url)
            onPick(ch)
        }
        fun refilter() {
            val q = search.editText!!.text.toString().trim().lowercase()
            pickAdapter.submit(rowsOf(list.filter { q.isEmpty() || it.name.lowercase().contains(q) }, order))
        }
        search.editText!!.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refilter()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val recycler = RecyclerView(this).apply {
            scrollbar()
            layoutManager = LinearLayoutManager(context)
            this.adapter = pickAdapter
        }
        loadPlaylistChannels { all, errors, final ->
            list = all
            order = Categorizer.sortCategories(list.map { it.category }.toSet())
            status.text = when {
                !final -> "${list.size} canali · aggiornamento…"
                list.isEmpty() && errors == 0 -> "Nessun canale: aggiungi prima una playlist."
                errors > 0 -> "Errore nel caricamento di $errors playlist · ${list.size} canali"
                else -> "${list.size} canali · tocca per scegliere"
            }
            refilter()
        }
        return PickerViews(search, status, recycler, pickAdapter)
    }

    // Modifica di un canale/evento già aggiunto: nome (già scritto, modificabile o cancellabile),
    // categoria e, se vuoi, un altro canale della playlist al posto di quello attuale
    private fun editChannelDialog(ci: Int, ji: Int) {
        val cats = Featured.load(prefs, adminSection)
        val ch = cats.getOrNull(ci)?.channels?.getOrNull(ji) ?: return
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        val til = field("Nome $adminNoun")
        til.endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT   // tasto per cancellare tutto (anche col telecomando)
        til.editText!!.apply {
            setText(ch.name)
            setSelectAllOnFocus(true)   // si può scrivere subito per sostituirlo, o cancellarlo con un tasto
        }
        til.placeholderText = ch.name   // se lo lasci vuoto, resta questo nome
        val timeTil = if (adminLive) timeField(ch.time) else null

        var picked: Channel? = null
        val pk = playlistPicker { p ->
            picked = p
            til.placeholderText = p.name   // se il nome è vuoto, prende il nome del canale scelto
        }
        pk.adapter.select(ch.url)          // il canale attuale risulta già selezionato
        pk.search.hint = "Cambia canale: cerca nella playlist"

        val catLabel = tv("Categoria", 13f, c(MR.attr.colorOnSurfaceVariant)).apply { setPadding(0, dp(16), 0, dp(4)) }
        var pickedCat = ci
        val group = android.widget.RadioGroup(this).apply { orientation = LinearLayout.VERTICAL }
        cats.forEachIndexed { k, cat ->
            group.addView(MaterialRadioButton(this).apply {
                id = View.generateViewId()
                text = cat.name
                isChecked = k == ci
                setOnClickListener { pickedCat = k }
            })
        }

        val listH = (resources.displayMetrics.heightPixels * (if (land) 0.45f else 0.4f)).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(til, LinearLayout.LayoutParams(MATCH, WRAP))
            timeTil?.let { addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }) }
            addView(catLabel)
            addView(group)
            addView(pk.search, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            addView(pk.status)
            addView(pk.recycler, LinearLayout.LayoutParams(MATCH, listH))
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Modifica $adminNoun")
            .setView(scroll(box))
            .setPositiveButton("Salva", null)
            .setNegativeButton("Annulla", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val p = picked
                val n = til.editText!!.text.toString().trim().ifEmpty { p?.name ?: ch.name }
                val tm = if (timeTil != null) readTime(timeTil) else ch.time
                if (tm == null) {
                    toast("Orario non valido (es. 20:45)")
                    return@setOnClickListener
                }
                Featured.edit(prefs, adminSection) { l ->
                    val from = l.getOrNull(ci)
                    val dest = l.getOrNull(pickedCat)
                    if (from != null && dest != null && ji < from.channels.size) {
                        val old = from.channels[ji]
                        val logos = ((p?.logos ?: old.logos) + listOfNotNull(Logos.repoUrl(n))).distinct()
                        val upd = old.copy(
                            name = n,
                            rawName = p?.rawName ?: old.rawName,
                            quality = p?.quality ?: old.quality,
                            url = p?.url ?: old.url,
                            category = dest.name,
                            logos = logos,
                            time = tm
                        )
                        if (pickedCat == ci) {
                            val chs = from.channels.toMutableList()
                            chs[ji] = upd
                            l[ci] = from.copy(channels = chs)
                        } else {
                            // spostato in un'altra categoria: va in fondo a quella
                            l[ci] = from.copy(channels = from.channels.filterIndexed { k, _ -> k != ji })
                            l[pickedCat] = dest.copy(channels = dest.channels + upd)
                        }
                    }
                }
                dlg.dismiss()
                refreshAdmin()
            }
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }

    // Nome (facoltativo) + scelta del canale dalla playlist
    private fun addChannelDialog(ci: Int) {
        if (Featured.load(prefs, adminSection).getOrNull(ci) == null) return
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val nameField = field("Nome $adminNoun")   // scelto un canale, il suo nome compare in grigio (segnaposto)
        val timeTil = if (adminLive) timeField("") else null
        var picked: Channel? = null
        val pk = playlistPicker { ch ->
            picked = ch
            nameField.placeholderText = ch.name
        }
        // In orizzontale nome e ricerca stanno affiancati, così resta spazio per la lista
        val fieldsRow = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            if (land) {
                addView(nameField, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) })
                addView(pk.search, LinearLayout.LayoutParams(0, WRAP, 1f))
            } else {
                addView(nameField, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(pk.search, LinearLayout.LayoutParams(MATCH, WRAP))
            }
        }
        val listH = (resources.displayMetrics.heightPixels * (if (land) 0.45f else 0.4f)).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(fieldsRow, LinearLayout.LayoutParams(MATCH, WRAP))
            timeTil?.let { addView(it, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }) }
            addView(pk.status)
            addView(pk.recycler, LinearLayout.LayoutParams(MATCH, listH))
        }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Aggiungi $adminNoun")
            .setView(scroll(box))
            .setPositiveButton("Aggiungi", null)
            .setNegativeButton("Annulla", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val p = picked
                if (p == null) {
                    toast("Scegli un canale dalla playlist")
                } else {
                    val nm = nameField.editText!!.text.toString().trim().ifEmpty { p.name }
                    val tm = if (timeTil != null) readTime(timeTil) else ""
                    if (tm == null) {
                        toast("Orario non valido (es. 20:45)")
                        return@setOnClickListener
                    }
                    Featured.edit(prefs, adminSection) { l ->
                        val cat = l.getOrNull(ci)
                        if (cat != null) {
                            val logos = (p.logos + listOfNotNull(Logos.repoUrl(nm))).distinct()
                            val ch = Channel(nm, p.rawName, p.quality, cat.name, p.url, logos, tm)
                            l[ci] = cat.copy(channels = cat.channels + ch)
                        }
                    }
                    dlg.dismiss()
                    refreshAdmin()
                }
            }
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dlg.show()
    }

    // --- Esporta / importa con un codice ---

    private fun exportDialog(cats: List<FeaturedCategory>, title: String) {
        val code = Featured.exportCode(cats)
        val n = cats.sumOf { it.channels.size }
        val til = field("Codice")
        til.editText!!.apply {
            setSingleLine(false)
            maxLines = 6
            setText(code)
        }
        val info = tv(
            "${cats.size} categorie · $n canali. Copia il codice e incollalo in \"Importa codice\" su un altro dispositivo. Contiene gli indirizzi dei canali: condividilo solo con chi vuoi.",
            13f, c(MR.attr.colorOnSurfaceVariant)
        ).apply { setPadding(0, 0, 0, dp(8)) }
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(scroll(padBox(info, til)))
                .setPositiveButton("Copia") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Codice IPTV", code))
                    toast("Codice copiato")
                }
                .setNeutralButton("Condividi") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, code)
                    }
                    startActivity(Intent.createChooser(send, "Condividi codice"))
                }
                .setNegativeButton("Chiudi", null)
        )
    }

    private fun importDialog() {
        val til = field("Incolla il codice qui")
        til.editText!!.apply {
            setSingleLine(false)
            maxLines = 6
        }
        // se negli appunti c'è già un codice, lo precompila
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clip != null && clip.startsWith(Featured.PREFIX)) til.editText!!.setText(clip)
        } catch (_: Exception) {
        }
        val info = tv(
            "\"Aggiungi\" mantiene le categorie attuali (se una esiste già unisce i canali). \"Sostituisci tutto\" cancella quelle attuali.",
            13f, c(MR.attr.colorOnSurfaceVariant)
        ).apply { setPadding(0, 0, 0, dp(8)) }

        fun doImport(replace: Boolean) {
            val imported = Featured.importCode(til.editText!!.text.toString())
            if (imported == null || imported.isEmpty()) {
                toast("Codice non valido")
                return
            }
            Featured.edit(prefs, adminSection) { l ->
                if (replace) {
                    l.clear()
                    l.addAll(imported)
                } else Featured.merge(l, imported)
            }
            refreshAdmin()
            toast("Importate ${imported.size} categorie")
        }

        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle("Importa codice")
                .setView(scroll(padBox(info, til)))
                .setPositiveButton("Aggiungi") { _, _ -> doImport(false) }
                .setNeutralButton("Sostituisci tutto") { _, _ ->
                    confirmDelete("Tutte le categorie attuali, sostituite con quelle del codice") { doImport(true) }
                }
                .setNegativeButton("Annulla", null)
        )
    }

    // ---------- Dati salvati ----------

    private fun loadPlaylists(): MutableList<String> {
        val a = JSONArray(prefs.getString("playlists", null) ?: "[]")
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun savePlaylists(l: List<String>) {
        prefs.edit().putString("playlists", JSONArray(l).toString()).apply()
    }

    // ---------- Playlist M3U (cache in memoria + su disco) ----------

    private fun cacheFile(url: String) =
        java.io.File(cacheDir, "pl_" + Integer.toHexString(url.hashCode()) + ".m3u")

    // Dalla memoria o dal disco (nessuna rete). Da chiamare fuori dal thread UI.
    private fun cachedPlaylist(url: String): List<Channel>? {
        cache[url]?.let { return it }
        return try {
            val f = cacheFile(url)
            if (f.exists()) M3U.parse(f.readText()).also { cache[url] = it } else null
        } catch (e: Exception) {
            null
        }
    }

    // Scarica e salva. Da chiamare fuori dal thread UI.
    private fun downloadPlaylist(url: String): List<Channel>? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val list = M3U.parse(text)
        cache[url] = list
        fetchedAt[url] = System.currentTimeMillis()
        try {
            cacheFile(url).writeText(text)
        } catch (_: Exception) {
        }
        list
    } catch (e: Exception) {
        null
    }

    override fun onStart() {
        super.onStart()
        multiOverlay?.resumeAll()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
        multiOverlay?.pauseAll()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        multiOverlay?.release()
        multiOverlay = null
        player.release()
        super.onDestroy()
    }
}
