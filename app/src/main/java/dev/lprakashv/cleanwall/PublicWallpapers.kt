package dev.lprakashv.cleanwall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal enum class WallpaperSource(val label: Int) {
    BING(R.string.bing_daily),
    COMMONS(R.string.commons_wallpaper),
    LOOPS(R.string.loop_wallpaper),
    COMMONS_ONLINE(R.string.commons_online_wallpaper),
    GRADIENT(R.string.dark_background)
}

internal data class WallpaperFrame(
    val poster: Bitmap,
    val credit: String,
    val animation: Drawable? = null,
    val video: File? = null
)

internal class PublicWallpapers(private val context: Context) {
    private data class Asset(val name: String, val credit: String)
    private val directory = File(context.filesDir, "public-wallpapers")

    private val collections = mapOf(
        WallpaperSource.COMMONS to listOf(
            Asset("lake-mountain.jpg", "Lake Mountain Landscape · Bonnie Moreland · CC0"),
            Asset("mountain-sunset.jpg", "Mountain Lake Sunset · 420 Photography · CC0")
        ),
        WallpaperSource.LOOPS to listOf(
            Asset("small-waterfall.mp4", "Small waterfall · YouTube Editor · CC BY 3.0")
        )
    )

    fun cleanCache() {
        val current = collections.flatMap { (source, assets) ->
            assets.mapIndexed { index, asset -> cacheFile(source, index, asset).name }
        }.toSet()
        pruneCache(directory, current)
    }

    fun hasIndex(source: WallpaperSource, index: Int) = index in collections.getValue(source).indices

    fun next(source: WallpaperSource, current: Int): Int = nextIndex(current, collections.getValue(source).size)

    fun cached(source: WallpaperSource, index: Int): WallpaperFrame? {
        val asset = collections.getValue(source).getOrNull(index) ?: return null
        val file = cacheFile(source, index, asset)
        return if (file.isFile) runCatching { decode(file, asset.credit) }.getOrNull() else null
    }

    fun load(source: WallpaperSource, index: Int): WallpaperFrame {
        cached(source, index)?.let { return it }
        val asset = collections.getValue(source)[index]
        val target = cacheFile(source, index, asset)
        directory.mkdirs()
        val temporary = File.createTempFile("wallpaper-", ".${target.extension}", directory)
        try {
            context.assets.open("wallpapers/${asset.name}").use { input ->
                temporary.outputStream().use { input.copyTo(it) }
            }
            val frame = decode(temporary, asset.credit)
            if (!temporary.renameTo(target)) throw IOException("Could not save the wallpaper")
            return if (frame.video == null) frame else frame.copy(video = target)
        } finally {
            temporary.delete()
        }
    }

    private fun cacheFile(source: WallpaperSource, index: Int, asset: Asset) =
        File(directory, "${source.name.lowercase()}-$index.${asset.name.substringAfterLast('.')}")

    fun download(url: String, file: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "CleanWallPlusUltra/1.6 (https://github.com/lprakashv/cleanwall-plus-ultra)")
        val limit = if (file.extension in setOf("mp4", "webm")) 48L * 1024 * 1024 else 12L * 1024 * 1024
        val deadline = SystemClock.elapsedRealtime() + 90_000
        try {
            if (connection.responseCode != 200 || connection.contentLengthLong > limit) {
                throw IOException("Wallpaper server returned ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > limit || SystemClock.elapsedRealtime() > deadline || Thread.currentThread().isInterrupted) {
                            throw IOException("Wallpaper download exceeded its limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun decode(file: File, credit: String): WallpaperFrame {
        if (file.extension in setOf("mp4", "webm")) {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.path)
                val width = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val height = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                require(width in 1..1920 && height in 1..1080 && shortVideo(duration))
                val poster = reader.getFrameAtTime(0) ?: throw IOException("Cannot read video poster")
                return WallpaperFrame(poster, credit, video = file)
            } finally {
                reader.release()
            }
        }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, dimensions)
        require(dimensions.outWidth in 1..12000 && dimensions.outHeight in 1..12000)
        val sample = BitmapFactory.Options().apply {
            while (dimensions.outWidth / inSampleSize.coerceAtLeast(1) > 2560 ||
                dimensions.outHeight / inSampleSize.coerceAtLeast(1) > 1440
            ) inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
        val poster = BitmapFactory.decodeFile(file.path, sample) ?: throw IOException("Cannot read wallpaper image")
        val animation = if (file.extension == "gif" && Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, _, _ ->
                decoder.setTargetSampleSize(sample.inSampleSize.coerceAtLeast(1))
            }.also { if (it is AnimatedImageDrawable) it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
        } else null
        return WallpaperFrame(poster, credit, animation)
    }

    companion object {
        private val cacheName = Regex("""(?:wallpaper|nasa|commons|loops)-[0-9]+\.[a-z0-9]+""")

        fun shortVideo(duration: Long) = duration in 1..30_000

        fun nextIndex(current: Int, size: Int) = if (current !in 0 until size) 0 else (current + 1) % size

        fun pruneCache(directory: File, current: Set<String>) {
            directory.listFiles()?.forEach { file ->
                if (file.name !in current && cacheName.matches(file.name) && file.isFile) file.delete()
            }
        }
    }
}
