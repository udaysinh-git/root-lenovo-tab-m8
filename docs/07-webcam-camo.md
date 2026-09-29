# 7. Use the tablet as an extra webcam, at the same time as the display

**Goal:** the tablet's camera shows up in Windows as a normal camera, **while** the tablet keeps working as a spacedesk
display. Everything runs over the USB cable, with minimal laptop load.

**Result:** a Windows 11 virtual camera, **"Tablet Camera"**, 1280×720 @ 30 fps. Every app sees it (Windows Camera,
browsers, Discord, Teams...). It runs **only while an app is using the camera**.

```
tablet: Lumia Wall's CameraStreamService (camera foreground service, front camera) ── MJPEG on 127.0.0.1:8080
   │ USB cable: adb forward  tcp:8765 → tcp:8080
laptop: "Tablet Camera" = Windows 11 virtual camera (MFCreateVirtualCamera)
        whose media source pulls http://127.0.0.1:8765/video and decodes it with WIC
```

> **Update (2026-09-30):** IP Webcam has been **replaced** by the streamer built into Lumia Wall
> ([lumia/.../CameraStreamService.java](../lumia/src/io/uday/lumiawall/CameraStreamService.java), see
> [chapter 8](08-lumia-wall.md)). IP Webcam's Play build **must** show an "IP Webcam is monitoring the area" overlay
> to run in the background ("distribution policies require a visible camera overlay"). Revoking the overlay permission
> makes it quit as soon as it leaves the screen. The built-in streamer:
> - binds **127.0.0.1 only**, so it's reachable just through `adb forward`, with no firewall rules needed;
> - runs the camera only while a client is connected, closing it 5 s after the last one;
> - serves the same `/video`, `/shot.jpg`, `/status.json` endpoints;
> - leaves the 180° mounting correction to the Windows side (`HKLM\SOFTWARE\TabletCamera\Rotation = 180`, applied
>   to the decoded pixels; WIC's FlipRotator fails on sequential JPEG decodes).
>
> The IP Webcam notes below are kept for reference.
## Cameras on the tablet

| ID | Facing | Sensor |
|---|---|---|
| 0 | Back | 3264×2448 (8 MP) |
| 1 | Front | 1600×1200 (2 MP) |

## Why not just Camo / DroidCam?

- **Camo** (720p, free, works): its Android app only streams **while it's on screen** (CameraX bound to the activity, no
  camera foreground service). On Android 11 only one app is in front, so it's camera **or** spacedesk display, never both.
  Camo Studio also bundles **adb 1.0.40**. A different adb version kills its server and Camo drops (see below).
- **DroidCam free** is 480p. HD needs Pro, which is bought through Google Play billing, so it's not usable on a vanilla GSI.
- **scrcpy camera mirroring** needs Android 12+.
- **Native USB webcam (UVC gadget):** this kernel (4.9.190) has no `CONFIG_USB_CONFIGFS_F_UVC` and no media subsystem.
  It does allow unsigned modules, so it's a possible future project.
