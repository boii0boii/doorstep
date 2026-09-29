# Doorstep Project Instructions

## Project

- Native Android app in Kotlin and Jetpack Compose. Gradle root is `android/`; package `com.boii0boii.doorstep`.
- The parked iOS/Xcode prototype lives in `ios/`; keep it separate from Android code.
- App scope: Reminders (background departure monitor + keys notification, requested by the user), Training, and Test. Do not add cloud services, accounts, analytics, advertisements, or `INTERNET` permission without an explicit request.
- Reminders must work with the phone locked in a pocket: sensing lives in foreground services, never in the Activity. GPS is not a reminder gate.
- Keep recordings, saved home SSID, and door coordinates in app-private storage. Never add SSID or coordinates to recording JSON/ZIP exports.
- Preserve `SensorEvent.timestamp` monotonic timing and UTC mapping in recording changes.
- The 10 m door radius is a foreground accuracy-aware experimental check, not an OS background geofence guarantee.
