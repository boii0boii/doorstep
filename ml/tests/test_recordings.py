import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from doorstep import load, parse, validate  # noqa: E402
from doorstep.recordings import ACCELEROMETER, STEP_COUNTER  # noqa: E402

SAMPLES = Path(__file__).resolve().parents[2] / "samples" / "recordings"
MARKED = "47555281-56fb-49db-8ef3-1739d444e497.json"


def document(**overrides):
    base = {
        "schemaVersion": 2,
        "recordingId": "00000000-0000-0000-0000-000000000001",
        "label": "Normal Movement",
        "startElapsedRealtimeNanos": 1_000_000_000,
        "endElapsedRealtimeNanos": 3_000_000_000,
        "samples": [
            {"sensorType": ACCELEROMETER, "sensorName": "accel", "elapsedRealtimeNanos": 1_000_000_000 + i * 20_000_000,
             "timestampUtc": "", "values": [0.0, 0.0, 9.8]}
            for i in range(100)
        ],
    }
    base.update(overrides)
    return base


class SampleRecordingTest(unittest.TestCase):
    def test_bundled_samples_load(self):
        recordings = load(SAMPLES)
        self.assertEqual(2, len(recordings))
        for rec in recordings:
            self.assertEqual("Door Crossing", rec.label)
            self.assertGreater(rec.streams[ACCELEROMETER].rate_hz(), 45)

    def test_marker_is_inside_marked_recording(self):
        rec = load(SAMPLES / MARKED)[0]
        self.assertAlmostEqual(8.67, rec.door_marker_s, places=2)
        self.assertLess(rec.door_marker_s, rec.duration_s)

    def test_step_counter_deltas_become_step_times(self):
        rec = load(SAMPLES / MARKED)[0]
        counts = rec.streams[STEP_COUNTER].values[:, 0]
        self.assertEqual(int(counts[-1] - counts[0]), len(rec.step_times()))


class ValidationTest(unittest.TestCase):
    def test_clean_document_has_no_problems(self):
        self.assertEqual([], validate(parse(document(), "clean")))

    def test_door_crossing_without_marker_is_flagged(self):
        problems = validate(parse(document(label="Door Crossing"), "unmarked"))
        self.assertTrue(any("without a door marker" in p for p in problems))

    def test_backwards_timestamps_are_flagged(self):
        doc = document()
        doc["samples"][5]["elapsedRealtimeNanos"] = 1_000_000_000
        self.assertTrue(any("backwards" in p for p in validate(parse(doc, "backwards"))))

    def test_unknown_schema_is_flagged(self):
        self.assertTrue(any("schemaVersion" in p for p in validate(parse(document(schemaVersion=9), "future"))))

    def test_low_sample_rate_is_flagged(self):
        doc = document()
        doc["samples"] = doc["samples"][::5]
        self.assertTrue(any("below 25 Hz" in p for p in validate(parse(doc, "slow"))))


if __name__ == "__main__":
    unittest.main()
