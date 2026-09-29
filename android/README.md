# Doorstep for Android

Native Kotlin/Jetpack Compose app (package `com.boii0boii.doorstep`). Android is the first platform because it exposes the connected Wi-Fi name through public APIs, without the Apple entitlement that a free iOS developer account cannot provision. See the [project README](../README.md) for the overview.

## Current scope

- **Reminders (keys reminder, works locked in a pocket):** a foreground service (`monitor/DepartureMonitorService`) watches the connected Wi-Fi with a network callback. While on the saved home SSID it keeps motion sensors registered in hardware-batched mode and wakes the CPU only on steps. When home Wi-Fi is lost while you have been walking, and it does not return within 10 s, it posts a high-priority "Leaving home? Check you have your keys." notification (sound + vibration). Suppressed when stationary, within 3 min of arriving, or within a 10 min cooldown. Every decision is logged to `files/monitor_log.jsonl`.
- **Self-labelling data:** each alert saves the preceding 120 s plus the grace period of buffered sensor data as a `Departure (auto)` recording (`captureMode: auto-departure`, with `homeWifiLostElapsedRealtimeNanos`). Tapping **Not leaving** on the alert relabels it `Normal Movement` with `userFeedback: not-leaving` (a hard negative). These recordings are the training set for an earlier, at-the-door motion model.
- **Training:** record Door Crossing and Normal Movement labels, record raw available motion sensor events with monotonic timestamps mapped to UTC, mark the doorway event, save JSON locally, and export all recordings as a ZIP through the system document picker.
- **Test:** save/check the home Wi-Fi SSID, save a main-door GPS coordinate, and inspect GPS age, horizontal accuracy, distance, and an accuracy-aware foreground test radius (10 m by default).
- Training recordings run in a foreground service with a wake lock, so they continue with the screen locked and survive rotation; the recording notification has **Mark door** and **Stop** actions.
- The GPS door radius is a foreground diagnostic only. It is not used by reminders: indoor GPS in a pocket is too inaccurate and slow for a doorway gate.
- Motion-model training and on-device inference are not implemented yet.
- Recordings and settings are app-private. No account, server, analytics, advertising, cloud sync, or `INTERNET` permission is used.

## Requirements

- Android Studio with Android SDK Platform 35 and Build Tools installed.
- JDK 17 (Android Studio's bundled JBR is suitable).
- A physical Android phone for sensor, Wi-Fi, and GPS validation. An emulator cannot validate pocket motion or real indoor GPS.

Open this `android/` directory as a project in Android Studio and allow Gradle sync. Select a connected phone with USB debugging enabled and run the `app` configuration. From a terminal in this directory, `./gradlew testDebugUnitTest` runs the unit tests and `./gradlew assembleDebug` builds the debug APK.

## Permissions

The app asks for location only when checking the current Wi-Fi identity or setting/checking the door coordinate. Android may redact the SSID as `UNKNOWN_SSID` if the required location authorization or system Location setting is unavailable. `ACCESS_WIFI_STATE` is declared for current network information. `ACTIVITY_RECOGNITION` is requested only when the phone has a step-counter sensor; denying it leaves other sensor recording usable.

Turning on **Reminders** additionally requires: precise location plus **Allow all the time** (Android redacts the SSID for background reads otherwise), activity recognition (step detection), and notifications on Android 13+. Also allow unrestricted battery use from the Reminders screen so the OEM battery manager does not stop the service. The monitor restarts after reboot and app updates if it was on. A persistent low-priority notification is shown while it runs; this is required for background sensor access. The 10 m rule is an app-side foreground check: `distanceToDoor + horizontalAccuracy <= radius`. Android's OS geofence is deliberately not registered at 10 m; reliable background geofencing generally needs a much larger radius and may deliver delayed callbacks.

## First physical-device test

1. Install and open the app, then select **Test**.
2. While connected to home Wi-Fi, tap **Check current Wi-Fi** and grant location permission when asked. Confirm the displayed SSID; tap **Use as home** and **Save**.
3. Stand at the main door and tap **Save this point as door**. Note the reported GPS accuracy.
4. Move around inside near the door and tap **Check distance**. A 10 m pass requires the measured distance plus reported accuracy to fit within 10 m. If indoor GPS uncertainty is larger, it should report the gate as not met rather than claiming a precise fix.
5. Repeat the Wi-Fi check at another network and confirm it does not match the saved home SSID.
6. Use **Training** to record both labels. For positives, press **MARK DOOR** consistently as the phone crosses the threshold; negatives should include approaching/opening the door without crossing.
7. Tap **Export ZIP** and choose a destination in the system document picker.

## Data format

The ZIP contains one JSON file per recording. Schema version 2 adds `captureMode` (`manual` or `auto-departure`), `homeWifiLostAtUtc`, and `homeWifiLostElapsedRealtimeNanos`; auto recordings may include step-detector events (sensor type 18). Each sensor event contains sensor type/name, raw values and `SensorEvent.timestamp` (`elapsedRealtimeNanos`). Its `timestampUtc` is derived from the monotonic clock at recording start, avoiding callback-arrival time as the sample time. Recording metadata includes schema version, recording/session IDs, label, start/end times in UTC and monotonic time, optional door-marker times, device/OS/app metadata, and sensor availability.

The home SSID and saved door coordinates are not included in recording JSON or ZIP exports.

## Validation

Unit tests cover the proximity predicate, monotonic-to-UTC timestamp mapping, the departure decision rules (walking, reconnect grace, arrival dwell, cooldown), and the rolling sensor buffer. Run them from Android Studio or with `./gradlew testDebugUnitTest`.
