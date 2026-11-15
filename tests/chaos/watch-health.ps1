<#
.SYNOPSIS
  Afiseaza starea storage-urilor (GET /admin/health, rol ADMIN) o data pe secunda, pentru al doilea terminal la demo
  (interfata nu are inca o pagina pentru /admin/health). Ctrl+C pentru oprire.
.EXAMPLE
  .\tests\chaos\watch-health.ps1
#>
param([int]$IntervalSec = 1)
. "$PSScriptRoot\lib\chaos.ps1"
try {
  while ($true) {
    $r = Invoke-Api -Path '/admin/health' -User 'admin' -TimeoutSec 5
    $t = Get-Date -Format 'HH:mm:ss'
    if ($r.Status -eq 200 -and $r.Json) {
      $parts = foreach ($s in $r.Json.storages) {
        $p = "$($s.storage) $($s.status)/$($s.circuitBreaker)"
        if ($s.PSObject.Properties.Name -contains 'detail' -and $s.detail) { $p += " ($($s.detail))" }
        $p
      }
      $color = if ($r.Json.status -eq 'UP') { 'Green' } elseif ($r.Json.status -eq 'DEGRADED') { 'Yellow' } else { 'Red' }
      Write-Host "$t  $($r.Json.status.PadRight(8)) meta $($r.Json.metadataDb.status)  |  $($parts -join '  |  ')" -ForegroundColor $color
    } else {
      Write-Host "$t  /admin/health: $(Format-Response $r)" -ForegroundColor Red
    }
    Start-Sleep -Seconds $IntervalSec
  }
} finally {
  Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
