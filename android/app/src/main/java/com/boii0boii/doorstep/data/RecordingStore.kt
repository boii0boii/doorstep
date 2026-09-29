package com.boii0boii.doorstep.data

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class RecordingStore(context: Context) {
    private val recordingDirectory = File(context.filesDir, "recordings").apply { mkdirs() }

    fun save(recording: SensorRecording): File = synchronized(LOCK) {
        writeAtomically(recording.recordingId, recording.toJson().toString(2))
    }

    /**
     * Records the user's "Not leaving" answer to an automatic departure alert. The alert can be
     * answered before the recording finishes saving, so the answer is kept as a marker file and
     * applied by [applyPendingFeedback] whichever happens last.
     */
    fun markNotLeaving(recordingId: String): Boolean = synchronized(LOCK) {
        if (!isRecordingId(recordingId)) return false
        File(recordingDirectory, "$recordingId$NOT_LEAVING_SUFFIX").writeText("", Charsets.UTF_8)
        applyPendingFeedback(recordingId)
    }

    fun applyPendingFeedback(recordingId: String): Boolean = synchronized(LOCK) {
        if (!isRecordingId(recordingId)) return false
        val marker = File(recordingDirectory, "$recordingId$NOT_LEAVING_SUFFIX")
        val file = File(recordingDirectory, "$recordingId.json")
        if (!marker.isFile || !file.isFile) return false
        val json = JSONObject(file.readText(Charsets.UTF_8))
            .put("label", Labels.NORMAL_MOVEMENT)
            .put("userFeedback", "not-leaving")
        writeAtomically(recordingId, json.toString(2))
        marker.delete()
        true
    }

    private fun writeAtomically(recordingId: String, content: String): File {
        val destination = File(recordingDirectory, "$recordingId.json")
        val temporary = File(recordingDirectory, "$recordingId.json.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        return destination
    }

    private fun isRecordingId(value: String) = runCatching { UUID.fromString(value) }.isSuccess

    fun files(): List<File> = recordingDirectory.listFiles { file ->
        file.isFile && file.extension == "json"
    }?.sortedByDescending(File::lastModified).orEmpty()

    fun summaries(): List<RecordingSummary> = files().mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            RecordingSummary(
                fileName = file.name,
                label = json.optString("label", "Recording") +
                    if (json.optString("captureMode") == SensorRecording.CAPTURE_AUTO_DEPARTURE) " · auto" else "",
                startedAtUtc = json.optString("startedAtUtc", "")
            )
        }.getOrNull()
    }

    fun delete(fileName: String): Boolean {
        val candidate = File(recordingDirectory, fileName).canonicalFile
        if (candidate.parentFile != recordingDirectory.canonicalFile) return false
        return candidate.isFile && candidate.delete()
    }

    fun exportZip(cacheDirectory: File): File {
        val archive = File(cacheDirectory, "DoorwaySensorRecordings-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            files().forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return archive
    }

    companion object {
        private val LOCK = Any()
        private const val NOT_LEAVING_SUFFIX = ".not-leaving"
    }
}
