# Finds the Tab M8 among connected adb devices, so no script needs a hard-coded serial (and none ever grabs another
# phone that happens to be plugged in). Under the LineageOS GSI the tablet reports a generic "Phh_Treble" model, but
# the vendor partition still says "Lenovo TB-8505X/F".
#   . "$PSScriptRoot\find-tablet.ps1"; $serial = Find-Tablet $adb
# $env:TABLET_SERIAL, if set, always wins.
function Find-Tablet([string]$Adb) {
    if ($env:TABLET_SERIAL) { return $env:TABLET_SERIAL }
    $serials = & $Adb devices 2>$null | Select-String '\sdevice$' | ForEach-Object { ($_ -split '\s+')[0] }
    foreach ($s in $serials) {
        $model = (& $Adb -s $s shell getprop ro.product.vendor.model 2>$null | Out-String).Trim()
        if ($model -match 'TB-8505') { return $s }
    }
    return $null
}
