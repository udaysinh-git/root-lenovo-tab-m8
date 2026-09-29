# 9. Day sheet: Google Keep + today's calendar

**Pull up from the bottom edge** of Lumia Wall (a faint grab bar marks it) to get the day at a glance:

- **Left ⅔, notes (Google Keep).** A note list (pinned first, in Keep's own order, colour bar per note) and the open note:
  text, or a checklist with checked items dimmed at the bottom the way Keep shows them. Tap a note, or **swipe the
  open note sideways** to move through them.
  - **Checklists are live:** tap an item to tick or untick it; the tablet updates at once and Keep a couple of
    seconds later.
  - **"+ add item"** opens the on-screen keyboard; Done adds the item to the list.
- **Right ⅓, the week.** Events from every calendar that's switched on in Google Calendar (holidays included). Today
  comes first and always shows, even when empty. Then comes each day of the next week that has something. Past events
  are dimmed and the current one is marked **now · until …**.
- **Refresh button** (top right): the laptop syncs Keep and Calendar with Google immediately. It spins until the fresh
  data arrives.

Close it by dragging the top strip down, tapping above it, or pressing back. It refreshes on open and every minute while
open. The laptop syncs Keep every 60 s and Calendar every 5 min.

## How it works

```
keepcal.py (laptop, child of WallBridge) --> %LOCALAPPDATA%\WallBridge\keep.json, calendar.json
WallBridge /keep, /calendar --(adb reverse tcp:8770)--> tablet DaySheet.java
```

- **Keep has no official API for personal accounts** (the Keep API is Workspace-only). `keepcal.py` uses
  [gkeepapi](https://github.com/kiwiz/gkeepapi), which speaks the Keep Android app's sync protocol, with incremental
  sync state cached in `keep-state.json`.
- **Calendar:** the same Google master token mints a read-only Calendar token (as the Calendar Android app would), so
  one sign-in covers both. Events come from Calendar API v3 (`singleEvents`, today plus the next 7 days).
- **Edits and refresh:** the tablet calls WallBridge `/keep/check?note=&item=&checked=`, `/keep/add?note=&text=` or
  `/keep/refresh?note=all`. WallBridge drops one JSON file per command into `%LOCALAPPDATA%\WallBridge\keep-cmds`.
  `keepcal.py` checks that folder every second, applies the edit with gkeepapi, syncs to Google, and republishes
  `keep.json` (and `calendar.json` for a refresh).
- WallBridge starts `keepcal.py loop --parent <pid>` once a login exists, checks every minute, restarts it if it died,
  and the script exits with WallBridge. Log: `%LOCALAPPDATA%\WallBridge\keepcal.log`. If a sync fails, the last good data
  keeps being served, marked "offline, showing last sync".

## One-time sign-in

The master token is effectively full access to the Google account, so it is stored **only** in Windows Credential
Manager (`keyring`, entry `WallBridge-google`). It is never written to disk in plain text and never sent to the tablet.

1. Open <https://accounts.google.com/EmbeddedSetup> in a browser and sign in. After "I agree" the page keeps loading
   forever; that's expected.
2. Copy the `oauth_token` cookie (DevTools, Application, Cookies, accounts.google.com). It is valid for a few minutes only.
3. Exchange it:

```powershell
cd bridge\keepcal
C:\Python313\python.exe -m venv .venv
.venv\Scripts\python.exe -m pip install gkeepapi gpsoauth keyring
.venv\Scripts\python.exe keepcal.py login --email you@gmail.com --oauth-token "oauth2_4/..."
.venv\Scripts\python.exe keepcal.py once      # test: prints note and event counts
```

WallBridge picks the login up within a minute. To sign out, delete the `WallBridge-google` credential in Credential
Manager and the files in `%LOCALAPPDATA%\WallBridge`.
