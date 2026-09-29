package com.boii0boii.doorstep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.boii0boii.doorstep.data.Labels
import com.boii0boii.doorstep.data.RecordingSummary
import com.boii0boii.doorstep.sensors.sensorAvailability

internal data class DoorstepUiState(
    val mode: String,
    val label: String,
    val isRecording: Boolean,
    val confirmUnmarkedDoorStop: Boolean,
    val status: String,
    val elapsedSeconds: Long,
    val sampleCount: Int,
    val recordingCount: Int,
    val recordingFiles: List<RecordingSummary>,
    val sensorAvailability: Map<String, Boolean>,
    val homeSsidInput: String,
    val savedHomeSsid: String,
    val observedSsid: String,
    val wifiStatus: String,
    val locationPermission: String,
    val locationStatus: String,
    val savedDoorStatus: String,
    val distanceToDoorMeters: Double?,
    val doorGatePassed: Boolean?,
    val radiusInput: String,
    val testedRadiusMeters: Double,
    val monitorEnabled: Boolean,
    val monitorRunning: Boolean,
    val monitorHomeWifi: String,
    val monitorMotionActive: Boolean,
    val monitorRecentChecks: List<String>,
    val monitorMessage: String,
    val batteryUnrestricted: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DoorstepScreen(
    state: DoorstepUiState,
    onModeChanged: (String) -> Unit,
    onLabelChanged: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onConfirmUnmarkedStop: () -> Unit,
    onCancelUnmarkedStop: () -> Unit,
    onMarkDoor: () -> Unit,
    onHomeSsidInputChanged: (String) -> Unit,
    onSaveHomeSsid: () -> Unit,
    onCheckWifi: () -> Unit,
    onUseObservedSsid: () -> Unit,
    onForgetWifi: () -> Unit,
    onCaptureDoor: () -> Unit,
    onCheckDoor: () -> Unit,
    onForgetDoor: () -> Unit,
    onRadiusChanged: (String) -> Unit,
    onExport: () -> Unit,
    onDeleteRecording: (String) -> Unit,
    onEnableMonitor: () -> Unit,
    onDisableMonitor: () -> Unit,
    onTestAlert: () -> Unit,
    onAllowBattery: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Doorstep") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Keys reminder · everything stays on this phone", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Reminders", "Training", "Test").forEach { option ->
                    FilterChip(selected = state.mode == option, onClick = { onModeChanged(option) }, label = { Text(option) })
                }
            }
            if (state.mode == "Reminders") {
                RemindersContent(state, onEnableMonitor, onDisableMonitor, onTestAlert, onAllowBattery)
            } else if (state.mode == "Training") {
                TrainingContent(state, onLabelChanged, onStartRecording, onStopRecording, onMarkDoor, onExport, onDeleteRecording)
            } else {
                TestContent(state, onHomeSsidInputChanged, onSaveHomeSsid, onCheckWifi, onUseObservedSsid,
                    onForgetWifi, onCaptureDoor, onCheckDoor, onForgetDoor, onRadiusChanged)
            }
            if (state.confirmUnmarkedDoorStop) {
                AlertDialog(
                    onDismissRequest = onCancelUnmarkedStop,
                    title = { Text("No door marker") },
                    text = {
                        Text("This Door Crossing recording has no MARK DOOR timestamp. It cannot be aligned to the threshold event for event-timed training. Keep recording to mark the crossing, or explicitly save it unmarked.")
                    },
                    confirmButton = {
                        Button(onClick = onConfirmUnmarkedStop) { Text("Save unmarked") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = onCancelUnmarkedStop) { Text("Keep recording") }
                    }
                )
            }
        }
    }
}

@Composable
private fun TrainingContent(
    state: DoorstepUiState,
    onLabelChanged: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onMark: () -> Unit,
    onExport: () -> Unit,
    onDeleteRecording: (String) -> Unit
) {
    SectionBlock("Recording") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Labels.DOOR_CROSSING, Labels.NORMAL_MOVEMENT).forEach { option ->
                FilterChip(selected = state.label == option, onClick = { if (!state.isRecording) onLabelChanged(option) }, label = { Text(option) })
            }
        }
        Text(if (state.isRecording) "Walk normally through your front door. Recording continues with the screen locked; the notification has Mark door and Stop." else "Record labeled examples with the phone in its usual pocket.")
        Text("Status: ${state.status}")
        if (state.isRecording) {
            Text("Elapsed ${state.elapsedSeconds / 60}:${(state.elapsedSeconds % 60).toString().padStart(2, '0')} · ${state.sampleCount} sensor events")
            if (state.label == Labels.DOOR_CROSSING) OutlinedButton(onClick = onMark) { Text("MARK DOOR") }
            Button(onClick = onStop) { Text("STOP RECORDING") }
        } else {
            Button(onClick = onStart) { Text("Start Recording") }
        }
    }
    SectionBlock("Sensor availability") {
        state.sensorAvailability.forEach { (name, available) ->
            Text("${if (available) "Available" else "Unavailable"} · $name")
        }
        Text("Android activity classification is not recorded in this first build. Sensor events use monotonic timestamps.", style = MaterialTheme.typography.bodySmall)
    }
    SectionBlock("Saved recordings (${state.recordingCount})") {
        if (state.recordingFiles.isEmpty()) Text("No recordings yet.")
        state.recordingFiles.take(10).forEach { item ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.label, fontWeight = FontWeight.Medium)
                    Text("${item.startedAtUtc} · ${item.fileName}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onDeleteRecording(item.fileName) }, enabled = !state.isRecording) {
                    Text("Delete")
                }
            }
        }
        OutlinedButton(onClick = onExport, enabled = state.recordingCount > 0 && !state.isRecording) { Text("Export ZIP") }
    }
}

