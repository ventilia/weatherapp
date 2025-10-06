package com.example.weatherapp

import android.Manifest
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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var tvCity: TextView
    private lateinit var tvTemperature: TextView
    private lateinit var tvWeatherDescription: TextView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var rootLayout: ConstraintLayout
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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Настройка pull-to-refresh
        swipeRefreshLayout.setOnRefreshListener {
            refreshData(true)  // Manual refresh с индикатором
        }

        // Показываем placeholder до первой загрузки
        tvCity.text = "Определение..."
        tvTemperature.text = "--°C"
        tvWeatherDescription.text = "Загрузка..."

        // Применяем эффект после измерения views
        rootLayout.post {
            updateTheme()  // Изначальная тема и blur
        }

        refreshData(false)  // Первая загрузка без индикатора
    }

    override fun onResume() {
        super.onResume()
        // Запускаем автообновление каждые 15 минут
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
    }

    override fun onPause() {
        super.onPause()
        // Останавливаем автообновление при паузе
        handler.removeCallbacks(updateRunnable)
    }

    private fun refreshData(isManual: Boolean) {
        if (isManual) {
            swipeRefreshLayout.isRefreshing = true
        }
        // Показываем placeholder во время обновления
        tvCity.text = "Определение..."
        tvTemperature.text = "--°C"
        tvWeatherDescription.text = "Загрузка..."

        // Обновляем тему перед запросом данных (на случай смены системной темы)
        updateTheme()

        checkLocationPermissions(isManual)
    }

    private fun checkLocationPermissions(isManual: Boolean) {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED -> {
                getLastLocation(isManual)
            }
            else -> {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                // Пока разрешения не даны, показываем ошибку (лаунчер обработает, если дадут)
                showLocationError("Геолокация недоступна. Пожалуйста, предоставьте разрешения.", isManual)
            }
        }
    }

    private fun getLastLocation(isManual: Boolean) {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null) {
                    currentLatitude = location.latitude
                    currentLongitude = location.longitude
                    getCityFromLocation(location.latitude, location.longitude) { city ->
                        currentCity = city
                        fetchWeather(isManual)
                    }
                } else {
                    showLocationError("Не удалось получить местоположение. Проверьте, включена ли геолокация.", isManual)
                }
            }.addOnFailureListener { e ->
                Log.e("WeatherApp", "Ошибка получения location: ${e.message}")
                showLocationError("Ошибка геолокации: ${e.message}", isManual)
            }
        } catch (e: SecurityException) {
            Log.e("WeatherApp", "SecurityException: ${e.message}")
            showLocationError("Разрешения не предоставлены.", isManual)
        }
    }

    private fun showLocationError(message: String, isManual: Boolean = false) {
        tvCity.text = "Ошибка"
        tvTemperature.text = "--°C"
        tvWeatherDescription.text = message
        if (isManual) {
            swipeRefreshLayout.isRefreshing = false
        }
        // Обновляем тему после показа ошибки (на случай смены)
        updateTheme()
        // Планируем следующее автообновление, чтобы попробовать снова позже
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
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

    private fun fetchWeather(isManual: Boolean) {
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
                            if (isManual) {
                                swipeRefreshLayout.isRefreshing = false
                            }
                            // Обновляем тему после успешного fetch (на случай смены)
                            updateTheme()
                            // Планируем следующее автообновление
                            handler.removeCallbacks(updateRunnable)
                            handler.postDelayed(updateRunnable, UPDATE_INTERVAL)
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        tvWeatherDescription.text = "Ошибка: ${response.code()}"
                        if (isManual) {
                            swipeRefreshLayout.isRefreshing = false
                        }
                        // Обновляем тему после ошибки
                        updateTheme()
                    }
                }
            } catch (e: Exception) {
                Log.e("WeatherApp", "Ошибка запроса: ${e.message}")
                withContext(Dispatchers.Main) {
                    tvWeatherDescription.text = "Ошибка сети"
                    if (isManual) {
                        swipeRefreshLayout.isRefreshing = false
                    }
                    // Обновляем тему после ошибки
                    updateTheme()
                }
            }
        }
    }

    // Функция обновления темы: проверка текущего режима и установка фона/цветов/изображения дома
    private fun updateTheme() {
        val isNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

        // Установка фона
        rootLayout.setBackgroundResource(if (isNightMode) R.drawable.night_bg else R.drawable.day_bg)

        // Установка изображения дома
        ivHouse.setImageResource(if (isNightMode) R.drawable.house_night else R.drawable.house_day)

        // Установка цветов текста (используем ресурсы из colors.xml для адаптации)
        val primaryTextColor = getColor(if (isNightMode) R.color.text_color_primary else R.color.text_color_primary)  // Белый/чёрный
        val secondaryTextColor = getColor(if (isNightMode) R.color.text_color_secondary else R.color.text_color_secondary)  // Серый вариации

        tvCity.setTextColor(primaryTextColor)
        tvTemperature.setTextColor(primaryTextColor)
        tvWeatherDescription.setTextColor(secondaryTextColor)

        // Применяем frosted glass эффект (с размытием)
        rootLayout.post {
            applyFrostedGlassEffect(isNightMode)
        }
    }

    // Функция применения эффекта матового стекла с размытием
    private fun applyFrostedGlassEffect(isNightMode: Boolean) {
        val bottomSheet: FrameLayout = findViewById(R.id.bottom_sheet)

        // Скрываем bottom sheet временно, чтобы захватить фон без него
        bottomSheet.visibility = View.INVISIBLE

        // Захватываем bitmap корневого layout
        val bitmap = Bitmap.createBitmap(rootLayout.width, rootLayout.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        rootLayout.draw(canvas)

        // Размываем bitmap (с scale для оптимизации производительности)
        val blurred = fastBlur(bitmap, 0.5f, 25)

        // Обрезаем под размер bottom sheet (примерно нижняя половина экрана)
        val sheetHeight = (rootLayout.height * bottomSheetBehavior.halfExpandedRatio).toInt()
        val cropY = rootLayout.height - sheetHeight
        val croppedBlurred = Bitmap.createBitmap(blurred, 0, cropY, rootLayout.width, sheetHeight)

        // Создаём drawable из размытого изображения
        val blurredDrawable = BitmapDrawable(resources, croppedBlurred)

        // Полупрозрачный оверлей (адаптированный под тему: светлый для дня, тёмный для ночи)
        val overlayColor = if (isNightMode) Color.parseColor("#80000000") else Color.parseColor("#80FFFFFF")  // 50% прозрачность
        val overlay = ColorDrawable(overlayColor)

        // LayerDrawable: blur + overlay
        val layer = LayerDrawable(arrayOf(blurredDrawable, overlay))

        // Устанавливаем как фон bottom sheet
        bottomSheet.background = layer

        // Возвращаем видимость
        bottomSheet.visibility = View.VISIBLE
    }

    // Функция быстрого размытия (Stack Blur алгоритм для совместимости)
    private fun fastBlur(sentBitmap: Bitmap, scale: Float, radius: Int): Bitmap {
        var bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)

        if (scale < 1) {
            bitmap = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        }

        val w = bitmap.width
        val h = bitmap.height
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

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
        val r1 = radius + 1
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
            i = -radius
            while (i <= radius) {
                p = pix[yi + min(wm, max(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = (p and 0x0000ff)
                rbs = r1 - kotlin.math.abs(i)
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
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (y == 0) {
                    vmin[x] = min(x + radius + 1, wm)
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
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = max(0, yp) + x
                sir = stack[i + radius]
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
            stackpointer = radius

            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])
                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum
                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]
                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]
                if (x == 0) {
                    vmin[y] = min(y + radius + 1, hm) * w
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
        if (scale < 1) {
            bitmap = Bitmap.createScaledBitmap(bitmap, sentBitmap.width, sentBitmap.height, true)
        }

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
}