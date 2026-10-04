param(
    [string]$BaseUrl = '__CEILING_SCOUT_BASE_URL__',
    [string]$OutputDirectory = (Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'Ceiling Scout Maps'),
    [switch]$NoPause
)

$ErrorActionPreference = 'Stop'
function Get-MapChecksum([string]$Path) {
    $mapHashAlgorithm = [Security.Cryptography.SHA256]::Create()
    $mapHashStream = [IO.File]::OpenRead($Path)
    try { return ([BitConverter]::ToString($mapHashAlgorithm.ComputeHash($mapHashStream))).Replace('-', '').ToLowerInvariant() }
    finally { $mapHashStream.Dispose(); $mapHashAlgorithm.Dispose() }
}
try {
    if ($BaseUrl -eq '__CEILING_SCOUT_BASE_URL__' -or [string]::IsNullOrWhiteSpace($BaseUrl)) {
        $BaseUrl = Read-Host 'Paste the laptop link shown in Ceiling Scout on your phone'
    }
    $mapServerUri = [Uri]$BaseUrl
    if ($mapServerUri.Scheme -ne 'http' -or $mapServerUri.HostNameType -ne [UriHostNameType]::IPv4 -or $mapServerUri.Query -or $mapServerUri.Fragment) {
        throw 'Use the complete local Wi-Fi link shown by Ceiling Scout.'
    }
    $mapAddress = [Net.IPAddress]::Parse($mapServerUri.Host).GetAddressBytes()
    $mapIsPrivate = $mapAddress[0] -eq 10 -or $mapAddress[0] -eq 127 -or ($mapAddress[0] -eq 192 -and $mapAddress[1] -eq 168) -or ($mapAddress[0] -eq 172 -and $mapAddress[1] -ge 16 -and $mapAddress[1] -le 31)
    if (-not $mapIsPrivate -or $mapServerUri.AbsolutePath -notmatch '^/[A-Za-z0-9_-]{43}/?$') {
        throw 'The sharing link must contain the private Wi-Fi address and its current sharing token.'
    }
    $mapBase = $mapServerUri.AbsoluteUri.TrimEnd('/')
    $mapToken = $mapServerUri.AbsolutePath.Trim('/')
    $mapOutputRoot = [IO.Path]::GetFullPath($OutputDirectory)
    if (-not (Test-Path -LiteralPath $mapOutputRoot)) { New-Item -ItemType Directory -Path $mapOutputRoot | Out-Null }
    $mapQueue = Invoke-RestMethod -Uri "$mapBase/api/maps" -TimeoutSec 20
    $mapSaved = 0
    $mapRetained = 0
    foreach ($mapBundle in @($mapQueue.maps)) {
        $mapPart = $null
        try {
            if ($mapBundle.id -notmatch '^run-[A-Za-z0-9-]{1,90}$' -or $mapBundle.sha256 -notmatch '^[a-fA-F0-9]{64}$' -or [long]$mapBundle.bytes -lt 1 -or [long]$mapBundle.bytes -gt 50000000) {
                throw 'The phone returned invalid bundle metadata.'
            }
            $mapTarget = [IO.Path]::GetFullPath((Join-Path $mapOutputRoot ($mapBundle.id + '.zip')))
            $mapPrefix = $mapOutputRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
            if (-not $mapTarget.StartsWith($mapPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'The destination is outside the chosen map folder.' }
            $mapExpectedHash = ([string]$mapBundle.sha256).ToLowerInvariant()
            $mapAlreadySaved = (Test-Path -LiteralPath $mapTarget -PathType Leaf) -and ((Get-MapChecksum $mapTarget) -eq $mapExpectedHash)
            if (-not $mapAlreadySaved) {
                if (Test-Path -LiteralPath $mapTarget) { throw 'A different file already uses this bundle name. Choose another output folder.' }
                $mapPart = $mapTarget + '.partial-' + [Guid]::NewGuid().ToString('N')
                Write-Host ('Saving ' + $mapBundle.id + '...')
                Invoke-WebRequest -UseBasicParsing -Uri "$mapBase/download/$($mapBundle.id)" -OutFile $mapPart -TimeoutSec 120
                if ((Get-Item -LiteralPath $mapPart).Length -ne [long]$mapBundle.bytes) { throw 'The download size differs. The phone copy is retained.' }
                $mapReceivedHash = Get-MapChecksum $mapPart
                if ($mapReceivedHash -ne $mapExpectedHash) { throw 'The download checksum differs. The phone copy is retained.' }
                $mapDurableStream = [IO.File]::Open($mapPart, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::Read)
                try { $mapDurableStream.Flush($true) } finally { $mapDurableStream.Dispose() }
                Move-Item -LiteralPath $mapPart -Destination $mapTarget
                $mapPart = $null
            }
            # Verify the final saved file, including a previously downloaded copy, before sending any receipt.
            $mapSavedHash = Get-MapChecksum $mapTarget
            if ($mapSavedHash -ne $mapExpectedHash) { throw 'The saved checksum differs. No receipt was sent.' }
            $mapReceipt = @{ id = [string]$mapBundle.id; sha256 = $mapSavedHash } | ConvertTo-Json -Compress
            $mapAck = Invoke-RestMethod -Method Post -Uri "$mapBase/api/ack" -ContentType 'application/json' -Headers @{ Authorization = "Bearer $mapToken" } -Body $mapReceipt -TimeoutSec 20
            if (-not $mapAck.deleted) { throw 'The phone did not confirm removal. Your laptop file is saved; the phone copy may remain.' }
            $mapSaved++
            Write-Host ('Verified and saved: ' + $mapTarget) -ForegroundColor Green
        } catch {
            $mapRetained++
            Write-Warning ($_.Exception.Message + ' No unverified phone copy was removed by this receiver.')
        } finally {
            # Delete only this receiver's unfinished file, never an existing ZIP or a folder.
            if ($mapPart -and (Test-Path -LiteralPath $mapPart -PathType Leaf)) { Remove-Item -LiteralPath $mapPart }
        }
    }
    Write-Host "Saved and acknowledged: $mapSaved. Transfers needing attention: $mapRetained."
    Write-Host ('Maps folder: ' + $mapOutputRoot)
    if ($mapQueue.maps.Count -eq 0) { Write-Host 'No saved maps are waiting on the phone.' }
    if ($NoPause -and $mapRetained -gt 0) { exit 1 }
} catch {
    Write-Warning ($_.Exception.Message + ' Keep the sharing page open on your phone and check that both devices use the same Wi-Fi.')
    if ($NoPause) { exit 1 }
}
if (-not $NoPause) { Read-Host 'Press Enter to close' | Out-Null }
