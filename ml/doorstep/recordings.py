"""Load and validate Doorstep recordings exported by the Android app.

A recording is one JSON document (schema v1 or v2). Recordings can be read from a
directory of JSON files, a single file, or the ZIP produced by the app's Export button.
"""

from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

import numpy as np

# android.hardware.Sensor type constants used by the app.
ACCELEROMETER = 1
MAGNETIC_FIELD = 2
GYROSCOPE = 4
PRESSURE = 6
LINEAR_ACCELERATION = 10
ROTATION_VECTOR = 11
STEP_DETECTOR = 18
STEP_COUNTER = 19

SENSOR_NAMES = {
    ACCELEROMETER: "accelerometer",
    MAGNETIC_FIELD: "magnetometer",
    GYROSCOPE: "gyroscope",
    PRESSURE: "barometer",
    LINEAR_ACCELERATION: "linearAcceleration",
    ROTATION_VECTOR: "rotationVector",
    STEP_DETECTOR: "stepDetector",
    STEP_COUNTER: "stepCounter",
}

SUPPORTED_SCHEMAS = {1, 2}
NANOS = 1_000_000_000


@dataclass
class Stream:
    """All events of one sensor type: times in seconds from recording start, values per axis."""

    sensor_type: int
    t: np.ndarray
    values: np.ndarray

    @property
    def name(self) -> str:
        return SENSOR_NAMES.get(self.sensor_type, f"type{self.sensor_type}")

    def magnitude(self) -> np.ndarray:
        return np.linalg.norm(self.values[:, :3], axis=1)

    def rate_hz(self) -> float:
        if len(self.t) < 2:
            return 0.0
        return float((len(self.t) - 1) / (self.t[-1] - self.t[0]))


@dataclass
class Recording:
    source: str
    meta: dict
    streams: dict[int, Stream] = field(default_factory=dict)

    @property
    def recording_id(self) -> str:
        return self.meta.get("recordingId", self.source)

    @property
    def label(self) -> str:
        return self.meta.get("label", "")

    @property
    def start_nanos(self) -> int:
        return int(self.meta["startElapsedRealtimeNanos"])

    @property
    def duration_s(self) -> float:
        return (int(self.meta["endElapsedRealtimeNanos"]) - self.start_nanos) / NANOS

    def seconds(self, elapsed_nanos: int | None) -> float | None:
        """Converts an elapsedRealtimeNanos value to seconds from recording start."""
        return None if elapsed_nanos is None else (int(elapsed_nanos) - self.start_nanos) / NANOS

    @property
    def door_marker_s(self) -> float | None:
        return self.seconds(self.meta.get("doorMarkerElapsedRealtimeNanos"))

    @property
    def wifi_lost_s(self) -> float | None:
        return self.seconds(self.meta.get("homeWifiLostElapsedRealtimeNanos"))

    def step_times(self) -> np.ndarray:
        """Step times in seconds, from step-detector events or, failing that, step-counter deltas."""
        if STEP_DETECTOR in self.streams:
            return self.streams[STEP_DETECTOR].t.copy()
        counter = self.streams.get(STEP_COUNTER)
        if counter is None or len(counter.t) < 2:
            return np.empty(0)
        counts = counter.values[:, 0]
        times = [t for t, delta in zip(counter.t[1:], np.diff(counts)) for _ in range(max(int(delta), 0))]
        return np.asarray(times, dtype=float)


def parse(document: dict, source: str) -> Recording:
    meta = {key: value for key, value in document.items() if key != "samples"}
    recording = Recording(source=source, meta=meta)
    start = int(document["startElapsedRealtimeNanos"])
    grouped: dict[int, tuple[list[float], list[list[float]]]] = {}
    for sample in document.get("samples", []):
        times, values = grouped.setdefault(int(sample["sensorType"]), ([], []))
        times.append((int(sample["elapsedRealtimeNanos"]) - start) / NANOS)
        values.append([float(v) for v in sample["values"]])
    for sensor_type, (times, values) in grouped.items():
        width = max(len(v) for v in values)
        padded = np.full((len(values), width), np.nan)
        for row, v in enumerate(values):
            padded[row, : len(v)] = v
        recording.streams[sensor_type] = Stream(sensor_type, np.asarray(times), padded)
    return recording


