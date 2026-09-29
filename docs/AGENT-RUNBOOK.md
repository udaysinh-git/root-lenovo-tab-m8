# Agent runbook: root a Lenovo Tab M8 HD from start to finish

This is for an **AI agent helping a person root their own Tab M8**. It is a decision tree and a checklist, not
a tutorial: pick the user's goal, run only the phases it needs, and stop. Every command and detail lives in the
numbered chapters (`docs/01`–`docs/10`); this file tells you which ones to run, in what order, and what to skip.

If you are working *on this repo's code* instead, read `AGENTS.md`, not this file.

## 0. Before you touch anything

Confirm all of these with the user first. Do not start a phase until they are true.

- **Device.** `adb shell getprop ro.product.vendor.model` must contain **`TB-8505`** (X = LTE, F = Wi-Fi). If it's
  anything else, stop: none of this applies.
- **Right person, right device.** Only ever run adb/fastboot against this tablet. If other Android devices are
  attached, pin the serial with `adb -s <serial>`. Never modify a device the user didn't name.
- **They accept the risk.** Rooting **wipes the tablet**, can **void the warranty**, and can **brick it**. IMEI/NVRAM
  damage is unrecoverable. Get an explicit "yes, go ahead" before any destructive step (see the disclaimer in `README.md`).
- **Backups off the tablet.** Anything they want to keep (photos, files) is already copied to a computer, because
  Phase 1 and the unlock both erase user data.

### Hard rules (never break, whatever the goal)

1. **Never flash TWRP** on this model. fastboot only. People have bricked theirs with TWRP.
2. **Never cross firmware.** TB-8505X firmware on an F (or the reverse) can brick it.
3. **Prebuilt images in the GitHub Release are for ONE exact build** (`TB-8505X_S301129`). Only use them if
   `adb shell getprop ro.build.display.id` matches exactly. Otherwise patch the user's own images (Phase 2).
4. **The vbmeta byte-flip is real:** fastboot 36.x cannot disable verification on this image, so you edit
   **the big-endian u32 at offset 120, i.e. one byte at offset 123 → `0x03`**. Don't "fix" this by adding
   `--disable-*` flags; they fail.
5. **The user's IMEI, serial and partition dumps are private.** They live in `private/` (git-ignored). Never paste a
   serial/IMEI into a file, a commit, a log you share, or a message. Back them up locally only.
6. **Confirm before every wipe** (`fastboot flashing unlock`, `fastboot -w`). Say what it erases, then wait for a yes.

## 1. Pick the goal, then run only these phases

Ask the user what they actually want. Map it:

| The user wants… | Run phases | Skip |
|---|---|---|
| **Just root** (keep stock Android 10) | 1, 2 | 3, 4, 5+ |
| **Root + the faster ROM** (most common ask) | 1, 2, 3 | 4, 5+ |
| **A tablet that stays plugged in** (kiosk, clock, always-on) | 1, 2, 3, 4 | 5+ |
| **The full wall setup** (third monitor, webcam, Lumia Wall dashboard) | 1, 2, 3, 4, then 5 | — |
| **Un-root / back to stock** | see `docs/06` | — |

"Root and a faster ROM, no wall display" → **Phases 1, 2, 3, and stop.** Don't build the Android app, don't install
WallBridge, don't touch the Windows side. Those are Phase 5 only.

## Phase 1 — Firmware + backup (`docs/01`)
Goal: have the exact stock images on the computer and the device-unique partitions backed up.
1. `adb shell getprop ro.build.display.id` and `sys.oem_unlock_allowed` (must be `1`). Record the build id.
2. Download that exact build with Lenovo **Software Fix** (free, official). **Copy the ROM folder out before closing
   the tool** — it deletes the zip after extracting.
3. Sanity-check the images: `boot.img` starts with `ANDROID!`, `vbmeta.img` with `AVB0`.
4. The `nvram`/`nvdata`/`proinfo`/`persist` backup happens right after root in Phase 2 (needs su). Don't forget it.
**Verify:** stock `boot.img` and `vbmeta.img` exist locally and have the right magic.

## Phase 2 — Unlock + root (`docs/02`)  ⚠️ wipes the tablet
1. Patch `boot.img` with Magisk from an adb shell (pull `magiskboot`/`magiskinit`/`magisk` out of the APK; run
   `get_flags` then `boot_patch.sh`). Result: `boot_magisk.img`.
2. Make `vbmeta_disabled.img`: set the byte at **offset 123 to `0x03`** (see Hard rule 4).
3. `adb reboot bootloader` → **confirm with the user** → `fastboot flashing unlock` (Vol+ on the tablet; **wipes data**).
4. `fastboot flash boot boot_magisk.img`, `fastboot flash vbmeta vbmeta_disabled.img` (no `--disable-*` flags),
   `fastboot reboot`.
5. Install the Magisk APK, open it once, enable **Superuser → Shell**.
6. **Now do the Phase 1 partition backup** (`scripts/android/backup-partitions.sh`) → pull to `private/` → delete from
   the tablet. Keep it; never share it.
**Verify:** `adb shell su -c id` prints `uid=0`. If the goal was "just root", you're done.

## Phase 3 — LineageOS 18.1 GSI (`docs/03`)  ⚠️ wipes userdata again
1. Get **AndyYan's LineageOS 18.1 `arm64_bvS`** (vanilla, no GApps). Android 15 GSIs do **not** boot. Verify the SHA1.
2. `adb reboot bootloader` → `fastboot flash system lineage-...-arm64_bvS.img` → **`fastboot -w`** (required; skipping
   it is the #1 bootloop cause) → `fastboot reboot`. First boot is ~100 s.
3. Re-root: flash `boot_magisk.img` again, reboot, install the Magisk APK, enable Shell.
**Verify:** it boots to LineageOS and `adb shell su -c id` is `uid=0` again. For "root + faster ROM", stop here.

## Phase 4 — Charge limiter (`docs/04`)
Only if the tablet will stay plugged in. Installs `scripts/android/charge-limit.sh` as a Magisk `service.d` script that
holds the battery at 50–60% via `/proc/mtk_battery_cmd/current_cmd`.
**Verify:** `cat /data/local/tmp/charge-limit.log` shows it acting on the battery level.

## Phase 5 — The wall setup (only if they asked for it)
This is a lot more, and needs a **Windows 11 laptop**. Do it only for the full-setup goal, in this order:
- `docs/05` third monitor (spacedesk over USB; watch for the Samsung-driver disconnect and the MTP-mode requirement),
- `docs/07` webcam (the Windows 11 virtual camera in `vcam/`),
- `docs/08` Lumia Wall home screen + `bridge/WallBridge` (music), `docs/09` Keep/Calendar, `docs/10` laptop vitals.
For build/deploy commands and the laptop-load rules, follow `AGENTS.md` §6. Don't start any of this for a root-only user.

## If something goes wrong
`docs/06` is the troubleshooting chapter (bootloops, the disconnect bug, going back to stock). The safe fallback from a
bad flash is always: reflash the **stock** `boot.img`, then `fastboot -w`. Never reach for TWRP.
