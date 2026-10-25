# Ranging and AR review — 2026-10-25

## Changes reviewed

- Ranging: spherical direct projection and acoustic distance, two-point distance and bearings,
  local tangent-plane multi-bearing intersection, map plotting, coordinate input and waypoint actions.
  Longitude deltas now wrap across the date line; invalid/non-finite inputs and backward-majority
  intersections are rejected. Hidden ranging pages release their waypoint listener and sensors.
  Switching away from the timer stops it before the other mode updates the shared map.
  A follow-up map fix handles a known destination while the GPS origin is unavailable.
- AR: optional Camera2 preview beneath the sensor-driven sky, preview optics/scale, calibration,
  runtime permission forwarding, and camera/session/surface cleanup. Late callbacks are scoped to
  their opening generation; disconnected/failed sessions release resources. Fixed-focus cameras
  keep supported focus settings. Display changes refresh the transform even for a 180-degree
  turn that leaves the view dimensions unchanged. Stored calibration values are bounded and finite.
  AR availability requires a usable preview stream as well as a back camera.

Preview rotation/scaling was checked against Android's
[Camera preview documentation](https://developer.android.com/media/camera/camera2/camera-preview).
Visual calibration remains necessary for device-specific lens metadata and compass errors.

## Build and numeric checks

- JDK 17.0.20.1, Gradle wrapper 9.3.0, AGP 8.13.2, Android SDK 36.
- `tools/GeodesicChecks.java`: **629 checks passed**, including known geometry, date-line
  crossings, invalid/overflowing inputs and 100 seeded local cases compared with independent
  spherical distance/bearing calculations. See README for the standalone compile/run commands.
- The exact ranging-only index snapshot was exported to an isolated temporary project and
  matched back to all staged paths. `assembleDebug assembleRelease lintDebug` passed there.
- The complete ranging/AR source passed debug and unsigned release assembly and debug lint.
  Lint reports zero errors and three advisory warnings in ranging strings/UI formatting
  (`PluralsCandidate` and `SetTextI18n`).
- Normal release invocation encountered a machine-wide signing override with a quoted keystore
  path. Release validation therefore used a temporary `GRADLE_USER_HOME` with shared cache/wrapper
  junctions and no machine init scripts or signing properties. No signing credentials or global
  configuration were changed; production release signing was not verified in this step.

## Device smoke checks

Built with a temporary application ID, `com.vladgba.gpstools.review`, and installed alongside
the existing application on PLU110. The temporary package had its own data and permissions.

Passed:

- Live camera preview and sky overlay visible in portrait and landscape.
- Leaving Sky releases its camera client; returning reopens it.
- Backgrounding releases the camera; foregrounding reopens it.
- Eight rapid AR toggles recover to a live camera session.
- Camera stays active at display rotations 0, 90, 180 and 270 degrees. Original system rotation
  settings were restored after the check.
- Opened ranging, started the timer, switched to Two points, and returned: the timer was stopped
  and its captured interval remained visible.
- No Java crash was recorded for the review process during these checks.

The temporary package was uninstalled and its camera client was gone after testing. Device
screenshots and scripts stayed in temporary storage. Camera permission denial, hardware disconnect,
API 24 hardware and absolute overlay alignment were reviewed in code but not reproduced on-device.
These smoke checks do not establish surveying accuracy or validate live triangulation against
known ground targets.
