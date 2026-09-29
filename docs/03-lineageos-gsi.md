# 3. Flash LineageOS 18.1 (GSI) and keep root

Stock Android 10 is heavy for 2 GB of RAM. A vanilla GSI (no Google apps) frees a lot of memory:
we measured **about 945 MB available at idle** on LineageOS 18.1.

## Which image

- **Architecture/variant:** arm64 **"b"** (`bvN`/`bvS`/`bgN`/`bgS`). `ro.build.system_root_image` reads `false`, but `/` is
  mounted from the system partition, so it takes the system-as-root ("b") images. The phh wiki agrees for the TB-8505.
- **Android version:** Android 11 is the sweet spot. Community reports show A12+ running noticeably slower and warmer on this
  SoC. **Android 15 GSIs don't boot** (they stop at the "will boot in 5 seconds" stage).
- **Avoid** AndyYan's LOS 20/21 "Light" (`gsi_arm64_vN/gN`) builds, which mostly bootloop.
- **Picked:** `lineage-18.1-20240121-UNOFFICIAL-arm64_bvS.img.xz` by AndyYan (vanilla + built-in su), 584 MB xz →
  1.72 GiB sparse image.
  - SHA1 `f7ad2f8938818f4866dfbd2f9b718a99a55266a7`
  - MD5 `cd282dff6667745fcd7daf0b13a9edc3`
- **Space:** the system partition is **3.8 GiB** (scatter: `0xF3800000`), so all common GSIs fit.

Other GSIs reported booting on the TB-8505: phh AOSP 11, LOS 19.1/20/21 (non-Light), TrebleDroid A13/A14, crDroid 9.10 slim,
ColtOS A13 slim, Evolution X A14 slim. Sources are the XDA "Lenovo Tab M8" and "TB-8505X" threads.

## Downloading from SourceForge (if it's slow for you)

SourceForge redirects browsers to a region mirror that can crawl. Plain `curl` gets bot-check HTML pages, and resuming
across mirrors can **splice an HTML page into the file** (it ended up corrupt for us). What worked was aria2 with many
mirrors, a wget user agent, and a checksum:

```sh
F=lineage-18.1-20240121-UNOFFICIAL-arm64_bvS.img.xz
P="project/andyyan-gsi/lineage-18.x/$F?viasf=1"
aria2c -U "Wget/1.21.4" --checksum=sha-1=f7ad2f8938818f4866dfbd2f9b718a99a55266a7 \
  -c -x4 -s24 -k1M --uri-selector=adaptive --lowest-speed-limit=8K --max-tries=0 -o "$F" \
  https://{netix,altushost-swe,yer,deac-fra,deac-riga,deac-ams,tenet,zenlayer}.dl.sourceforge.net/$P
# re-run until it exits 0 (the checksum passes)
xz -dk "$F"
```

## Flash

Bootloader fastboot is enough. There's no `super` partition, so fastbootd isn't needed.

```sh
adb reboot bootloader
fastboot flash boot stock/boot.img                       # stock boot for the first boot (several users needed this)
fastboot flash system lineage-18.1-...-arm64_bvS.img     # 14 sparse chunks, ~67 s; the "avb footer" warning is harmless
fastboot -w                                              # REQUIRED: wipes userdata (skipping it is the #1 bootloop cause)
fastboot reboot
```

The first boot took just **98 seconds**. Result: `18.1-20240121-UNOFFICIAL-arm64_bvS`, Android 11, `userdebug`.

## Re-root with Magisk

The **same Magisk-patched stock boot image** from step 2 works on LineageOS:

```sh
adb reboot bootloader
fastboot flash boot boot_magisk.img
fastboot reboot
adb install Magisk-v30.7.apk        # open it once, then Superuser → enable Shell
```

The ROM's built-in su lives at `/system/bin/phh-su` and isn't called `su`, so it doesn't clash with Magisk.

## Known issues (community + ours)

- Plugging in headphones doesn't mute the speaker. Bluetooth audio can be flaky (phh Settings has a "MediaTek Bluetooth fix").
- The rear camera focuses badly, and lift-to-wake can't be turned off.
- No charging LED. Charging while powered off is broken.
- Mobile data: IMEI and baseband are detected; not tested further here.
- **The screensaver (Daydream) is on by default** and starts while charging, which is bad for a wall display. Turn it off:
  `settings put secure screensaver_enabled 0; settings put secure screensaver_activate_on_dock 0`.
