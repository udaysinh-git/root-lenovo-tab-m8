# Builds the WallBars overlay (status bar height 0) and installs it on the tablet as a Magisk module:
#   /data/adb/modules/wallbars/system/product/overlay/WallBars/WallBars.apk
# Preinstalled-path overlays may overlay framework resources that aren't declared <overlayable>; one reboot mounts it.
#   overlays\build-wallbars.ps1 [-Install]
# Remove: adb shell su -c "rm -rf /data/adb/modules/wallbars" and reboot (or: cmd overlay disable io.uday.wallbars).
param([switch]$Install)
$ErrorActionPreference = "Stop"
$sdk  = "E:\DevData\Android\Sdk"
$bt   = "$sdk\build-tools\35.0.0"
$jar  = "$sdk\platforms\android-35\android.jar"
$src  = "$PSScriptRoot\WallBars"
$out  = "$PSScriptRoot\build"
$adb  = "$PSScriptRoot\..\downloads\adb-1.0.40\platform-tools\adb.exe"
$serial = if ($env:TABLET_SERIAL) { $env:TABLET_SERIAL } else { "<serial>" }

New-Item -ItemType Directory -Force $out | Out-Null
& "$bt\aapt2.exe" compile --dir "$src\res" -o "$out\wallbars-res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -o "$out\wallbars-unsigned.apk" -I $jar --manifest "$src\AndroidManifest.xml" "$out\wallbars-res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }
& "$bt\zipalign.exe" -f 4 "$out\wallbars-unsigned.apk" "$out\WallBars.apk"
& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" --ks-pass pass:android --key-pass pass:android "$out\WallBars.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
"built: $out\WallBars.apk"

if ($Install) {
    $prop = "id=wallbars`nname=Wall bars`nversion=1`nversionCode=1`nauthor=udaysinh-git`ndescription=Status bar height 0 (hidden); Lumia Wall pulls notifications from the top edge`n"
    [IO.File]::WriteAllText("$out\module.prop", $prop)
    & $adb -s $serial push "$out\WallBars.apk" "$out\module.prop" /data/local/tmp/ | Out-Null
    & $adb -s $serial shell "su -c 'd=/data/adb/modules/wallbars; mkdir -p `$d/system/product/overlay/WallBars && cp /data/local/tmp/WallBars.apk `$d/system/product/overlay/WallBars/ && cp /data/local/tmp/module.prop `$d/ && chmod 644 `$d/system/product/overlay/WallBars/WallBars.apk && ls -lR `$d'"
    "installed as Magisk module 'wallbars': reboot, then  cmd overlay enable io.uday.wallbars"
}
