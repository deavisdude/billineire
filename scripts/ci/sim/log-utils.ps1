# Log utilities for sanitization and parsing helpers
# Keep intentionally minimal and free of side-effects so scripts can dot-source safely

function Sanitize-Text {
    param([string]$text)
    if (-not $text) { return '' }
    # Strip ANSI/escape sequences like ESC[0;31m
    $clean = $text -replace "\x1b\[[0-9;]*[A-Za-z]", ''
    # Normalize CRLF -> LF
    $clean = $clean -replace "\r\n", "`n"
    $clean = $clean -replace "\r", "`n"
    # Remove non-ASCII characters (preserve LF and TAB)
    $chars = $clean.ToCharArray() | ForEach-Object {
        $c = [int]$_
        if (($c -ge 32 -and $c -le 126) -or $c -eq 9 -or $c -eq 10) { [char]$c } else { '' }
    }
    return -join $chars
}

function Read-And-Sanitize-LogFile {
    param([string]$path)
    if (-not (Test-Path $path)) { return '' }
    $raw = Get-Content -Path $path -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { return '' }
    return Sanitize-Text $raw
}