@Composable
private fun RemindersContent(
    state: DoorstepUiState,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onTestAlert: () -> Unit,
    onAllowBattery: () -> Unit
) {
    SectionBlock("Keys reminder") {
        Text("Works with the phone locked in your pocket. When you walk out and your home Wi-Fi drops and stays gone, the phone vibrates and rings a \"check your keys\" reminder.")
        Text(
            "This first version alerts shortly after you step outside, typically before you are far from the door. " +
                "Each alert also saves the preceding two minutes of motion so a door-crossing model can be trained to alert earlier.",
            style = MaterialTheme.typography.bodySmall
        )
        if (state.monitorEnabled) {
            Button(onClick = onDisable) { Text("Turn off reminders") }
        } else {
            Button(onClick = onEnable) { Text("Turn on reminders") }
        }
        if (state.monitorMessage.isNotBlank()) Text(state.monitorMessage, style = MaterialTheme.typography.bodySmall)
        if (!state.batteryUnrestricted) {
            OutlinedButton(onClick = onAllowBattery) { Text("Allow unrestricted battery use") }
        }
        OutlinedButton(onClick = onTestAlert) { Text("Test alert sound") }
    }
    SectionBlock("Status") {
        Text("Monitor: ${if (state.monitorRunning) "running" else "stopped"}")
        Text("Home Wi-Fi: ${if (state.savedHomeSsid.isBlank()) "not set (see Test)" else state.monitorHomeWifi}")
        Text("Motion: ${if (state.monitorMotionActive) "walking — buffering sensors" else "idle"}")
    }
    SectionBlock("Recent decisions") {
        if (state.monitorRecentChecks.isEmpty()) Text("None yet. Each time home Wi-Fi drops, the outcome appears here.")
        state.monitorRecentChecks.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text(
            "ALERT = reminder sent · NOT_WALKING = Wi-Fi dropped while still · RECONNECTED = brief drop · JUST_ARRIVED / COOLDOWN = suppressed.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun TestContent(
    state: DoorstepUiState,
    onSsidChanged: (String) -> Unit,
    onSaveSsid: () -> Unit,
    onCheckWifi: () -> Unit,
    onUseObserved: () -> Unit,
    onForgetWifi: () -> Unit,
    onCaptureDoor: () -> Unit,
    onCheckDoor: () -> Unit,
    onForgetDoor: () -> Unit,
    onRadiusChanged: (String) -> Unit
) {
    SectionBlock("Home Wi-Fi") {
        Text("Permission state: ${state.locationPermission}", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.homeSsidInput,
            onValueChange = onSsidChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Home network name (SSID)") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSaveSsid, enabled = state.homeSsidInput.isNotBlank()) { Text("Save") }
            OutlinedButton(onClick = onCheckWifi) { Text("Check current Wi-Fi") }
        }
        if (state.observedSsid.isNotBlank()) {
            Text("Current network: ${state.observedSsid}")
            OutlinedButton(onClick = onUseObserved) { Text("Use as home") }
        }
        Text(state.wifiStatus, style = MaterialTheme.typography.bodySmall)
        if (state.savedHomeSsid.isNotBlank()) {
            Text("Saved locally: ${state.savedHomeSsid}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onForgetWifi) { Text("Forget home Wi-Fi") }
        }
    }
    SectionBlock("Main-door GPS check") {
        Text(state.savedDoorStatus, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.radiusInput,
            onValueChange = onRadiusChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Foreground test radius (metres)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCaptureDoor) { Text("Save this point as door") }
            OutlinedButton(onClick = onCheckDoor) { Text("Check distance") }
        }
        state.distanceToDoorMeters?.let { Text("Distance to saved door: ${"%.1f".format(it)} m") }
        state.doorGatePassed?.let { passed ->
            val radius = "%.1f".format(state.testedRadiusMeters)
            Text(if (passed) "$radius m proximity test: PASS" else "$radius m proximity test: NOT MET")
        }
        Text(state.locationStatus, style = MaterialTheme.typography.bodySmall)
        if (state.savedDoorStatus != "No main-door coordinate saved.") OutlinedButton(onClick = onForgetDoor) { Text("Forget door location") }
    }
    SectionBlock("Detection") {
        Text("No motion model is installed yet. Keys reminders use home Wi-Fi loss while walking; see Reminders.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionBlock(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HorizontalDivider()
            content()
        }
    }
}
