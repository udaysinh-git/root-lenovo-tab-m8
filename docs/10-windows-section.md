# 10. The "windows" section: laptop vitals and controls

Swipe **right** from grace wall (the home section): the panorama now has a section to the left of home, like a
Steam Deck's performance overlay and quick-access menu for the laptop. Lumia Wall still opens on grace wall, and back
and home return there. A "linux" section for Grace can sit next to it later.

**Vitals (left)**
- **CPU** total with a 60 s sparkline, peak thread, and a bar per logical processor (16 on the i7-12650H).
- **GPU** (RTX 4060 via NVML, which ships with the driver): load, temperature, power draw, VRAM.
- **Memory**: percent and GB used.
- Footer: **battery**, **network** down/up, host name and uptime.

**Controls (right)**
- **Sound output**: every active playback device. Tap one to make it the default for all roles (console, multimedia,
  communications). The current one has the accent bar. Below the list are a **volume** slider and a **mute** button.
- Tiles:
  - **bluetooth** (the radio on/off);
  - **mic** (mutes or unmutes the default communications microphone);
  - **power** (cycles Windows' power mode: efficiency, balanced, performance);
  - **lock**;
  - **sleep** (hold; a tap only explains).
- **Laptop screen** brightness slider.

CPU temperature isn't shown: Windows only exposes it through an admin-level kernel driver, and WallBridge stays
unprivileged.

## How it works (WallBridge)

| Endpoint | What |
|---|---|
| `/sys/stats` | CPU from `NtQuerySystemInformation` (per-processor times), RAM from `GlobalMemoryStatusEx`, GPU from `nvml.dll`, battery from `GetSystemPowerStatus`, network from adapter byte counters |
| `/sys/state` | outputs + volume + mute (Core Audio via NAudio), mic mute, Bluetooth (`Windows.Devices.Radios`), brightness (WMI `WmiMonitorBrightness`), power mode (`PowerGetEffectiveOverlayScheme`) |
| `/sys/cmd/...` | `output?id=`, `volume?v=`, `mute?on=`, `micmute?on=`, `bluetooth?on=`, `brightness?v=`, `power?mode=`, `lock`, `sleep` |

Windows has no public API for changing the default audio device. `IPolicyConfig` is the COM interface the Sound settings
use themselves, and it has been stable from Windows 7 to 11.

On the tablet, `WindowsSection.java` polls `/sys/stats` every second and `/sys/state` every 3 s, **only while the section
is on screen**. Commands run on their own thread. Any state read that started before the latest tap is discarded, so a
stale reading can never undo what you just chose.

## Staying (almost) free on the laptop

Everything on the laptop is demand-driven:
- **Vitals:** sampled only while the tablet asked within the last 10 s.
- **Now playing:** the refresh loop and the browser audio-meter sampling run only while the tablet is polling `/state`,
  i.e. while Lumia Wall is on screen. The meter sampling also runs only when a browser is the active player.
- **WMI:** brightness is cached for 30 s, because WMI work lands in `WmiPrvSE.exe`. The Bluetooth radio object is kept
  and updates itself.
- **Keep and Calendar:** they sync every minute only while the day sheet was looked at in the last 5 minutes, otherwise
  every 15 minutes. Opening the sheet always triggers a fresh sync.
- **.NET runtime:** no concurrent (background) GC, and `System.GC.ConserveMemory=7`.

- **Caching:** the network adapter list is refreshed every 30 s (listing adapters took tens of ms with Tailscale,
  Hyper-V etc.), and only byte counters are read each second. Audio devices are cached and rebuilt only when Windows
  reports a device or default change (`IMMNotificationClient`); enumerating them used to open every device's property
  store on each read.

Measured on the i7-12650H (16 threads), CPU time of each process over 30 s:

| Tablet showing | WallBridge | keepcal.py | WmiPrvSE |
|---|---|---|---|
| spacedesk (Lumia Wall in the background) | 0.05% of one core | 0% | 0% |
| grace wall (music playing) | 0.26% of one core | 0% | 0% |
| windows section (vitals every 1 s, status every 3 s) | 0.16% of one core | 0% | 0% |

That's at most 0.02% of the whole CPU. Before the caching, the windows section cost 9.3% of a core: vitals took
48 ms of CPU per sample (now 1 ms) and status 172 ms per read (now 4 ms). RAM: WallBridge ~110 MB, keepcal.py ~45 MB.
