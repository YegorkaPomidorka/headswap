<#
.SYNOPSIS
  Bench stand in one command: smoke build on the phone over USB adb + PC head unit emulator + live video window.

.DESCRIPTION
  1. (-Build or no smoke APK) builds assembleSmoke. The self-declared android.car.permission.TEMPLATE_RENDERER
     clashes with the installed release build (INSTALL_FAILED_DUPLICATE_PERMISSION, ADR 0022), so it is removed
     from the debug manifest for the build only and the manifest is restored afterwards.
  2. Installs the smoke APK when built or missing.
  3. Sets bridgeMode in shared_prefs/aa.xml (-Mode desktop|aa|keep), activates the bridge from the PC
     (ManualAccessLauncher with the token from headswap_activation.xml).
  4. adb reverse tcp:7240, starts carlife_hu.py, starts BridgeService with debug_tcp 7240,
     shows the head unit screen in a live window (--view: mouse = touches).
  5. Copies the smoke log next to the emulator log in -Out.

.EXAMPLE
  .\stand.ps1 -Build
  .\stand.ps1 -Hu mazda
  .\stand.ps1 -Duration 300 -HuArgs '--touch-test'
#>
param(
    [switch]$Build,
    [ValidateSet('desktop', 'aa', 'keep')][string]$Mode = 'desktop',
    [int]$Duration = 3600,
    [string]$Size = '',  # default: 1280x720, -Hu mazda 1728x648
    # HU profile (carlife_hu.py --hu): mazda = Desay G6S of Mazda CX-5, Commander knob buttons under the window,
    # mouse wheel = knob turn, arrows = tilts, Enter = OK
    [ValidateSet('honda', 'mazda')][string]$Hu = 'honda',
    [string[]]$HuArgs = @(),
    [string]$Out = '',
    [switch]$NoView,
    [switch]$Audio      # activation with --audio: desktop sound capture over the CarLife MEDIA channel (ADR 0025)
)
# Continue: in Windows PowerShell 5.1 any stderr line of a native command (adb progress) is an error record;
# failures are checked by exit code below.
$ErrorActionPreference = 'Continue'
$Pkg = 'com.yegorkapomidorka.headswap.smoke'
$Here = $PSScriptRoot
$Android = Join-Path (Split-Path $Here -Parent) 'android'
$Apk = Join-Path $Android 'app\build\outputs\apk\smoke\app-smoke.apk'
$DebugManifest = Join-Path $Android 'app\src\debug\AndroidManifest.xml'
if (-not $Out) { $Out = Join-Path $Here ('out\stand-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
New-Item -ItemType Directory -Force $Out | Out-Null

function Step($msg) { Write-Host "== $msg" -ForegroundColor Cyan }
function AdbOk { & adb.exe @args; if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed ($LASTEXITCODE)" } }

$dev = (& adb get-state 2>$null)
if ($dev -ne 'device') { throw 'No phone over adb: connect USB, unlock, allow debugging' }

# 1. build
if ($Build -or -not (Test-Path $Apk)) {
    Step 'build assembleSmoke (TEMPLATE_RENDERER removed for this build only)'
    $orig = [IO.File]::ReadAllText($DebugManifest)
    $patched = $orig.Replace('    <application>',
        "    <permission android:name=`"android.car.permission.TEMPLATE_RENDERER`" tools:node=`"remove`" />`n`n    <application>")
    try {
        [IO.File]::WriteAllText($DebugManifest, $patched)
        $env:TEMP = 'C:\tmp'; $env:TMP = 'C:\tmp'
        $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\tmp'
        New-Item -ItemType Directory -Force 'C:\tmp' | Out-Null
        Push-Location $Android
        try { & .\gradlew.bat ':app:assembleSmoke' -q; if ($LASTEXITCODE -ne 0) { throw 'assembleSmoke failed' } }
        finally { Pop-Location }
    } finally {
        [IO.File]::WriteAllText($DebugManifest, $orig)
    }
}

# 2. install
$installed = (& adb shell pm path $Pkg 2>$null) -match 'package:'
if ($Build -or -not $installed) {
    Step 'install smoke'
    AdbOk install -r $Apk
}

# 3. prefs + activation
AdbOk shell am force-stop $Pkg
$tokenXml = (& adb shell run-as $Pkg cat shared_prefs/headswap_activation.xml 2>$null) -join "`n"
if ($tokenXml -notmatch 'name="token">([0-9a-f]+)<') {
    Step 'first start to create the activation token'
    AdbOk shell am start -n "$Pkg/com.yegorkapomidorka.headswap.MainActivity" | Out-Null
    for ($i = 0; $i -lt 20 -and $tokenXml -notmatch 'name="token">([0-9a-f]+)<'; $i++) {
        Start-Sleep -Milliseconds 500
        $tokenXml = (& adb shell run-as $Pkg cat shared_prefs/headswap_activation.xml 2>$null) -join "`n"
    }
    AdbOk shell am force-stop $Pkg
    if ($tokenXml -notmatch 'name="token">([0-9a-f]+)<') { throw 'No activation token in headswap_activation.xml' }
}
$token = $Matches[1]

if ($Mode -ne 'keep') {
    Step "bridgeMode=$Mode"
    $xml = (& adb shell run-as $Pkg cat shared_prefs/aa.xml 2>$null) -join "`n"
    if ($xml -notmatch '<map') { $xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>`n<map>`n</map>" }
    $xml = [regex]::Replace($xml, '\s*<string name="bridgeMode">[^<]*</string>', '')
    $xml = $xml.Replace('</map>', "    <string name=`"bridgeMode`">$Mode</string>`n</map>")
    $tmp = Join-Path $Out 'aa.xml'
    [IO.File]::WriteAllText($tmp, $xml.Replace("`r", '') + "`n")
    AdbOk push $tmp /data/local/tmp/hs_aa.xml 2>$null | Out-Null
    AdbOk shell "run-as $Pkg sh -c 'cat /data/local/tmp/hs_aa.xml > shared_prefs/aa.xml'"
    AdbOk shell rm /data/local/tmp/hs_aa.xml
}

Step 'activation (ManualAccessLauncher)'
$apkPath = ((& adb shell pm path $Pkg) -replace 'package:', '').Trim()
$act = & adb shell "CLASSPATH=$apkPath app_process / com.yegorkapomidorka.headswap.dex.ManualAccessLauncher --package $Pkg --token $token$(if ($Audio) { ' --audio' })" 2>&1
if (($act -join "`n") -notmatch 'HEADSWAP_READY') { $act | Select-Object -Last 10 | Write-Host; throw 'Activation failed' }

# 4. emulator + bridge + view
Step "emulator for $Duration s, out: $Out"
AdbOk reverse tcp:7240 tcp:7240 | Out-Null
$env:PYTHONIOENCODING = 'utf-8'
if (-not $Size) { $Size = if ($Hu -eq 'mazda') { '1728x648' } else { '1280x720' } }
$pyArgs = @('carlife_hu.py', '--duration', $Duration, '--out', "`"$Out`"", '--size', $Size, '--hu', $Hu)
if (-not $NoView) { $pyArgs += '--view' }   # live window: mouse = touches, BACK/HOME keys (carlife_view.py)
$pyArgs += $HuArgs
$huProc = Start-Process python -ArgumentList $pyArgs -WorkingDirectory $Here -NoNewWindow -PassThru
Start-Sleep -Seconds 3
AdbOk shell am start-foreground-service -n "$Pkg/com.yegorkapomidorka.headswap.BridgeService" --ei debug_tcp 7240 | Out-Null
Step 'bridge started; close the window or wait -Duration to finish'
$huProc.WaitForExit()

# 5. phone log
$log = ((& adb shell ls -t "/sdcard/Android/data/$Pkg/files/" 2>$null) | Select-Object -First 1)
if ($log) {
    $log = $log.Trim()
    & adb pull "/sdcard/Android/data/$Pkg/files/$log" (Join-Path $Out $log) | Out-Null
    Step "phone log: $(Join-Path $Out $log)"
}
Step "emulator log: $(Join-Path $Out 'carlife_hu.log')"
