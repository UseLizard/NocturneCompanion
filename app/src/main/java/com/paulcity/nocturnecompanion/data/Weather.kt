package com.paulcity.nocturnecompanion.data

import kotlinx.serialization.Serializable

@Serializable
data class WeatherResponse(
    val latitude: Double,
    val longitude: Double,
    val hourly: HourlyWeather,
    val daily: DailyWeather
)

@Serializable
data class HourlyWeather(
    val time: List<String>,
    val temperature_2m: List<Double>,
    val relativehumidity_2m: List<Int>,
    val apparent_temperature: List<Double>,
    val precipitation_probability: List<Int>,
    val weathercode: List<Int>,
    val windspeed_10m: List<Double>
)

@Serializable
data class DailyWeather(
    val time: List<String>,
    val weathercode: List<Int>,
    val temperature_2m_max: List<Double>,
    val temperature_2m_min: List<Double>,
    val precipitation_sum: List<Double>,
    val precipitation_probability_max: List<Int>
)
