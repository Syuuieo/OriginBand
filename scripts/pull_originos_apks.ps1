$ErrorActionPreference = "Continue"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ScriptDir
$OutRoot = Join-Path $RepoRoot "analysis\apks"
$ReportPath = Join-Path $OutRoot "device-report.txt"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb was not found in PATH. Ensure Android SDK platform-tools is installed and on PATH."
    exit 1
}

$devicesOutput = & adb devices 2>$null
$onlineDevices = @()
foreach ($line in $devicesOutput) {
    if ($line -match '^(\S+)\s+device\s*$') {
        $onlineDevices += $Matches[1]
    }
}

if ($onlineDevices.Count -eq 0) {
    Write-Host "No authorized ADB device detected." -ForegroundColor Yellow
    Write-Host "Connect your rooted OriginOS phone:"
    Write-Host "  1. Enable Developer Options -> USB debugging (or Wireless debugging + adb pair/connect)."
    Write-Host "  2. Plug in USB cable and accept the 'Allow USB debugging' RSA authorization prompt on phone."
    Write-Host "  3. Re-run: .\scripts\pull_originos_apks.ps1"
    Write-Host ""
    Write-Host "Current 'adb devices' output:"
    $devicesOutput | ForEach-Object { Write-Host "  $_" }
    exit 1
}

$Serial = $onlineDevices[0]
Write-Host "Using ADB device: $Serial" -ForegroundColor Green

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null

$report = [System.Collections.Generic.List[string]]::new()
$report.Add("=== OriginOS Device Extraction Report ===")
$report.Add("Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
$report.Add("ADB Serial: $Serial")
$report.Add("")
$report.Add("=== Device Properties ===")

$props = @(
    "ro.product.model",
    "ro.product.brand",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.vivo.os.version",
    "ro.vivo.os.build.display.id",
    "ro.product.cpu.abi"
)
foreach ($p in $props) {
    $val = (& adb -s $Serial shell getprop $p 2>$null | Out-String).Trim()
    $report.Add("${p}=$val")
}

$report.Add("")
$report.Add("=== Root Check ===")
try {
    $suVer = (& adb -s $Serial shell "su -v" 2>&1 | Out-String).Trim()
    $report.Add("su -v: $suVer")
} catch {
    $report.Add("su -v: unavailable")
}
try {
    $suId = (& adb -s $Serial shell "su -c id" 2>&1 | Out-String).Trim()
    $report.Add("su -c id: $suId")
} catch {
    $report.Add("su -c id: unavailable")
}

$report.Add("")
$report.Add("=== Relevant Installed Packages ===")
$allPackagesRaw = & adb -s $Serial shell pm list packages 2>&1
$allPackages = @()
foreach ($line in $allPackagesRaw) {
    if ($line -match '^package:(.+)$') {
        $allPackages += $Matches[1].Trim()
    }
}

$pattern = '(health|vivo|wear|watch|iot|device|clock|mi\.health|lsposed|vector)'
$matchedPackages = $allPackages | Where-Object { $_ -match $pattern } | Sort-Object -Unique
foreach ($pkg in $matchedPackages) {
    $report.Add($pkg)
}

$targetCandidates = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
foreach ($base in @("com.vivo.health", "com.vivo.healthservice", "com.vivo.healthwidget", "com.vivo.devicepower", "com.vivo.connbase.deviceaccessory", "com.mi.health", "com.android.BBKClock", "com.vivo.iot", "com.vivo.wearable")) {
    if ($allPackages -contains $base) {
        [void]$targetCandidates.Add($base)
    }
}
foreach ($pkg in $matchedPackages) {
    if ($pkg -match '^com\.vivo\.(health|iot|wear|watch|device|smart|ai|connect)' -or
        $pkg -match '^com\.bbk\.(health|clock)' -or
        $pkg -eq 'com.android.BBKClock' -or
        $pkg -eq 'com.mi.health') {
        [void]$targetCandidates.Add($pkg)
    }
}

$report.Add("")
$report.Add("=== Extracted Package Details ===")

foreach ($pkg in ($targetCandidates | Sort-Object)) {
    Write-Host "Inspecting and pulling $pkg ..."
    $report.Add("")
    $report.Add("--- $pkg ---")

    $dump = & adb -s $Serial shell dumpsys package $pkg 2>$null
    $interestingDump = $dump | Where-Object {
        $_ -match 'versionCode=|versionName=|targetSdk=|minSdk=|SigningInfo|signatures:|flags=\['
    } | Select-Object -First 25
    foreach ($dLine in $interestingDump) {
        $report.Add("  $($dLine.Trim())")
    }

    $pkgDir = Join-Path $OutRoot $pkg
    New-Item -ItemType Directory -Force -Path $pkgDir | Out-Null

    $paths = & adb -s $Serial shell pm path $pkg 2>&1
    $pulledAny = $false
    foreach ($pLine in $paths) {
        if ($pLine -match '^package:(.+)$') {
            $remotePath = $Matches[1].Trim()
            $fileName = Split-Path -Leaf $remotePath
            $localPath = Join-Path $pkgDir $fileName
            & adb -s $Serial pull $remotePath $localPath | Out-Null
            if (Test-Path $localPath) {
                $hash = (Get-FileHash -Path $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
                $size = (Get-Item $localPath).Length
                $report.Add("  APK: $remotePath -> $fileName (${size} bytes, sha256=$hash)")
                $pulledAny = $true
            } else {
                $report.Add("  APK PULL FAILED: $remotePath")
            }
        }
    }
    if (-not $pulledAny) {
        $report.Add("  No APK paths pulled.")
    }
}

$report | Set-Content -Path $ReportPath -Encoding UTF8
Write-Host ""
Write-Host "Extraction complete. Report written to: $ReportPath" -ForegroundColor Green
