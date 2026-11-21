package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperIntervalTest {
    @Test fun supportsEveryRefreshIntervalAndHandlesClockChanges() {
        val last = 1_000_000L
        assertEquals(listOf(30, 60, 360, 720, 1440, 10080), WallpaperInterval.entries.map { it.minutes })
        WallpaperInterval.entries.forEach { interval ->
            val next = last + interval.minutes * 60_000L
            assertFalse(interval.due(next - 1, last))
            assertTrue(interval.due(next, last))
            assertTrue(interval.due(last, 0))
            assertTrue(interval.due(last - 1, last))
        }
    }
}
