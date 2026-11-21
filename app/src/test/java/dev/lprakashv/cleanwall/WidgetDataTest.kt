package dev.lprakashv.cleanwall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WidgetDataTest {
    @Test fun homeCardsBindWeatherAndActivityReadings() {
        val (weather, activity) = WidgetCatalog.defaults.map(WidgetCatalog.available::getValue)
        assertEquals(WidgetAction.WEATHER_FORECAST, weather.action)
        assertEquals(WidgetAction.APP_ACTIVITY, activity.action)
        assertEquals(
            WidgetContent("25 °C", "Bengaluru · Clear", "☀"),
            weather.content(mapOf("value" to "25 °C", "summary" to "Bengaluru · Clear", "symbol" to "☀"))
        )
        assertEquals(WidgetContent("1 h", "Mornings · 1 h"), activity.content(mapOf("today" to "1 h", "peak" to "Mornings · 1 h")))
        assertEquals(ActivityComponent.entries.toSet(), activity.activityComponents)
    }

    @Test fun restoresOrderAndKeepsAnExplicitlyEmptyRow() {
        assertEquals(listOf(WidgetId.WEATHER, WidgetId.ACTIVITY), WidgetCatalog.restore(null))
        assertEquals(emptyList<WidgetId>(), WidgetCatalog.restore(""))
        assertEquals(
            listOf(WidgetId.DATE, WidgetId.ACTIVITY, WidgetId.WEATHER),
            WidgetCatalog.restore("date,retired,activity,date,weather")
        )
    }

    @Test fun cardsRespectTheirMinimumAndKeepTheirWidthWhenTheRowGrows() {
        val specs = WidgetCatalog.available
        val narrowWidths = specs.values.map { it.widthForViewport(320) }
        assertEquals(listOf(224, 300, 200), narrowWidths)
        val viewport = 892
        val initial = WidgetCatalog.defaults.map { specs.getValue(it).widthForViewport(viewport) }
        val expanded = specs.values.map { it.widthForViewport(viewport) }
        assertEquals(initial, expanded.take(initial.size))
        assertEquals(viewport, initial.sum() + WIDGET_GAP_DP)
        assertEquals(true, expanded.sum() + (expanded.size - 1) * WIDGET_GAP_DP > viewport)
    }

    @Test fun remoteSourcesKeepTheirRefreshHooks() {
        val source = WidgetSource.Remote { mapOf("score" to "12") }
        assertEquals(0L, source.intervalDelay(100, null))
        assertEquals(899_950L, source.intervalDelay(100, 50))
        assertEquals(0L, source.intervalDelay(900_100, 50))
        assertEquals(mapOf("score" to "12"), source.load())

        val manual = WidgetSource.Remote(hooks = setOf(WidgetSource.Hook.SELECT)) { emptyMap() }
        assertNull(manual.intervalDelay(100, null))
        assertThrows(IllegalArgumentException::class.java) {
            WidgetSpec(R.string.settings, source = source, action = WidgetAction.REFRESH) { WidgetContent("Example") }
        }
    }
}
