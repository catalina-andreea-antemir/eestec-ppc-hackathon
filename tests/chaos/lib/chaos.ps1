# Shared library for the FileGrid chaos tests (F9). Dot-source it: . "$PSScriptRoot\lib\chaos.ps1"
# Works in Windows PowerShell 5.1 and PowerShell 7. HTTP goes through curl.exe (self-signed TLS: -k).
# Passwords are read from .env (FILEGRID_USER_*_PASSWORD) and kept only in memory and in temp files deleted
# right away; they are never printed and never appear on a command line.
# Contract: docs/api/openapi.yaml. Resilience: filegrid.resilience in application.yml (health every 3 s, circuit
# opens after 2 failed checks, open-wait 5 s), so the waits below are in the order of seconds.

$ErrorActionPreference = 'Stop'

$script:ChaosDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$script:BaseUrl = if ($env:CHAOS_BASE_URL) { $env:CHAOS_BASE_URL.TrimEnd('/') } else { 'https://localhost/api/v1' }
$script:ToxiUrl = if ($env:CHAOS_TOXIPROXY_URL) { $env:CHAOS_TOXIPROXY_URL.TrimEnd('/') } else { 'http://127.0.0.1:8474' }
$script:RunId = if ($env:CHAOS_RUN_ID) { $env:CHAOS_RUN_ID } else { 'chaos-' + (Get-Date -Format 'MMddHHmmss') }
$script:Work = Join-Path ([IO.Path]::GetTempPath()) ("filegrid-" + $script:RunId + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 6))
New-Item -ItemType Directory -Force $script:Work | Out-Null

$sysCurl = Join-Path $env:SystemRoot 'System32\curl.exe'
$script:Curl = if ($env:SystemRoot -and (Test-Path $sysCurl)) { $sysCurl } else { 'curl' }
$script:Checks = New-Object System.Collections.ArrayList
$script:Tokens = @{}
$script:Passwords = @{}
$script:Inv = [Globalization.CultureInfo]::InvariantCulture

# --- .env ------------------------------------------------------------------------------------------------------------
$envFile = Join-Path $script:Root '.env'
if (-not (Test-Path $envFile)) { throw ".env is missing in $script:Root (start the stack with scripts\start.ps1)." }
foreach ($line in Get-Content $envFile) {
  if ($line -match '^\s*FILEGRID_USER_([A-Z]+)_PASSWORD\s*=\s*(.*)$') {
    $script:Passwords[$Matches[1].ToLowerInvariant()] = $Matches[2].Trim().Trim('"').Trim("'")
  }
}
foreach ($u in 'teren', 'contor', 'admin', 'dispecer') {
  if (-not $script:Passwords[$u]) { throw "FILEGRID_USER_$($u.ToUpperInvariant())_PASSWORD is missing from .env" }
}

# --- Output and checks -----------------------------------------------------------------------------------------------
function Write-Title([string]$Text) { Write-Host ''; Write-Host "=== $Text ===" -ForegroundColor Cyan }
function Write-Step([string]$Text) { Write-Host "--- $Text" -ForegroundColor Yellow }
function Write-Info([string]$Text) { Write-Host "    $Text" }

function Assert-Check {
  param([string]$Name, [bool]$Ok, [string]$Detail = '')
  [void]$script:Checks.Add([pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail })
  $tag = if ($Ok) { '[OK]  ' } else { '[FAIL]' }
  $color = if ($Ok) { 'Green' } else { 'Red' }
  $msg = "  $tag $Name"
  if ($Detail) { $msg += " ($Detail)" }
  Write-Host $msg -ForegroundColor $color
  return $Ok
}

# Demo mode (CHAOS_PAUSE=1): pauses while the component is broken, until Enter, so the effect is visible in the UI.
function Wait-Demo([string]$What = 'repair') {
  if ($env:CHAOS_PAUSE -eq '1') { [void](Read-Host "    [demo pause] Enter to $What") }
}

# Exception message with file and line (used by the "scenario ran without exceptions" check).
function Format-Error($ErrorRecord) {
  $ii = $ErrorRecord.InvocationInfo
  $where = if ($ii -and $ii.ScriptName) { " [$(Split-Path $ii.ScriptName -Leaf):$($ii.ScriptLineNumber)]" } else { '' }
  return "$($ErrorRecord.Exception.Message)$where"
}

