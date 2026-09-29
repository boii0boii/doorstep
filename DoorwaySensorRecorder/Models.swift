import Foundation

enum RecordingLabel: String, CaseIterable, Codable, Identifiable {
    case doorCrossing = "Door Crossing"
    case normalMovement = "Normal Movement"

    var id: String { rawValue }
}

struct SensorAvailability: Codable {
    let accelerometer: Bool
    let deviceMotion: Bool
    let magnetometer: Bool
    let altimeter: Bool
    let pedometer: Bool
    let motionActivity: Bool
    let heading: Bool
}

struct SensorSample: Codable, Identifiable {
    let id: UUID
    let timestamp: Date
    let accelerometer: Vector3?
    let userAcceleration: Vector3?
    let rotationRate: Vector3?
    let attitude: Attitude?
    let magneticField: Vector3?
    let headingDegrees: Double?
    let relativeAltitudeMeters: Double?
    let stepCount: Int?
    let distanceMeters: Double?
    let activityState: String?
    let sourceTimestamps: SourceTimestamps
}

struct Vector3: Codable {
    let x: Double
    let y: Double
    let z: Double
}

struct Attitude: Codable {
    let roll: Double
    let pitch: Double
    let yaw: Double
    let quaternionX: Double
    let quaternionY: Double
    let quaternionZ: Double
    let quaternionW: Double
}

struct SourceTimestamps: Codable {
    let deviceMotion: Date?
    let magnetometer: Date?
    let altimeter: Date?
    let pedometer: Date?
    let activity: Date?
}

struct Recording: Codable, Identifiable {
    let id: UUID
    let label: RecordingLabel
    let startedAt: Date
    let endedAt: Date
    let doorMarkerAt: Date?
    let deviceModel: String
    let systemVersion: String
    let appVersion: String
    let availability: SensorAvailability
    let samples: [SensorSample]
}