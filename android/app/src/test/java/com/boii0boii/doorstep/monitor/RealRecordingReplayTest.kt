package com.boii0boii.doorstep.monitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Replays real Pixel 8a recordings from `samples/recordings` through [DepartureDetector],
 * simulating home Wi-Fi dropping at the end of each recording.
 */
class RealRecordingReplayTest {
    private val samples = File("../../samples/recordings")
    private val grace = DepartureConfig().reconnectGraceNanos

    private class Replay(val startNanos: Long, val endNanos: Long, val stepTimes: List<Long>, val json: JSONObject)

    private fun load(id: String): Replay {
        val json = JSONObject(File(samples, "$id.json").readText())
        val events = json.getJSONArray("samples")
        // The app records the cumulative step counter; each increase is one or more steps.
        val steps = mutableListOf<Long>()
        var previous: Long? = null
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            if (event.getInt("sensorType") != STEP_COUNTER) continue
            val count = event.getJSONArray("values").getDouble(0).toLong()
            val time = event.getLong("elapsedRealtimeNanos")
            previous?.let { repeat((count - it).toInt().coerceAtLeast(0)) { steps += time } }
            previous = count
        }
        return Replay(json.getLong("startElapsedRealtimeNanos"), json.getLong("endElapsedRealtimeNanos"), steps, json)
    }

    private fun replay(recording: Replay, wifiLostAt: Long = recording.endNanos, reconnectAt: Long? = null): DepartureCheck {
        val detector = DepartureDetector()
        detector.onHomeWifiConnected(recording.startNanos - TimeUnit.MINUTES.toNanos(10))
        recording.stepTimes.forEach(detector::onStep)
        detector.onHomeWifiLost(wifiLostAt)
        reconnectAt?.let(detector::onHomeWifiConnected)
        return detector.evaluate(wifiLostAt + grace)!!
    }

    @Test
    fun walkingRecordingAlertsWhenWifiDropsAtItsEnd() {
        val check = replay(load(WALKING))
        assertEquals(DepartureOutcome.ALERT, check.outcome)
        assertEquals(11, check.stepsInWindow)
    }

    @Test
    fun wifiBlipDuringTheSameWalkDoesNotAlert() {
        val recording = load(WALKING)
        val check = replay(recording, reconnectAt = recording.endNanos + TimeUnit.SECONDS.toNanos(3))
        assertEquals(DepartureOutcome.RECONNECTED, check.outcome)
    }

    @Test
    fun wifiDropLongAfterTheWalkDoesNotAlert() {
        val recording = load(WALKING)
        val check = replay(recording, wifiLostAt = recording.endNanos + TimeUnit.MINUTES.toNanos(5))
        assertEquals(DepartureOutcome.NOT_WALKING, check.outcome)
    }

    /**
     * The 10-second marked clip has only two counted steps: the Pixel step counter delivers in
     * batches roughly 10 s late, so steps near the end of a short recording are missing. A real
     * departure is evaluated over a 90 s lookback with the lower-latency step detector plus a
     * sensor flush, which is why the monitor does not rely on the step counter.
     */
    @Test
    fun shortMarkedClipHasTooFewCountedStepsOnItsOwn() {
        val check = replay(load(MARKED))
        assertEquals(DepartureOutcome.NOT_WALKING, check.outcome)
        assertEquals(2, check.stepsInWindow)
    }

    /**
     * These samples were saved by an early build that wrote events in arrival order, so different
     * sensors interleave by a few milliseconds. Each sensor's own stream must still be ordered;
     * current builds also sort the merged stream (see orderSensorReadings).
     */
    @Test
    fun realRecordingsArePerSensorOrderedAndSampledNearFiftyHertz() {
        listOf(WALKING, MARKED).forEach { id ->
            val recording = load(id)
            val events = recording.json.getJSONArray("samples")
            val lastBySensor = mutableMapOf<Int, Long>()
            var accelerometerEvents = 0
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val type = event.getInt("sensorType")
                val time = event.getLong("elapsedRealtimeNanos")
                assertTrue("sensor $type must be time-ordered in $id", time >= (lastBySensor[type] ?: Long.MIN_VALUE))
                lastBySensor[type] = time
                if (type == ACCELEROMETER) accelerometerEvents++
            }
            val seconds = (recording.endNanos - recording.startNanos) / 1e9
            assertTrue("accelerometer rate in $id", accelerometerEvents / seconds in 45.0..70.0)
        }
    }

    private companion object {
        const val WALKING = "98df0f3b-6505-4b6f-a5b9-f492471ed576"
        const val MARKED = "47555281-56fb-49db-8ef3-1739d444e497"
        const val ACCELEROMETER = 1
        const val STEP_COUNTER = 19
    }
}
