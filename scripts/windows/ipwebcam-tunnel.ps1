# Keeps the USB tunnel to the tablet's IP Webcam server alive: laptop 127.0.0.1:8765 -> tablet :8080.
# adb forwards die whenever the adb server restarts (e.g. Camo Studio restarting its own adb),
# so this re-creates it every few seconds when missing.
#
# Uses an adb whose version matches Camo Studio's bundled adb (1.0.40), so it never kills Camo's server.
param(
    [string]$Adb = (Join-Path $PSScriptRoot '..\..\downloads\adb-1.0.40\platform-tools\adb.exe'),
    [string]$Serial = $env:TABLET_SERIAL,
    [int]$LocalPort = 8765,
    [int]$RemotePort = 8080
)

while ($true) {
    $devices = & $Adb devices 2>$null | Select-String '\sdevice$' | ForEach-Object { ($_ -split '\s+')[0] }
    $target = if ($Serial) { $Serial } else { $devices | Where-Object { $_ -notmatch ':' } | Select-Object -First 1 }
    if ($target -and ($devices -contains $target)) {
        $list = (& $Adb forward --list 2>$null) -join "`n"
        if ($list -notmatch "tcp:$LocalPort tcp:$RemotePort") {
            & $Adb -s $target forward "tcp:$LocalPort" "tcp:$RemotePort" 2>$null | Out-Null
        }
    }
    Start-Sleep -Seconds 5
}
