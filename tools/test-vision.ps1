$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$taskJdk = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.toolchain\jdk') -Directory -ErrorAction SilentlyContinue | Where-Object { Test-Path (Join-Path $_.FullName 'bin\javac.exe') } | Select-Object -First 1
$taskJava = if ($taskJdk) { Join-Path $taskJdk.FullName 'bin\java.exe' } else { 'java' }
$taskJavac = if ($taskJdk) { Join-Path $taskJdk.FullName 'bin\javac.exe' } else { 'javac' }
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path 'analysis\test-classes' | Out-Null
    & $taskJavac -d 'analysis\test-classes' 'app\src\main\java\com\corefilter\farmer\vision\PixelVision.java' 'app\src\main\java\com\corefilter\farmer\engine\FarmEngine.java' 'app\src\main\java\com\corefilter\farmer\engine\ScreenInterpreter.java' 'tests\PixelVisionTest.java'
    if ($LASTEXITCODE -ne 0) { throw 'Vision test compilation failed.' }
    & $taskJava -cp 'analysis\test-classes' PixelVisionTest .
    if ($LASTEXITCODE -ne 0) { throw 'Vision fixture checks failed.' }
} finally { Pop-Location }
