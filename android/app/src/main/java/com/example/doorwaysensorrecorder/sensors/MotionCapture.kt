package com.example.doorwaysensorrecorder.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.example.doorwaysensorrecorder.data.DeviceInfo
import com.example.doorwaysensorrecorder.data.Labels
import com.example.doorwaysensorrecorder.data.SensorReading
import com.example.doorwaysensorrecorder.data.SensorRecording
import com.example.doorwaysensorrecorder.data.orderSensorReadings
import com.example.doorwaysensorrecorder.data.utcTimestampForMonotonicEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

fun sensorAvailability(sensorManager: SensorManager, includeStepCounter: Boolean): Map<String, Boolean> {
    val types = listOf(
        Sensor.TYPE_ACCELEROMETER to "accelerometer",
        Sensor.TYPE_LINEAR_ACCELERATION to "linearAcceleration",
        Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_ROTATION_VECTOR to "rotationVector",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
        Sensor.TYPE_PRESSURE to "barometer"
    ) + if (includeStepCounter) listOf(Sensor.TYPE_STEP_COUNTER to "stepCounter") else emptyList()
    return types.associate { (type, name) -> name to (sensorManager.getDefaultSensor(type) != null) }
}

class MotionCapture(
    private val context: Context,
    private val onReadingCountChanged: (Int) -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val readings = CopyOnWriteArrayList<SensorReading>()
    private val captureLock = Any()
    private var sensorThread: HandlerThread? = null
    @Volatile private var recordingLabel: String? = null
    private var sessionId = ""
    private var startedAtUtc = ""
    private var startedAtInstant = Instant.EPOCH
    private var startElapsedRealtimeNanos = 0L
    private var doorMarkerAtUtc: String? = null
    private var doorMarkerElapsedRealtimeNanos: Long? = null
    private var currentSensors: List<Sensor> = emptyList()

    fun hasStepCounter(): Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    fun availability(includeStepCounter: Boolean): Map<String, Boolean> =
        sensorAvailability(sensorManager, includeStepCounter)

    fun start(label: String, includeStepCounter: Boolean) {
        stopThread()
        readings.clear()
        recordingLabel = label
        sessionId = LocalDate.now(ZoneOffset.UTC).toString()
        startedAtInstant = Instant.now()
        startedAtUtc = startedAtInstant.toString()
        startElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        doorMarkerAtUtc = null
        doorMarkerElapsedRealtimeNanos = null

        currentSensors = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE
        ).mapNotNull { type -> sensorManager.getDefaultSensor(type) }.toMutableList().apply {
            if (includeStepCounter) sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let(::add)
        }

        val thread = HandlerThread("DoorwayMotionCapture").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        currentSensors.forEach { sensor ->
            val samplingPeriodUs = if (sensor.type == Sensor.TYPE_ACCELEROMETER ||
                sensor.type == Sensor.TYPE_LINEAR_ACCELERATION || sensor.type == Sensor.TYPE_GYROSCOPE
            ) 20_000 else 50_000
            sensorManager.registerListener(this, sensor, samplingPeriodUs, handler)
        }
    }

    /** Returns true only when this call saved the recording's single door marker. */
    fun markDoor(): Boolean {
        if (recordingLabel != Labels.DOOR_CROSSING || doorMarkerAtUtc != null) return false
        val markerElapsed = SystemClock.elapsedRealtimeNanos()
        doorMarkerElapsedRealtimeNanos = markerElapsed
        doorMarkerAtUtc = utcTimestampForMonotonicEvent(startedAtInstant, startElapsedRealtimeNanos, markerElapsed)
        return true
    }

    fun stop(): SensorRecording? {
        val label = recordingLabel ?: return null
        synchronized(captureLock) { recordingLabel = null }
        sensorManager.unregisterListener(this)
        stopThread()
        val endedElapsed = SystemClock.elapsedRealtimeNanos()
        val endedAt = Instant.now().toString()
        val includeStepCounter = context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val device = DeviceInfo.current(context)
        return SensorRecording(
            sessionId = sessionId,
            label = label,
            startedAtUtc = startedAtUtc,
            endedAtUtc = endedAt,
            startElapsedRealtimeNanos = startElapsedRealtimeNanos,
            endElapsedRealtimeNanos = endedElapsed,
            doorMarkerAtUtc = doorMarkerAtUtc,
            doorMarkerElapsedRealtimeNanos = doorMarkerElapsedRealtimeNanos,
            deviceModel = device.deviceModel,
            androidRelease = device.androidRelease,
            appVersion = device.appVersion,
            sensorAvailability = availability(includeStepCounter),
            samples = orderSensorReadings(readings.toList())
        )
    }

    fun isRecording(): Boolean = recordingLabel != null
    fun readingCount(): Int = readings.size
    fun currentLabel(): String? = recordingLabel
    fun hasDoorMarker(): Boolean = doorMarkerAtUtc != null

    override fun onSensorChanged(event: SensorEvent) {
        val updatedCount = synchronized(captureLock) {
            if (recordingLabel == null || event.timestamp < startElapsedRealtimeNanos) return
            val wallTime = utcTimestampForMonotonicEvent(startedAtInstant, startElapsedRealtimeNanos, event.timestamp)
            readings.add(
                SensorReading(
                    sensorType = event.sensor.type,
                    sensorName = event.sensor.name,
                    elapsedRealtimeNanos = event.timestamp,
                    timestampUtc = wallTime,
                    values = event.values.toList()
                )
            )
            readings.size
        }
        Handler(context.mainLooper).post { onReadingCountChanged(updatedCount) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun stopThread() {
        sensorThread?.quitSafely()
        sensorThread = null
    }
}