# Scenario summary; returns the exit code (0 = all checks passed).
function Complete-Scenario([string]$Name) {
  $failed = @($script:Checks | Where-Object { -not $_.Ok })
  $total = $script:Checks.Count
  Write-Host ''
  if ($failed.Count -eq 0 -and $total -gt 0) {
    Write-Host "RESULT $Name : PASSED ($total/$total checks)" -ForegroundColor Green
  } else {
    Write-Host "RESULT $Name : FAILED ($($total - $failed.Count)/$total checks)" -ForegroundColor Red
    foreach ($f in $failed) { Write-Host "  - $($f.Name) $($f.Detail)" -ForegroundColor Red }
  }
  Remove-Item -Recurse -Force $script:Work -ErrorAction SilentlyContinue
  if ($failed.Count -eq 0 -and $total -gt 0) { return 0 } else { return 1 }
}

# Polls until $Condition returns true; returns the elapsed seconds, or -1 on timeout.
function Wait-Until {
  param([scriptblock]$Condition, [int]$TimeoutSec = 60, [int]$PollMs = 500)
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
    try { if (& $Condition) { return [math]::Round($sw.Elapsed.TotalSeconds, 1) } } catch { }
    Start-Sleep -Milliseconds $PollMs
  }
  return -1
}

# --- Docker ----------------------------------------------------------------------------------------------------------
# docker compose from the repo root. Extra arguments (e.g. -f override, --profile) go before the subcommand.
function Invoke-Compose {
  param([string[]]$Arguments, [switch]$Quiet)
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $out = & docker compose --project-directory $script:Root -f (Join-Path $script:Root 'docker-compose.yml') @Arguments 2>&1 |
      ForEach-Object { "$_" }
    $code = $LASTEXITCODE
  } finally { $ErrorActionPreference = $old }
  if (-not $Quiet) { $out | ForEach-Object { Write-Host "    | $_" -ForegroundColor DarkGray } }
  if ($code -ne 0) { throw "docker compose $($Arguments -join ' ') failed (exit $code): $($out -join ' ')" }
  return $out
}

function Get-ContainerIds([string]$Service) {
  return @(Invoke-Compose -Arguments @('ps', '-a', '-q', $Service) -Quiet | Where-Object { $_ -match '^[0-9a-f]{12,}$' })
}

function Get-ContainerId([string]$Service) {
  $ids = @(Get-ContainerIds $Service)
  if ($ids.Count -eq 0) { return $null }
  return $ids[0]
}

# Service state across ALL its containers (api may have 3 replicas, C14): 'running/healthy' only if every one is;
# otherwise the first differing state (e.g. 'running/starting', 'exited/').
function Get-ServiceHealth([string]$Service) {
  $ids = @(Get-ContainerIds $Service)
  if ($ids.Count -eq 0) { return 'absent' }
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $states = @(& docker inspect --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{end}}' @ids 2>&1 | ForEach-Object { "$_".Trim() }) }
  finally { $ErrorActionPreference = $old }
  $bad = @($states | Where-Object { $_ -ne 'running/healthy' })
  if ($bad.Count -eq 0) { return 'running/healthy' }
  return "$($bad[0]) ($($states.Count - $bad.Count)/$($states.Count) healthy)"
}

# Number of api replicas currently running (C14: `docker compose up -d --scale api=3`); preserved on recreate.
function Get-ApiReplicas { $n = @(Get-ContainerIds 'api').Count; if ($n -lt 1) { return 1 } else { return $n } }

function Wait-ServiceHealthy([string]$Service, [int]$TimeoutSec = 180) {
  return Wait-Until -TimeoutSec $TimeoutSec -PollMs 1000 -Condition { (Get-ServiceHealth $Service) -eq 'running/healthy' }
}

# Shell command inside a stack container (docker compose exec -T). Returns the output lines.
function Invoke-InContainer([string]$Service, [string]$Command) {
  return Invoke-Compose -Arguments @('exec', '-T', $Service, 'sh', '-c', $Command) -Quiet
}

