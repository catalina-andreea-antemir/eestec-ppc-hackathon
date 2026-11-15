<#
.SYNOPSIS
  Comenzile scurte pentru demo-ul live F11 (docs/demo/demo-script.md). Fiecare pas e independent.
.DESCRIPTION
  Refoloseste biblioteca testelor de haos (tests/chaos/lib/chaos.ps1): login cu parolele din .env (fara afisare),
  curl.exe cu -k, SQL ca sa in containerul mssql (doar SELECT aici). Datele create au prefixul RO-DEMO / demo-.
.EXAMPLE
  .\docs\demo\demo.ps1 duplicat                       # pas 1: lot nou 201, acelasi lot 200 duplicate:true
  .\docs\demo\demo.ps1 criptare -File .\sample-data\connection-docs\cerere-racordare-fictiv-001.pdf
  .\docs\demo\demo.ps1 acces-interzis                 # pas 2: 403 + asteapta alerta 4 in alert-webhook
  .\docs\demo\demo.ps1 advisor                        # pas 6: recomandarile storage-advisor
  .\docs\demo\demo.ps1 advisor -RunBenchmark          # ruleaza intai benchmark-ul (sincron, 1-3 minute)
  .\docs\demo\demo.ps1 trace -TraceId <X-Trace-Id>    # liniile de log din Loki pentru un trace
  .\docs\demo\demo.ps1 retea-start | retea-taie | retea-reia | retea-stop   # pas 5, toxiproxy pe https://localhost:8443
