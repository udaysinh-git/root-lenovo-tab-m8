#!/system/bin/sh
# Check that /proc/mtk_battery_cmd/current_cmd really stops and restarts charging
B=/sys/class/power_supply/battery
show() { echo "$1: status=$(cat $B/status) cap=$(cat $B/capacity)% current_now=$(cat $B/current_now 2>/dev/null) cmd=[$(cat /proc/mtk_battery_cmd/current_cmd)]"; }
show before
echo "0 1" > /proc/mtk_battery_cmd/current_cmd
sleep 8
show "charging OFF"
echo "0 0" > /proc/mtk_battery_cmd/current_cmd
sleep 8
show "charging ON"
