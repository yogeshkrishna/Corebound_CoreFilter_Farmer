param(
    [string]$Repository = 'yogeshkrishna/ceiling-scout',
    [string]$NotesFile
)

$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}$') { throw 'Repository must be owner/repository.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
if (-not $ghCommand) { throw 'Install GitHub CLI and sign in with gh auth login first.' }
Push-Location $projectRoot
try {
    $changes = & git status --porcelain
    if ($LASTEXITCODE -ne 0) { throw 'This folder must be a Git checkout.' }
    if ($changes) { throw 'Commit the reviewed source before publishing a release.' }
    $sourceText = Get-Content -LiteralPath (Join-Path $projectRoot 'app\build.gradle') -Raw
    $match = [regex]::Match($sourceText, "versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'")
    if (-not $match.Success) { throw 'Use a three-part versionName, such as 0.3.0.' }
    $releaseTag = 'v' + $match.Groups[1].Value
    $revision = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read the source commit.' }
    & (Join-Path $PSScriptRoot 'build.ps1')
    $apkPath = Join-Path $projectRoot 'dist\Ceiling-Scout.apk'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apkPath
    $hash = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumPath = $apkPath + '.sha256'
    [System.IO.File]::WriteAllText($checksumPath, $hash + '  Ceiling-Scout.apk' + [Environment]::NewLine, [System.Text.Encoding]::ASCII)
    $arguments = @('release','create',$releaseTag,$apkPath,$checksumPath,'--repo',$Repository,'--target',$revision,'--title',('Ceiling Scout ' + $releaseTag))
    if ($NotesFile) {
        $resolvedNotes = (Resolve-Path -LiteralPath $NotesFile).Path
        $arguments += @('--notes-file',$resolvedNotes)
    } else { $arguments += '--generate-notes' }
    & $ghCommand.Source @arguments
    if ($LASTEXITCODE -ne 0) { throw 'GitHub release creation failed. Existing releases are not overwritten.' }
} finally {
    Pop-Location
}
