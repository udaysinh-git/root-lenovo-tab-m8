# 7. Use the tablet as an extra webcam (Camo over USB)

Tested with **Camo Studio 2.9** (Microsoft Store) on Windows 11 and **Camo Camera 2.1.18.16320** on LineageOS 18.1.

## Cameras

| ID | Facing | Sensor |
|---|---|---|
| 0 | Back | 3264×2448 (8 MP) |
| 1 | Front | 1600×1200 (2 MP) |

Both work through the GSI's camera HAL. Camo opens camera 0 (back) by default; switch cameras in Camo Studio.

## Install

There's no Play Store on the vanilla GSI, so sideload the APK (APKMirror, package `com.reincubate.camo`). Check the signer first:
`CN=Aidan Fitzpatrick, OU=Camo, O=Reincubate, L=London`, SHA-256
`a870e64754c2cf29e854484efd1a2c5ff8897a57063fcce56c947e3a62f66dfe`.

```sh
adb install camo-2.1.18.16320.apk
adb shell pm grant com.reincubate.camo android.permission.CAMERA
adb shell pm grant com.reincubate.camo android.permission.RECORD_AUDIO
```

Open Camo on the tablet and tap **Get started**. Tap **Cancel** on "Camo Camera is out of date" (it's comparing against the
Play Store). With USB debugging on and the cable in, Camo Studio picks the tablet up over USB (it lists the device as
*Phh-Treble vanilla*). The tablet then dims its screen ("Camo Camera is still active").

Result: a Windows camera named **Camo**, 1280×720 @ 30 fps (free tier). *"Unsupported settings: Filter"* in Camo Studio only
means a Pro effect isn't available on this device.

## Check the feed without looking at it

The Camo virtual camera outputs flat black (luma 16) when no device is streaming. Measure the average luma:

```sh
ffmpeg -f dshow -i video="Camo" -frames:v 60 -vf "signalstats,metadata=print:key=lavfi.signalstats.YAVG" -f null -
```

A steady 16.0 means no stream; anything that moves above it is a real picture.

## adb version clash with Camo Studio

Camo Studio bundles its own **adb 1.0.40**, and a current platform-tools adb is 1.0.41. When a client and server of different
versions meet, the client **kills the server** ("adb server version (40) doesn't match this client (41); killing..."). Camo
then loses the tablet and the tablet app sits there doing nothing.

- Run a **matching adb for your own commands**: platform-tools **r28.0.2** has adb 1.0.40 (r28.0.3 is already 1.0.41), from
  `https://dl.google.com/android/repository/platform-tools_r28.0.2-windows.zip`. It shares Camo's server safely.
- A second adb server on another port (`adb -P 5038`) **doesn't** help. Every adb server grabs all USB adb interfaces.
- If Camo's adb was killed, restart Camo Studio; it starts its adb again on launch.
- Camo Studio's own `adb.exe` lives under `C:\Program Files\WindowsApps\...` and can't be run directly (Access denied).

## Notes

- **Camera or display, not both.** On Android 11 only one app is in the foreground, so while Camo streams, the spacedesk
  display is off, and the other way round.
- The Camo virtual camera enumerates before the laptop's built-in webcam. When the tablet isn't streaming, apps that pick the
  default camera get black. Disable the Camo device (Device Manager, or `Disable-PnpDevice` on `ROOT\CAMERA\0000`) when you
  want the built-in webcam.
