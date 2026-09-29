#!/system/bin/sh
# Continuous diagnostics for the spacedesk drop investigation (run as root, backgrounds itself).
D=/data/local/tmp/diag
mkdir -p $D
for p in $(pgrep -f "logcat -b all -v threadtime -f $D"); do kill $p; done
nohup logcat -b all -v threadtime -f $D/all.log -r 8192 -n 8 >/dev/null 2>&1 &
# once every 5 s: timestamp, USB state, top activity, spacedesk pid, battery, free RAM
(
  while true; do
    echo "$(date +%T) usb=$(getprop sys.usb.state) sd_pid=$(pidof ph.spacedesk.beta) top=$(dumpsys activity activities | grep -m1 mResumedActivity | grep -oE '[a-z0-9.]+/[A-Za-z0-9.]+') bat=$(cat /sys/class/power_supply/battery/capacity)% memavail=$(grep MemAvailable /proc/meminfo | awk '{print $2}')kB"
    sleep 5
  done
) >> $D/state.log 2>&1 &
echo "recorder started"
