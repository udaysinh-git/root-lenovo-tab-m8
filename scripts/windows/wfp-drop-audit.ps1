# Briefly audit Windows Filtering Platform packet drops for traffic from the tablet, then turn auditing off.
# Run elevated. Send test traffic from the tablet while it waits.
# Note: third-party WFP firewalls (e.g. Portmaster) drop packets in their own callouts and may NOT show up here.
param([string]$TabletIp = '192.168.1.8', [int]$Seconds = 25, [string]$OutDir = (Join-Path $PSScriptRoot '..\..\private\logs'))

New-Item -ItemType Directory -Force $OutDir | Out-Null
$out = Join-Path $OutDir 'wfp-drops.txt'
auditpol /set /subcategory:"Filtering Platform Packet Drop" /failure:enable | Out-Null
$start = Get-Date
"ready" | Set-Content (Join-Path $OutDir 'wfp-ready.txt')
Start-Sleep -Seconds $Seconds
auditpol /set /subcategory:"Filtering Platform Packet Drop" /failure:disable | Out-Null

$events = Get-WinEvent -FilterHashtable @{ LogName = 'Security'; Id = 5152, 5157; StartTime = $start } -ErrorAction SilentlyContinue |
    Where-Object { $_.Message -match [regex]::Escape($TabletIp) }
"drops from ${TabletIp}: $($events.Count)" | Set-Content $out
$events | Select-Object -First 6 | ForEach-Object {
    $m = $_.Message
    $fields = 'Direction','Source Address','Source Port','Destination Address','Destination Port','Protocol','Filter Run-Time ID','Layer Name','Application Name'
    $line = foreach ($f in $fields) { if ($m -match "$([regex]::Escape($f)):\s*(.+)") { "$f=$($Matches[1].Trim())" } }
    "[{0}] {1}" -f $_.Id, ($line -join ' | ')
} | Add-Content $out
Get-Content $out
