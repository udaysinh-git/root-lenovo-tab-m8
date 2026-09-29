"""
Google Keep notes + today's Google Calendar for the wall tablet, via WallBridge.

Keep has no official API for personal accounts, so this uses gkeepapi (the Keep Android app's own sync
protocol) with a Google "master token". The same token mints a read-only Calendar token, so there is one login.

  keepcal.py login --email you@gmail.com --oauth-token oauth2_4/...   (one-time; see docs/09)
  keepcal.py loop [--parent PID]                                     (WallBridge runs this)
  keepcal.py once                                                    (sync once, print a summary)

The master token grants broad access to the account: it lives only in Windows Credential Manager (keyring),
never on disk in plain text and never on the tablet. Output: %LOCALAPPDATA%\\WallBridge\\keep.json, calendar.json.
"""
import argparse
import ctypes
import datetime as dt
import json
import os
import secrets
import sys
import time
import traceback

import gkeepapi
import gpsoauth
import keyring
import requests

SERVICE = "WallBridge-google"
DIR = os.path.join(os.environ["LOCALAPPDATA"], "WallBridge")
CONFIG = os.path.join(DIR, "keepcal-config.json")
KEEP_STATE = os.path.join(DIR, "keep-state.json")
KEEP_OUT = os.path.join(DIR, "keep.json")
CAL_OUT = os.path.join(DIR, "calendar.json")
CMD_DIR = os.path.join(DIR, "keep-cmds")        # WallBridge drops tablet edits here (tick / add item)
CAL_DAYS = 8                                    # today + the next 7 days

GOOGLE_SIG = "38918a453d07199354f8b19af05ec6562ced5788"   # signing cert of Google's Android apps
CAL_SCOPE = "oauth2:https://www.googleapis.com/auth/calendar.readonly"
KEEP_EVERY, CAL_EVERY = 60, 300          # while the tablet has looked at the day sheet recently
IDLE_EVERY = 900                         # otherwise: every 15 min (opening the sheet forces a sync anyway)
WATCH = os.path.join(DIR, "keep-watch")  # WallBridge touches this when the tablet asks for /keep or /calendar


def watched():
    try:
        return time.time() - os.path.getmtime(WATCH) < 300
    except OSError:
        return False


def load_config():
    try:
        with open(CONFIG, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def save_json(path, obj):
    os.makedirs(DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False)
    os.replace(tmp, path)          # atomic: WallBridge never serves a half-written file


# ---------------------------------------------------------------- login

def login(email, oauth_token):
    cfg = load_config()
    android_id = cfg.get("android_id") or secrets.token_hex(8)
    r = gpsoauth.exchange_token(email, oauth_token, android_id)
    if "Token" not in r:
        sys.exit(f"login failed: {r.get('Error', r)}")
    keyring.set_password(SERVICE, email, r["Token"])
    save_json(CONFIG, {"email": email, "android_id": android_id})
    print(f"ok: master token stored in Windows Credential Manager for {email}")


def credentials():
    cfg = load_config()
    email = cfg.get("email")
    if not email:
        raise RuntimeError("not logged in (run keepcal.py login)")
    master = keyring.get_password(SERVICE, email)
    if not master:
        raise RuntimeError("master token missing from Credential Manager (run keepcal.py login)")
    return email, master, cfg["android_id"]


# ---------------------------------------------------------------- keep

KEEP = None


def keep_client():
    global KEEP
    if KEEP is None:
        email, master, android_id = credentials()
        state = None
        if os.path.exists(KEEP_STATE):   # incremental sync: only changes since last time
            with open(KEEP_STATE, encoding="utf-8") as f:
                state = json.load(f)
        k = gkeepapi.Keep()
        k.authenticate(email, master, state=state, device_id=android_id)
        KEEP = k
    return KEEP


def sync_keep():
    k = keep_client()
    k.sync()
    with open(KEEP_STATE, "w", encoding="utf-8") as f:
        json.dump(k.dump(), f)
    notes = []
    for n in k.all():
        if n.trashed or n.archived:
            continue
        item = {
            "id": n.id,
            "title": n.title or "",
            "color": n.color.value if n.color else "DEFAULT",
            "pinned": bool(n.pinned),
            "updated": int(n.timestamps.updated.timestamp() * 1000),
            "sort": int(n.sort or 0),
        }
        if isinstance(n, gkeepapi.node.List):
            item["items"] = [{"id": i.id, "text": i.text, "checked": bool(i.checked),
                              "indent": 1 if i.indented else 0} for i in n.items]
        else:
            item["text"] = n.text or ""
        notes.append(item)
    # Keep's own order: pinned first, then by its sort value (what you see in the app), newest first
    notes.sort(key=lambda x: (not x["pinned"], -x["sort"], -x["updated"]))
    save_json(KEEP_OUT, {"ok": True, "at": int(time.time() * 1000), "notes": notes})
    return len(notes)


# ---------------------------------------------------------------- calendar

CAL_TOKEN = {"token": None, "exp": 0}


def calendar_token():
    if CAL_TOKEN["token"] and time.time() < CAL_TOKEN["exp"] - 120:
        return CAL_TOKEN["token"]
    email, master, android_id = credentials()
    r = gpsoauth.perform_oauth(email, master, android_id, service=CAL_SCOPE,
                               app="com.google.android.calendar", client_sig=GOOGLE_SIG)
    if "Auth" not in r:
        raise RuntimeError(f"calendar token: {r.get('Error', r)}")
    CAL_TOKEN["token"] = r["Auth"]
    CAL_TOKEN["exp"] = int(r.get("Expiry", time.time() + 3000))
    return CAL_TOKEN["token"]


def sync_calendar():
    h = {"Authorization": "Bearer " + calendar_token()}
    api = "https://www.googleapis.com/calendar/v3"
    cals = requests.get(f"{api}/users/me/calendarList", headers=h, timeout=15)
    cals.raise_for_status()
    now = dt.datetime.now().astimezone()
    start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    end = start + dt.timedelta(days=CAL_DAYS)
    events = []
    for c in cals.json().get("items", []):
        if not c.get("selected", False) or c.get("hidden", False):
            continue                                # only calendars shown in Google Calendar itself
        r = requests.get(f"{api}/calendars/{requests.utils.quote(c['id'], safe='')}/events", headers=h, timeout=15,
                         params={"timeMin": start.isoformat(), "timeMax": end.isoformat(),
                                 "singleEvents": "true", "orderBy": "startTime", "maxResults": 100})
        if r.status_code != 200:
            continue
        for e in r.json().get("items", []):
            if e.get("status") == "cancelled":
                continue
            all_day = "date" in e.get("start", {})
            if all_day:
                s = dt.datetime.fromisoformat(e["start"]["date"]).replace(tzinfo=now.tzinfo)
                f = dt.datetime.fromisoformat(e["end"]["date"]).replace(tzinfo=now.tzinfo)
            else:
                s = dt.datetime.fromisoformat(e["start"]["dateTime"])
                f = dt.datetime.fromisoformat(e["end"]["dateTime"])
            events.append({
                "title": e.get("summary", "(no title)"),
                "location": e.get("location", ""),
                "start": int(s.timestamp() * 1000),
                "end": int(f.timestamp() * 1000),
                "all_day": all_day,
                "calendar": c.get("summaryOverride") or c.get("summary", ""),
                "color": e.get("backgroundColor") or c.get("backgroundColor", "#4E2A8C"),
            })
    events.sort(key=lambda x: (x["start"], not x["all_day"]))
    save_json(CAL_OUT, {"ok": True, "at": int(time.time() * 1000), "events": events})
    return len(events)


# ---------------------------------------------------------------- loop

def parent_alive(pid):
    if not pid:
        return True
    h = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)   # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    code = ctypes.c_ulong()
    ctypes.windll.kernel32.GetExitCodeProcess(h, ctypes.byref(code))
    ctypes.windll.kernel32.CloseHandle(h)
    return code.value == 259                                     # STILL_ACTIVE


