# 5. Use it as a Windows extra monitor (spacedesk over USB)

**Setup:** spacedesk driver **2.2.33** on Windows 11, spacedesk viewer **2.1.38** on the tablet (LineageOS 18.1),
USB cable. Wi-Fi mode also works (see the end of this page).

## Tablet kiosk settings

```sh
adb shell settings put global stay_on_while_plugged_in 7     # never sleep on power
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1                # 1 = landscape, 3 = landscape flipped (cable on the other side)
adb shell locksettings set-disabled true                     # no lock screen
adb shell cmd uimode night yes
adb shell settings put secure screensaver_enabled 0          # the Daydream clock otherwise covers spacedesk while charging
adb shell settings put secure screensaver_activate_on_dock 0
```

## Install the viewer

It's only on app stores. APKMirror carries it as a split bundle (`.apkm`, which is a zip). Unzip it and install the base
plus the splits you need:

```sh
adb install-multiple base.apk split_config.en.apk split_config.hdpi.apk
```

Check the signer first. It's `CN=Mark Neil Templonuevo, OU=datronicsoft Inc`, SHA-256
`69572e3752b733bfa907795b3ed4bf71394a759a34fa99f9936664f3efaa8c48`.

## Getting USB mode to connect and stay connected

spacedesk's **"USB Cable Android"** uses Android Open Accessory (AOA). Windows sends a switch command, and the tablet
re-appears as Google's generic accessory device **`18D1:2D01`** (accessory + adb). We hit four separate problems.

### 1. Tablet must be in File Transfer (MTP) mode, not "adb only"

In adb-only mode (`17EF:201C`), Windows binds the *whole* device to the adb WinUSB driver, and spacedesk can't send the
AOA switch. Also, after any disconnect the tablet falls back to its default USB mode. Set both the current mode and the
default to MTP:

```sh
adb shell svc usb setFunctions mtp
adb shell svc usb setScreenUnlockedFunctions mtp   # the default after re-plug (applies because the lock screen is off)
adb shell dumpsys usb | grep screen_unlocked_functions   # → MTP
```

**Don't** `setprop persist.sys.usb.config ...` while a session is live. It resets the USB gadget and kills the session.

### 2. Samsung's USB driver breaks it (the ~2-minute disconnects)

With **"Samsung USB Driver for Mobile Phones"** installed (common if you ever used a Samsung phone), USB sessions died at
almost exactly **2:06** every time. The tablet logs showed a clean host-side cut (`UsbDeviceManager: exited USB accessory
mode`, no crash, no memory kill). The spacedesk console warns about this, and so do several spacedesk forum threads.

Samsung's `ssudbus.inf` claims `USB\VID_18D1&PID_2D01` ("AOA+Dynamic") and fails with **Code 10** on this tablet.

**Fix:**
1. Back up the Samsung packages if you need them later (`pnputil /export-driver oemNN.inf <dir>` for each).
2. Uninstall **Samsung USB Driver for Mobile Phones** from Apps.
3. **Repair spacedesk:** `msiexec /fa spacedesk_driver_Win_10_64_v2.2.33.msi`. **Samsung's uninstaller also deleted
   `spacedeskdriverandroidusb.inf`** from the driver store (8 spacedesk packages became 7). That showed up as a black screen,
   with MI_00 on **Code 28, no driver**.
4. If the display interface still has no driver, run [`scripts/windows/force-spacedesk-aoa.ps1`](../scripts/windows/force-spacedesk-aoa.ps1)
   elevated. It force-binds spacedesk's inf to `18D1:2D01` and its `MI_00` via `UpdateDriverForPlugAndPlayDevices(INSTALLFLAG_FORCE)`.
5. **Reboot.** A freshly bound driver can show **Code 19** until then.

The healthy state looks like this:

```
VID_18D1&PID_2D01        USB Composite Device (spacedesk)   OK
VID_18D1&PID_2D01&MI_00  spacedesk Android USB Device       OK
VID_18D1&PID_2D01&MI_01  Android Accessory Interface        OK   (adb keeps working)
```

After this, the USB session **held past 5 minutes** (the old limit was 2:06).

### 3. Don't leave other USB filter drivers around

UsbDk (installed for mtkclient) sits as an UpperFilter on every USB device. Uninstall it.

### 4. The viewer app on the tablet

- In the viewer, the **DISCOVERY** bar is only a scanning animation. The round USB button just shows help.
- When the accessory attaches, the viewer switches to its USB display screen on its own. If it says *Cannot detect any
  Primary Machine*, replug the cable.

## Windows side

- **Flip / resolution:** on Android 11 the viewer forces its own orientation, so rotate on the Windows side instead.
  [`scripts/windows/set-tablet-display.ps1`](../scripts/windows/set-tablet-display.ps1) finds the spacedesk display and sets
  1280×800 and **landscape (flipped)** (`-Orientation 0` for normal). The setting persists.
- **Wallpaper bars:** the tablet is 16:10. A 16:9 wallpaper set to *Fit* leaves bars at the top and bottom. Use *Fill*
  (`WallpaperStyle=10`); 16:9 monitors look identical.
- **"Non-Commercial Viewer connected" popup** on every connect: [`close-spacedesk-nag.ps1`](../scripts/windows/close-spacedesk-nag.ps1)
  closes it automatically. Start it with `close-spacedesk-nag.vbs` (no console window) from a shortcut in `shell:startup`.
  An "At log on" scheduled task didn't fire reliably after reboots.

## Wi-Fi mode and third-party firewalls

Wi-Fi mode works too. The picture traffic stays on your LAN and uses no internet bandwidth; we measured about 3 Mbit/s for
mostly static content. If the viewer hangs on *"IP-Validation in progress..."* or never discovers the PC:

- The viewer's discovery/validation is **UDP to port 28252**. The stream is also not plain TCP, so an `adb reverse` tunnel
  **can't** carry spacedesk.
- **Portmaster** (and similar WFP-based firewalls) drop these packets *below* Windows Firewall, so nothing appears in the
  Windows logs. `pktmon` (see [`pktmon-tablet.ps1`](../scripts/windows/pktmon-tablet.ps1)) showed the packets arriving at the
  network card, while a WFP drop audit showed nothing. In Portmaster: *Spacedesk Service → Settings*:
  - *Connection Types →* **Force Block Incoming Connections: off** (app-only). Rules don't override a force-block.
  - *Rules → Incoming Rules*: `+ <tablet IP>` then `- *`.
- If your PC is on both Ethernet and Wi-Fi in the same subnet, the viewer lists both addresses. Pick the Ethernet one.
