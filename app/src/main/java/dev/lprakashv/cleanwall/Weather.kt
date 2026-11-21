package dev.lprakashv.cleanwall

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.roundToInt

internal object Weather {
    data class City(val name: String, val latitude: Double, val longitude: Double)
    data class ForecastDay(
        val date: LocalDate,
        val minimum: Int,
        val maximum: Int,
        val symbol: String,
        val description: Int,
        val precipitationChance: Int?,
        val precipitationMm: Double?
    )
    data class Conditions(
        val celsius: Int,
        val description: Int,
        val symbol: String,
        val humidity: Int,
        val forecast: List<ForecastDay>
    )

    val bengaluru = City("Bengaluru", 12.97194, 77.59369)

    fun find(query: String): List<City> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val results = json("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=5")
            .optJSONArray("results") ?: return emptyList()
        return List(results.length()) { index ->
            val result = results.getJSONObject(index)
            val place = listOf(result.getString("name"), result.optString("admin1"), result.optString("country"))
                .filter { it.isNotBlank() }.distinct().joinToString(", ")
            City(place, result.getDouble("latitude"), result.getDouble("longitude"))
        }
    }

    fun current(city: City): Conditions {
        val result = json(
            "https://api.open-meteo.com/v1/forecast?latitude=${city.latitude}&longitude=${city.longitude}" +
                "&current=temperature_2m,relative_humidity_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum" +
                "&forecast_days=7&timezone=auto"
        )
        val current = result.getJSONObject("current")
        val code = current.getInt("weather_code")
        val daily = result.getJSONObject("daily")
        val days = daily.getJSONArray("time")
        val chances = daily.optJSONArray("precipitation_probability_max")
        val amounts = daily.optJSONArray("precipitation_sum")
        val forecast = List(days.length()) { index ->
            val dayCode = daily.getJSONArray("weather_code").getInt(index)
            ForecastDay(
                date = LocalDate.parse(days.getString(index)),
                minimum = daily.getJSONArray("temperature_2m_min").getDouble(index).roundToInt(),
                maximum = daily.getJSONArray("temperature_2m_max").getDouble(index).roundToInt(),
                symbol = weatherSymbol(dayCode),
                description = weatherDescription(dayCode),
                precipitationChance = chances?.optInt(index, -1)?.takeIf { it in 0..100 },
                precipitationMm = amounts?.optDouble(index)?.takeIf { it.isFinite() && it >= 0 }
            )
        }
        return Conditions(
            current.getDouble("temperature_2m").roundToInt(), weatherDescription(code),
            weatherSymbol(code), current.getInt("relative_humidity_2m"), forecast
        )
    }

    private fun weatherDescription(code: Int) = when (code) {
        0 -> R.string.weather_clear
        1, 2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45, 48 -> R.string.weather_fog
        in 51..57 -> R.string.weather_drizzle
        in 61..67, in 80..82 -> R.string.weather_rain
        in 71..77, 85, 86 -> R.string.weather_snow
        in 95..99 -> R.string.weather_thunderstorms
        else -> R.string.weather_unknown
    }

    private fun weatherSymbol(code: Int) = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        in 51..67, in 80..82 -> "☂"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "ϟ"
        else -> "☁"
    }

    private fun json(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }
}
