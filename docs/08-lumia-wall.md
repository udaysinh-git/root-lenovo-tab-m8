# 8. Lumia Wall: a Windows Phone 8 home screen for the wall tablet

`lumia/` is a tiny (~110 KB), gradle-less Android app (framework APIs only; `aapt2 + javac + d8`, see `lumia/build.ps1`)
that replaces the launcher with a **Windows Phone 8 panorama**:

- **da wall** panorama title that drifts at 45% of the scroll speed (parallax), with sections:
  - **grace wall**: live tiles: clock (flips to day and week), Pune weather (Open-Meteo, flips to high/low and humidity),
    display (spacedesk), music (live cover and track), reader (KOReader), manga (Mihon), camera, Grace, battery;
  - **music**: the laptop's now playing with transport and a live visualiser (see below);
  - **lyrics**: time-synced lyrics for the current song, **only present when the song has lyrics** (see below);
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

`bridge/WallBridge` is a small, windowless .NET 9 app on the laptop (~50 MB RAM, near-zero CPU when idle). It starts from a
`shell:startup` shortcut.
- **Now playing** from Windows' own media sessions (SMTC, what the volume overlay shows): Spotify, browsers and most
  players. Spotify is preferred when several are open. Position is extrapolated between SMTC updates. Hashed
  AUMIDs are shown as "browser".
- **Transport:** play/pause, next, previous, and seek (`/cmd/seek?ms=N`, SMTC `TryChangePlaybackPositionAsync`;
  some websites refuse it and the tablet says so).
- **Pause detection for browsers:** many sites never tell the browser they paused, so SMTC keeps saying "Playing" with
  a frozen position. For browser sessions the bridge trusts Windows' per-app audio meters instead (`AudioActivity.cs`,
  sampled every 150 ms, "silent for 2.5 s" = paused). Every SMTC refresh has a 3 s timeout, because a hung WinRT call
  used to freeze updates silently; `/state` reports `age_ms` and `error`, and `/debug` lists every raw session.
- **Position:** Spotify's own timestamp anchors the position (accurate enough for lyrics); browsers use the bridge's
  clock. Spotify 1.3 sometimes publishes an **empty timeline (0/0) for a whole track** after auto-advancing, and
  neither seeks nor pause/play revive it. The bridge then counts from the track start itself (`estimated: true`,
  frozen on pause), and one tap on a lyric line re-syncs it exactly.
