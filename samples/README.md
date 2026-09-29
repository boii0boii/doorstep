# Sample recordings

Two real recordings from a Google Pixel 8a (Android 16), both labelled **Door Crossing**. They contain motion sensor data only: no location, no Wi-Fi name.

| Recording | Length | Door marker | Notes |
|---|---|---|---|
| `47555281…` | 10.1 s | at 8.7 s | Plotted in [docs/images/door-crossing.png](../docs/images/door-crossing.png) |
| `98df0f3b…` | 19.6 s | none | Saved without pressing MARK DOOR; 11 counted steps |

Both use recording schema v1 and were saved by an early build of the app, so they differ from current output in two ways:

- **Samples are in arrival order.** Streams from different sensors interleave out of order by a few milliseconds; each sensor's own stream is in order. Current builds sort the merged stream by `SensorEvent.timestamp`.
- **Step-counter events stamped before the start are included.** The Pixel step counter delivers in batches roughly 10 s late and stamps each event with when the step happened, so the first events after registration can precede the recording. No step-counter events arrive in the last ~10 s of either recording for the same reason.

`python ml/report.py samples/recordings` prints a quality report, and `RealRecordingReplayTest` in the Android unit tests replays these recordings through the departure rule.
