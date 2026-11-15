<#
.SYNOPSIS
  Short commands for the live demo (docs/demo/demo-script.md). Each step is independent.
.DESCRIPTION
  Reuses the chaos test library (tests/chaos/lib/chaos.ps1): login with the .env passwords (never printed),
  curl.exe with -k, SQL as sa inside the mssql container (SELECT only here). Created data is prefixed RO-DEMO / demo-.
.EXAMPLE
  .\docs\demo\demo.ps1 duplicat                       # step 1: new batch 201, same batch 200 duplicate:true
  .\docs\demo\demo.ps1 criptare -File .\sample-data\connection-docs\cerere-racordare-fictiv-001.pdf
  .\docs\demo\demo.ps1 acces-interzis                 # step 2: 403 + waits for alert 4 in alert-webhook
  .\docs\demo\demo.ps1 advisor                        # step 6: storage-advisor recommendations
  .\docs\demo\demo.ps1 advisor -RunBenchmark          # runs the benchmark first (synchronous, 1-3 minutes)
  .\docs\demo\demo.ps1 trace -TraceId <X-Trace-Id>    # Loki log lines for one trace
  .\docs\demo\demo.ps1 retea-start | retea-taie | retea-reia | retea-stop   # step 5, toxiproxy on https://localhost:8443
#>
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet('duplicat', 'criptare', 'acces-interzis', 'advisor', 'trace', 'retea-start', 'retea-taie', 'retea-reia', 'retea-stop')]
  [string]$Pas,
  [string]$File,
  [string]$TraceId,
  [switch]$RunBenchmark,
  # Upload bandwidth cap through toxiproxy, KB/s (0 = unlimited).
  [int]$RateKBps = 20480
)

if (-not $env:CHAOS_RUN_ID) { $env:CHAOS_RUN_ID = 'demo-' + (Get-Date -Format 'MMddHHmmss') }
. (Join-Path $PSScriptRoot '..\..\tests\chaos\lib\chaos.ps1')
$Inv = [Globalization.CultureInfo]::InvariantCulture

function Show-Response([string]$Label, $r) {
  $color = if ($r.Status -ge 200 -and $r.Status -lt 300) { 'Green' } else { 'Yellow' }
  Write-Host "$Label -> HTTP $($r.Status)   X-Trace-Id: $($r.Headers['X-Trace-Id'])" -ForegroundColor $color
  if ($r.Json) { Write-Host ('    ' + ($r.Json | ConvertTo-Json -Compress -Depth 4)) }
}

function Get-Hex([byte[]]$Bytes, [int]$Count = 16) {
  return (($Bytes | Select-Object -First $Count) | ForEach-Object { $_.ToString('X2') }) -join ''
}

