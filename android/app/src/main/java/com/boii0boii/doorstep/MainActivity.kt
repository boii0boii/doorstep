package com.boii0boii.doorstep

import android.Manifest
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.boii0boii.doorstep.capture.CaptureService
import com.boii0boii.doorstep.data.AppSettings
import com.boii0boii.doorstep.data.Labels
import com.boii0boii.doorstep.data.RecordingStore
import com.boii0boii.doorstep.data.RecordingSummary
import com.boii0boii.doorstep.device.DeviceContextRepository
import com.boii0boii.doorstep.device.DoorFix
import com.boii0boii.doorstep.device.WifiCheck
import com.boii0boii.doorstep.location.isWithinAccuracyAwareRadius
import com.boii0boii.doorstep.monitor.DepartureMonitorService
import com.boii0boii.doorstep.monitor.Notifications
import com.boii0boii.doorstep.sensors.sensorAvailability
import com.boii0boii.doorstep.state.CaptureStatus
import com.boii0boii.doorstep.state.MonitorStatus
import com.boii0boii.doorstep.state.RecordingsChanged
import com.boii0boii.doorstep.ui.DoorstepScreen
import com.boii0boii.doorstep.ui.DoorstepUiState
import java.io.File
import kotlinx.coroutines.delay
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
                    DoorstepScreen(
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

    private fun uiState() = DoorstepUiState(
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
