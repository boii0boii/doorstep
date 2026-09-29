# Offline tools

Python tools for recordings exported from the app (a ZIP from **Export ZIP**, a folder of JSON files, or a single file). They run on your computer; nothing is uploaded.

```sh
pip install -r ml/requirements.txt

python ml/report.py samples/recordings           # data-quality report
python ml/plot_recording.py samples/recordings/47555281-56fb-49db-8ef3-1739d444e497.json door.png
python -m unittest discover -s ml/tests -t ml    # tests
```

`report.py` checks each recording: schema version, per-sensor timestamp order, events outside the recording window, a missing door marker on Door Crossing recordings, and accelerometer rate. It also counts steps.

```
recording  label          length  marker  accel Hz  steps  issues
---------  -------------  ------  ------  --------  -----  ------
98df0f3b   Door Crossing  19.6 s  -       57        11     1 stepCounter event(s) stamped before start; Door Crossing without a door marker (…)
47555281   Door Crossing  10.1 s  8.7 s   57        2      3 stepCounter event(s) stamped before start
```

`plot_recording.py` draws acceleration, rotation, magnetic field and barometric relative altitude, aligned to the door marker (or to the Wi-Fi loss for automatic departures):

![Door crossing sensor plot](../docs/images/door-crossing.png)

## Planned

Windowed feature extraction, a regularised logistic-regression baseline evaluated on held-out recording sessions, and export of a versioned JSON model that the Android app can score on-device. See [docs/ANDROID_PLAN.md](../docs/ANDROID_PLAN.md) for the original training plan.
