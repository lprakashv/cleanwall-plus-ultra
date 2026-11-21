package dev.lprakashv.cleanwall

import android.content.ContentResolver
import android.content.Intent
import android.media.tv.TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME
import android.media.tv.TvContract.WatchNextPrograms
import android.os.CancellationSignal
import java.net.URISyntaxException

internal object ContinueWatching {
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    const val PER_APP_LIMIT = 5
    const val ROW_LIMIT = 10

    data class Entry(
        val appId: String,
        val title: String,
        val episode: String?,
        val link: String,
        val type: Int,
        val position: Long,
        val duration: Long,
        val thumbnail: String? = null,
        val poster: String? = null
    ) {
        val key get() = appId to link
        val isNextEpisode get() = type == WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
        val progress: Int?
            get() = if (!isNextEpisode && position > 0 && duration > 0) {
                (100.0 * position / duration).toInt().coerceIn(1, 100)
            } else null

        fun playbackIntent(): Intent? {
            val intent = try {
                Intent.parseUri(link, Intent.URI_INTENT_SCHEME)
            } catch (_: URISyntaxException) {
                return null
            }
            if (intent.selector != null || intent.`package`?.let { it != appId } == true ||
                intent.component?.let { it.packageName != appId } == true) return null
            return intent.setPackage(appId).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun select(entries: Sequence<Entry>, apps: Set<String>, sources: Set<String>? = null): List<Entry> = buildList {
        val counts = mutableMapOf<String, Int>()
        val keys = mutableSetOf<Pair<String, String>>()
        for (entry in entries) {
            if (entry.appId !in apps || (sources != null && entry.appId !in sources)) continue
            if (entry.title.isBlank() || entry.link.isBlank()) continue
            if (!entry.isNextEpisode && entry.type != WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE) continue
            if (!entry.isNextEpisode && entry.duration > 0 && entry.position.toDouble() / entry.duration >= 0.98) continue
            val count = counts.getOrDefault(entry.appId, 0)
            if (count >= PER_APP_LIMIT || !keys.add(entry.key)) continue
            counts[entry.appId] = count + 1
            add(entry)
        }
    }

    fun read(resolver: ContentResolver, apps: Set<String>, signal: CancellationSignal): List<Entry> {
        val columns = arrayOf(
            COLUMN_PACKAGE_NAME, WatchNextPrograms.COLUMN_TITLE, WatchNextPrograms.COLUMN_EPISODE_TITLE,
            WatchNextPrograms.COLUMN_INTENT_URI, WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, WatchNextPrograms.COLUMN_DURATION_MILLIS,
            WatchNextPrograms.COLUMN_THUMBNAIL_URI, WatchNextPrograms.COLUMN_POSTER_ART_URI
        )
        // TvProvider allows searchable cross-app reads without a SQL selection.
        return resolver.query(
            WatchNextPrograms.CONTENT_URI, columns, null, null,
            "${WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC", signal
        )?.use { cursor ->
            val entries = sequence {
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    if (cursor.isNull(4)) continue
                    yield(Entry(
                        cursor.getString(0).orEmpty(), cursor.getString(1).orEmpty(),
                        cursor.getString(2), cursor.getString(3).orEmpty(),
                        cursor.getInt(4), cursor.getLong(5), cursor.getLong(6),
                        cursor.getString(7), cursor.getString(8)
                    ))
                }
            }
            select(entries, apps)
        }.orEmpty()
    }
}
