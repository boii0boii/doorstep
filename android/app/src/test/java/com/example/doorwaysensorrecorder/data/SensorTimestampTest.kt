package com.example.doorwaysensorrecorder.data

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorTimestampTest {
    @Test
    fun mapsMonotonicDeltaToUtc() {
        val start = Instant.parse("2026-09-26T12:00:00Z")
        val actual = utcTimestampForMonotonicEvent(start, 1_000_000_000L, 2_250_000_000L)
        assertEquals("2026-09-26T12:00:01.250Z", actual)
    }

    @Test
    fun clampsAnEventBeforeRecordingStart() {
        val start = Instant.parse("2026-09-26T12:00:00Z")
        val actual = utcTimestampForMonotonicEvent(start, 2_000L, 1_000L)
        assertEquals("2026-09-26T12:00:00Z", actual)
    }

    @Test
    fun ordersMergedSensorEventsByMonotonicTimestamp() {
        val first = SensorReading(4, "gyro", 20L, "t2", listOf(1f))
        val second = SensorReading(1, "accel", 10L, "t1", listOf(2f))
        val sameTime = SensorReading(4, "gyro", 10L, "t1", listOf(3f))

        assertEquals(listOf(second, sameTime, first), orderSensorReadings(listOf(first, second, sameTime)))
    }
}