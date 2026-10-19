# Publish one GitHub Release that carries a TPC-signed APK per ATAK line.
#
#   .\scripts\publish-release.ps1 -Tag tpc-0.1.10 -ApkDir D:\tpc -WhatIf
#   .\scripts\publish-release.ps1 -Tag tpc-0.1.10 -ApkDir D:\tpc -NotesFile notes.md
#
# -ApkDir holds PlowTAK-<stamp>-ATAK-<line>-civ-release.apk from tak.gov.
# Every APK must share one stamp. -WhatIf prints the release body and the
# README link block and does not call gh or edit README.

param(
    [Parameter(Mandatory = $true)]
    [string]$Tag,
    [Parameter(Mandatory = $true)]
    [string]$ApkDir,
    [string]$NotesFile,
    [switch]$Draft,
    [switch]$WhatIf
)

$ErrorActionPreference = "Stop"
$Repo = Split-Path -Parent $PSScriptRoot
$OwnerRepo = "CopIXus/PlowTAK"
$Lines = @("5.6.0", "5.7.0", "5.8.0")
Set-Location $Repo

function Get-VersionCode([string]$Stamp, [string]$Line) {
    if ($Stamp -notmatch '^(\d{2})\.(\d{4})\.(\d{4})$') {
        throw "stamp '$Stamp' is not yy.MMdd.HHmm"
    }
    $yy = [int]$Matches[1]
    $mmdd = [int]$Matches[2]
    $hhmm = [int]$Matches[3]
    $minor = 8
    $parts = $Line.Split(".")
    if ($parts.Length -gt 1) { $minor = [int]$parts[1] }
    $offset = [Math]::Max(0, $minor - 6)
    return (($yy - 20) * 100000000) + ($mmdd * 10000) + $hhmm + $offset
}

function Get-UnreleasedNotes {
    $text = Get-Content -LiteralPath (Join-Path $Repo "CHANGELOG.md") -Raw
    if ($text -match '(?s)## \[Unreleased\]\r?\n(.*?)(?=\r?\n## )') {
        return $Matches[1].Trim()
    }
    return ""
}

if (-not (Test-Path -LiteralPath $ApkDir)) {
    throw "APK folder not found: $ApkDir"
}

$apks = @(Get-ChildItem -LiteralPath $ApkDir -Filter "PlowTAK-*-ATAK-*-civ-release.apk" -File |
    Where-Object { $_.Name -match '^PlowTAK-(\d{2}\.\d{4}\.\d{4})-ATAK-(\d+\.\d+\.\d+)-civ-release\.apk$' })
if ($apks.Count -eq 0) {
    throw "no PlowTAK-*-ATAK-*-civ-release.apk under $ApkDir"
}

$rows = foreach ($apk in $apks) {
    if ($apk.Name -notmatch '^PlowTAK-(\d{2}\.\d{4}\.\d{4})-ATAK-(\d+\.\d+\.\d+)-civ-release\.apk$') {
        continue
    }
    $hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash.ToLower()
    $sidecar = "$($apk.FullName).sha256"
    [pscustomobject]@{
        Name   = $apk.Name
        Path   = $apk.FullName
        Sidecar = $sidecar
        Stamp  = $Matches[1]
        Line   = $Matches[2]
        Hash   = $hash
    }
}
$rows = @($rows | Sort-Object Line)
$stamps = @($rows.Stamp | Select-Object -Unique)
if ($stamps.Count -ne 1) {
    throw "APKs must share one version stamp, found: $($stamps -join ', ')"
}
$stamp = $stamps[0]
$present = @($rows.Line)
$missing = @($Lines | Where-Object { $_ -notin $present })

$which = ($present | ForEach-Object { $_.Substring(0, 3) }) -join " / "
$intro = "PlowTAK **$stamp**, TPC-signed for ATAK-CIV $which."
if ($missing.Count -gt 0) {
    $intro += " Not in this release yet: $($missing -join ', ')."
}

