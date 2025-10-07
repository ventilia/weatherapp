package com.example.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvCity: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvWeatherDescription: TextView
    private lateinit var ivMainWeatherIcon: ImageView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var ivHouse: ImageView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var currentCity: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val UPDATE_INTERVAL = 15 * 60 * 1000L

    private val updateRunnable = Runnable { refreshData(false) }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            getLastLocation(false)
        } else {
            showLocationError("Разрешения на геолокацию не предоставлены. Пожалуйста, разрешите доступ в настройках.")
        }
    }

    private lateinit var tvWeeklyTab: TextView
    private lateinit var tvDailyTab: TextView
    private lateinit var rvForecast: RecyclerView
    private lateinit var forecastAdapter: ForecastAdapter

    private var hourlyData: List<ForecastItem> = emptyList()
    private var dailyData: List<ForecastItem> = emptyList()
    private var currentMode: String = "daily"

    private var textColorPrimary: Int = android.graphics.Color.BLACK
    private var textColorSecondary: Int = android.graphics.Color.GRAY

    private var isNightMode = false

    private lateinit var weatherRepository: WeatherRepository
    private lateinit var locationHelper: LocationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCity = findViewById(R.id.tv_city)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvWeatherDescription = findViewById(R.id.tv_weather_description)
        ivMainWeatherIcon = findViewById(R.id.iv_main_weather_icon)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        rootLayout = findViewById(R.id.root_layout)
        ivHouse = findViewById(R.id.iv_house)

        isNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (isNightMode) {
            rootLayout.background = getDrawable(R.drawable.night_bg)
            ivHouse.setImageResource(R.drawable.house_night)
            textColorPrimary = android.graphics.Color.GRAY
            textColorSecondary = android.graphics.Color.DKGRAY
        } else {
            rootLayout.background = getDrawable(R.drawable.day_bg)
            ivHouse.setImageResource(R.drawable.house_day)
            textColorPrimary = android.graphics.Color.BLACK
            textColorSecondary = android.graphics.Color.GRAY
        }

        tvCity.setTextColor(textColorPrimary)
        tvTemperature.setTextColor(textColorPrimary)
        tvWeatherDescription.setTextColor(textColorPrimary)

        val bottomSheet: FrameLayout = findViewById(R.id.bottom_sheet)
        layoutInflater.inflate(R.layout.bottom_sheet_layout, bottomSheet, true)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.skipCollapsed = false
        bottomSheetBehavior.halfExpandedRatio = 0.35f
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        bottomSheetBehavior.isDraggable = false
        bottomSheetBehavior.isHideable = false

        tvWeeklyTab = bottomSheet.findViewById(R.id.tv_weekly_tab)
        tvDailyTab = bottomSheet.findViewById(R.id.tv_daily_tab)
        rvForecast = bottomSheet.findViewById(R.id.rv_forecast)

        rvForecast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        forecastAdapter = ForecastAdapter(emptyList(), isNightMode)
        rvForecast.adapter = forecastAdapter

        tvDailyTab.setTextColor(textColorPrimary)
        tvWeeklyTab.setTextColor(textColorSecondary)

        tvDailyTab.setOnClickListener { switchToMode("daily") }
        tvWeeklyTab.setOnClickListener { switchToMode("weekly") }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        swipeRefreshLayout.setOnRefreshListener {
            refreshData(true)
        }

        weatherRepository = WeatherRepository()
        locationHelper = LocationHelper(this, fusedLocationClient)

        checkLocationPermissions()
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
    }

    private fun switchToMode(mode: String) {
        currentMode = mode
        if (mode == "daily") {
            forecastAdapter.updateData(hourlyData)
            tvDailyTab.setTextColor(textColorPrimary)
            tvWeeklyTab.setTextColor(textColorSecondary)
        } else {
            forecastAdapter.updateData(dailyData)
            tvWeeklyTab.setTextColor(textColorPrimary)
            tvDailyTab.setTextColor(textColorSecondary)
        }
    }

    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            getLastLocation(true)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation(isInitial: Boolean) {
        locationHelper.getLastLocation { location ->
            if (location != null) {
                currentLatitude = location.latitude
                currentLongitude = location.longitude
                getCityFromLocation(location)
                refreshData(isInitial)
            } else {
                showLocationError("Не удалось получить местоположение.")
            }
        }
    }

    private fun getCityFromLocation(location: Location) {
        locationHelper.getCityFromLocation(location) { city ->
            currentCity = city ?: "Неизвестный город"
            tvCity.text = currentCity
        }
    }

    private fun refreshData(showRefreshing: Boolean) {
        if (showRefreshing) {
            swipeRefreshLayout.isRefreshing = true
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response: Response<WeatherResponse> = weatherRepository.getWeather(
                    currentLatitude, currentLongitude
                )

                if (response.isSuccessful) {
                    val weather = response.body()
                    weather?.let {
                        withContext(Dispatchers.Main) {
                            updateUI(it)
                            if (showRefreshing) {
                                swipeRefreshLayout.isRefreshing = false
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showError("Ошибка: ${response.code()}")
                        if (showRefreshing) {
                            swipeRefreshLayout.isRefreshing = false
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Исключение: ${e.message}")
                    if (showRefreshing) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                }
            }
        }

        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
    }

    private fun updateUI(weather: WeatherResponse) {
        val current = weather.current
        tvTemperature.text = "${current.temperature.toInt()}°C"
        tvWeatherDescription.text = WeatherUtils.getWeatherDescription(current.weatherCode)

        ivMainWeatherIcon.setImageResource(WeatherUtils.getWeatherIconResource(current.weatherCode, isNightMode))

        hourlyData = buildHourlyData(weather)
        dailyData = buildDailyData(weather)

        switchToMode(currentMode)
        updateBackground()
    }

    private fun buildHourlyData(weather: WeatherResponse): List<ForecastItem> {
        return buildList {
            val now = LocalDateTime.now()
            val currentHourIndex = weather.hourly?.time?.indexOfFirst {
                LocalDateTime.parse(it).isAfter(now.minusHours(1))
            } ?: 0
            for (i in currentHourIndex until minOf(currentHourIndex + 24, weather.hourly?.time?.size ?: 0)) {
                val time = LocalDateTime.parse(weather.hourly!!.time[i])
                val label = if (i == currentHourIndex) "Сейчас" else time.format(DateTimeFormatter.ofPattern("HH:mm"))
                val temp = "${weather.hourly.temperature2m[i].toInt()}°"
                add(ForecastItem(label, temp, weather.hourly.weatherCode[i], i == currentHourIndex))
            }
        }
    }

    private fun buildDailyData(weather: WeatherResponse): List<ForecastItem> {
        return buildList {
            val currentDate = LocalDateTime.now().toLocalDate()
            for (i in 0 until (weather.daily?.time?.size ?: 0)) {
                val date = java.time.LocalDate.parse(weather.daily!!.time[i])
                val dayOfWeek = date.dayOfWeek
                val daysUntil = ChronoUnit.DAYS.between(currentDate, date).toInt()
                val label = when {
                    daysUntil == 0 -> "Сегодня"
                    daysUntil == 1 -> "Завтра"
                    else -> dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                }
                val temp = "${weather.daily.temperature2mMin[i].toInt()}° / ${weather.daily.temperature2mMax[i].toInt()}°"
                add(ForecastItem(label, temp, weather.daily.weatherCode[i], daysUntil == 0))
            }
        }
    }

    private fun updateBackground() {
        CoroutineScope(Dispatchers.Default).launch {
            val bitmap = BlurUtils.blurView(rootLayout)
            withContext(Dispatchers.Main) {
                val bottomSheet = findViewById<FrameLayout>(R.id.bottom_sheet)
                bottomSheet.background = BitmapDrawable(resources, bitmap)
            }
        }
    }

    data class ForecastItem(
        val label: String,
        val temperature: String,
        val weatherCode: Int,
        val isCurrent: Boolean
    )

    private fun showError(message: String) {
        Log.e("WeatherApp", message)
    }

    private fun showLocationError(message: String) {
        Log.e("WeatherApp", message)
        swipeRefreshLayout.isRefreshing = false
    }
}