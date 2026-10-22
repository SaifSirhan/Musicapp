param([string]$path)
# Reports imports whose simple name never appears in the file body again
# (count <= 1 means only the import line itself matches). Read-only.
$ErrorActionPreference = "Stop"
$lines = [System.IO.File]::ReadAllLines($path)
$text = [string]::Join([char]10, $lines)
foreach ($l in $lines) {
    if ($l -match '^\s*import\s+([\w.]+)(\s+as\s+(\w+))?\s*$') {
        $sym = if ($matches[3]) { $matches[3] } else { $matches[1].Split('.')[-1] }
        $pattern = '\b' + [regex]::Escape($sym) + '\b'
        $c = ([regex]::Matches($text, $pattern)).Count
        if ($c -le 1) { Write-Output ("UNUSED: " + $l.Trim()) }
    }
}
