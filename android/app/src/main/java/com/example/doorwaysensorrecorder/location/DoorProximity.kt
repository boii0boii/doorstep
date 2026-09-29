package com.example.doorwaysensorrecorder.location

/** Returns true only when the full reported uncertainty radius fits in the target radius. */
fun isWithinAccuracyAwareRadius(
    distanceMeters: Double,
    horizontalAccuracyMeters: Double,
    radiusMeters: Double = 10.0
): Boolean = distanceMeters.isFinite() &&
    horizontalAccuracyMeters.isFinite() &&
    radiusMeters.isFinite() &&
    distanceMeters >= 0.0 &&
    horizontalAccuracyMeters >= 0.0 &&
    radiusMeters > 0.0 &&
    distanceMeters + horizontalAccuracyMeters <= radiusMeters
