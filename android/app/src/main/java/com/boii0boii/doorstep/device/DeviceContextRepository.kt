package com.boii0boii.doorstep.device

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.CancellationSignal
import android.os.SystemClock
import java.util.concurrent.TimeUnit

sealed interface WifiCheck {
    data class Available(val ssid: String) : WifiCheck
    data object NotConnectedToWifi : WifiCheck
    data object Redacted : WifiCheck
}

data class DoorFix(
    val location: Location,
    val ageMillis: Long
)

class DeviceContextRepository(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun currentWifi(): WifiCheck {
        val network = connectivityManager.activeNetwork ?: return WifiCheck.NotConnectedToWifi
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return WifiCheck.NotConnectedToWifi
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return WifiCheck.NotConnectedToWifi
        }
        val wifiInfo = capabilities.transportInfo as? WifiInfo ?: return WifiCheck.Redacted
        val ssid = wifiInfo.getSSID()?.trim('"')?.trim().orEmpty()
        return if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID) {
            WifiCheck.Redacted
        } else {
            WifiCheck.Available(ssid)
        }
    }

    fun requestGpsFix(onResult: (Result<DoorFix>) -> Unit) {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            onResult(Result.failure(IllegalStateException("Turn on Location and GPS to check the door radius.")))
            return
        }
        try {
            locationManager.getCurrentLocation(
                LocationManager.GPS_PROVIDER,
                CancellationSignal(),
                appContext.mainExecutor
            ) { location ->
                if (location == null) {
                    onResult(Result.failure(IllegalStateException("No fresh GPS fix. Try outside or near a window.")))
                } else if (!location.hasAccuracy() || location.accuracy < 0f) {
                    onResult(Result.failure(IllegalStateException("GPS returned no usable horizontal accuracy.")))
                } else {
                    val ageMillis = TimeUnit.NANOSECONDS.toMillis(
                        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0L)
                    )
                    onResult(Result.success(DoorFix(location, ageMillis)))
                }
            }
        } catch (error: SecurityException) {
            onResult(Result.failure(error))
        } catch (error: IllegalArgumentException) {
            onResult(Result.failure(error))
        }
    }
}
