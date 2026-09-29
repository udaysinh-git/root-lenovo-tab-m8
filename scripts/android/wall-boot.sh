#!/system/bin/sh
# Magisk service.d script for the wall tablet.
#  - Boot: open Lumia Wall (home; it starts the built-in camera streamer), then spacedesk for the display.
#  - Keep-alive: if the camera streamer (127.0.0.1:8080) is gone, briefly reopen Lumia Wall to restart it,
#    then return to whatever was on screen.
PORT=8080
LOG=/data/local/tmp/wall-boot.log
HOME_APP=io.uday.lumiawall/.WallActivity

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
sleep 15

listening() {
    hexport=$(printf '%04X' $PORT)
    cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null | awk -v p=":$hexport" 'toupper(substr($2, length($2)-4)) == p && $4 == "0A" { found=1 } END { exit !found }'
}
top_package() {
    dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity | sed -n 's/.* u0 \([^/]*\)\/.*/\1/p'
}
bring() { monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

echo "$(date '+%F %T') boot: home -> camera service -> spacedesk" > "$LOG"
am start -n "$HOME_APP" >/dev/null 2>&1
sleep 8
bring ph.spacedesk.beta

while true; do
    sleep 60
    if ! listening; then
        back=$(top_package)
        echo "$(date '+%F %T') camera streamer down, restarting via home (back to ${back:-spacedesk})" >> "$LOG"
        am start -n "$HOME_APP" >/dev/null 2>&1
        sleep 6
        [ -n "$back" ] && [ "$back" != "io.uday.lumiawall" ] && bring "$back"
    fi
done
