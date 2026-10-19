# GPS Tools

Android app that shows what the device's GNSS receiver sees. Plain Java on the Android
framework only: no AndroidX, no Kotlin stdlib, no third-party libraries (the `dependencies`
block is empty on purpose).

## Screens

- **Status**: latitude/longitude (decimal and DMS), WGS84 and MSL altitude, geoid separation,
  horizontal/vertical/speed/bearing accuracy, PDOP/HDOP/VDOP, NMEA fix quality,
  used/in-view counts per constellation, TTFF, fix time and age.
- **Coordinate formats** (Status tab, tap to copy): decimal degrees, DMS, degrees + decimal
  minutes, UTM, MGRS, British National Grid (OSGB36, Helmert datum shift, ~3–5 m), Plus
  Codes and **voice codes**. Waypoints can be typed in any of them (or pasted as a `geo:` link).
  The converters live in `coords/` with no Android dependency and were checked against an
  independent UTM implementation, the Ordnance Survey worked example and the official Open
  Location Code test vectors.
- **Text code** (`coords/TextCode`, Tools → Location codes): the most compact form for
  messages, letters and digits, no separators. Crockford Base32 (0–9, A–Z without I L O U;
  case-insensitive, O→0, I/L→1). The length gives the precision and range:

  | | ~10 m | ~3 m | reader must be within |
  |---|---|---|---|
  | local | 6 chars | 7 chars | ±23 km N-S |
  | regional | 8 chars | 9 chars | ±365 km N-S |
  | global | 10 chars | 11 chars | anywhere |

  Example: Nevsky Cathedral `DGHXVKCPEY` (global, 10 m), `XSPB7H` (local). Every code ends
  in a 6–9 bit CRC, so any single wrong character is always detected; shared bare, as
  compact as possible.
- **Voice code** (`coords/VoiceCode`, Tools tab): this app's own format for exchanging a
  position as one pronounceable word, by voice/radio, SMS/chat or QR. 64 consonant-vowel
  syllables (16 consonants × a e i o, 6 bits each), ~3 m cells, latitude/longitude bits
  interleaved so shared prefixes mean nearby places:
  - global: 9 syllables / 18 letters, anywhere (`kobigolesokekeriza`);
  - regional: 7 syllables / 14 letters, receiver within ±182 km N-S;
  - local: 5 syllables / 10 letters, receiver within ±5.7 km N-S.

  Short codes are completed with the receiver's own position (like Plus Code short codes); a
  result near the edge of the window is flagged. Every code carries a CRC (7, 8 or 6 bits), so
  any single wrong syllable is always detected. The QR (own encoder, byte mode, level M,
  versions 1–10) holds a standard `geo:` URI labelled with the code, so any camera opens it
  in a map; the app also receives shared text and `geo:` links.
- **Satellites**: every tracked signal with RINEX id (G05, R07, E11, C23, J02, I03, S31),
  constellation, PRN/slot/FCN, band (L1, L5, E5a, B1I…), C/N0, elevation, azimuth and
  used / ephemeris / almanac flags. Sort by system, signal or elevation.
- **Sky**: polar sky plot (filled = used in fix) and a C/N0 bar chart, or a Stellarium-style
  **3D view**: horizon, ground, alt/az grid, compass points, the Sun, satellites with motion
  trails; drag/pinch to look around or "Compass" to point the phone at the sky (rotation
  vector sensor, corrected to true north); tap a satellite for its details.
- **Integrity**: heuristic **jamming** (AGC drop per band from raw measurements, C/N0
  collapse, signal loss) and **spoofing** detection (mock location, GNSS vs network position,
  impossible jumps, GNSS vs phone clock and time jumps, C/N0 uniformity, C/N0 vs elevation
  correlation, unusually strong signals, constellations vanishing). Verdict cards, a 3-minute
  C/N0/AGC chart, per-check explanations; the tab label turns into a warning from any tab.
- **Tools**:
  - **Session recorder**: NMEA, satellites CSV, fixes CSV, GPX, KML and **RINEX 3.04**
    observations (code, phase, Doppler, C/N0 from raw measurements). Runs as a foreground
    service, so it keeps recording with the screen off. Sessions are shared through a small
    built-in content provider or pulled over USB from `Android/data/<package>/files/sessions`.
  - **Position averaging**: survey a point; mean position, σ E/N/U, CEP50, 2DRMS and a
    scatter plot; warns when the receiver's static hold repeats the same fix.
  - **Quality history**: accuracy, DOP, satellites and signal over 5/15/30 minutes. Tapping a
    satellite on the Satellites tab shows its own C/N0 and elevation history.
  - **Orbits & passes**: CelesTrak GNSS TLEs, a light J2 propagator, a 12-hour visibility
    forecast per constellation and upcoming rises/sets. GLONASS and Galileo are identified by
    matching tracked satellites. The 3D view shows predicted (also below-horizon) satellites
    and the orbit of the selected one.
  - **Waypoints**: save the current or averaged position or typed coordinates; navigate with an
    arrow (magnet-safe compass, GPS course fallback) and a pin on the 3D horizon. Share any
    waypoint as a voice code.
  - **Backtrack**: records a breadcrumb trail (≥ 5 m steps, fixes better than 25 m) with the
    screen off, then guides back along the same path in reverse: arrow to a breadcrumb ~15 m
    ahead, remaining distance along the trail, off-trail distance, trail map, and alerts when
    straying more than 30 m or on arrival.
  - **Location codes**: text codes (10 m / 3 m) and voice codes for the current position or a
    waypoint, with copy/share/QR, plus a decoder for received codes; see above.
  - **NMEA log**: live raw sentence log with pause and clear.
- Menu: copy coordinates, open in a map app, share, keep screen on, clear assistance data
  (cold start, to measure TTFF).

Permissions: precise location, foreground service (location) and notifications for the
recorder and backtrack, internet for orbit downloads.

## Build

Requirements: Android SDK with platform 36, and a full JDK 17+ that includes `jlink`
(IntelliJ's bundled JBR does not).

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew lintDebug
./gradlew installDebug         # with a device connected
```

`local.properties` (not committed) points to the SDK: `sdk.dir=C\:/path/to/sdk`.

minSdk 24 (first API with `GnssStatus` and `OnNmeaMessageListener`), targetSdk 36.
