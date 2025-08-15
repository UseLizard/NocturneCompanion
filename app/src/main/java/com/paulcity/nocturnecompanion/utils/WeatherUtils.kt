package com.paulcity.nocturnecompanion.utils

import androidx.annotation.DrawableRes
import com.paulcity.nocturnecompanion.R

@DrawableRes
fun getWeatherIcon(weatherCode: Int): Int {
    return when (weatherCode) {
        0 -> R.drawable.ic_weather_clear_day
        1 -> R.drawable.ic_weather_partly_cloudy_day
        2 -> R.drawable.ic_weather_cloudy
        3 -> R.drawable.ic_weather_cloudy
        45, 48 -> R.drawable.ic_weather_fog
        51, 53, 55 -> R.drawable.ic_weather_drizzle
        56, 57 -> R.drawable.ic_weather_sleet
        61, 63, 65 -> R.drawable.ic_weather_rain
        66, 67 -> R.drawable.ic_weather_sleet
        71, 73, 75 -> R.drawable.ic_weather_snow
        77 -> R.drawable.ic_weather_snow
        80, 81, 82 -> R.drawable.ic_weather_rain
        85, 86 -> R.drawable.ic_weather_snow
        95 -> R.drawable.ic_weather_thunderstorms
        96, 99 -> R.drawable.ic_weather_thunderstorms
        else -> R.drawable.ic_weather_not_available
    }
}

/**
 * Convert Open-Meteo weather code to human-readable condition
 */
fun getConditionFromCode(code: Int): String {
    return when (code) {
        0 -> "Clear"
        1 -> "Partly Cloudy"
        2, 3 -> "Cloudy"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing Drizzle"
        61, 63, 65 -> "Rain"
        66, 67 -> "Freezing Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain Showers"
        85, 86 -> "Snow Showers"
        95 -> "Thunderstorm"
        96, 99 -> "Heavy Thunderstorm"
        else -> "Unknown"
    }
}
