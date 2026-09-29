package com.boii0boii.doorstep.data

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    const val PREFS_NAME = "doorway_settings"
    const val KEY_HOME_SSID = "home_ssid"
    const val KEY_MONITOR_ENABLED = "departure_monitor_enabled"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun homeSsid(context: Context): String = prefs(context).getString(KEY_HOME_SSID, "").orEmpty()

    fun isMonitorEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_MONITOR_ENABLED, false)

    fun setMonitorEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MONITOR_ENABLED, enabled).apply()
    }
}
