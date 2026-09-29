package com.example.doorwaysensorrecorder.monitor

class RawSensorEvent(
    val sensorType: Int,
    val sensorName: String,
    val elapsedRealtimeNanos: Long,
    val values: FloatArray
)

/** Keeps the most recent [windowNanos] of sensor events, keyed by `SensorEvent.timestamp`. */
class SensorRingBuffer(private val windowNanos: Long) {
    private val events = ArrayDeque<RawSensorEvent>()
    private var newestNanos = Long.MIN_VALUE

    @Synchronized
    fun add(event: RawSensorEvent) {
        events.addLast(event)
        if (event.elapsedRealtimeNanos > newestNanos) newestNanos = event.elapsedRealtimeNanos
        val horizon = newestNanos - windowNanos
        while (events.isNotEmpty() && events.first().elapsedRealtimeNanos < horizon) events.removeFirst()
    }

    @Synchronized
    fun snapshot(fromNanos: Long, toNanos: Long): List<RawSensorEvent> = events
        .filter { it.elapsedRealtimeNanos in fromNanos..toNanos }
        .sortedWith(compareBy<RawSensorEvent> { it.elapsedRealtimeNanos }.thenBy { it.sensorType })

    @Synchronized
    fun clear() {
        events.clear()
        newestNanos = Long.MIN_VALUE
    }

    @Synchronized
    fun size(): Int = events.size
}
