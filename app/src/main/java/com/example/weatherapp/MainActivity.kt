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
                getCityName(location)
                fetchWeatherData()
            } else {
                showLocationError("Не удалось получить геолокацию. Пожалуйста, проверьте настройки.")
            }
        }.addOnFailureListener {
            showLocationError("Ошибка получения геолокации: ${it.message}")
        }
    }

    // Получение названия города по координатам
    private fun getCityName(location: Location) {
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
        if (showProgress) swipeRefreshLayout.isRefreshing = true
        if (currentLatitude != 0.0 && currentLongitude != 0.0) {
            fetchWeatherData()
        } else {
            getLastLocation(showProgress)
        }
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)  // Перезапуск таймера
    }

    // Загрузка данных погоды
    private fun fetchWeatherData() {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(WeatherApi::class.java)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val hourlyParams = "temperature_2m,weather_code"  // Для ежедневного (по часам)
                val dailyParams = "temperature_2m_max,temperature_2m_min,weather_code"  // Для еженедельного

                val response: retrofit2.Response<WeatherResponse> = api.getWeather(
                    latitude = currentLatitude,
                    longitude = currentLongitude,
                    hourly = hourlyParams,
                    daily = dailyParams
                ).execute()

                if (response.isSuccessful) {
                    val weatherResponse = response.body()
                    weatherResponse?.let {
                        withContext(Dispatchers.Main) {
                            updateUI(it)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showLocationError("Ошибка загрузки данных: ${response.code()}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLocationError("Ошибка: ${e.message}")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    swipeRefreshLayout.isRefreshing = false
                }
            }
        }
    }

    // Обновление UI
    private fun updateUI(weatherResponse: WeatherResponse) {
        val current = weatherResponse.current
        tvTemperature.text = "${current.temperature.toInt()}°C"
        tvWeatherDescription.text = getWeatherDescription(current.weatherCode)

        // Обработка данных для ежедневного (по часам)
        val hourly = weatherResponse.hourly
        if (hourly != null) {
            hourlyData = processHourlyData(hourly)
            if (currentMode == "daily") {
                forecastAdapter.updateData(hourlyData)
            }
        }

        // Обработка данных для еженедельного
        val daily = weatherResponse.daily
        if (daily != null) {
            dailyData = processDailyData(daily)
            if (currentMode == "weekly") {
                forecastAdapter.updateData(dailyData)
            }
        }

        // Обновление фона (дома с блюром)
        updateBackground()
    }

    // Обработка ежедневных данных (по часам)
    private fun processHourlyData(hourly: Hourly): List<ForecastItem> {
        val items = mutableListOf<ForecastItem>()
        val currentTime = LocalDateTime.now(ZoneId.systemDefault())
        var foundCurrent = false

        for (i in hourly.time.indices) {
            val timeStr = hourly.time[i]
            val time = LocalDateTime.parse(timeStr, DateTimeFormatter.ISO_DATE_TIME)
            val label = if (ChronoUnit.HOURS.between(currentTime, time) == 0L) {
                foundCurrent = true
                "Сейчас"
            } else {
                time.hour.toString().padStart(2, '0') + ":00"
            }
            val temp = "${hourly.temperature2m[i].toInt()}°"
            items.add(ForecastItem(label, temp, hourly.weatherCode[i], label == "Сейчас"))
        }
        return items
    }

    // Обработка еженедельных данных
    private fun processDailyData(daily: Daily): List<ForecastItem> {
        val items = mutableListOf<ForecastItem>()
        val currentDate = LocalDateTime.now(ZoneId.systemDefault()).toLocalDate()

        for (i in daily.time.indices) {
            val dateStr = daily.time[i]
            val date = LocalDateTime.parse(dateStr + "T00:00", DateTimeFormatter.ISO_DATE_TIME).toLocalDate()
            val label = if (date == currentDate) {
                "Сегодня"
            } else {
                date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru"))
            }
            val temp = "${daily.temperature2mMax[i].toInt()}° / ${daily.temperature2mMin[i].toInt()}°"
            items.add(ForecastItem(label, temp, daily.weatherCode[i], label == "Сегодня"))
        }
        return items
    }

    // Переключение режимов (ежедневный/еженедельный)
    private fun switchToMode(mode: String) {
        currentMode = mode
        if (mode == "daily") {
            forecastAdapter.updateData(hourlyData)
            tvDailyTab.setTextColor(Color.BLACK)
            tvWeeklyTab.setTextColor(Color.GRAY)
        } else {
            forecastAdapter.updateData(dailyData)
            tvWeeklyTab.setTextColor(Color.BLACK)
            tvDailyTab.setTextColor(Color.GRAY)
        }
    }

    // Показ ошибки
    private fun showLocationError(message: String) {
        Log.e("MainActivity", message)
        // Можно добавить Toast или Snackbar
    }

    // Обновление фона с блюром
    private fun updateBackground() {
        // Загружаем изображение дома
        val houseDrawable = resources.getDrawable(R.drawable.house_day, null)  // Предполагаем, что есть drawable house.png/jpg
        val houseBitmap = (houseDrawable as BitmapDrawable).bitmap

        // Применяем блюр
        val blurredBitmap = applyBlur(houseBitmap, 25)  // Радиус блюра 25

        // Создаем LayerDrawable: блюр + затемнение
        val overlay = ColorDrawable(Color.argb(128, 0, 0, 0))  // Полупрозрачный черный
        val layers = arrayOf(BitmapDrawable(resources, blurredBitmap), overlay)
        val layerDrawable = LayerDrawable(layers)

        // Устанавливаем как фон rootLayout
        rootLayout.background = layerDrawable

        // Устанавливаем оригинальное изображение дома в ImageView (без блюра)
        ivHouse.setImageDrawable(houseDrawable)
    }

    // Функция для блюра битмапа (Stack Blur)
    private fun applyBlur(sentBitmap: Bitmap, blurRadius: Int): Bitmap {
        if (blurRadius < 1) {
            return sentBitmap
        }

        // Исправление: Обработка null для config
        var bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)

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
}