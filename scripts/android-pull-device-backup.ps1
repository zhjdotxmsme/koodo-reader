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
    $base = @()
    if ($Serial) { $base += @("-s", $Serial) }
    # NOTE: `& @($base + $Args)` is WRONG under Windows PowerShell 5.1 — the
    # call operator concatenates the array into a single command NAME
    # ("adb.exe shell ...") and fails with CommandNotFoundException. The
    # arguments must be splatted individually: `& $exe @base @Args`.
    & $adb @base @Args
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
# Pull one binary file with byte-exact semantics.
#
# `adb exec-out` streams the raw bytes of the remote command to stdout
# WITHOUT a PTY. We copy that stream at the PROCESS level (BaseStream ->
# FileStream). Piping through PowerShell instead would decode and re-encode
# the bytes as text (UTF-16LE under Windows PowerShell 5.1) and silently
# corrupt the database file.
function Save-AdbBinary([string]$remotePath, [string]$localPath) {
    # POSIX-shell-quote the path so names with spaces survive.
    $quoted = $remotePath.Replace("'", "'\''")
    $remoteCmd = "run-as $pkg cat '$quoted'"
    $serialArgs = ""
    if ($Serial) { $serialArgs = " -s $Serial" }
    # The whole remote command travels as ONE adb argument, so the device
    # shell sees the inner quotes and splits the path correctly.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $adb
    $psi.Arguments = "exec-out$serialArgs shell `"$remoteCmd`""
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $out = [System.IO.File]::Create($localPath)
    $proc.StandardOutput.BaseStream.CopyTo($out)
    $out.Dispose()
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) {
        Remove-Item -Force $localPath -ErrorAction SilentlyContinue
        throw "adb exec-out failed for $remotePath (exit $($proc.ExitCode))"
    }
    # Verify the byte count against the device. `wc -c <path>` (a file
    # ARGUMENT, not a shell redirect) runs as the app uid via run-as; a
    # redirect would be opened by the shell uid and fail with EACCES.
    $sizeLine = Invoke-Adb shell "run-as $pkg wc -c '$quoted'" |
        Where-Object { $_ -match "\S" } | Select-Object -First 1
    $localSize = (Get-Item $localPath).Length
    if ($sizeLine) {
        $remoteSize = ($sizeLine.Trim() -split "\s+")[0]
        if ([int64]$remoteSize -ne $localSize) {
            Write-Warning "$remotePath size mismatch (remote=$remoteSize local=$localSize)"
        } else {
            Write-Host "[pull-backup] $remotePath -> $localPath ($localSize bytes)"
        }
    } else {
        Write-Warning "${remotePath}: could not read the remote size"
    }
}

foreach ($f in $files) {
    Save-AdbBinary $f.remote (Join-Path $dest $f.local)
}
# books/covers: list and pull one by one
$bookList = (Invoke-Adb shell "run-as $pkg ls files/books" 2>$null) -split "\s+" | Where-Object { $_ -match '\.' }
foreach ($b in $bookList) {
    if ($b -match '^\d') {
        Save-AdbBinary "files/books/$b" (Join-Path $dest "books\$b")
    }
}
Write-Host "[pull-backup] wrote $dest (sizes verified)"
