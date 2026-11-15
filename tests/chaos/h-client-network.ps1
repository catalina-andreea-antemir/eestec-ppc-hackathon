<#
.SYNOPSIS
  F9 h) Retea degradata intre client si nginx: un video incarcat in bucati prin toxiproxy (https://localhost:8443 ->
  nginx:443) cu latenta mare si pierdere aproximata (toxicul timeout pe o parte din conexiuni). Clientul reincearca
  singur bucatile esuate, ca interfata (F4): backoff 1 s, 2 s, 4 s ... maxim 10 s, Retry-After respectat, pana la
  2 minute de la primul esec, resincronizare din GET /uploads/{id} la CHUNK_OUT_OF_ORDER. Upload-ul se termina fara
  interventie manuala, cu SHA-256 final egal cu cel local.
.DESCRIPTION
  Porneste toxiproxy (profilul chaos) daca nu ruleaza, iar proxy-ul `nginx` (0.0.0.0:8443 -> nginx:443) vine din
  tests/chaos/toxiproxy.json (sau se creeaza cu POST /proxies, ca in instructiunile C14). api NU se recreeaza: doar
  drumul client -> nginx e degradat. La final toxicele se scot, iar toxiproxy se opreste daca l-a pornit scriptul.
.EXAMPLE
  .\tests\chaos\h-client-network.ps1
  .\tests\chaos\h-client-network.ps1 -SizeMiB 1130          # 1,1 GB, ca la demo
  .\tests\chaos\h-client-network.ps1 -LatencyMs 2000 -LossToxicity 0.3
#>
param([int]$SizeMiB = 200, [int]$LatencyMs = 1000, [int]$JitterMs = 100, [double]$LossToxicity = 0.2,
      [int]$LossTimeoutMs = 3000, [int]$RetryTimeoutSec = 120, [switch]$KeepToxiproxy)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title "h) Retea client -> nginx degradata (latenta $LatencyMs ms, pierdere ~$([int]($LossToxicity * 100))%): video $SizeMiB MiB in bucati, reluat automat"

$DirectUrl = $BaseUrl
$ClientUrl = if ($env:CHAOS_CLIENT_URL) { $env:CHAOS_CLIENT_URL.TrimEnd('/') } else { 'https://localhost:8443/api/v1' }
$startedToxi = $false
$script:Retries = 0
$script:RetryLog = @{}
$uploadId = $null
$completed = $false

# Reincercare ca in interfata (frontend/filegrid-ui/src/app/core/upload/retry.ts): erori de retea (status 0),
# 429, 502, 503, 504 si 409 UPLOAD_BUSY sunt trecatoare; Retry-After are prioritate; altfel 1, 2, 4, 8, 10 s +-20%.
function Invoke-WithRetry([string]$Op, [scriptblock]$Call) {
  $first = $null; $attempt = 0; $rnd = New-Object Random
  while ($true) {
    $r = $null; $err = $null
    try { $r = & $Call } catch { $err = $_.Exception.Message }
    $busy = $r -and $r.Status -eq 409 -and $r.Json -and $r.Json.code -eq 'UPLOAD_BUSY'
    $transient = $err -or (-not $r) -or $r.Status -eq 0 -or (@(429, 502, 503, 504) -contains $r.Status) -or $busy
    if (-not $transient) { return $r }
    if (-not $first) { $first = Get-Date }
    $attempt++
    $why = if ($err) { $err } elseif ($r.Status -eq 0) { 'conexiune pierduta' } else { Format-Response $r }
    $delay = [int]([math]::Min(10000, 1000 * [math]::Pow(2, $attempt - 1)) * (0.8 + 0.4 * $rnd.NextDouble()))
    $ra = 0
    if ($r -and $r.Headers['Retry-After'] -and [int]::TryParse($r.Headers['Retry-After'], [ref]$ra)) { $delay = $ra * 1000 }
    if (((Get-Date) - $first).TotalMilliseconds + $delay -gt $RetryTimeoutSec * 1000) {
      Write-Info "${Op}: renunt dupa $attempt reincercari ($why)"
      if ($r) { return $r } else { throw "${Op}: $why" }
    }
    $script:Retries++
    $key = if ($r -and $r.Status -gt 0) { "HTTP $($r.Status)" } else { 'retea' }
    $script:RetryLog[$key] = 1 + [int]$script:RetryLog[$key]
    Write-Info ("{0}: {1}, reincerc peste {2:0.0} s (incercarea {3})" -f $Op, $why, ($delay / 1000), ($attempt + 1))
    Start-Sleep -Milliseconds $delay
  }
}