- **IP Camera Adapter** (IP Webcam's old DirectShow filter, 4.9): its 64-bit filter **crashes every consumer**
  (access violation) on Windows 11 23H2. Don't use it.

## 1. Tablet: IP Webcam

1. Sideload **IP Webcam** (`com.pas.webcam`, publisher *Thyoni Tech* = Pavel Khlebovich) from APKMirror. It's an `.apkm`
   bundle: unzip it and install `base.apk` + your ABI/language/density splits with `adb install-multiple`. Signer:
   `CN=Pas XL`, SHA-256 `29c6216db158f51e36593b2394a23bfdf173d951f41d22bd9b61e04b3724636c`. It declares a
   `camera|microphone` foreground service, which is **what lets it stream behind other apps**.
2. Permissions and background:
   ```sh
   adb shell pm grant com.pas.webcam android.permission.CAMERA
   adb shell pm grant com.pas.webcam android.permission.RECORD_AUDIO
   adb shell appops set com.pas.webcam SYSTEM_ALERT_WINDOW allow
   adb shell dumpsys deviceidle whitelist +com.pas.webcam
   ```
3. Open it and tap **Start server** at the bottom. It keeps running when you switch to spacedesk.
   **Auto-start:** [`scripts/android/ipwebcam-autostart.sh`](../scripts/android/ipwebcam-autostart.sh) in `/data/adb/service.d/`
   starts the server at boot via `am start -n com.pas.webcam/.Rolling -a android.intent.action.RUN`, re-applies the settings
   below, brings spacedesk back to the front, and restarts the server within a minute if it dies. It checks for a socket in
   LISTEN state, because leftover TIME_WAIT sockets on :8080 fooled a naive port check.
4. **Lock it to the cable.** The server has no password by default, so anyone on your Wi-Fi could watch. Install
   [`scripts/android/ipwebcam-lockdown.sh`](../scripts/android/ipwebcam-lockdown.sh) into `/data/adb/service.d/`. It drops
   port 8080 on `wlan0`/`rndis0` for both IPv4 **and IPv6** (the server also listens on IPv6).
5. Settings via its HTTP API (through the tunnel). **They are runtime-only and reset when the app restarts**, which is why the
   autostart script re-applies them:
   ```sh
   curl "http://127.0.0.1:8765/settings/ffc?set=on"                 # front camera
   curl "http://127.0.0.1:8765/settings/video_size?set=1280x720"
   curl "http://127.0.0.1:8765/settings/quality?set=60"
   curl "http://127.0.0.1:8765/settings/orientation?set=upsidedown" # tablet mounted flipped
   curl "http://127.0.0.1:8765/status.json?show_avail=1"             # current values + options
   ```

## 2. Laptop: the USB tunnel

`adb -s <serial> forward tcp:8765 tcp:8080`. Forwards die whenever the adb server restarts, so
[`scripts/windows/ipwebcam-tunnel.ps1`](../scripts/windows/ipwebcam-tunnel.ps1) re-creates it every 5 s. Start it hidden at
sign-in with a `shell:startup` shortcut to `ipwebcam-tunnel.vbs`, and set the user env var `TABLET_SERIAL`.

**Use an adb that matches Camo Studio's version (1.0.40, platform-tools r28.0.2)** if Camo Studio is installed. A version
mismatch ("adb server version (40) doesn't match this client (41); killing...") kills Camo's server. Also, every adb server
grabs all USB devices, so running a second server on another port (`-P`) doesn't help.

## 3. Laptop: the "Tablet Camera" virtual camera

Source: [`vcam/`](../vcam). It's Microsoft's MIT-licensed
[Windows-Camera VirtualCamera sample](https://github.com/microsoft/Windows-Camera/tree/master/Samples/VirtualCamera)
(SimpleMediaSource) with these changes:
- its own CLSID `{CEBBFFF7-1284-4D72-81FF-8C216C8984B6}`, name *Tablet Camera*, 1280×720;
- `TabletFrameSource.cpp`: a WinHTTP reader for the MJPEG stream (frames found by JPEG SOI/EOI markers), WIC decode, scaled
  to 32bpp BGRA, latest frame kept. It connects on first frame request and disconnects after 5 s with no requests;
- `SimpleFrameGenerator::_CreateRGB32Frame` copies that frame. With no signal it shows the sample's dimmed gradient.

Build (VS 2022 + Windows SDK 10.0.26100):
```powershell
nuget restore vcam\TabletCamSource\packages.config -PackagesDirectory vcam\packages -Source https://api.nuget.org/v3/index.json
msbuild vcam\TabletCamSource\VirtualCameraMediaSource.vcxproj /p:Configuration=Release /p:Platform=x64 /p:SolutionDir=<repo>\vcam\
cl /EHsc /O2 vcam\TabletCamRegister\TabletCamRegister.cpp /Fe:vcam\TabletCamRegister\TabletCamRegister.exe   # in a VS x64 dev prompt
```
Install (elevated): [`vcam/install-tablet-camera.ps1`](../vcam/install-tablet-camera.ps1). It copies the DLL to
`C:\Program Files\TabletCamera\`, registers the in-proc COM server (`ThreadingModel=Both`) and calls
`MFCreateVirtualCamera(SoftwareCameraSource, System, AllUsers, ...)->Start()`. The camera then **persists across reboots**.
Remove it with `-Uninstall`.

Checks:
- `ffmpeg -list_devices true -f dshow -i dummy` shows `"Tablet Camera (Windows Virtual Camera)"`.
- Comparing a frame from the virtual camera with `/shot.jpg` from the tablet gave a correlation of **1.00**. The first
  frame or two are the placeholder while the stream connects.
- It's currently single-client ("device already in use" for a second app).

## Gotchas

- **Other virtual cameras hijack the default.** Camo enumerates first, so apps pick it (it's black when no phone is
  streaming). Disable it when not needed (`Disable-PnpDevice` on `ROOT\CAMERA\0000`) and pick *Tablet Camera* explicitly.
- **Chromium/Electron apps** (Legcord, for one) can show the wrong preview right after cameras are added or disabled.
  Restart the app. The Windows Camera app is a good neutral test.
- **Measure, don't eyeball.** ffmpeg + `signalstats` (average luma; flat 16 = black), or compare a frame against the
  tablet's `/shot.jpg`, tells you whether a feed is live without viewing it.
