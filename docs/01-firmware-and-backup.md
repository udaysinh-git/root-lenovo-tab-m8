# 1. Get the exact stock firmware and back up the tablet

You want the stock image for **your exact build** before touching anything. Magisk patches the boot
image, and a boot image from a different build means a mismatched kernel (bootloops, broken LTE).

## Check your build

Enable Developer options (tap *Build number* 7 times), turn on **USB debugging** and **OEM unlocking**, then:

```sh
adb shell getprop ro.build.display.id        # e.g. TB-8505X_S301129_230914_BMP
adb shell getprop sys.oem_unlock_allowed     # must be 1
```

## Download it with Lenovo Software Fix (free, official)

Free mirrors (lolinet etc.) only carry older builds; the current one is behind paywalls elsewhere.
Lenovo's own tool gets it free:

1. Install **Software Fix** (formerly *Rescue and Smart Assistant / LMSA*). The version used was 7.6.2.10:
   `https://download.lenovo.com/consumer/mobiles/software_fix_v7.6.2.10_setup.exe` (Authenticode-signed by Lenovo).
   The installer shows several driver prompts (Spreadtrum, MediaTek, etc.). They're harmless.
2. Log in with a Lenovo account, open **Rescue**, and plug in the tablet with USB debugging on. It detects the model and build.
3. Click **Download**. **Don't click Rescue/Flash** (you only want the files). The cloud icon at the top shows progress and the
   save path, by default `C:\ProgramData\RSA\Download\RomFiles\`.
4. **Copy the extracted folder out as soon as it's done.** Software Fix extracts the zip and then **deletes the zip**.

The package (`TB-8505X_S301129_230914_BMP_SVC`, 1.8 GB zipped / 3.8 GB extracted) contains `boot.img`, `vbmeta.img`,
`system.img`, `vendor.img`, `MT6761_Android_scatter.txt`, `MTK_AllInOne_DA_6761.bin` and the rest. It's a full factory
restore image, flashable with the MTK Flash Tool that Software Fix also downloads (`MTK_Flash_tool_v5.1920.00.001_V2.zip`).

Verify the files you'll use:

```sh
# boot.img must start with "ANDROID!", vbmeta.img with "AVB0"
```

## Back up the device-unique partitions (after root)

`nvram`, `nvdata`, `nvcfg`, `proinfo`, `persist`, `protect1/2`, `seccfg` and friends hold **your IMEI, serial and radio
calibration**. No firmware download can restore them. Once rooted (step 2), dump every small partition:

```sh
adb push scripts/android/backup-partitions.sh /data/local/tmp/bp.sh
adb shell su -c "sh /data/local/tmp/bp.sh list"                     # shows name, mmcblk0pN, size
adb shell su -c "sh /data/local/tmp/bp.sh dump /data/local/tmp/pbk"  # ~627 MB, writes SHA256SUMS
adb pull /data/local/tmp/pbk ./private/partitions
adb shell su -c "rm -rf /data/local/tmp/pbk"
```

The script skips the big partitions (`system`, `vendor`, `product`, `cache`, `lenovocust`) that are in the stock ROM anyway,
plus `userdata`. It also saves `mmcblk0boot0/1` (the preloader). Check the checksums on your PC afterwards.

**Keep these dumps private.** Unlocking and patching boot don't touch these partitions, so it's safe to take this backup right
after rooting.

## Why not mtkclient?

The Helio A22 has the usual MediaTek boot-ROM exploit, and mtkclient can dump everything **without unlocking**. BROM entry:
tablet off, hold **Vol+ and Vol-**, plug in USB. It shows up as `0E8D:0003`. Target config: SBC on, **SLA off, DAA on**, so
the Kamakiri exploit is needed.

On **Windows 11** it didn't work for us:
- A serial handshake (`--serialport COMx`) works, but Kamakiri needs raw USB control transfers.
- The UsbDk backend only opens from an **elevated** process (`UsbDkController -n` says "Enumeration failed" otherwise). Even
  elevated, the handshake failed every time.
- The pip entry point `mtk.exe` was broken (`No module named mtkclient.mtk`), so use `python mtk.py`. The bundled
  `libusb-1.0.dll` isn't found by bare name from a venv on Python 3.8+, so patch `usblib.py` to load it by absolute path.
- mtkclient exits with "Please disconnect, start mtkclient and reconnect" if the device is already attached when it starts.
- **A leftover elevated mtkclient process will keep grabbing BROM and resetting the tablet on every power-up**, which looks
  like an endless USB connect/disconnect loop. A non-elevated shell can't see an elevated process's command line, so find it by
  name (`Get-Process python`, empty Path) and kill it by PID from an elevated shell.
- UsbDk installs itself as an **UpperFilter on every USB device**. Uninstall it when you're done; it breaks other USB tools.

**Use Linux if you want mtkclient.** Otherwise Software Fix plus a root backup gets you the same result.
