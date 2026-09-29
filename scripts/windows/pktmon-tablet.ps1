# Trace packets from/to the tablet at the NIC with Windows' built-in pktmon (run elevated).
# Proves whether the tablet's packets actually reach the PC (vs. being dropped by the network).
param([string]$TabletIp = '192.168.1.8', [int]$Seconds = 20, [string]$OutDir = (Join-Path $PSScriptRoot '..\..\private\logs'))

New-Item -ItemType Directory -Force $OutDir | Out-Null
$etl = Join-Path $OutDir 'pktmon-tablet.etl'
pktmon stop 2>$null | Out-Null
pktmon filter remove | Out-Null
pktmon filter add TABLET -i $TabletIp | Out-Null
pktmon start --capture --comp nics --pkt-size 128 --file-name $etl | Out-Null
"capturing $TabletIp for $Seconds s - send traffic from the tablet now"
Start-Sleep -Seconds $Seconds
pktmon stop | Out-Null
pktmon counters --include-hidden --drop-reason | Out-File (Join-Path $OutDir 'pktmon-counters.txt')
pktmon etl2txt $etl --out (Join-Path $OutDir 'pktmon-trace.txt') --verbose 1 | Out-Null
pktmon filter remove | Out-Null
Get-Content (Join-Path $OutDir 'pktmon-trace.txt') | Select-String $TabletIp | Select-Object -First 20
