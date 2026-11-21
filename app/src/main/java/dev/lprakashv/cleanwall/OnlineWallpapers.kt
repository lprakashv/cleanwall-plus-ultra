package dev.lprakashv.cleanwall

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.text.Html
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class OnlineConfig(val category: String, val motion: Boolean)
internal fun WallpaperSource.isOnline() = this == WallpaperSource.COMMONS_ONLINE

internal class OnlineWallpapers(context: Context, private val prefs: SharedPreferences, private val media: PublicWallpapers) {
    private val directory = File(context.filesDir, "online-wallpaper")
    private val retiredDirectory = File(context.filesDir, "pixabay-wallpaper")
    private data class Candidate(val url: String, val credit: String, val extension: String)

    fun cached(source: WallpaperSource, config: OnlineConfig): WallpaperFrame? = runCatching {
        if (prefs.getString("online_cache_source", null) != source.name ||
            normalizeCategory(prefs.getString("online_cache_category", null)) != config.category ||
            prefs.getBoolean("online_cache_motion", false) != config.motion) return null
        val name = prefs.getString("online_cache_file", null) ?: return null
        if (!name.matches(Regex("[0-9]+\\.(jpg|webm|mp4)"))) return null
        media.decode(File(directory, name), prefs.getString("online_cache_credit", "") ?: "")
    }.getOrNull()

    fun load(source: WallpaperSource, config: OnlineConfig, index: Int): Pair<WallpaperFrame, Int> {
        val deadline = SystemClock.elapsedRealtime() + 90_000
        for (featured in if (config.motion) listOf(true, false) else listOf(true)) {
            val search = commonsSearch(config, featured)
            val pages = search.optJSONObject("query")?.optJSONArray("pages") ?: continue
            for (offset in 1..pages.length()) {
                if (SystemClock.elapsedRealtime() > deadline) throw IOException("Wallpaper search timed out")
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val next = (index + offset).mod(pages.length())
                val candidate = commonsCandidate(search, next, config) ?: continue
                directory.mkdirs()
                val target = File(directory, "${System.nanoTime().ushr(1)}.${candidate.extension}")
                val temp = File.createTempFile("download-", ".${candidate.extension}", directory)
                try {
                    media.download(candidate.url, temp)
                    val frame = media.decode(temp, candidate.credit)
                    if (!temp.renameTo(target)) throw IOException("Cannot save wallpaper")
                    prefs.edit().putString("online_cache_source", source.name).putString("online_cache_category", config.category)
                        .putBoolean("online_cache_motion", config.motion).putString("online_cache_file", target.name)
                        .putString("online_cache_credit", candidate.credit).apply()
                    pruneCache(directory, target.name)
                    return (if (frame.video == null) frame else frame.copy(video = target)) to next
                } catch (error: IOException) {
                    Log.w("OnlineWallpapers", "Could not load a wallpaper candidate", error)
                } catch (error: IllegalArgumentException) {
                    Log.w("OnlineWallpapers", "Cannot decode a wallpaper candidate", error)
                } finally {
                    temp.delete()
                }
            }
        }
        throw IOException("No suitable online wallpapers")
    }

    fun cleanCache() {
        val current = prefs.getString("online_cache_file", null)
            .takeIf { prefs.getString("online_cache_source", null) == WallpaperSource.COMMONS_ONLINE.name }
        pruneCache(directory, current)
        pruneCache(retiredDirectory, null)
    }

