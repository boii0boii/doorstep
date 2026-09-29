package com.example.doorwaysensorrecorder.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler

enum class HomeWifiState { HOME, NOT_HOME, UNKNOWN }

/**
 * Tracks whether the phone is connected to the saved home SSID using a network callback, which
 * keeps working with the screen off inside a foreground service. Reading the SSID from the
 * background needs precise location plus "Allow all the time" location access; without it
 * Android redacts the name and the state stays UNKNOWN (fail closed: no alerts).
 */
class HomeWifiMonitor(
    context: Context,
    private val handler: Handler,
    private val homeSsid: () -> String,
    private val onStateChanged: (HomeWifiState) -> Unit
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var homeNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    var state = HomeWifiState.UNKNOWN
        private set

    fun start() {
        if (callback != null) return
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    handleCapabilities(network, capabilities)
                override fun onLost(network: Network) = handleLost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    handleCapabilities(network, capabilities)
                override fun onLost(network: Network) = handleLost(network)
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivityManager.registerNetworkCallback(request, created, handler)
        callback = created
    }

    fun stop() {
        callback?.let { runCatching { connectivityManager.unregisterNetworkCallback(it) } }
        callback = null
        homeNetwork = null
    }

    /** Re-reads the current network, e.g. after the saved home SSID changes. */
    fun restart() {
        stop()
        update(HomeWifiState.UNKNOWN)
        start()
    }

    private fun handleCapabilities(network: Network, capabilities: NetworkCapabilities) {
        val ssid = ssidOf(capabilities)
        val home = homeSsid()
        when {
            ssid == null -> if (homeNetwork == null) update(HomeWifiState.UNKNOWN)
            home.isNotBlank() && ssid == home -> {
                homeNetwork = network
                update(HomeWifiState.HOME)
            }
            network == homeNetwork || homeNetwork == null -> {
                homeNetwork = null
                update(HomeWifiState.NOT_HOME)
            }
        }
    }

    private fun handleLost(network: Network) {
        if (network == homeNetwork) {
            homeNetwork = null
            update(HomeWifiState.NOT_HOME)
        } else if (homeNetwork == null) {
            update(HomeWifiState.NOT_HOME)
        }
    }

    private fun update(newState: HomeWifiState) {
        if (newState == state) return
        state = newState
        onStateChanged(newState)
    }

    @Suppress("DEPRECATION")
    private fun ssidOf(capabilities: NetworkCapabilities): String? {
        val info = capabilities.transportInfo as? WifiInfo
        val fromCallback = normalise(info?.ssid)
        if (fromCallback != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return fromCallback
        return normalise(wifiManager.connectionInfo?.ssid)
    }

    private fun normalise(raw: String?): String? {
        val ssid = raw?.trim('"')?.trim().orEmpty()
        return if (ssid.isEmpty() || ssid == WifiManager.UNKNOWN_SSID) null else ssid
    }
}
