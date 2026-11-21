package dev.lprakashv.cleanwall

import android.app.AppOpsManager
import android.app.usage.UsageEvents.Event
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

internal class AndroidActivityTime(private val context: Context) {
    data class Week(val days: Map<LocalDate, Map<String, Long>>, val todayBlocks: Map<TimeBlock, Long>)
    @Suppress("DEPRECATION")
    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun week(apps: Set<String>): Week? {
        if (!hasAccess()) return null
        return try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val firstDay = today.minusDays(6)
            val begin = firstDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val now = System.currentTimeMillis()
            val tracker = ForegroundTime(firstDay, zone, apps - context.packageName)
            val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(begin, now)
            val event = Event()
            while (events?.hasNextEvent() == true && events.getNextEvent(event)) {
                val at = event.timeStamp
                when (event.eventType) {
                    Event.ACTIVITY_RESUMED -> event.packageName?.let { tracker.resume(it, event.className, at) }
                    Event.ACTIVITY_PAUSED, Event.ACTIVITY_STOPPED -> event.packageName?.let { tracker.pause(it, event.className, at) }
                    Event.SCREEN_INTERACTIVE -> tracker.screen(true, at)
                    Event.SCREEN_NON_INTERACTIVE -> tracker.screen(false, at)
                    Event.KEYGUARD_SHOWN -> tracker.keyguard(true, at)
                    Event.KEYGUARD_HIDDEN -> tracker.keyguard(false, at)
                    Event.DEVICE_SHUTDOWN, Event.DEVICE_STARTUP -> tracker.reset(at)
                }
            }
            Week(tracker.finish(now), tracker.blocks(today))
        } catch (_: SecurityException) {
            null
        }
    }
}
