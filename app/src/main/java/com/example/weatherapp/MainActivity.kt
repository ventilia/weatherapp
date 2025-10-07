package com.example.weatherapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var tvCity: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvWeatherDescription: TextView
    private lateinit var ivMainWeatherIcon: ImageView  // ← Новая переменная
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var ivHouse: ImageView
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val BASE_URL = "https://api.open-meteo.com/v1/"

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

    private var textColorPrimary: Int = Color.BLACK
    private var textColorSecondary: Int = Color.GRAY

    // Флаг темы
    private var isNightMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCity = findViewById(R.id.tv_city)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvWeatherDescription = findViewById(R.id.tv_weather_description)
        ivMainWeatherIcon = findViewById(R.id.iv_main_weather_icon)  // ← Инициализация
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        rootLayout = findViewById(R.id.root_layout)
        ivHouse = findViewById(R.id.iv_house)

        isNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (isNightMode) {
            rootLayout.background = getDrawable(R.drawable.night_bg)
            ivHouse.setImageResource(R.drawable.house_night)
            textColorPrimary = Color.GRAY
            textColorSecondary = Color.DKGRAY
        } else {
            rootLayout.background = getDrawable(R.drawable.day_bg)
            ivHouse.setImageResource(R.drawable.house_day)
            textColorPrimary = Color.BLACK
            textColorSecondary = Color.GRAY
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
        forecastAdapter = ForecastAdapter(emptyList(), isNightMode)  // ← Передаём тему
        rvForecast.adapter = forecastAdapter

        tvDailyTab.setTextColor(textColorPrimary)
        tvWeeklyTab.setTextColor(textColorSecondary)

        tvDailyTab.setOnClickListener { switchToMode("daily") }
        tvWeeklyTab.setOnClickListener { switchToMode("weekly") }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        swipeRefreshLayout.setOnRefreshListener {
            refreshData(true)
        }

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
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                currentLatitude = location.latitude
                currentLongitude = location.longitude
                getCityFromLocation(location)
                refreshData(isInitial)
            } else {
                showLocationError("Не удалось получить местоположение.")
            }
        }.addOnFailureListener {
            showLocationError("Ошибка получения местоположения: ${it.message}")
        }
    }

    private fun getCityFromLocation(location: Location) {
        CoroutineScope(Dispatchers.IO).launch {
            val geocoder = Geocoder(this@MainActivity, Locale.getDefault())
            val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            } else {
                @Suppress("deprecation")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }
            withContext(Dispatchers.Main) {
                currentCity = addresses?.firstOrNull()?.locality ?: "Неизвестный город"
                tvCity.text = currentCity
            }
        }
    }

    private fun refreshData(showRefreshing: Boolean) {
        if (showRefreshing) {
            swipeRefreshLayout.isRefreshing = true
        }

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response: Response<WeatherResponse> = retrofit.create(WeatherApi::class.java).getWeather(
                    currentLatitude, currentLongitude,
                    hourly = "temperature_2m,weather_code",
                    daily = "temperature_2m_max,temperature_2m_min,weather_code"
                ).execute()

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
        tvWeatherDescription.text = getWeatherDescription(current.weatherCode)

        // Устанавливаем иконку для основного экрана
        ivMainWeatherIcon.setImageResource(getWeatherIconResource(current.weatherCode, isNightMode))

        hourlyData = buildList {
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

        dailyData = buildList {
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

        switchToMode(currentMode)
        updateBackground()
    }

    private fun updateBackground() {
        CoroutineScope(Dispatchers.Default).launch {
            val bitmap = blurBitmap(rootLayout)
            withContext(Dispatchers.Main) {
                val bottomSheet = findViewById<FrameLayout>(R.id.bottom_sheet)
                bottomSheet.background = BitmapDrawable(resources, bitmap)
            }
        }
    }

    private fun blurBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return fastBlur(bitmap, 25)
    }

    private fun fastBlur(sentBitmap: Bitmap, blurRadius: Int): Bitmap {
        var bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true)

        if (blurRadius < 1) {
            return bitmap
        }

        val w = bitmap.width
        val h = bitmap.height

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = blurRadius + blurRadius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(max(w, h))

        var divsum = div + 1 shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yi = 0
        yw = yi

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = blurRadius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = bsum
            rsum = gsum
            boutsum = rsum
            goutsum = boutsum
            routsum = goutsum
            binsum = routsum
            ginsum = binsum
            rinsum = ginsum
            i = -blurRadius
            while (i <= blurRadius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + blurRadius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - i.absoluteValue
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = blurRadius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - blurRadius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) {
                    vmin[x] = min(x + blurRadius + 1, wm)
                }
                p = pix[yw + vmin[x]]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[(stackpointer) % div]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi++
                x++
            }
            yw += w
            y++
        }
        x = 0
        while (x < w) {
            bsum = 0
            gsum = bsum
            rsum = gsum
            boutsum = rsum
            goutsum = boutsum
            routsum = goutsum
            binsum = routsum
            ginsum = binsum
            rinsum = ginsum
            yp = -blurRadius * w
            i = -blurRadius
            while (i <= blurRadius) {
                yi = max(0, yp) + x
                sir = stack[i + blurRadius]
                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]
                rbs = r1 - kotlin.math.abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = blurRadius

            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - blurRadius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (x == 0) {
                    vmin[y] = min(y + blurRadius + 1, hm) * w
                }
                p = x + vmin[y]
                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
                rsum += rinsum
                gsum += ginsum
                bsum += binsum
                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]
                yi += w
                y++
            }
            x++
        }
        bitmap.setPixels(pix, 0, w, 0, 0, w, h)

        bitmap = Bitmap.createScaledBitmap(bitmap, sentBitmap.width, sentBitmap.height, true)

        return bitmap
    }

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

    // 🔹 НОВАЯ ФУНКЦИЯ: выбор иконки с учётом темы
    private fun getWeatherIconResource(weatherCode: Int, isNight: Boolean): Int {
        return when (weatherCode) {
            // ☀️ Ясно
            0 -> if (isNight) R.drawable.ic_clear_night else R.drawable.ic_clear_day

            // ⛅ Переменная облачность
            1, 2 -> if (isNight) R.drawable.ic_partly_cloudy_night else R.drawable.ic_partly_cloudy_day

            // ☁️ Пасмурно
            3 -> R.drawable.ic_cloudy

            // 🌫 Туман и инейный туман
            45, 48 -> R.drawable.ic_fog

            // 💧 Морось (все виды) → будем использовать лёгкий дождь (или heavy_rain, если нет отдельной)
            // Но у нас нет "лёгкого дождя", поэтому используем heavy_rain как универсальный "дождь"
            // Однако лучше создать одну иконку "дождь", но раз у нас уже есть heavy_rain —
            // давай введём общую "ic_rain", но если не хочешь — можно временно использовать heavy_rain.
            // Но чтобы не вводить новую — давай **переименуем смысл**: пусть ic_heavy_rain = просто "дождь"
            51, 53, 55, 56, 57, 61, 63, 66, 67 -> R.drawable.ic_heavy_rain

            // 🌧 Сильный дождь и ливни
            65, 80, 81, 82 -> R.drawable.ic_heavy_rain

            // ❄️ Снег (все виды) → нет иконки снега! Нужно добавить хотя бы одну.
            // Но ты просишь использовать имеющиеся. У нас её нет.
            // Поэтому временно отобразим снег как "облачно" или "дождь"? Лучше — как "облачно", но это плохо.
            // ⚠️ Решение: **добавим одну иконку снега** — `ic_snow` (без разделения на силу и день/ночь).
            // Это минимальное расширение. Без неё — placeholder.
            71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow

            // ⚡ Гроза
            95, 96, 99 -> R.drawable.ic_thunderstorm

            // ❓ Неизвестное — fallback
            else -> R.drawable.ic_placeholder
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