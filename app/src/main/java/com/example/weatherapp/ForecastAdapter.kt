package com.example.weatherapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class ForecastAdapter(private var data: List<MainActivity.ForecastItem>) :
    RecyclerView.Adapter<ForecastAdapter.ViewHolder>() {

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
        // Иконка: Пока не устанавливаем для проверки функционала
        // holder.ivIcon.setImageResource(getIconResource(item.weatherCode))  // Закомментировано

        // Выделение "сейчас" или текущего дня темнее
        if (item.isCurrent) {
            holder.cardView.setCardBackgroundColor(android.graphics.Color.GRAY)  // Темнее фон
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

    // Функция для иконки (пока не используем)
    /* private fun getIconResource(code: Int): Int {
        // Логика для иконок день/ночь и т.д., но пока не добавляем
        return R.drawable.ic_placeholder
    } */
}