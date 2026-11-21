package dev.lprakashv.cleanwall

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.time.LocalDate
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        const val CARD_GAP = 4
        const val WIDGET_ORDER = "widget_order"
        const val WIDGET_ACTIVITY_CHART = "widget_activity_chart"
        const val WIDGET_ACTIVITY_APPS = "widget_activity_apps"
        const val WIDGET_DATE_YEAR = "widget_date_year"
        const val WIDGETS_VISIBLE = "widgets_visible"
        const val CONTINUE_VISIBLE = "continue_visible"
        const val CONTINUE_BY_APP = "continue_by_app"
        const val CONTINUE_ROW = "continue_row"
        const val CONTINUE_SOURCES = "continue_sources"
        const val TV_LISTINGS_REQUEST = 1
    }

    private data class App(
        val id: String, val label: String, val activity: ResolveInfo,
        val icon: Drawable?, val banner: Drawable?
    )

    private class AppRail(val watch: Boolean, val container: HorizontalScrollView, val cards: LinearLayout) {
        var lastApp: String? = null
    }

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val activityTime by lazy { AndroidActivityTime(this) }
    private var runningPackages: Set<String>? = null
    private val runningApps by lazy { RunningApps(this) { packages ->
        runningPackages = packages
        updateRunningCards()
    } }
    private var usageLoading = false
    private var usageLoaded = false
    private var usage: AndroidActivityTime.Week? = null
    private lateinit var appTitle: TextView
    private val wallpaper by lazy { HomeWallpaper(this, prefs) { motion } }
    private lateinit var appSettings: TextView
    private lateinit var playStore: ImageView
    private lateinit var widgets: HomeWidgets
    private lateinit var widgetSlot: FrameLayout
    private lateinit var clockBlockIcon: ImageView
    private var widgetOrder = emptyList<WidgetId>()
    private var movingWidget: View? = null
    private var lastWidget: View? = null
    private val appWorker = Executors.newSingleThreadExecutor()
    private val weatherWorker = Executors.newSingleThreadExecutor()
    private var apps = emptyList<App>()
    private var appSignature: List<String>? = null
    private var selectedApp: String? = null
    private var city = Weather.bengaluru
    private var lastWeatherRequest = 0L
    private var conditions: Weather.Conditions? = null
    private var weatherStale = false
    private var weatherLoading = true
    private lateinit var watchRail: AppRail
    private lateinit var utilitiesRail: AppRail
    private lateinit var utilityHeading: TextView
    private lateinit var continueSlot: LinearLayout
    private lateinit var continueCards: LinearLayout
    private lateinit var suggestionsOverlay: ScrollView
    private lateinit var suggestionList: LinearLayout
    private var lastContinueKey: Pair<String, String>? = null
    private var suggestionAnchor: View? = null
    private var watchNextRequest: CancellationSignal? = null
    private var homeVisible = false
    private var watchNext = emptyList<ContinueWatching.Entry>()
    private var visibleWatchNext = emptyList<ContinueWatching.Entry>()
    private lateinit var position: TextView
    private lateinit var appControls: TextView
    private lateinit var actionButtons: LinearLayout
    private var actionCard: View? = null
    private var movingApp: App? = null
    private var slidePending = false
    private val handler = Handler(Looper.getMainLooper())
    private val continueArtwork by lazy { ContinueArtwork(contentResolver, handler) }
    private val widgetUpdates = WidgetUpdates(handler) { source, snapshot -> widgets.showData(source, snapshot) }
    private val dateChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = showDateAndTimeBlock()
    }
    private val updateWidgets = object : Runnable {
        override fun run() {
            refreshContinueWatching()
            refreshActivityTime()
            refreshWeather()
            wallpaper.refresh()
            handler.postDelayed(this, 60_000)
        }
    }

    private val ink = HomeTheme.INK
    private val muted = HomeTheme.MUTED
    private lateinit var homeTheme: HomeTheme
    private val motion get() = prefs.getBoolean("motion", true)
    private val widgetsVisible get() = prefs.getBoolean(WIDGETS_VISIBLE, true)
    private val continueVisible get() = prefs.getBoolean(CONTINUE_VISIBLE, false)
    private val continueByApp get() = prefs.getBoolean(CONTINUE_BY_APP, false)
    private val continueRow get() = prefs.getBoolean(CONTINUE_ROW, !continueByApp)
    private val continueSources get() = prefs.getStringSet(CONTINUE_SOURCES, null)
    private val artOutline = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, dp(3).toFloat())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedApp = savedInstanceState?.getString("selected")
        city = Weather.City(
            prefs.getString("city", null) ?: Weather.bengaluru.name,
            prefs.getString("latitude", null)?.toDoubleOrNull() ?: Weather.bengaluru.latitude,
            prefs.getString("longitude", null)?.toDoubleOrNull() ?: Weather.bengaluru.longitude
        )
        appWorker.execute {
            val unfinishedLayout = File(filesDir, "widgets.txt.new")
            if (unfinishedLayout.isFile) unfinishedLayout.delete()
        }
        widgetOrder = WidgetCatalog.restore(prefs.getString(WIDGET_ORDER, null))
        createHome()
    }

    override fun onResume() {
        super.onResume()
        homeVisible = true
        val dateEvents = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dateChanges, dateEvents, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dateChanges, dateEvents)
        }
        showDateAndTimeBlock()
        wallpaper.resume()
        loadApps()
        runningApps.resume()
        updateWidgets.run()
        widgetUpdates.resume()
    }

    override fun onPause() {
        homeVisible = false
        runningApps.pause()
        unregisterReceiver(dateChanges)
        finishWidgetMove(save = false)
        finishMove(save = false)
        closeCardActions(restoreFocus = false)
        closeSuggestions(restoreFocus = true)
        cancelWatchNextRequest()
        handler.removeCallbacks(updateWidgets)
        widgetUpdates.pause()
        wallpaper.pause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedApp)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        runningApps.close()
        continueArtwork.close()
        appWorker.shutdownNow()
        weatherWorker.shutdownNow()
        wallpaper.close()
        widgetUpdates.close()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    // Keep Home controls inside the TV's 5% overscan margin.
    private val homeHorizontalInsetDp get() = resources.configuration.screenWidthDp / 20
    private val homeVerticalInsetDp get() = resources.configuration.screenHeightDp / 20

    private fun label(value: CharSequence, size: Float, color: Int = ink, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun homeSheet(title: String) = HomeSheet(this, title, homeTheme)

    private fun createHome() {
        homeTheme = HomeTheme(this, prefs.getInt("glass", 132))
        val horizontalInset = dp(homeHorizontalInsetDp)
        val verticalInset = dp(homeVerticalInsetDp)
        val root = vertical()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(getString(R.string.home_name), 30f, bold = true).apply {
            val logo = getDrawable(R.mipmap.clean_wall_icon)?.apply {
                setBounds(0, 0, dp(42), dp(42))
            }
            setCompoundDrawablesRelative(logo, null, null, null)
            compoundDrawablePadding = dp(8)
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), Color.BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(20, 30, 1, TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(TextClock(this).apply {
            val locale = resources.configuration.locales[0]
            format12Hour = DateFormat.getBestDateTimePattern(locale, "EEEMMMdhm")
            format24Hour = DateFormat.getBestDateTimePattern(locale, "EEEMMMdHm")
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink)
            includeFontPadding = false
            isFocusable = false
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        clockBlockIcon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        header.addView(clockBlockIcon, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginStart = dp(7) })
        playStore = ImageView(this).apply {
            id = View.generateViewId()
            contentDescription = getString(R.string.play_store)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = homeTheme.control(Color.TRANSPARENT)
            isFocusable = true
            setOnClickListener { openPlayStore() }
        }
        updateStoreIcon()
        header.addView(playStore, LinearLayout.LayoutParams(dp(36), dp(38)).apply { marginStart = dp(12) })
        appSettings = control(getString(R.string.settings)) { showSettings() }.apply {
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_settings, 0, 0, 0)
            compoundDrawablePadding = dp(5)
            setPadding(dp(8), 0, dp(8), 0)
        }
        header.addView(appSettings, LinearLayout.LayoutParams(dp(107), dp(38)).apply { marginStart = dp(6) })
        root.addView(header)

        widgetSlot = FrameLayout(this)
        root.addView(widgetSlot, LinearLayout.LayoutParams(-1, -2))
        mountWidgets()

        continueCards = LinearLayout(this)
        continueSlot = vertical().apply {
            visibility = View.GONE
            addView(label(getString(R.string.continue_watching), 18f, bold = true))
            addView(scrollRail(continueCards), LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(4) })
        }
        root.addView(continueSlot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        suggestionAnchor = null
        suggestionList = vertical().apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        suggestionsOverlay = ScrollView(this).apply {
            visibility = View.GONE
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = homeTheme.shape(homeTheme.dialogSurface, stroke = HomeTheme.BOUNDARY)
            elevation = dp(8).toFloat()
            addView(suggestionList)
        }

        val watchHeader = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        watchHeader.addView(label(getString(R.string.watch), 22f, bold = true))
        appTitle = label("", 16f, muted).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        watchHeader.addView(appTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        position = label("", 13f, muted).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        watchHeader.addView(position)
        root.addView(watchHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        watchRail = createRail(watch = true)
        root.addView(watchRail.container, LinearLayout.LayoutParams(-1, dp(136)).apply { topMargin = dp(3) })
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
        root.addView(actionStrip, LinearLayout.LayoutParams(-1, dp(31)))
        root.addView(Space(this), LinearLayout.LayoutParams(-1, dp(6), 1f))
        utilityHeading = label(getString(R.string.utilities), 16f, muted, bold = true)
        root.addView(utilityHeading)
        utilitiesRail = createRail(watch = false)
        root.addView(utilitiesRail.container, LinearLayout.LayoutParams(-1, dp(76)).apply { topMargin = dp(4) })

        val wallpaperCredit = label("", 11f, muted).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(wallpaperCredit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        setContentView(FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF512B72.toInt(), 0xFF354C84.toInt(), 0xFF225C64.toInt())
            )
            addView(wallpaper.layer(wallpaperCredit), FrameLayout.LayoutParams(-1, -1))
            addView(ScrollView(this@MainActivity).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                isFocusable = false
                addView(root)
            }, FrameLayout.LayoutParams(-1, -1).apply {
                setMargins(horizontalInset, verticalInset, horizontalInset, verticalInset)
            })
            addView(suggestionsOverlay, FrameLayout.LayoutParams(0, 0))
        })
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, next ->
            val anchor = suggestionAnchor
            if (anchor != null && next != null && next.parent !== suggestionList) closeSuggestions()
            updateAppControls()
        }
        linkRows()
        showDateAndTimeBlock()
        showActivity()
        showConditions()
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
                updateStoreIcon()
                showApps()
                refreshContinueWatching()
                refreshActivityTime()
            }
        }
    }

    private fun control(title: String, action: () -> Unit) = label(title, 15f, bold = true).apply {
        gravity = Gravity.CENTER
        id = View.generateViewId()
        isFocusable = true
        background = homeTheme.control()
        elevation = dp(2).toFloat()
        setOnClickListener { action() }
    }

    private fun openPlayStore() {
        val app = apps.firstOrNull { it.id == AppPlacement.PLAY_STORE }
        if (app != null && tryStartActivity(launchIntent(app))) return
        val fallback = packageManager.getLaunchIntentForPackage(AppPlacement.PLAY_STORE)
        if (fallback == null || !tryStartActivity(fallback)) {
            Toast.makeText(this, getString(R.string.cannot_open, getString(R.string.play_store)), Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStoreIcon() {
        val icon = apps.firstOrNull { it.id == AppPlacement.PLAY_STORE }?.icon
        playStore.setImageDrawable(icon?.constantState?.newDrawable(resources) ?: icon ?: getDrawable(R.drawable.ic_store))
    }

    private fun openWidgetApp(id: String) {
        val app = apps.firstOrNull { it.id == id }?.takeUnless { id in hiddenIds() }
        if (app != null) launch(app)
        else Toast.makeText(this, R.string.widget_app_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun runWidgetAction(spec: WidgetSpec) {
        when (spec.action) {
            WidgetAction.WEATHER_FORECAST -> showForecast()
            WidgetAction.APP_ACTIVITY -> showUsage()
            WidgetAction.OPEN_APP -> openWidgetApp((spec.source as WidgetSource.App).packageName)
            WidgetAction.REFRESH -> widgetUpdates.select(spec.source as WidgetSource.Remote)
            WidgetAction.DETAILS, WidgetAction.NONE -> Unit
        }
    }

    private fun mountWidgets() {
        val specs = widgetOrder.takeIf { widgetsVisible }.orEmpty().map { id ->
            val spec = WidgetCatalog.available.getValue(id)
            if (spec.source != WidgetSource.Device.ACTIVITY) spec else spec.copy(
                activityComponents = spec.activityComponents.filterTo(mutableSetOf()) { component ->
                    when (component) {
                        ActivityComponent.CHART -> prefs.getBoolean(WIDGET_ACTIVITY_CHART, true)
                        ActivityComponent.ROWS -> prefs.getBoolean(WIDGET_ACTIVITY_APPS, true)
                        ActivityComponent.DETAIL_ICON -> true
                    }
                }
            )
        }
        val width = resources.configuration.screenWidthDp - 2 * homeHorizontalInsetDp
        lastWidget = null
        widgets = HomeWidgets(this, specs, width, homeTheme, { motion }, ::runWidgetAction)
        widgetUpdates.bind(specs)
        widgets.tiles.forEachIndexed { index, tile ->
            val id = widgetOrder[index]
            tile.tag = id
            setCardFocus(tile, focused = false, animate = false, restScale = .99f)
            tile.setOnFocusChangeListener { view, focused ->
                setCardFocus(view, focused || view.isActivated, animate = true, restScale = .99f)
                if (focused) {
                    lastWidget = view
                    linkRows()
                }
            }
            tile.setOnLongClickListener {
                showCardActions(tile, widgetActions(id))
                true
            }
        }
        widgetSlot.removeAllViews()
        widgetSlot.visibility = if (specs.isEmpty()) View.GONE else View.VISIBLE
        widgetSlot.addView(widgets.container, FrameLayout.LayoutParams(-1, dp(widgets.rowHeightDp)).apply {
            topMargin = dp(12)
        })
    }

    private fun widgetTitle(id: WidgetId) = getString(WidgetCatalog.available.getValue(id).title)

    private fun widgetActions(id: WidgetId): List<Pair<String, () -> Unit>> = buildList {
        if (widgetsVisible && widgetOrder.size > 1) add(getString(R.string.move_app) to { moveWidget(id) })
        if (WidgetCatalog.available.getValue(id).source is WidgetSource.Device) {
            add(getString(R.string.configure_widget) to { configureWidget(id) })
        }
        add(getString(R.string.remove_widget) to { removeWidget(id) })
    }

    private fun saveWidgetOrder(order: List<WidgetId>) {
        widgetOrder = order
        prefs.edit().putString(WIDGET_ORDER, order.joinToString(",") { it.key }).apply()
    }

    private fun refreshWidgetRow(focus: WidgetId?) {
        mountWidgets()
        showDateAndTimeBlock()
        showActivity()
        showConditions()
        showWidgetShortcuts(hiddenIds())
        refreshWeather()
        refreshActivityTime()
        lastWidget = widgets.tiles.firstOrNull { it.tag == focus }
        linkRows()
        (lastWidget ?: appSettings).requestFocus()
    }

    private fun moveWidget(id: WidgetId) {
        val tile = widgets.tiles.firstOrNull { it.tag == id } ?: return
        movingWidget = tile
        tile.isActivated = true
        tile.translationZ = dp(5).toFloat()
        tile.requestFocus()
        appControls.text = getString(R.string.move_controls, widgetTitle(id))
    }

    private fun finishWidgetMove(save: Boolean) {
        val tile = movingWidget ?: return
        movingWidget = null
        if (save) {
            saveWidgetOrder(widgets.tiles.map { it.tag as WidgetId })
        } else {
            val distance = widgetOrder.indexOf(tile.tag as WidgetId) - widgets.tiles.indexOf(tile)
            repeat(kotlin.math.abs(distance)) { widgets.slide(tile, if (distance > 0) 1 else -1) }
            revealWidget(tile)
        }
        tile.isActivated = false
        tile.translationZ = 0f
        appControls.setText(R.string.app_controls)
    }

    private fun revealWidget(tile: View) {
        tile.post { tile.requestRectangleOnScreen(Rect(0, 0, tile.width, tile.height), !motion) }
    }

    private fun removeWidget(id: WidgetId) {
        val index = widgetOrder.indexOf(id)
        if (index < 0) return
        saveWidgetOrder(widgetOrder - id)
        refreshWidgetRow(widgetOrder.getOrNull(minOf(index, widgetOrder.lastIndex)))
    }

    private fun showWidgetManager() {
        val shown = widgetOrder
        val options = listOf(
            HomeSheet.Choice(getString(R.string.show_widgets_row), visibilitySetting(widgetsVisible)),
            HomeSheet.Choice(getString(R.string.add_widget))
        ) + shown.map { HomeSheet.Choice(widgetTitle(it)) }
        homeSheet(getString(R.string.widgets_settings)).items(options) { index ->
            if (index == 0) {
                prefs.edit().putBoolean(WIDGETS_VISIBLE, !widgetsVisible).apply()
                refreshWidgetRow(null)
            } else if (index == 1) showWidgetCatalog()
            else {
                val id = shown[index - 2]
                val actions = widgetActions(id)
                homeSheet(widgetTitle(id)).choices(actions.map { it.first }) { choice -> actions[choice].second() }
            }
        }
    }

    private fun showWidgetCatalog() {
        val available = WidgetCatalog.available.keys - widgetOrder.toSet()
        val sheet = homeSheet(getString(R.string.add_widget))
        if (available.isEmpty()) {
            sheet.message(getString(R.string.no_available_widgets))
        } else {
            val choices = available.toList()
            sheet.choices(choices.map(::widgetTitle)) { index ->
                val id = choices[index]
                saveWidgetOrder(widgetOrder + id)
                refreshWidgetRow(id)
            }
        }
    }

    private fun configureWidget(id: WidgetId) {
        val source = WidgetCatalog.available.getValue(id).source
        val defaults = source == WidgetSource.Device.ACTIVITY
        val options = when (source) {
            WidgetSource.Device.WEATHER -> return chooseCity()
            WidgetSource.Device.ACTIVITY -> listOf(WIDGET_ACTIVITY_CHART to R.string.widget_show_chart, WIDGET_ACTIVITY_APPS to R.string.widget_show_apps)
            WidgetSource.Device.DATE -> listOf(WIDGET_DATE_YEAR to R.string.widget_show_year)
            else -> return
        }
        val choices = options.map { (key, title) ->
            HomeSheet.Choice(getString(title), getString(if (prefs.getBoolean(key, defaults)) R.string.widget_on else R.string.widget_off))
        }.toMutableList()
        if (source == WidgetSource.Device.ACTIVITY) choices.add(HomeSheet.Choice(getString(R.string.usage_access)))

        homeSheet(widgetTitle(id)).items(choices) { index ->
            if (source == WidgetSource.Device.ACTIVITY && index == options.size) {
                showUsageAccess()
                return@items
            }
            val key = options[index].first
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, defaults)).apply()
            refreshWidgetRow(id)
        }
    }

    private fun showDateAndTimeBlock() {
        val now = Calendar.getInstance()
        val block = TimeBlock.atHour(now.get(Calendar.HOUR_OF_DAY))
        if (clockBlockIcon.tag != block) {
            clockBlockIcon.tag = block
            clockBlockIcon.setImageResource(block.icon)
            clockBlockIcon.contentDescription = getString(R.string.current_time_block, getString(block.label))
        }
        if (!widgets.uses(WidgetSource.Device.DATE)) return
        val calendarPattern = if (prefs.getBoolean(WIDGET_DATE_YEAR, false)) "EEEE, MMMM yyyy" else "EEEE, MMMM"
        val values = mapOf(
            "day" to DateFormat.format("d", now).toString(),
            "calendar" to DateFormat.format(calendarPattern, now).toString()
        )
        widgets.showData(WidgetSource.Device.DATE, WidgetUpdates.Snapshot(values))
    }

    private fun createRail(watch: Boolean): AppRail {
        val cards = LinearLayout(this).apply {
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            val inset = dp(4)
            setPadding(inset, dp(4), inset, dp(4))
            clipChildren = false
            clipToPadding = false
        }
        return AppRail(watch, scrollRail(cards), cards)
    }

    private fun scrollRail(cards: LinearLayout) =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(cards)
        }

    private fun showApps() {
        closeSuggestions(restoreFocus = true)
        val previousFocus = currentFocus
        val appActionsOpen = actionCard?.tag is String
        val keepFocus = (watchRail.cards.childCount + utilitiesRail.cards.childCount == 0 && previousFocus !in widgets.tiles &&
            actionCard == null) || focusedRail() != null || appActionsOpen
        val previousApp = selectedApp
        movingApp = null
        if (appActionsOpen) closeCardActions(restoreFocus = false)
        val hidden = hiddenIds()
        showWidgetShortcuts(hidden)
        val (watch, utilities) = orderedApps().filterNot { it.id in hidden }.partition(::isWatch)
        showRail(watchRail, watch)
        showRail(utilitiesRail, utilities)
        utilityHeading.visibility = if (utilities.isEmpty()) View.GONE else View.VISIBLE
        utilitiesRail.container.visibility = utilityHeading.visibility

        val mainCard = lastCard(watchRail)
        val selected = findCard(previousApp) ?: mainCard ?: lastCard(utilitiesRail)
        selectedApp = selected?.tag as? String
        if (mainCard == null) {
            appTitle.text = ""
            position.setText(R.string.no_watch_apps)
        } else {
            updateWatchTitle(mainCard)
        }
        showContinueWatching()
        linkRows()
        if (keepFocus) (selected ?: appSettings).requestFocus()
        else if (previousFocus?.isAttachedToWindow == true) previousFocus.requestFocus()
    }

    private fun showWidgetShortcuts(hidden: Set<String>) {
        if (widgets.hasAppShortcuts) {
            val names = apps.filterNot { it.id in hidden }.associate { it.id to it.label }
            widgets.showAppShortcuts { source ->
                val name = names[source.packageName]
                mapOf(
                    "name" to (name ?: getString(R.string.widget_app_unavailable)),
                    "hint" to if (name == null) "" else getString(R.string.widget_open_hint)
                )
            }
        }
    }

    private fun showRail(row: AppRail, apps: List<App>) {
        row.cards.removeAllViews()
        apps.forEach { app ->
            val card = makeCard(row, app)
            row.cards.addView(card, LinearLayout.LayoutParams(dp(if (row.watch) 189 else 102), -1).apply {
                marginEnd = dp(CARD_GAP)
            })
        }
        val last = lastCard(row)
        row.lastApp = last?.tag as? String
        if (last != null) row.container.post {
            if (last.parent == row.cards && row.lastApp == last.tag) centerCard(row, last)
        }
    }

    private fun makeCard(row: AppRail, app: App): View {
        val banner = app.banner.takeIf { row.watch }
        val face = vertical().apply {
            gravity = Gravity.CENTER
            clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isDuplicateParentStateEnabled = true
        }
        val art = ImageView(this).apply {
            setImageDrawable(banner ?: app.icon)
            scaleType = if (banner == null) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            outlineProvider = artOutline
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        val title = label(app.label, if (row.watch) 15f else 12f, bold = true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(5), 0, dp(5), 0)
            setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
            isDuplicateParentStateEnabled = true
            background = homeTheme.control(Color.TRANSPARENT)
        }
        if (row.watch) {
            val artwork = if (banner != null) art else FrameLayout(this).apply {
                addView(art, FrameLayout.LayoutParams(dp(49), dp(49), Gravity.CENTER))
            }
            face.addView(artwork, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            face.addView(art, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        face.addView(title, LinearLayout.LayoutParams(-1, dp(if (row.watch) 22 else 19)).apply {
            topMargin = dp(3)
        })
        val card = FrameLayout(this).apply {
            id = View.generateViewId()
            tag = app.id
            contentDescription = app.label
            isFocusable = true
            clipChildren = false
            addView(face, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@MainActivity).apply {
                tag = "running_dot"
                background = homeTheme.shape(HomeTheme.ACCENT, radius = 10, stroke = Color.BLACK)
                visibility = if (app.id in runningPackages.orEmpty()) View.VISIBLE else View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(5)
                marginEnd = dp(5)
            })
            setOnClickListener { launch(app) }
            setOnLongClickListener { showAppActions(app); true }
            setOnFocusChangeListener { view, focused ->
                val emphasized = focused || view.isActivated
                view.translationZ = if (emphasized) dp(4).toFloat() else 0f
                setCardFocus(face, emphasized, animate = true, focusedScale = 1.04f)
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

    private fun setCardFocus(
        face: View, focused: Boolean, animate: Boolean, restScale: Float = .975f, focusedScale: Float = 1f
    ) {
        val scale = if (focused) focusedScale else restScale
        face.animate().cancel()
        if (motion && animate) {
            face.animate().scaleX(scale).scaleY(scale).setDuration(215)
                .setInterpolator(DecelerateInterpolator()).start()
        } else {
            face.scaleX = scale
            face.scaleY = scale
        }
    }

    private fun hiddenIds(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty()

    private fun isWatch(app: App): Boolean = AppPlacement.belongsInWatch(
        app.id, app.activity.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_VIDEO,
        prefs.getStringSet("watch_pins", emptySet()).orEmpty(),
        prefs.getStringSet("utility_pins", emptySet()).orEmpty()
    )

    private fun watchSources(): List<App> {
        val hidden = hiddenIds()
        return orderedApps().filter { it.id !in hidden && isWatch(it) }
    }

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
        appTitle.text = apps.firstOrNull { it.id == card.tag }?.label
        position.text = getString(R.string.app_position, watchRail.cards.indexOfChild(card) + 1, watchRail.cards.childCount)
    }

    private fun hasTvListingsAccess() =
        checkSelfPermission(ContinueWatching.PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun cancelWatchNextRequest() {
        watchNextRequest?.cancel()
        watchNextRequest = null
    }

    private fun refreshContinueWatching() {
        if (!homeVisible) return
        if (!continueVisible || !hasTvListingsAccess()) {
            cancelWatchNextRequest()
            if (watchNext.isNotEmpty()) {
                watchNext = emptyList()
                showContinueWatching()
            }
            return
        }
        if (apps.isEmpty() || watchNextRequest != null) return
        val ids = apps.mapTo(mutableSetOf()) { it.id }
        val request = CancellationSignal()
        watchNextRequest = request
        appWorker.execute {
            val entries = runCatching { ContinueWatching.read(contentResolver, ids, request) }.getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed || watchNextRequest !== request) return@runOnUiThread
                watchNextRequest = null
                if (watchNext != entries) {
                    watchNext = entries
                    showContinueWatching()
                }
            }
        }
    }

    private fun showContinueWatching() {
        val focused = currentFocus?.takeIf { it.parent === continueCards }
        val available = watchSources().associateBy { it.id }
        val entries = if (continueVisible && hasTvListingsAccess()) {
            ContinueWatching.select(watchNext.asSequence(), available.keys, continueSources)
        } else emptyList()
        suggestionAnchor?.tag?.let { appId ->
            if (!continueByApp || visibleWatchNext.filter { it.appId == appId } != entries.filter { it.appId == appId }) {
                closeSuggestions(restoreFocus = true)
            }
        }
        visibleWatchNext = entries
        val suggestions = if (continueByApp) entries.mapTo(mutableSetOf()) { it.appId } else emptySet()
        updateRunningCards(suggestions)
        updateAppControls()
        continueCards.removeAllViews()
        if (continueRow) visibleWatchNext.take(ContinueWatching.ROW_LIMIT).forEach { entry ->
            val tile = continueTile(entry, available.getValue(entry.appId), true).apply {
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        lastContinueKey = entry.key
                        linkRows()
                    }
                }
            }
            continueCards.addView(tile, LinearLayout.LayoutParams(dp(290), -1).apply { marginEnd = dp(CARD_GAP) })
        }
        continueSlot.visibility = if (continueCards.childCount == 0) View.GONE else View.VISIBLE
        linkRows()
        if (focused != null) {
            (lastContinueCard() ?: lastCard(watchRail) ?: lastCard(utilitiesRail) ?: lastWidget ?: appSettings).requestFocus()
        }
    }

    private fun lastContinueCard(): View? =
        lastContinueKey?.let { continueCards.findViewWithTag<View>(it) } ?: continueCards.getChildAt(0)

    private fun continueTile(entry: ContinueWatching.Entry, source: App, showSource: Boolean) = LinearLayout(this).apply {
        id = View.generateViewId()
        tag = entry.key
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        background = homeTheme.control()
        setPadding(dp(6), dp(6), dp(6), dp(6))
        val title = entry.episode?.takeIf { it.isNotBlank() && it != entry.title }?.let {
            getString(R.string.continue_episode_title, entry.title, it)
        } ?: entry.title
        val status = getString(R.string.continue_next_episode).takeIf { entry.isNextEpisode }
        val shortcut = getString(R.string.continue_utilities_hint)
            .takeIf { !showSource && lastCard(utilitiesRail) != null }
        val detail = listOfNotNull(source.label.takeIf { showSource }, status, shortcut).joinToString(" · ")
        val spokenProgress = entry.progress?.let { getString(R.string.continue_playback_progress, it) }
        contentDescription = listOfNotNull(title, detail.takeIf { it.isNotEmpty() }, spokenProgress).joinToString(". ")
        val image = ImageView(this@MainActivity).apply {
            setImageDrawable(source.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            outlineProvider = artOutline
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(image, LinearLayout.LayoutParams(dp(96), dp(54)).apply { marginEnd = dp(9) })
        continueArtwork.show(image, listOfNotNull(entry.thumbnail, entry.poster).filter { it.isNotBlank() },
            dp(96), dp(54))
        addView(vertical().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 15f, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (detail.isNotEmpty()) addView(label(detail, 12f, muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (showSource) source.icon?.let { original ->
                    val icon = original.constantState?.newDrawable(resources)?.mutate() ?: original.mutate()
                    icon.setBounds(0, 0, dp(16), dp(16))
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = dp(4)
                }
            })
            entry.progress?.let { value ->
                addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = value
                    progressTintList = ColorStateList.valueOf(HomeTheme.ACCENT)
                    progressBackgroundTintList = ColorStateList.valueOf(HomeTheme.MUTED)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(4) })
            }
        }, LinearLayout.LayoutParams(0, -1, 1f))
        setOnClickListener {
            val intent = entry.playbackIntent()
            if (intent == null || !tryStartActivity(intent)) {
                Toast.makeText(this@MainActivity, getString(R.string.cannot_open, entry.title), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAppControls() {
        if (movingApp != null || movingWidget != null) return
        val appId = currentFocus?.tag as? String
        val hasSuggestions = continueByApp && visibleWatchNext.any { it.appId == appId }
        appControls.setText(if (hasSuggestions) R.string.continue_dropdown_hint else R.string.app_controls)
    }

    private fun openSuggestions(anchor: View): Boolean {
        val entries = visibleWatchNext.filter { it.appId == anchor.tag }
        if (entries.isEmpty()) return false
        val row = watchRail
        val frame = suggestionsOverlay.parent as FrameLayout
        val width = minOf(row.container.width, dp(320))
        val bottom = frame.height - dp(homeVerticalInsetDp)
        val minimumHeight = dp(78)
        if (width == 0 || bottom < minimumHeight) return false
        val railLocation = IntArray(2).also(row.container::getLocationInWindow)
        val frameLocation = IntArray(2).also(frame::getLocationInWindow)
        val anchorOffset = row.cards.left + anchor.left - row.container.scrollX
        val left = railLocation[0] - frameLocation[0] + anchorOffset.coerceIn(0, row.container.width - width)
        val belowRail = railLocation[1] - frameLocation[1] + row.container.height
        val top = belowRail.coerceIn(0, bottom - minimumHeight)
        val height = minOf(entries.size * dp(68) + 2 * dp(5), bottom - top)
        val app = apps.firstOrNull { it.id == anchor.tag } ?: return false
        entries.forEach { entry ->
            suggestionList.addView(continueTile(entry, app, false), LinearLayout.LayoutParams(-1, dp(68)))
        }
        suggestionsOverlay.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.TOP or Gravity.START).apply {
            topMargin = top
            marginStart = if (frame.layoutDirection == View.LAYOUT_DIRECTION_RTL) frame.width - width - left else left
        }
        suggestionsOverlay.scrollTo(0, 0)
        suggestionsOverlay.visibility = View.VISIBLE
        suggestionAnchor = anchor
        linkRows()
        suggestionList.getChildAt(0).requestFocus()
        return true
    }

    private fun closeSuggestions(restoreFocus: Boolean = false) {
        val anchor = suggestionAnchor ?: return
        suggestionAnchor = null
        if (restoreFocus && suggestionList.hasFocus()) anchor.requestFocus()
        suggestionsOverlay.visibility = View.GONE
        suggestionList.removeAllViews()
        linkRows()
    }

    private fun linkRows() {
        val watchCard = lastCard(watchRail)
        val utilityCard = lastCard(utilitiesRail)
        val widget = lastWidget ?: widgets.tiles.firstOrNull()
        val continueCard = lastContinueCard()
        appSettings.nextFocusDownId = widget?.id ?: continueCard?.id ?:
            findCard(selectedApp)?.id ?: watchCard?.id ?: utilityCard?.id ?: View.NO_ID
        playStore.nextFocusDownId = appSettings.nextFocusDownId
        widgets.tiles.forEach { tile ->
            tile.nextFocusUpId = appSettings.id
            tile.nextFocusDownId = continueCard?.id ?: watchCard?.id ?: utilityCard?.id ?: View.NO_ID
        }
        for (index in 0 until continueCards.childCount) {
            continueCards.getChildAt(index).apply {
                nextFocusUpId = widget?.id ?: appSettings.id
                nextFocusDownId = watchCard?.id ?: utilityCard?.id ?: View.NO_ID
                nextFocusLeftId = continueCards.getChildAt(index + horizontalStep(this, KeyEvent.KEYCODE_DPAD_LEFT))?.id ?: id
                nextFocusRightId = continueCards.getChildAt(index + horizontalStep(this, KeyEvent.KEYCODE_DPAD_RIGHT))?.id ?: id
            }
        }
        for (index in 0 until watchRail.cards.childCount) {
            watchRail.cards.getChildAt(index).apply {
                nextFocusUpId = continueCard?.id ?: widget?.id ?: appSettings.id
                nextFocusDownId = utilityCard?.id ?: View.NO_ID
            }
        }
        for (index in 0 until utilitiesRail.cards.childCount) {
            utilitiesRail.cards.getChildAt(index).nextFocusUpId = watchCard?.id ?: continueCard?.id ?: widget?.id ?: appSettings.id
        }
        suggestionAnchor?.let { anchor ->
            for (index in 0 until suggestionList.childCount) {
                suggestionList.getChildAt(index).apply {
                    nextFocusUpId = suggestionList.getChildAt(index - 1)?.id ?: anchor.id
                    nextFocusDownId = suggestionList.getChildAt(index + 1)?.id ?: utilityCard?.id ?: id
                    nextFocusLeftId = id
                    nextFocusRightId = id
                }
            }
        }
    }

    private fun centerCard(row: AppRail, card: View) {
        val center = card.left + card.width / 2 - row.container.width / 2
        if (motion) row.container.smoothScrollTo(center, 0) else row.container.scrollTo(center, 0)
    }

    private fun showAppActions(app: App) {
        val card = findCard(app.id) ?: return
        val inWatch = rowFor(card).watch
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            getString(R.string.move_app) to { moveApp(app) },
            getString(if (inWatch) R.string.send_to_utilities else R.string.pin_to_watch) to { placeApp(app, !inWatch) },
            getString(R.string.hide_app) to {
                prefs.edit().putStringSet("hidden", hiddenIds() + app.id).apply()
                showApps()
            },
            getString(R.string.app_info) to { openAppInfo(app) },
            getString(R.string.uninstall_app) to { uninstallApp(app) }
        )
        if (app.id in runningPackages.orEmpty()) actions.add(0, getString(R.string.force_stop) to {
            homeSheet(getString(R.string.force_stop_title, app.label)).choices(
                listOf(getString(R.string.cancel), getString(R.string.force_stop))
            ) { choice ->
                if (choice == 1) runningApps.forceStop(app.id) { success ->
                    if (!isDestroyed) Toast.makeText(this,
                        getString(if (success) R.string.force_stop_requested else R.string.force_stop_failed, app.label),
                        Toast.LENGTH_SHORT).show()
                }
            }
        })
        showCardActions(card, actions)
    }

    private fun updateRunningCards(suggestions: Set<String> = if (continueByApp) {
        visibleWatchNext.mapTo(mutableSetOf()) { it.appId }
    } else emptySet()) {
        apps.forEach { app ->
            val card = findCard(app.id) ?: return@forEach
            val running = app.id in runningPackages.orEmpty()
            card.findViewWithTag<View>("running_dot")?.visibility = if (running) View.VISIBLE else View.GONE
            val description = if (app.id in suggestions) {
                getString(R.string.continue_dropdown_accessibility, app.label)
            } else app.label
            card.contentDescription = if (running) getString(R.string.app_running_accessibility, description) else description
        }
    }

    private fun showCardActions(card: View, actions: List<Pair<String, () -> Unit>>) {
        actionCard = card
        card.isActivated = true
        actionButtons.removeAllViews()
        actions.forEach { (title, action) ->
            actionButtons.addView(control(title) { closeCardActions(restoreFocus = true); action() },
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        }
        appControls.visibility = View.INVISIBLE
        actionButtons.visibility = View.VISIBLE
        actionButtons.getChildAt(0).requestFocus()
    }

    private fun closeCardActions(restoreFocus: Boolean) {
        val card = actionCard ?: return
        actionCard = null
        actionButtons.visibility = View.GONE
        appControls.visibility = View.VISIBLE
        card.isActivated = false
        if (restoreFocus) card.requestFocus()
        else if (card.tag is WidgetId) setCardFocus(card, card.hasFocus(), animate = false, restScale = .99f)
        else setCardFocus((card as FrameLayout).getChildAt(0), card.hasFocus(), animate = false)
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
        if (actionCard != null) {
            if (horizontal) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val next = actionButtons.indexOfChild(currentFocus) + horizontalStep(actionButtons, key)
                    actionButtons.getChildAt(next)?.requestFocus()
                }
                return true
            }
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE ||
                key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeCardActions(restoreFocus = true)
                return true
            }
        }
        movingWidget?.let { tile ->
            when {
                horizontal -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        widgets.slide(tile, horizontalStep(widgets.container, key))
                        revealWidget(tile)
                    }
                    return true
                }
                key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finishWidgetMove(save = true)
                    return true
                }
                key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) finishWidgetMove(save = false)
                    return true
                }
                key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
        }
        if (horizontal && ::appSettings.isInitialized && (currentFocus === appSettings || currentFocus === playStore)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val target = if (horizontalStep(appSettings, key) < 0) playStore else appSettings
                target.requestFocus()
            }
            return true
        }
        if (horizontal && ::widgets.isInitialized) {
            val index = widgets.tiles.indexOf(currentFocus)
            if (index >= 0) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    widgets.tiles.getOrNull(index + horizontalStep(widgets.container, key))?.requestFocus()
                }
                return true
            }
        }
        val app = movingApp
        val row = if (::watchRail.isInitialized && ::utilitiesRail.isInitialized) focusedRail() else null
        if (app == null && continueByApp) {
            val suggestion = currentFocus?.takeIf { it.parent === suggestionList }
            val utility = lastCard(utilitiesRail)
            if (suggestion != null && utility != null &&
                (horizontal ||
                    (key == KeyEvent.KEYCODE_DPAD_DOWN && suggestion === suggestionList.getChildAt(suggestionList.childCount - 1)))
            ) {
                if (event.action == KeyEvent.ACTION_DOWN) utility.requestFocus()
                return true
            }
            if (suggestionAnchor != null && (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE)) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) closeSuggestions(restoreFocus = true)
                return true
            }
            val anchor = currentFocus
            if (row?.watch == true && anchor != null && key == KeyEvent.KEYCODE_DPAD_DOWN &&
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && openSuggestions(anchor)) return true
        }
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
        updateAppControls()
    }

    private fun showHiddenApps() {
        val hidden = orderedApps().filter { it.id in hiddenIds() }
        val sheet = homeSheet(getString(R.string.hidden_apps))
        if (hidden.isEmpty()) sheet.message(getString(R.string.no_hidden_apps))
        else sheet.items(hidden.map { HomeSheet.Choice(it.label, icon = it.icon) }) { index ->
            val app = hidden[index]
            homeSheet(app.label)
                .choices(listOf(getString(R.string.open_app), getString(R.string.show_on_home), getString(R.string.uninstall_app))) { choice ->
                    when (choice) {
                        0 -> launch(app)
                        1 -> {
                            prefs.edit().putStringSet("hidden", hiddenIds() - app.id).apply()
                            showApps()
                        }
                        2 -> uninstallApp(app)
                    }
                }
        }
    }

    private fun launchIntent(app: App) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        .setClassName(app.id, app.activity.activityInfo.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun tryStartActivity(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun launch(app: App) {
        if (!tryStartActivity(launchIntent(app))) {
            Toast.makeText(this, getString(R.string.cannot_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo(app: App) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.id, null))
        if (!tryStartActivity(intent)) {
            Toast.makeText(this, getString(R.string.cannot_open_app_info, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstallApp(app: App) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.id, null))
        if (!tryStartActivity(intent)) {
            Toast.makeText(this, getString(R.string.cannot_uninstall, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun recentDays(): List<LocalDate> {
        val today = LocalDate.now()
        return (6L downTo 0L).map(today::minusDays)
    }

    private fun usageApps(counts: Map<String, Long>): List<UsagePanel.AppCount> =
        counts.entries.sortedByDescending { it.value }.map { (id, millis) ->
            val app = apps.firstOrNull { it.id == id }
            UsagePanel.AppCount(id, app?.label ?: id, millis, app?.icon)
        }

    private fun refreshActivityTime() {
        if (!widgets.uses(WidgetSource.Device.ACTIVITY) || usageLoading || apps.isEmpty()) return
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
        if (!widgets.uses(WidgetSource.Device.ACTIVITY)) return
        val data = usage
        val days = recentDays()
        widgets.showActivity(
            data?.let { week -> days.map { date -> date to (week.days[date]?.values?.sum() ?: 0L) } },
            usageLoaded,
            usageApps(data?.days?.get(days.last()).orEmpty()).take(2),
            data?.todayBlocks.orEmpty()
        )
    }

    private fun showUsage() {
        val data = usage
        if (data == null) {
            if (usageLoaded) showUsageAccess()
            else homeSheet(getString(R.string.app_activity)).message(getString(R.string.usage_loading))
            return
        }
        val days = recentDays().map { date ->
            UsagePanel.Day(date, usageApps(data.days[date].orEmpty()))
        }
        UsagePanel(this, days, motion, homeTheme).show()
    }

    private fun showUsageAccess() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        val handler = intent.resolveActivity(packageManager)
        val sheet = homeSheet(getString(R.string.usage_access))
        if (handler == null || handler.className.endsWith("EmptyStubActivity")) {
            sheet.message(getString(R.string.usage_access_unavailable))
        } else {
            sheet.choices(listOf(getString(R.string.usage_open_settings))) {
                if (!tryStartActivity(intent)) {
                    homeSheet(getString(R.string.usage_access)).message(getString(R.string.usage_access_unavailable))
                }
            }
        }
    }

    private fun showSettings() {
        val hiddenCount = orderedApps().count { it.id in hiddenIds() }
        val options = listOf(
            HomeSheet.Choice(getString(R.string.hidden_apps), hiddenCount.toString()),
            HomeSheet.Choice(getString(R.string.background_setting), getString(wallpaper.source.label)),
            HomeSheet.Choice(getString(R.string.glass_setting), glassOptions()[glassIndex()]),
            HomeSheet.Choice(getString(R.string.motion_setting), getString(
                if (motion) R.string.motion_normal else R.string.motion_reduced
            )),
            HomeSheet.Choice(getString(R.string.widgets_settings)),
            HomeSheet.Choice(getString(R.string.continue_watching), visibilitySetting(continueVisible)),
            HomeSheet.Choice(getString(R.string.running_apps), getString(
                if (runningPackages == null) R.string.running_apps_unavailable else R.string.running_apps_connected
            )),
            HomeSheet.Choice(getString(R.string.android_settings))
        )
        homeSheet(getString(R.string.settings)).items(options) { choice ->
            when (choice) {
                0 -> showHiddenApps()
                1 -> showWallpaperSettings()
                2 -> selectGlass()
                3 -> selectMotion()
                4 -> showWidgetManager()
                5 -> showContinueSettings()
                6 -> if (!runningApps.requestAccess()) {
                    homeSheet(getString(R.string.running_apps)).message(getString(R.string.running_apps_setup))
                }
                7 -> if (!tryStartActivity(Intent(Settings.ACTION_SETTINGS))) {
                    Toast.makeText(this, getString(R.string.cannot_open, getString(R.string.android_settings)), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun visibilitySetting(visible: Boolean) = getString(if (visible) R.string.widget_on else R.string.widget_off)

    private fun showContinueSettings() {
        val options = buildList {
            add(HomeSheet.Choice(getString(R.string.show_continue_watching), visibilitySetting(continueVisible)))
            add(HomeSheet.Choice(getString(R.string.continue_home_row), visibilitySetting(continueRow)))
            add(HomeSheet.Choice(getString(R.string.continue_by_app), visibilitySetting(continueByApp)))
            add(HomeSheet.Choice(getString(R.string.continue_sources), getString(
                if (continueSources == null) R.string.continue_all_apps else R.string.continue_selected_apps
            )))
            if (continueVisible && !hasTvListingsAccess()) add(HomeSheet.Choice(getString(R.string.tv_listings_access)))
        }
        homeSheet(getString(R.string.continue_watching)).items(options) { index ->
            when (index) {
                0 -> {
                    prefs.edit().putBoolean(CONTINUE_VISIBLE, !continueVisible).apply()
                    showContinueWatching()
                    refreshContinueWatching()
                    if (continueVisible && !hasTvListingsAccess()) requestTvListingsAccess()
                }
                1 -> {
                    prefs.edit().putBoolean(CONTINUE_ROW, !continueRow).apply()
                    showContinueWatching()
                }
                2 -> {
                    val row = continueRow
                    prefs.edit().putBoolean(CONTINUE_ROW, row).putBoolean(CONTINUE_BY_APP, !continueByApp).apply()
                    showContinueWatching()
                }
                3 -> showContinueSources()
                4 -> showTvListingsAccess()
            }
        }
    }

    private fun showContinueSources() {
        val title = getString(R.string.continue_sources)
        val sources = continueSources
        val choices = listOf(getString(R.string.continue_all_apps), getString(R.string.continue_selected_apps))
        homeSheet(title).choices(choices, if (sources == null) 0 else 1) { choice ->
            if (choice == 0) {
                prefs.edit().remove(CONTINUE_SOURCES).apply()
                showContinueWatching()
                return@choices
            }
            if (sources == null) {
                prefs.edit().putStringSet(CONTINUE_SOURCES, emptySet()).apply()
                showContinueWatching()
            }
            val candidates = watchSources()
            val sheet = homeSheet(choices[1])
            if (candidates.isEmpty()) {
                sheet.message(getString(R.string.no_source_apps))
            } else {
                val selected = candidates.indices.filterTo(mutableSetOf()) { candidates[it].id in sources.orEmpty() }
                sheet.checks(candidates.map { HomeSheet.Choice(it.label, icon = it.icon) }, selected) { index, checked ->
                    val appId = candidates[index].id
                    val saved = continueSources.orEmpty()
                    prefs.edit().putStringSet(CONTINUE_SOURCES, if (checked) saved + appId else saved - appId).apply()
                    showContinueWatching()
                }
            }
        }
    }

    private fun requestTvListingsAccess() {
        requestPermissions(arrayOf(ContinueWatching.PERMISSION), TV_LISTINGS_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != TV_LISTINGS_REQUEST || !continueVisible) return
        if (!hasTvListingsAccess()) {
            Toast.makeText(this, R.string.tv_listings_required, Toast.LENGTH_LONG).show()
        }
        refreshContinueWatching()
    }

    private fun showTvListingsAccess() {
        homeSheet(getString(R.string.tv_listings_access)).choices(
            listOf(getString(R.string.request_access), getString(R.string.app_permissions))
        ) { index ->
            if (index == 0) requestTvListingsAccess()
            else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                if (!tryStartActivity(intent)) {
                    homeSheet(getString(R.string.tv_listings_access)).message(getString(R.string.tv_listings_required))
                }
            }
        }
    }

    private fun showWallpaperSettings() {
        val options = listOf(
            HomeSheet.Choice(getString(R.string.wallpaper_source), getString(wallpaper.source.label)),
            HomeSheet.Choice(getString(R.string.wallpaper_frequency), getString(wallpaper.interval.label)),
            HomeSheet.Choice(getString(R.string.wallpaper_refresh))
        )
        homeSheet(getString(R.string.background_setting)).items(options) { choice ->
            when (choice) {
                0 -> homeSheet(getString(R.string.wallpaper_source))
                    .choices(WallpaperSource.entries.map { getString(it.label) }, wallpaper.source.ordinal) {
                        wallpaper.select(WallpaperSource.entries[it])
                    }
                1 -> homeSheet(getString(R.string.wallpaper_frequency))
                    .choices(WallpaperInterval.entries.map { getString(it.label) }, wallpaper.interval.ordinal) {
                        wallpaper.interval = WallpaperInterval.entries[it]
                    }
                2 -> wallpaper.refresh(manual = true)
            }
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
        homeSheet(getString(R.string.glass_setting)).choices(glassOptions(), glassIndex()) { choice ->
            prefs.edit().putInt("glass", glassLevels[choice]).apply()
            createHome()
            showApps()
            appSettings.requestFocus()
        }
    }

    private fun selectMotion() {
        val options = listOf(getString(R.string.motion_normal), getString(R.string.motion_reduced))
        homeSheet(getString(R.string.motion_setting)).choices(options, if (motion) 0 else 1) { choice ->
            prefs.edit().putBoolean("motion", choice == 0).apply()
            wallpaper.render()
        }
    }

    private fun refreshWeather(force: Boolean = false) {
        if (!widgets.uses(WidgetSource.Device.WEATHER)) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastWeatherRequest != 0L && now - lastWeatherRequest < 15 * 60 * 1000L) return
        lastWeatherRequest = now
        val location = city
        weatherWorker.execute {
            val result = runCatching { Weather.current(location) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || city != location) return@runOnUiThread
                conditions = result ?: conditions
                weatherLoading = false
                weatherStale = result == null
                showConditions()
                if (result == null) lastWeatherRequest = now - 14 * 60 * 1000L
            }
        }
    }

    private fun showConditions() {
        if (!widgets.uses(WidgetSource.Device.WEATHER)) return
        widgets.showWeather(city.name, conditions, weatherLoading, weatherStale)
    }

    private fun showForecast() {
        val current = conditions
        if (current == null || current.forecast.isEmpty()) {
            homeSheet(getString(R.string.weather)).message(
                getString(if (weatherLoading) R.string.loading_weather else R.string.weather_unavailable)
            )
            return
        }
        WeatherPanel(this, city.name, current, weatherStale, homeTheme).show()
    }

    private fun chooseCity() {
        homeSheet(getString(R.string.weather_city))
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
                homeSheet(getString(R.string.weather_city))
                    .choices(matches.map { it.name }) { index ->
                        city = matches[index]
                        prefs.edit().putString("city", city.name)
                            .putString("latitude", city.latitude.toString())
                            .putString("longitude", city.longitude.toString()).apply()
                        conditions = null
                        lastWeatherRequest = 0L
                        weatherLoading = true
                        weatherStale = false
                        showConditions()
                        refreshWeather(force = true)
                    }
            }
        }
    }

}
