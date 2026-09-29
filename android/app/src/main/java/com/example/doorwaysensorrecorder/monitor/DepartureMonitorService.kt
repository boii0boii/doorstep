package com.example.doorwaysensorrecorder.monitor

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.example.doorwaysensorrecorder.data.AppSettings
import com.example.doorwaysensorrecorder.data.DeviceInfo
import com.example.doorwaysensorrecorder.data.Labels
import com.example.doorwaysensorrecorder.data.RecordingStore
import com.example.doorwaysensorrecorder.data.SensorReading
import com.example.doorwaysensorrecorder.data.SensorRecording
import com.example.doorwaysensorrecorder.data.utcTimestampForMonotonicEvent
import com.example.doorwaysensorrecorder.sensors.sensorAvailability
import com.example.doorwaysensorrecorder.state.MonitorStatus
import com.example.doorwaysensorrecorder.state.RecordingsChanged
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Foreground service that works with the phone locked in a pocket.
 *
 * While on home Wi-Fi it keeps motion sensors registered in hardware-batched mode (the sensor hub
 * samples while the CPU sleeps) and wakes the CPU only when steps occur, holding a short wake lock
 * while walking so a rolling sensor buffer stays filled. When home Wi-Fi is lost while walking and
 * does not come back within a grace period, it posts the keys reminder and saves the buffered
 * sensor data as an automatically labelled departure recording for later model training.
 */
