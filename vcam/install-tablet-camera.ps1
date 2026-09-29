# Installs the "Tablet Camera" Windows 11 virtual camera (run elevated).
#   -Uninstall  removes it again.
param([switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$here  = $PSScriptRoot
$dest  = 'C:\Program Files\TabletCamera'
$clsid = '{CEBBFFF7-1284-4D72-81FF-8C216C8984B6}'
$key   = "HKLM:\SOFTWARE\Classes\CLSID\$clsid"
$log   = Join-Path $here 'install.log'
Start-Transcript -Path $log -Force | Out-Null

if ($Uninstall) {
    if (Test-Path "$dest\TabletCamRegister.exe") { & "$dest\TabletCamRegister.exe" remove }
    Remove-Item $key -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $dest -Recurse -Force -ErrorAction SilentlyContinue
    "uninstalled"
    Stop-Transcript | Out-Null
    return
}

# 1. files (the Frame Server may hold the old DLL; stop it, Windows restarts it on the next camera use)
Stop-Service FrameServer -Force -ErrorAction SilentlyContinue
Stop-Service FrameServerMonitor -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item (Join-Path $here 'x64\Release\VirtualCameraMediaSource.dll') "$dest\TabletCameraSource.dll" -Force
Copy-Item (Join-Path $here 'TabletCamRegister\TabletCamRegister.exe') "$dest\TabletCamRegister.exe" -Force

# 2. COM registration of the media source (in-proc, loaded by the Windows Frame Server)
New-Item -Path "$key\InprocServer32" -Force | Out-Null
Set-ItemProperty -Path $key -Name '(default)' -Value 'Tablet Camera media source'
Set-ItemProperty -Path "$key\InprocServer32" -Name '(default)' -Value "$dest\TabletCameraSource.dll"
Set-ItemProperty -Path "$key\InprocServer32" -Name 'ThreadingModel' -Value 'Both'

# Mounting correction applied to every frame (0/90/180/270). The wall tablet hangs upside down.
New-Item -Path 'HKLM:\SOFTWARE\TabletCamera' -Force | Out-Null
Set-ItemProperty -Path 'HKLM:\SOFTWARE\TabletCamera' -Name 'Rotation' -Value 180 -Type DWord

# 3. register the virtual camera (system lifetime: persists across reboots)
& "$dest\TabletCamRegister.exe" add

# 4. remove the broken IP Camera Adapter (DirectShow filter that crashed every consumer)
Get-ChildItem 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall' |
    Get-ItemProperty | Where-Object { $_.DisplayName -match '^IP Camera Adapter' -and $_.PSChildName -match '^\{' } |
    ForEach-Object {
        "removing $($_.DisplayName) $($_.PSChildName)"
        Start-Process msiexec -ArgumentList '/x', $_.PSChildName, '/qn', '/norestart' -Wait
    }

Get-PnpDevice -Class Camera -ErrorAction SilentlyContinue | Select-Object Status, FriendlyName, InstanceId | Format-Table -AutoSize | Out-String
Stop-Transcript | Out-Null