def guarded(name, fn, out):
    global KEEP
    try:
        return fn()
    except Exception as e:
        if name == "keep":
            KEEP = None                                          # re-authenticate next time
        prev = {}
        try:
            with open(out, encoding="utf-8") as f:
                prev = json.load(f)
        except Exception:
            pass
        # keep serving the last good data, flagged with the error
        prev.update({"ok": False, "error": f"{type(e).__name__}: {e}"})
        save_json(out, prev)
        traceback.print_exc()


def apply_commands():
    """Edits from the tablet, queued by WallBridge as one JSON file each: tick/untick an item, add an item."""
    files = sorted(f for f in os.listdir(CMD_DIR) if f.endswith(".json")) if os.path.isdir(CMD_DIR) else []
    if not files:
        return None
    k = keep_client()
    wants = {"keep"}
    for f in files:
        path = os.path.join(CMD_DIR, f)
        try:
            with open(path, encoding="utf-8") as fh:
                c = json.load(fh)
            note = k.get(c.get("note", ""))
            if isinstance(note, gkeepapi.node.List):
                if c.get("op") == "check":
                    for it in note.items:
                        if it.id == c.get("item"):
                            it.checked = bool(c.get("checked"))
                elif c.get("op") == "add" and c.get("text", "").strip():
                    note.add(c["text"].strip(), False, gkeepapi.node.NewListItemPlacementValue.Bottom)
            if c.get("op") == "refresh":                         # the tablet's refresh button
                wants.add("calendar")
        except Exception:
            traceback.print_exc()
        finally:
            os.remove(path)
    return wants


def loop(parent):
    last_keep = last_cal = 0
    while parent_alive(parent):
        t = time.time()
        wants = guarded("keep", apply_commands, KEEP_OUT)
        if wants:
            last_keep = 0                                        # push the edit to Google and republish now
            if "calendar" in wants:
                last_cal = 0
        w = watched()
        if t - last_keep >= (KEEP_EVERY if w else IDLE_EVERY):
            guarded("keep", sync_keep, KEEP_OUT)
            last_keep = t
        if t - last_cal >= (CAL_EVERY if w else IDLE_EVERY):
            guarded("calendar", sync_calendar, CAL_OUT)
            last_cal = t
        time.sleep(1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    lg = sub.add_parser("login")
    lg.add_argument("--email", required=True)
    lg.add_argument("--oauth-token", required=True)
    lp = sub.add_parser("loop")
    lp.add_argument("--parent", type=int, default=0)
    sub.add_parser("once")
    a = ap.parse_args()
    if a.cmd == "login":
        login(a.email, a.oauth_token)
    elif a.cmd == "once":
        print("keep notes:", sync_keep())
        print("calendar events (today+tomorrow):", sync_calendar())
    else:
        loop(a.parent)


if __name__ == "__main__":
    main()
