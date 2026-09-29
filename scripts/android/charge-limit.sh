#!/system/bin/sh
# Charge limiter for the wall-mounted TB-8505X (Magisk service.d script).
# Holds the battery between LOW and HIGH so it doesn't sit at 100% on USB power 24/7.
# The MTK switch: "0 1" > /proc/mtk_battery_cmd/current_cmd stops charging, "0 0" resumes it.

HIGH=60
LOW=50
CMD=/proc/mtk_battery_cmd/current_cmd
CAP=/sys/class/power_supply/battery/capacity
LOG=/data/local/tmp/charge-limit.log

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done

echo "$(date '+%F %T') started, holding ${LOW}-${HIGH}%" > "$LOG"
state=""
while true; do
    cap=$(cat "$CAP")
    if [ "$cap" -ge "$HIGH" ] && [ "$state" != "off" ]; then
        echo "0 1" > "$CMD"; state=off
        echo "$(date '+%F %T') ${cap}% -> charging off" >> "$LOG"
    elif [ "$cap" -le "$LOW" ] && [ "$state" != "on" ]; then
        echo "0 0" > "$CMD"; state=on
        echo "$(date '+%F %T') ${cap}% -> charging on" >> "$LOG"
    fi
    sleep 60
done
