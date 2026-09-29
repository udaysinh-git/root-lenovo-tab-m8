param([string]$Label, [int]$Seconds = 30)
# CPU (as % of ONE logical core, and of the whole 16-thread CPU) and RAM of WallBridge + keepcal.py + WmiPrvSE
$procs = @(Get-Process WallBridge -ErrorAction SilentlyContinue)
$procs += @(Get-CimInstance Win32_Process -Filter "Name='python.exe'" | Where-Object CommandLine -like '*keepcal.py loop*' |
    ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
$procs += @(Get-Process WmiPrvSE -ErrorAction SilentlyContinue)
$t0 = @{}
foreach ($p in $procs) { $p.Refresh(); $t0[$p.Id] = $p.TotalProcessorTime.TotalMilliseconds }
Start-Sleep $Seconds
foreach ($p in $procs) {
    $p.Refresh()
    $d = $p.TotalProcessorTime.TotalMilliseconds - $t0[$p.Id]
    "{0,-9} {1,-11} {2,6:N2}% of a core  ({3:N3}% of CPU)  ram {4,4} MB" -f $Label, $p.ProcessName, ($d / ($Seconds * 10)), ($d / ($Seconds * 10) / 16), [int]($p.WorkingSet64 / 1MB)
}