class DepartureMonitorService : Service(), SensorEventListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val detector = DepartureDetector()
    private val buffer = SensorRingBuffer(BUFFER_WINDOW_NANOS)
    private lateinit var sensorManager: SensorManager
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var store: RecordingStore
    private lateinit var log: MonitorLog
    private lateinit var homeWifi: HomeWifiMonitor
    private var sensorThread: HandlerThread? = null
    private var sensorsRegistered = false
    private var significantMotion: Sensor? = null
    private val evaluateRunnable = Runnable { evaluatePendingDeparture() }
    private val motionIdleRunnable = Runnable { MonitorStatus.motionActive = false }

    private val significantMotionListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            onWalkingEvidence(event.timestamp)
            requestSignificantMotion()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        if (!enterForeground()) {
            MonitorStatus.homeWifi = "Could not start: grant activity recognition and notification access."
            stopSelf()
            return
        }
        sensorManager = getSystemService(SensorManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DoorwaySensorRecorder:departure")
            .apply { setReferenceCounted(false) }
        store = RecordingStore(applicationContext)
        log = MonitorLog(applicationContext)
        homeWifi = HomeWifiMonitor(this, mainHandler, { AppSettings.homeSsid(this) }, ::onHomeWifiState)
        homeWifi.start()
        MonitorStatus.isRunning = true
        MonitorStatus.recentChecks = log.recent(RECENT_CHECKS)
        log.append(JSONObject().put("event", "monitor-started"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_HOME && ::homeWifi.isInitialized) homeWifi.restart()
        return START_STICKY
    }

    override fun onDestroy() {
        if (::homeWifi.isInitialized) {
            homeWifi.stop()
            unregisterSensors()
            mainHandler.removeCallbacksAndMessages(null)
            if (wakeLock.isHeld) wakeLock.release()
            log.append(JSONObject().put("event", "monitor-stopped"))
        }
        ioExecutor.shutdown()
        MonitorStatus.isRunning = false
        MonitorStatus.motionActive = false
        if (::homeWifi.isInitialized) MonitorStatus.homeWifi = "Monitor stopped"
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = runCatching {
        val notification = Notifications.monitorNotification(this, "Waiting for home Wi-Fi.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(Notifications.ID_MONITOR, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(Notifications.ID_MONITOR, notification)
        }
    }.isSuccess

    private fun onHomeWifiState(state: HomeWifiState) {
        val now = SystemClock.elapsedRealtimeNanos()
        when (state) {
            HomeWifiState.HOME -> detector.onHomeWifiConnected(now)
            HomeWifiState.NOT_HOME -> if (detector.onHomeWifiLost(now)) {
                // Keep the CPU up through the grace period so post-exit motion is buffered too.
                val graceMillis = TimeUnit.NANOSECONDS.toMillis(detector.graceNanos)
                wakeLock.acquire(graceMillis + WAKE_MARGIN_MS)
                if (sensorsRegistered) sensorManager.flush(this)
                mainHandler.postDelayed(evaluateRunnable, graceMillis)
            }
            HomeWifiState.UNKNOWN -> Unit
        }
        val text = when (state) {
            HomeWifiState.HOME -> "On home Wi-Fi · armed"
            HomeWifiState.NOT_HOME -> "Not on home Wi-Fi"
            HomeWifiState.UNKNOWN -> "Wi-Fi name unavailable (check location access)"
        }
        MonitorStatus.homeWifi = text
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_MONITOR, Notifications.monitorNotification(this, text))
        updateSensorRegistration()
    }

    private fun evaluatePendingDeparture() {
        val now = SystemClock.elapsedRealtimeNanos()
        val check = detector.evaluate(now) ?: return
        var recordingId: String? = null
        if (check.outcome == DepartureOutcome.ALERT) {
            recordingId = UUID.randomUUID().toString()
            Notifications.showDepartureAlert(this, recordingId)
            saveDepartureRecording(recordingId, check)
        }
        log.append(
            JSONObject()
                .put("event", "check")
                .put("outcome", check.outcome.name)
                .put("steps", check.stepsInWindow)
                .put("recordingId", recordingId ?: JSONObject.NULL)
        )
        MonitorStatus.recentChecks = log.recent(RECENT_CHECKS)
        updateSensorRegistration()
    }

    private fun saveDepartureRecording(recordingId: String, check: DepartureCheck) {
        val fromNanos = check.lostAtNanos - PRE_LOSS_NANOS
        val events = buffer.snapshot(fromNanos, check.checkedAtNanos)
        if (events.isEmpty()) return
        val nowInstant = Instant.now()
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val startInstant = nowInstant.minusNanos(nowNanos - fromNanos)
        val includeSteps = hasActivityRecognition()
        val availability = sensorAvailability(sensorManager, includeSteps)
        val device = DeviceInfo.current(this)
        wakeLock.acquire(SAVE_WAKE_MS)
        ioExecutor.execute {
            runCatching {
                val readings = events.map { event ->
                    SensorReading(
                        sensorType = event.sensorType,
                        sensorName = event.sensorName,
                        elapsedRealtimeNanos = event.elapsedRealtimeNanos,
                        timestampUtc = utcTimestampForMonotonicEvent(startInstant, fromNanos, event.elapsedRealtimeNanos),
                        values = event.values.toList()
                    )
                }
                store.save(
                    SensorRecording(
                        recordingId = recordingId,
                        sessionId = LocalDate.now(ZoneOffset.UTC).toString(),
                        label = Labels.DEPARTURE_AUTO,
                        startedAtUtc = startInstant.toString(),
                        endedAtUtc = nowInstant.toString(),
                        startElapsedRealtimeNanos = fromNanos,
                        endElapsedRealtimeNanos = nowNanos,
                        doorMarkerAtUtc = null,
                        doorMarkerElapsedRealtimeNanos = null,
                        deviceModel = device.deviceModel,
                        androidRelease = device.androidRelease,
                        appVersion = device.appVersion,
                        sensorAvailability = availability,
                        samples = readings,
                        captureMode = SensorRecording.CAPTURE_AUTO_DEPARTURE,
                        homeWifiLostAtUtc = utcTimestampForMonotonicEvent(startInstant, fromNanos, check.lostAtNanos),
                        homeWifiLostElapsedRealtimeNanos = check.lostAtNanos
                    )
                )
                store.applyPendingFeedback(recordingId)
            }
            mainHandler.post { RecordingsChanged.bump() }
        }
    }

    /** Sensors run only while on home Wi-Fi or while a departure check is pending. */
    private fun updateSensorRegistration() {
        val shouldListen = detector.isOnHomeWifi || detector.hasPendingCheck
        if (shouldListen && !sensorsRegistered) registerSensors()
        if (!shouldListen && sensorsRegistered) {
            unregisterSensors()
            buffer.clear()
            if (wakeLock.isHeld) wakeLock.release()
            MonitorStatus.motionActive = false
        }
    }

    private fun registerSensors() {
        val thread = HandlerThread("DepartureSensors").apply { start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        MOTION_SENSORS.forEach { (type, periodUs) ->
            sensorManager.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, periodUs, MAX_REPORT_LATENCY_US, handler)
            }
        }
        if (hasActivityRecognition()) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US, handler)
            }
            val wakeUpStepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, true)
            if (wakeUpStepDetector != null) {
                sensorManager.registerListener(this, wakeUpStepDetector, SensorManager.SENSOR_DELAY_NORMAL, 0, handler)
            } else {
                sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, MAX_REPORT_LATENCY_US, handler)
                }
                requestSignificantMotion()
            }
        } else {
            requestSignificantMotion()
        }
        sensorsRegistered = true
    }

    private fun unregisterSensors() {
        if (!sensorsRegistered) return
        sensorManager.unregisterListener(this)
        significantMotion?.let { sensorManager.cancelTriggerSensor(significantMotionListener, it) }
        significantMotion = null
        sensorThread?.quitSafely()
        sensorThread = null
        sensorsRegistered = false
    }

    private fun requestSignificantMotion() {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) ?: return
        significantMotion = sensor
        sensorManager.requestTriggerSensor(significantMotionListener, sensor)
    }

    /**
     * Called on a step (or significant-motion trigger). Holds the CPU awake while walking and, when
     * waking from sleep, flushes the hardware FIFO so the seconds before the first step are kept.
     */
    private fun onWalkingEvidence(timestampNanos: Long) {
        val wasAwake = wakeLock.isHeld
        wakeLock.acquire(ACTIVE_HOLD_MS)
        if (!wasAwake && sensorsRegistered) sensorManager.flush(this)
        mainHandler.post {
            detector.onStep(timestampNanos)
            MonitorStatus.motionActive = true
            mainHandler.removeCallbacks(motionIdleRunnable)
            mainHandler.postDelayed(motionIdleRunnable, ACTIVE_HOLD_MS)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        buffer.add(RawSensorEvent(event.sensor.type, event.sensor.name, event.timestamp, event.values.copyOf()))
        if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) onWalkingEvidence(event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun hasActivityRecognition() =
        checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_REFRESH_HOME = "com.example.doorwaysensorrecorder.action.REFRESH_HOME"

        private const val RECENT_CHECKS = 6
        private const val MAX_REPORT_LATENCY_US = 1_000_000
        private const val ACTIVE_HOLD_MS = 30_000L
        private const val WAKE_MARGIN_MS = 5_000L
        private const val SAVE_WAKE_MS = 30_000L
        private val PRE_LOSS_NANOS = TimeUnit.SECONDS.toNanos(120)
        private val BUFFER_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(180)

        // Same streams and rates as manual training recordings, so both feed one model.
        private val MOTION_SENSORS = listOf(
            Sensor.TYPE_ACCELEROMETER to 20_000,
            Sensor.TYPE_LINEAR_ACCELERATION to 20_000,
            Sensor.TYPE_GYROSCOPE to 20_000,
            Sensor.TYPE_ROTATION_VECTOR to 50_000,
            Sensor.TYPE_MAGNETIC_FIELD to 50_000,
            Sensor.TYPE_PRESSURE to 50_000
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DepartureMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DepartureMonitorService::class.java))
        }

        fun refreshHome(context: Context) {
            if (!MonitorStatus.isRunning) return
            context.startService(Intent(context, DepartureMonitorService::class.java).setAction(ACTION_REFRESH_HOME))
        }
    }
}
