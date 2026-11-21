package dev.lprakashv.cleanwall

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class ActiveApp(val id: String, val activity: String?)
internal enum class TimeBlock(val label: Int, val icon: Int) {
    MORNING(R.string.mornings, R.drawable.ic_sunrise),
    DAYTIME(R.string.daytime, R.drawable.ic_sun),
    EVENING(R.string.evenings, R.drawable.ic_sunset),
    LATE_NIGHT(R.string.late_nights, R.drawable.ic_moon);

    companion object {
        fun atHour(hour: Int): TimeBlock = when (hour) {
            in 6..11 -> MORNING
            in 12..17 -> DAYTIME
            in 18..21 -> EVENING
            else -> LATE_NIGHT
        }
    }
}

internal class ForegroundTime(
    firstDay: LocalDate,
    private val zone: ZoneId,
    private val includedApps: Set<String>
) {
    private val totals = mutableMapOf<LocalDate, MutableMap<String, Long>>()
    private val blockTotals = mutableMapOf<LocalDate, MutableMap<TimeBlock, Long>>()
    private var active: ActiveApp? = null
    private var interactive = true
    private var locked = false
    private val firstDayStart = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
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

    fun blocks(date: LocalDate): Map<TimeBlock, Long> = blockTotals[date].orEmpty()

    private fun advance(at: Long) {
        if (at <= since) return
        val id = active?.id
        if (interactive && !locked && id != null && id in includedApps) {
            var start = maxOf(since, firstDayStart)
            while (start < at) {
                val local = Instant.ofEpochMilli(start).atZone(zone)
                val date = local.toLocalDate()
                val nextDay = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val block = TimeBlock.atHour(local.hour)
                val untilHour = when (block) {
                    TimeBlock.MORNING -> 12
                    TimeBlock.DAYTIME -> 18
                    TimeBlock.EVENING -> 22
                    TimeBlock.LATE_NIGHT -> if (local.hour < 6) 6 else 24
                }
                val boundary = if (untilHour == 24) nextDay else
                    date.atTime(untilHour, 0).atZone(zone).toInstant().toEpochMilli()
                val end = minOf(at, if (boundary > start) boundary else nextDay)
                val elapsed = end - start
                val apps = totals.getOrPut(date) { mutableMapOf() }
                apps[id] = (apps[id] ?: 0L) + elapsed
                val blocks = blockTotals.getOrPut(date) { mutableMapOf() }
                blocks[block] = (blocks[block] ?: 0L) + elapsed
                start = end
            }
        }
        since = at
    }
}
