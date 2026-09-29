package com.boii0boii.doorstep.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorProximityTest {
    @Test
    fun requiresReportedUncertaintyToFitInsideTenMeters() {
        assertTrue(isWithinAccuracyAwareRadius(distanceMeters = 2.0, horizontalAccuracyMeters = 5.0))
        assertFalse(isWithinAccuracyAwareRadius(distanceMeters = 6.0, horizontalAccuracyMeters = 5.0))
    }

    @Test
    fun rejectsInvalidMeasurements() {
        assertFalse(isWithinAccuracyAwareRadius(distanceMeters = -1.0, horizontalAccuracyMeters = 1.0))
        assertFalse(isWithinAccuracyAwareRadius(distanceMeters = 1.0, horizontalAccuracyMeters = Double.NaN))
        assertFalse(isWithinAccuracyAwareRadius(distanceMeters = 1.0, horizontalAccuracyMeters = 1.0, radiusMeters = 0.0))
    }
}
