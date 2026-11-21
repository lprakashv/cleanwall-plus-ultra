package dev.lprakashv.cleanwall

import android.app.Activity
import android.content.SharedPreferences
import android.graphics.drawable.Animatable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

internal enum class WallpaperInterval(val minutes: Int, val label: Int) {
    HALF_HOUR(30, R.string.wallpaper_30m),
    HOURLY(60, R.string.wallpaper_1h),
    SIX_HOURS(360, R.string.wallpaper_6h),
    TWELVE_HOURS(720, R.string.wallpaper_12h),
    DAILY(1440, R.string.wallpaper_daily),
    WEEKLY(10080, R.string.wallpaper_weekly);

    fun due(now: Long, last: Long) = last <= 0 || now < last || now - last >= minutes * 60_000L
}

internal class HomeWallpaper(
    private val context: Activity,
    private val prefs: SharedPreferences,
    private val motion: () -> Boolean
) {
    private companion object {
        const val CLEANUP_INTERVAL = 24 * 60 * 60_000L
        // An interrupted worker can outlive its Activity while a download closes.
        val cacheLock = Any()
    }

    private val bing = BingWallpaper(File(context.filesDir, "bing-wallpaper"))
    private val catalog = PublicWallpapers(context)
    private val online = OnlineWallpapers(context, prefs, catalog)
    private val worker = Executors.newSingleThreadExecutor()
    private var showing: WallpaperFrame? = null
    private var displayedSource: WallpaperSource? = null
    private var inFlight = false
    private var retryAt = 0L
    private var nextCleanupAt = 0L
    private var visible = false
    private var playingFile: File? = null
    private var failedVideo: File? = null
    private lateinit var poster: ImageView
    private lateinit var video: VideoView
    private lateinit var scrim: View
    private lateinit var credit: TextView

    var source: WallpaperSource
        get() = WallpaperSource.entries.firstOrNull { it.name == prefs.getString("wallpaper_source", null) }
            ?: if (prefs.getString("wallpaper_source", null) in setOf("PIXABAY", "NASA_ONLINE", "NASA")) WallpaperSource.COMMONS_ONLINE else null
            ?: if (prefs.getBoolean("bing", true)) WallpaperSource.BING else WallpaperSource.GRADIENT
        private set(value) { prefs.edit().putString("wallpaper_source", value.name).apply() }

    var interval: WallpaperInterval
        get() = WallpaperInterval.entries.firstOrNull { it.minutes == prefs.getInt("wallpaper_minutes", 1440) }
            ?: WallpaperInterval.DAILY
        set(value) {
            prefs.edit().putInt("wallpaper_minutes", value.minutes).apply()
            refresh()
        }

    val onlineMotion get() = prefs.getBoolean("online_wallpaper_motion", false)
    val onlineCategory get() = OnlineWallpapers.normalizeCategory(prefs.getString("online_wallpaper_category", null))
    private fun onlineConfig() = OnlineConfig(onlineCategory, onlineMotion)

    fun findOnlineCategories(query: String) = online.categories(query)

    fun setOnlineMotion(value: Boolean) = updateOnline { putBoolean("online_wallpaper_motion", value) }
    fun setOnlineCategory(value: String) = updateOnline { putString("online_wallpaper_category", value) }

    private fun updateOnline(update: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply {
            update()
            remove("wallpaper_checked_COMMONS_ONLINE")
            remove("wallpaper_index_COMMONS_ONLINE")
        }.apply()
        retryAt = 0
        if (source.isOnline()) {
            displayedSource = null
            render()
            refresh()
        }
    }

    fun layer(creditView: TextView) = FrameLayout(context).apply {
        stopVideo()
        if (::poster.isInitialized) (poster.drawable as? Animatable)?.stop()
        credit = creditView
        poster = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        video = VideoView(context).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            visibility = View.GONE
            setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
            setOnPreparedListener { player ->
                player.isLooping = true
                player.setVolume(0f, 0f)
            }
            setOnErrorListener { _, what, extra ->
                Log.w("HomeWallpaper", "Video playback failed ($what, $extra)")
                failedVideo = playingFile
                stopVideo()
                true
            }
        }
        scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xB016152D.toInt(), 0xB0211F3D.toInt(), 0xE012182C.toInt())
            )
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(poster, FrameLayout.LayoutParams(-1, -1))
        addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        addView(scrim, FrameLayout.LayoutParams(-1, -1))
        render()
    }

    fun select(value: WallpaperSource) {
        if (source == value) return
        source = value
        retryAt = 0
        failedVideo = null
        render()
        refresh()
    }

    fun resume() {
        visible = true
        render()
    }

    fun pause() {
        visible = false
        render()
    }

    fun close() {
        pause()
        worker.shutdownNow()
    }

    fun refresh(manual: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (visible && now >= nextCleanupAt) {
            nextCleanupAt = now + CLEANUP_INTERVAL
            worker.execute {
                synchronized(cacheLock) {
                    catalog.cleanCache()
                    online.cleanCache()
                    bing.cleanCache()
                }
            }
        }
        val selected = source
        val onlineConfig = onlineConfig()
        if (selected == WallpaperSource.GRADIENT) {
            if (manual) toast(R.string.wallpaper_choose_source)
            return
        }
        if (inFlight) {
            if (manual) toast(R.string.wallpaper_updating)
            return
        }
        if (!manual && now < retryAt) return
        val checkedKey = "wallpaper_checked_${selected.name}"
        val indexKey = "wallpaper_index_${selected.name}"
        val due = manual || interval.due(System.currentTimeMillis(), prefs.getLong(checkedKey, 0))
        if (!due && displayedSource == selected) return
        val currentIndex = prefs.getInt(indexKey, -1)
        val hasFrame = displayedSource == selected && showing != null
        inFlight = true
        if (manual) toast(R.string.wallpaper_updating)
        worker.execute {
            var nextIndex = currentIndex
            val result = runCatching {
                synchronized(cacheLock) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    if (selected == WallpaperSource.BING) {
                        val cached = bing.cached()
                        if (!hasFrame) offerCached(selected, cached?.let { WallpaperFrame(it.bitmap, it.credit) })
                        val photo = (if (due || cached == null) bing.fetch(cached?.date) else null) ?: cached
                        photo?.let { WallpaperFrame(it.bitmap, it.credit) } ?: throw IOException("No Bing wallpaper")
                    } else if (selected.isOnline()) {
                        val cached = if (hasFrame) null else online.cached(selected, onlineConfig)
                        offerCached(selected, cached, onlineConfig)
                        if (!due && cached != null) cached else {
                            val loaded = online.load(selected, onlineConfig, currentIndex)
                            nextIndex = loaded.second
                            loaded.first
                        }
                    } else {
                        val cached = if (hasFrame) null else catalog.cached(selected, currentIndex)
                        offerCached(selected, cached)
                        nextIndex = if (due || !catalog.hasIndex(selected, currentIndex)) catalog.next(selected, currentIndex) else currentIndex
                        if (!due && cached != null) cached else catalog.load(selected, nextIndex)
                    }
                }
            }
            context.runOnUiThread {
                if (context.isDestroyed) return@runOnUiThread
                inFlight = false
                if (source != selected || (selected.isOnline() && onlineConfig != onlineConfig())) {
                    if (visible) refresh()
                    return@runOnUiThread
                }
                result.onSuccess { frame ->
                    showing = frame
                    displayedSource = selected
                    retryAt = 0
                    val edit = prefs.edit().putInt(indexKey, nextIndex)
                    if (due) edit.putLong(checkedKey, System.currentTimeMillis())
                    edit.apply()
                    render()
                    if (manual) toast(R.string.wallpaper_updated)
                }.onFailure { error ->
                    Log.w("HomeWallpaper", "Could not load ${selected.name} wallpaper", error)
                    retryAt = SystemClock.elapsedRealtime() + 5 * 60_000L
                    if (manual || displayedSource != selected) toast(R.string.wallpaper_failed)
                }
            }
        }
    }

    private fun offerCached(selected: WallpaperSource, frame: WallpaperFrame?, config: OnlineConfig? = null) {
        if (frame == null) return
        context.runOnUiThread {
            if (!context.isDestroyed && source == selected && (config == null || config == onlineConfig())) {
                showing = frame
                displayedSource = selected
                render()
            }
        }
    }

    fun render() {
        if (!::poster.isInitialized) return
        val frame = showing.takeIf { displayedSource == source }
        val animate = visible && motion()
        val animation = frame?.animation?.takeIf { animate }
        if (animation == null || poster.drawable !== animation) {
            (poster.drawable as? Animatable)?.stop()
            if (animation == null) poster.setImageBitmap(frame?.poster) else poster.setImageDrawable(animation)
        }
        (animation as? Animatable)?.start()
        val file = frame?.video?.takeIf { animate && it != failedVideo }
        if (file != playingFile) {
            stopVideo()
            if (file != null) {
                playingFile = file
                video.setVideoPath(file.path)
                video.visibility = View.VISIBLE
                video.start()
            }
        }
        scrim.visibility = if (frame == null) View.GONE else View.VISIBLE
        credit.text = frame?.credit.orEmpty()
        credit.visibility = if (frame == null) View.GONE else View.VISIBLE
    }

    private fun stopVideo() {
        if (!::video.isInitialized) return
        if (playingFile != null) video.suspend()
        video.visibility = View.GONE
        playingFile = null
    }

    private fun toast(message: Int) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
