package dev.lprakashv.cleanwall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal class BingWallpaper(private val file: File) {
    data class Photo(val date: String, val credit: String, val bitmap: Bitmap)

    private val cache = AtomicFile(file)

    fun cleanCache() {
        // Opening the file lets AtomicFile recover an older backup before we remove an unfinished write.
        runCatching { cache.openRead().close() }
        val unfinished = File(file.path + ".new")
        if (unfinished.isFile) unfinished.delete()
    }

    fun cached(): Photo? = runCatching {
        DataInputStream(cache.openRead()).use { input ->
            val date = input.readUTF()
            val credit = input.readUTF()
            val bitmap = BitmapFactory.decodeStream(input) ?: return null
            Photo(date, credit, bitmap)
        }
    }.getOrNull()

    fun fetch(currentDate: String?): Photo? {
        val metadata = download("/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=en-IN") {
            JSONObject(String(readBounded(it, 64 * 1024), Charsets.UTF_8))
        }.getJSONArray("images").getJSONObject(0)
        val date = metadata.getString("startdate")
        if (date == currentDate) return null
        val path = metadata.getString("url")
        require(path.startsWith("/th?"))
        val bytes = download(path) { readBounded(it, 4 * 1024 * 1024) }
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        require(dimensions.outWidth in 1..1920 && dimensions.outHeight in 1..1080)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: throw IOException("Invalid Bing wallpaper")
        val credit = metadata.getString("copyright").take(500)
        val output = cache.startWrite()
        try {
            DataOutputStream(output).apply {
                writeUTF(date)
                writeUTF(credit)
                write(bytes)
                flush()
            }
            cache.finishWrite(output)
        } catch (error: IOException) {
            cache.failWrite(output)
        }
        return Photo(date, credit, bitmap)
    }

    private fun <T> download(path: String, read: (InputStream) -> T): T {
        val connection = URL("https://www.bing.com$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        return try {
            if (connection.responseCode != 200) throw IOException("Bing returned ${connection.responseCode}")
            connection.inputStream.use(read)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return out.toByteArray()
            if (out.size() + count > limit) throw IOException("Bing response exceeded $limit bytes")
            out.write(buffer, 0, count)
        }
    }
}
