# Android Companion App Plan

> **Design history.** This is the original planning document. The approach changed once the phone-locked-in-a-pocket constraint was worked through: reminders no longer use GPS as a gate, and instead combine home Wi-Fi loss with walking, detected by a foreground service. See the [README](../README.md#how-it-works) for the current design.

## Goal and recommendation

Build a native Android app that can collect the personal doorway dataset and, after offline training, run the same location-gated pattern detector entirely on-device. The repository retains the iOS/Xcode recorder and now includes the Android v1 under `android/`.

**Implementation priority:** start Android first. The current Apple Personal Team cannot provision iOS's Access Wi-Fi Information entitlement, which blocks a signed iPhone build of the three-gate prototype. Android exposes current network information through public APIs without an Apple-style signing entitlement. It still requires runtime permissions and physical-device validation. Keep the iOS recorder intact; do not remove the home-Wi-Fi gate from the product design.

**Current Android v1 status:** a Kotlin/Compose app exists under `android/` with Training and Test modes, local JSON recording/ZIP export, individual recording deletion, current-SSID diagnostics, a saved GPS door coordinate, and a configurable accuracy-aware foreground radius (10 m by default). ML, notification, and background-geofence behavior are not implemented. `./gradlew testDebugUnitTest assembleDebug` passes using Android Studio's bundled JDK and SDK 35. Real Wi-Fi/GPS behavior still requires a physical Android phone.

Use Kotlin and Jetpack Compose for Android, while keeping the existing SwiftUI app native on iOS. Put Android in a standalone `android/` Gradle project so it does not disturb the Xcode project. Do not port the iOS UI or wrap it in a web view. Share the data contract, Python preparation/training/evaluation pipeline, feature specification, model parameters, and behavioral tests. Keep platform sensor, location, permission, background, storage, and notification code native.

For the first small personalized baseline, train regularized logistic regression in Python and export a versioned JSON artifact containing feature names/order, scaling values, weights, intercept, threshold, and metadata. Implement the same small scorer in Swift and Kotlin. This is a genuine trained on-device model and avoids separate Core ML/TFLite conversions for the initial experiment. If a more complex model later proves necessary, export compatible Core ML and LiteRT/TFLite artifacts from the same training source and test both against shared golden inputs.

## Shared behavior and data contract

Both apps must implement the same contract:

- Same labels: Door Crossing and Normal Movement.
- Same marker meaning: MARK DOOR is pressed at one consistently defined physical event, preferably as the phone/body crosses the threshold.
- Same sample units, feature names, feature order, resampling policy, missing-value rules, window duration, hop, and probability threshold.
- Same three-gate rule: known/recent home Wi-Fi context AND trained motion pattern AND fresh proximity to the saved main-door coordinate, plus quality/permission/cooldown checks. Wi-Fi is a coarse home-context gate, not a doorway geofence; neither Wi-Fi identity nor coordinates are model features or included in recording exports by default.
- Same JSON recording schema and version. Include `recordingId`, `sessionId` (collection day/session), label, start/end wall-clock timestamps, marker timestamp, app/platform/device/OS metadata, sensor availability, and samples. Use ISO-8601 UTC wall time for human-readable events and monotonic sensor timestamps for ordering and windowing.
- Keep datasets user/device specific until cross-device transfer is demonstrated with held-out tests. An iPhone-trained or one-Android-phone-trained model is not automatically valid for another phone; sensor placement, ranges, rates, and vendor calibration differ.

The current iOS recorder uses callback receipt dates for sample times and does not yet store a session identifier. Before mixing platform recordings, update it to preserve monotonic source timestamps and session/day IDs as described in [LOCATION_AND_ML_PLAN.md](LOCATION_AND_ML_PLAN.md).

## Android application architecture

Suggested package responsibilities:

- Compose UI: expose **Training** and **Test** modes. Training includes label selection, start/stop, MARK DOOR, elapsed time, recordings, sensor readiness, and export. Test shows home Wi-Fi match, door-location accuracy/distance, sensor-window quality, model availability/score, and the reason an alert would or would not fire.
- `MotionSensorRepository`: `SensorManager` registration, sensor discovery, event timestamp handling, optional sensor streams, and clean start/stop lifecycle.
- `LocationRepository`: permission state, one saved door anchor, one-shot/current location quality, and optional coarse geofence integration.
- `HomeNetworkRepository`: detect the selected Wi-Fi network when Android exposes its identity; keep a minimal local network fingerprint, never credentials, and report unsupported/permission-denied states explicitly.
- `RecordingRepository`: local persistence, schema versioning, delete/export, and no network synchronization. Room is suitable for structured recording metadata; JSON files can remain the interchange/export format.
- `FeatureWindowBuilder`: the same versioned resampling and feature contract as Python and iOS.
- `DoorwayModelRunner`: parse the versioned linear model JSON and calculate the standardized score locally. Reject unsupported feature/model versions.
- `DetectionCoordinator`: combine fresh location, model score, data quality, notification permission, hysteresis, and cooldown; the only component permitted to post alerts.
- `LocalNotificationPublisher`: create a notification channel and post generic local notifications with a stable event ID for deduplication.

Keep model scoring and recording useful without location or notification permission. No backend, analytics, accounts, advertisement SDK, or app-owned network calls are needed. Avoid declaring `INTERNET` unless a separately approved feature needs it; inspect the merged manifest for permissions added by dependencies. If strict offline location is required, use and test an appropriate platform location provider; network-assisted system location may use connectivity even though the app uploads no dataset.

## Sensor/API mapping

Discover sensors at runtime and display availability. Do not assume every Android phone has a barometer, magnetometer, or step sensor.

| Data | Android API | Notes |
| --- | --- | --- |
| Accelerometer | `Sensor.TYPE_ACCELEROMETER` | Values are in m/s^2; convert or explicitly normalize consistently with the shared feature spec. |
| User acceleration | `Sensor.TYPE_LINEAR_ACCELERATION` | Optional hardware/fusion sensor; otherwise derive only if a tested, versioned method is used. |
| Rotation rate | `Sensor.TYPE_GYROSCOPE` | Optional; preserve radians/second and timestamp. |
| Attitude/orientation | `Sensor.TYPE_ROTATION_VECTOR` | Convert to a documented quaternion/Euler representation; beware conventions differ from Core Motion. |
| Magnetic field | `Sensor.TYPE_MAGNETIC_FIELD` | Optional and environment-sensitive; do not make it mandatory for v1. |
| Barometer | `Sensor.TYPE_PRESSURE` | Optional; derive relative altitude only with documented pressure conversion and baseline. |
| Steps | `Sensor.TYPE_STEP_COUNTER` or `TYPE_STEP_DETECTOR` | Optional; runtime `ACTIVITY_RECOGNITION` permission is required on modern Android versions. Counter is cumulative since reboot, so store recording-relative deltas. |
| Activity state | Activity Recognition Transition API, where justified | Requires `ACTIVITY_RECOGNITION`; Play Services may be used. Treat as optional, and do not make the first model depend on it. |
| Sensor event time | `SensorEvent.timestamp` | Monotonic nanoseconds from boot; use this for sample alignment. Store wall-clock mapping separately for export and marker comparison. |

Android has no direct, guaranteed equivalent to Core Motion's complete device-motion stream. Feature engineering must use streams common to both platforms or explicitly support optional/missing sensors. Do not assume Android heading has the same semantics as iOS true heading.

## Location, permissions, and notifications

For foreground-only setup and detection:

1. In first-run setup, offer “Set Home Wi-Fi” while connected to the desired network and “Set Main Door Location” while standing at the door. Do not force either permission at install; let the user set these up when ready. For the current SSID, use `ConnectivityManager` and `NetworkCapabilities.getTransportInfo()` to obtain `WifiInfo`; declare `ACCESS_WIFI_STATE` and request precise `ACCESS_FINE_LOCATION` in context because SSID is location-sensitive. Android can return `WifiManager.UNKNOWN_SSID` if access is redacted or system Location is off. `NEARBY_WIFI_DEVICES` is for specific Wi-Fi management/discovery APIs and is not a substitute for location permission when the app derives home presence from SSID. Store only a local SSID, never the Wi-Fi password.
2. Treat connected-to-home-Wi-Fi as the coarse app-level home/armed gate, not as a door location or guaranteed OS wake event. Network callbacks/background delivery can be restricted. If the connection drops as the user exits, allow only a short tested “recently observed home Wi-Fi” latch for the crossing window.
3. Request `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` in context when the user chooses “Set Main Door Location.” Android users can grant approximate location only; if the fix cannot satisfy the configured accuracy gate, explain that and do not alert.
4. Use Android `LocationManager` for location fixes if avoiding a Google Play Services dependency is preferred. If using a Play Services fused provider or `GeofencingClient`, document that dependency; it is still an on-device app flow, not an app backend.
5. Display fix time and `accuracy` before saving the door anchor. Persist only the one anchor and its setup metadata locally. Offer change/delete controls. Do not collect a continuous route or put coordinates in recording exports by default. Decide explicitly whether recordings, home Wi-Fi identity, and door coordinate are included in Android Auto Backup/device transfer; exclude sensitive data by default if the requirement is that it remain only on this device.
6. On Android 13 and later, request `POST_NOTIFICATIONS` only when the user enables audible alerts. Create a notification channel on Android 8 and later, with sound subject to user/system settings. Denial must not break recording or detection diagnostics.
7. Request `ACTIVITY_RECOGNITION` only if using the step counter/detector or Activity Recognition API; handle denial by marking those channels unavailable.

Background location is a separate, later opt-in:

- Android 10 and later require `ACCESS_BACKGROUND_LOCATION` for background location access, with a separate user-facing permission flow; behavior and Play policy depend on the target SDK and distribution channel. Ask only if a tested background feature actually needs it.
- A geofence is an optional coarse OS wake-up, not the home Wi-Fi gate or doorway positioning. A radius around 100 m or more is typically more realistic for dependable geofence behavior; validate on target devices and do not treat a boundary event as a doorway crossing. Wi-Fi state by itself must not be assumed to wake the app.
- Continuous sensor inference while the app is backgrounded generally requires an ongoing, user-visible foreground service with the correct foreground-service type and permissions for the chosen Android/target SDK. Android 9+ also restricts delivery of many continuous sensor events to background apps. This affects battery, notification UX, and Play review; do not add an always-on service before foreground detection is evaluated.
- Android vendors may apply additional battery restrictions. Test screen-off, Doze, battery saver, app standby, reboot, permission revocation, and force-stop. Force-stop and OEM power management can prevent timely processing.
- If location or processing is stale/unavailable, fail closed. Do not send a delayed alert that no longer corresponds to a current door-area motion event.

For v1, **10 m is an experimental foreground proximity threshold**, not the registered Android OS geofence. Show fresh-fix age, distance, and reported horizontal accuracy; the implemented test requires `distance + accuracy <= radius`. The predicate can often fail indoors, which is a valid result rather than a reason to ignore uncertainty. Google recommends a 100–150 m minimum radius for best background geofence results, and transition delivery may be delayed by minutes. If background wake-up is later needed, consider a separate coarse 100–150 m region only as an opportunity to check again; still require the fresh 10 m door test, matching home Wi-Fi, and trained motion in the foreground/eligible service. A BLE beacon is a possible later doorway-level signal if GPS cannot support the experiment, but it changes hardware, setup, and data collection requirements.

## Training and model interoperability

1. Update both recorders to emit the same versioned JSON schema and monotonic timing fields. Add `sessionId` to both before collecting the cross-platform dataset. Training Mode collects labeled examples; Test Mode first validates Wi-Fi and door-location gates, then tests an imported model.
2. Implement dataset parsing, timestamp validation, window creation, grouped splits, baseline training, metrics, and model export once in the shared `ml/` Python project.
3. Record platform, device model, OS version, sensor availability, sample rates, units, and placement notes in metadata. Use them for auditing and subgroup reports, not as shortcuts that leak the label.
4. Train/evaluate per user and preferably per target phone first. If the user switches phones, collect calibration data and evaluate that phone separately before enabling alerts.
5. Export a versioned portable artifact. For logistic regression, JSON fields should include schema/model version, ordered feature names, means/scales, coefficients, intercept, selected threshold, and training report reference. The app validates every required field and rejects unknown versions.
6. Create golden feature vectors from exported recordings. Assert that Python, Swift, and Kotlin output matching feature arrays and scores within a documented tolerance. Test missing sensors, stale carried-forward values, NaN/Infinity, and model-version mismatch.
7. If the best evaluated model cannot be implemented as the shared linear scorer, compare Core ML and LiteRT/TFLite conversion from one source model. Keep the same preprocessing outside the model or package it consistently. Add numerical parity tests before rollout; never train separate iOS and Android models without documenting that they are different models.

## Android delivery phases

1. **Build and install:** open `android/` in Android Studio with JDK 17 and Android SDK 35, sync Gradle, and run on a physical Android phone. Verify that `WifiInfo.getSSID()` returns the connected SSID after permission and location settings are granted.
2. **Sensor validation:** check rates, units, monotonic timestamps, missing sensors, screen-off behavior, and pocket orientation. The emulator is not adequate for sensor experiments.
3. **Location and home-network validation:** save a local home SSID and one door anchor. Measure indoor GPS age/accuracy and repeat the configurable 10 m accuracy-aware check at the door; keep it foreground-only. No coordinates or SSID enter exported recordings.
4. **Shared offline model:** use the common Python pipeline and grouped evaluation. Generate portable model parameters only after held-out results meet a predeclared false-alarm tolerance.
5. **On-device shadow mode:** implement Kotlin feature generation and model scoring. Display “would detect” status while logging minimal local decisions; send no notifications.
6. **Notification gate:** after physical-phone shadow testing, add opt-in notification permission/channel and require home Wi-Fi context, current door-location quality, validated pattern score, and cooldown. Play a local channel sound only when the user enables it; OS settings can silence it. Test each failed gate independently.
7. **Background experiment:** separately prototype geofence wake-up and a user-visible foreground service if needed. Measure reliability and battery on the actual device. Keep background monitoring disabled by default until it proves useful and is appropriately disclosed.
8. **Release:** document supported Android versions/devices, permissions, data deletion/export, model provenance, tested background states, battery impact, and limitations. Keep iOS and Android feature claims aligned.

## Android acceptance checks

- Android records, deletes, and exports without location, notification, or activity-recognition permission.
- Unsupported sensors are reported and do not crash recording. Model features remain identical for shared required channels.
- The marker's wall time maps accurately to monotonic sensor time; sample order does not depend on callback receipt timing.
- Approximate/revoked/stale location, outside-area location, low model score, bad input window, missing model, or denied notification permission never sends an alert.
- A high model score outside the saved area never notifies; a good location with a low score never notifies.
- A nonmatching home network independently prevents alerts. Wi-Fi loss during a crossing follows only the short, explicitly tested grace rule; stale home-network observations never arm detection.
- Notification events are deduplicated and respect cooldown; no location trail is stored.
- Android foreground and screen-off behavior is physically tested. Background behavior is claimed only for tested device/OS/permission combinations.
- Android/iOS/Python golden-vector tests agree, and held-out evaluation is grouped by recording/session rather than by overlapping windows.