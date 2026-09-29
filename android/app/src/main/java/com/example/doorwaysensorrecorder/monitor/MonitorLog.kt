package com.example.doorwaysensorrecorder.monitor

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Local JSON-lines log of every departure decision, including non-alerts, so false alerts and
 * missed departures can be counted later. Contains no SSID or location.
 */
class MonitorLog(context: Context) {
    private val file = File(context.filesDir, "monitor_log.jsonl")
    private val timeFormat = DateTimeFormatter.ofPattern("EEE HH:mm:ss").withZone(ZoneId.systemDefault())

    fun append(entry: JSONObject) = synchronized(LOCK) {
        file.appendText(entry.put("atUtc", Instant.now().toString()).toString() + "\n", Charsets.UTF_8)
        if (file.length() > MAX_BYTES) {
            val kept = file.readLines(Charsets.UTF_8).takeLast(KEEP_LINES)
            file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    fun recent(count: Int): List<String> = synchronized(LOCK) {
        if (!file.isFile) return emptyList()
        file.readLines(Charsets.UTF_8).takeLast(count).reversed().mapNotNull { line ->
            runCatching {
                val json = JSONObject(line)
                val time = timeFormat.format(Instant.parse(json.getString("atUtc")))
                val detail = when (json.optString("event")) {
                    "check" -> "${json.optString("outcome")} · ${json.optInt("steps")} steps"
                    "feedback" -> "you answered: ${json.optString("answer")}"
                    else -> json.optString("event")
                }
                "$time · $detail"
            }.getOrNull()
        }
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 256_000L
        private const val KEEP_LINES = 1_000
    }
}
