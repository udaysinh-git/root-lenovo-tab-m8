# 2. Unlock the bootloader and root with Magisk

Tested with **Magisk v30.7** and platform-tools **fastboot 36.0.1**.

## Patch boot.img (no Magisk app needed)

Magisk's patch script runs fine from an adb shell. Pull the pieces out of the APK (it's a zip):

| From the APK | Rename to |
|---|---|
| `lib/arm64-v8a/libmagiskboot.so` | `magiskboot` |
| `lib/arm64-v8a/libmagiskinit.so` | `magiskinit` |
| `lib/arm64-v8a/libmagisk.so` | `magisk` |
| `lib/arm64-v8a/libinit-ld.so` | `init-ld` |
| `lib/arm64-v8a/libbusybox.so` | `busybox` |
| `assets/stub.apk`, `assets/boot_patch.sh`, `assets/util_functions.sh` | (same) |

```sh
adb shell mkdir -p /data/local/tmp/mp
adb push <those files> stock/boot.img /data/local/tmp/mp/
adb shell "cd /data/local/tmp/mp && chmod 755 * && ./busybox sh -c '. ./util_functions.sh; BOOTMODE=true; get_flags; ./busybox sh ./boot_patch.sh boot.img'"
adb pull /data/local/tmp/mp/new-boot.img boot_magisk.img
```

Expected: `KEEPVERITY=true KEEPFORCEENCRYPT=true`, *Pre-init storage partition: cache*, and a 32 MiB `new-boot.img`.
The `Failed to patch` lines for `dtb`/`kernel_dtb` are harmless (there are no verity flags to strip).

## Make a vbmeta with verification disabled (the fastboot bug)

The usual command **fails** with fastboot 36.x:

```
$ fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img
fastboot: error: Failed to find AVB_MAGIC at offset: 0
```

This happens even though the image is a valid `AVB0` header. Instead, patch the header yourself: the **flags** field is a
big-endian u32 at **offset 120**. Set it to `3` (`HASHTREE_DISABLED | VERIFICATION_DISABLED`), which is **one byte, offset 123
→ `0x03`**:

```powershell
$b = [IO.File]::ReadAllBytes('vbmeta.img'); $b[123] = 3; [IO.File]::WriteAllBytes('vbmeta_disabled.img', $b)
```
```sh
# Linux/macOS
cp vbmeta.img vbmeta_disabled.img && printf '\x03' | dd of=vbmeta_disabled.img bs=1 seek=123 conv=notrunc
```

Then flash it **without** the `--disable-*` flags. Ready-made images for build `S301129_230914` are in this repo's Releases.

## Unlock and flash

```sh
adb reboot bootloader                   # fastboot shows product=akita_row_call
fastboot getvar unlocked                # no
fastboot flashing unlock                # confirm with Vol+ on the tablet. WIPES ALL DATA
fastboot getvar unlocked                # yes
fastboot flash boot boot_magisk.img
fastboot flash vbmeta vbmeta_disabled.img
fastboot reboot
```

- The Windows fastboot driver worked out of the box (device shows as *Android ADB Interface*, `0E8D:201C`). Older threads
  mention "waiting for device" trouble; we didn't hit it.
- The first boot after the wipe took about 3 minutes. adb comes back as `unauthorized`, so accept the RSA prompt on the tablet.
- Result: `ro.boot.verifiedbootstate=orange`, `ro.boot.flash.locked=0`, `magisk -v` = `30.7:MAGISK:R`, SELinux Enforcing.

## Getting `su` in the adb shell

Install the Magisk APK (`adb install Magisk-v30.7.apk`), **open it once**, then run `adb shell su -c id`.

**Gotcha:** if the grant prompt times out (about 10 s), Magisk saves a **deny** for *Shell*, and every later request is
refused silently. Fix: Magisk → **Superuser** tab → toggle **Shell** on.

Now back up your unique partitions (see step 1).
