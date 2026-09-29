package com.boii0boii.doorstep.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide state shared by the services and the UI, so a recording or the departure monitor
 * keeps running (and stays visible) across screen-off, rotation, and Activity recreation.
 * Write only from the main thread.
 */
object CaptureStatus {
    var isRecording by mutableStateOf(false)
    var label by mutableStateOf<String?>(null)
    var startedAtMillis by mutableLongStateOf(0L)
    var sampleCount by mutableIntStateOf(0)
    var hasDoorMarker by mutableStateOf(false)
    var message by mutableStateOf("Ready")
}

object MonitorStatus {
    var isRunning by mutableStateOf(false)
    var homeWifi by mutableStateOf("Not checked")
    var motionActive by mutableStateOf(false)
    var recentChecks by mutableStateOf(emptyList<String>())
}

object RecordingsChanged {
    var version by mutableIntStateOf(0)
    fun bump() { version++ }
}
