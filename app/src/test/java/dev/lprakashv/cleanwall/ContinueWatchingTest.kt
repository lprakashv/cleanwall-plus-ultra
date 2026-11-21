package dev.lprakashv.cleanwall

import android.media.tv.TvContract.WatchNextPrograms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingTest {
    private val first = ContinueWatching.Entry(
        "first", "Episode", null, "stream://episode/1",
        WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, 25, 100
    )
    private val second = first.copy(appId = "second", type = WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)

    @Test
    fun onlyVisibleSelectedAppsAppear() {
        val entries = sequenceOf(first, second, first.copy(appId = "hidden"))
        val apps = setOf("first", "second")
        assertEquals(listOf(first, second), ContinueWatching.select(entries, apps))
        assertEquals(listOf(second), ContinueWatching.select(entries, apps, setOf("second", "hidden")))
        assertEquals(emptyList<ContinueWatching.Entry>(), ContinueWatching.select(entries, apps, emptySet()))
    }

    @Test
    fun keepsNewestDistinctPlayableEntriesWithinEachAppLimit() {
        val episodes = (2..7).map { first.copy(link = "stream://episode/$it") }
        val entries = listOf(
            first.copy(type = WatchNextPrograms.WATCH_NEXT_TYPE_NEW), first.copy(link = ""),
            first, first.copy(position = 20)
        ) + episodes + second
        assertEquals(
            listOf(first) + episodes.take(ContinueWatching.PER_APP_LIMIT - 1) + second,
            ContinueWatching.select(entries.asSequence(), setOf("first", "second"))
        )
    }

    @Test
    fun showsProgressOnlyForContinuedTitlesWithTiming() {
        assertEquals(25, first.progress)
        assertEquals(100, first.copy(position = 110).progress)
        assertNull(first.copy(duration = 0).progress)
        assertNull(second.progress)
    }

    @Test
    fun filtersAtNinetyEightPercentWithoutLosingNextEpisodesOrUnknownProgress() {
        val almost = first.copy(link = "almost", position = 9799, duration = 10000)
        val complete = first.copy(link = "complete", position = 9800, duration = 10000)
        val beyond = first.copy(link = "beyond", position = 120, duration = 100)
        val unknown = first.copy(link = "unknown", position = 99, duration = 0)
        val next = second.copy(link = "next", position = 100, duration = 100)
        assertEquals(
            listOf(almost, unknown, next),
            ContinueWatching.select(sequenceOf(complete, beyond, almost, unknown, next), setOf("first", "second"))
        )
    }
}
