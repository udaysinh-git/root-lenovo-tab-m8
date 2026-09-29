# 4. Charge limiter for an always-plugged tablet

A tablet on USB power 24/7 sits at 100 % and ages its battery fast (swelling risk). This tablet has **no stock charge-limit
option**, but MediaTek exposes a charging switch that works with root:

```sh
echo "0 1" > /proc/mtk_battery_cmd/current_cmd   # stop charging (stays powered from USB)
echo "0 0" > /proc/mtk_battery_cmd/current_cmd   # resume charging
cat /proc/mtk_battery_cmd/current_cmd            # current state
```

Verified with `scripts/android/charge-test.sh`:

```
before:       status=Charging     current_now=38000
charging OFF: status=Not charging current_now=-300
charging ON:  status=Charging     current_now=69100
```

## Install

[`scripts/android/charge-limit.sh`](../scripts/android/charge-limit.sh) holds the battery between **50 % and 60 %**. Adjust
`LOW`/`HIGH` at the top of the script. It runs from Magisk's `service.d`, so it starts on every boot:

```sh
adb push scripts/android/charge-limit.sh /data/local/tmp/
adb shell su -c "cp /data/local/tmp/charge-limit.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/charge-limit.sh"
adb shell su -c "nohup sh /data/adb/service.d/charge-limit.sh >/dev/null 2>&1 &"   # start now without rebooting
adb shell su -c "cat /data/local/tmp/charge-limit.log"
```

It only writes to the control file when crossing a threshold, and checks once a minute.
