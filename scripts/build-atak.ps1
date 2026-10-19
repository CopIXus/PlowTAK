# Build the plugin for one ATAK line, against that line's SDK.
#
#   .\scripts\build-atak.ps1 5.8.0
#   .\scripts\build-atak.ps1 5.6.0 debug
#   .\scripts\build-atak.ps1 all
#
# SDK root is $env:ATAK_SDK_ROOT (default T:\TAK). A line is found as either
#   <root>\ATAK-CIV-<x.y.z>-SDK\ATAK-CIV-<x.y.z>-SDK\main.jar
# or the flat TAK Beacon layout <root>\ATAK-CIV-<x.y.z>\main.jar.
# A patch extract such as ATAK-CIV-5.8.0.1-SDK satisfies a request for 5.8.0.
# Missing lines are skipped for "all" and are an error for one explicit line.
# SDKs are not downloaded.

param(
    [Parameter(Position = 0)]
    [string]$Atak = "5.8.0",
    [Parameter(Position = 1)]
    [ValidateSet("release", "debug")]
    [string]$Type = "release"
)

$ErrorActionPreference = "Stop"
$Lines = @("5.6.0", "5.7.0", "5.8.0")
$Root = if ($env:ATAK_SDK_ROOT) { $env:ATAK_SDK_ROOT } else { "T:\TAK" }
$Repo = Split-Path -Parent $PSScriptRoot
Set-Location $Repo

function Find-Jar([string]$Dir) {
    $jar = Join-Path $Dir "main.jar"
    if (Test-Path -LiteralPath $jar) { return $Dir }
    return $null
}

function Resolve-AtakSdk([string]$SdkRoot, [string]$Line) {
    $exact = @(
        (Join-Path $SdkRoot "ATAK-CIV-$Line-SDK\ATAK-CIV-$Line-SDK"),
        (Join-Path $SdkRoot "ATAK-CIV-$Line"),
        (Join-Path $SdkRoot "ATAK-CIV-$Line-SDK")
    )
    foreach ($dir in $exact) {
        $found = Find-Jar $dir
        if ($found) { return $found }
    }
    if (-not (Test-Path -LiteralPath $SdkRoot)) { return $null }
    $dirs = Get-ChildItem -LiteralPath $SdkRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "ATAK-CIV-$Line*" }
    foreach ($dir in $dirs) {
        $nested = Find-Jar (Join-Path $dir.FullName $dir.Name)
        if ($nested) { return $nested }
        $flat = Find-Jar $dir.FullName
        if ($flat) { return $flat }
    }
    return $null
}

if ($Atak -eq "all") {
    $any = $false
    foreach ($line in $Lines) {
        if (-not (Resolve-AtakSdk $Root $line)) {
            Write-Host "no SDK for $line under $Root; skipping"
            continue
        }
        & $PSCommandPath $line $Type
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $any = $true
    }
    if (-not $any) {
        Write-Error "no ATAK-CIV SDK found under $Root"
        exit 1
    }
    exit 0
}

if ($Lines -notcontains $Atak) {
    Write-Error "unsupported ATAK line '$Atak' (use $($Lines -join ', '), or all)"
    exit 1
}

$sdk = Resolve-AtakSdk $Root $Atak
if (-not $sdk) {
    Write-Error "no SDK for $Atak under $Root (looked for ATAK-CIV-$Atak and ATAK-CIV-$Atak*)"
    exit 1
}
$takdev = Join-Path $sdk "atak-gradle-takdev.jar"
if (-not (Test-Path -LiteralPath $takdev)) {
    Write-Error "SDK at $sdk has main.jar but no atak-gradle-takdev.jar"
    exit 1
}

$taskType = (Get-Culture).TextInfo.ToTitleCase($Type)
Write-Host "Building ATAK $Atak ($Type) against $sdk"
& .\gradlew.bat --no-daemon ":app:clean" ":app:assembleCiv$taskType" `
    "-Patak.version=$Atak" `
    "-Psdk.path=$sdk" `
    "-Ptakdev.plugin=$takdev"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apkDir = Join-Path $Repo "app\build\outputs\apk\civ\$Type"
$apks = Get-ChildItem -LiteralPath $apkDir -Filter "*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "*$Atak*" -and $_.Name -notlike "*unsigned*" }
if (-not $apks) {
    Write-Error "no signed APK for $Atak under $apkDir"
    exit 1
}
$dist = Join-Path $Repo "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
foreach ($apk in $apks) {
    Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $dist $apk.Name) -Force
    Write-Host $apk.Name
}
