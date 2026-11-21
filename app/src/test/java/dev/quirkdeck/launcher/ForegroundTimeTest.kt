package dev.quirkdeck.launcher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ForegroundTimeTest {
    private val day = LocalDate.parse("2026-11-21")
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test fun appSwitchPausesOldAppEvenWhenEventsOverlap() {
        val start = instant("2026-11-21T09:00:00Z")
        val tracker = ForegroundTime(day, zone, setOf("player", "store"))
        tracker.resume("player", "Main", start)
        tracker.resume("store", "Main", start + 8_000)
        tracker.pause("player", "Main", start + 8_100)
        tracker.resume("home", "Main", start + 10_000)
        assertEquals(mapOf("player" to 8_000L, "store" to 2_000L), tracker.finish(start + 80_000)[day])
    }

    @Test fun splitsAtLocalMidnightAndExcludesScreenOffAndLockTime() {
        val midnight = instant("2026-11-21T18:30:00Z")
        val tracker = ForegroundTime(day, zone, setOf("player"))
        tracker.resume("player", "Main", midnight - 5_000)
        tracker.screen(false, midnight + 4_000)
        tracker.keyguard(true, midnight + 5_000)
        tracker.screen(true, midnight + 8_000)
        tracker.keyguard(false, midnight + 9_000)
        tracker.reset(midnight + 12_000)

        assertEquals(
            mapOf(day to mapOf("player" to 5_000L), day.plusDays(1) to mapOf("player" to 7_000L)),
            tracker.finish(midnight + 60_000)
        )
    }

    @Test fun includesCarryInWithoutCountingBeforeTheWindow() {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val tracker = ForegroundTime(day, zone, setOf("player"))
        tracker.resume("player", "Main", start - 30_000)
        tracker.pause("player", "Main", start + 2_000)
        assertEquals(mapOf(day to mapOf("player" to 2_000L)), tracker.finish(start + 5_000))
    }

    private fun instant(value: String) = Instant.parse(value).toEpochMilli()
}