# SQL as sa inside the mssql container (the SA password never leaves the container: MSSQL_SA_PASSWORD). The query
# is base64-encoded because Windows PowerShell 5.1 mangles quotes in native arguments and adds a BOM on stdin.
function Invoke-Sql([string]$Query, [string]$Database = 'master') {
  $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("SET NOCOUNT ON;`n$Query`n"))
  $cmd = 'echo ' + $b64 + ' | base64 -d > /tmp/chaos.sql && SQLCMDPASSWORD=$MSSQL_SA_PASSWORD /opt/mssql-tools18/bin/sqlcmd -S localhost -U sa -C -I -b -h -1 -W -s , -d ' + $Database + ' -i /tmp/chaos.sql; rc=$?; rm -f /tmp/chaos.sql; exit $rc'
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try {
    $out = & docker compose --project-directory $script:Root -f (Join-Path $script:Root 'docker-compose.yml') exec -T mssql bash -c $cmd 2>&1 |
      ForEach-Object { "$_" }
    $code = $LASTEXITCODE
  } finally { $ErrorActionPreference = $old }
  if ($code -ne 0) { throw "sqlcmd failed (exit $code): $($out -join ' ')" }
  return @($out | Where-Object { $_ -ne '' })
}

# --- HTTP (curl) -----------------------------------------------------------------------------------------------------
# Recreates the work directory if it vanished (e.g. %TEMP% cleanup during the run).
function New-TempPath([string]$Suffix = '.tmp') {
  if (-not (Test-Path $script:Work)) { New-Item -ItemType Directory -Force $script:Work | Out-Null }
  return Join-Path $script:Work ([Guid]::NewGuid().ToString('N') + $Suffix)
}

function Invoke-Curl {
  param([string[]]$CurlArgs, [string]$Url, [string]$OutFile, [int]$TimeoutSec = 120)
  $body = if ($OutFile) { $OutFile } else { New-TempPath '.body' }
  $head = New-TempPath '.head'
  $all = @('-sS', '-k', '-o', $body, '-D', $head, '-w', '%{http_code}', '--max-time', "$TimeoutSec", '-H', 'Expect:') + $CurlArgs + @($Url)
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $codeText = (& $script:Curl @all 2>$null) -join '' } finally { $ErrorActionPreference = $old }
  $ms = $sw.ElapsedMilliseconds
  $status = 0; [void][int]::TryParse(($codeText -replace '\D', ''), [ref]$status)
  $headers = @{}
  if (Test-Path $head) {
    foreach ($l in Get-Content $head) { if ($l -match '^([A-Za-z0-9-]+):\s*(.*)$') { $headers[$Matches[1]] = $Matches[2].Trim() } }
    Remove-Item $head -Force
  }
  $text = $null; $json = $null
  if (-not $OutFile -and (Test-Path $body)) {
    $text = [IO.File]::ReadAllText($body, [Text.Encoding]::UTF8)
    Remove-Item $body -Force
    if ($text -and $text.TrimStart().StartsWith('{')) { try { $json = $text | ConvertFrom-Json } catch { } }
  }
  return [pscustomobject]@{ Status = $status; Headers = $headers; Text = $text; Json = $json; Ms = $ms; File = $OutFile }
}

function Get-Token([string]$User) {
  $t = $script:Tokens[$User]
  if ($t -and ((Get-Date) - $t.At).TotalMinutes -lt 10) { return $t.Token }
  $f = New-TempPath '.json'
  try {
    $payload = @{ username = $User; password = $script:Passwords[$User] } | ConvertTo-Json -Compress
    [IO.File]::WriteAllText($f, $payload, (New-Object Text.UTF8Encoding($false)))
    $r = Invoke-Curl -Url "$script:BaseUrl/auth/login" -CurlArgs @('-X', 'POST', '-H', 'Content-Type: application/json', '--data-binary', "@$f")
  } finally { Remove-Item $f -Force -ErrorAction SilentlyContinue }
  if ($r.Status -ne 200 -or -not $r.Json) { throw "Login $User failed: HTTP $($r.Status)" }
  $script:Tokens[$User] = @{ Token = $r.Json.accessToken; At = Get-Date }
  return $r.Json.accessToken
}

