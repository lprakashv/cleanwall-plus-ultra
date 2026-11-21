package dev.quirkdeck.launcher

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

internal object Weather {
    data class City(val name: String, val latitude: Double, val longitude: Double)
    data class Conditions(val celsius: Int, val description: String, val symbol: String)

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
                "&current=temperature_2m,weather_code"
        ).getJSONObject("current")
        val code = result.getInt("weather_code")
        val description = when (code) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67, in 80..82 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Thunderstorms"
            else -> "Current conditions"
        }
        val symbol = when (code) {
            0 -> "☀"
            1, 2 -> "⛅"
            in 51..67, in 80..82 -> "☂"
            in 71..77, 85, 86 -> "❄"
            in 95..99 -> "ϟ"
            else -> "☁"
        }
        return Conditions(result.getDouble("temperature_2m").roundToInt(), description, symbol)
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
