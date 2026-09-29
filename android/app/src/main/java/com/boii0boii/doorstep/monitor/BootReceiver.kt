package com.boii0boii.doorstep.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.boii0boii.doorstep.data.AppSettings

/** Restarts the departure monitor after reboot or app update if the user had it enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppSettings.isMonitorEnabled(context)) return
        runCatching { DepartureMonitorService.start(context) }
    }
}
