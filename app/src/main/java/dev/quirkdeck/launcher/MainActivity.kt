package dev.quirkdeck.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private data class App(
        val id: String, val label: String, val activity: ResolveInfo,
        val icon: Drawable?, val banner: Drawable?
    )

    private class AppRail(val watch: Boolean, val container: HorizontalScrollView, val cards: LinearLayout) {
        var lastApp: String? = null
    }

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val activityTime by lazy { AndroidActivityTime(this) }
    private var usageLoading = false
    private var usageLoaded = false
    private var usage: Map<LocalDate, Map<String, Long>>? = null
    private lateinit var appTitle: TextView
    private val bing by lazy { BingWallpaper(File(filesDir, "bing-wallpaper")) }
    private val wallpaperWorker = Executors.newSingleThreadExecutor()
    private var wallpaperPhoto: BingWallpaper.Photo? = null
    private var wallpaperInFlight = false
    private var wallpaperCheck = -1L
    private lateinit var wallpaper: ImageView
    private lateinit var wallpaperCredit: TextView
    private lateinit var appSettings: TextView
    private lateinit var playStore: TextView
    private lateinit var weatherShortcut: View
    private lateinit var usageShortcut: View
    private lateinit var launchChart: LaunchChart
    private lateinit var weatherSymbol: TextView

    private val appWorker = Executors.newSingleThreadExecutor()
    private val weatherWorker = Executors.newSingleThreadExecutor()
    private var apps = emptyList<App>()
    private var appSignature: List<String>? = null
    private var selectedApp: String? = null
    private var city = Weather.bengaluru
    private var lastWeatherRequest = 0L
    private var conditions: Weather.Conditions? = null
    private lateinit var watchRail: AppRail
    private lateinit var utilitiesRail: AppRail
    private lateinit var utilityHeading: TextView
    private lateinit var position: TextView
    private lateinit var appControls: TextView
    private lateinit var actionButtons: LinearLayout
    private var actionApp: App? = null
    private var movingApp: App? = null
    private var slidePending = false
    private lateinit var weatherValue: TextView
    private lateinit var weatherDetail: TextView
    private lateinit var activityValue: TextView
    private lateinit var activityDetail: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updateWidgets = object : Runnable {
        override fun run() {
            refreshActivityTime()
            refreshWeather()
            refreshWallpaper()
            handler.postDelayed(this, 60_000)
        }
    }

    private val ink = Color.rgb(233, 241, 249)
    private val muted = Color.rgb(166, 184, 204)
    private val surface get() = Color.argb(prefs.getInt("glass", 132), 13, 24, 39)
    private val glassBorder = Color.argb(72, 165, 197, 224)
    private val motion get() = prefs.getBoolean("motion", true)
    private val accent = Color.rgb(156, 213, 230)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedApp = savedInstanceState?.getString("selected")
        city = Weather.City(
            prefs.getString("city", null) ?: Weather.bengaluru.name,
            prefs.getString("latitude", null)?.toDoubleOrNull() ?: Weather.bengaluru.latitude,
            prefs.getString("longitude", null)?.toDoubleOrNull() ?: Weather.bengaluru.longitude
        )
        createHome()
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        updateWidgets.run()
    }

    override fun onPause() {
        finishMove(save = false)
        closeAppActions(restoreFocus = false)
        handler.removeCallbacks(updateWidgets)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedApp)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        appWorker.shutdownNow()
        weatherWorker.shutdownNow()
        wallpaperWorker.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun panel(color: Int, border: Int = color) = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(color)
        setStroke(dp(2), border)
    }

    private fun focusPanel() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), panel(Color.argb(240, 36, 64, 83), accent))
        addState(intArrayOf(), panel(surface, glassBorder))
    }

    private fun label(value: CharSequence, size: Float, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun widget(title: String): LinearLayout = vertical().apply {
        background = panel(surface, glassBorder)
        setPadding(dp(16), dp(5), dp(16), dp(5))
        gravity = Gravity.CENTER_VERTICAL
        addView(label(title, 14f, muted, true))
    }

    private fun createHome() {
        val root = vertical().apply {
            setPadding(dp(34), dp(14), dp(34), dp(24))
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 43, 61), Color.rgb(8, 15, 26))
            )
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(getString(R.string.home_name), 24f, accent, true).apply {
            letterSpacing = .04f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        playStore = control(getString(R.string.play_store)) { openPlayStore() }
        appSettings = control(getString(R.string.settings)) { showSettings() }
        header.addView(playStore, LinearLayout.LayoutParams(dp(121), dp(38)))
        header.addView(appSettings, LinearLayout.LayoutParams(dp(100), dp(38)).apply { marginStart = dp(8) })
        root.addView(header)
        appTitle = label(getString(R.string.watch), 32f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = -.025f
            setShadowLayer(dp(9).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
        }
        root.addView(appTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val widgets = LinearLayout(this)
        val clock = widget(getString(R.string.local_time)).apply {
            addView(TextClock(this@MainActivity).apply clockValue@{
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        animateReading(this@clockValue)
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            })
            addView(TextClock(this@MainActivity).apply {
                format12Hour = "EEE, d MMM"
                format24Hour = format12Hour
                setTextColor(muted)
                textSize = 14f
            })
        }
        weatherValue = label(city.name, 21f, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        weatherDetail = label(getString(R.string.loading_weather), 14f, muted)
        val weatherCard = widget(getString(R.string.weather)).apply {
            id = View.generateViewId()
            background = focusPanel()
            isFocusable = true
            setOnClickListener { chooseCity() }
            weatherSymbol = label("", 24f, accent)
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(weatherSymbol, LinearLayout.LayoutParams(dp(35), -2))
                addView(weatherValue, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(weatherDetail)
        }
        activityValue = label("", 22f, bold = true)
        activityDetail = label("", 14f, muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        launchChart = LaunchChart(this)
        val activity = widget(getString(R.string.active_today)).apply {
            id = View.generateViewId()
            isFocusable = true
            background = focusPanel()
            setOnClickListener { showUsage() }
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(activityValue, LinearLayout.LayoutParams(0, -2, 1f))
                addView(launchChart, LinearLayout.LayoutParams(dp(76), dp(27)))
            })
            addView(activityDetail)
        }
        weatherShortcut = weatherCard
        usageShortcut = activity
        listOf(clock, weatherCard, activity).forEachIndexed { index, card ->
            widgets.addView(card, LinearLayout.LayoutParams(0, dp(82), 1f).apply {
                marginStart = if (index == 0) 0 else dp(12)
            })
        }
        val galleryHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        galleryHeader.addView(label(getString(R.string.watch), 20f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        position = label("", 14f, muted)
        galleryHeader.addView(position)
        root.addView(galleryHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        watchRail = createRail(watch = true)
        root.addView(watchRail.container, LinearLayout.LayoutParams(-1, dp(126)).apply { topMargin = dp(3) })
        appControls = label(getString(R.string.app_controls), 13f, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        actionButtons = LinearLayout(this).apply { visibility = View.GONE }
        val actionStrip = FrameLayout(this).apply {
            addView(appControls, FrameLayout.LayoutParams(-1, -1))
            addView(actionButtons, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(actionStrip, LinearLayout.LayoutParams(-1, dp(31)).apply { topMargin = dp(2) })
        root.addView(widgets, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        utilityHeading = label(getString(R.string.utilities), 19f, bold = true)
        root.addView(utilityHeading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        utilitiesRail = createRail(watch = false)
        root.addView(utilitiesRail.container, LinearLayout.LayoutParams(-1, dp(72)).apply { topMargin = dp(3) })

        wallpaper = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        wallpaperCredit = label("", 11f, ink).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xBD091522.toInt(), 0x97091522.toInt(), 0xF2091522.toInt())
            )
        }
        setContentView(FrameLayout(this).apply {
            background = root.background
            root.background = null
            addView(wallpaper, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
            addView(wallpaperCredit, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(dp(34), 0, dp(34), dp(10))
            })
        })
        showWallpaper()
        showActivity()
    }

    private fun loadApps() {
        val previous = appSignature
        appWorker.execute {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val entries = packageManager.queryIntentActivities(query, 0)
                .filter { it.activityInfo.packageName != packageName }
                .distinctBy { it.activityInfo.packageName }
            val signature = entries.map { "${it.activityInfo.name}:${it.activityInfo.applicationInfo.sourceDir}" }.sorted()
            if (signature == previous) return@execute

            val loaded = entries.map { info ->
                val activity = info.activityInfo
                val banner = activity.loadBanner(packageManager) ?: activity.applicationInfo.loadBanner(packageManager)
                App(activity.packageName, info.loadLabel(packageManager).toString(), info, info.loadIcon(packageManager), banner)
            }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            runOnUiThread {
                if (isDestroyed || signature == appSignature) return@runOnUiThread
                appSignature = signature
                apps = loaded
                showApps()
                refreshActivityTime()
            }
        }
    }

    private fun control(title: String, action: () -> Unit) = label(title, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        id = View.generateViewId()
        isFocusable = true
        background = focusPanel()
        setOnClickListener { action() }
    }

    private fun openPlayStore() {
        val app = apps.firstOrNull { it.id == AppPlacement.PLAY_STORE }
        if (app != null) launch(app)
        else Toast.makeText(this, getString(R.string.cannot_open, getString(R.string.play_store)), Toast.LENGTH_SHORT).show()
    }

    private fun createRail(watch: Boolean): AppRail {
        val cards = LinearLayout(this).apply {
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            setPadding(dp(5), dp(5), dp(5), dp(5))
            clipChildren = false
            clipToPadding = false
        }
        val container = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(cards)
        }
        return AppRail(watch, container, cards)
    }

    private fun showApps() {
        val previousFocus = currentFocus
        val keepFocus = watchRail.cards.childCount + utilitiesRail.cards.childCount == 0 ||
            focusedRail() != null || actionApp != null
        val previousApp = selectedApp
        movingApp = null
        closeAppActions(restoreFocus = false)
        val (watch, utilities) = orderedApps().filterNot { it.id in hiddenIds() }.partition(::isWatch)
        showRail(watchRail, watch)
        showRail(utilitiesRail, utilities)
        utilityHeading.visibility = if (utilities.isEmpty()) View.GONE else View.VISIBLE
        utilitiesRail.container.visibility = utilityHeading.visibility

        val mainCard = lastCard(watchRail)
        val selected = findCard(previousApp) ?: mainCard ?: lastCard(utilitiesRail)
        selectedApp = selected?.tag as? String
        if (mainCard == null) {
            appTitle.setText(R.string.watch)
            position.setText(R.string.no_watch_apps)
        } else {
            updateWatchTitle(mainCard)
        }
        linkRows()
        if (keepFocus) (selected ?: appSettings).requestFocus()
        else if (previousFocus?.isAttachedToWindow == true) previousFocus.requestFocus()
    }

    private fun showRail(row: AppRail, apps: List<App>) {
        row.cards.removeAllViews()
        apps.forEach { app ->
            val card = makeCard(row, app)
            row.cards.addView(card, LinearLayout.LayoutParams(dp(if (row.watch) 185 else 125), -1).apply {
                marginEnd = dp(if (row.watch) 8 else 6)
            })
        }
        val last = lastCard(row)
        row.lastApp = last?.tag as? String
        if (last != null) row.container.post {
            if (last.parent == row.cards && row.lastApp == last.tag) centerCard(row, last)
        }
    }

    private fun makeCard(row: AppRail, app: App): View {
        val face = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF35506A.toInt(), 0xFF182739.toInt())
            ).apply { cornerRadius = dp(13).toFloat() }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val art = ImageView(this).apply {
            setImageDrawable(app.banner ?: app.icon)
            scaleType = if (app.banner == null) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        }
        val size = if (app.banner == null) dp(if (row.watch) 55 else 36) else -1
        face.addView(art, FrameLayout.LayoutParams(size, size, Gravity.CENTER).apply {
            if (app.banner == null) bottomMargin = dp(15)
        })
        val fade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                0x3815283E, 0x06132439, 0xDC102034.toInt()
            ))
        }
        face.addView(fade, FrameLayout.LayoutParams(-1, -1))
        face.addView(label(app.label, if (row.watch) 15f else 12f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
        }, FrameLayout.LayoutParams(-1, dp(if (row.watch) 30 else 23), Gravity.BOTTOM))
        val card = FrameLayout(this).apply {
            id = View.generateViewId()
            tag = app.id
            contentDescription = app.label
            isFocusable = true
            nextFocusUpId = if (row.watch) appSettings.id else weatherShortcut.id
            addView(face, FrameLayout.LayoutParams(-1, -1))
            foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_activated), cardBorder(0xFFCFE9FF.toInt()))
                addState(intArrayOf(android.R.attr.state_focused), cardBorder(accent))
                addState(intArrayOf(), cardBorder(Color.TRANSPARENT))
            }
            setOnClickListener { launch(app) }
            setOnLongClickListener { showAppActions(app); true }
            setOnFocusChangeListener { view, focused ->
                val emphasized = focused || view.isActivated
                setCardFocus(face, emphasized, animate = true)
                fade.alpha = if (emphasized) .90f else 1f
                if (focused) {
                    val changed = row.lastApp != app.id
                    selectCard(row, view)
                    if (changed) row.container.post { if (view.hasFocus()) centerCard(row, view) }
                }
            }
        }
        setCardFocus(face, focused = false, animate = false)
        return card
    }

    private fun cardBorder(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(13).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), color)
    }

    private fun setCardFocus(face: View, focused: Boolean, animate: Boolean) {
        val scale = if (focused) 1f else .92f
        val opacity = if (focused) 1f else .68f
        face.animate().cancel()
        if (motion && animate) {
            face.animate().scaleX(scale).scaleY(scale).alpha(opacity).setDuration(155)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            face.scaleX = scale
            face.scaleY = scale
            face.alpha = opacity
        }
    }

    private fun hiddenIds(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty()

    private fun isWatch(app: App): Boolean = AppPlacement.belongsInWatch(
        app.id, app.activity.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_VIDEO,
        prefs.getStringSet("watch_pins", emptySet()).orEmpty(),
        prefs.getStringSet("utility_pins", emptySet()).orEmpty()
    )

    private fun orderedApps(): List<App> {
        val rank = prefs.getString("order", "").orEmpty().split(',').withIndex().associate { it.value to it.index }
        return apps.filter { it.id != AppPlacement.PLAY_STORE }.sortedWith(
            compareBy<App> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { AppPlacement.defaultRank(it.id) }
        )
    }

    private fun lastCard(row: AppRail): View? = row.cards.findViewWithTag<View>(row.lastApp) ?: row.cards.getChildAt(0)

    private fun findCard(id: String?): View? {
        if (id == null) return null
        return watchRail.cards.findViewWithTag<View>(id) ?: utilitiesRail.cards.findViewWithTag<View>(id)
    }

    private fun rowFor(card: View): AppRail = if (card.parent === watchRail.cards) watchRail else utilitiesRail

    private fun focusedRail(): AppRail? = when (currentFocus?.parent) {
        watchRail.cards -> watchRail
        utilitiesRail.cards -> utilitiesRail
        else -> null
    }

    private fun selectCard(row: AppRail, card: View) {
        row.lastApp = card.tag as String
        selectedApp = row.lastApp
        if (row.watch) updateWatchTitle(card)
        linkRows()
    }

    private fun updateWatchTitle(card: View) {
        appTitle.text = card.contentDescription
        position.text = getString(R.string.app_position, watchRail.cards.indexOfChild(card) + 1, watchRail.cards.childCount)
    }

    private fun linkRows() {
        val watchCard = lastCard(watchRail)
        val utilityCard = lastCard(utilitiesRail)
        val current = findCard(selectedApp) ?: watchCard ?: utilityCard
        appSettings.nextFocusDownId = current?.id ?: weatherShortcut.id
        playStore.nextFocusDownId = appSettings.nextFocusDownId
        weatherShortcut.nextFocusUpId = watchCard?.id ?: appSettings.id
        usageShortcut.nextFocusUpId = weatherShortcut.nextFocusUpId
        weatherShortcut.nextFocusDownId = utilityCard?.id ?: View.NO_ID
        usageShortcut.nextFocusDownId = weatherShortcut.nextFocusDownId
    }

    private fun centerCard(row: AppRail, card: View) {
        val center = card.left + card.width / 2 - row.container.width / 2
        if (motion) row.container.smoothScrollTo(center, 0) else row.container.scrollTo(center, 0)
    }

    private fun showAppActions(app: App) {
        val card = findCard(app.id) ?: return
        actionApp = app
        card.isActivated = true
        actionButtons.removeAllViews()
        val inWatch = rowFor(card).watch
        val actions = listOf<Pair<String, () -> Unit>>(
            getString(R.string.move_app) to { moveApp(app) },
            getString(if (inWatch) R.string.send_to_utilities else R.string.pin_to_watch) to { placeApp(app, !inWatch) },
            getString(R.string.hide_app) to {
                prefs.edit().putStringSet("hidden", hiddenIds() + app.id).apply()
                showApps()
            }
        )
        actions.forEach { (title, action) ->
            actionButtons.addView(control(title) { closeAppActions(restoreFocus = true); action() },
                LinearLayout.LayoutParams(dp(150), -1).apply { marginEnd = dp(8) })
        }
        appControls.visibility = View.INVISIBLE
        actionButtons.visibility = View.VISIBLE
        actionButtons.getChildAt(0).requestFocus()
    }

    private fun closeAppActions(restoreFocus: Boolean) {
        val app = actionApp ?: return
        actionApp = null
        actionButtons.visibility = View.GONE
        appControls.visibility = View.VISIBLE
        (findCard(app.id) as? FrameLayout)?.apply {
            isActivated = false
            if (restoreFocus) requestFocus()
            else setCardFocus(getChildAt(0), hasFocus(), animate = false)
        }
    }

    private fun placeApp(app: App, inWatch: Boolean) {
        val watch = prefs.getStringSet("watch_pins", emptySet()).orEmpty() - app.id
        val utilities = prefs.getStringSet("utility_pins", emptySet()).orEmpty() - app.id
        val automaticWatch = AppPlacement.belongsInWatch(
            app.id, app.activity.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_VIDEO,
            emptySet(), emptySet()
        )
        prefs.edit().putStringSet("watch_pins", if (inWatch && !automaticWatch) watch + app.id else watch)
            .putStringSet("utility_pins", if (!inWatch && automaticWatch) utilities + app.id else utilities)
            .apply()
        showApps()
    }

    private fun moveApp(app: App) {
        val card = findCard(app.id) ?: return
        val row = rowFor(card)
        if (row.cards.childCount < 2) return
        movingApp = app
        card.isActivated = true
        card.translationZ = dp(5).toFloat()
        card.requestFocus()
        appControls.text = getString(R.string.move_controls, app.label)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        val horizontal = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT
        if (actionApp != null) {
            if (horizontal) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val next = actionButtons.indexOfChild(currentFocus) + horizontalStep(actionButtons, key)
                    actionButtons.getChildAt(next)?.requestFocus()
                }
                return true
            }
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE ||
                key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeAppActions(restoreFocus = true)
                return true
            }
        }
        val app = movingApp
        val row = if (::watchRail.isInitialized && ::utilitiesRail.isInitialized) focusedRail() else null
        if (horizontal && row != null) {
            val step = horizontalStep(row.cards, key)
            if (app != null) {
                if (event.action == KeyEvent.ACTION_DOWN) slideApp(row, app, step)
                return true
            }
            val focused = row.cards.indexOfChild(currentFocus)
            if (focused + step !in 0 until row.cards.childCount) return true
        }
        if (app == null) return super.dispatchKeyEvent(event)
        return when (key) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finishMove(save = true)
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finishMove(save = false)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> super.dispatchKeyEvent(event)
        }
    }

    private fun horizontalStep(view: View, key: Int): Int {
        val right = key == KeyEvent.KEYCODE_DPAD_RIGHT
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        return if (right != rtl) 1 else -1
    }

    private fun slideApp(row: AppRail, app: App, offset: Int) {
        val card = findCard(app.id) ?: return
        val cards = row.cards
        val from = cards.indexOfChild(card)
        val target = from + offset
        val neighbor = cards.getChildAt(target) ?: return
        if (!slidePending) {
            slidePending = true
            val oldPositions = (0 until cards.childCount).map(cards::getChildAt).associateWith { it.x }
            cards.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    cards.viewTreeObserver.removeOnPreDrawListener(this)
                    slidePending = false
                    if (movingApp != app) return true
                    if (motion) oldPositions.forEach { (tile, oldX) ->
                        tile.animate().cancel()
                        tile.translationX = oldX - tile.left
                        if (tile.translationX != 0f) tile.animate().translationX(0f).setDuration(155).start()
                    }
                    centerCard(row, card)
                    return true
                }
            })
        }
        // Move the neighboring tile so the picked-up tile keeps focus.
        cards.removeViewAt(target)
        cards.addView(neighbor, from)
        selectCard(row, card)
    }

    private fun finishMove(save: Boolean) {
        val app = movingApp ?: return
        movingApp = null
        val card = findCard(app.id)
        if (!save || card == null) {
            showApps()
            return
        }
        val cards = rowFor(card).cards
        val visible = (0 until cards.childCount).map { cards.getChildAt(it).tag as String }
        val moved = visible.toSet()
        val replacement = visible.iterator()
        val order = orderedApps().map { if (it.id in moved) replacement.next() else it.id }
        prefs.edit().putString("order", order.joinToString(",")).apply()
        card.isActivated = false
        card.translationZ = 0f
        appControls.setText(R.string.app_controls)
    }

    private fun showHiddenApps() {
        val hidden = orderedApps().filter { it.id in hiddenIds() }
        val sheet = HomeSheet(this, getString(R.string.hidden_apps))
        if (hidden.isEmpty()) sheet.message(getString(R.string.no_hidden_apps))
        else sheet.choices(hidden.map { it.label }) { index ->
            val app = hidden[index]
            HomeSheet(this, app.label)
                .choices(listOf(getString(R.string.open_app), getString(R.string.show_on_home))) { choice ->
                    if (choice == 0) launch(app) else {
                        prefs.edit().putStringSet("hidden", hiddenIds() - app.id).apply()
                        showApps()
                    }
                }
        }
    }

    private fun launch(app: App) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(app.id, app.activity.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.cannot_open, app.label), Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, getString(R.string.cannot_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun recentDays(): List<LocalDate> {
        val today = LocalDate.now()
        return (6L downTo 0L).map(today::minusDays)
    }

    private fun appName(id: String) = apps.firstOrNull { it.id == id }?.label ?: id

    private fun refreshActivityTime() {
        if (usageLoading || apps.isEmpty()) return
        usageLoading = true
        val packages = apps.mapTo(mutableSetOf()) { it.id }
        appWorker.execute {
            val result = runCatching { activityTime.week(packages) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                usageLoading = false
                usageLoaded = true
                usage = result
                showActivity()
            }
        }
    }

    private fun showActivity() {
        val data = usage
        if (data == null) {
            activityValue.setText(if (usageLoaded) R.string.usage_unavailable else R.string.usage_loading)
            activityDetail.setText(if (usageLoaded) R.string.usage_settings_hint else R.string.usage_details_hint)
            launchChart.update(emptyList())
            return
        }
        val totals = recentDays().map { data[it]?.values?.sum() ?: 0L }
        activityValue.text = activeDuration(this, totals.last())
        activityDetail.setText(R.string.usage_details_hint)
        launchChart.update(totals)
    }

    private fun showUsage() {
        val data = usage
        if (data == null) {
            if (usageLoaded) showUsageAccess()
            else HomeSheet(this, getString(R.string.app_activity)).message(getString(R.string.usage_loading))
            return
        }
        val days = recentDays().map { date ->
            val activity = data[date].orEmpty().map { (id, millis) ->
                UsagePanel.AppCount(id, appName(id), millis)
            }.sortedByDescending { it.count }
            UsagePanel.Day(date, activity)
        }
        UsagePanel(this, days, motion).show()
    }

    private fun showUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        val handler = intent.resolveActivity(packageManager)
        val sheet = HomeSheet(this, getString(R.string.usage_access))
        if (handler == null || handler.className.endsWith("EmptyStubActivity")) {
            sheet.message(getString(R.string.usage_access_unavailable))
        } else {
            sheet.choices(listOf(getString(R.string.usage_open_settings))) {
                try {
                    startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    HomeSheet(this, getString(R.string.usage_access)).message(getString(R.string.usage_access_unavailable))
                }
            }
        }
    }

    private fun animateReading(view: View) {
        if (!motion || !view.isAttachedToWindow) return
        view.animate().cancel()
        view.alpha = .45f
        view.translationY = dp(5).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun refreshWallpaper(force: Boolean = false) {
        if (!prefs.getBoolean("bing", true) || wallpaperInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (!force && wallpaperCheck >= 0 && now - wallpaperCheck < 60 * 60 * 1000L) return
        wallpaperCheck = now
        wallpaperInFlight = true
        val current = wallpaperPhoto
        wallpaperWorker.execute {
            val cached = current ?: bing.cached()
            if (current == null && cached != null) runOnUiThread {
                if (!isDestroyed) {
                    wallpaperPhoto = cached
                    showWallpaper()
                }
            }
            val result = runCatching { bing.fetch(cached?.date) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                wallpaperInFlight = false
                wallpaperPhoto = result.getOrNull() ?: cached
                if (result.isFailure) wallpaperCheck = now - 45 * 60 * 1000L
                showWallpaper()
            }
        }
    }

    private fun showWallpaper() {
        val photo = wallpaperPhoto.takeIf { prefs.getBoolean("bing", true) }
        wallpaper.setImageBitmap(photo?.bitmap)
        wallpaperCredit.text = photo?.credit.orEmpty()
    }

    private fun showSettings() {
        val hiddenCount = orderedApps().count { it.id in hiddenIds() }
        val options = listOf(
            HomeSheet.Choice(getString(R.string.hidden_apps), hiddenCount.toString()),
            HomeSheet.Choice(getString(R.string.background_setting), getString(
                if (prefs.getBoolean("bing", true)) R.string.bing_daily else R.string.dark_background
            )),
            HomeSheet.Choice(getString(R.string.glass_setting), glassOptions()[glassIndex()]),
            HomeSheet.Choice(getString(R.string.motion_setting), getString(
                if (motion) R.string.motion_normal else R.string.motion_reduced
            )),
            HomeSheet.Choice(getString(R.string.weather_city), city.name),
            HomeSheet.Choice(getString(R.string.usage_access)),
            HomeSheet.Choice(getString(R.string.android_settings))
        )
        HomeSheet(this, getString(R.string.settings)).items(options) { choice ->
            when (choice) {
                0 -> showHiddenApps()
                1 -> selectBackground()
                2 -> selectGlass()
                3 -> selectMotion()
                4 -> chooseCity()
                5 -> showUsageAccess()
                6 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }

    private fun selectBackground() {
        val options = listOf(getString(R.string.bing_daily), getString(R.string.dark_background))
        HomeSheet(this, getString(R.string.background_setting))
            .choices(options, if (prefs.getBoolean("bing", true)) 0 else 1) { choice ->
                prefs.edit().putBoolean("bing", choice == 0).apply()
                showWallpaper()
                refreshWallpaper(force = true)
            }
    }

    private val glassLevels = intArrayOf(95, 132, 210)

    private fun glassIndex(): Int {
        val index = glassLevels.indexOf(prefs.getInt("glass", 132))
        return if (index < 0) 1 else index
    }

    private fun glassOptions() = listOf(
        getString(R.string.clear_glass), getString(R.string.balanced_glass), getString(R.string.dark_glass)
    )

    private fun selectGlass() {
        HomeSheet(this, getString(R.string.glass_setting)).choices(glassOptions(), glassIndex()) { choice ->
            prefs.edit().putInt("glass", glassLevels[choice]).apply()
            createHome()
            showApps()
            showConditions(city, conditions)
            appSettings.requestFocus()
        }
    }

    private fun selectMotion() {
        val options = listOf(getString(R.string.motion_normal), getString(R.string.motion_reduced))
        HomeSheet(this, getString(R.string.motion_setting)).choices(options, if (motion) 0 else 1) { choice ->
            prefs.edit().putBoolean("motion", choice == 0).apply()
        }
    }

    private fun refreshWeather(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastWeatherRequest != 0L && now - lastWeatherRequest < 15 * 60 * 1000L) return
        lastWeatherRequest = now
        val location = city
        weatherWorker.execute {
            val result = runCatching { Weather.current(location) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || city != location) return@runOnUiThread
                conditions = result ?: conditions
                showConditions(location, conditions, stale = result == null)
                if (result == null) lastWeatherRequest = now - 14 * 60 * 1000L
            }
        }
    }

    private fun showConditions(location: Weather.City, current: Weather.Conditions?, stale: Boolean = false) {
        val reading = if (current == null) location.name else getString(R.string.temperature, current.celsius)
        val symbol = current?.symbol.orEmpty()
        if (weatherValue.text.toString() != reading || weatherSymbol.text.toString() != symbol) {
            weatherValue.text = reading
            weatherSymbol.text = symbol
            animateReading(weatherValue)
            animateReading(weatherSymbol)
        }
        weatherDetail.text = when {
            current == null -> getString(R.string.weather_unavailable)
            stale -> getString(R.string.weather_stale, location.name)
            else -> getString(R.string.weather_detail, location.name, current.description)
        }
    }

    private fun chooseCity() {
        HomeSheet(this, getString(R.string.weather_city))
            .input(getString(R.string.city_hint), getString(R.string.find_city), ::searchCities)
    }

    private fun searchCities(query: String) {
        weatherWorker.execute {
            val matches = runCatching { Weather.find(query) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (matches.isNullOrEmpty()) {
                    Toast.makeText(this, if (matches == null) R.string.weather_unavailable else R.string.no_cities, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                HomeSheet(this, getString(R.string.weather_city))
                    .choices(matches.map { it.name }) { index ->
                        city = matches[index]
                        prefs.edit().putString("city", city.name)
                            .putString("latitude", city.latitude.toString())
                            .putString("longitude", city.longitude.toString()).apply()
                        conditions = null
                        weatherValue.text = city.name
                        weatherSymbol.text = ""
                        weatherDetail.setText(R.string.loading_weather)
                        refreshWeather(force = true)
                    }
            }
        }
    }

}
