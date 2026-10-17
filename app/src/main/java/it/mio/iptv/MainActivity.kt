package it.mio.iptv

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import com.google.android.material.R as MR

// Contenitore che mantiene il rapporto 16:9 (come il player di YouTube)
private class Ratio(ctx: Context) : FrameLayout(ctx) {
    var ratio = true
    override fun onMeasure(w: Int, h: Int) {
        if (ratio) {
            val width = MeasureSpec.getSize(w)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY)
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
    private val cache = mutableMapOf<String, List<Channel>>()
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var playerBox: Ratio
    private lateinit var controls: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var fsBtn: ImageButton
    private lateinit var spinner: CircularProgressIndicator
    private lateinit var seekFlash: TextView

    private lateinit var homeView: View
    private lateinit var userView: View
    private lateinit var adminView: View
    private lateinit var userPanel: View
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var errorText: TextView
    private lateinit var nowLogo: ImageView
    private lateinit var listStatus: TextView
    private lateinit var chipGroup: ChipGroup
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
    private var fullscreen = false

    // ---------- Avvio ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
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
                    fullscreen -> setFullscreen(false)
                    currentView !== homeView -> goHome()
                    else -> finish()
                }
            }
        })

        setupPlayer()
        buildHome()
        buildUser()
        buildAdmin()
        show(homeView)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Come YouTube: ruotando il telefono si passa a schermo intero
        if (currentView === userView) {
            setFullscreen(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
        }
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
            setOnClickListener { onClick() }
        }

    private fun show(v: View) {
        root.removeAllViews()
        root.addView(v, ViewGroup.LayoutParams(MATCH, MATCH))
        currentView = v
    }

    private fun field(hint: String): TextInputLayout {
        val til = TextInputLayout(this)
        til.hint = hint
        til.addView(TextInputEditText(til.context).apply { setSingleLine() })
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
        onClick: (() -> Unit)?, onDelete: (() -> Unit)?
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
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onDelete != null) addView(
                iconBtn(R.drawable.ic_delete, c(MR.attr.colorOnSurfaceVariant), dp(10), null) { onDelete() },
                LinearLayout.LayoutParams(dp(44), dp(44))
            )
        }
        return MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(
                if (selected) c(MR.attr.colorPrimaryContainer) else c(MR.attr.colorSurfaceVariant)
            )
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
            addView(row)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
    }

    private fun homeCard(icon: Int, title: String, sub: String, onClick: () -> Unit): View {
        val onC = c(MR.attr.colorOnSecondaryContainer)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(title, 20f, onC, true))
            addView(tv(sub, 14f, onC))
        }
        return MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c(MR.attr.colorSecondaryContainer))
            isClickable = true
            isFocusable = true
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

    // ---------- Home ----------

    private fun buildHome() {
        homeView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(tv("IPTV Player", 34f, c(MR.attr.colorOnSurface), true))
            addView(tv("Scegli come entrare", 16f, c(MR.attr.colorOnSurfaceVariant)).apply {
                setPadding(0, dp(4), 0, dp(16))
            })
            addView(homeCard(R.drawable.ic_person, "Utente", "Guarda i canali") { showUser() })
            addView(homeCard(R.drawable.ic_edit, "Admin", "Gestisci le playlist") { showAdmin() })
        }
    }

    private fun goHome() {
        player.stop()
        if (fullscreen) setFullscreen(false)
        currentChannel = null
        show(homeView)
    }

    // ---------- Player in stile YouTube ----------

    private fun setupPlayer() {
        // User-Agent da lettore IPTV: molti server rifiutano quello di default.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.20 LibVLC/3.0.20")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = onError(error)
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                showControls()
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
        val liveDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFF0000.toInt())
            }
        }
        val live = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, 0, dp(14))
            addView(liveDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) })
            addView(tv("DIRETTA", 12f, white, true))
        }
        controls = FrameLayout(this).apply {
            addView(View(context).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(titleView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
            addView(playBtn, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
            addView(live, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START))
            addView(fsBtn, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(8), dp(4))
            })
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
    }

    private val hideRun = Runnable {
        controls.animate().alpha(0f).setDuration(200).withEndAction {
            controls.visibility = View.GONE
        }.start()
    }

    private fun showControls() {
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
        if (!triedHls) {
            triedHls = true
            start(currentUrl, true)
            return
        }
        errorText.text = "Impossibile riprodurre questo canale (${e.errorCodeName})."
    }

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen) return
        fullscreen = on
        userPanel.visibility = if (on) View.GONE else View.VISIBLE
        playerBox.ratio = !on
        playerBox.layoutParams = LinearLayout.LayoutParams(MATCH, if (on) MATCH else WRAP)
        playerBox.requestLayout()
        fsBtn.setImageResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------- Schermata utente: categorie + canali ----------

    private fun logoBox(): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = logoBackground(this@MainActivity, 8)
    }

    private fun buildUser() {
        nowTitle = tv("Scegli un canale", 20f, c(MR.attr.colorOnSurface), true)
        nowSub = tv("", 13f, c(MR.attr.colorOnSurfaceVariant))
        errorText = tv("", 14f, c(MR.attr.colorError))
        nowLogo = logoBox().apply { visibility = View.GONE }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(nowTitle)
            addView(nowSub)
            addView(errorText)
        }
        val info = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
            addView(nowLogo, LinearLayout.LayoutParams(dp(64), dp(44)).apply { rightMargin = dp(12) })
            addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        listStatus = tv("", 13f, c(MR.attr.colorOnSurfaceVariant)).apply {
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
            setPadding(dp(16), 0, dp(16), 0)
        }
        val chipScroll = HorizontalScrollView(this).apply {
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

        channelAdapter = ChannelAdapter(this) { playChannel(it) }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channelAdapter
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), dp(16))
        }

        userPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(listStatus)
            addView(chipScroll, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(searchField, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(recycler, lp(0, 1f))
        }
        userView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(playerBox, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(userPanel, lp(0, 1f))
        }
    }

    private fun showUser() {
        cache.clear()
        currentChannel = null
        nowTitle.text = "Scegli un canale"
        nowSub.text = ""
        titleView.text = ""
        errorText.text = ""
        nowLogo.visibility = View.GONE
        channelAdapter.select(null)
        loadAllChannels()
        show(userView)
    }

    // Scarica tutte le playlist, unisce i canali e crea le categorie da sole
    private fun loadAllChannels() {
        val pls = loadPlaylists()
        if (pls.isEmpty()) {
            setChannels(emptyList())
            listStatus.text = "Nessuna playlist: aggiungila dalla sezione Admin."
            return
        }
        listStatus.text = "Caricamento canali…"
        val results = arrayOfNulls<List<Channel>>(pls.size)
        var errors = 0
        var pending = pls.size
        pls.forEachIndexed { i, url ->
            fetchPlaylist(url) { list, _ ->
                if (list != null) results[i] = list else errors++
                pending--
                if (pending == 0) {
                    val all = results.filterNotNull().flatten()
                    setChannels(all)
                    listStatus.text = when {
                        errors > 0 -> "Errore nel caricamento di $errors playlist · ${all.size} canali"
                        all.isEmpty() -> "La playlist è vuota."
                        else -> "${all.size} canali"
                    }
                }
            }
        }
    }

    private fun setChannels(list: List<Channel>) {
        allChannels = list.sortedWith(Categorizer.channelOrder)
        val counts = allChannels.groupingBy { it.category }.eachCount()
        categories = Categorizer.sortCategories(counts.keys)
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

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val filtered = allChannels.filter {
            (selectedCategory == null || it.category == selectedCategory) &&
                (q.isEmpty() || it.name.lowercase().contains(q))
        }
        val rows = mutableListOf<ChannelAdapter.Row>()
        filtered.groupBy { it.category }
            .toSortedMap(compareBy<String> { categories.indexOf(it) })
            .forEach { (cat, list) ->
                rows.add(ChannelAdapter.Row.Header(cat, list.size))
                list.forEach { rows.add(ChannelAdapter.Row.Item(it)) }
            }
        channelAdapter.submit(rows)
    }

    private fun playChannel(ch: Channel) {
        currentChannel = ch
        nowTitle.text = ch.name
        nowSub.text = "Diretta · ${ch.category}" + if (ch.quality.isNotEmpty()) " · ${ch.quality}" else ""
        titleView.text = ch.name
        errorText.text = ""
        nowLogo.visibility = View.VISIBLE
        loadChannelLogo(nowLogo, ch)
        spinner.visibility = View.VISIBLE
        channelAdapter.select(ch.url)
        showControls()
        currentUrl = ch.url
        triedHls = ch.url.contains(".m3u8", ignoreCase = true)
        start(ch.url, triedHls)
    }

    // ---------- Schermata admin (solo playlist: i canali si creano da soli) ----------

    private fun buildAdmin() {
        val toolbar = MaterialToolbar(this).apply {
            title = "Admin · Playlist"
            setNavigationIcon(R.drawable.ic_back)
            setNavigationIconTint(c(MR.attr.colorOnSurface))
            setTitleTextColor(c(MR.attr.colorOnSurface))
            setNavigationOnClickListener { goHome() }
        }
        adminList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(96))
        }
        fab = ExtendedFloatingActionButton(this).apply {
            text = "Aggiungi playlist"
            setIconResource(R.drawable.ic_add)
            setOnClickListener { addPlaylistDialog() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(toolbar)
            addView(ScrollView(context).apply { addView(adminList) }, lp(0, 1f))
        }
        adminView = FrameLayout(this).apply {
            addView(column, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(fab, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(16), dp(16))
            })
        }
    }

    private fun showAdmin() {
        refreshAdmin()
        show(adminView)
    }

    private fun refreshAdmin() {
        adminList.removeAllViews()
        val l = loadPlaylists()
        if (l.isEmpty()) adminList.addView(tv("Nessuna playlist.", 14f, c(MR.attr.colorOnSurfaceVariant)))
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
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(til, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Aggiungi playlist")
            .setView(box)
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
            .show()
    }

    // ---------- Dati salvati ----------

    private fun loadPlaylists(): MutableList<String> {
        val a = JSONArray(prefs.getString("playlists", null) ?: "[]")
        return MutableList(a.length()) { a.getString(it) }
    }

    private fun savePlaylists(l: List<String>) {
        prefs.edit().putString("playlists", JSONArray(l).toString()).apply()
    }

    // ---------- Playlist M3U ----------

    private fun fetchPlaylist(url: String, cb: (List<Channel>?, String?) -> Unit) {
        cache[url]?.let { cb(it, null); return }
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
                val list = M3U.parse(conn.inputStream.bufferedReader().use { it.readText() })
                runOnUiThread {
                    cache[url] = list
                    cb(list, null)
                }
            } catch (e: Exception) {
                runOnUiThread { cb(null, e.message) }
            }
        }.start()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        player.release()
        super.onDestroy()
    }
}
