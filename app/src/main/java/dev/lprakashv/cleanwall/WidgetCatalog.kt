package dev.lprakashv.cleanwall

internal enum class WidgetId(val key: String) {
    WEATHER("weather"), ACTIVITY("activity"), DATE("date")
}

internal object WidgetCatalog {
    val defaults = listOf(WidgetId.WEATHER, WidgetId.ACTIVITY)
    val available = mapOf(
        WidgetId.WEATHER to WidgetSpec(
            title = R.string.weather,
            source = WidgetSource.Device.WEATHER,
            minWidthDp = 224,
            style = WidgetStyle.READING,
            action = WidgetAction.WEATHER_FORECAST,
            iconAtEnd = true,
            animate = true
        ) { values ->
            WidgetContent(values.getValue("value"), values["summary"], values["symbol"])
        },
        WidgetId.ACTIVITY to WidgetSpec(
            title = R.string.active_today,
            source = WidgetSource.Device.ACTIVITY,
            minWidthDp = 300,
            style = WidgetStyle.READING,
            action = WidgetAction.APP_ACTIVITY,
            activityComponents = setOf(ActivityComponent.DETAIL_ICON, ActivityComponent.CHART, ActivityComponent.ROWS)
        ) { values ->
            WidgetContent(values.getValue("today"), values["peak"])
        },
        WidgetId.DATE to WidgetSpec(
            title = R.string.widget_date,
            source = WidgetSource.Device.DATE,
            minWidthDp = 200,
            style = WidgetStyle.HEADLINE,
            action = WidgetAction.NONE
        ) { values ->
            WidgetContent(values.getValue("day"), values["calendar"])
        }
    )

    fun restore(saved: String?): List<WidgetId> {
        if (saved == null) return defaults
        val byKey = available.keys.associateBy { it.key }
        return saved.split(',').mapNotNull(byKey::get).distinct()
    }
}
