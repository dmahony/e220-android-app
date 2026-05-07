package com.dmahony.e220chat

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
suspend fun resolveCurrentLocation(context: Context): Location? = withContext(Dispatchers.Main) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { provider -> runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false) }

    if (providers.isEmpty()) {
        return@withContext null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val current = withTimeoutOrNull(5000L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                locationManager.getCurrentLocation(
                    LocationManager.GPS_PROVIDER,
                    null,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (continuation.isActive) {
                        continuation.resume(location)
                    }
                }
            }
        }
        if (current != null) {
            return@withContext current
        }
    }

    providers.mapNotNull { provider ->
        runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { location -> location.time }
}