def load(path: str | Path) -> list[Recording]:
    """Loads recordings from a JSON file, a directory of JSON files, or an exported ZIP."""
    path = Path(path)
    return sorted(_iter_documents(path), key=lambda r: r.meta.get("startedAtUtc", ""))


def _iter_documents(path: Path) -> Iterator[Recording]:
    if path.is_dir():
        for file in sorted(path.glob("*.json")):
            yield parse(json.loads(file.read_text(encoding="utf-8")), file.name)
    elif path.suffix == ".zip":
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith(".json"):
                    yield parse(json.loads(archive.read(name)), name)
    else:
        yield parse(json.loads(path.read_text(encoding="utf-8")), path.name)


def validate(recording: Recording) -> list[str]:
    """Returns a list of problems; an empty list means the recording is usable."""
    problems: list[str] = []
    meta = recording.meta
    for key in ("schemaVersion", "recordingId", "label", "startElapsedRealtimeNanos", "endElapsedRealtimeNanos"):
        if key not in meta:
            problems.append(f"missing field {key}")
    if problems:
        return problems
    if meta["schemaVersion"] not in SUPPORTED_SCHEMAS:
        problems.append(f"unsupported schemaVersion {meta['schemaVersion']}")
    if recording.duration_s <= 0:
        problems.append("end is not after start")
    if not recording.streams:
        problems.append("no sensor samples")
    for stream in recording.streams.values():
        if np.any(np.diff(stream.t) < 0):
            problems.append(f"{stream.name} timestamps go backwards")
        before = int(np.sum(stream.t < -1e-3))
        after = int(np.sum(stream.t > recording.duration_s + 1e-3))
        if before:
            # Batched sensors (notably the step counter) stamp events when the step happened,
            # which can precede registration; they are real but not inside the window.
            problems.append(f"{before} {stream.name} event(s) stamped before start")
        if after:
            problems.append(f"{after} {stream.name} event(s) stamped after end")
    marker = recording.door_marker_s
    if marker is not None and not 0 <= marker <= recording.duration_s:
        problems.append("door marker is outside the recording window")
    if recording.label == "Door Crossing" and marker is None:
        problems.append("Door Crossing without a door marker (cannot be aligned for event-timed training)")
    accel = recording.streams.get(ACCELEROMETER)
    if accel is None:
        problems.append("no accelerometer data")
    elif accel.rate_hz() < 25:
        problems.append(f"accelerometer rate {accel.rate_hz():.0f} Hz is below 25 Hz")
    return problems


def summarise(recordings: Iterable[Recording]) -> str:
    rows = [("recording", "label", "length", "marker", "accel Hz", "steps", "issues")]
    for rec in recordings:
        accel = rec.streams.get(ACCELEROMETER)
        marker = rec.door_marker_s
        issues = validate(rec)
        rows.append((
            rec.recording_id[:8],
            rec.label,
            f"{rec.duration_s:.1f} s",
            "-" if marker is None else f"{marker:.1f} s",
            f"{accel.rate_hz():.0f}" if accel else "-",
            str(len(rec.step_times())),
            "ok" if not issues else "; ".join(issues),
        ))
    widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]) - 1)]
    lines = []
    for index, row in enumerate(rows):
        cells = [cell.ljust(width) for cell, width in zip(row, widths)] + [row[-1]]
        lines.append("  ".join(cells))
        if index == 0:
            lines.append("  ".join("-" * width for width in widths) + "  ------")
    return "\n".join(lines)
