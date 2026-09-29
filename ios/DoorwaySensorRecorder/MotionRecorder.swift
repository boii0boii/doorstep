import CoreMotion
import Foundation
import UIKit

@MainActor
final class MotionRecorder: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var status = "Ready"
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var recordings: [Recording] = []
    @Published private(set) var availability: SensorAvailability
    @Published var selectedLabel: RecordingLabel = .doorCrossing
    @Published private(set) var doorMarkerAt: Date?

    private let motionManager = CMMotionManager()
    private let altimeter = CMAltimeter()
    private let pedometer = CMPedometer()
    private let activityManager = CMMotionActivityManager()
    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "DoorwaySensorRecorder.motion"
        queue.qualityOfService = .userInitiated
        return queue
    }()
    private let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return encoder
    }()

    private var startedAt: Date?
    private var timer: Timer?
    private var samples: [SensorSample] = []
    private var latestDeviceMotion: (CMDeviceMotion, Date)?
    private var latestMagnetometer: (CMMagnetometerData, Date)?
    private var latestAltitude: (Double, Date)?
    private var latestPedometer: (Int, Double, Date)?
    private var latestActivity: (String, Date)?

    override init() {
        availability = SensorAvailability(
            accelerometer: motionManager.isAccelerometerAvailable,
            deviceMotion: motionManager.isDeviceMotionAvailable,
            magnetometer: motionManager.isMagnetometerAvailable,
            altimeter: CMAltimeter.isRelativeAltitudeAvailable(),
            pedometer: CMPedometer.isStepCountingAvailable(),
            motionActivity: CMMotionActivityManager.isActivityAvailable(),
            heading: false
        )
        super.init()
        recordings = loadRecordings()
    }

    var canMarkDoor: Bool {
        isRecording && selectedLabel == .doorCrossing && doorMarkerAt == nil
    }

    func startRecording() {
        guard !isRecording else { return }
        let now = Date()
        startedAt = now
        doorMarkerAt = nil
        samples.removeAll(keepingCapacity: true)
        latestDeviceMotion = nil
        latestMagnetometer = nil
        latestAltitude = nil
        latestPedometer = nil
        latestActivity = nil
        isRecording = true
        status = "Recording"
        startSensors(from: now)
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, let startedAt = self.startedAt else { return }
                self.elapsed = Date().timeIntervalSince(startedAt)
            }
        }
    }

    func markDoor() {
        guard canMarkDoor else { return }
        doorMarkerAt = Date()
        status = "Door marked"
    }

    func stopRecording() {
        guard isRecording, let startedAt else { return }
        let endedAt = Date()
        stopSensors()
        timer?.invalidate()
        timer = nil
        let recording = Recording(
            id: UUID(),
            label: selectedLabel,
            startedAt: startedAt,
            endedAt: endedAt,
            doorMarkerAt: doorMarkerAt,
            deviceModel: UIDevice.current.model + " (" + deviceModelIdentifier() + ")",
            systemVersion: UIDevice.current.systemVersion,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0",
            availability: availability,
            samples: samples
        )
        recordings.insert(recording, at: 0)
        saveRecordings()
        isRecording = false
        status = "Saved \(recording.samples.count) samples"
        elapsed = endedAt.timeIntervalSince(startedAt)
        self.startedAt = nil
    }

    func deleteRecordings(at offsets: IndexSet) {
        recordings.remove(atOffsets: offsets)
        saveRecordings()
    }

    func exportArchive() -> URL? {
        guard let directory = try? FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true) else { return nil }
        let folder = directory.appendingPathComponent("DoorwaySensorExport-\(Int(Date().timeIntervalSince1970))", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            for recording in recordings {
                let data = try encoder.encode(recording)
                try data.write(to: folder.appendingPathComponent("\(recording.id.uuidString).json"), options: .atomic)
            }
            let archive = directory.appendingPathComponent("DoorwaySensorRecordings-\(Int(Date().timeIntervalSince1970)).zip")
            try ZipArchive.create(from: folder, at: archive)
            return archive
        } catch {
            status = "Export failed: \(error.localizedDescription)"
            return nil
        }
    }

    private func startSensors(from startDate: Date) {
        if availability.accelerometer {
            motionManager.accelerometerUpdateInterval = 1.0 / 50.0
            motionManager.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
                guard let self, let data else { return }
                Task { @MainActor in self.appendSample(at: Date(), accelerometer: data.acceleration) }
            }
        }
        if availability.deviceMotion {
            motionManager.deviceMotionUpdateInterval = 1.0 / 50.0
            motionManager.startDeviceMotionUpdates(using: .xArbitraryCorrectedZVertical, to: queue) { [weak self] data, _ in
                guard let self, let data else { return }
                Task { @MainActor in self.latestDeviceMotion = (data, Date()) }
            }
        }
        if availability.magnetometer {
            motionManager.magnetometerUpdateInterval = 1.0 / 25.0
            motionManager.startMagnetometerUpdates(to: queue) { [weak self] data, _ in
                guard let self, let data else { return }
                Task { @MainActor in self.latestMagnetometer = (data, Date()) }
            }
        }
        if availability.altimeter {
            altimeter.startRelativeAltitudeUpdates(to: queue) { [weak self] data, _ in
                guard let self, let data else { return }
                Task { @MainActor in self.latestAltitude = (data.relativeAltitude.doubleValue, Date()) }
            }
        }
        if availability.pedometer {
            pedometer.startUpdates(from: startDate) { [weak self] data, _ in
                guard let self, let data else { return }
                Task { @MainActor in
                    self.latestPedometer = (data.numberOfSteps.intValue, data.distance?.doubleValue ?? 0, Date())
                }
            }
        }
        if availability.motionActivity {
            activityManager.startActivityUpdates(to: queue) { [weak self] activity in
                guard let self, let activity else { return }
                Task { @MainActor in self.latestActivity = (self.activityName(activity), Date()) }
            }
        }
    }

    private func stopSensors() {
        motionManager.stopAccelerometerUpdates()
        motionManager.stopDeviceMotionUpdates()
        motionManager.stopMagnetometerUpdates()
        altimeter.stopRelativeAltitudeUpdates()
        pedometer.stopUpdates()
        activityManager.stopActivityUpdates()
    }

    private func appendSample(at timestamp: Date, accelerometer: CMAcceleration) {
        guard isRecording else { return }
        let motion = latestDeviceMotion
        let magnetic = latestMagnetometer
        let altitude = latestAltitude
        let pedometer = latestPedometer
        let activity = latestActivity
        samples.append(SensorSample(
            id: UUID(),
            timestamp: timestamp,
            accelerometer: Vector3(x: accelerometer.x, y: accelerometer.y, z: accelerometer.z),
            userAcceleration: motion.map { Vector3(x: $0.0.userAcceleration.x, y: $0.0.userAcceleration.y, z: $0.0.userAcceleration.z) },
            rotationRate: motion.map { Vector3(x: $0.0.rotationRate.x, y: $0.0.rotationRate.y, z: $0.0.rotationRate.z) },
            attitude: motion.map { Attitude(roll: $0.0.attitude.roll, pitch: $0.0.attitude.pitch, yaw: $0.0.attitude.yaw, quaternionX: $0.0.attitude.quaternion.x, quaternionY: $0.0.attitude.quaternion.y, quaternionZ: $0.0.attitude.quaternion.z, quaternionW: $0.0.attitude.quaternion.w) },
            magneticField: magnetic.map { Vector3(x: $0.0.magneticField.x, y: $0.0.magneticField.y, z: $0.0.magneticField.z) },
            headingDegrees: nil,
            relativeAltitudeMeters: altitude?.0,
            stepCount: pedometer?.0,
            distanceMeters: pedometer?.1,
            activityState: activity?.0,
            sourceTimestamps: SourceTimestamps(deviceMotion: motion?.1, magnetometer: magnetic?.1, altimeter: altitude?.1, pedometer: pedometer?.2, activity: activity?.1)
        ))
    }

    private func activityName(_ activity: CMMotionActivity) -> String {
        if activity.stationary { return "stationary" }
        if activity.walking { return "walking" }
        if activity.running { return "running" }
        if activity.cycling { return "cycling" }
        if activity.automotive { return "automotive" }
        return "unknown"
    }

    private var storageURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("recordings.json")
    }

    private func loadRecordings() -> [Recording] {
        guard let data = try? Data(contentsOf: storageURL), let value = try? JSONDecoder.iso8601.decode([Recording].self, from: data) else { return [] }
        return value
    }

    private func saveRecordings() {
        do {
            try FileManager.default.createDirectory(at: storageURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try encoder.encode(recordings).write(to: storageURL, options: .atomic)
        } catch {
            status = "Save failed: \(error.localizedDescription)"
        }
    }

    private func deviceModelIdentifier() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        return withUnsafeBytes(of: &systemInfo.machine) { buffer in
            String(bytes: buffer, encoding: .ascii)?.trimmingCharacters(in: .controlCharacters) ?? "unknown"
        }
    }
}

