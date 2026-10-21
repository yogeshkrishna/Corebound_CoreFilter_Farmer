param(
    [Parameter(Mandatory=$true)][string]$KeyStore,
    [Parameter(Mandatory=$true)][string]$PasswordFile,
    [string]$Alias = 'pilot-release',
    [string]$KeyPasswordFile = $PasswordFile,
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Output
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (-not $Output) { $Output = Join-Path $project 'dist/CoreFilterPilot-1.0.0-rc1.apk' }
if (-not $Sdk) { throw 'Set ANDROID_HOME or pass -Sdk.' }
$signer = Join-Path $Sdk 'build-tools/35.0.0/apksigner.bat'
$aligner = Join-Path $Sdk 'build-tools/35.0.0/zipalign.exe'
$inputApk = Join-Path $project 'app/build/outputs/apk/release/app-release-unsigned.apk'
foreach ($file in @($KeyStore,$PasswordFile,$KeyPasswordFile,$signer,$aligner,$inputApk)) {
    if (-not (Test-Path -LiteralPath $file)) { throw "Required file is missing: $file" }
}
New-Item -ItemType Directory -Path (Split-Path -Parent $Output) -Force | Out-Null
$signArguments = @('sign', '--ks', $KeyStore, '--ks-key-alias', $Alias, '--ks-pass', "file:$PasswordFile")
if ((Resolve-Path -LiteralPath $KeyPasswordFile).Path -ne (Resolve-Path -LiteralPath $PasswordFile).Path) {
    $signArguments += @('--key-pass', "file:$KeyPasswordFile")
}
$signArguments += @('--v4-signing-enabled', 'false', '--out', $Output, $inputApk)
& $signer @signArguments
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& $signer verify --verbose --print-certs $Output
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& $aligner -c -P 16 4 $Output
if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed.' }
Get-FileHash -LiteralPath $Output -Algorithm SHA256