function Get-UploadState([string]$Id) {
  return Invoke-WithRetry 'stare' { Invoke-Api -Path "/uploads/$Id" -User 'teren' -TimeoutSec 60 }
}

try {
  Write-Step 'pregatire: toxiproxy si proxy-ul client -> nginx'
  if ((Get-ServiceHealth 'toxiproxy') -ne 'running/healthy') {
    $startedToxi = $true
    [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'up', '-d', 'toxiproxy'))
  }
  $sec = Wait-ServiceHealthy 'toxiproxy' 60
  [void](Assert-Check 'toxiproxy healthy (API pe 127.0.0.1:8474)' ($sec -ge 0) "dupa $sec s")
  if ((Invoke-Toxi GET '/proxies/nginx').Status -ne 200) {
    $c = Invoke-Toxi POST '/proxies' '{"name":"nginx","listen":"0.0.0.0:8443","upstream":"nginx:443","enabled":true}'
    Write-Info "proxy nginx creat: HTTP $($c.Status)"
  }
  Reset-Toxiproxy
  $BaseUrl = $ClientUrl
  $h = Invoke-Curl -Url "$ClientUrl/health"
  [void](Assert-Check "$ClientUrl/health -> 200 (prin toxiproxy, fara toxice)" ($h.Status -eq 200) "HTTP $($h.Status), $($h.Ms) ms")
  [void](Get-Token 'teren')   # login inainte de degradare (token valabil 15 minute)
  [void](Get-Token 'admin')

  $f = New-RandomFile "$RunId-inspectie-retea-degradata.mp4" ($SizeMiB * 1MB)
  $sha = Get-Sha256 $f
  $size = (Get-Item $f).Length
  Write-Info "fisier local: $SizeMiB MiB, sha256 $($sha.Substring(0, 12))..."

  Write-Step "stric: latenta $LatencyMs ms +-$JitterMs (downstream) si timeout $LossTimeoutMs ms pe $([int]($LossToxicity * 100))% din conexiuni (upstream)"
  Add-Toxic 'nginx' 'chaos-client-latency' 'latency' @{ latency = $LatencyMs; jitter = $JitterMs } 1.0 'downstream'
  Add-Toxic 'nginx' 'chaos-client-loss' 'timeout' @{ timeout = $LossTimeoutMs } $LossToxicity 'upstream'
  $t0 = Get-Date
  $h2 = Invoke-Curl -Url "$ClientUrl/health"
  Write-Info "health prin proxy degradat: HTTP $($h2.Status), $($h2.Ms) ms"

  Write-Step 'verific: upload-ul in bucati, cu reincercari automate'
  $init = Invoke-WithRetry 'initializare' {
    Invoke-Api -Method POST -Path '/uploads' -User 'teren' -TimeoutSec 60 -Json (@{ category = 'INSPECTION_VIDEO'; fileName = (Split-Path $f -Leaf); totalSize = $size } | ConvertTo-Json -Compress)
  }
  [void](Assert-Check 'POST /uploads -> 201' ($init.Status -eq 201) "$(Format-Response $init), storage $($init.Json.storage), $($init.Json.totalChunks) bucati")
  if ($init.Status -ne 201) { throw 'initializarea a esuat si dupa reincercari' }
  $s = $init.Json; $uploadId = $s.uploadId; $cs = [int64]$s.chunkSize; $total = [int]$s.totalChunks
  $n = 1; $resyncs = 0; $retriedChunks = @{}
  while ($n -le $total) {
    $off = ($n - 1) * $cs
    $part = Get-FileSlice $f $off ([math]::Min($cs, $size - $off))
    $before = $script:Retries
    $cur = $n
    $r = Invoke-WithRetry "bucata $cur/$total" { Invoke-Api -Method PUT -Path "/uploads/$uploadId/chunks/$cur" -User 'teren' -InFile $part -TimeoutSec 300 }
    Remove-Item $part -Force
    if ($script:Retries -gt $before) { $retriedChunks[$cur] = $script:Retries - $before }
    if ($r.Status -eq 200) {
      $n = [math]::Max([int]$r.Json.nextChunk, $cur + 1)
    } elseif ($r.Status -eq 409 -and $r.Json.code -eq 'CHUNK_OUT_OF_ORDER' -and $resyncs -lt 5) {
      $resyncs++
      $st = Get-UploadState $uploadId
      Write-Info "bucata ${cur}: CHUNK_OUT_OF_ORDER, serverul asteapta $($st.Json.nextChunk)"
      $n = [int]$st.Json.nextChunk
    } else {
      throw "bucata ${cur}: $(Format-Response $r) (dupa reincercari)"
    }
    if ($cur % 5 -eq 0 -or $cur -eq $total) { Write-Info ("progres: {0}/{1} bucati, {2} reincercari pana acum, {3:0} s" -f $cur, $total, $script:Retries, ((Get-Date) - $t0).TotalSeconds) }
  }
  $done = $null
  while ($true) {
    $done = Invoke-WithRetry 'finalizare' { Invoke-Api -Method POST -Path "/uploads/$uploadId/complete" -User 'teren' -TimeoutSec 900 }
    if ($done.Status -eq 409 -and $done.Json.code -eq 'UPLOAD_INCOMPLETE' -and $resyncs -lt 5) {
      $resyncs++; throw "complete: UPLOAD_INCOMPLETE dupa ce toate bucatile au primit 200 ($(Format-Response $done))"
    }
    break
  }
  $completed = ($done.Status -eq 201 -or $done.Status -eq 200)
  $elapsed = ((Get-Date) - $t0).TotalSeconds
  [void](Assert-Check 'complete -> 201/200 CONFIRMED, fara interventie manuala' ($completed -and $done.Json.status -eq 'CONFIRMED') "$(Format-Response $done), storage $($done.Json.storage), $([math]::Round($elapsed)) s")
  [void](Assert-Check 'sha256 final (server) = sha256 local' ($done.Json.sha256 -eq $sha) "$($sha.Substring(0, 12))...")
  $retrySummary = ($script:RetryLog.Keys | ForEach-Object { "$($script:RetryLog[$_]) x $_" }) -join ', '
  Write-Info "reincercari automate: $($script:Retries) ($retrySummary); bucati reluate: $($retriedChunks.Count)/$total; resincronizari: $resyncs"
  [void](Assert-Check 'reteaua a fost chiar degradata (cel putin o reincercare automata)' ($script:Retries -gt 0) "$($script:Retries) reincercari")

  Write-Step 'repar: toxiproxy /reset (fara toxice)'
  Reset-Toxiproxy
  $BaseUrl = $DirectUrl

  Write-Step 'verific: integritatea pe server (drum direct, https://localhost)'
  $m = Get-FileMeta $done.Json.id
  [void](Assert-Check 'metadate: CONFIRMED, marime si sha256 corecte' ($m.Status -eq 200 -and $m.Json.status -eq 'CONFIRMED' -and [int64]$m.Json.size -eq $size -and $m.Json.sha256 -eq $sha) "$(Format-Response $m), storage $($m.Json.storage)")
  $v = Invoke-Api -Path "/files/$($done.Json.id)/verify" -User 'admin' -TimeoutSec 900
  [void](Assert-Check 'GET /files/{id}/verify -> ok:true (recalculat din storage)' ($v.Status -eq 200 -and $v.Json.ok -eq $true) (Format-Response $v))
  $h3 = Invoke-Curl -Url "$ClientUrl/health"
  [void](Assert-Check 'drumul prin proxy revine la normal' ($h3.Status -eq 200 -and $h3.Ms -lt $LatencyMs) "HTTP $($h3.Status), $($h3.Ms) ms")
} catch {
  [void](Assert-Check 'scenariul h rulat fara exceptii' $false (Format-Error $_))
} finally {
  $BaseUrl = $DirectUrl
  Reset-Toxiproxy
  if ($uploadId -and -not $completed) {
    Write-Step 'curatare (finally): abort pe sesiunea neterminata'
    try { $a = Invoke-Api -Method POST -Path "/uploads/$uploadId/abort" -User 'teren'; Write-Info (Format-Response $a) } catch { }
  }
  if (-not $KeepToxiproxy -and $startedToxi) {
    Write-Step 'reparare (finally): opresc toxiproxy (pornit de script)'
    try { [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'rm', '-sf', 'toxiproxy')) } catch { Write-Host "  oprire esuata: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'h-client-network')
