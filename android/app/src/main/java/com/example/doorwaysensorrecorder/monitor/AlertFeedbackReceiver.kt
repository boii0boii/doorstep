package com.example.doorwaysensorrecorder.monitor

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.doorwaysensorrecorder.data.RecordingStore
import com.example.doorwaysensorrecorder.state.RecordingsChanged
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
        const val ACTION_NOT_LEAVING = "com.example.doorwaysensorrecorder.action.NOT_LEAVING"
        const val EXTRA_RECORDING_ID = "recordingId"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
