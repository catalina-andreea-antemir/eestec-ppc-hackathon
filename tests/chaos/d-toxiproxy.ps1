<#
.SYNOPSIS
  F9 d) Latency and intermittent errors through toxiproxy on a storage backend (S3 and/or FileGridStorage): with
  latency and short errors the user still gets 201/200 (C11 retry on reads, fallback on writes); on a persistent
  error the circuit opens and writes move to the fallback; after repair they return to the primary.
.DESCRIPTION
  Enables chaos mode: toxiproxy (chaos profile) and api recreated with infra/docker/docker-compose.chaos.yml, so S3 and
  FileGridStorage go through the proxy. At the end (and on failure) api is restored to the normal config and toxiproxy stopped.
  Targets: s3 -> OUTAGE_RECORD (primary S3, fallback DB); db -> METER_READING (primary DB, fallback S3).
.EXAMPLE
  .\tests\chaos\d-toxiproxy.ps1
  .\tests\chaos\d-toxiproxy.ps1 -Targets s3 -FlapOnMs 500 -FlapSeconds 30
#>
param([string[]]$Targets = @('s3', 'db'), [int]$LatencyMs = 400, [int]$FlapOnMs = 150, [int]$FlapOffMs = 2200, [int]$FlapSeconds = 15)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title "d) Toxiproxy: latency, intermittent errors and persistent error ($($Targets -join ', '))"

$override = Join-Path $Root 'infra\docker\docker-compose.chaos.yml'
# C14: the stack may run several api replicas; recreating keeps their count.
$replicas = Get-ApiReplicas
$chaosOn = $false
$meterSeq = 0

# The variable's value across all api replicas (distinct, comma-separated), so a replica stuck on the old config
# is visible too.
function Get-ApiEnv([string]$Name) {
  $vals = foreach ($id in (Get-ContainerIds 'api')) {
    $lines = & docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' $id
    $l = $lines | Where-Object { $_ -like "$Name=*" } | Select-Object -First 1
    if ($l) { $l.Substring($Name.Length + 1) } else { '' }
  }
  return (@($vals | Select-Object -Unique) -join ',')
}

# One write in the target's category. Returns status, fileId, storage and the expected hash.
function Send-Write($T, [string]$Label) {
  if ($T.Name -eq 's3') {
    $f = New-OutageCsv "$RunId-avarie-toxi-$Label-$([Guid]::NewGuid().ToString('N').Substring(0, 6)).csv"
    $r = Send-Outage $f
    $storage = if ($r.Json) { $r.Json.storage } else { $null }
    $id = if ($r.Json) { $r.Json.id } else { $null }
    return [pscustomobject]@{ Status = $r.Status; Id = $id; Storage = $storage; Sha = (Get-Sha256 $f); Ms = $r.Ms; R = $r }
  }
  $script:meterSeq++
  $b = New-MeterBatch "$RunId-DOB-$('{0:0000000}' -f $script:meterSeq)" ([datetime]::SpecifyKind([datetime]'2026-10-01', 'Utc')) 96 'Dobrogea'
  $r = Send-MeterBatch $b
  $storage = $null; $id = $null; $sha = $null
  if ($r.Json -and $r.Json.fileId) { $id = $r.Json.fileId; $sha = $r.Json.sha256; $m = Get-FileMeta $id; if ($m.Json) { $storage = $m.Json.storage } }
  return [pscustomobject]@{ Status = $r.Status; Id = $id; Storage = $storage; Sha = $sha; Ms = $r.Ms; R = $r }
}

# Ca un client real (UI-ul F4, simulatorul): la 503 cu Retry-After asteapta si reincearca, de cel mult $Max ori.
# Contractul permite 503 + Retry-After cand o eroare de storage dureaza mai mult decat retry-ul intern C11 (~0,3 s);
# cerinta PPC e ca transferul sa se termine fara interventie manuala, nu ca primul raspuns sa fie mereu 200.
function Invoke-AsClient([scriptblock]$Call, [int]$Max = 4) {
  $retries = 0
  while ($true) {
    $res = & $Call
    $st = if ($res.PSObject.Properties.Name -contains 'R') { $res.R } else { $res }
    $ra = $st.Headers['Retry-After']
    if ($res.Status -ne 503 -or -not $ra -or $retries -ge $Max) { break }
    $retries++
    # Retry-After respectat integral (cat tine circuitul C11 deschis), plafonat la 15 s.
    Start-Sleep -Seconds ([math]::Min([int]$ra, 15))
  }
  $res | Add-Member -NotePropertyName Retries -NotePropertyValue $retries -Force
  return $res
}

