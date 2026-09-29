"""Plots one Doorstep recording's motion streams, aligned to the door marker.

Usage: python ml/plot_recording.py <recording.json> [output.png]
"""

import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

from doorstep import load
from doorstep.recordings import ACCELEROMETER, GYROSCOPE, LINEAR_ACCELERATION, MAGNETIC_FIELD, PRESSURE

INK = "#17202b"
MUTED = "#5a6573"
GRID = "#e3e7ec"
SERIES = "#2b5d8a"
SERIES_LIGHT = "#8fb1cf"
BRASS = "#a5761d"


def smooth(values: np.ndarray, width: int) -> np.ndarray:
    if len(values) < width:
        return values
    padded = np.pad(values, (width // 2, width - 1 - width // 2), mode="edge")
    return np.convolve(padded, np.ones(width) / width, mode="valid")


def relative_altitude_m(pressure_hpa: np.ndarray) -> np.ndarray:
    # International barometric formula, relative to the first reading.
    return 44330.0 * (1.0 - (pressure_hpa / pressure_hpa[0]) ** (1 / 5.255))


def plot(recording, output: Path) -> None:
    if recording.door_marker_s is not None:
        origin, origin_label = recording.door_marker_s, "seconds from door marker"
    elif recording.wifi_lost_s is not None:
        origin, origin_label = recording.wifi_lost_s, "seconds from home Wi-Fi loss"
    else:
        origin, origin_label = 0.0, "seconds from start"
    streams = recording.streams
    panels = [p for p in (ACCELEROMETER, GYROSCOPE, MAGNETIC_FIELD, PRESSURE) if p in streams]

    plt.rcParams.update({
        "font.family": ["Helvetica Neue", "Arial", "DejaVu Sans"],
        "font.size": 10,
        "axes.edgecolor": GRID,
        "axes.labelcolor": MUTED,
        "xtick.color": MUTED,
        "ytick.color": MUTED,
    })
    fig, axes = plt.subplots(len(panels), 1, figsize=(9, 1.9 * len(panels) + 0.8), sharex=True)
    axes = np.atleast_1d(axes)

    for ax, sensor_type in zip(axes, panels):
        stream = streams[sensor_type]
        t = stream.t - origin
        if sensor_type == ACCELEROMETER:
            ax.plot(t, stream.magnitude(), color=SERIES_LIGHT, lw=0.8, label="total")
            if LINEAR_ACCELERATION in streams:
                lin = streams[LINEAR_ACCELERATION]
                ax.plot(lin.t - origin, lin.magnitude(), color=SERIES, lw=0.9, label="gravity removed")
            ax.set_ylabel("acceleration\nm/s²")
            ax.legend(loc="upper left", frameon=False, ncol=2, fontsize=8.5, bbox_to_anchor=(0, 1.12))
        elif sensor_type == GYROSCOPE:
            ax.plot(t, stream.magnitude(), color=SERIES, lw=0.9)
            ax.set_ylabel("rotation\nrad/s")
        elif sensor_type == MAGNETIC_FIELD:
            ax.plot(t, stream.magnitude(), color=SERIES_LIGHT, lw=0.8)
            ax.plot(t, smooth(stream.magnitude(), 10), color=SERIES, lw=1.4)
            ax.set_ylabel("magnetic field\nµT")
        elif sensor_type == PRESSURE:
            altitude = relative_altitude_m(stream.values[:, 0])
            ax.plot(t, altitude, color=SERIES_LIGHT, lw=0.8)
            ax.plot(t, smooth(altitude, 20), color=SERIES, lw=1.4)
            ax.set_ylabel("relative\naltitude m")
        ax.grid(True, color=GRID, lw=0.6)
        ax.spines[["top", "right"]].set_visible(False)
        if recording.door_marker_s is not None:
            ax.axvline(0, color=BRASS, lw=1.6)
        if recording.wifi_lost_s is not None:
            ax.axvline(recording.wifi_lost_s - origin, color=INK, lw=1.2, ls="--")

    top = axes[0]
    if recording.door_marker_s is not None:
        top.annotate("MARK DOOR", xy=(0, 1), xycoords=("data", "axes fraction"), xytext=(4, 4),
                     textcoords="offset points", color=BRASS, fontsize=9, fontweight="bold")
    axes[-1].set_xlim(-origin, recording.duration_s - origin)
    axes[-1].set_xlabel(origin_label)
    fig.suptitle(f"{recording.label} · {recording.meta.get('deviceModel', '')} · recording {recording.recording_id[:8]}",
                 x=0.01, ha="left", color=INK, fontsize=11.5, fontweight="bold")
    fig.tight_layout()
    fig.savefig(output, dpi=160, facecolor="white")
    print(f"Wrote {output}")


def main(argv: list[str]) -> int:
    if len(argv) not in (2, 3):
        print(__doc__.strip())
        return 2
    source = Path(argv[1])
    output = Path(argv[2]) if len(argv) == 3 else source.with_suffix(".png")
    plot(load(source)[0], output)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
