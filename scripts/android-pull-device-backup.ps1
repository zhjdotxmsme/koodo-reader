# Pull app data snapshot before risky installs (debug run-as).
param([string]$Serial)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root ".tools\device-backup"
$adb = "C:\Users\54389\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}
$pkg = "com.koodoreader.reader"
function Invoke-Adb([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args) {
    $base = @($adb)
    if ($Serial) { $base += @("-s", $Serial) }
    & @($base + $Args)
}
New-Item -ItemType Directory -Force -Path $dest, "$dest\databases", "$dest\books", "$dest\cover", "$dest\shared_prefs" | Out-Null
Invoke-Adb shell am force-stop $pkg
$files = @(
    @{ remote = "databases/koodo.db"; local = "databases\koodo.db" },
    @{ remote = "databases/koodo.db-wal"; local = "databases\koodo.db-wal" },
    @{ remote = "databases/koodo.db-shm"; local = "databases\koodo.db-shm" },
    @{ remote = "databases/koodo-stats.db"; local = "databases\koodo-stats.db" },
    @{ remote = "databases/koodo-stats.db-wal"; local = "databases\koodo-stats.db-wal" },
    @{ remote = "databases/koodo-stats.db-shm"; local = "databases\koodo-stats.db-shm" },
    @{ remote = "shared_prefs/reader_progress.xml"; local = "shared_prefs\reader_progress.xml" }
)
foreach ($f in $files) {
    $name = Split-Path $f.remote -Leaf
    Invoke-Adb shell "run-as $pkg cat $($f.remote)" | Set-Content -Path (Join-Path $dest $f.local) -Encoding Byte -ErrorAction SilentlyContinue
    try {
        Invoke-Adb shell "run-as $pkg cat $($f.remote)" > (Join-Path $dest $f.local)
    } catch { Write-Warning "skip $($f.remote)" }
}
# books/covers: list and pull one by one
$bookList = (Invoke-Adb shell "run-as $pkg ls files/books" 2>$null) -split "\s+" | Where-Object { $_ -match '\.' }
foreach ($b in $bookList) {
    if ($b -match '^\d') {
        Invoke-Adb shell "run-as $pkg cat files/books/$b" > (Join-Path $dest "books\$b")
    }
}
Write-Host "[pull-backup] wrote $dest (verify sizes before trusting)"