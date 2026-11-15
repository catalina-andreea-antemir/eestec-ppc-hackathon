<#
.SYNOPSIS
  F9 h) Degraded network between client and nginx: a chunked video upload through toxiproxy (https://localhost:8443 ->
  nginx:443) with high latency and approximate packet loss (the timeout toxic on a share of connections). The client
  retries failed chunks on its own, like the UI (F4): backoff 1 s, 2 s, 4 s ... up to 10 s, Retry-After honored, for
  up to 2 minutes after the first failure, resync from GET /uploads/{id} on CHUNK_OUT_OF_ORDER. The upload finishes
  with no manual intervention and a final SHA-256 equal to the local one.
.DESCRIPTION
  Starts toxiproxy (chaos profile) if it is not running; the `nginx` proxy (0.0.0.0:8443 -> nginx:443) comes from
  tests/chaos/toxiproxy.json (or is created with POST /proxies). api is NOT recreated: only the client -> nginx
  path is degraded. At the end the toxics are removed, and toxiproxy is stopped if this script started it.
.EXAMPLE
  .\tests\chaos\h-client-network.ps1
  .\tests\chaos\h-client-network.ps1 -SizeMiB 1130          # 1.1 GB, as in the demo
  .\tests\chaos\h-client-network.ps1 -LatencyMs 2000 -LossToxicity 0.3
#>
param([int]$SizeMiB = 200, [int]$LatencyMs = 1000, [int]$JitterMs = 100, [double]$LossToxicity = 0.2,
      [int]$LossTimeoutMs = 3000, [int]$RetryTimeoutSec = 120, [switch]$KeepToxiproxy)
. "$PSScriptRoot\lib\chaos.ps1"
Write-Title "h) Degraded client -> nginx network (latency $LatencyMs ms, loss ~$([int]($LossToxicity * 100))%): $SizeMiB MiB chunked video, auto-resumed"

$DirectUrl = $BaseUrl
$ClientUrl = if ($env:CHAOS_CLIENT_URL) { $env:CHAOS_CLIENT_URL.TrimEnd('/') } else { 'https://localhost:8443/api/v1' }
$startedToxi = $false
$script:Retries = 0
$script:RetryLog = @{}
$uploadId = $null
$completed = $false

# Same retry policy as the UI (frontend/filegrid-ui/src/app/core/upload/retry.ts): network errors (status 0),
# 429, 502, 503, 504 and 409 UPLOAD_BUSY are transient; Retry-After wins; otherwise 1, 2, 4, 8, 10 s +-20%.
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
    $why = if ($err) { $err } elseif ($r.Status -eq 0) { 'connection lost' } else { Format-Response $r }
    $delay = [int]([math]::Min(10000, 1000 * [math]::Pow(2, $attempt - 1)) * (0.8 + 0.4 * $rnd.NextDouble()))
    $ra = 0
    if ($r -and $r.Headers['Retry-After'] -and [int]::TryParse($r.Headers['Retry-After'], [ref]$ra)) { $delay = $ra * 1000 }
    if (((Get-Date) - $first).TotalMilliseconds + $delay -gt $RetryTimeoutSec * 1000) {
      Write-Info "${Op}: giving up after $attempt retries ($why)"
      if ($r) { return $r } else { throw "${Op}: $why" }
    }
    $script:Retries++
    $key = if ($r -and $r.Status -gt 0) { "HTTP $($r.Status)" } else { 'network' }
    $script:RetryLog[$key] = 1 + [int]$script:RetryLog[$key]
    Write-Info ("{0}: {1}, retrying in {2:0.0} s (attempt {3})" -f $Op, $why, ($delay / 1000), ($attempt + 1))
    Start-Sleep -Milliseconds $delay
  }
}

function Get-UploadState([string]$Id) {
  return Invoke-WithRetry 'status' { Invoke-Api -Path "/uploads/$Id" -User 'teren' -TimeoutSec 60 }
}