#>
param(
  [Parameter(Mandatory = $true, Position = 0)]
  [ValidateSet('duplicat', 'criptare', 'acces-interzis', 'advisor', 'trace', 'retea-start', 'retea-taie', 'retea-reia', 'retea-stop')]
  [string]$Pas,
  [string]$File,
  [string]$TraceId,
  [switch]$RunBenchmark,
  # Limita de banda pentru upload prin toxiproxy, KB/s (0 = fara limita).
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

    # ---- Pas 1: idempotenta loturilor de citiri ---------------------------------------------------------------------
    'duplicat' {
      $meter = 'RO-MUN-DEMO' + (Get-Date -Format 'HHmmss')
      $day = [datetime]::SpecifyKind((Get-Date).ToUniversalTime().Date.AddDays(-1), 'Utc')
      $b = New-MeterBatch $meter $day 96 'Muntenia'
      Write-Host "contor $meter, 96 citiri (o zi la 15 min), Idempotency-Key: $($b.Key)" -ForegroundColor Cyan
      $r1 = Send-MeterBatch $b
      Show-Response 'POST /meter-readings (prima trimitere)' $r1
      $r2 = Send-MeterBatch $b
      Show-Response 'POST /meter-readings (retrimis, aceeasi cheie)' $r2
      $same = $r1.Json.fileId -and ($r1.Json.fileId -eq $r2.Json.fileId)
      Write-Host "acelasi fileId: $same" -ForegroundColor $(if ($same) { 'Green' } else { 'Red' })
      $row = (Invoke-Sql "SELECT COUNT(*), COUNT(DISTINCT reading_ts), COUNT(DISTINCT file_id) FROM dbo.meter_readings WHERE meter_id = '$meter';" 'FileGridMeta' | Select-Object -Last 1).Split(',')
      Write-Host "FileGridMeta.dbo.meter_readings: $($row[0]) citiri, $($row[1]) momente distincte, $($row[2]) fisier(e)  (asteptat 96 / 96 / 1)" -ForegroundColor Cyan
    }

    # ---- Pas 2: documentul criptat in FileGridStorage, descarcat identic --------------------------------------------
    'criptare' {
      if (-not $File -or -not (Test-Path $File)) { throw 'Da fisierul incarcat din UI: -File .\sample-data\connection-docs\<nume>.pdf' }
      $name = Split-Path $File -Leaf
      $local = Get-Sha256 $File
      # La CONNECTION_DOC numele e criptat, deci `q` nu se aplica: se cauta in documentele din ultimele 2 ore.
      $from = (Get-Date).ToUniversalTime().AddHours(-2).ToString('yyyy-MM-ddTHH:mm:ssZ', $Inv)
      $s = Invoke-Api -Path "/files?category=CONNECTION_DOC&size=100&from=$from" -User 'racordari'
      $doc = @($s.Json.items | Where-Object { $_.originalName -eq $name } | Sort-Object createdAt -Descending) | Select-Object -First 1
      if (-not $doc) { throw "Nu gasesc '$name' in documentele de racordare (l-ai incarcat din UI ca racordari?)" }
      Write-Host "document $name  id $($doc.id)  storage $($doc.storage)  encrypted $($doc.encrypted)" -ForegroundColor Cyan
      Write-Host "  sha256 local (original)      $local"
      Write-Host "  sha256Plain din metadate     $($doc.sha256Plain)"
      Write-Host "  sha256 stocat (criptat)      $($doc.sha256)"
      $meta = (Invoke-Sql "SELECT storage_type, storage_key, encrypted, LEFT(original_filename, 32) FROM dbo.files WHERE id = '$($doc.id)';" 'FileGridMeta' | Select-Object -Last 1).Split(',')
      Write-Host "FileGridMeta.dbo.files: storage $($meta[0]), encrypted $($meta[2]), original_filename in baza: $($meta[3])... (si numele e criptat)" -ForegroundColor Cyan
      $raw = (Invoke-Sql ("SELECT TOP 1 CONVERT(varchar(34), SUBSTRING(c.chunk_data, 1, 16), 2) FROM dbo.blob_versions v " +
          "JOIN dbo.blob_chunks c ON c.storage_key = v.version_key WHERE v.logical_key = N'$($meta[1])' AND v.published = 1 ORDER BY c.chunk_offset;") 'FileGridStorage' | Select-Object -Last 1)
      $head = [IO.File]::ReadAllBytes((Resolve-Path $File).Path)
      Write-Host "primii 16 octeti, fisierul original : $(Get-Hex $head)   ($([Text.Encoding]::ASCII.GetString($head, 0, 8)))"
      Write-Host "primii 16 octeti, FileGridStorage   : $raw   (AES-GCM, nu se vede antetul %PDF)" -ForegroundColor Yellow
      $out = Join-Path $Work "descarcat-$name"
      $d = Invoke-Api -Path "/files/$($doc.id)/download" -User 'racordari' -OutFile $out
      $dl = Get-Sha256 $out
      Write-Host "GET /files/{id}/download (racordari) -> HTTP $($d.Status), X-Content-SHA256 $($d.Headers['X-Content-SHA256'])"
      Write-Host "sha256 fisier descarcat = original: $($dl -eq $local)" -ForegroundColor $(if ($dl -eq $local) { 'Green' } else { 'Red' })
    }

    # ---- Pas 2 (continuare): acces cu rol gresit -> 403 + alerta 4 ---------------------------------------------------
    'acces-interzis' {
      $since = (Get-Date).ToUniversalTime()
      $r = Invoke-Api -Path '/files?category=CONNECTION_DOC&size=1' -User 'dispecer'
      Show-Response 'dispecer: GET /files?category=CONNECTION_DOC' $r
      Write-Host 'astept alerta 4 (FileGridUnexpectedDocumentAccess) in alert-webhook (maxim 90 s)...' -ForegroundColor Cyan
      $sw = [Diagnostics.Stopwatch]::StartNew()
      $found = $null
      while ($sw.Elapsed.TotalSeconds -lt 90 -and -not $found) {
        Start-Sleep -Seconds 3
        $logs = Invoke-Compose -Arguments @('logs', '--no-color', '-t', '--since', $since.ToString('yyyy-MM-ddTHH:mm:ssZ', $Inv), 'alert-webhook') -Quiet
        $found = $logs | Where-Object { $_ -match 'status=firing' -and $_ -match 'business_alerts=[0-9,]*4' } | Select-Object -First 1
      }
      if ($found) { Write-Host "dupa $([math]::Round($sw.Elapsed.TotalSeconds)) s: $found" -ForegroundColor Green }
      else { Write-Host 'nicio notificare noua in 90 s (alerta era deja firing din ultimele 5 minute? vezi docs/demo/demo-script.md)' -ForegroundColor Red }
    }

    # ---- Pas 6: benchmark + storage-advisor -------------------------------------------------------------------------
    'advisor' {
      if ($RunBenchmark) {
        Write-Host 'POST /admin/benchmark/run (admin, sincron)...' -ForegroundColor Cyan
        $b = Invoke-Api -Method POST -Path '/admin/benchmark/run' -User 'admin' -TimeoutSec 320
        Write-Host "benchmark -> HTTP $($b.Status) $(if ($b.Json.code) { $b.Json.code }) ($([math]::Round($b.Ms / 1000)) s)"
      }
      $a = Invoke-Api -Path '/stats/storage-advisor' -User 'dispecer'
      Show-Response 'GET /stats/storage-advisor' ([pscustomobject]@{ Status = $a.Status; Headers = $a.Headers; Json = $null })
      $a.Json.recommendations | Format-Table category, recommendedStorage, currentPolicyStorage, matchesPolicy -AutoSize | Out-String | Write-Host
      Write-Host "basis: $($a.Json.basis | ConvertTo-Json -Compress)"
      Write-Host "interpretation: $($a.Json.interpretation)" -ForegroundColor Cyan
    }

    # ---- Meniu: urmarirea unei operatii in Loki ---------------------------------------------------------------------
    'trace' {
      if (-not $TraceId) { throw 'Da -TraceId <valoarea header-ului X-Trace-Id>' }
      $q = '{job="filegrid-api"} | json | traceId="' + $TraceId + '"'
      # Interogarea trece in base64 si printr-un fisier (PowerShell 5.1 strica ghilimelele din argumentele native).
      $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($q))
      $out = Invoke-InContainer 'api' ("echo $b64 | base64 -d > /tmp/demo-loki.q && curl -sG http://loki:3100/loki/api/v1/query_range " +
          '--data-urlencode query@/tmp/demo-loki.q --data-urlencode since=1h --data-urlencode limit=20; rm -f /tmp/demo-loki.q')
      $json = ($out -join '') | ConvertFrom-Json
      if (-not $json.data.result) { Write-Host 'nicio linie in Loki pentru acest trace (cererile reusite fara log nu apar)' -ForegroundColor Yellow }
      foreach ($st in $json.data.result) {
        foreach ($v in $st.values) {
          $l = $v[1] | ConvertFrom-Json
          Write-Host "$($l.'@timestamp')  replica $($st.stream.replica)  $($l.level)  $($l.message)"
        }
      }
    }

    # ---- Pas 5: retea degradata intre browser si nginx (toxiproxy, profil chaos) ------------------------------------
    'retea-start' {
      [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'up', '-d', 'toxiproxy'))
      if ((Wait-ServiceHealthy 'toxiproxy' 60) -lt 0) { throw 'toxiproxy nu a devenit healthy' }
      [void](Invoke-Toxi DELETE '/proxies/nginx')
      $p = Invoke-Toxi POST '/proxies' '{"name":"nginx","listen":"0.0.0.0:8443","upstream":"nginx:443","enabled":true}'
      Write-Host "proxy nginx (127.0.0.1:8443 -> nginx:443): HTTP $($p.Status)"
      if ($RateKBps -gt 0) {
        Add-Toxic 'nginx' 'upload-lent' 'bandwidth' @{ rate = $RateKBps } 1.0 'upstream'
        Write-Host "limita upload: $RateKBps KB/s (toxic bandwidth, sensul client -> nginx)"
      }
      Write-Host 'deschide https://localhost:8443 (accepta certificatul), login teren, Upload video' -ForegroundColor Cyan
    }
    'retea-taie' {
      Set-ProxyEnabled 'nginx' $false
      Write-Host 'retea TAIATA (proxy nginx dezactivat: conexiunile se inchid, cele noi sunt refuzate)' -ForegroundColor Red
    }
    'retea-reia' {
      Set-ProxyEnabled 'nginx' $true
      Write-Host 'retea REPORNITA (UI reia singur de la nextChunk)' -ForegroundColor Green
    }
    'retea-stop' {
      [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'rm', '-sf', 'toxiproxy'))
      Write-Host 'toxiproxy oprit si sters; https://localhost ramane neatins' -ForegroundColor Green
    }
  }
} finally {
  Remove-Item -Recurse -Force $Work -ErrorAction SilentlyContinue
}
