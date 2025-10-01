package com.example.weatherapp

import android.Manifest
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvCity: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvWeatherDescription: TextView

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val BASE_URL = "https://api.open-meteo.com/v1/"
    private val DEFAULT_CITY = "Москва"
    private val DEFAULT_LATITUDE = 55.7558
    private val DEFAULT_LONGITUDE = 37.6173

    private var currentLatitude: Double = DEFAULT_LATITUDE
    private var currentLongitude: Double = DEFAULT_LONGITUDE
    private var currentCity: String = DEFAULT_CITY

    // Лаунчер для запроса разрешений
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            getLastLocation()
        } else {
            // Разрешения не даны, используем дефолт
            useDefaultLocation("Геолокация недоступна")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCity = findViewById(R.id.tv_city)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvWeatherDescription = findViewById(R.id.tv_weather_description)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Показываем placeholder до загрузки
        tvCity.text = "Определение..."
        tvTemperature.text = "--°C"
        tvWeatherDescription.text = "Загрузка..."

        checkLocationPermissions()
    }

    private fun checkLocationPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED -> {
                getLastLocation()
            }
            else -> {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
    }

    private fun getLastLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude
                    getCityFromLocation(location.latitude, location.longitude) { city ->
                        currentCity = city
                        fetchWeather()
                    }
                } else {
                    useDefaultLocation("Не удалось получить местоположение")
                }
            }.addOnFailureListener { e ->
                Log.e("WeatherApp", "Ошибка получения location: ${e.message}")
                useDefaultLocation("Ошибка геолокации")
            }
        } catch (e: SecurityException) {
            Log.e("WeatherApp", "SecurityException: ${e.message}")
            useDefaultLocation("Разрешения не предоставлены")
        }
    }

    private fun useDefaultLocation(message: String) {
        currentLatitude = DEFAULT_LATITUDE
        currentLongitude = DEFAULT_LONGITUDE
        currentCity = DEFAULT_CITY
        tvWeatherDescription.text = "$message. Используется дефолт."
        fetchWeather()
    }

    // Асинхронная функция для получения города через Geocoder
    private fun getCityFromLocation(lat: Double, lon: Double, callback: (String) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val geocoder = Geocoder(this@MainActivity, Locale("ru", "RU"))  // Русский язык
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    geocoder.getFromLocation(lat, lon, 1) { addresses ->
                        val city = addresses.firstOrNull()?.locality ?: "Неизвестный город"
                        callback(city)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lon, 1)
                    val city = addresses?.firstOrNull()?.locality ?: "Неизвестный город"
                    callback(city)
                }
            } catch (e: Exception) {
                Log.e("WeatherApp", "Ошибка Geocoder: ${e.message}")
                callback("Неизвестный город")
            }
        }
    }

    private fun fetchWeather() {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(WeatherApi::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = api.getWeather(currentLatitude, currentLongitude).execute()

                if (response.isSuccessful) {
                    val weatherData = response.body()
                    weatherData?.let {
                        val description = getWeatherDescription(it.current.weatherCode)
                        withContext(Dispatchers.Main) {
                            tvCity.text = currentCity
                            tvTemperature.text = "${it.current.temperature.toInt()}°C"
                            tvWeatherDescription.text = description
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvWeatherDescription.text = "Ошибка: ${response.code()}"
                    }
                }
            } catch (e: Exception) {
                Log.e("WeatherApp", "Ошибка запроса: ${e.message}")
                withContext(Dispatchers.Main) {
                    tvWeatherDescription.text = "Ошибка сети"
                }
            }
        }
    }

    // Функция для преобразования WMO weather_code в описание на русском
    private fun getWeatherDescription(code: Int): String {
        return when (code) {
            0 -> "Ясно"
            1 -> "В основном ясно"
            2 -> "Частично облачно"
            3 -> "Пасмурно"
            45 -> "Туман"
            48 -> "Инейный туман"
            51 -> "Легкая морось"
            53 -> "Умеренная морось"
            55 -> "Сильная морось"
            56 -> "Легкая изморось"
            57 -> "Сильная изморось"
            61 -> "Легкий дождь"
            63 -> "Умеренный дождь"
            65 -> "Сильный дождь"
            66 -> "Легкий ледяной дождь"
            67 -> "Сильный ледяной дождь"
            71 -> "Легкий снег"
            73 -> "Умеренный снег"
            75 -> "Сильный снег"
            77 -> "Снежные зерна"
            80 -> "Легкий ливень"
            81 -> "Умеренный ливень"
            82 -> "Сильный ливень"
            85 -> "Легкий снегопад"
            86 -> "Сильный снегопад"
            95 -> "Гроза"
            96 -> "Гроза с легким градом"
            99 -> "Гроза с сильным градом"
            else -> "Неизвестно"
        }
    }
}