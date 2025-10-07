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
import retrofit2.Call
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
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rootLayout: CoordinatorLayout
    private lateinit var ivHouse: ImageView  // ImageView для дома
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>  // Behavior для BottomSheet (FrameLayout)

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val BASE_URL = "https://api.open-meteo.com/v1/"

    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var currentCity: String = ""

    private val handler = Handler(Looper.getMainLooper())
    private val UPDATE_INTERVAL = 15 * 60 * 1000L  // 15 минут в миллисекундах

    private val updateRunnable = Runnable { refreshData(false) }  // Автообновление без индикатора

    // Лаунчер для запроса разрешений
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            getLastLocation(false)  // После запроса, если даны, получаем location
        } else {
            // Разрешения не даны, показываем ошибку
            showLocationError("Разрешения на геолокацию не предоставлены. Пожалуйста, разрешите доступ в настройках.")
        }
    }

    // Элементы Bottom Sheet
    private lateinit var tvWeeklyTab: TextView
    private lateinit var tvDailyTab: TextView
    private lateinit var rvForecast: RecyclerView
    private lateinit var forecastAdapter: ForecastAdapter

    // Данные прогноза
    private var hourlyData: List<ForecastItem> = emptyList()
    private var dailyData: List<ForecastItem> = emptyList()
    private var currentMode: String = "daily"  // По умолчанию ежедневный (по часам)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCity = findViewById(R.id.tv_city)
        tvTemperature = findViewById(R.id.tv_temperature)
        tvWeatherDescription = findViewById(R.id.tv_weather_description)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        rootLayout = findViewById(R.id.root_layout)
        ivHouse = findViewById(R.id.iv_house)  // Инициализация ImageView дома

        // Инициализация BottomSheet (FrameLayout)
        val bottomSheet: FrameLayout = findViewById(R.id.bottom_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        // Настройки: пропускаем collapsed, устанавливаем half-expanded на 50% экрана (для половины дома)
        bottomSheetBehavior.skipCollapsed = true
        bottomSheetBehavior.halfExpandedRatio = 0.5f  // 50% высоты экрана, чтобы заполнить нижнюю половину дома
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED  // Изначально в half-expanded
        bottomSheetBehavior.isDraggable = false  // Отключаем свайп и взаимодействие
        bottomSheetBehavior.isHideable = false  // Не скрываем

        // Инициализация элементов Bottom Sheet
        tvWeeklyTab = findViewById(R.id.tv_weekly_tab)
        tvDailyTab = findViewById(R.id.tv_daily_tab)
        rvForecast = findViewById(R.id.rv_forecast)
        rvForecast.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        forecastAdapter = ForecastAdapter(emptyList())
        rvForecast.adapter = forecastAdapter

        // Переключение вкладок
        tvDailyTab.setOnClickListener {
            switchToMode("daily")
        }
        tvWeeklyTab.setOnClickListener {
            switchToMode("weekly")
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Swipe to refresh
        swipeRefreshLayout.setOnRefreshListener {
            refreshData(true)  // Обновление с индикатором
        }

        // Проверяем разрешения и загружаем данные
        checkLocationPermissions()

        // Запускаем автообновление
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
    }

    // Проверка разрешений на геолокацию
    private fun checkLocationPermissions() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            getLastLocation(true)  // Первый запуск, с индикатором
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Получение текущей локации
    @SuppressLint("MissingPermission")
    private fun getLastLocation(showProgress: Boolean) {
        if (showProgress) swipeRefreshLayout.isRefreshing = true

        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            if (location != null) {
                currentLatitude = location.latitude
                currentLongitude = location.longitude
                getCityFromLocation(location)
                fetchWeatherData("current")  // Загружаем текущую погоду
                fetchForecastData()  // Загружаем прогноз
            } else {
                showLocationError("Не удалось получить локацию. Проверьте настройки GPS.")
            }
        }.addOnFailureListener {
            showLocationError("Ошибка получения локации: ${it.message}")
        }
    }

    // Получение города по координатам
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
                if (!addresses.isNullOrEmpty()) {
                    currentCity = addresses[0].locality ?: addresses[0].adminArea ?: "Неизвестно"
                    tvCity.text = currentCity
                } else {
                    currentCity = "Неизвестно"
                    tvCity.text = currentCity
                }
            }
        }
    }

    // Обновление данных
    private fun refreshData(showProgress: Boolean) {
        if (currentLatitude != 0.0 && currentLongitude != 0.0) {
            if (showProgress) swipeRefreshLayout.isRefreshing = true
            fetchWeatherData("current")
            fetchForecastData()
            handler.postDelayed(updateRunnable, UPDATE_INTERVAL)  // Перезапуск таймера
        }
    }

    // Получение данных погоды
    private fun fetchWeatherData(forecastType: String) {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(WeatherApi::class.java)

        val hourlyParams = if (forecastType == "daily") "temperature_2m,weather_code" else null
        val dailyParams = if (forecastType == "weekly") "temperature_2m_max,temperature_2m_min,weather_code" else null

        val call = api.getWeather(
            latitude = currentLatitude,
            longitude = currentLongitude,
            hourly = hourlyParams,
            daily = dailyParams
        )

        call.enqueue(object : retrofit2.Callback<WeatherResponse> {
            override fun onResponse(call: Call<WeatherResponse>, response: Response<WeatherResponse>) {
                swipeRefreshLayout.isRefreshing = false
                if (response.isSuccessful) {
                    val weatherResponse = response.body()
                    weatherResponse?.let {
                        updateUI(it)
                    }
                } else {
                    showError("Ошибка загрузки данных: ${response.code()}")
                }
            }

            override fun onFailure(call: Call<WeatherResponse>, t: Throwable) {
                swipeRefreshLayout.isRefreshing = false
                showError("Ошибка сети: ${t.message}")
            }
        })
    }

    // Загрузка прогноза (по умолчанию hourly)
    private fun fetchForecastData() {
        fetchWeatherData(currentMode)
    }

    // Обновление UI текущей погоды и прогноза
    private fun updateUI(weatherResponse: WeatherResponse) {
        val current = weatherResponse.current
        tvTemperature.text = "${current.temperature.toInt()}°"
        tvWeatherDescription.text = getWeatherDescription(current.weatherCode)

        // Определение дня/ночи на основе часа в current.time
        val currentTime = LocalDateTime.parse(current.time, DateTimeFormatter.ISO_DATE_TIME)
        val isDay = currentTime.hour in 6..18
        updateBackground(isDay)

        // Обработка прогноза
        if (currentMode == "daily") {
            weatherResponse.hourly?.let { hourly ->
                hourlyData = processHourlyData(hourly, current.time)
                forecastAdapter.updateData(hourlyData)
            }
        } else {
            weatherResponse.daily?.let { daily ->
                dailyData = processDailyData(daily)
                forecastAdapter.updateData(dailyData)
            }
        }

        // Применяем блюр к Bottom Sheet после обновления UI
        val bottomSheet: FrameLayout = findViewById(R.id.bottom_sheet)
        applyBlurAndTransparency(bottomSheet)
    }

    // Переключение режимов прогноза
    private fun switchToMode(mode: String) {
        currentMode = mode
        if (mode == "daily") {
            tvDailyTab.setBackgroundColor(Color.LTGRAY)
            tvWeeklyTab.setBackgroundColor(Color.TRANSPARENT)
            forecastAdapter.updateData(hourlyData)
        } else {
            tvWeeklyTab.setBackgroundColor(Color.LTGRAY)
            tvDailyTab.setBackgroundColor(Color.TRANSPARENT)
            forecastAdapter.updateData(dailyData)
        }
        fetchWeatherData(mode)
    }

    // Обработка почасового прогноза
    private fun processHourlyData(hourly: Hourly, currentTimeStr: String): List<ForecastItem> {
        val items = mutableListOf<ForecastItem>()
        val currentTime = LocalDateTime.parse(currentTimeStr, DateTimeFormatter.ISO_DATE_TIME)
        val formatter = DateTimeFormatter.ofPattern("HH:mm")

        for (i in hourly.time.indices) {
            val time = LocalDateTime.parse(hourly.time[i], DateTimeFormatter.ISO_DATE_TIME)
            if (time.isAfter(currentTime) || time == currentTime) {
                val label = if (i == 0) "Сейчас" else time.format(formatter)
                val isCurrent = i == 0
                items.add(
                    ForecastItem(
                        label,
                        "${hourly.temperature2m[i].toInt()}°",
                        hourly.weatherCode[i],
                        isCurrent
                    )
                )
                if (items.size == 24) break  // Ограничиваем 24 часами
            }
        }
        return items
    }

    // Обработка ежедневного прогноза
    private fun processDailyData(daily: Daily): List<ForecastItem> {
        val items = mutableListOf<ForecastItem>()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        for (i in daily.time.indices) {
            val date = LocalDateTime.parse(daily.time[i], formatter)
            val label = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            items.add(
                ForecastItem(
                    label,
                    "${daily.temperature2mMax[i].toInt()}° / ${daily.temperature2mMin[i].toInt()}°",
                    daily.weatherCode[i],
                    false
                )
            )
        }
        return items
    }

    // Функция для обновления фона в зависимости от дня/ночи
    private fun updateBackground(isDay: Boolean) {
        rootLayout.background = if (isDay) {
            resources.getDrawable(R.drawable.day_bg, null)
        } else {
            resources.getDrawable(R.drawable.night_bg, null)
        }
    }

    // Функция для применения блюра и прозрачности только к Bottom Sheet
    private fun applyBlurAndTransparency(bottomSheet: View) {
        // Временно скрываем Bottom Sheet, чтобы захватить только подлежащий контент
        bottomSheet.visibility = View.INVISIBLE
        rootLayout.post {
            val screenshot = takeScreenshot(rootLayout)
            val blurredScreenshot = blurBitmap(screenshot, 25)  // Блюр с радиусом 25

            // Устанавливаем блюренный скриншот как фон Bottom Sheet с прозрачностью
            val transparentBlur = ColorDrawable(Color.argb(128, 0, 0, 0))  // Полупрозрачный черный (для затемнения)
            val layers = arrayOf(BitmapDrawable(resources, blurredScreenshot), transparentBlur)
            val layerDrawable = LayerDrawable(layers)
            bottomSheet.background = layerDrawable

            // Возвращаем видимость
            bottomSheet.visibility = View.VISIBLE
        }
    }

    // Функция для захвата скриншота
    private fun takeScreenshot(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    // Функция блюра (Stack Blur)
    private fun blurBitmap(sentBitmap: Bitmap, blurRadius: Int): Bitmap {
        var bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)

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

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = (i / divsum)
            i++
        }

        yw = 0
        yi = yw

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
                sir[2] = (p and 0x0000ff)
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

        // Если был scale, возвращаем к оригинальному размеру
        bitmap = Bitmap.createScaledBitmap(bitmap, sentBitmap.width, sentBitmap.height, true)

        return bitmap
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

    // Вспомогательный класс для элементов прогноза
    data class ForecastItem(
        val label: String,
        val temperature: String,
        val weatherCode: Int,
        val isCurrent: Boolean
    )

    // Функция показа ошибки (заглушка)
    private fun showError(message: String) {
        Log.e("WeatherApp", message)
        // Можно добавить Toast или Snackbar
    }

    // Функция показа ошибки локации (заглушка)
    private fun showLocationError(message: String) {
        Log.e("WeatherApp", message)
        swipeRefreshLayout.isRefreshing = false
        // Можно добавить диалог или Toast
    }
}