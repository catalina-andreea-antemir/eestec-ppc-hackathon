<#
.SYNOPSIS
  F9 d) Latenta si erori intermitente prin toxiproxy pe un storage (S3 si/sau FileGridStorage): cu latenta si cu
  erori scurte utilizatorul primeste in continuare 201/200 (retry C11 la citiri, rezerva la scrieri); la eroare
  persistenta circuitul se deschide si scrierile trec pe rezerva; dupa reparare revin pe principal.
.DESCRIPTION
  Porneste modul chaos: toxiproxy (profilul chaos) si api recreat cu infra/docker/docker-compose.chaos.yml, ca S3 si
  FileGridStorage sa treaca prin proxy. La final (si la esec) readuce api pe configuratia normala si opreste toxiproxy.
  Tinte: s3 -> OUTAGE_RECORD (principal S3, rezerva DB); db -> METER_READING (principal DB, rezerva S3).
.EXAMPLE
  .\tests\chaos\d-toxiproxy.ps1
  .\tests\chaos\d-toxiproxy.ps1 -Targets s3 -FlapOnMs 500 -FlapSeconds 30
#>
param([string[]]$Targets = @('s3', 'db'), [int]$LatencyMs = 400, [int]$FlapOnMs = 150, [int]$FlapOffMs = 2200, [int]$FlapSeconds = 15)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title "d) Toxiproxy: latenta, erori intermitente si eroare persistenta ($($Targets -join ', '))"

$override = Join-Path $Root 'infra\docker\docker-compose.chaos.yml'
# C14: stack-ul poate rula cu mai multe replici api; recrearea pastreaza numarul lor.
$replicas = Get-ApiReplicas
$chaosOn = $false
$meterSeq = 0

# Valoarea variabilei pe toate replicile api (distincte, separate prin virgula); asa se vede si o replica ramasa
# pe configuratia veche.
function Get-ApiEnv([string]$Name) {
  $vals = foreach ($id in (Get-ContainerIds 'api')) {
    $lines = & docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' $id
    $l = $lines | Where-Object { $_ -like "$Name=*" } | Select-Object -First 1
    if ($l) { $l.Substring($Name.Length + 1) } else { '' }
  }
  return (@($vals | Select-Object -Unique) -join ',')
}

# O scriere in categoria tintei. Intoarce status, fileId, storage si hash-ul asteptat.
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

function Restore-Normal {
  Write-Step 'reparare: toxiproxy fara toxice, api inapoi pe configuratia normala, profilul chaos oprit'
  Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue   # jobul de erori intermitente, daca a ramas pornit
  Reset-Toxiproxy
  [void](Invoke-Compose -Arguments @('up', '-d', '--no-deps', '--scale', "api=$replicas", 'api'))
  $sec = Wait-ServiceHealthy 'api' 240
  [void](Assert-Check "api recreat fara toxiproxy si healthy ($replicas replici)" ($sec -ge 0 -and (Get-ApiEnv 'S3_ENDPOINT') -eq 'http://minio:9000') "dupa $sec s, S3_ENDPOINT=$(Get-ApiEnv 'S3_ENDPOINT')")
  [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'rm', '-sf', 'toxiproxy'))
  foreach ($st in 'FTP', 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' 90) }
}

$all = @{
  s3 = [pscustomobject]@{ Name = 's3'; Proxy = 's3'; Storage = 'S3'; Fallback = 'DB'; Category = 'OUTAGE_RECORD' }
  db = [pscustomobject]@{ Name = 'db'; Proxy = 'storage-db'; Storage = 'DB'; Fallback = 'S3'; Category = 'METER_READING' }
}