$table = @(
    "| ATAK-CIV | File | SHA-256 |",
    "|---|---|---|"
)
foreach ($row in $rows) {
    $short = $row.Line.Substring(0, 3)
    $table += "| $short | ``$($row.Name)`` | ``$($row.Hash)`` |"
}

if ($NotesFile) {
    $whatsNew = (Get-Content -LiteralPath $NotesFile -Raw).Trim()
} else {
    $whatsNew = Get-UnreleasedNotes
}
if (-not $whatsNew) { $whatsNew = "_No notes._" }

$codes = ($rows | ForEach-Object {
    "{0} ({1})" -f (Get-VersionCode $stamp $_.Line), $_.Line.Substring(0, 3)
}) -join ", "

$body = @"
$intro

### Which file?
Pick the APK that matches the ATAK-CIV version on the device (Settings, About ATAK), sideload it, then Plugins, PlowTAK, **Load**. A CopIX-signed CI build shows "signature INVALID" on release ATAK.

$($table -join "`n")

Built and signed by the TAK Product Center third-party pipeline. These load in official ATAK.

### What's new

$whatsNew

Peer dependency: **VNS 4.0** from tak.gov for offline maps.

Version codes for MDM: $codes.
"@

Write-Host $body

$linkLines = foreach ($row in $rows) {
    $url = "https://github.com/$OwnerRepo/releases/download/$Tag/$($row.Name)"
    "- ATAK-CIV $($row.Line.Substring(0, 3)): [$($row.Name)]($url)"
}
$readmeBlock = @(
    "<!-- plowtak-release-links:start -->"
    $linkLines
    "<!-- plowtak-release-links:end -->"
) -join "`n"
Write-Host ""
Write-Host "README block:"
Write-Host $readmeBlock

$readmePath = Join-Path $Repo "README.md"
$readme = Get-Content -LiteralPath $readmePath -Raw
$startMark = "<!-- plowtak-release-links:start -->"
$endMark = "<!-- plowtak-release-links:end -->"
$startAt = $readme.IndexOf($startMark)
$endAt = $readme.IndexOf($endMark)
if ($startAt -lt 0 -or $endAt -lt $startAt) {
    Write-Warning "README.md has no plowtak-release-links markers; links were not written."
} elseif (-not $WhatIf) {
    $updated = $readme.Substring(0, $startAt) + $readmeBlock + $readme.Substring($endAt + $endMark.Length)
    [System.IO.File]::WriteAllText($readmePath, $updated)
    Write-Host "Updated README.md. Commit README.md and CHANGELOG.md."
}

$assets = @()
foreach ($row in $rows) {
    $assets += $row.Path
    $assets += $row.Sidecar
}

if ($WhatIf) {
    Write-Host "WhatIf: no GitHub release was created or updated."
    return
}

foreach ($row in $rows) {
    Set-Content -LiteralPath $row.Sidecar -Value "$($row.Hash)  $($row.Name)" -Encoding ascii
}

$existing = $false
try {
    gh release view $Tag --repo $OwnerRepo --json tagName 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $existing = $true }
} catch {
    $existing = $false
}

$notesDir = Join-Path (Join-Path $Repo "dist") "tpp"
$notesFile = Join-Path $notesDir "release-notes.md"
New-Item -ItemType Directory -Force -Path $notesDir | Out-Null
Set-Content -LiteralPath $notesFile -Value $body -Encoding utf8

if ($existing) {
    gh release upload $Tag --repo $OwnerRepo --clobber @assets
    if ($LASTEXITCODE -ne 0) { throw "gh release upload failed" }
    gh release edit $Tag --repo $OwnerRepo --title "PlowTAK $stamp" --notes-file $notesFile
    if ($LASTEXITCODE -ne 0) { throw "gh release edit failed" }
} else {
    $draftArg = @()
    if ($Draft) { $draftArg = @("--draft") }
    gh release create $Tag --repo $OwnerRepo --title "PlowTAK $stamp" --notes-file $notesFile @draftArg @assets
    if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
}

Write-Host "Release: https://github.com/$OwnerRepo/releases/tag/$Tag"
