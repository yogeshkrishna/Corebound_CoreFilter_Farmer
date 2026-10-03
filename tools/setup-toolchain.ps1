param([switch]$AcceptAndroidSdkLicense)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolchainRoot = Join-Path $projectRoot '.toolchain'
New-Item -ItemType Directory -Path $toolchainRoot -Force | Out-Null

function Get-VerifiedArchive {
    param([string]$Url, [string]$Path, [string]$Hash, [string]$Algorithm = 'SHA256')
    if (-not (Test-Path -LiteralPath $Path) -or
        (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLower() -ne $Hash) {
        Write-Host "Downloading $Url"
        Invoke-WebRequest -Uri $Url -OutFile $Path
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLower() -ne $Hash) {
        throw "Checksum mismatch: $Path"
    }
}

$jdkRoot = Join-Path $toolchainRoot 'jdk\jdk-17.0.20.1+1'
if (-not (Test-Path (Join-Path $jdkRoot 'bin\java.exe'))) {
    $jdkArchive = Join-Path $toolchainRoot 'jdk.zip'
    Get-VerifiedArchive -Url 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip' -Path $jdkArchive -Hash 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
    Expand-Archive -LiteralPath $jdkArchive -DestinationPath (Join-Path $toolchainRoot 'jdk') -Force
}
$env:JAVA_HOME = $jdkRoot
$env:ANDROID_HOME = Join-Path $toolchainRoot 'android-sdk'

# Pin SDK command-line tools 19 instead of silently changing build tooling.
$sdkManager = Join-Path $env:ANDROID_HOME 'cmdline-tools\19.0\cmdline-tools\bin\sdkmanager.bat'
if (-not (Test-Path -LiteralPath $sdkManager)) {
    $sdkArchive = Join-Path $toolchainRoot 'commandline-tools19.zip'
    Get-VerifiedArchive -Url 'https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip' -Path $sdkArchive -Hash '54a582f3bf73e04253602f2d1c80bd5868aac115' -Algorithm 'SHA1'
    Expand-Archive -LiteralPath $sdkArchive -DestinationPath (Join-Path $env:ANDROID_HOME 'cmdline-tools\19.0') -Force
}

$packagesFile = Join-Path $toolchainRoot 'packages.txt'
@('platform-tools', 'platforms;android-35', 'build-tools;35.0.0') |
    Set-Content -LiteralPath $packagesFile -Encoding ASCII
if ($AcceptAndroidSdkLicense) {
    # For unattended setup only; review https://developer.android.com/studio/terms first.
    ('y' * 100).ToCharArray() | & $sdkManager "--sdk_root=$env:ANDROID_HOME" "--package_file=$packagesFile"
} else {
    & $sdkManager "--sdk_root=$env:ANDROID_HOME" "--package_file=$packagesFile"
}
if ($LASTEXITCODE -ne 0) { throw "SDK setup failed with exit code $LASTEXITCODE." }

Write-Host 'Toolchain ready. Build with: powershell -ExecutionPolicy Bypass -File .\tools\build.ps1'