# Authenticated API call. -Json: JSON body (string); -Form: text fields; -FormFile: multipart file;
# -InFile: binary body (application/octet-stream); -OutFile: write the response body to a file.
function Invoke-Api {
  param([string]$Method = 'GET', [string]$Path, [string]$User = 'admin', [string]$Json, [hashtable]$Form,
        [string]$FormFile, [string]$FormFileType = 'application/octet-stream', [string]$InFile, [string]$OutFile,
        [hashtable]$Headers, [int]$TimeoutSec = 120)
  $a = @('-X', $Method)
  if ($User) { $a += @('-H', "Authorization: Bearer $(Get-Token $User)") }
  if ($Headers) { foreach ($k in $Headers.Keys) { $a += @('-H', "${k}: $($Headers[$k])") } }
  $jf = $null
  if ($Json) {
    $jf = New-TempPath '.json'
    [IO.File]::WriteAllText($jf, $Json, (New-Object Text.UTF8Encoding($false)))
    $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$jf")
  }
  if ($Form) { foreach ($k in $Form.Keys) { $a += @('--form-string', "$k=$($Form[$k])") } }
  if ($FormFile) { $a += @('-F', "file=@$FormFile;type=$FormFileType") }
  if ($InFile) { $a += @('-H', 'Content-Type: application/octet-stream', '--data-binary', "@$InFile") }
  try { return Invoke-Curl -Url "$script:BaseUrl$Path" -CurlArgs $a -OutFile $OutFile -TimeoutSec $TimeoutSec }
  finally { if ($jf) { Remove-Item $jf -Force -ErrorAction SilentlyContinue } }
}

function Format-Response($r) {
  $s = "HTTP $($r.Status)"
  if ($r.Json -and ($r.Json.PSObject.Properties.Name -contains 'code')) { $s += " $($r.Json.code)" }
  if ($r.Headers['Retry-After']) { $s += ", Retry-After $($r.Headers['Retry-After'])" }
  return $s
}

# --- Test data -------------------------------------------------------------------------------------------------------
function Get-Sha256([string]$Path) { return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant() }

function New-RandomFile([string]$Name, [int]$Size) {
  [void](New-TempPath)   # ensures the work directory exists
  $p = Join-Path $script:Work $Name
  $buf = New-Object byte[] $Size
  (New-Object Random).NextBytes($buf)
  [IO.File]::WriteAllBytes($p, $buf)
  return $p
}

function New-OutageCsv([string]$Name, [int]$Lines = 200) {
  [void](New-TempPath)   # ensures the work directory exists
  $p = Join-Path $script:Work $Name
  $rnd = New-Object Random
  $sb = New-Object Text.StringBuilder
  [void]$sb.AppendLine('ts,statie,eveniment,durata_min')
  for ($i = 0; $i -lt $Lines; $i++) {
    [void]$sb.AppendLine(('2026-11-15T{0:00}:{1:00}:00Z,ST-CHAOS-{2},{3},{4}' -f ($i % 24), ($i % 60), $rnd.Next(100),
      (@('declansare', 'scurtcircuit', 'cadere-tensiune')[$i % 3]), $rnd.Next(240)))
  }
  [void]$sb.AppendLine("# $script:RunId $([Guid]::NewGuid())")
  [IO.File]::WriteAllText($p, $sb.ToString(), (New-Object Text.UTF8Encoding($false)))
  return $p
}

# --- API operations --------------------------------------------------------------------------------------------------
function Send-Outage([string]$Path, [string]$User = 'teren') {
  return Invoke-Api -Method POST -Path '/files' -User $User -FormFile $Path -FormFileType 'text/csv' -Form @{
    category = 'OUTAGE_RECORD'; zone = 'Muntenia'; stationId = 'ST-CHAOS-01'
    occurredAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ', $script:Inv)
  }
}

# Reading batch: $Count readings 15 minutes apart from $Start (UTC). Returns the body and the idempotency key.
function New-MeterBatch([string]$MeterId, [datetime]$Start, [int]$Count = 96, [string]$Zone = 'Muntenia') {
  $rnd = New-Object Random
  $items = for ($i = 0; $i -lt $Count; $i++) {
    $ts = $Start.AddMinutes(15 * $i).ToString('yyyy-MM-ddTHH:mm:ssZ', $script:Inv)
    '{"ts":"' + $ts + '","kwh":' + ($rnd.Next(0, 800) / 1000).ToString('0.000', $script:Inv) + '}'
  }
  $first = $Start.ToString('yyyy-MM-ddTHH:mm:ssZ', $script:Inv)
  return [pscustomobject]@{
    Body = '{"meterId":"' + $MeterId + '","zone":"' + $Zone + '","readings":[' + ($items -join ',') + ']}'
    Key = "${MeterId}_$first"; Count = $Count
  }
}

function Send-MeterBatch($Batch, [string]$User = 'contor') {
  return Invoke-Api -Method POST -Path '/meter-readings' -User $User -Json $Batch.Body -Headers @{ 'Idempotency-Key' = $Batch.Key; 'X-Scenario' = 'chaos' }
}

