<#
.SYNOPSIS
  Prints storage health (GET /admin/health, ADMIN role) once per second, for a second terminal during the demo
  (the UI has no page for /admin/health). Ctrl+C to stop.
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
