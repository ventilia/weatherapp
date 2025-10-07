// LocationHelper.kt (новый файл: вынесена логика локации)
package com.example.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationHelper(
    private val context: Context,
    private val fusedLocationClient: FusedLocationProviderClient
) {

    @SuppressLint("MissingPermission")
    fun getLastLocation(onLocationReceived: (Location?) -> Unit) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            onLocationReceived(location)
        }.addOnFailureListener {
            onLocationReceived(null)
        }
    }

    fun getCityFromLocation(location: Location, onCityReceived: (String?) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            } else {
                @Suppress("deprecation")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }
            withContext(Dispatchers.Main) {
                onCityReceived(addresses?.firstOrNull()?.locality)
            }
        }
    }
}