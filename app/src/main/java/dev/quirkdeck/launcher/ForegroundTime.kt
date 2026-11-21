package dev.quirkdeck.launcher

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class ActiveApp(val id: String, val activity: String?)

internal class ForegroundTime(
    private val firstDay: LocalDate,
    private val zone: ZoneId,
    private val includedApps: Set<String>
) {
    private val totals = mutableMapOf<LocalDate, MutableMap<String, Long>>()
    private var active: ActiveApp? = null
    private var interactive = true
    private var locked = false
    private var since = firstDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun resume(id: String, activity: String?, at: Long) {
        advance(at)
        active = ActiveApp(id, activity)
    }

    fun pause(id: String, activity: String?, at: Long) {
        if (active != ActiveApp(id, activity)) return
        advance(at)
        active = null
    }

    fun screen(interactive: Boolean, at: Long) {
        advance(at)
        this.interactive = interactive
    }

    fun keyguard(locked: Boolean, at: Long) {
        advance(at)
        this.locked = locked
    }

    fun reset(at: Long) {
        advance(at)
        active = null
        interactive = true
        locked = false
    }

    fun finish(at: Long): Map<LocalDate, Map<String, Long>> {
        advance(at)
        return totals
    }

    private fun advance(at: Long) {
        if (at <= since) return
        val id = active?.id
        if (interactive && !locked && id != null && id in includedApps) {
            var start = maxOf(since, firstDay.atStartOfDay(zone).toInstant().toEpochMilli())
            while (start < at) {
                val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(at, nextDay)
                val apps = totals.getOrPut(date) { mutableMapOf() }
                apps[id] = (apps[id] ?: 0L) + (end - start)
                start = end
            }
        }
        since = at
    }
}
