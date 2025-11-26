Import-Module Pester -ErrorAction SilentlyContinue

Describe 'Log sanitization helpers' {
    It 'Sanitize-Text strips ANSI sequences and non-ASCII characters' {
        $raw = "`e[31mHELLO`e[0m World ☃ ñ ö\r\nLine2"
        . (Join-Path $PSScriptRoot '..\log-utils.ps1')
        $out = Sanitize-Text $raw
        $out | Should Match 'HELLO.*World.*Line2'
        $out | Should Not Match '\x1b\['
        # Ensure non-ASCII removed (no characters >127)
        ($out.ToCharArray() | ForEach-Object { [int]$_ } | Where-Object { $_ -gt 127 }).Count | Should Be 0
    }

    It 'Read-And-Sanitize-LogFile normalizes line endings and strips control chars' {
        $tmp = Join-Path $PSScriptRoot 'tmp_log.txt'
        $esc = [char]27
        $content = "First`r`nSecond" + $esc + "[32mGreen" + $esc + "[0m" + "αß"
        Set-Content -Path $tmp -Value $content -Encoding UTF8
        . (Join-Path $PSScriptRoot '..\log-utils.ps1')
        $clean = Read-And-Sanitize-LogFile $tmp
        $clean | Should Match "First`nSecond"
        $clean | Should Not Match '\x1b\['
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }

    It 'Regex can extract determinism hash from sanitized log text' {
        . (Join-Path $PSScriptRoot '..\log-utils.ps1')
        $esc = [char]27
        $raw = $esc + "[32m[PATH] Determinism hash: AB12cd34 (nodes=128)" + $esc + "[0m"
        $clean = Sanitize-Text $raw
        $pattern = '\[PATH\] Determinism hash: ([A-Fa-f0-9]+) \(nodes=([0-9]+)\)'
        $m = [regex]::Match($clean, $pattern)
        $m.Success | Should Be $true
        $m.Groups[1].Value | Should Be 'AB12cd34'
        $m.Groups[2].Value | Should Be '128'
    }
}
