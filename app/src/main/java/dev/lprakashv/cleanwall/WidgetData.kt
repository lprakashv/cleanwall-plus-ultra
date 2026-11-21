package dev.lprakashv.cleanwall

internal const val WIDGET_GAP_DP = 4

internal sealed interface WidgetSource {
    enum class Device : WidgetSource { ACTIVITY, DATE, WEATHER }

    data class App(val packageName: String) : WidgetSource

    class Remote(
        val hooks: Set<Hook> = setOf(Hook.SHOW, Hook.INTERVAL),
        private val intervalMillis: Long = 15 * 60_000L,
        val load: () -> Map<String, String>
    ) : WidgetSource {
        init {
            require(intervalMillis >= 60_000)
        }

        fun intervalDelay(now: Long, started: Long?): Long? {
            if (Hook.INTERVAL !in hooks) return null
            return if (started == null) 0 else (intervalMillis - (now - started)).coerceAtLeast(0)
        }
    }

    enum class Hook { SHOW, INTERVAL, SELECT }
}

internal data class WidgetContent(val text: String, val detail: String? = null, val icon: String? = null)

internal data class WidgetSpec(
    val title: Int,
    val source: WidgetSource? = null,
    val minWidthDp: Int = 200,
    val style: WidgetStyle = WidgetStyle.STANDARD,
    val action: WidgetAction = WidgetAction.DETAILS,
    val iconAtEnd: Boolean = false,
    val animate: Boolean = false,
    val activityComponents: Set<ActivityComponent> = emptySet(),
    val content: (Map<String, String>) -> WidgetContent
) {
    init {
        require(minWidthDp > 0)
        require(activityComponents.isEmpty() || source == WidgetSource.Device.ACTIVITY)
        when (action) {
            WidgetAction.WEATHER_FORECAST -> require(source == WidgetSource.Device.WEATHER)
            WidgetAction.APP_ACTIVITY -> require(source == WidgetSource.Device.ACTIVITY)
            WidgetAction.OPEN_APP -> require(source is WidgetSource.App)
            WidgetAction.REFRESH -> require(source is WidgetSource.Remote && WidgetSource.Hook.SELECT in source.hooks)
            WidgetAction.DETAILS, WidgetAction.NONE -> Unit
        }
    }

    fun widthForViewport(viewportWidthDp: Int): Int =
        maxOf(minWidthDp, (viewportWidthDp - WIDGET_GAP_DP) / 2)
}

internal enum class ActivityComponent { DETAIL_ICON, CHART, ROWS }
internal enum class WidgetAction { DETAILS, REFRESH, NONE, WEATHER_FORECAST, APP_ACTIVITY, OPEN_APP }

internal enum class WidgetStyle(val size: Float, val lines: Int, val bold: Boolean = true) {
    STANDARD(22f, 2),
    READING(26f, 1),
    HEADLINE(32f, 1),
    COMPACT(24f, 1),
    NOTE(18f, 3, bold = false)
}
