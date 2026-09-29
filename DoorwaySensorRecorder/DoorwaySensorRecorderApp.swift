import SwiftUI

@main
struct DoorwaySensorRecorderApp: App {
    @StateObject private var recorder = MotionRecorder()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(recorder)
        }
    }
}