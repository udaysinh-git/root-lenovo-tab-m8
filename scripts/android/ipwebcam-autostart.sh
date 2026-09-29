#!/system/bin/sh
# Magisk service.d script: start IP Webcam's server at boot and restart it if it ever stops.
# After (re)starting it, spacedesk is brought back to the front so the wall display returns.
# IP Webcam keeps streaming from its background (camera) service.
PORT=8080
LOG=/data/local/tmp/ipwebcam-autostart.log

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
sleep 20                                    # let Wi-Fi, USB and the launcher settle

# True only if a socket is in LISTEN state (st 0A) on $PORT; ignores TIME_WAIT/leftover connections.
listening() {
    hexport=$(printf '%04X' $PORT)
    cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null | awk -v p=":$hexport" 'toupper(substr($2, length($2)-4)) == p && $4 == "0A" { found=1 } END { exit !found }'
}

# Settings changed through IP Webcam's HTTP API are runtime-only and reset when the app restarts,
# so re-apply them every time the server comes up (over loopback, which the lockdown doesn't block).
apply_settings() {
    for s in "ffc?set=on" "video_size?set=1280x720" "quality?set=60" "orientation?set=upsidedown"; do
        curl -s -m 5 -o /dev/null "http://127.0.0.1:$PORT/settings/$s"
    done
    echo "$(date '+%F %T') settings applied (front camera, 1280x720, q60, upside-down)" >> "$LOG"
}

start_server() {
    echo "$(date '+%F %T') starting IP Webcam server" >> "$LOG"
    am start -n com.pas.webcam/.Rolling -a android.intent.action.RUN >/dev/null 2>&1
    sleep 12
    apply_settings
    monkey -p ph.spacedesk.beta -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
}

echo "$(date '+%F %T') autostart watching port $PORT" > "$LOG"

# Boot: camera server first, then the display (spacedesk) in front.
if listening; then
    apply_settings
    monkey -p ph.spacedesk.beta -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
else
    start_server
fi

while true; do
    listening || start_server
    sleep 60
done
