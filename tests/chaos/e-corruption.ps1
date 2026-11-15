<#
.SYNOPSIS
  F9 e) Corupere: un octet modificat direct pe discul FTP (dd conv=notrunc) -> GET /files/{id}/verify raspunde 409
  FILE_CORRUPT (si descarcarea, verificata inainte de trimitere, la fel); dupa ce octetul original e pus la loc,
  verify -> ok:true si descarcarea are hash-ul corect.
.EXAMPLE
  .\tests\chaos\e-corruption.ps1
#>
param([int]$Offset = 1000)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title 'e) Corupere: un octet schimbat pe FTP -> 409 FILE_CORRUPT, apoi ok:true'

$path = $null
$original = $null
$corrupted = $false

function Read-ByteAt([string]$File, [int]$At) {
  $v = (Invoke-InContainer 'ftp' "od -An -tu1 -j $At -N 1 $File") -join ''
  return [int]$v.Trim()
}

# Scrie un octet pe loc (fara trunchiere). Valoarea trece in base64, fara ghilimele in linia de comanda.
function Write-ByteAt([string]$File, [int]$At, [int]$Value) {
  $b64 = [Convert]::ToBase64String([byte[]]@($Value))
  [void](Invoke-InContainer 'ftp' "echo $b64 | base64 -d | dd of=$File bs=1 seek=$At count=1 conv=notrunc 2>/dev/null")
}

try {
  Write-Step 'pregatire: video mic incarcat pe FTP'
  [void](Assert-StorageState 'FTP' 'UP' 'CLOSED' 60)
  $f = New-RandomFile "$RunId-inspectie-corupere.mp4" (64KB)
  $sha = Get-Sha256 $f
  $u = Send-Video $f
  [void](Assert-Check 'video -> 201 pe FTP' ($u.Ok -and $u.Complete.storage -eq 'FTP') "$(Format-Response $u.Response), storage $($u.Complete.storage)")
  $id = $u.Complete.id
  # Upload-ul in bucati pe FTP: /srv/ftp/u/<uploadId>/c1..cN + manifest (FtpAdapter).
  $path = "/srv/ftp/u/$($u.Init.uploadId)/c1"
  $ls = (Invoke-InContainer 'ftp' "ls -l $path") -join ''
  [void](Assert-Check "bucata exista pe disc: $path" ($ls -match 'c1') $ls.Trim())
  $v0 = Get-Verify $id
  [void](Assert-Check 'verify inainte -> 200 ok:true' ($v0.Status -eq 200 -and $v0.Json.ok -eq $true) (Format-Response $v0))

  Write-Step "stric: octetul de la pozitia $Offset din bucata 1 (dd conv=notrunc)"
  $original = Read-ByteAt $path $Offset
  $corrupted = $true
  Write-ByteAt $path $Offset ($original -bxor 0xFF)
  $now = Read-ByteAt $path $Offset
  Write-Info "octet $Offset : $original -> $now"

  Write-Step 'verific: integritatea'
  $v1 = Get-Verify $id
  $det = if ($v1.Json -and $v1.Json.details) { "asteptat $($v1.Json.details.expectedSha256.Substring(0, 12))..., calculat $($v1.Json.details.actualSha256.Substring(0, 12))..." } else { '' }
  [void](Assert-Check 'verify -> 409 FILE_CORRUPT' ($v1.Status -eq 409 -and $v1.Json.code -eq 'FILE_CORRUPT') "$(Format-Response $v1); $det")
  [void](Assert-Check 'detaliile 409: actualSha256 diferit de expectedSha256 (= hash-ul de la upload)' ($v1.Json.details.expectedSha256 -eq $sha -and $v1.Json.details.actualSha256 -ne $sha))
  $d1 = Get-Download $id
  [void](Assert-Check 'descarcare (fisier mic, verificat inainte de trimitere) -> 409 FILE_CORRUPT' ($d1.Status -eq 409 -and $d1.Json.code -eq 'FILE_CORRUPT') (Format-Response $d1))

  Wait-Demo
  Write-Step 'repar: pun octetul original la loc'
  Write-ByteAt $path $Offset $original
  $corrupted = $false
  Write-Info "octet $Offset : $(Read-ByteAt $path $Offset)"

  Write-Step 'verific revenirea'
  $v2 = Get-Verify $id
  [void](Assert-Check 'verify dupa reparare -> 200 ok:true' ($v2.Status -eq 200 -and $v2.Json.ok -eq $true -and $v2.Json.actualSha256 -eq $sha) (Format-Response $v2))
  [void](Test-DownloadOk $id $sha 'video reparat')
} catch {
  [void](Assert-Check 'scenariul e rulat fara exceptii' $false (Format-Error $_))
} finally {
  if ($corrupted -and $path -and $null -ne $original) {
    Write-Step 'reparare (finally): octetul original inapoi'
    try { Write-ByteAt $path $Offset $original } catch { Write-Host "  reparare esuata: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'e-corruption')
