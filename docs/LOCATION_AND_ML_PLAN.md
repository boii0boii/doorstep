# Location-Gated Doorway Recognition Plan

## Purpose and current status

The current Xcode target records labeled Core Motion sessions and exports local JSON/ZIP data. It now also has a **Test** mode that probes current Wi-Fi SSID access and captures/checks a main-door coordinate and location accuracy. It does not yet train or run a model, monitor gates in the background, or send alerts. Treat Test mode as a physical-device feasibility probe, not detection.

This document is the implementation and research plan for adding those capabilities. The eventual decision rule is:

```text
play an opt-in local alert only when
  the phone is connected to (or was very recently observed on) the saved home Wi-Fi
  AND a valid recent location fix is within the saved main-door area
  AND the on-device model detects the learned doorway-motion pattern
  AND the event passes confidence, data-quality, and cooldown checks
```

These are three independent gates: home Wi-Fi is a coarse home-context/arming signal, the saved coordinate is the main-door proximity gate, and the model is the motion-pattern gate. Wi-Fi is not a geographic fence and neither Wi-Fi nor GPS is a model feature. The app must not conclude that the user forgot keys or make claims beyond “doorway-like motion detected near the saved door.” If any gate, location accuracy, or sensor quality is unavailable, suppress the alert rather than guessing.

## Important iPhone location limits

