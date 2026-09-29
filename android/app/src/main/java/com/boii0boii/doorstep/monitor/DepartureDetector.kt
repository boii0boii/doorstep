package com.boii0boii.doorstep.monitor

import java.util.concurrent.TimeUnit

/**
 * Tier-B departure rule: the phone was on home Wi-Fi, lost it, did not regain it within a short
 * grace period, and the user was walking around the moment of loss. All times are
 * `elapsedRealtimeNanos`. Pure logic so it can be unit-tested without Android.
 */
data class DepartureConfig(
    val reconnectGraceNanos: Long = TimeUnit.SECONDS.toNanos(10),
    val walkingLookbackNanos: Long = TimeUnit.SECONDS.toNanos(90),
    val minSteps: Int = 8,
    val minHomeDwellNanos: Long = TimeUnit.MINUTES.toNanos(3),
    val cooldownNanos: Long = TimeUnit.MINUTES.toNanos(10)
)

enum class DepartureOutcome { ALERT, RECONNECTED, NOT_WALKING, JUST_ARRIVED, COOLDOWN }

data class DepartureCheck(
    val lostAtNanos: Long,
    val checkedAtNanos: Long,
    val outcome: DepartureOutcome,
    val stepsInWindow: Int
)

class DepartureDetector(private val config: DepartureConfig = DepartureConfig()) {
    private val steps = ArrayDeque<Long>()
    private var homeSinceNanos: Long? = null
    private var homeSinceBeforeLoss: Long? = null
    private var lostAtNanos: Long? = null
    private var reconnectedDuringGrace = false
    private var lastAlertLostAtNanos: Long? = null

    val isOnHomeWifi: Boolean get() = homeSinceNanos != null
    val hasPendingCheck: Boolean get() = lostAtNanos != null
    val graceNanos: Long get() = config.reconnectGraceNanos

    fun onStep(timestampNanos: Long) {
        steps.addLast(timestampNanos)
        val horizon = timestampNanos - config.walkingLookbackNanos - config.reconnectGraceNanos
        while (steps.isNotEmpty() && steps.first() < horizon) steps.removeFirst()
    }

    fun onHomeWifiConnected(nowNanos: Long) {
        if (homeSinceNanos != null) return
        if (lostAtNanos != null) {
            reconnectedDuringGrace = true
            homeSinceNanos = homeSinceBeforeLoss ?: nowNanos
        } else {
            homeSinceNanos = nowNanos
        }
    }

    /** Returns true when this loss starts a pending check that [evaluate] must resolve. */
    fun onHomeWifiLost(nowNanos: Long): Boolean {
        val since = homeSinceNanos ?: return false
        homeSinceNanos = null
        if (lostAtNanos != null) return false
        homeSinceBeforeLoss = since
        lostAtNanos = nowNanos
        reconnectedDuringGrace = false
        return true
    }

    /** Resolves the pending check once its grace period has elapsed; null if nothing is due. */
    fun evaluate(nowNanos: Long): DepartureCheck? {
        val lostAt = lostAtNanos ?: return null
        if (nowNanos - lostAt < config.reconnectGraceNanos) return null
        val stepCount = steps.count { it >= lostAt - config.walkingLookbackNanos && it <= nowNanos }
        val dwell = lostAt - (homeSinceBeforeLoss ?: lostAt)
        val lastAlert = lastAlertLostAtNanos
        val outcome = when {
            reconnectedDuringGrace -> DepartureOutcome.RECONNECTED
            dwell < config.minHomeDwellNanos -> DepartureOutcome.JUST_ARRIVED
            lastAlert != null && lostAt - lastAlert < config.cooldownNanos -> DepartureOutcome.COOLDOWN
            stepCount < config.minSteps -> DepartureOutcome.NOT_WALKING
            else -> DepartureOutcome.ALERT
        }
        if (outcome == DepartureOutcome.ALERT) lastAlertLostAtNanos = lostAt
        lostAtNanos = null
        homeSinceBeforeLoss = null
        reconnectedDuringGrace = false
        return DepartureCheck(lostAt, nowNanos, outcome, stepCount)
    }
}
