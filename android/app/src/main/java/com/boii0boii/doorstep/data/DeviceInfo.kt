package com.boii0boii.doorstep.data

import android.content.Context
import android.os.Build

data class DeviceInfo(
    val deviceModel: String,
    val androidRelease: String,
    val appVersion: String
) {
    companion object {
        fun current(context: Context): DeviceInfo = DeviceInfo(
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "1.0"
        )
    }
}
