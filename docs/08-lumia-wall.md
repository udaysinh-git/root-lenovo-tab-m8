# 8. Lumia Wall: a Windows Phone 8 home screen for the wall tablet

`lumia/` is a tiny (~110 KB), gradle-less Android app (framework APIs only; `aapt2 + javac + d8`, see `lumia/build.ps1`)
that replaces the launcher with a **Windows Phone 8 panorama**:

- **uday's great wall** panorama title that drifts at 45% of the scroll speed (parallax), with sections:
  - **grace wall**: live tiles: clock (flips to day and week), Pune weather (Open-Meteo, flips to high/low and humidity),
    display (spacedesk), music, reader (KOReader), manga (Mihon), camera, Grace, battery;
  - **music**: placeholder for the laptop now-playing and visualiser bridge (next);
  - **status**: tablet battery and charge state, built-in camera server (live/ready/off), Grace (ssh/komga/jellyfin), Wi-Fi;
  - **apps**: WP8 app list, A–Z with outlined letter tiles.
- **Motion:** tilt on press, random live-tile flips every 6–11 s, **turnstile** out on launch and in on return.
  Panorama releases do **one** glide (fling direction or nearest section) on a long ease-out; the giant title sits on
  its own hardware layer.
- **Look:** pure black, WP accents deepened ~25% for a dim room, text at 90% white. Font: Microsoft's **Selawik**
  (OFL, metric-compatible with Segoe UI).
- **Camera:** `CameraStreamService` (see chapter 7), started from the activity so the camera foreground service keeps
  access in the background.

## Build and install

```powershell
lumia\build.ps1 -Install          # builds, installs on $env:TABLET_SERIAL, launches
adb shell cmd package set-home-activity io.uday.lumiawall/.WallActivity
adb shell pm grant io.uday.lumiawall android.permission.CAMERA
```
Revert to the stock launcher: `cmd package set-home-activity com.android.launcher3/.uioverrides.QuickstepLauncher`.

Build gotchas, both fixed in `build.ps1`:
- `android.jar` has no `java.lang.invoke.LambdaMetafactory`, so javac can't compile lambdas against it. A
  compile-time-only stub (`lumia/buildstubs/`) goes on the bootclasspath; d8 desugars lambdas, so the stub never ships.
- `aapt2 link -A assets` on Windows writes entries as `assets/fonts\x.ttf` (backslash) and Android can't find them
  ("Font asset not found"). Assets are added to the zip manually with `/` separators.

## Boot flow

[`scripts/android/wall-boot.sh`](../scripts/android/wall-boot.sh) (Magisk `service.d`):
1. Boot: open Lumia Wall (starts the camera streamer), then **spacedesk** so the tablet is the laptop's third screen.
2. Every minute: if 127.0.0.1:8080 isn't listening, reopen Lumia Wall to restart the streamer, then return to whatever
   was on screen.

It checks sockets in **LISTEN** state (`st == 0A`) only. Leftover TIME_WAIT sockets on the port fooled an earlier version.