private extension JSONDecoder {
    static var iso8601: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

private enum ZipArchive {
    static func create(from directory: URL, at destination: URL) throws {
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        var output = Data()
        var centralDirectory = Data()
        for file in files {
            let data = try Data(contentsOf: file)
            let name = file.lastPathComponent.data(using: .utf8)!
            let checksum = CRC32.checksum(data)
            let offset = UInt32(output.count)
            output.append(le32(0x04034b50)); output.append(le16(20)); output.append(le16(0)); output.append(le16(0)); output.append(le16(0)); output.append(le16(0)); output.append(le32(checksum)); output.append(le32(UInt32(data.count))); output.append(le32(UInt32(data.count))); output.append(le16(UInt16(name.count))); output.append(le16(0)); output.append(name); output.append(data)
            centralDirectory.append(le32(0x02014b50)); centralDirectory.append(le16(20)); centralDirectory.append(le16(20)); centralDirectory.append(le16(0)); centralDirectory.append(le16(0)); centralDirectory.append(le16(0)); centralDirectory.append(le16(0)); centralDirectory.append(le32(checksum)); centralDirectory.append(le32(UInt32(data.count))); centralDirectory.append(le32(UInt32(data.count))); centralDirectory.append(le16(UInt16(name.count))); centralDirectory.append(le16(0)); centralDirectory.append(le16(0)); centralDirectory.append(le16(0)); centralDirectory.append(le16(0)); centralDirectory.append(le32(0)); centralDirectory.append(le32(offset)); centralDirectory.append(name)
        }
        output.append(centralDirectory)
        output.append(le32(0x06054b50)); output.append(le16(0)); output.append(le16(0)); output.append(le16(UInt16(files.count))); output.append(le16(UInt16(files.count))); output.append(le32(UInt32(centralDirectory.count))); output.append(le32(UInt32(output.count - centralDirectory.count))); output.append(le16(0))
        try output.write(to: destination, options: .atomic)
    }

    private static func le16(_ value: UInt16) -> Data { withUnsafeBytes(of: value.littleEndian) { Data($0) } }
    private static func le32(_ value: UInt32) -> Data { withUnsafeBytes(of: value.littleEndian) { Data($0) } }
}

private enum CRC32 {
    static func checksum(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xffffffff
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) == 1 ? 0xedb88320 : 0) }
        }
        return crc ^ 0xffffffff
    }
}