- A saved latitude/longitude is a geographic anchor, not a precise indoor doorway beacon. GPS can be unavailable or drift by tens of metres indoors. If the iPhone cannot produce a sufficiently fresh and accurate fix at the door, location-gated detection may often be unavailable.
- Core Location circular-region monitoring is a coarse wake-up mechanism. It is not a reliable way to tell which doorway in a home the user crossed; effective radii are generally on the scale of 100 metres, subject to system/device conditions. Keep the coarse wake radius separate from the tighter location-confidence rule.
- iOS does not promise uninterrupted Core Motion processing while an app is suspended. Location permissions do not grant unlimited background CPU time. Foreground detection is the first supported milestone; background operation must be measured on the actual iPhone and iOS version.
- A force-quit app, denied/reduced location authorization, disabled Precise Location, stale fixes, Low Power Mode, or unavailable sensor streams can prevent timely detection. Show the state and fail closed.
- iOS does not provide a general-purpose background wake trigger for joining an arbitrary Wi-Fi network. `NWPathMonitor` can report network-path changes but does not identify the SSID. The supported prototype path is `NEHotspotNetwork.fetchCurrent()` (iOS 14+) with the **Access Wi-Fi Information** capability, which adds `com.apple.developer.networking.wifi-info`. Apple's current API documentation says the call returns `nil` unless the entitlement is present and at least one qualifying condition holds, including Core Location authorization for precise location. The app already needs location for the door gate, so test this combination on a signed physical-device build; do not assume the Simulator or an unsigned build proves it works. See Apple's [fetchCurrent documentation](https://developer.apple.com/documentation/networkextension/nehotspotnetwork/fetchcurrent(completionhandler:)) and [Access Wi-Fi Information entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com_apple_developer_networking_wifi-info).
- Wi-Fi can disconnect at the edge of the home network as the user exits. If testing supports it, allow a short local “recently on home Wi-Fi” latch for the candidate crossing window; expire it quickly, never treat an old connection as current, and document the chosen duration.
- If reliable doorway-level location or unattended background detection is a hard requirement, evaluate a local BLE beacon at the doorway or another explicit user-presence signal. Do not represent GPS geofencing as doorway-accurate.

## Target architecture

### iOS app

Keep the existing SwiftUI app and separate these responsibilities:

- `MotionRecorder`: retain collection behavior; use sensor timestamps and a shared monotonic time basis for alignment. Record callback receipt time separately if useful. Avoid treating callback scheduling time as the physical sensor measurement time.
- Recording metadata: add an explicit session/day identifier so train/validation/test splits can keep all recordings from the same collection session together. Do not infer sessions only from arbitrary filename ordering.
- `DoorLocationService`: own `CLLocationManager`, permission state, one saved door anchor, current location quality, and optional coarse region monitoring. Request location only after the user explicitly starts location setup.
- `DoorLocationStore`: persist the anchor locally in Application Support or Keychain-backed app storage as appropriate. Keep coordinates out of ordinary motion samples and training exports by default.
- `HomeWiFiContextService`: identify whether the current/recent network matches the user-selected home network when iOS permits it. Store only the minimum local network identity needed; never store a Wi-Fi password. Report “unavailable” when SSID access is denied or unsupported rather than silently bypassing the gate.
- `FeatureWindowBuilder`: resample/align the current motion streams and produce exactly the feature vector used by Python training.
- `DoorwayClassifier`: load a versioned model artifact and feature specification; report unavailable or incompatible artifacts without crashing. For the first regularized linear baseline, prefer shared scaler/weight/bias JSON and a small deterministic native scorer. This lets iOS and Android use identical learned parameters without maintaining two converters.
- `DetectionCoordinator`: combine model score, fresh location confidence, availability, hysteresis, duplicate suppression, and app state. It is the only component allowed to request a notification.
- `LocalNotificationService`: request notification authorization, create a generic local notification, and attach a stable event identifier for deduplication.
- SwiftUI screens: retain recorder/export screens and expose two top-level modes, **Training** and **Test**. Setup stores the selected home SSID and main-door coordinate locally. Training records labeled positives and negatives. Test displays each independent gate (Wi-Fi identity available/matched, door fix accuracy/distance, sensor window, and model readiness/score); do not enable alerts just because the app is in Test mode.

Suggested data flow:

```text
Core Motion -> aligned rolling window -> shared feature builder -> pattern score --+
Core Location -> fresh fix near saved door ---------------------------------------+-> DetectionCoordinator -> audible local alert
Home Wi-Fi -> known/recent home-network check -------------------------------------+
```

Neither coordinates nor sensor samples leave the device. Do not upload, sync, or add analytics.

### Training tools on the computer

Add a small, reproducible Python project, for example:

```text
ml/
  README.md
  requirements.txt
  feature_spec.json
  prepare_dataset.py
  train_model.py
  evaluate_model.py
  export_model.py
  export_coreml.py (optional if a later model uses Core ML)
  tests/
```

For v1, “backend” means your Mac running local scripts, not a hosted server. Keep raw exported recordings private and out of source control. The workflow is: iPhone Training Mode records data locally -> user exports a ZIP to the Mac -> Python validates/prepares it, trains and evaluates locally -> Mac produces a report plus versioned model JSON -> developer copies the accepted model artifact into the iOS app bundle in Xcode and rebuilds/reinstalls on the phone -> Test Mode runs the model locally. No data or model needs to pass through a cloud service.

The training pipeline reads the app's ZIP/JSON format, validates schema and timestamps, creates fixed windows, creates splits grouped by recording/session, trains a baseline, evaluates it, and exports a versioned model artifact only after evaluation. For the first linear model, export the scaler values, coefficients, intercept, threshold, and feature-spec version as JSON. If a later model needs a runtime, export equivalent Core ML and Android LiteRT/TFLite models from the same training source and verify parity. Save the model card/report beside the artifact with dataset IDs/counts, feature-spec version, split definition, metrics, threshold, and known limitations.

## Step-by-step implementation and use

### Phase 0: Establish a baseline

1. Build and run the existing app on the physical iPhone; simulator motion is not representative.
2. Check that each recording is saved and exported, sensor availability is accurate, and the device's timestamp order is sensible.
3. Define the event consistently. For future training, press MARK DOOR at the same physical point on every positive trial, preferably as the body/phone crosses the doorway threshold. Do not alternate between marking “arrived at the door” and “crossed the threshold.”
4. Record interruptions or unusual carrying positions in notes. Keep the phone in the same pocket and orientation where practical.

### Phase 1: Collect a useful personal dataset

The initial target of 20 positive and 30 negative recordings is a pilot, not enough to establish reliable real-world performance. Prefer collecting across multiple days and conditions; a stronger initial target is 50–100 positive crossings and at least 100–200 negatives, if practical. More examples should represent normal variability, not just repetitions of one identical walk.

Positive examples:

1. Choose Door Crossing and start before the approach begins.
2. Walk normally toward the door, pause/open it as usual, press MARK DOOR at the defined threshold event, cross, and continue a few steps outside.
3. Stop and save. Repeat with natural variations in pace, pauses, pocket placement, and door operation; document major variations.

Negative examples should include hard negatives, not only unrelated indoor walking: approach the door but turn away; pause near it; open/close it without crossing; walk through another interior doorway; walk around the home; stand, sit, turn, climb stairs, and carry the phone in ordinary ways. Label every session accurately.

Use recording ID and session/day ID for splitting. Never put windows from one recording on both sides of a train/test split. Keep a final set of whole sessions/days untouched until model and threshold choices are complete.

### Phase 2: Set the home network and main-door location

1. Add an explicit “Set Home Wi-Fi” action while connected to the desired network. Explain that this identifies the home context, not the doorway. Enable the Xcode **Access Wi-Fi Information** capability and test `NEHotspotNetwork.fetchCurrent()` with precise location authorized on the physical phone. `NWPathMonitor` alone cannot confirm the network name. If `fetchCurrent()` returns `nil`, show the gate as unavailable and do not claim automatic Wi-Fi arming; debug signing, entitlement, authorization, and device conditions before considering Android fallback.
2. Store only a local network identifier/fingerprint needed to compare future connections, such as the selected SSID and optionally BSSID where available. Do not store credentials or export the identifier with motion recordings. Add change/forget controls. Establish with testing how long a recent-home-network observation can remain valid after Wi-Fi disconnects; begin with a short window (for example, 2 minutes), then shorten or remove it if it creates false alerts.
3. Add an explicit “Set Main Door Location” action and explain that the coordinate stays on this iPhone and is a separate gate from Wi-Fi. Save one fresh coordinate while at the main door and show its horizontal accuracy; do not imply a 10 m fix if the reported uncertainty is larger.
4. Ask for location authorization only when the user starts location setup. Start with When In Use for a foreground prototype. Explain that background region monitoring may require Always authorization and additional testing; do not request it without a clear opt-in and feature explanation.
  - Add `NSLocationWhenInUseUsageDescription` to `Info.plist` for foreground location setup and checks.
  - Only if background monitoring is deliberately implemented, add `NSLocationAlwaysAndWhenInUseUsageDescription`, configure the required location background capability, and request the higher authorization after a separate user opt-in. Do not enable background location merely for the foreground prototype.
5. Check authorization and accuracy authorization. If Precise Location is off, explain how to enable it; do not silently proceed as if a coarse fix were precise.
6. At the doorway, wait for a fresh `CLLocation` fix. Show horizontal accuracy and timestamp. Save the coordinate, accuracy, creation time, and a user-configurable proximity radius locally. Reject or warn on poor fixes (for example, worse than 25 m); treat this as a starting quality limit to validate, not a guarantee of indoor GPS precision.
7. Do not store a continuous location trail. Do not append coordinates to motion samples or include the home coordinate or Wi-Fi identity in ZIP exports by default. Provide “Change Door Location,” “Delete Door Location,” and “Forget Home Wi-Fi.”
8. Optionally register a coarse `CLCircularRegion` as a separate OS wake-up aid, within the system's supported limits. It is not the home Wi-Fi gate and its enter/exit event alone does not prove the phone is at the doorway.
9. Request `UNUserNotificationCenter` authorization only when audible alerts are enabled. It does not require a location-purpose string in `Info.plist`.

For a candidate current fix, define location confidence explicitly. For example, require a recent fix (initially no older than 15 seconds), acceptable `horizontalAccuracy` (initially 25 m or better), and that the full uncertainty radius fits inside the configured door area: `distance(current, anchor) + horizontalAccuracy <= allowedRadius`. Tune these values only from observed iPhone behavior. If no reliable fix is available indoors, location condition is false and no notification is sent.

### Phase 3: Train and evaluate offline

1. In Training Mode, collect Door Crossing and Normal Movement recordings on the iPhone. Export the recorder ZIP to the user's Mac and unpack it locally. Keep the data private and out of git; do not upload it to a hosted backend.
2. Validate every JSON document, required metadata, sample order, sensor availability, and marker placement. Produce a data-quality report before training.
3. Use a versioned preprocessing specification shared with the iOS implementation. A practical first experiment is fixed 5-second windows with 0.5-second hop on a 50 Hz time grid. Resample based on timestamps, not array index. Track missingness/source age; do not fill stale values as if they were current.
4. Start with robust engineered features from accelerometer, user acceleration, and rotation rate: per-axis mean, standard deviation, RMS, range, energy, jerk, and step-count delta where supported. Avoid absolute magnetic field, heading, altitude, and raw attitude as required features until tests show they add stable value; these can vary by phone, building, orientation, or availability. Include missing-value indicators and sensor-availability metadata where appropriate.
5. Label positive windows relative to the consistently placed door marker, including approach/pause/crossing context. Sample negative windows across each Normal Movement recording. Limit or weight windows per recording so a long recording cannot dominate the dataset.
6. Train a simple personalized baseline first, such as regularized logistic regression, then compare one small tree-based model if it can be exported and reproduced. Do not begin with a neural sequence model for a tiny personal dataset.
7. Select the detection threshold on validation sessions to meet a predeclared false-alarm tolerance. Report precision, recall, false-positive rate, missed crossings, confusion matrix, per-session outcomes, and uncertainty. Compare against simple rules/baselines.
8. Evaluate only once on untouched held-out sessions/days. Report results honestly. If there are too few independent sessions, call the result preliminary and collect more data; do not tune on the test set.
9. Export the selected model and exact feature ordering in versioned artifacts. For the initial linear baseline, serialize the scaler, coefficients, intercept, and threshold; confirm Python, Swift, and Kotlin produce matching features and scores for the same saved windows within numeric tolerances. If a later model requires Core ML or LiteRT/TFLite, export from the same training source and add cross-runtime conformance tests before shipping.
10. Copy the evaluated model JSON into the iOS app's bundled resources, rebuild/reinstall, and use Test Mode to confirm the app reports the expected model version and matching scores for golden windows before live shadow testing.

The model is user-specific. A model trained on one phone/user/home must not be described as validated for other people or homes.

### Phase 4: Add foreground detection in shadow mode

1. Bundle the real evaluated model artifact and feature spec. If either is absent or versions mismatch, show “Model unavailable” and keep recording/export operational. Test Mode must still let the user test Wi-Fi and door-location gates while the model is absent.
2. Build rolling fixed-duration windows from live supported motion streams using the same timestamp, alignment, and missing-data rules as training.
3. Run the versioned model scorer locally. Start by showing scores and “would have alerted” events without notifications. Log only minimal local diagnostic decisions, with no location history.
4. Require a current/recent match to the selected home Wi-Fi, valid sensor quality, a model score over the validated threshold for a documented number of consecutive windows, a fresh location fix within the configured main-door area, alert permission, and no active cooldown. All three gates are conjunctive. A low-quality or missing input must suppress the event. Do not let the Wi-Fi latch outlive the tested short crossing window.
5. Add hysteresis and one event ID/cooldown so one crossing cannot produce a stream of alerts. A reasonable initial cooldown to test is 10 minutes; make it configurable during research.
6. Review shadow-mode false positives and missed crossings over real days. Enable notifications only after the user opts in and the observed false-alert rate is acceptable.

### Phase 5: Notifications and background feasibility

Use `UNUserNotificationCenter` for a generic local notification with the system default sound when the user enables audible alerts. Request notification permission at that moment. This is a local notification sound, not a phone call or guaranteed ring: Silent mode, Focus, notification settings, and system policy can suppress it. Do not use speech, server push, or key-reminder wording. Provide a visible “Disable alerts” control.

Start with the app open and screen active. Then separately test location authorization and background behavior on the target physical iPhone. If adding Always authorization and background location mode, explain the extra battery/privacy cost and use only for the explicitly enabled region-monitoring feature. Treat region callbacks as opportunities to refresh state, not proof that a crossing occurred. Never promise continuous sensor inference while suspended.

Test each state independently: foreground, screen locked, background but not force-quit, force-quit, reboot, low power, location unavailable, reduced accuracy, motion permission denied, notifications denied, and sensor/model unavailable. If the app cannot evaluate a timely motion window under a state, document that limitation and send no notification. Do not add background modes merely to silence lifecycle problems.

## Detection contract

The notification decision should be auditable and equivalent to:

```text
homeWiFiOK = selected home network is observed now
             OR was observed within the short, tested crossing grace window

doorOK = fix exists
             AND fix is fresh
             AND horizontal accuracy is acceptable
             AND distance + horizontal accuracy <= configured radius

motionOK = model exists and matches feature-spec version
           AND required sensors/window quality are acceptable
           AND smoothed score >= validated threshold

alert = alerts enabled AND notification permission granted
  AND homeWiFiOK AND doorOK AND motionOK AND not in cooldown
```

If any term is false or unknown, do not alert. Make the UI show the failed gate, such as “Not on home Wi-Fi,” “Home network unavailable,” “Outside door area,” “Location too imprecise,” “Collecting motion window,” “Model unavailable,” or “Pattern score below threshold.” Do not claim that no alert means the user stayed home; the app may have lacked permission, a reliable fix, Wi-Fi identity, or a processing opportunity.

## Acceptance checks

- Existing recorder, local persistence, deletion, and ZIP export continue to work without location/model setup.
- Location is not requested until the user opts into location setup; denied permission leaves collection usable.
- The signed physical-device app can read the current SSID with the Access Wi-Fi Information entitlement and qualifying precise-location authorization; when it cannot, Test Mode visibly reports unavailable rather than treating any Wi-Fi as home.
- Door anchor is local, editable, deletable, and excluded from ordinary recording exports unless the user explicitly chooses otherwise.
- A nonmatching home network, door location outside the configured radius, or low model score independently prevents an alert, even when the other two gates match.
- Stale/inaccurate location, missing sensors, invalid model, denied notification permission, and duplicate windows fail closed with an understandable status.
- Python-to-Swift feature parity is tested on stored examples; time/session grouped evaluation prevents leakage.
- Foreground behavior is tested on a physical iPhone. Background claims are limited to states directly verified on that device and iOS release.
- The Mac training report and transferred model artifact identify the same feature-spec/model version; model import/replacement does not alter recordings or include Wi-Fi/location data.
- Evaluation reports show false positives on hard negatives and missed doorway crossings; no success claim is based only on training accuracy.

## Privacy and battery checklist

- Keep recordings, model, anchor, and diagnostics on-device unless the user explicitly exports recordings.
- Keep location separate from the motion dataset and do not collect continuous routes.
- Keep the home Wi-Fi identity local and separate from recordings; never store its password.
- Decide whether app data is included in operating-system device backups; exclude the saved home coordinate by default if “local only” means it must remain only on this physical device.
- Offer delete controls for recordings and the saved anchor; document where local data is stored.
- Explain location, motion, and notification permissions in context and allow each feature to be disabled.
- Start sensors only when recording or when the user enables a bounded detection session. Measure battery use before proposing always-on monitoring.