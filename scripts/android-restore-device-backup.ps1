# Restore a pulled device backup into a debug install (run-as).
# Default source: .tools/device-backup (see android-pull-device-backup.ps1 if added).
param(
    [string]$BackupDir,
    [string]$Serial
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $BackupDir) { $BackupDir = Join-Path $root ".tools\device-backup" }
if (-not (Test-Path $BackupDir)) { Write-Error "Backup missing: $BackupDir" }

$adbCandidates = @(
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    "C:\Users\54389\AppData\Local\Android\Sdk\platform-tools\adb.exe"
)
$adb = $adbCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $adb) { Write-Error "adb not found" }
function Invoke-Adb([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args) {
    $base = @($adb)
    if ($Serial) { $base += @("-s", $Serial) }
    & @($base + $Args)
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Args -join ' ')" }
}

$pkg = "com.koodoreader.reader"
Write-Host "[restore] force-stop $pkg"
Invoke-Adb shell am force-stop $pkg

function Push-IntoApp($local, $remoteName, $remoteDir) {
    Invoke-Adb push $local "/data/local/tmp/$remoteName"
    Invoke-Adb shell "run-as $pkg mkdir -p $remoteDir"
    Invoke-Adb shell "run-as $pkg cp /data/local/tmp/$remoteName $remoteDir/$remoteName"
}

$dbDir = Join-Path $BackupDir "databases"
if (Test-Path $dbDir) {
    Get-ChildItem $dbDir -File | ForEach-Object {
        Write-Host "[restore] db $($_.Name)"
        Push-IntoApp $_.FullName $_.Name "databases"
    }
}

$booksDir = Join-Path $BackupDir "books"
if (Test-Path $booksDir) {
    Get-ChildItem $booksDir -File | ForEach-Object {
        Write-Host "[restore] book $($_.Name)"
        Push-IntoApp $_.FullName $_.Name "files/books"
    }
}

$coverDir = Join-Path $BackupDir "cover"
if (Test-Path $coverDir) {
    Get-ChildItem $coverDir -File | ForEach-Object {
        Write-Host "[restore] cover $($_.Name)"
        Push-IntoApp $_.FullName $_.Name "files/cover"
    }
}

$prefsDir = Join-Path $BackupDir "shared_prefs"
if (Test-Path $prefsDir) {
    Get-ChildItem $prefsDir -File | ForEach-Object {
        Write-Host "[restore] pref $($_.Name)"
        Push-IntoApp $_.FullName $_.Name "shared_prefs"
    }
}

Write-Host "[restore] OK — cold-start the app to reopen the library."
Invoke-Adb shell am start -n "$pkg/.LauncherAlias"