try {
  Write-Step 'setup: toxiproxy and the client -> nginx proxy'
  if ((Get-ServiceHealth 'toxiproxy') -ne 'running/healthy') {
    $startedToxi = $true
    [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'up', '-d', 'toxiproxy'))
  }
  $sec = Wait-ServiceHealthy 'toxiproxy' 60
  [void](Assert-Check 'toxiproxy healthy (API on 127.0.0.1:8474)' ($sec -ge 0) "after $sec s")
  if ((Invoke-Toxi GET '/proxies/nginx').Status -ne 200) {
    $c = Invoke-Toxi POST '/proxies' '{"name":"nginx","listen":"0.0.0.0:8443","upstream":"nginx:443","enabled":true}'
    Write-Info "nginx proxy created: HTTP $($c.Status)"
  }
  Reset-Toxiproxy
  $BaseUrl = $ClientUrl
  $h = Invoke-Curl -Url "$ClientUrl/health"
  [void](Assert-Check "$ClientUrl/health -> 200 (through toxiproxy, no toxics)" ($h.Status -eq 200) "HTTP $($h.Status), $($h.Ms) ms")
  [void](Get-Token 'teren')   # log in before degrading the network (token valid for 15 minutes)
  [void](Get-Token 'admin')

  $f = New-RandomFile "$RunId-inspectie-retea-degradata.mp4" ($SizeMiB * 1MB)
  $sha = Get-Sha256 $f
  $size = (Get-Item $f).Length
  Write-Info "local file: $SizeMiB MiB, sha256 $($sha.Substring(0, 12))..."

  Write-Step "break: latency $LatencyMs ms +-$JitterMs (downstream) and timeout $LossTimeoutMs ms on $([int]($LossToxicity * 100))% of connections (upstream)"
  Add-Toxic 'nginx' 'chaos-client-latency' 'latency' @{ latency = $LatencyMs; jitter = $JitterMs } 1.0 'downstream'
  Add-Toxic 'nginx' 'chaos-client-loss' 'timeout' @{ timeout = $LossTimeoutMs } $LossToxicity 'upstream'
  $t0 = Get-Date
  $h2 = Invoke-Curl -Url "$ClientUrl/health"
  Write-Info "health through the degraded proxy: HTTP $($h2.Status), $($h2.Ms) ms"

  Write-Step 'checking the chunked upload with automatic retries'
  $init = Invoke-WithRetry 'init' {
    Invoke-Api -Method POST -Path '/uploads' -User 'teren' -TimeoutSec 60 -Json (@{ category = 'INSPECTION_VIDEO'; fileName = (Split-Path $f -Leaf); totalSize = $size } | ConvertTo-Json -Compress)
  }
  [void](Assert-Check 'POST /uploads -> 201' ($init.Status -eq 201) "$(Format-Response $init), storage $($init.Json.storage), $($init.Json.totalChunks) chunks")
  if ($init.Status -ne 201) { throw 'init failed even after retries' }
  $s = $init.Json; $uploadId = $s.uploadId; $cs = [int64]$s.chunkSize; $total = [int]$s.totalChunks
  $n = 1; $resyncs = 0; $retriedChunks = @{}
  while ($n -le $total) {
    $off = ($n - 1) * $cs
    $part = Get-FileSlice $f $off ([math]::Min($cs, $size - $off))
    $before = $script:Retries
    $cur = $n
    $r = Invoke-WithRetry "chunk $cur/$total" { Invoke-Api -Method PUT -Path "/uploads/$uploadId/chunks/$cur" -User 'teren' -InFile $part -TimeoutSec 300 }
    Remove-Item $part -Force
    if ($script:Retries -gt $before) { $retriedChunks[$cur] = $script:Retries - $before }
    if ($r.Status -eq 200) {
      $n = [math]::Max([int]$r.Json.nextChunk, $cur + 1)
    } elseif ($r.Status -eq 409 -and $r.Json.code -eq 'CHUNK_OUT_OF_ORDER' -and $resyncs -lt 5) {
      $resyncs++
      $st = Get-UploadState $uploadId
      Write-Info "chunk ${cur}: CHUNK_OUT_OF_ORDER, the server expects $($st.Json.nextChunk)"
      $n = [int]$st.Json.nextChunk
    } else {
      throw "chunk ${cur}: $(Format-Response $r) (after retries)"
    }
    if ($cur % 5 -eq 0 -or $cur -eq $total) { Write-Info ("progress: {0}/{1} chunks, {2} retries so far, {3:0} s" -f $cur, $total, $script:Retries, ((Get-Date) - $t0).TotalSeconds) }
  }
  $done = $null
  while ($true) {
    $done = Invoke-WithRetry 'complete' { Invoke-Api -Method POST -Path "/uploads/$uploadId/complete" -User 'teren' -TimeoutSec 900 }
    if ($done.Status -eq 409 -and $done.Json.code -eq 'UPLOAD_INCOMPLETE' -and $resyncs -lt 5) {
      $resyncs++; throw "complete: UPLOAD_INCOMPLETE although every chunk got 200 ($(Format-Response $done))"
    }
    break
  }
  $completed = ($done.Status -eq 201 -or $done.Status -eq 200)
  $elapsed = ((Get-Date) - $t0).TotalSeconds
  [void](Assert-Check 'complete -> 201/200 CONFIRMED, no manual intervention' ($completed -and $done.Json.status -eq 'CONFIRMED') "$(Format-Response $done), storage $($done.Json.storage), $([math]::Round($elapsed)) s")
  [void](Assert-Check 'final sha256 (server) = local sha256' ($done.Json.sha256 -eq $sha) "$($sha.Substring(0, 12))...")
  $retrySummary = ($script:RetryLog.Keys | ForEach-Object { "$($script:RetryLog[$_]) x $_" }) -join ', '
  Write-Info "automatic retries: $($script:Retries) ($retrySummary); retried chunks: $($retriedChunks.Count)/$total; resyncs: $resyncs"
  [void](Assert-Check 'the network was actually degraded (at least one automatic retry)' ($script:Retries -gt 0) "$($script:Retries) retries")

  Write-Step 'repair: toxiproxy /reset (no toxics)'
  Reset-Toxiproxy
  $BaseUrl = $DirectUrl

  Write-Step 'checking server-side integrity (direct path, https://localhost)'
  $m = Get-FileMeta $done.Json.id
  [void](Assert-Check 'metadata: CONFIRMED, correct size and sha256' ($m.Status -eq 200 -and $m.Json.status -eq 'CONFIRMED' -and [int64]$m.Json.size -eq $size -and $m.Json.sha256 -eq $sha) "$(Format-Response $m), storage $($m.Json.storage)")
  $v = Invoke-Api -Path "/files/$($done.Json.id)/verify" -User 'admin' -TimeoutSec 900
  [void](Assert-Check 'GET /files/{id}/verify -> ok:true (recomputed from storage)' ($v.Status -eq 200 -and $v.Json.ok -eq $true) (Format-Response $v))
  $h3 = Invoke-Curl -Url "$ClientUrl/health"
  [void](Assert-Check 'the proxied path is back to normal' ($h3.Status -eq 200 -and $h3.Ms -lt $LatencyMs) "HTTP $($h3.Status), $($h3.Ms) ms")
} catch {
  [void](Assert-Check 'scenario h ran without exceptions' $false (Format-Error $_))
} finally {
  $BaseUrl = $DirectUrl
  Reset-Toxiproxy
  if ($uploadId -and -not $completed) {
    Write-Step 'cleanup (finally): abort the unfinished session'
    try { $a = Invoke-Api -Method POST -Path "/uploads/$uploadId/abort" -User 'teren'; Write-Info (Format-Response $a) } catch { }
  }
  if (-not $KeepToxiproxy -and $startedToxi) {
    Write-Step 'repair (finally): stopping toxiproxy (started by this script)'
    try { [void](Invoke-Compose -Arguments @('--profile', 'chaos', 'rm', '-sf', 'toxiproxy')) } catch { Write-Host "  stop failed: $($_.Exception.Message)" -ForegroundColor Red }
  }
}
exit (Complete-Scenario 'h-client-network')
