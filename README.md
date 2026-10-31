# pGPS

A GNSS toolbox for Android that shows what your phone's satellite receiver really sees: every satellite and signal, a 3D and AR sky view, jamming and spoofing checks, a session recorder, waypoints and a way back along your own trail.

## Features

- **Status.** Position in many formats (decimal, DMS, UTM, MGRS, British grid, Plus Codes), altitude, accuracies, DOP, fix quality, time to first fix. Tap any value to copy it.
- **Satellites.** Every tracked signal with its system, band, signal strength, elevation and azimuth; sort by system, signal or elevation.
- **Sky.** A polar sky plot, a signal bar chart, or a 3D sky with the horizon, the Sun and satellite trails. Point the phone at the sky, or turn on **AR** to overlay it on the camera.
- **Integrity.** Heuristic jamming and spoofing detection, with verdict cards, a 3-minute signal chart and an explanation for every check.
- **Recorder.** NMEA, CSV, GPX, KML and RINEX 3.04 logs, kept running with the screen off.
- **Waypoints and navigation.** Save places, navigate with an arrow, average a position for a survey point, and **Backtrack** along a recorded trail.
- **Range and triangulation.** Distances, bearings and the intersection of compass rays.
- **Location codes.** Share a position as a short text code or a pronounceable voice code, or as a QR.
- **Orbits.** A 12-hour visibility forecast and upcoming satellite passes.
- **Mock location.** Set a fake position for testing other apps.
- **No ads, no data collection.**

## Permissions

| Permission | Why | Required |
|---|---|---|
| Location (precise) | Everything GNSS: position, satellites, signals | Yes |
| Foreground service (location) | Recording and Backtrack with the screen off | Optional |
| Notifications | The ongoing recording / Backtrack notification | Optional |
| Internet | Downloading satellite orbit data (CelesTrak) | Optional |
| Camera | The AR sky overlay | Optional |
| Mock location | Setting a fake position for testing (Spoof tool) | Optional |
| Location extra commands | **Clear assistance data**, for a cold start | Optional |

### Notes

- **Mock location** works only after you pick pGPS as the mock location app in Developer options.
- Text and voice code formats, recorder formats and the ranging maths are described in the [guide](docs/GUIDE.md).

## Build

Requires Android 7.0+ (API 24). Built in plain Java on the Android framework, without libraries.

```bash
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`.
