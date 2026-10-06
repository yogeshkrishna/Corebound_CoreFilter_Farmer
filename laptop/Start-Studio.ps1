$ErrorActionPreference = 'Stop'
$studioRoot = $PSScriptRoot
$studioVenv = Join-Path $studioRoot '.venv'
$studioPython = Join-Path $studioVenv 'Scripts\python.exe'
if (-not (Test-Path -LiteralPath $studioPython)) {
    Write-Host 'Preparing Ceiling Scout Studio. First launch downloads its image tools.'
    $bundledPython = Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
    if (Test-Path -LiteralPath $bundledPython) { & $bundledPython -m venv $studioVenv }
    elseif (Get-Command py -ErrorAction SilentlyContinue) { py -3 -m venv $studioVenv }
    elseif (Get-Command python -ErrorAction SilentlyContinue) { python -m venv $studioVenv }
    else { Write-Host 'Install Python 3.12 from python.org, then open Start Studio again.'; Read-Host 'Press Enter'; exit 1 }
    if ($LASTEXITCODE -ne 0) { throw 'Python environment setup failed' }
}
$studioMarker = Join-Path $studioVenv 'studio-ready'
if (-not (Test-Path -LiteralPath $studioMarker)) {
    & $studioPython -m pip install -r (Join-Path $studioRoot 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Image tools could not be installed. Check your internet connection and try again.' }
    Set-Content -LiteralPath $studioMarker -Value '0.5.0'
}
$studioLog = Join-Path $env:LOCALAPPDATA 'Ceiling Scout Studio'
New-Item -ItemType Directory -Path $studioLog -Force | Out-Null
$studioConfig = Join-Path $studioLog 'connection.json'
if (Test-Path -LiteralPath $studioConfig) {
    $studioKey = (Get-Content -LiteralPath $studioConfig -Raw | ConvertFrom-Json).token
    $studioUrl = "http://127.0.0.1:8767/connect/$studioKey/"
    try {
        $studioResponse = Invoke-RestMethod -Uri ($studioUrl + 'api/status') -TimeoutSec 2
        if ($studioResponse.map) { Start-Process $studioUrl -WindowStyle Hidden; return }
    } catch { }
}
Start-Process -FilePath $studioPython -ArgumentList @('"' + (Join-Path $studioRoot 'studio.py') + '"', '--open') -WorkingDirectory $studioRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $studioLog 'studio.log') -RedirectStandardError (Join-Path $studioLog 'errors.log')
