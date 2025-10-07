package com.example.weatherapp

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class WeatherRepository {

    private val BASE_URL = "https://api.open-meteo.com/v1/"

    suspend fun getWeather(latitude: Double, longitude: Double): Response<WeatherResponse> {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(WeatherApi::class.java).getWeather(
            latitude, longitude,
            hourly = "temperature_2m,weather_code",
            daily = "temperature_2m_max,temperature_2m_min,weather_code"
        ).execute()
    }
}