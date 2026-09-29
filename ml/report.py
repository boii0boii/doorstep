"""Prints a data-quality report for Doorstep recordings.

Usage: python ml/report.py samples/recordings   (or an exported ZIP)
Exits with status 1 if any recording is unreadable.
"""

import sys

from doorstep import load, summarise


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.strip())
        return 2
    recordings = load(argv[1])
    if not recordings:
        print(f"No recordings found in {argv[1]}")
        return 1
    print(summarise(recordings))
    labels = {}
    for rec in recordings:
        labels[rec.label] = labels.get(rec.label, 0) + 1
    print("\n" + ", ".join(f"{count} × {label}" for label, count in sorted(labels.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
