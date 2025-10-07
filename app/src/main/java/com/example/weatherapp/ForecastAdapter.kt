package com.example.weatherapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ForecastAdapter(
    private var data: List<MainActivity.ForecastItem>,
    private val isNightMode: Boolean
) : RecyclerView.Adapter<ForecastAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: MaterialCardView = view as MaterialCardView
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val ivIcon: ImageView = view.findViewById(R.id.iv_weather_icon)
        val tvTemp: TextView = view.findViewById(R.id.tv_temp)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.forecast_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = data[position]
        holder.tvTime.text = item.label
        holder.tvTemp.text = item.temperature

        // Устанавливаем иконку с учётом темы
        holder.ivIcon.setImageResource(getWeatherIconResource(item.weatherCode, isNightMode))

        if (item.isCurrent) {
            holder.cardView.setCardBackgroundColor(android.graphics.Color.GRAY)
            holder.tvTime.setTextColor(android.graphics.Color.WHITE)
            holder.tvTemp.setTextColor(android.graphics.Color.WHITE)
        } else {
            holder.cardView.setCardBackgroundColor(android.graphics.Color.WHITE)
            holder.tvTime.setTextColor(android.graphics.Color.BLACK)
            holder.tvTemp.setTextColor(android.graphics.Color.BLACK)
        }
    }

    override fun getItemCount(): Int = data.size

    fun updateData(newData: List<MainActivity.ForecastItem>) {
        data = newData
        notifyDataSetChanged()
    }

    // Копия функции из MainActivity (можно вынести в утилиты, но для простоты — дублируем)
    private fun getWeatherIconResource(weatherCode: Int, isNight: Boolean): Int {
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