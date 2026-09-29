package com.example.doorwaysensorrecorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.doorwaysensorrecorder.capture.CaptureService
import com.example.doorwaysensorrecorder.data.AppSettings
import com.example.doorwaysensorrecorder.data.Labels
import com.example.doorwaysensorrecorder.data.RecordingStore
import com.example.doorwaysensorrecorder.data.RecordingSummary
import com.example.doorwaysensorrecorder.device.DeviceContextRepository
import com.example.doorwaysensorrecorder.device.DoorFix
import com.example.doorwaysensorrecorder.device.WifiCheck
import com.example.doorwaysensorrecorder.location.isWithinAccuracyAwareRadius
import com.example.doorwaysensorrecorder.monitor.DepartureMonitorService
import com.example.doorwaysensorrecorder.monitor.Notifications
import com.example.doorwaysensorrecorder.sensors.sensorAvailability
import com.example.doorwaysensorrecorder.state.CaptureStatus
import com.example.doorwaysensorrecorder.state.MonitorStatus
import com.example.doorwaysensorrecorder.state.RecordingsChanged
import kotlinx.coroutines.delay
import java.io.File
import java.time.Instant

private const val KEY_DOOR_LAT = "door_latitude"
private const val KEY_DOOR_LON = "door_longitude"
private const val KEY_DOOR_SETUP_ACCURACY = "door_setup_accuracy"
private const val DOOR_RADIUS_METERS = 10.0
private const val MAX_FIX_AGE_MS = 15_000L

class MainActivity : ComponentActivity() {
    private lateinit var sensorManager: SensorManager
    private lateinit var store: RecordingStore
    private lateinit var deviceContext: DeviceContextRepository
    private val prefs by lazy { AppSettings.prefs(this) }