# Video through chunked upload (init, chunks, complete).
function Send-Video([string]$Path, [string]$User = 'teren') {
  $size = (Get-Item $Path).Length
  $name = Split-Path $Path -Leaf
  $init = Invoke-Api -Method POST -Path '/uploads' -User $User -Json (@{ category = 'INSPECTION_VIDEO'; fileName = $name; totalSize = $size } | ConvertTo-Json -Compress)
  if ($init.Status -ne 201) { return [pscustomobject]@{ Ok = $false; Step = 'init'; Response = $init; Init = $null; Complete = $null } }
  $s = $init.Json
  $n = 1
  for ($off = [int64]0; $off -lt $size; $off += $s.chunkSize) {
    $part = Get-FileSlice $Path $off ([math]::Min([int64]$s.chunkSize, $size - $off))
    $put = Invoke-Api -Method PUT -Path "/uploads/$($s.uploadId)/chunks/$n" -User $User -InFile $part
    Remove-Item $part -Force
    if ($put.Status -ne 200) { return [pscustomobject]@{ Ok = $false; Step = "chunk $n"; Response = $put; Init = $s; Complete = $null } }
    $n++
  }
  $done = Invoke-Api -Method POST -Path "/uploads/$($s.uploadId)/complete" -User $User
  return [pscustomobject]@{ Ok = ($done.Status -eq 201 -or $done.Status -eq 200); Step = 'complete'; Response = $done; Init = $s; Complete = $done.Json }
}

function Get-FileSlice([string]$Path, [int64]$Offset, [int64]$Length) {
  $out = New-TempPath '.chunk'
  $in = [IO.File]::OpenRead($Path)
  try {
    [void]$in.Seek($Offset, 'Begin')
    $buf = New-Object byte[] $Length
    $read = 0
    while ($read -lt $Length) { $k = $in.Read($buf, $read, $Length - $read); if ($k -le 0) { break }; $read += $k }
    [IO.File]::WriteAllBytes($out, $buf)
  } finally { $in.Close() }
  return $out
}

# Direct download (ADMIN sees every category). Returns the response plus the locally computed body hash.
function Get-Download([string]$FileId, [string]$User = 'admin') {
  $out = New-TempPath '.dl'
  $r = Invoke-Api -Path "/files/$FileId/download" -User $User -OutFile $out
  $hash = $null; $json = $null
  if (Test-Path $out) {
    if ($r.Status -eq 200 -or $r.Status -eq 206) { $hash = Get-Sha256 $out }
    else { try { $json = [IO.File]::ReadAllText($out) | ConvertFrom-Json } catch { } }
    Remove-Item $out -Force
  }
  $r | Add-Member -NotePropertyName Sha256 -NotePropertyValue $hash
  if ($json) { $r.Json = $json }
  return $r
}

function Get-Verify([string]$FileId, [string]$User = 'admin') { return Invoke-Api -Path "/files/$FileId/verify" -User $User }
function Get-FileMeta([string]$FileId, [string]$User = 'admin') { return Invoke-Api -Path "/files/$FileId" -User $User }

# Download with a correct hash: 200, X-Content-SHA256 = body hash = expected hash.
function Test-DownloadOk([string]$FileId, [string]$Expected, [string]$Label) {
  $d = Get-Download $FileId
  $ok = $d.Status -eq 200 -and $d.Sha256 -eq $Expected -and $d.Headers['X-Content-SHA256'] -eq $Expected
  return Assert-Check "$Label downloads with the correct hash" $ok "$(Format-Response $d), sha256 $(if ($d.Sha256) { $d.Sha256.Substring(0, 12) } else { '-' })..."
}

# --- Health per storage (C11, GET /admin/health) ---------------------------------------------------------------------
function Get-StorageState([string]$Storage) {
  $r = Invoke-Api -Path '/admin/health' -User 'admin' -TimeoutSec 10
  if ($r.Status -ne 200 -or -not $r.Json) { return $null }
  return $r.Json.storages | Where-Object { $_.storage -eq $Storage } | Select-Object -First 1
}

function Format-StorageState($s) {
  if (-not $s) { return 'unknown' }
  $t = "$($s.storage) $($s.status), circuit $($s.circuitBreaker)"
  if ($s.PSObject.Properties.Name -contains 'detail' -and $s.detail) { $t += ", $($s.detail)" }
  return $t
}

