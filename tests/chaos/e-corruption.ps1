<#
.SYNOPSIS
  F9 e) Corruption: one byte changed directly on the FTP disk (dd conv=notrunc) -> GET /files/{id}/verify returns 409
  FILE_CORRUPT (so does the download, which is verified before sending); once the original byte is restored,
  verify -> ok:true and the download has the correct hash.
.EXAMPLE
  .\tests\chaos\e-corruption.ps1
#>
param([int]$Offset = 1000)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'e) Corruption: one byte changed on FTP -> 409 FILE_CORRUPT, then ok:true'

$path = $null
$original = $null
$corrupted = $false

function Read-ByteAt([string]$File, [int]$At) {
  $v = (Invoke-InContainer 'ftp' "od -An -tu1 -j $At -N 1 $File") -join ''
  return [int]$v.Trim()
}

# Writes one byte in place (no truncation). The value goes through base64 to keep quotes off the command line.
function Write-ByteAt([string]$File, [int]$At, [int]$Value) {
  $b64 = [Convert]::ToBase64String([byte[]]@($Value))
  [void](Invoke-InContainer 'ftp' "echo $b64 | base64 -d | dd of=$File bs=1 seek=$At count=1 conv=notrunc 2>/dev/null")
}

try {
  Write-Step 'setup: small video uploaded to FTP'
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)
  $f = New-RandomFile "$RunId-inspectie-corupere.mp4" (64KB)
  $sha = Get-Sha256 $f
  $u = Send-Video $f
  [void](Assert-Check 'video -> 201 on FTP' ($u.Ok -and $u.Complete.storage -eq 'FTP') "$(Format-Response $u.Response), storage $($u.Complete.storage)")
  $id = $u.Complete.id
  # Chunked uploads on FTP live in /srv/ftp/u/<uploadId>/c1..cN + manifest (FtpAdapter).
  $path = "/srv/ftp/u/$($u.Init.uploadId)/c1"
  $ls = (Invoke-InContainer 'ftp' "ls -l $path") -join ''
  [void](Assert-Check "chunk exists on disk: $path" ($ls -match 'c1') $ls.Trim())
  $v0 = Get-Verify $id
  [void](Assert-Check 'verify before -> 200 ok:true' ($v0.Status -eq 200 -and $v0.Json.ok -eq $true) (Format-Response $v0))

  Write-Step "break: byte at offset $Offset of chunk 1 (dd conv=notrunc)"
  $original = Read-ByteAt $path $Offset
  $corrupted = $true
  Write-ByteAt $path $Offset ($original -bxor 0xFF)
  $now = Read-ByteAt $path $Offset
  Write-Info "byte $Offset : $original -> $now"

  Write-Step 'checking integrity'
  $v1 = Get-Verify $id
  $det = if ($v1.Json -and $v1.Json.details) { "expected $($v1.Json.details.expectedSha256.Substring(0, 12))..., actual $($v1.Json.details.actualSha256.Substring(0, 12))..." } else { '' }
  [void](Assert-Check 'verify -> 409 FILE_CORRUPT' ($v1.Status -eq 409 -and $v1.Json.code -eq 'FILE_CORRUPT') "$(Format-Response $v1); $det")
  [void](Assert-Check '409 details: actualSha256 differs from expectedSha256 (= upload hash)' ($v1.Json.details.expectedSha256 -eq $sha -and $v1.Json.details.actualSha256 -ne $sha))
  $d1 = Get-Download $id
  [void](Assert-Check 'download (small file, verified before sending) -> 409 FILE_CORRUPT' ($d1.Status -eq 409 -and $d1.Json.code -eq 'FILE_CORRUPT') (Format-Response $d1))

  Wait-Demo
  Write-Step 'repair: restoring the original byte'
  Write-ByteAt $path $Offset $original
  $corrupted = $false
  Write-Info "byte $Offset : $(Read-ByteAt $path $Offset)"

  Write-Step 'checking recovery'
  $v2 = Get-Verify $id
  [void](Assert-Check 'verify after repair -> 200 ok:true' ($v2.Status -eq 200 -and $v2.Json.ok -eq $true -and $v2.Json.actualSha256 -eq $sha) (Format-Response $v2))
  [void](Test-DownloadOk $id $sha 'repaired video')
} catch {
  [void](Assert-Check 'scenario e ran without exceptions' $false (Format-Error $_))
} finally {
  if ($corrupted -and $path -and $null -ne $original) {
    Write-Step 'repair (finally): restoring the original byte'
    try { Write-ByteAt $path $Offset $original } catch { Write-Host "  repair failed: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'e-corruption')