- **Lyrics:** free and keyless from [LRCLIB](https://lrclib.net). The bridge first tries an exact `/api/get`
  (title, artist, album, duration), then `/api/search`, then a search with Spotify-style suffixes stripped
  ("- Remastered 2009", "(feat. X)"). It prefers synced LRC, caches per track, and retries failed lookups after 30 s.
  `/lyrics` returns `source` = loading | synced | plain | none | error plus the timed lines.
- **Spectrum:** WASAPI **loopback** (what the laptop plays, not the mic), 2048-pt FFT, 32 log bands (40 Hz–16 kHz),
  ~30 frames/s, slow AGC, 1 byte per band. Captures **only while the tablet is streaming it**.
- Serves loopback-only `127.0.0.1:8770` over a raw socket (`HttpListener` rejects `Host: 127.0.0.1` without a urlacl):
  `/state`, `/art`, `/cmd/{playpause|next|prev}`, `/spectrum` (endless 32-byte frames).
- The tablet reaches it through **`adb reverse tcp:8770 tcp:8770`**, which `scripts/windows/ipwebcam-tunnel.ps1` keeps
  alive next to the camera forward.
- Build: `dotnet publish bridge\WallBridge -c Release -o bridge\out` (a local `bridge/nuget.config` adds nuget.org).

On the tablet, `MusicBridge.java` polls `/state` every second, fetches the cover and lyrics on track change, and
streams `/spectrum` only while something plays **and** the music section is actually in view (re-checked on every
panorama scroll, so the bars start as soon as you swipe to it). `VisView` draws the bars with instant attack,
slow release and falling peak caps. The **music** section is the WP8 now-playing layout (cover, title, artist, progress,
round transport buttons, visualiser), and the grace wall music tile shows the live cover and track.

## Lyrics section

`LyricsView.java` draws the lines itself on a canvas (no child views), so the whole animation is one cheap pass per frame:
- The **current line** eases up to full size and brightness while the others recede. Sung lines are dimmer than
  upcoming ones, and instrumental breaks show as `•  •  •`.
- The list **glides** to keep the current line at ~38% height, using exponential follow so interruptions never jerk.
- A soft **karaoke sweep** brightens the current line left to right over roughly the time it takes to sing it. The bright
  text is painted through a gradient, so the edge is an accent-tinted glow inside the letters.
- **Tap a line** to seek the laptop's player there. **Drag vertically** to browse (the panorama keeps horizontal swipes);
  auto-follow resumes after 4 s.
- Timing runs on the tablet's own clock between the 1 s polls and is nudged (not snapped) toward each poll, so the sweep
  never stutters.
- It animates frame by frame only while something moves **and** it's on screen; otherwise it wakes when the next line
  starts.

The column is **hidden** when the song has no lyrics, nothing plays, or the lookup failed. While a lookup is running
it stays as it was, to avoid a flicker. Hiding or showing a panorama section would shift every section after it, so if one
of those is on screen the scroll moves with it. If the lyrics column itself was on screen, the panorama glides back
to music.

## Action center (quick settings)

**Pull down from the top band** (the big title and the section headers, above the tiles) and a WP8.1-style action
center slides down. A faint grab bar at the top centre hints that it's there. Close it with a swipe up, a tap below it,
or back.

| Tile / control | What it does |
|---|---|
| camera | Stops or starts the built-in camera server. Off also creates `/data/local/tmp/wall/camera.off` so the `wall-boot.sh` watchdog doesn't restart it, and Lumia Wall won't start it on launch. The laptop's Tablet Camera shows its standby picture. |
| wi-fi, bluetooth | `svc wifi` / `svc bluetooth` enable or disable. |
| charge | On = `charge-limit.sh` holds 50–60%. Off creates `/data/local/tmp/wall/charge.full`: the script suspends the limit and charges to 100% (e.g. before taking the tablet off the wall). |
| night | Drops to the lowest brightness; tap again to restore. |
| screen off | Sleeps the display (the power button wakes it). |
| brightness slider | Perceptual (square-law) scale. While dragging, only Lumia Wall's window changes (instant, no root call per frame). On release, `settings put system screen_brightness` is written once, so it also applies to spacedesk. |
| all settings, restart display, restart tablet | Android Settings; force-stop and relaunch spacedesk; reboot (**hold**, a tap only explains). |

Everything that Android 11 reserves for system apps goes through `su` (`Root.java`, off the UI thread). Lumia Wall's
uid is pre-granted in Magisk so there's never a prompt:

```sh
magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(<uid>,2,0,0,0)"
# uid: dumpsys package io.uday.lumiawall | grep userId
```

Both Magisk scripts read the flag directory. `charge-limit.sh` now checks every 20 s. When restarted by hand on a running
tablet (uptime > 10 min), `wall-boot.sh` only resumes its watchdog instead of replaying the boot sequence (home, then
spacedesk).

## System bars: gesture navigation, no status bar

The stock 3-button bar is big on this screen: 72 px, and because the tablet is mounted rotated, Android puts it down the
left side. Two changes give every app the full screen.

**Gesture navigation**: swipe up from the bottom edge for home, swipe up and hold for recents, swipe in from the left or
right edge for back. It switches live:

```sh
cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
```

**Status bar hidden everywhere, notifications still one swipe away**
- Android 11 removed `policy_control` (the old global immersive switch); setting it does nothing.
- `overlays/WallBars` is a resource overlay that sets the framework's `status_bar_height_portrait/_landscape` to 0 dp.
  `overlays/build-wallbars.ps1 -Install` builds it and installs it as the Magisk module `wallbars`
  (`/system/product/overlay/WallBars/WallBars.apk`, systemless).
  - Only overlays in a preinstalled path may change framework resources that aren't declared `<overlayable>`.
  - One reboot mounts it, then `cmd overlay enable io.uday.wallbars`. It isn't static, so
    `cmd overlay disable io.uday.wallbars` brings the status bar back live.
- With a 0 px status bar there's nothing left to pull the shade from, so `EdgeService` (in Lumia Wall) adds an invisible
  12 dp strip along the top edge, above every app. A downward swipe on it runs `cmd statusbar expand-notifications`.
  - It is a **foreground service**: a plain started service was stopped a minute after Lumia Wall left the screen
    ("Stopping service due to app idle"). Its notification sits on a minimum-importance channel.
  - It needs "display over other apps": `appops set io.uday.lumiawall SYSTEM_ALERT_WINDOW allow`.
  - The **Settings app hides all third-party overlays** while it's open (anti-tapjacking), so the strip doesn't work
    inside Settings. Use the action center or leave Settings first.
