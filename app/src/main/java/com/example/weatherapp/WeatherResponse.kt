package com.example.weatherapp

import com.google.gson.annotations.SerializedName

data class WeatherResponse(
    @SerializedName("current")
    val current: Current,

    @SerializedName("hourly")
    val hourly: Hourly? = null,

    @SerializedName("daily")
    val daily: Daily? = null
)

data class Current(
    @SerializedName("temperature_2m")
    val temperature: Double,

    @SerializedName("weather_code")
    val weatherCode: Int
)

data class Hourly(
    @SerializedName("time")
    val time: List<String>,

    @SerializedName("temperature_2m")
    val temperature2m: List<Double>,

    @SerializedName("weather_code")
    val weatherCode: List<Int>
)

data class Daily(
    @SerializedName("time")
    val time: List<String>,

    @SerializedName("temperature_2m_max")
    val temperature2mMax: List<Double>,

    @SerializedName("temperature_2m_min")
    val temperature2mMin: List<Double>,

    @SerializedName("weather_code")
    val weatherCode: List<Int>
)