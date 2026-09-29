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

## Music: laptop now playing + live visualiser (WallBridge)

ridge/WallBridge is a small, windowless .NET 9 app on the laptop (~50 MB RAM, near-zero CPU when idle). It starts from a
shell:startup shortcut.
- **Now playing** from Windows' own media sessions (SMTC, what the volume overlay shows): Spotify, browsers and most
  players. Spotify is preferred when several are open. Position is extrapolated between SMTC updates. Hashed
  AUMIDs are shown as "browser".
- **Transport:** play/pause, next, previous.
- **Spectrum:** WASAPI **loopback** (what the laptop plays, not the mic), 2048-pt FFT, 32 log bands (40 Hz–16 kHz),
  ~30 frames/s, slow AGC, 1 byte per band. Captures **only while the tablet is streaming it**.
- Serves loopback-only 127.0.0.1:8770 over a raw socket (HttpListener rejects Host: 127.0.0.1 without a urlacl):
  /state, /art, /cmd/{playpause|next|prev}, /spectrum (endless 32-byte frames).
- The tablet reaches it through **db reverse tcp:8770 tcp:8770**, which scripts/windows/ipwebcam-tunnel.ps1 keeps
  alive next to the camera forward.
- Build: dotnet publish bridge\WallBridge -c Release -o bridge\out (a local ridge/nuget.config adds nuget.org).

On the tablet, MusicBridge.java polls /state every second, fetches the cover on track change, and streams
/spectrum only while something plays **and** Lumia Wall is on screen. VisView draws the bars with instant attack,
slow release and falling peak caps. The **music** section is the WP8 now-playing layout (cover, title, artist, progress,
round transport buttons, visualiser), and the grace wall music tile shows the live cover and track.
