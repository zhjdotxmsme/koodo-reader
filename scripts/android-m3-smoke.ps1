# android-m3-smoke.ps1 — install debug APK, push fixtures, launch native shell.
# Full checklist: docs/superpowers/plans/2026-10-21-m3-device-smoke-checklist.md
param(
    [switch]$SkipBuild,
    [string]$Serial = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    $base = @($adb)
    if ($Serial) { $base += @("-s", $Serial) }
    & @($base + $Args)
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Args -join ' ')" }
}

if (-not $SkipBuild) {
    $tmp = Join-Path $root ".tools\tmp"
    $env:JAVA_HOME = Join-Path $root ".tools\jdk-17.0.20.1+1"
    $env:Path = "$env:JAVA_HOME\bin;$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:Path
    $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    $env:GRADLE_USER_HOME = Join-Path $root ".tools\gradle-home"
    $env:LOCALAPPDATA = Join-Path $root ".tools\localappdata"
    $env:TEMP = $tmp; $env:TMP = $tmp
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Push-Location (Join-Path $root "android")
    try {
        & .\gradlew.bat --no-daemon `
            --init-script "$root\.tools\reloc-build.gradle" `
            --init-script "$root\.tools\debug-signing.gradle" `
            --project-cache-dir "$root\.tools\gradle-project-cache" `
            "-Djava.io.tmpdir=$tmp" `
            "-Dkotlin.compiler.execution.strategy=in-process" `
            :app:assembleDebug -Ptarget=native -PabiFilters=arm64-v8a
    } finally { Pop-Location }
}

$apk = Join-Path $root ".tools\out\_app\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { Write-Error "APK missing: $apk" }

Write-Host "[m3-smoke] devices:"
Invoke-Adb devices -l

Write-Host "[m3-smoke] install $apk"
Invoke-Adb install -r $apk

Invoke-Adb shell mkdir -p /sdcard/Download/koodo-smoke
$fixtures = @(
    @{ Local = ".tools\testbooks\sample.pdf"; Remote = "/sdcard/Download/koodo-smoke/sample.pdf" },
    @{ Local = ".tools\testbooks\KoodoTestBook.epub"; Remote = "/sdcard/Download/koodo-smoke/KoodoTestBook.epub" }
)
foreach ($f in $fixtures) {
    $local = Join-Path $root $f.Local
    if (Test-Path $local) {
        Write-Host "[m3-smoke] push $local"
        Invoke-Adb push $local $f.Remote
    }
}

Invoke-Adb logcat -c
Invoke-Adb shell am force-stop com.koodoreader.reader
Invoke-Adb shell am start -n com.koodoreader.reader/.LauncherAlias

Write-Host "[m3-smoke] OK — unlock device, import from Download/koodo-smoke if shelf empty, then run checklist P1–E4."
