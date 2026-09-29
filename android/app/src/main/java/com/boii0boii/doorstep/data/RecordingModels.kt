package com.boii0boii.doorstep.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

fun utcTimestampForMonotonicEvent(
    recordingStartUtc: Instant,
    recordingStartElapsedRealtimeNanos: Long,
    eventElapsedRealtimeNanos: Long
): String {
    val delta = (eventElapsedRealtimeNanos - recordingStartElapsedRealtimeNanos).coerceAtLeast(0L)
    return recordingStartUtc.plusNanos(delta).toString()
}

data class RecordingSummary(
    val fileName: String,
    val label: String,
    val startedAtUtc: String
)

data class SensorReading(
    val sensorType: Int,
    val sensorName: String,
    val elapsedRealtimeNanos: Long,
    val timestampUtc: String,
    val values: List<Float>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("sensorType", sensorType)
        .put("sensorName", sensorName)
        .put("elapsedRealtimeNanos", elapsedRealtimeNanos)
        .put("timestampUtc", timestampUtc)
        .put("values", JSONArray(values))
}


object Labels {
    const val DOOR_CROSSING = "Door Crossing"
    const val NORMAL_MOVEMENT = "Normal Movement"
    const val DEPARTURE_AUTO = "Departure (auto)"
}

fun orderSensorReadings(readings: List<SensorReading>): List<SensorReading> =
    readings.sortedWith(compareBy<SensorReading> { it.elapsedRealtimeNanos }.thenBy { it.sensorType })

data class SensorRecording(
    val recordingId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val label: String,
    val startedAtUtc: String,
    val endedAtUtc: String,
    val startElapsedRealtimeNanos: Long,
    val endElapsedRealtimeNanos: Long,
    val doorMarkerAtUtc: String?,
    val doorMarkerElapsedRealtimeNanos: Long?,
    val deviceModel: String,
    val androidRelease: String,
    val appVersion: String,
    val sensorAvailability: Map<String, Boolean>,
    val samples: List<SensorReading>,
    val captureMode: String = CAPTURE_MANUAL,
    val homeWifiLostAtUtc: String? = null,
    val homeWifiLostElapsedRealtimeNanos: Long? = null
) {
    fun toJson(): JSONObject {
        val availability = JSONObject()
        sensorAvailability.forEach { (name, available) -> availability.put(name, available) }
        val sampleArray = JSONArray()
        samples.forEach { sample -> sampleArray.put(sample.toJson()) }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("recordingId", recordingId)
            .put("sessionId", sessionId)
            .put("label", label)
            .put("captureMode", captureMode)
            .put("startedAtUtc", startedAtUtc)
            .put("endedAtUtc", endedAtUtc)
            .put("startElapsedRealtimeNanos", startElapsedRealtimeNanos)
            .put("endElapsedRealtimeNanos", endElapsedRealtimeNanos)
            .put("doorMarkerAtUtc", doorMarkerAtUtc ?: JSONObject.NULL)
            .put("doorMarkerElapsedRealtimeNanos", doorMarkerElapsedRealtimeNanos ?: JSONObject.NULL)
            .put("homeWifiLostAtUtc", homeWifiLostAtUtc ?: JSONObject.NULL)
            .put("homeWifiLostElapsedRealtimeNanos", homeWifiLostElapsedRealtimeNanos ?: JSONObject.NULL)
            .put("platform", "android")
            .put("deviceModel", deviceModel)
            .put("androidRelease", androidRelease)
            .put("appVersion", appVersion)
            .put("sensorAvailability", availability)
            .put("samples", sampleArray)
    }

    companion object {
        const val SCHEMA_VERSION = 2
        const val CAPTURE_MANUAL = "manual"
        const val CAPTURE_AUTO_DEPARTURE = "auto-departure"
        fun utcNow(): String = Instant.now().toString()
    }
}
