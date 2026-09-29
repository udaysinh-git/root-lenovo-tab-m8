# 6. Troubleshooting and dead ends

## Tablet keeps connecting/disconnecting on USB after an mtkclient attempt
A stray **elevated** mtkclient/Python process is grabbing the boot ROM on every power-up and resetting it. A non-elevated shell
can't read its command line. Find it with `Get-Process python` (elevated processes show an empty Path) and kill it by PID from
an elevated shell. See [step 1](01-firmware-and-backup.md#why-not-mtkclient).

## Tablet boots into Safe Mode
Holding Vol- while Android starts triggers safe mode (e.g. after a failed BROM entry). Reboot normally.

## `adb devices` hangs, or shows nothing while Windows sees the tablet
spacedesk's USB mode (or an elevated tool) is holding the USB interface. Make adb independent of USB by enabling adb over
Wi-Fi permanently (root):

```sh
adb shell su -c "setprop persist.adb.tcp.port 5555; setprop ctl.restart adbd"
adb connect <tablet-ip>:5555
```

If the adb server itself is stuck: `taskkill /f /im adb.exe`, then `adb start-server`.

## The spacedesk display drops after a while
- Check the **screensaver** is off (see [step 3](03-lineageos-gsi.md#known-issues-community--ours)).
- Record what happens with [`scripts/android/diag-recorder.sh`](../scripts/android/diag-recorder.sh) (root). It runs `logcat -b all`
  into rotating files, plus a 5-second state line (USB state, top activity, spacedesk PID, battery, free RAM). Then grep:
  `grep -hE "exited USB accessory mode|entering USB accessory mode" /data/local/tmp/diag/all.log*`
  Session lengths that are identical every time point to a host-side cause (for us: the Samsung driver).

## Things that didn't work
- **mtkclient on Windows 11**, both the UsbDk and serial paths. Use Linux.
- **spacedesk over an `adb reverse` tunnel.** Only TCP goes through adb; spacedesk validates over UDP, and its network stream
  isn't a single TCP connection.
- **`wm fixed-to-user-rotation`** to force the viewer's orientation. It only exists on Android 12+.
- **`fastboot --disable-verity --disable-verification flash vbmeta`** on fastboot 36.x (see [step 2](02-unlock-and-root.md)).

## Undo everything
- Flash the stock ROM with the MTK Flash Tool (scatter file in the Software Fix package), or use Software Fix → Rescue.
- To relock (after returning to stock boot and vbmeta): `fastboot flashing lock`. This wipes the tablet again.
