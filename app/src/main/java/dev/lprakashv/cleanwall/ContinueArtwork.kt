package dev.lprakashv.cleanwall

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class ContinueArtwork(private val resolver: ContentResolver, private val handler: Handler) {
    companion object {
        fun sampleSize(imageWidth: Int, imageHeight: Int, width: Int, height: Int): Int {
            var sample = 1
            while ((imageWidth / (sample * 2) >= width && imageHeight / (sample * 2) >= height) ||
                imageWidth / sample > 2048 || imageHeight / sample > 2048
            ) sample *= 2
            return sample
        }
    }
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        ThreadPoolExecutor.DiscardPolicy())
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var active = true

    fun show(view: ImageView, uris: List<String>, width: Int, height: Int) {
        if (uris.isEmpty()) return
        worker.execute {
            val image = uris.firstNotNullOfOrNull { uri ->
                val key = "$uri:$width:$height"
                cache.get(key) ?: runCatching { load(uri, width, height) }.getOrNull()?.also { cache.put(key, it) }
            }
            if (image != null) handler.post {
                if (active && view.isAttachedToWindow) {
                    view.scaleType = ImageView.ScaleType.CENTER_CROP
                    view.setImageBitmap(image)
                }
            }
        }
    }

    fun close() {
        active = false
        worker.shutdownNow()
        cache.evictAll()
    }

    private fun load(value: String, width: Int, height: Int): Bitmap? {
        val uri = Uri.parse(value)
        val bytes = when (uri.scheme) {
            "https", "http" -> {
                val connection = URL(value).openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                try {
                    if (connection.responseCode != 200) return null
                    connection.inputStream.use(::read)
                } finally {
                    connection.disconnect()
                }
            }
            "content", "android.resource" -> resolver.openInputStream(uri)?.use(::read) ?: return null
            else -> return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return out.toByteArray()
            require(out.size() + count <= 4 * 1024 * 1024)
            out.write(buffer, 0, count)
        }
    }
}
