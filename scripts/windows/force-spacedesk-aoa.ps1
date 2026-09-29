# Force spacedesk's Android USB driver onto Google's generic AOA device IDs (18D1:2D01, its MI_00
# interface, and 18D1:2D00). Run elevated, with the tablet connected in spacedesk USB mode.
#
# Why: other drivers can claim these generic IDs first. Samsung's "USB Driver for Mobile Phones"
# (ssudbus.inf) matches 18D1:2D01 and fails with Code 10; after Samsung's uninstaller runs, the
# spacedesk inf may be gone entirely (repair spacedesk with `msiexec /fa <spacedesk.msi>` first),
# and MI_00 can be left with no driver (Code 28). Only these AOA IDs are touched.
param([string]$LogDir = (Join-Path $PSScriptRoot '..\..\private\logs'))
New-Item -ItemType Directory -Force $LogDir | Out-Null
Start-Transcript -Path (Join-Path $LogDir 'force-spacedesk-aoa.log') -Force | Out-Null

Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class NewDev {
    [DllImport("newdev.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern bool UpdateDriverForPlugAndPlayDevices(IntPtr hwnd, string hardwareId, string infPath, int flags, out bool reboot);
}
'@

$inf = ((pnputil /enum-drivers | Out-String) -split '(?=Published Name)' |
        Where-Object { $_ -match 'spacedeskdriverandroidusb\.inf' } |
        ForEach-Object { [regex]::Match($_, 'oem\d+\.inf').Value } | Select-Object -First 1)
$infPath = "C:\Windows\INF\$inf"
"spacedesk AOA inf: $infPath"

foreach ($hw in 'USB\VID_18D1&PID_2D01', 'USB\VID_18D1&PID_2D01&MI_00', 'USB\VID_18D1&PID_2D00') {
    $reboot = $false
    $ok = [NewDev]::UpdateDriverForPlugAndPlayDevices([IntPtr]::Zero, $hw, $infPath, 1, [ref]$reboot)   # INSTALLFLAG_FORCE
    $err = [Runtime.InteropServices.Marshal]::GetLastWin32Error()
    "{0}: ok={1} reboot={2} lastError=0x{3:X}" -f $hw, $ok, $reboot, $err
}

Get-PnpDevice | Where-Object { $_.InstanceId -match '^USB\\VID_18D1&PID_2D0' } | ForEach-Object {
    $p = Get-PnpDeviceProperty -InstanceId $_.InstanceId -KeyName DEVPKEY_Device_DriverInfPath, DEVPKEY_Device_ProblemCode
    "{0} [{1}] inf={2} problem={3} status={4}" -f $_.InstanceId, $_.FriendlyName, $p[0].Data, $p[1].Data, $_.Status
}
Stop-Transcript | Out-Null