    private var pendingPermissionAction = ""
    private var mode by mutableStateOf("Reminders")
    private var label by mutableStateOf(Labels.DOOR_CROSSING)
    private var confirmUnmarkedDoorStop by mutableStateOf(false)
    private var elapsedSeconds by mutableStateOf(0L)
    private var monitorMessage by mutableStateOf("")
    private var monitorEnabled by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(true)
    private var recordingFiles by mutableStateOf(emptyList<RecordingSummary>())
    private var sensorAvailability by mutableStateOf(emptyMap<String, Boolean>())
    private var homeSsid by mutableStateOf("")
    private var homeSsidInput by mutableStateOf("")
    private var observedSsid by mutableStateOf("")
    private var wifiStatus by mutableStateOf("Check the connected Wi-Fi network on this device.")
    private var locationPermissionText by mutableStateOf("Not requested")
    private var locationStatus by mutableStateOf("No GPS test run yet.")
    private var savedDoorStatus by mutableStateOf("No main-door coordinate saved.")
    private var distanceToDoorMeters by mutableStateOf<Double?>(null)
    private var doorGatePassed by mutableStateOf<Boolean?>(null)
    private var radiusInput by mutableStateOf("10")
    private var testedRadiusMeters by mutableStateOf(DOOR_RADIUS_METERS)

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        refreshPermissionStatus()
        if (pendingPermissionAction == "monitor") {
            pendingPermissionAction = ""
            if (grants.isNotEmpty() && grants.values.none { it }) {
                monitorMessage = "Departure reminders need that permission. You can grant it in system settings."
            } else {
                requestEnableMonitor()
            }
            return@registerForActivityResult
        }
        val requested = pendingPermissionAction
        pendingPermissionAction = ""
        when (requested) {
            "wifi" -> if (hasFineLocation()) checkCurrentWifi() else wifiStatus = "SSID is unavailable without location permission on this Android version."
            "captureDoor" -> if (hasFineLocation()) captureDoorLocation() else locationStatus = "Location permission denied."
            "checkDoor" -> if (hasFineLocation()) checkCurrentDoorDistance() else locationStatus = "Location permission denied."
            "start" -> startRecording()
        }
    }

    private val createZipDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            CaptureStatus.message = "Export cancelled."
            return@registerForActivityResult
        }
        val archive = pendingArchive
        if (archive == null) {
            CaptureStatus.message = "Export failed: archive is missing."
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output -> archive.inputStream().use { it.copyTo(output) } }
                ?: error("Could not open the selected destination")
        }.onSuccess {
            CaptureStatus.message = "ZIP export saved."
        }.onFailure {
            CaptureStatus.message = "Export failed: ${it.localizedMessage}"
        }
    }

    private var pendingArchive: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = RecordingStore(applicationContext)
        deviceContext = DeviceContextRepository(applicationContext)
        sensorManager = getSystemService(SensorManager::class.java)
        Notifications.ensureChannels(this)
        homeSsid = AppSettings.homeSsid(this)
        homeSsidInput = homeSsid
        sensorAvailability = sensorAvailability(sensorManager, includeStepCounter = hasActivityRecognition())
        monitorEnabled = AppSettings.isMonitorEnabled(this)
        refreshPermissionStatus()
        refreshRecordings()
        if (monitorEnabled && !MonitorStatus.isRunning && monitorMissingRequirement() == null) {
            DepartureMonitorService.start(this)
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DoorwayScreen(
                        state = uiState(),
                        onModeChanged = { mode = it },
                        onLabelChanged = { label = it },
                        onStartRecording = ::requestStartRecording,
                        onStopRecording = ::requestStopRecording,
                        onConfirmUnmarkedStop = { confirmUnmarkedDoorStop = false; stopRecording() },
                        onCancelUnmarkedStop = { confirmUnmarkedDoorStop = false },
                        onMarkDoor = { CaptureService.mark(this) },
                        onHomeSsidInputChanged = { homeSsidInput = it },
                        onSaveHomeSsid = ::saveHomeSsid,
                        onCheckWifi = ::requestWifiCheck,
                        onUseObservedSsid = ::useObservedSsid,
                        onForgetWifi = ::forgetHomeWifi,
                        onCaptureDoor = ::requestDoorCapture,
                        onCheckDoor = ::requestDoorCheck,
                        onForgetDoor = ::forgetDoorLocation,
                        onRadiusChanged = { radiusInput = it.filter { char -> char.isDigit() || char == '.' } },
                        onExport = ::exportRecordings,
                        onDeleteRecording = ::deleteRecording,
                        onEnableMonitor = ::requestEnableMonitor,
                        onDisableMonitor = ::disableMonitor,
                        onTestAlert = { Notifications.showDepartureAlert(this, recordingId = null) },
                        onAllowBattery = ::requestUnrestrictedBattery
                    )
                    LaunchedEffect(CaptureStatus.isRecording, CaptureStatus.startedAtMillis) {
                        while (CaptureStatus.isRecording) {
                            elapsedSeconds = ((System.currentTimeMillis() - CaptureStatus.startedAtMillis) / 1000L).coerceAtLeast(0L)
                            delay(500)
                        }
                    }
                    LaunchedEffect(RecordingsChanged.version) { refreshRecordings() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private fun uiState() = DoorwayUiState(
        mode = mode,
        label = if (CaptureStatus.isRecording) CaptureStatus.label ?: label else label,
        isRecording = CaptureStatus.isRecording,
        confirmUnmarkedDoorStop = confirmUnmarkedDoorStop,
        status = CaptureStatus.message,
        elapsedSeconds = elapsedSeconds,
        sampleCount = CaptureStatus.sampleCount,
        recordingCount = recordingFiles.size,
        recordingFiles = recordingFiles,
        sensorAvailability = sensorAvailability,
        homeSsidInput = homeSsidInput,
        savedHomeSsid = homeSsid,
        observedSsid = observedSsid,
        wifiStatus = wifiStatus,
        locationPermission = locationPermissionText,
        locationStatus = locationStatus,
        savedDoorStatus = savedDoorStatus,
        distanceToDoorMeters = distanceToDoorMeters,
        doorGatePassed = doorGatePassed,
        radiusInput = radiusInput,
        testedRadiusMeters = testedRadiusMeters,
        monitorEnabled = monitorEnabled,
        monitorRunning = MonitorStatus.isRunning,
        monitorHomeWifi = MonitorStatus.homeWifi,
        monitorMotionActive = MonitorStatus.motionActive,
        monitorRecentChecks = MonitorStatus.recentChecks,
        monitorMessage = monitorMessage,
        batteryUnrestricted = batteryUnrestricted
    )

    private fun requestStartRecording() {
        val needsStepPermission = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER) != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        if (needsStepPermission) {
            pendingPermissionAction = "start"
            permissionsLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (CaptureStatus.isRecording) return
        val includeSteps = hasActivityRecognition()
        elapsedSeconds = 0L
        sensorAvailability = sensorAvailability(sensorManager, includeSteps)
        CaptureService.start(this, label, includeSteps)
    }

    private fun stopRecording() {
        CaptureService.stop(this)
    }

    private fun requestStopRecording() {
        if (CaptureStatus.label == Labels.DOOR_CROSSING && !CaptureStatus.hasDoorMarker) {
            confirmUnmarkedDoorStop = true
        } else {
            stopRecording()
        }
    }

    private fun saveHomeSsid() {
        val value = homeSsidInput.trim()
        if (value.isEmpty()) {
            wifiStatus = "Enter a network name or capture the connected SSID first."
            return
        }
        homeSsid = value
        prefs.edit().putString(AppSettings.KEY_HOME_SSID, value).apply()
        DepartureMonitorService.refreshHome(this)
        wifiStatus = "Home network saved locally. Check Current Wi-Fi to verify it."
    }

    private fun useObservedSsid() {
        if (observedSsid.isBlank()) return
        homeSsidInput = observedSsid
        saveHomeSsid()
    }

    private fun forgetHomeWifi() {
        prefs.edit().remove(AppSettings.KEY_HOME_SSID).apply()
        DepartureMonitorService.refreshHome(this)
        homeSsid = ""
        homeSsidInput = ""
        observedSsid = ""
        wifiStatus = "Home network forgotten."
    }

    private fun requestWifiCheck() {
        if (!hasFineLocation()) {
            pendingPermissionAction = "wifi"
            permissionsLauncher.launch(locationPermissions())
            return
        }
        checkCurrentWifi()
    }

    private fun checkCurrentWifi() {
        when (val result = deviceContext.currentWifi()) {
            is WifiCheck.Available -> {
                observedSsid = result.ssid
                wifiStatus = when {
                    homeSsid.isBlank() -> "SSID access works. Save this network as home Wi-Fi."
                    homeSsid == result.ssid -> "Home Wi-Fi matched."
                    else -> "SSID access works, but this network is not the saved home Wi-Fi."
                }
            }
            WifiCheck.NotConnectedToWifi -> {
                observedSsid = ""
                wifiStatus = "Not connected to Wi-Fi."
            }
            WifiCheck.Redacted -> {
                observedSsid = ""
                wifiStatus = "SSID is redacted by Android. Confirm location permission and that Location is enabled in system settings."
            }
        }
    }

    private fun requestDoorCapture() {
        if (!hasFineLocation()) {
            pendingPermissionAction = "captureDoor"
            permissionsLauncher.launch(locationPermissions())
            return
        }
        captureDoorLocation()
    }

    private fun requestDoorCheck() {
        if (!hasFineLocation()) {
            pendingPermissionAction = "checkDoor"
            permissionsLauncher.launch(locationPermissions())
            return
        }
        checkCurrentDoorDistance()
    }

    private fun captureDoorLocation() {
        locationStatus = "Waiting for a fresh GPS fix at the main door…"
        deviceContext.requestGpsFix { result ->
            result.onSuccess { fix ->
                val location = fix.location
                prefs.edit()
                    .putString(KEY_DOOR_LAT, location.latitude.toString())
                    .putString(KEY_DOOR_LON, location.longitude.toString())
                    .putFloat(KEY_DOOR_SETUP_ACCURACY, location.accuracy)
                    .apply()
                savedDoorStatus = "Saved locally · setup accuracy ${"%.1f".format(location.accuracy)} m"
                distanceToDoorMeters = 0.0
                val radius = currentRadius().also { testedRadiusMeters = it }
                doorGatePassed = fix.ageMillis <= MAX_FIX_AGE_MS && isWithinAccuracyAwareRadius(0.0, location.accuracy.toDouble(), radius)
                locationStatus = formatFix(fix, radius)
            }.onFailure {
                locationStatus = it.localizedMessage ?: "GPS location unavailable."
                doorGatePassed = false
            }
        }
    }

    private fun checkCurrentDoorDistance() {
        val latitude = prefs.getString(KEY_DOOR_LAT, null)?.toDoubleOrNull()
        val longitude = prefs.getString(KEY_DOOR_LON, null)?.toDoubleOrNull()
        if (latitude == null || longitude == null) {
            locationStatus = "Save the main-door location first."
            doorGatePassed = false
            return
        }
        locationStatus = "Waiting for a fresh GPS fix…"
        deviceContext.requestGpsFix { result ->
            result.onSuccess { fix ->
                val target = Location("saved-door").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                }
                val distance = fix.location.distanceTo(target).toDouble()
                distanceToDoorMeters = distance
                val radius = currentRadius().also { testedRadiusMeters = it }
                doorGatePassed = fix.ageMillis <= MAX_FIX_AGE_MS &&
                    isWithinAccuracyAwareRadius(distance, fix.location.accuracy.toDouble(), radius)
                locationStatus = formatFix(fix, radius)
            }.onFailure {
                locationStatus = it.localizedMessage ?: "GPS location unavailable."
                doorGatePassed = false
            }
        }
    }

    private fun formatFix(fix: DoorFix, radius: Double): String {
        val position = if (fix.ageMillis <= MAX_FIX_AGE_MS) "fresh" else "stale"
        return "GPS accuracy ${"%.1f".format(fix.location.accuracy)} m · fix ${fix.ageMillis / 1000}s old · $position · test radius ${"%.1f".format(radius)} m"
    }

    private fun forgetDoorLocation() {
        prefs.edit().remove(KEY_DOOR_LAT).remove(KEY_DOOR_LON).remove(KEY_DOOR_SETUP_ACCURACY).apply()
        savedDoorStatus = "No main-door coordinate saved."
        locationStatus = "Door location forgotten."
        distanceToDoorMeters = null
        doorGatePassed = null
    }

    private fun currentRadius(): Double = radiusInput.toDoubleOrNull()?.coerceIn(1.0, 100.0) ?: DOOR_RADIUS_METERS

    /** Returns what still blocks the departure monitor, or null when it can run. */
    private fun monitorMissingRequirement(): String? = when {
        AppSettings.homeSsid(this).isBlank() -> "home Wi-Fi"
        !hasFineLocation() -> "precise location"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED -> "background location"
        !hasActivityRecognition() -> "activity recognition"
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> "notifications"
        else -> null
    }

    private fun requestEnableMonitor() {
        pendingPermissionAction = "monitor"
        when (monitorMissingRequirement()) {
            "home Wi-Fi" -> {
                pendingPermissionAction = ""
                monitorMessage = "Save your home Wi-Fi in Test first."
            }
            "precise location" -> permissionsLauncher.launch(locationPermissions())
            "background location" -> {
                monitorMessage = "Choose \"Allow all the time\" so the Wi-Fi name stays readable while the phone is locked."
                permissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            }
            "activity recognition" -> permissionsLauncher.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
            "notifications" -> permissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            else -> {
                pendingPermissionAction = ""
                AppSettings.setMonitorEnabled(this, true)
                monitorEnabled = true
                sensorAvailability = sensorAvailability(sensorManager, includeStepCounter = true)
                DepartureMonitorService.start(this)
                monitorMessage = if (batteryUnrestricted) "Departure reminders are on." else
                    "Departure reminders are on. Allow unrestricted battery use so Android does not pause them."
            }
        }
    }

    private fun disableMonitor() {
        AppSettings.setMonitorEnabled(this, false)
        monitorEnabled = false
        DepartureMonitorService.stop(this)
        monitorMessage = "Departure reminders are off."
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestUnrestrictedBattery() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun exportRecordings() {
        if (recordingFiles.isEmpty()) {
            CaptureStatus.message = "No recordings to export."
            return
        }
        runCatching { store.exportZip(cacheDir) }
            .onSuccess { archive ->
                pendingArchive = archive
                createZipDocument.launch(archive.name)
            }
            .onFailure { CaptureStatus.message = "Export failed: ${it.localizedMessage}" }
    }

    private fun deleteRecording(fileName: String) {
        CaptureStatus.message = if (store.delete(fileName)) "Recording deleted." else "Could not delete recording."
        refreshRecordings()
    }

    private fun refreshRecordings() {
        recordingFiles = store.summaries()
    }

    private fun refreshPermissionStatus() {
        locationPermissionText = when {
            hasFineLocation() -> "Precise location granted"
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> "Approximate location only"
            else -> "Location not granted"
        }
    }

    private fun hasActivityRecognition() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locationPermissions() = arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
}

private data class DoorwayUiState(
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
private fun DoorwayScreen(
    state: DoorwayUiState,
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
    Scaffold(topBar = { TopAppBar(title = { Text("Doorway Sensor Recorder") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Local motion study", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    state: DoorwayUiState,
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
    state: DoorwayUiState,
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
    state: DoorwayUiState,
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
