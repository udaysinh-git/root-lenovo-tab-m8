#!/system/bin/sh
# Magisk service.d script: keep IP Webcam's server (port 8080, no password) off the network.
# The PC reaches it only through `adb forward` over USB, which arrives on the loopback interface.
PORT=8080
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 5; done
for t in iptables ip6tables; do
    for ifc in wlan0 rndis0; do
        $t -C INPUT -i $ifc -p tcp --dport $PORT -j DROP 2>/dev/null || \
        $t -I INPUT -i $ifc -p tcp --dport $PORT -j DROP
    done
done
