package com.boii0boii.doorstep.monitor

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.boii0boii.doorstep.data.RecordingStore
import com.boii0boii.doorstep.state.RecordingsChanged
import org.json.JSONObject
import java.util.concurrent.Executors

/** Handles "Not leaving" on a keys reminder: the saved departure becomes a hard negative. */
class AlertFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_NOT_LEAVING) return
        val recordingId = intent.getStringExtra(EXTRA_RECORDING_ID) ?: return
        context.getSystemService(NotificationManager::class.java).cancel(Notifications.ID_ALERT)
        val appContext = context.applicationContext
        val pending = goAsync()
        executor.execute {
            try {
                RecordingStore(appContext).markNotLeaving(recordingId)
                MonitorLog(appContext).append(
                    JSONObject().put("event", "feedback").put("answer", "not leaving").put("recordingId", recordingId)
                )
                android.os.Handler(appContext.mainLooper).post { RecordingsChanged.bump() }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_NOT_LEAVING = "com.boii0boii.doorstep.action.NOT_LEAVING"
        const val EXTRA_RECORDING_ID = "recordingId"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
