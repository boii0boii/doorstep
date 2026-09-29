package com.boii0boii.doorstep.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.boii0boii.doorstep.MainActivity
import com.boii0boii.doorstep.R
import com.boii0boii.doorstep.capture.CaptureService
import com.boii0boii.doorstep.data.Labels

object Notifications {
    const val CHANNEL_MONITOR = "departure_monitor"
    const val CHANNEL_ALERT = "departure_alert"
    const val CHANNEL_CAPTURE = "training_capture"
    const val ID_MONITOR = 1
    const val ID_CAPTURE = 2
    const val ID_ALERT = 3

    private val ALERT_VIBRATION = longArrayOf(0, 400, 200, 400, 200, 800)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Departure monitor", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the app watches for you leaving home."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CAPTURE, "Training recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a labelled training recording is running."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Keys reminder", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminder to check your keys when you leave home."
                enableVibration(true)
                vibrationPattern = ALERT_VIBRATION
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
                )
            }
        )
    }

    fun monitorNotification(context: Context, text: String): Notification =
        Notification.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_key)
            .setContentTitle("Watching for departures")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    fun captureNotification(context: Context, label: String, hasDoorMarker: Boolean): Notification {
        val builder = Notification.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_key)
            .setContentTitle("Recording: $label")
            .setContentText(if (hasDoorMarker) "Door marker saved." else "Recording continues with the screen off.")
            .setOngoing(true)
            .setContentIntent(openApp(context))
        if (label == Labels.DOOR_CROSSING && !hasDoorMarker) {
            builder.addAction(action(context, "Mark door", CaptureService.ACTION_MARK, 1))
        }
        builder.addAction(action(context, "Stop", CaptureService.ACTION_STOP, 2))
        return builder.build()
    }

    /** Posts the keys reminder. [recordingId] links the "Not leaving" answer to the saved data. */
    fun showDepartureAlert(context: Context, recordingId: String?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val builder = Notification.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_key)
            .setContentTitle("Leaving home?")
            .setContentText("Check you have your keys.")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        if (recordingId != null) {
            val notLeaving = Intent(context, AlertFeedbackReceiver::class.java)
                .setAction(AlertFeedbackReceiver.ACTION_NOT_LEAVING)
                .putExtra(AlertFeedbackReceiver.EXTRA_RECORDING_ID, recordingId)
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Not leaving",
                    PendingIntent.getBroadcast(context, 3, notLeaving, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                ).build()
            )
        }
        manager.notify(ID_ALERT, builder.build())
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(context: Context, title: String, serviceAction: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            null,
            title,
            PendingIntent.getService(
                context,
                requestCode,
                Intent(context, CaptureService::class.java).setAction(serviceAction),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()
}