    fun categories(query: String): List<String> {
        val params = "action=query&format=json&formatversion=2&list=search&srnamespace=14&srlimit=30" +
            "&srsearch=${encode("intitle:$query")}"
        val results = JSONObject(read("https://commons.wikimedia.org/w/api.php?$params"))
            .optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until results.length()).mapNotNull { index ->
            results.optJSONObject(index)?.optString("title")?.removePrefix("Category:")?.takeIf { it.isNotBlank() }
        }
    }

    private fun commonsSearch(config: OnlineConfig, featured: Boolean): JSONObject {
        val category = config.category.replace("\"", "").replace("\\", "")
        val group = if (config.motion) "Featured media" else "Featured pictures on Wikimedia Commons"
        val quality = if (featured) " deepcategory:\"$group\"" else ""
        val query = "deepcategory:\"$category\" -sexual -nude -pornography incategory:\"CC-Zero\"$quality filetype:${if (config.motion) "video" else "bitmap"}"
        val params = "action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit=40" +
            "&gsrsearch=${encode(query)}&prop=imageinfo%7Cvideoinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata" +
            "&viprop=url%7Csize%7Cmime%7Cderivatives&iiurlwidth=1920"
        return JSONObject(read("https://commons.wikimedia.org/w/api.php?$params"))
    }

    private fun commonsCandidate(data: JSONObject, index: Int, config: OnlineConfig): Candidate? {
        val page = data.getJSONObject("query").getJSONArray("pages").getJSONObject(index)
        val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return null
        val meta = info.optJSONObject("extmetadata") ?: return null
        val license = meta.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
        val url = if (config.motion) {
            val video = page.optJSONArray("videoinfo")?.optJSONObject(0) ?: return null
            val seconds = video.optDouble("duration", 0.0)
            if (seconds !in 0.001..30.0 || license != "CC0") return null
            val variants = video.optJSONArray("derivatives")
            val best = (0 until (variants?.length() ?: 0)).mapNotNull { variants?.optJSONObject(it) }
                .filter {
                    val bandwidth = it.optLong("bandwidth")
                    it.optString("type").startsWith("video/webm") && bandwidth > 0 &&
                        suitableCommons(true, license, "video/webm", it.optInt("width"), it.optInt("height"),
                            (bandwidth * seconds / 8).toLong())
                }.maxByOrNull { it.optInt("width") * it.optInt("height") }
            best?.optString("src") ?: info.optString("url").takeIf {
                suitableCommons(true, license, info.optString("mime"), info.optInt("width"), info.optInt("height"), info.optLong("size"))
            } ?: return null
        } else {
            if (!suitableCommons(false, license, info.optString("mime"), info.optInt("width"), info.optInt("height"), info.optLong("size"))) return null
            info.optString("thumburl")
        }
        if (!allowedUrl(url, "wikimedia.org")) return null
        val author = Html.fromHtml(meta.optJSONObject("Artist")?.optString("value").orEmpty(), Html.FROM_HTML_MODE_LEGACY).toString().trim().take(100)
        val title = page.optString("title").removePrefix("File:").take(90)
        return Candidate(url, "$title · $author · CC0 · Wikimedia Commons", if (config.motion) "webm" else "jpg")
    }

    private fun read(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000; connection.readTimeout = 12_000; connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "CleanWallPlusUltra/1.6 (https://github.com/lprakashv/cleanwall-plus-ultra)")
        try {
            if (connection.responseCode != 200) throw IOException("Wallpaper API returned ${connection.responseCode}")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 2 * 1024 * 1024 || Thread.currentThread().isInterrupted) throw IOException("Wallpaper response too large")
                    output.write(buffer, 0, count)
                }
            }
            return output.toString("UTF-8")
        } finally { connection.disconnect() }
    }

    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

        fun normalizeCategory(value: String?) = when (value) {
            null, "places" -> "Landscapes"
            "nature" -> "Nature"
            "animals" -> "Animals"
            "space" -> "Outer space"
            else -> value
        }
        private val cacheName = Regex("(?:[0-9]+(?:-[0-9]+)?|download-[0-9]+)\\.(?:jpg|webm|mp4)")

        fun pruneCache(directory: File, current: String?) {
            directory.listFiles()?.forEach { file ->
                if (file.name != current && file.isFile && cacheName.matches(file.name)) file.delete()
            }
        }

        fun suitableCommons(motion: Boolean, license: String, mime: String, width: Int, height: Int, size: Long): Boolean {
            if (license != "CC0" || width < 1280 || height < 720 || width <= height) return false
            return if (motion) mime == "video/webm" && width <= 1920 && height <= 1080 && size in 1..48L * 1024 * 1024
                else mime == "image/jpeg"
        }

        fun allowedUrl(value: String, domain: String): Boolean = runCatching {
            val url = URL(value)
            url.protocol == "https" && (url.host == domain || url.host.endsWith(".$domain")) &&
                url.userInfo == null && (url.port == -1 || url.port == 443)
        }.getOrDefault(false)
    }
}
