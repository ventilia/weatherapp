package com.example.weatherapp

object WeatherUtils {

    fun getWeatherDescription(code: Int): String {
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

    fun getWeatherIconResource(weatherCode: Int, isNight: Boolean): Int {
        return when (weatherCode) {
            0 -> if (isNight) R.drawable.ic_clear_night else R.drawable.ic_clear_day
            1, 2 -> if (isNight) R.drawable.ic_partly_cloudy_night else R.drawable.ic_partly_cloudy_day
            3 -> R.drawable.ic_cloudy
            45, 48 -> R.drawable.ic_fog
            51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_heavy_rain
            71, 73, 75, 77, 85, 86 -> R.drawable.ic_snow
            95, 96, 99 -> R.drawable.ic_thunderstorm
            else -> R.drawable.ic_placeholder
        }
    }
}