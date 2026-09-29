#!/system/bin/sh
# Dump device-specific partitions on the TB-8505X (run as root).
# Usage: backup-partitions.sh list | dump <outdir>
BN=$(ls -d /dev/block/platform/*/by-name | head -1)

# Everything except the big partitions that the stock ROM already covers, plus userdata
SKIP=" system vendor product userdata cache lenovocust mmcblk0 "

case "$1" in
  list)
    for p in $(ls "$BN"); do
      n=$(basename "$(readlink -f "$BN/$p")")
      s=$(cat /sys/class/block/$n/size 2>/dev/null)
      echo "$p $n $((s * 512 / 1024))KB"
    done
    ;;
  dump)
    out="$2"; mkdir -p "$out"
    for p in $(ls "$BN"); do
      case "$SKIP" in *" $p "*) continue ;; esac
      dd if="$BN/$p" of="$out/$p.img" bs=1M 2>/dev/null && echo "ok $p"
    done
    # eMMC boot areas hold the preloader
    for b in mmcblk0boot0 mmcblk0boot1; do
      [ -e /dev/block/$b ] && dd if=/dev/block/$b of="$out/$b.img" bs=1M 2>/dev/null && echo "ok $b"
    done
    cd "$out" && sha256sum *.img > SHA256SUMS
    ;;
esac
