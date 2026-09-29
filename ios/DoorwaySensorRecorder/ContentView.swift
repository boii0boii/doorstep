import SwiftUI
@preconcurrency import CoreLocation
import NetworkExtension

struct ContentView: View {
    @EnvironmentObject private var recorder: MotionRecorder
    @StateObject private var feasibility = DeviceFeasibility()
    @State private var shareItem: ShareItem?
    @State private var selectedMode = "Training"

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Doorway Sensor Recorder")
                        .font(.title2.bold())
                    Text("Training collects labeled motion examples. Test checks Wi-Fi and door-location access on this iPhone.")
                        .foregroundStyle(.secondary)
                }

                Section {
                    Picker("Mode", selection: $selectedMode) {
                        Text("Training").tag("Training")
                        Text("Test").tag("Test")
                    }
                    .pickerStyle(.segmented)
                }

                if selectedMode == "Training" {
                    Section("Recording") {
                        Picker("Label", selection: $recorder.selectedLabel) {
                            ForEach(RecordingLabel.allCases) { label in Text(label.rawValue).tag(label) }
                        }
                        if recorder.isRecording {
                            Text("Walk normally through your front door.")
                                .font(.headline)
                            LabeledContent("Status", value: recorder.status)
                            LabeledContent("Elapsed", value: elapsedText)
                            if recorder.selectedLabel == .doorCrossing {
                                Button("MARK DOOR") { recorder.markDoor() }
                                    .disabled(!recorder.canMarkDoor)
                                if recorder.doorMarkerAt != nil { Text("Door timestamp recorded").foregroundStyle(.green) }
                            }
                            Button("STOP RECORDING", role: .destructive) { recorder.stopRecording() }
                        } else {
                            LabeledContent("Status", value: recorder.status)
                            Button("Start Recording") { recorder.startRecording() }
                                .buttonStyle(.borderedProminent)
                        }
                    }

                    Section("Sensors on this iPhone") {
                        sensorRow("Accelerometer", recorder.availability.accelerometer)
                        sensorRow("Device motion", recorder.availability.deviceMotion)
                        sensorRow("Magnetometer", recorder.availability.magnetometer)
                        sensorRow("Relative altitude", recorder.availability.altimeter)
                        sensorRow("Pedometer", recorder.availability.pedometer)
                        sensorRow("Motion activity", recorder.availability.motionActivity)
                        Label("Heading is intentionally not requested. Magnetometer data is recorded without GPS.", systemImage: "location.slash")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }

                    Section("Previous recordings (\(recorder.recordings.count))") {
                        if recorder.recordings.isEmpty {
                            Text("No recordings yet.").foregroundStyle(.secondary)
                        } else {
                            ForEach(recorder.recordings) { recording in
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(recording.label.rawValue).font(.headline)
                                    Text("\(recording.startedAt.formatted(date: .abbreviated, time: .standard)) · \(recording.samples.count) samples")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        Button("Export Data") {
                            if let url = recorder.exportArchive() { shareItem = ShareItem(url: url) }
                        }
                            .disabled(recorder.recordings.isEmpty || recorder.isRecording)
                    }

                } else {
                    Section("Home Wi-Fi") {
                        TextField("Home network name (SSID)", text: $feasibility.homeSSID)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Button("Save Home Wi-Fi") { feasibility.saveHomeWiFi() }
                            .disabled(feasibility.homeSSID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Button("Check Current Wi-Fi") { feasibility.checkCurrentWiFi() }
                        if let observedSSID = feasibility.observedSSID {
                            LabeledContent("Current network", value: observedSSID)
                            Button("Use Current Network as Home") { feasibility.useObservedWiFiAsHome() }
                                .disabled(observedSSID.isEmpty)
                        }
                        Text(feasibility.wifiStatus)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if !feasibility.homeSSID.isEmpty {
                            LabeledContent("Saved home network", value: feasibility.homeSSID)
                            Button("Forget Home Wi-Fi", role: .destructive) { feasibility.forgetHomeWiFi() }
                        }
                    }

                    Section("Main door location") {
                        LabeledContent("Permission", value: feasibility.locationAuthorization)
                        Button("Capture Current Location as Door") { feasibility.captureDoorLocation() }
                        Button("Check Current Location") { feasibility.checkDoorProximity() }
                        Text(feasibility.locationStatus)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(feasibility.savedDoorStatus)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if let distance = feasibility.distanceToDoor {
                            LabeledContent("Distance to saved door", value: String(format: "%.1f m", distance))
                        }
                        if feasibility.hasSavedDoor {
                            Button("Forget Door Location", role: .destructive) { feasibility.forgetDoorLocation() }
                        }
                    }

                    Section("Readiness") {
                        Label("Motion model is not installed yet", systemImage: "xmark.circle")
                            .foregroundStyle(.secondary)
                        Text("This test checks Wi-Fi identity and location only. It does not detect a doorway pattern or send alerts.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Doorway Study")
            .sheet(item: $shareItem) { item in ShareSheet(url: item.url) }
        }
    }

    private var elapsedText: String {
        let seconds = Int(recorder.elapsed)
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    private func sensorRow(_ name: String, _ available: Bool) -> some View {
        Label(name, systemImage: available ? "checkmark.circle.fill" : "xmark.circle")
            .foregroundStyle(available ? .green : .secondary)
    }
}

@MainActor
private final class DeviceFeasibility: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published var homeSSID: String
    @Published private(set) var observedSSID: String?
    @Published private(set) var wifiStatus = "Check whether iOS exposes the connected network name."
    @Published private(set) var locationAuthorization = "Not requested"
    @Published private(set) var locationStatus = "Location has not been checked."
    @Published private(set) var savedDoorStatus: String
    @Published private(set) var distanceToDoor: CLLocationDistance?
    @Published private(set) var hasSavedDoor: Bool

    private let locationManager = CLLocationManager()
    private let defaults = UserDefaults.standard
    private var shouldSaveNextLocationAsDoor = false

    override init() {
        homeSSID = UserDefaults.standard.string(forKey: "homeWiFiSSID") ?? ""
        let latitude = UserDefaults.standard.object(forKey: "doorLatitude") as? Double
        let longitude = UserDefaults.standard.object(forKey: "doorLongitude") as? Double
        hasSavedDoor = latitude != nil && longitude != nil
        if let latitude, let longitude {
            let accuracy = UserDefaults.standard.double(forKey: "doorAccuracy")
            savedDoorStatus = String(format: "Saved door: %.6f, %.6f (setup accuracy %.1f m)", latitude, longitude, accuracy)
        } else {
            savedDoorStatus = "No door location saved."
        }
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        refreshAuthorizationStatus()
    }

    func saveHomeWiFi() {
        homeSSID = homeSSID.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(homeSSID, forKey: "homeWiFiSSID")
        wifiStatus = "Saved on this iPhone. Check Current Wi-Fi to verify SSID access and matching."
    }

    func useObservedWiFiAsHome() {
        guard let observedSSID, !observedSSID.isEmpty else { return }
        homeSSID = observedSSID
        saveHomeWiFi()
        wifiStatus = "Current network saved as home Wi-Fi."
    }

    func forgetHomeWiFi() {
        homeSSID = ""
        observedSSID = nil
        defaults.removeObject(forKey: "homeWiFiSSID")
        wifiStatus = "Home Wi-Fi forgotten."
    }

    func checkCurrentWiFi() {
        wifiStatus = "Checking current Wi-Fi…"
        NEHotspotNetwork.fetchCurrent { [weak self] network in
            Task { @MainActor in
                guard let self else { return }
                guard let network, !network.ssid.isEmpty else {
                    self.observedSSID = nil
                    self.wifiStatus = "Unavailable: iOS returned no SSID. First grant Precise When In Use location by checking the door location, then verify the Wi-Fi entitlement/signing and retry on the connected physical iPhone."
                    return
                }
                self.observedSSID = network.ssid
                if self.homeSSID.isEmpty {
                    self.wifiStatus = "SSID access works. Save this network as home Wi-Fi or enter the home network name."
                } else if network.ssid == self.homeSSID {
                    self.wifiStatus = "Home Wi-Fi matched."
                } else {
                    self.wifiStatus = "SSID access works, but this network does not match the saved home Wi-Fi."
                }
            }
        }
    }

    func captureDoorLocation() {
        shouldSaveNextLocationAsDoor = true
        requestLocation()
    }

    func checkDoorProximity() {
        shouldSaveNextLocationAsDoor = false
        requestLocation()
    }

    func forgetDoorLocation() {
        defaults.removeObject(forKey: "doorLatitude")
        defaults.removeObject(forKey: "doorLongitude")
        defaults.removeObject(forKey: "doorAccuracy")
        hasSavedDoor = false
        distanceToDoor = nil
        savedDoorStatus = "No door location saved."
        locationStatus = "Saved door location forgotten."
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refreshAuthorizationStatus()
        if shouldSaveNextLocationAsDoor, manager.authorizationStatus == .authorizedAlways || manager.authorizationStatus == .authorizedWhenInUse {
            manager.requestLocation()
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last else { return }
        guard location.horizontalAccuracy >= 0 else {
            locationStatus = "Location fix has invalid accuracy. Try again outdoors or near a window."
            shouldSaveNextLocationAsDoor = false
            return
        }

        let age = max(0, Date().timeIntervalSince(location.timestamp))
        locationStatus = String(format: "Fix accuracy: %.1f m · age: %.0f sec · %@", location.horizontalAccuracy, age, location.timestamp.formatted(date: .omitted, time: .standard))
        if shouldSaveNextLocationAsDoor {
            defaults.set(location.coordinate.latitude, forKey: "doorLatitude")
            defaults.set(location.coordinate.longitude, forKey: "doorLongitude")
            defaults.set(location.horizontalAccuracy, forKey: "doorAccuracy")
            hasSavedDoor = true
            savedDoorStatus = String(format: "Saved door: %.6f, %.6f (setup accuracy %.1f m)", location.coordinate.latitude, location.coordinate.longitude, location.horizontalAccuracy)
            shouldSaveNextLocationAsDoor = false
        }

        guard let latitude = defaults.object(forKey: "doorLatitude") as? Double,
              let longitude = defaults.object(forKey: "doorLongitude") as? Double else {
            distanceToDoor = nil
            return
        }
        let door = CLLocation(latitude: latitude, longitude: longitude)
        distanceToDoor = location.distance(from: door)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        locationStatus = "Location check failed: \(error.localizedDescription)"
        shouldSaveNextLocationAsDoor = false
    }

    private func requestLocation() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationStatus = "Requesting When In Use location permission…"
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            locationStatus = "Waiting for a fresh location fix…"
            locationManager.requestLocation()
        case .denied, .restricted:
            shouldSaveNextLocationAsDoor = false
            locationStatus = "Location permission is denied or restricted. Enable it in Settings to test the door location."
        @unknown default:
            locationStatus = "Location authorization state is unknown."
        }
    }

    private func refreshAuthorizationStatus() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            locationAuthorization = "Not requested"
        case .restricted:
            locationAuthorization = "Restricted"
        case .denied:
            locationAuthorization = "Denied"
        case .authorizedAlways:
            locationAuthorization = locationManager.accuracyAuthorization == .fullAccuracy ? "Always · Precise" : "Always · Approximate"
        case .authorizedWhenInUse:
            locationAuthorization = locationManager.accuracyAuthorization == .fullAccuracy ? "When In Use · Precise" : "When In Use · Approximate"
        @unknown default:
            locationAuthorization = "Unknown"
        }
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

private struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}