function Restore-Normal {
  Write-Step 'repair: toxiproxy without toxics, api back on the normal config, chaos profile stopped'
  Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue   # the flapping job, if still running
  Reset-Toxiproxy
  [void](Invoke-Compose -Arguments @('up', '-d', '--no-deps', '--scale', "api=$replicas", 'api'))
  $sec = Wait-ServiceHealthy 'api' 240
  [void](Assert-Check "api recreated without toxiproxy and healthy ($replicas replicas)" ($sec -ge 0 -and (Get-ApiEnv 'S3_ENDPOINT') -eq 'http://minio:9000') "after $sec s, S3_ENDPOINT=$(Get-ApiEnv 'S3_ENDPOINT')")
  [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'rm', '-sf', 'toxiproxy'))
  foreach ($st in 'FTP', 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' 90) }
}

$all = @{
  s3 = [pscustomobject]@{ Name = 's3'; Proxy = 's3'; Storage = 'S3'; Fallback = 'DB'; Category = 'OUTAGE_RECORD' }
  db = [pscustomobject]@{ Name = 'db'; Proxy = 'storage-db'; Storage = 'DB'; Fallback = 'S3'; Category = 'METER_READING' }
}

try {
  Write-Step 'setup: starting toxiproxy (chaos profile) and recreating api behind the proxy'
  $chaosOn = $true
  [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'up', '-d', 'toxiproxy'))
  $sec = Wait-ServiceHealthy 'toxiproxy' 60
  [void](Assert-Check 'toxiproxy healthy, API only on 127.0.0.1:8474' ($sec -ge 0 -and (Invoke-Toxi GET '/proxies').Status -eq 200) "after $sec s")
  [void](Invoke-Compose -Arguments @('-f', $override, '--profile', 'chaos', 'up', '-d', '--no-deps', '--scale', "api=$replicas", 'api'))
  $sec = Wait-ServiceHealthy 'api' 240
  [void](Assert-Check "api recreated behind toxiproxy and healthy ($replicas replicas)" ($sec -ge 0 -and (Get-ApiEnv 'S3_ENDPOINT') -eq 'http://toxiproxy:19000') "after $sec s, S3_ENDPOINT=$(Get-ApiEnv 'S3_ENDPOINT')")
  foreach ($st in 'FTP', 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' 90) }

  foreach ($name in $Targets) {
    $T = $all[$name]
    if (-not $T) { throw "Unknown target: $name (s3 or db)" }
    Write-Title "d/$name) proxy $($T.Proxy): $($T.Category), primary $($T.Storage), fallback $($T.Fallback)"
    $base = Send-Write $T 'baza'
    [void](Assert-Check "baseline write -> 201 on $($T.Storage)" ($base.Status -eq 201 -and $base.Storage -eq $T.Storage) "$(Format-Response $base.R), storage $($base.Storage), $($base.Ms) ms")

    # --- d1: latency ---
    Write-Step "break (1): latency $LatencyMs ms +-100 ms on $($T.Proxy)"
    Add-Toxic $T.Proxy 'chaos-latency' 'latency' @{ latency = $LatencyMs; jitter = 100 }
    $ws = @(1..3 | ForEach-Object { Send-Write $T "lat$_" })
    $okW = @($ws | Where-Object { $_.Status -eq 201 -and $_.Storage -eq $T.Storage }).Count
    [void](Assert-Check "with latency: writes -> 201, still on $($T.Storage)" ($okW -eq 3) "$okW/3, times $(($ws | ForEach-Object { $_.Ms }) -join '/') ms (without latency: $($base.Ms) ms)")
    $d = Get-Download $base.Id
    [void](Assert-Check 'with latency: download -> 200, correct hash' ($d.Status -eq 200 -and $d.Sha256 -eq $base.Sha) "$(Format-Response $d), $($d.Ms) ms")
    Remove-Toxic $T.Proxy 'chaos-latency'

    # --- d2: intermittent errors ---
    # A background job enables the reset_peer toxic (every connection, pooled ones included) for $FlapOnMs ms, then
    # removes it for $FlapOffMs ms, in a loop; meanwhile reads and writes alternate for $FlapSeconds seconds.
    # Default 150 ms stays within the C11 retry budget (3 attempts, backoff 100 ms -> 200 ms); with windows longer
    # than ~0.3 s a read may get 503 + Retry-After, which the contract allows.
    Write-Step "break (2): intermittent errors on $($T.Proxy): reset_peer on $FlapOnMs ms / off $FlapOffMs ms, $FlapSeconds s"
    $flap = Start-Job -ArgumentList $Curl, $ToxiUrl, $T.Proxy, $FlapOnMs, $FlapOffMs, $Work -ScriptBlock {
      param($curl, $url, $proxy, $on, $off, $work)
      $f = Join-Path $work 'flap.json'; $o = Join-Path $work 'flap.out'
      [IO.File]::WriteAllText($f, '{"name":"chaos-flap","type":"reset_peer","stream":"downstream","toxicity":1,"attributes":{"timeout":0}}')
      $n = 0
      while ($true) {
        & $curl -s -o $o -X POST -H 'Content-Type: application/json' --data-binary "@$f" "$url/proxies/$proxy/toxics" | Out-Null
        $n++
        Start-Sleep -Milliseconds $on
        & $curl -s -o $o -X DELETE "$url/proxies/$proxy/toxics/chaos-flap" | Out-Null
        Start-Sleep -Milliseconds $off
      }
    }
    $readResults = New-Object System.Collections.ArrayList
    $ws = New-Object System.Collections.ArrayList
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $i = 0
    while ($sw.Elapsed.TotalSeconds -lt $FlapSeconds) {
      $i++
      $id = $base.Id; $tt = $T; $lbl = "flap$i"
      [void]$readResults.Add((Invoke-AsClient { Get-Download $id }))
      [void]$ws.Add((Invoke-AsClient { Send-Write $tt $lbl }))
      Start-Sleep -Milliseconds 150
    }
    Stop-Job $flap; Remove-Job $flap -Force
    Remove-Toxic $T.Proxy 'chaos-flap'
    $nR = $readResults.Count; $nW = $ws.Count
    $okR = @($readResults | Where-Object { $_.Status -eq 200 -and $_.Sha256 -eq $base.Sha }).Count
    $codesR = ($readResults | Group-Object Status | ForEach-Object { "$($_.Count)x$($_.Name)" }) -join ', '
    $retR = ($readResults | Measure-Object -Property Retries -Sum).Sum
    $retW = ($ws | Measure-Object -Property Retries -Sum).Sum
    [void](Assert-Check 'short read errors: downloads 200 with correct hash (C11 retry + Retry-After like a client)' ($okR -eq $nR) "$okR/$nR ($codesR); retries after 503+Retry-After: $retR")
    $ok201 = @($ws | Where-Object { $_.Status -eq 201 }).Count
    $where = ($ws | Where-Object { $_.Status -eq 201 } | Group-Object Storage | ForEach-Object { "$($_.Count)x$($_.Name)" }) -join ', '
    $codesW = ($ws | Group-Object Status | ForEach-Object { "$($_.Count)x$($_.Name)" }) -join ', '
    [void](Assert-Check 'short write errors: the user gets 201 (primary or fallback)' ($ok201 -eq $nW) "$ok201/$nW ($codesW); where: $where; retries after 503+Retry-After: $retW")
    $fallbackHits = @($ws | Where-Object { $_.Status -eq 201 -and $_.Storage -eq $T.Fallback })
    foreach ($w in $fallbackHits | Select-Object -First 1) { [void](Test-DownloadOk $w.Id $w.Sha "write moved to $($T.Fallback) during the errors") }
    Write-Info "health: $(Format-StorageState (Get-StorageState $T.Storage))"
    [void](Assert-StorageState $T.Storage 'UP' 'CLOSED' 60)

    # --- d3: persistent error ---
    Write-Step "break (3): persistent error, proxy $($T.Proxy) disabled (connections refused)"
    Set-ProxyEnabled $T.Proxy $false
    [void](Assert-StorageState $T.Storage 'DOWN' 'OPEN' 45)
    $ws = @(1..3 | ForEach-Object { Send-Write $T "down$_" })
    $okF = @($ws | Where-Object { $_.Status -eq 201 -and $_.Storage -eq $T.Fallback }).Count
    [void](Assert-Check "persistent error: writes -> 201 on fallback $($T.Fallback)" ($okF -eq 3) "$okF/3, $(($ws | ForEach-Object { "$($_.Status) $($_.Storage) $($_.Ms)ms" }) -join '; ')")
    $d = Get-Download $base.Id
    [void](Assert-Check "file on $($T.Storage): 503 with Retry-After while it is down" ($d.Status -eq 503 -and $d.Headers['Retry-After']) (Format-Response $d))

    Write-Step "repair: toxiproxy /reset (proxy enabled, no toxics)"
    Reset-Toxiproxy
    [void](Assert-StorageState $T.Storage 'UP' 'CLOSED' 60)
    $after = Send-Write $T 'dupa'
    [void](Assert-Check "after repair: write -> 201 on $($T.Storage) again" ($after.Status -eq 201 -and $after.Storage -eq $T.Storage) "$(Format-Response $after.R), storage $($after.Storage)")
    [void](Test-DownloadOk $base.Id $base.Sha "baseline file ($($T.Storage))")
    [void](Test-DownloadOk $ws[0].Id $ws[0].Sha "write on the fallback ($($T.Fallback))")
  }
} catch {
  [void](Assert-Check 'scenario d ran without exceptions' $false (Format-Error $_))
} finally {
  if ($chaosOn) {
    try { Restore-Normal } catch { [void](Assert-Check 'restore the normal config' $false (Format-Error $_)) }
  }
}
exit (Complete-Scenario 'd-toxiproxy')
