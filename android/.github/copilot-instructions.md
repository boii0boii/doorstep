# Android Project Instructions

## Project

- Native Android app in Kotlin and Jetpack Compose. Gradle root is this directory.
- Keep the iOS/Xcode project separate; Android code belongs under `android/`.
- App scope: Reminders (background departure monitor + keys notification, requested by the user), Training, and Test. Do not add cloud services, accounts, analytics, advertisements, or `INTERNET` permission without an explicit request.
- Reminders must work with the phone locked in a pocket: sensing lives in foreground services, never in the Activity. GPS is not a reminder gate.
- Keep recordings, saved home SSID, and door coordinates in app-private storage. Never add SSID or coordinates to recording JSON/ZIP exports.
- Preserve `SensorEvent.timestamp` monotonic timing and UTC mapping in recording changes.
- The 10 m door radius is a foreground accuracy-aware experimental check, not an OS background geofence guarantee.

## Setup Checklist

- [x] Confirm setup file exists and is project-specific.
- [x] Clarify requirements: Kotlin, Compose, Android v1, Training/Test, local data, Wi-Fi/GPS checks, 10 m experimental threshold.
- [x] Scaffold standalone Android Gradle project in `android/`.
- [x] Implement Training/Test screens, motion capture, local JSON/ZIP export, SSID diagnostics, and GPS door test.
- [x] Add focused 10 m proximity unit tests and README instructions.
- [x] Extension installation: no extension was required or specified.
- [x] Task setup: no separate VS Code task is needed; use Gradle wrapper tasks.
- [x] Compile: `./gradlew testDebugUnitTest assembleDebug` passes with Android Studio's bundled JDK and Android SDK 35.
- [ ] Launch: not launched; requires Android Studio and a user-authorized physical Android device.
- [x] Documentation: `README.md` and this instruction file describe current project status and limitations.
