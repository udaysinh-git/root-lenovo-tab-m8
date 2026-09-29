#!/system/bin/sh
# Show sockets owned by the spacedesk viewer (hex addresses decoded), sampled a few times
uid=$(dumpsys package ph.spacedesk.beta | grep -m1 userId= | sed 's/.*userId=\([0-9]*\).*/\1/')
echo "spacedesk uid=$uid"
dec() { # hex "0100007F:6E9C" -> 127.0.0.1:28316
  ip=$1; p=${2}
  printf "%d.%d.%d.%d:%d" 0x${ip:6:2} 0x${ip:4:2} 0x${ip:2:2} 0x${ip:0:2} 0x$p
}
for i in 1 2 3 4 5 6; do
  for f in tcp udp tcp6 udp6; do
    tail -n +2 /proc/net/$f | while read sl local rem st rest; do
      set -- $rest; owner=$5
      [ "$owner" = "$uid" ] || continue
      l=${local%:*}; lp=${local#*:}; r=${rem%:*}; rp=${rem#*:}
      if [ ${#l} -eq 8 ]; then echo "$f $(dec $l $lp) -> $(dec $r $rp) st=$st"; else echo "$f [v6] :$((0x$lp)) -> :$((0x$rp)) st=$st"; fi
    done
  done
  sleep 1
done | sort | uniq -c
