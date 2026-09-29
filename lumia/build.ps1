# Gradle-less build for Lumia Wall (same pattern as DotLauncher): aapt2 + javac + d8, framework APIs only.
#   .\build.ps1            -> build only
#   .\build.ps1 -Install   -> build, install on the tablet, launch it
param([switch]$Install, [string]$Serial = $env:TABLET_SERIAL)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$sdk  = "E:\DevData\Android\Sdk"
$bt   = "$sdk\build-tools\35.0.0"
$jar  = "$sdk\platforms\android-35\android.jar"
$out  = "$root\build"
# adb 1.0.40 matches Camo Studio's bundled adb, so builds never kill its server (see docs/07).
$adb  = Join-Path $root '..\downloads\adb-1.0.40\platform-tools\adb.exe'
if (-not (Test-Path $adb)) { $adb = 'adb' }
. (Join-Path $root '..\scripts\windows\find-tablet.ps1')

if (Test-Path $out) { Get-ChildItem $out -Recurse -Force | Sort-Object FullName -Descending | Remove-Item -Force -Recurse }
New-Item -ItemType Directory -Force -Path "$out\classes","$out\gen","$out\dex" | Out-Null

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }

# Assets are added to the zip below: aapt2 -A on Windows stores them as "assets/fonts\x.ttf" (backslash),
# which Android can't find.
& "$bt\aapt2.exe" link -o "$out\unsigned.apk" -I $jar `
    --manifest "$root\AndroidManifest.xml" "$out\res.zip" --java "$out\gen" `
    --min-sdk-version 26 --target-sdk-version 30
if ($LASTEXITCODE) { throw "aapt2 link failed" }

# android.jar has no LambdaMetafactory; compile a stub onto the classpath (never dexed) so lambdas compile.
New-Item -ItemType Directory -Force -Path "$out\stubs" | Out-Null
$sout = & "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -Xlint:-options -source 8 -target 8 `
    -bootclasspath $jar -d "$out\stubs" (Get-ChildItem -Recurse "$root\buildstubs" -Filter *.java).FullName 2>&1
if ($LASTEXITCODE) { $sout; throw "stub javac failed" }

$srcs = (Get-ChildItem -Recurse "$root\src","$out\gen" -Filter *.java).FullName
$jout = & "$env:JAVA_HOME\bin\javac.exe" -encoding UTF-8 -Xlint:-options -source 8 -target 8 `
    -bootclasspath "$jar;$out\stubs" -d "$out\classes" $srcs 2>&1
if ($LASTEXITCODE) { $jout | ForEach-Object { Write-Host $_ }; throw "javac failed" }

$classes = (Get-ChildItem -Recurse "$out\classes" -Filter *.class).FullName
& "$bt\d8.bat" --min-api 26 --lib $jar --output "$out\dex" $classes
if ($LASTEXITCODE) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", "Update")
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", "classes.dex") | Out-Null
foreach ($f in Get-ChildItem -Recurse -File "$root\assets") {
    $entry = 'assets/' + $f.FullName.Substring("$root\assets\".Length).Replace('\', '/')
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $entry) | Out-Null
}
$zip.Dispose()

& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }

& "$bt\apksigner.bat" sign --ks "$env:USERPROFILE\.android\debug.keystore" `
    --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey `
    --out "$out\lumiawall.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }

"built: $out\lumiawall.apk  ({0:N0} bytes)" -f (Get-Item "$out\lumiawall.apk").Length

if ($Install) {
    if (-not $Serial) { $Serial = Find-Tablet $adb }
    if (-not $Serial) { throw "Tab M8 not found on adb (or set `$env:TABLET_SERIAL)" }
    & $adb -s $Serial install -r "$out\lumiawall.apk"
    if ($LASTEXITCODE) { throw "adb install failed" }
    & $adb -s $Serial shell am start -n io.uday.lumiawall/.WallActivity | Out-Null
    "installed and launched on $Serial"
}
