param(
    [string[]]$Tasks = @('assembleDebug', 'testDebugUnitTest', 'lintDebug')
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.toolchain\jdk') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } | Select-Object -First 1
if ($localJdk) {
    $env:JAVA_HOME = $localJdk.FullName
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to JDK 17 or newer, or run tools\setup-toolchain.ps1 first.'
}

$localSdk = Join-Path $projectRoot '.toolchain\android-sdk'
if (Test-Path (Join-Path $localSdk 'platforms\android-35\android.jar')) {
    $env:ANDROID_HOME = $localSdk
}
if (-not $env:ANDROID_HOME) {
    throw 'Set ANDROID_HOME to an Android SDK with platform 35, or run tools\setup-toolchain.ps1 first.'
}

Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE." }
    if ($Tasks -contains 'testDebugUnitTest' -or $Tasks -contains 'test' -or $Tasks -contains 'check' -or $Tasks -contains 'build') {
        & (Join-Path $PSScriptRoot 'test-vision.ps1')
    }
} finally {
    Pop-Location
}
