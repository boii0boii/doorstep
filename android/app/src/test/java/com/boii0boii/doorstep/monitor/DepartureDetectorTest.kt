package com.boii0boii.doorstep.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureDetectorTest {
    private val second = 1_000_000_000L
    private val minute = 60 * second

    private fun walk(detector: DepartureDetector, from: Long, count: Int) {
        repeat(count) { detector.onStep(from + it * second / 2) }
    }

    private fun settledAtHome(): DepartureDetector = DepartureDetector().apply { onHomeWifiConnected(0L) }

    @Test
    fun alertsWhenWalkingAndHomeWifiStaysLost() {
        val detector = settledAtHome()
        walk(detector, 10 * minute - 30 * second, 40)
        assertTrue(detector.onHomeWifiLost(10 * minute))
        assertNull(detector.evaluate(10 * minute + 5 * second))
        val check = detector.evaluate(10 * minute + 10 * second)!!
        assertEquals(DepartureOutcome.ALERT, check.outcome)
        assertFalse(detector.hasPendingCheck)
    }

    @Test
    fun reconnectWithinGraceIsNotADeparture() {
        val detector = settledAtHome()
        walk(detector, 10 * minute - 30 * second, 40)
        detector.onHomeWifiLost(10 * minute)
        detector.onHomeWifiConnected(10 * minute + 3 * second)
        assertEquals(DepartureOutcome.RECONNECTED, detector.evaluate(10 * minute + 10 * second)!!.outcome)
        assertTrue(detector.isOnHomeWifi)
    }

    @Test
    fun stationaryWifiDropIsNotADeparture() {
        val detector = settledAtHome()
        detector.onHomeWifiLost(10 * minute)
        val check = detector.evaluate(10 * minute + 10 * second)!!
        assertEquals(DepartureOutcome.NOT_WALKING, check.outcome)
        assertEquals(0, check.stepsInWindow)
    }

    @Test
    fun ignoresLossShortlyAfterArriving() {
        val detector = DepartureDetector().apply { onHomeWifiConnected(10 * minute) }
        walk(detector, 11 * minute, 40)
        detector.onHomeWifiLost(11 * minute + 30 * second)
        assertEquals(DepartureOutcome.JUST_ARRIVED, detector.evaluate(12 * minute)!!.outcome)
    }

    @Test
    fun cooldownSuppressesSecondAlert() {
        val detector = settledAtHome()
        walk(detector, 10 * minute - 30 * second, 40)
        detector.onHomeWifiLost(10 * minute)
        assertEquals(DepartureOutcome.ALERT, detector.evaluate(10 * minute + 10 * second)!!.outcome)

        detector.onHomeWifiConnected(11 * minute)
        walk(detector, 15 * minute - 30 * second, 40)
        detector.onHomeWifiLost(15 * minute)
        assertEquals(DepartureOutcome.COOLDOWN, detector.evaluate(15 * minute + 10 * second)!!.outcome)
    }

    @Test
    fun blipDoesNotResetHomeDwell() {
        val detector = settledAtHome()
        detector.onHomeWifiLost(5 * minute)
        detector.onHomeWifiConnected(5 * minute + 2 * second)
        detector.evaluate(5 * minute + 10 * second)
        walk(detector, 6 * minute - 30 * second, 40)
        detector.onHomeWifiLost(6 * minute)
        assertEquals(DepartureOutcome.ALERT, detector.evaluate(6 * minute + 10 * second)!!.outcome)
    }

    @Test
    fun lossWithoutPriorHomeWifiIsIgnored() {
        assertFalse(DepartureDetector().onHomeWifiLost(minute))
    }

    @Test
    fun ringBufferKeepsOnlyRecentWindow() {
        val buffer = SensorRingBuffer(windowNanos = 10 * second)
        (0..20).forEach { buffer.add(RawSensorEvent(1, "accel", it * second, floatArrayOf(0f))) }
        val kept = buffer.snapshot(0, Long.MAX_VALUE)
        assertEquals(10 * second, kept.first().elapsedRealtimeNanos)
        assertEquals(11, kept.size)
    }
}