try {
  switch ($Pas) {

    # ---- Step 1: reading batch idempotency -------------------------------------------------------------------------
    'duplicat' {
      $meter = 'RO-MUN-DEMO' + (Get-Date -Format 'HHmmss')
      $day = [datetime]::SpecifyKind((Get-Date).ToUniversalTime().Date.AddDays(-1), 'Utc')
      $b = New-MeterBatch $meter $day 96 'Muntenia'
      Write-Host "meter $meter, 96 readings (one day at 15 min), Idempotency-Key: $($b.Key)" -ForegroundColor Cyan
      $r1 = Send-MeterBatch $b
      Show-Response 'POST /meter-readings (first send)' $r1
      $r2 = Send-MeterBatch $b
      Show-Response 'POST /meter-readings (resent, same key)' $r2
      $same = $r1.Json.fileId -and ($r1.Json.fileId -eq $r2.Json.fileId)
      Write-Host "same fileId: $same" -ForegroundColor $(if ($same) { 'Green' } else { 'Red' })
      $row = (Invoke-Sql "SELECT COUNT(*), COUNT(DISTINCT reading_ts), COUNT(DISTINCT file_id) FROM dbo.meter_readings WHERE meter_id = '$meter';" 'FileGridMeta' | Select-Object -Last 1).Split(',')
      Write-Host "FileGridMeta.dbo.meter_readings: $($row[0]) readings, $($row[1]) distinct timestamps, $($row[2]) file(s)  (expected 96 / 96 / 1)" -ForegroundColor Cyan
    }

    # ---- Step 2: encrypted document in FileGridStorage, downloaded byte-identical ----------------------------------
    'criptare' {
      if (-not $File -or -not (Test-Path $File)) { throw 'Pass the file uploaded from the UI: -File .\sample-data\connection-docs\<name>.pdf' }
      $name = Split-Path $File -Leaf
      $local = Get-Sha256 $File
      # CONNECTION_DOC names are encrypted, so `q` does not apply: search documents from the last 2 hours instead.
      $from = (Get-Date).ToUniversalTime().AddHours(-2).ToString('yyyy-MM-ddTHH:mm:ssZ', $Inv)
      $s = Invoke-Api -Path "/files?category=CONNECTION_DOC&size=100&from=$from" -User 'racordari'
      $doc = @($s.Json.items | Where-Object { $_.originalName -eq $name } | Sort-Object createdAt -Descending) | Select-Object -First 1
      if (-not $doc) { throw "Cannot find '$name' among the connection documents (was it uploaded from the UI as racordari?)" }
      Write-Host "document $name  id $($doc.id)  storage $($doc.storage)  encrypted $($doc.encrypted)" -ForegroundColor Cyan
      Write-Host "  local sha256 (original)      $local"
      Write-Host "  sha256Plain from metadata    $($doc.sha256Plain)"
      Write-Host "  stored sha256 (encrypted)    $($doc.sha256)"
      $meta = (Invoke-Sql "SELECT storage_type, storage_key, encrypted, LEFT(original_filename, 32) FROM dbo.files WHERE id = '$($doc.id)';" 'FileGridMeta' | Select-Object -Last 1).Split(',')
      Write-Host "FileGridMeta.dbo.files: storage $($meta[0]), encrypted $($meta[2]), original_filename in the database: $($meta[3])... (the name is encrypted too)" -ForegroundColor Cyan
      $raw = (Invoke-Sql ("SELECT TOP 1 CONVERT(varchar(34), SUBSTRING(c.chunk_data, 1, 16), 2) FROM dbo.blob_versions v " +
          "JOIN dbo.blob_chunks c ON c.storage_key = v.version_key WHERE v.logical_key = N'$($meta[1])' AND v.published = 1 ORDER BY c.chunk_offset;") 'FileGridStorage' | Select-Object -Last 1)
      $head = [IO.File]::ReadAllBytes((Resolve-Path $File).Path)
      Write-Host "first 16 bytes, original file   : $(Get-Hex $head)   ($([Text.Encoding]::ASCII.GetString($head, 0, 8)))"
      Write-Host "first 16 bytes, FileGridStorage : $raw   (AES-GCM, no %PDF header visible)" -ForegroundColor Yellow
      $out = Join-Path $Work "descarcat-$name"
      $d = Invoke-Api -Path "/files/$($doc.id)/download" -User 'racordari' -OutFile $out
      $dl = Get-Sha256 $out
      Write-Host "GET /files/{id}/download (racordari) -> HTTP $($d.Status), X-Content-SHA256 $($d.Headers['X-Content-SHA256'])"
      Write-Host "downloaded file sha256 = original: $($dl -eq $local)" -ForegroundColor $(if ($dl -eq $local) { 'Green' } else { 'Red' })
    }

    # ---- Step 2 (cont.): access with the wrong role -> 403 + alert 4 -----------------------------------------------
    'acces-interzis' {
      $since = (Get-Date).ToUniversalTime()
      $r = Invoke-Api -Path '/files?category=CONNECTION_DOC&size=1' -User 'dispecer'
      Show-Response 'dispecer: GET /files?category=CONNECTION_DOC' $r
      Write-Host 'waiting for alert 4 (FileGridUnexpectedDocumentAccess) in alert-webhook (up to 90 s)...' -ForegroundColor Cyan
      $sw = [Diagnostics.Stopwatch]::StartNew()
      $found = $null
      while ($sw.Elapsed.TotalSeconds -lt 90 -and -not $found) {
        Start-Sleep -Seconds 3
        $logs = Invoke-Compose -Arguments @('logs', '--no-color', '-t', '--since', $since.ToString('yyyy-MM-ddTHH:mm:ssZ', $Inv), 'alert-webhook') -Quiet
        $found = $logs | Where-Object { $_ -match 'status=firing' -and $_ -match 'business_alerts=[0-9,]*4' } | Select-Object -First 1
      }
      if ($found) { Write-Host "after $([math]::Round($sw.Elapsed.TotalSeconds)) s: $found" -ForegroundColor Green }
      else { Write-Host 'no new notification within 90 s (was the alert already firing in the last 5 minutes? see docs/demo/demo-script.md)' -ForegroundColor Red }
    }

    # ---- Step 6: benchmark + storage-advisor -----------------------------------------------------------------------
    'advisor' {
      if ($RunBenchmark) {
        Write-Host 'POST /admin/benchmark/run (admin, synchronous)...' -ForegroundColor Cyan
        $b = Invoke-Api -Method POST -Path '/admin/benchmark/run' -User 'admin' -TimeoutSec 320
        Write-Host "benchmark -> HTTP $($b.Status) $(if ($b.Json.code) { $b.Json.code }) ($([math]::Round($b.Ms / 1000)) s)"
      }
      $a = Invoke-Api -Path '/stats/storage-advisor' -User 'dispecer'
      Show-Response 'GET /stats/storage-advisor' ([pscustomobject]@{ Status = $a.Status; Headers = $a.Headers; Json = $null })
      $a.Json.recommendations | Format-Table category, recommendedStorage, currentPolicyStorage, matchesPolicy -AutoSize | Out-String | Write-Host
      Write-Host "basis: $($a.Json.basis | ConvertTo-Json -Compress)"
      Write-Host "interpretation: $($a.Json.interpretation)" -ForegroundColor Cyan
    }

    # ---- Menu: follow one operation in Loki ------------------------------------------------------------------------
    'trace' {
      if (-not $TraceId) { throw 'Pass -TraceId <value of the X-Trace-Id header>' }
      $q = '{job="filegrid-api"} | json | traceId="' + $TraceId + '"'
      # The query goes through base64 and a file (PowerShell 5.1 mangles quotes in native command arguments).
      $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($q))
      $out = Invoke-InContainer 'api' ("echo $b64 | base64 -d > /tmp/demo-loki.q && curl -sG http://loki:3100/loki/api/v1/query_range " +
          '--data-urlencode query@/tmp/demo-loki.q --data-urlencode since=1h --data-urlencode limit=20; rm -f /tmp/demo-loki.q')
      $json = ($out -join '') | ConvertFrom-Json
      if (-not $json.data.result) { Write-Host 'no Loki lines for this trace (successful requests that log nothing do not show up)' -ForegroundColor Yellow }
      foreach ($st in $json.data.result) {
        foreach ($v in $st.values) {
          $l = $v[1] | ConvertFrom-Json
          Write-Host "$($l.'@timestamp')  replica $($st.stream.replica)  $($l.level)  $($l.message)"
        }
      }
    }

    # ---- Step 5: degraded network between browser and nginx (toxiproxy, chaos profile) -----------------------------
    'retea-start' {
      [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'up', '-d', 'toxiproxy'))
      if ((Wait-ServiceHealthy 'toxiproxy' 60) -lt 0) { throw 'toxiproxy did not become healthy' }
      [void](Invoke-Toxi DELETE '/proxies/nginx')
      $p = Invoke-Toxi POST '/proxies' '{"name":"nginx","listen":"0.0.0.0:8443","upstream":"nginx:443","enabled":true}'
      Write-Host "proxy nginx (127.0.0.1:8443 -> nginx:443): HTTP $($p.Status)"
      if ($RateKBps -gt 0) {
        Add-Toxic 'nginx' 'upload-lent' 'bandwidth' @{ rate = $RateKBps } 1.0 'upstream'
        Write-Host "upload cap: $RateKBps KB/s (bandwidth toxic, client -> nginx direction)"
      }
      Write-Host 'open https://localhost:8443 (accept the certificate), log in as teren, upload a drone video' -ForegroundColor Cyan
    }
    'retea-taie' {
      Set-ProxyEnabled 'nginx' $false
      Write-Host 'network CUT (nginx proxy disabled: open connections close, new ones are refused)' -ForegroundColor Red
    }
    'retea-reia' {
      Set-ProxyEnabled 'nginx' $true
      Write-Host 'network RESTORED (the UI resumes on its own from nextChunk)' -ForegroundColor Green
    }
    'retea-stop' {
      [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'rm', '-sf', 'toxiproxy'))
      Write-Host 'toxiproxy stopped and removed; https://localhost is untouched' -ForegroundColor Green
    }
  }
} finally {
  Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
