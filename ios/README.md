# iOS prototype (parked)

The original SwiftUI motion recorder. It records labelled Core Motion sessions and exports JSON/ZIP, and has a Test mode that probes Wi-Fi SSID access and a GPS door coordinate.

Development moved to Android because reading the connected Wi-Fi name on iOS (`NEHotspotNetwork.fetchCurrent()`) requires the Access Wi-Fi Information entitlement, which a free Personal Team cannot provision. Open `DoorwaySensorRecorder.xcodeproj` in Xcode and run on a physical iPhone; see [docs/LOCATION_AND_ML_PLAN.md](../docs/LOCATION_AND_ML_PLAN.md) for the iOS plan.

This recorder predates the Android schema v2 and uses callback-receipt timestamps rather than monotonic sensor timestamps, so its recordings should not be mixed with Android data without conversion.
