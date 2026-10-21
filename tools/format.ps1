param([switch]$Check, [string]$Jdk = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
function Test-Jdk17([string]$Directory) {
    if (-not $Directory -or -not (Test-Path -LiteralPath (Join-Path $Directory 'bin/java.exe'))) { return $false }
    $release = Join-Path $Directory 'release'
    return (Test-Path -LiteralPath $release) -and [bool](Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="17[.\-"]' -Quiet)
}
if (-not (Test-Jdk17 $Jdk)) {
    $found = Get-ChildItem (Join-Path (Split-Path -Parent $project) '.toolchain/jdk') -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Jdk17 $_.FullName } | Select-Object -First 1
    if ($found) { $Jdk = $found.FullName }
}
if (-not (Test-Jdk17 $Jdk)) { throw 'JDK 17 is required. Set JAVA_HOME or pass -Jdk.' }
$jar = Join-Path $project '.local/tools/google-java-format.jar'
$checksum = '812f805f58112460edf01bf202a8e61d0fd1f35c0d4fabd54220640776ec57a1'
if (-not (Test-Path -LiteralPath $jar)) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $jar) -Force | Out-Null
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/com/google/googlejavaformat/google-java-format/1.24.0/google-java-format-1.24.0-all-deps.jar' -OutFile $jar
}
if ((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $checksum) { throw 'Formatter checksum mismatch.' }
$sources = Get-ChildItem (Join-Path $project 'engine/src'), (Join-Path $project 'app/src') -Recurse -File -Filter '*.java' | ForEach-Object FullName
[string[]]$formatterOptions = if ($Check) { @('--dry-run', '--set-exit-if-changed') } else { @('--replace') }
$argumentFile = Join-Path $project '.local/tools/format-arguments.txt'
$arguments = @($formatterOptions) + @($sources | ForEach-Object { $_.Replace('\','/') })
[IO.File]::WriteAllLines($argumentFile, $arguments, [Text.UTF8Encoding]::new($false))
try {
    & (Join-Path $Jdk 'bin/java.exe') -jar $jar "@$argumentFile"
    if ($LASTEXITCODE -ne 0) { throw 'Java formatting check failed.' }
} finally {
    Remove-Item -LiteralPath $argumentFile -ErrorAction SilentlyContinue
}