# Waits for a storage state (e.g. DOWN + OPEN, or UP + CLOSED); returns the seconds or -1.
# /admin/health answers from whichever instance gets the request and each api replica has its own circuit, so we
# wait until the last state seen on EVERY replica (the `instance` field; nginx rotates them) matches.
function Wait-StorageState([string]$Storage, [string]$Status, [string]$Circuit, [int]$TimeoutSec = 60) {
  $replicas = Get-ApiReplicas
  $last = @{}
  return Wait-Until -TimeoutSec $TimeoutSec -PollMs 300 -Condition {
    $r = Invoke-Api -Path '/admin/health' -User 'admin' -TimeoutSec 10
    if ($r.Status -ne 200 -or -not $r.Json) { return $false }
    $s = $r.Json.storages | Where-Object { $_.storage -eq $Storage } | Select-Object -First 1
    $last[[string]$r.Json.instance] = [bool]($s -and $s.status -eq $Status -and (-not $Circuit -or $s.circuitBreaker -eq $Circuit))
    $ok = @($last.Values | Where-Object { $_ }).Count
    ($ok -ge $replicas) -and ($ok -eq $last.Count)
  }
}

function Assert-StorageState([string]$Storage, [string]$Status, [string]$Circuit, [int]$TimeoutSec = 60) {
  $sec = Wait-StorageState $Storage $Status $Circuit $TimeoutSec
  $now = Get-StorageState $Storage
  $want = "$Storage $Status" + $(if ($Circuit) { ", circuit $Circuit" } else { '' })
  return Assert-Check "/admin/health: $want" ($sec -ge 0) $(if ($sec -ge 0) { "after $sec s" } else { "timeout $TimeoutSec s, now: $(Format-StorageState $now)" })
}

# Final stack state: every service running and healthy, every storage UP.
function Assert-StackHealthy([int]$TimeoutSec = 120) {
  foreach ($svc in 'mssql', 'minio', 'ftp', 'api', 'nginx') {
    $sec = Wait-ServiceHealthy $svc $TimeoutSec
    [void](Assert-Check "container $svc healthy" ($sec -ge 0) (Get-ServiceHealth $svc))
  }
  foreach ($st in 'FTP', 'S3', 'DB') { [void](Assert-StorageState $st 'UP' 'CLOSED' $TimeoutSec) }
}

# --- Toxiproxy (API on 127.0.0.1:8474, chaos profile only) -----------------------------------------------------------
function Invoke-Toxi([string]$Method, [string]$Path, [string]$Json) {
  $a = @('-X', $Method)
  $jf = $null
  if ($Json) {
    $jf = New-TempPath '.json'
    [IO.File]::WriteAllText($jf, $Json, (New-Object Text.UTF8Encoding($false)))
    $a += @('-H', 'Content-Type: application/json', '--data-binary', "@$jf")
  }
  try { return Invoke-Curl -Url "$script:ToxiUrl$Path" -CurlArgs $a -TimeoutSec 10 }
  finally { if ($jf) { Remove-Item $jf -Force -ErrorAction SilentlyContinue } }
}

function Add-Toxic([string]$Proxy, [string]$Name, [string]$Type, [hashtable]$Attributes, [double]$Toxicity = 1.0, [string]$Stream = 'downstream') {
  $body = @{ name = $Name; type = $Type; stream = $Stream; toxicity = $Toxicity; attributes = $Attributes } | ConvertTo-Json -Compress
  $r = Invoke-Toxi POST "/proxies/$Proxy/toxics" $body
  if ($r.Status -ne 200) { throw "toxiproxy: toxic $Name on $Proxy failed (HTTP $($r.Status)) $($r.Text)" }
}

function Remove-Toxic([string]$Proxy, [string]$Name) { [void](Invoke-Toxi DELETE "/proxies/$Proxy/toxics/$Name") }
function Set-ProxyEnabled([string]$Proxy, [bool]$Enabled) {
  $r = Invoke-Toxi POST "/proxies/$Proxy" ('{"enabled":' + $Enabled.ToString().ToLowerInvariant() + '}')
  if ($r.Status -ne 200) { throw "toxiproxy: proxy $Proxy enabled=$Enabled failed (HTTP $($r.Status))" }
}
# All proxies enabled, no toxics.
function Reset-Toxiproxy { try { [void](Invoke-Toxi POST '/reset') } catch { } }
