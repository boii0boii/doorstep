package com.example.doorwaysensorrecorder.capture

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.example.doorwaysensorrecorder.data.RecordingStore
import com.example.doorwaysensorrecorder.monitor.Notifications
import com.example.doorwaysensorrecorder.sensors.MotionCapture
import com.example.doorwaysensorrecorder.state.CaptureStatus
import com.example.doorwaysensorrecorder.state.RecordingsChanged
import java.util.concurrent.Executors

/**
 * Runs a labelled training recording as a foreground service with a partial wake lock, so sensor
 * events keep arriving with the screen off and the recording survives Activity recreation.
 */
class CaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var capture: MotionCapture? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DoorwaySensorRecorder:capture")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(
                label = intent.getStringExtra(EXTRA_LABEL) ?: return START_NOT_STICKY,
                includeStepCounter = intent.getBooleanExtra(EXTRA_INCLUDE_STEPS, false)
            )
            ACTION_MARK -> mark()
            ACTION_STOP -> stop()
            else -> if (capture == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (capture?.isRecording() == true) stop()
        if (wakeLock.isHeld) wakeLock.release()
        ioExecutor.shutdown()
        super.onDestroy()
    }

    private fun start(label: String, includeStepCounter: Boolean) {
        if (capture?.isRecording() == true) return
        val notification = Notifications.captureNotification(this, label, hasDoorMarker = false)
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifications.ID_CAPTURE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
            } else {
                startForeground(Notifications.ID_CAPTURE, notification)
            }
        }
        if (started.isFailure) {
            CaptureStatus.message = "Could not start background recording: ${started.exceptionOrNull()?.localizedMessage}"
            stopSelf()
            return
        }
        wakeLock.acquire(MAX_RECORDING_MS)
        val newCapture = MotionCapture(applicationContext) { count -> CaptureStatus.sampleCount = count }
        capture = newCapture
        newCapture.start(label, includeStepCounter)
        CaptureStatus.isRecording = true
        CaptureStatus.label = label
        CaptureStatus.startedAtMillis = System.currentTimeMillis()
        CaptureStatus.sampleCount = 0
        CaptureStatus.hasDoorMarker = false
        CaptureStatus.message = "Recording. It continues with the screen off."
    }

    private fun mark() {
        val current = capture ?: return
        if (!current.markDoor()) return
        CaptureStatus.hasDoorMarker = true
        CaptureStatus.message = "Door marker saved."
        val label = current.currentLabel() ?: return
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_CAPTURE, Notifications.captureNotification(this, label, hasDoorMarker = true))
    }

    private fun stop() {
        val current = capture ?: run { stopSelf(); return }
        capture = null
        val result = current.stop()
        if (wakeLock.isHeld) wakeLock.release()
        CaptureStatus.isRecording = false
        CaptureStatus.hasDoorMarker = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (result == null) {
            stopSelf()
            return
        }
        CaptureStatus.message = "Saving ${result.samples.size} sensor events…"
        val store = RecordingStore(applicationContext)
        ioExecutor.execute {
            val saved = runCatching { store.save(result) }
            mainHandler.post {
                CaptureStatus.message = saved.fold(
                    onSuccess = { "Saved ${result.samples.size} sensor events." },
                    onFailure = { "Save failed: ${it.localizedMessage}" }
                )
                RecordingsChanged.bump()
                stopSelf()
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.doorwaysensorrecorder.action.CAPTURE_START"
        const val ACTION_MARK = "com.example.doorwaysensorrecorder.action.CAPTURE_MARK"
        const val ACTION_STOP = "com.example.doorwaysensorrecorder.action.CAPTURE_STOP"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_INCLUDE_STEPS = "includeSteps"
        private const val MAX_RECORDING_MS = 30 * 60 * 1000L

        fun start(context: Context, label: String, includeStepCounter: Boolean) {
            context.startForegroundService(
                Intent(context, CaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_LABEL, label)
                    .putExtra(EXTRA_INCLUDE_STEPS, includeStepCounter)
            )
        }

        fun mark(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_MARK))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
