package dev.quirkdeck.launcher

import android.content.Context

internal fun activeDuration(context: Context, millis: Long, compact: Boolean = false): String {
    val seconds = millis / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 && compact -> context.getString(R.string.duration_hours_short, hours)
        hours > 0 -> context.getString(R.string.duration_hours, hours, minutes % 60)
        minutes > 0 -> context.getString(R.string.duration_minutes, minutes)
        else -> context.getString(R.string.duration_seconds, seconds)
    }
}
