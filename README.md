# DashLink — a better companion app for VIOFO dashcams

DashLink is an unofficial Android app for VIOFO dashcams (A119, A129, A139, A229, A329,
T130, WM1/BM1, VS1). It is a ground-up rewrite in Kotlin + Jetpack Compose. The camera
protocol and per-model settings tables come from the official VIOFO app (v3.2.23); the
connection handling and UI are new.

> Not affiliated with VIOFO. Use at your own risk; firmware updates still need the official app or an SD card.

## What's better than the official app

| | Official app | DashLink |
|---|---|---|
| **Joining the camera** | You join its Wi‑Fi in Android settings yourself | DashLink requests the camera network itself (`WifiNetworkSpecifier`) and remembers each camera |
| **Mobile data** | Android often routes requests over mobile data, so "can't connect" | Only camera traffic goes to the camera; maps, sharing and other apps keep using mobile data |
| **Dropouts** | Live view closes, and you reconnect by hand | Reconnects on its own (up to 4 tries), resumes paused downloads, and re-opens the live stream |
| **Recording safety** | Browsing files stops recording | Recording resumes automatically when you leave the file browser, background the app, or downloads finish |
| **Downloads** | Start over if interrupted | Resume where they left off (HTTP Range), run in the background, and land in your gallery (Movies/DashLink) |
| **Live view** | Basic | Low-latency stream that reconnects on its own and falls back from UDP to TCP |
| **Event socket** | Assumes one message per read; no reconnect | Buffers properly and reconnects; shows SD-card removed/full/slow alerts |
| **Settings** | Long static screen | Grouped, shows only what your model and firmware support, plus a destructive-action guard (type FORMAT to format) |
| **GPS playback** | Uses Chinese map offsets | Plain WGS‑84 on OpenStreetMap, speed overlay in mph or km/h, no API key |
| **Size** | ~32 MB | ~3 MB |

## Foldables and large screens

Built and checked for the Pixel 10 Pro Fold (outer 411×900 dp, inner 791×820 dp):

- **Navigation:** a bottom bar when folded, a side rail when unfolded.
- **Files:** list-detail when unfolded; the clip plays next to the grid, and you can switch between front and rear in place.
- **Settings:** two panes when unfolded, with the section list on the left and its settings on the right.
- **Tabletop mode:** half-fold the phone on the dashboard. Live view sits above the hinge, and the controls sit on the flat half.
- **Full screen:** phones rotate into it; unfolded, it just hides the app chrome and system bars.
- **Saved clips:** video and route map side by side when unfolded.

Layouts are driven by window size class and fold posture (`ui/Adaptive.kt`), never by device model, so other foldables and tablets work too.

## Features

- **Live view**: record/stop, snapshot, microphone toggle, front/rear/interior/PiP switching, full screen on rotate
- **Files**: loop / event / parking / photo filters and per-lens filters, grouped by day with thumbnails. Long-press to multi-select, then download or delete. Stream any clip straight from the camera and switch between front and rear of the same moment.
- **Settings**: resolution, loop length, HDR/WDR, exposure, bitrate, G-sensor, parking mode and timers, GPS and stamps, car number and custom text, time zone, beep/voice, Wi‑Fi band, camera Wi‑Fi name/password, clock sync, format SD, reset, restart
- **Saved clips**: downloaded videos with GPS route map, live speed readout, and sharing
- **Multiple cameras** remembered, with optional auto-connect on launch
- **Day timeline**: above each day's clips, a 24-hour strip shows when the camera was recording; event clips are amber, parking clips are blue

## Install / build

The build output is `app/build/outputs/apk/release/app-release.apk`. It is signed with the debug key, so you can sideload it directly; switch to your own signing config before distributing.

```bash
# needs JDK 17+ and the Android SDK (platform 35)
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease          # or assembleDebug
./gradlew testDebugUnitTest        # unit + Robolectric tests; writes screenshots for folded,
                                   # unfolded, tabletop, light theme and 130% text to app/build/screenshots
```

Minimum Android 8.0. Android 10+ is needed for the app to join the camera Wi‑Fi itself; on older phones, join it in Wi‑Fi settings and tap **Already on camera Wi‑Fi**.

## How it works

```
ui/            Compose screens (Home, Camera[Live|Files|Settings], players, library, downloads)
session/       CameraSession: connect → handshake → heartbeat, push events, mode switching,
               auto-reconnect, "resume recording" safety net
api/           NovatekClient (HTTP CGI at 192.168.1.254/?custom=1&cmd=…), Settings (model
               detection from firmware string + label tables in assets/option_tables.json)
net/           CameraLink (Wi‑Fi request), CameraSocketFactory (per-socket routing),
               NotifySocket (TCP :3333 status events)
data/          Resumable download queue + foreground service, preferences, local media
gps/           Parser for the GPS track Novatek cameras embed in MP4 files
```

Protocol notes:
- **Commands are serialised.** These cameras crash on concurrent commands.
- **Listing and downloading files need playback mode** (`3001 par=2`), which stops recording. DashLink stops recording cleanly first (`2001 par=0`), remembers that it did, and restarts it afterwards.
- **Settings are read with `3014`.** Only settings the camera reports are shown.
- **Values are written as `cmd=<n>&par=<index>`.** Resolution and exposure on the A139 family use `str=` values instead.

## Known limitations

- **Untested on real cameras.** The protocol was checked against the official app's code and a simulated camera in the tests.
- **No OTA firmware updates.** The settings screen shows the firmware version; update from viofo.com via the SD card.
- **Resolution is read-only on some models.** On the A229 Plus/Pro/Duo and A329/A339 it is shown but must be changed on the camera, because their option mappings couldn't be verified.
- **GPS may not show for some clips.** Newer firmware may store GPS in a format this parser doesn't read yet; those clips play normally without a route.

## Credits

Titles and numbers are set in [Barlow Semi Condensed](https://github.com/jpt/barlow) (SIL Open Font License 1.1; the licence ships in `app/src/main/assets/licenses/`).