try {
  Write-Step 'pregatire: pornesc toxiproxy (profilul chaos) si recreez api prin proxy'
  $chaosOn = $true
  [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'up', '-d', 'toxiproxy'))
  $sec = Wait-ServiceHealthy 'toxiproxy' 60
  [void](Assert-Check 'toxiproxy healthy, API doar pe 127.0.0.1:8474' ($sec -ge 0 -and (Invoke-Toxi GET '/proxies').Status -eq 200) "dupa $sec s")
  [void](Invoke-Compose -Arguments @('-f', $override, '--profile', 'chaos', 'up', '-d', '--no-deps', '--scale', "api=$replicas", 'api'))
  $sec = Wait-ServiceHealthy 'api' 240
  [void](Assert-Check "api recreat prin toxiproxy si healthy ($replicas replici)" ($sec -ge 0 -and (Get-ApiEnv 'S3_ENDPOINT') -eq 'http://toxiproxy:19000') "dupa $sec s, S3_ENDPOINT=$(Get-ApiEnv 'S3_ENDPOINT')")
  foreach ($st in 'FTP', 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' 90) }

  foreach ($name in $Targets) {
    $T = $all[$name]
    if (-not $T) { throw "Tinta necunoscuta: $name (s3 sau db)" }
    Write-Title "d/$name) proxy $($T.Proxy): $($T.Category), principal $($T.Storage), rezerva $($T.Fallback)"
    $base = Send-Write $T 'baza'
    [void](Assert-Check "scriere de baza -> 201 pe $($T.Storage)" ($base.Status -eq 201 -and $base.Storage -eq $T.Storage) "$(Format-Response $base.R), storage $($base.Storage), $($base.Ms) ms")

    # --- d1: latenta ---
    Write-Step "stric (1): latenta $LatencyMs ms +-100 ms pe $($T.Proxy)"
    Add-Toxic $T.Proxy 'chaos-latency' 'latency' @{ latency = $LatencyMs; jitter = 100 }
    $ws = @(1..3 | ForEach-Object { Send-Write $T "lat$_" })
    $okW = @($ws | Where-Object { $_.Status -eq 201 -and $_.Storage -eq $T.Storage }).Count
    [void](Assert-Check "cu latenta: scrierile -> 201 tot pe $($T.Storage)" ($okW -eq 3) "$okW/3, timpi $(($ws | ForEach-Object { $_.Ms }) -join '/') ms (fara latenta: $($base.Ms) ms)")
    $d = Get-Download $base.Id
    [void](Assert-Check 'cu latenta: descarcarea -> 200, hash corect' ($d.Status -eq 200 -and $d.Sha256 -eq $base.Sha) "$(Format-Response $d), $($d.Ms) ms")
    Remove-Toxic $T.Proxy 'chaos-latency'

    # --- d2: erori intermitente ---
    # Un job de fundal porneste toxicul reset_peer (toate conexiunile, inclusiv cele din pool) $FlapOnMs ms, apoi il
    # (implicit 150 ms: sub bugetul de retry C11, 3 incercari cu backoff 100 ms -> 200 ms; cu ferestre mai lungi
    # decat ~0,3 s o citire poate primi 503 + Retry-After, conform contractului)
    # scoate $FlapOffMs ms, in bucla; intre timp alternam citiri si scrieri $FlapSeconds secunde.
    Write-Step "stric (2): erori intermitente pe $($T.Proxy): reset_peer pornit $FlapOnMs ms / oprit $FlapOffMs ms, $FlapSeconds s"
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
      [void]$readResults.Add((Get-Download $base.Id))
      [void]$ws.Add((Send-Write $T "flap$i"))
      Start-Sleep -Milliseconds 150
    }
    Stop-Job $flap; Remove-Job $flap -Force
    Remove-Toxic $T.Proxy 'chaos-flap'
    $nR = $readResults.Count; $nW = $ws.Count
    $okR = @($readResults | Where-Object { $_.Status -eq 200 -and $_.Sha256 -eq $base.Sha }).Count
    $codesR = ($readResults | Group-Object Status | ForEach-Object { "$($_.Count)x$($_.Name)" }) -join ', '
    [void](Assert-Check 'erori scurte la citire ascunse de retry: descarcari 200 cu hash corect' ($okR -eq $nR) "$okR/$nR ($codesR)")
    $ok201 = @($ws | Where-Object { $_.Status -eq 201 }).Count
    $where = ($ws | Where-Object { $_.Status -eq 201 } | Group-Object Storage | ForEach-Object { "$($_.Count)x$($_.Name)" }) -join ', '
    $codesW = ($ws | Group-Object Status | ForEach-Object { "$($_.Count)x$($_.Name)" }) -join ', '
    [void](Assert-Check 'erori scurte la scriere: utilizatorul primeste 201 (principal sau rezerva)' ($ok201 -eq $nW) "$ok201/$nW ($codesW); unde: $where")
    $fallbackHits = @($ws | Where-Object { $_.Status -eq 201 -and $_.Storage -eq $T.Fallback })
    foreach ($w in $fallbackHits | Select-Object -First 1) { [void](Test-DownloadOk $w.Id $w.Sha "scriere mutata pe $($T.Fallback) in timpul erorilor") }
    Write-Info "health: $(Format-StorageState (Get-StorageState $T.Storage))"
    [void](Assert-StorageState $T.Storage 'UP' 'CLOSED' 60)

    # --- d3: eroare persistenta ---
    Write-Step "stric (3): eroare persistenta, proxy $($T.Proxy) dezactivat (conexiuni refuzate)"
    Set-ProxyEnabled $T.Proxy $false
    [void](Assert-StorageState $T.Storage 'DOWN' 'OPEN' 45)
    $ws = @(1..3 | ForEach-Object { Send-Write $T "down$_" })
    $okF = @($ws | Where-Object { $_.Status -eq 201 -and $_.Storage -eq $T.Fallback }).Count
    [void](Assert-Check "eroare persistenta: scrierile -> 201 pe rezerva $($T.Fallback)" ($okF -eq 3) "$okF/3, $(($ws | ForEach-Object { "$($_.Status) $($_.Storage) $($_.Ms)ms" }) -join '; ')")
    $d = Get-Download $base.Id
    [void](Assert-Check "fisierul de pe $($T.Storage): 503 cu Retry-After cat timp e cazut" ($d.Status -eq 503 -and $d.Headers['Retry-After']) (Format-Response $d))

    Write-Step "repar: toxiproxy /reset (proxy activ, fara toxice)"
    Reset-Toxiproxy
    [void](Assert-StorageState $T.Storage 'UP' 'CLOSED' 60)
    $after = Send-Write $T 'dupa'
    [void](Assert-Check "dupa reparare: scrierea -> 201 din nou pe $($T.Storage)" ($after.Status -eq 201 -and $after.Storage -eq $T.Storage) "$(Format-Response $after.R), storage $($after.Storage)")
    [void](Test-DownloadOk $base.Id $base.Sha "fisierul de baza ($($T.Storage))")
    [void](Test-DownloadOk $ws[0].Id $ws[0].Sha "scrierea de pe rezerva ($($T.Fallback))")
  }
} catch {
  [void](Assert-Check 'scenariul d rulat fara exceptii' $false (Format-Error $_))
} finally {
  if ($chaosOn) {
    try { Restore-Normal } catch { [void](Assert-Check 'revenire la configuratia normala' $false (Format-Error $_)) }
  }
}
exit (Complete-Scenario 'd-toxiproxy')
