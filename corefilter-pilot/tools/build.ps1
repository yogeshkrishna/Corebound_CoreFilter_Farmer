param(
    [string[]]$Tasks = @(':engine:test', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug'),
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
function Test-Jdk17([string]$Directory) {
    if (-not $Directory -or -not (Test-Path -LiteralPath (Join-Path $Directory 'bin/javac.exe'))) { return $false }
    $release = Join-Path $Directory 'release'
    return (Test-Path -LiteralPath $release) -and [bool](Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="17[.\-"]' -Quiet)
}
if (-not (Test-Jdk17 $Jdk)) {
    $candidate = Join-Path (Split-Path -Parent $project) '.toolchain/jdk'
    $found = Get-ChildItem -LiteralPath $candidate -Directory -ErrorAction SilentlyContinue |
        Where-Object { Test-Jdk17 $_.FullName } | Select-Object -First 1
    if ($found) { $Jdk = $found.FullName }
}
if (-not $Sdk) {
    $candidate = Join-Path (Split-Path -Parent $project) '.toolchain/android-sdk'
    if (Test-Path -LiteralPath $candidate) { $Sdk = $candidate }
}
if (-not (Test-Jdk17 $Jdk)) { throw 'JDK 17 is required. Set JAVA_HOME to JDK 17, or pass -Jdk.' }
if (-not $Sdk -or -not (Test-Path -LiteralPath (Join-Path $Sdk 'platforms/android-35/android.jar'))) { throw 'Install Android SDK platform 35 and Build-Tools 35.0.0, then set ANDROID_HOME or pass -Sdk.' }
$previousJava = $env:JAVA_HOME
$previousAndroid = $env:ANDROID_HOME
Push-Location $project
try {
    $env:JAVA_HOME = $Jdk
    $env:ANDROID_HOME = $Sdk
    & '.\gradlew.bat' @Tasks --console=plain --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Build/check failed with exit code $LASTEXITCODE." }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousAndroid
}
