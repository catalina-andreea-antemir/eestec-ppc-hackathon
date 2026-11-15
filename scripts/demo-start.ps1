# Porneste FileGrid pentru demo si verifica cele trei replici API.
$ErrorActionPreference = 'Stop'
# Verificam codurile de iesire explicit in ambele versiuni PowerShell.
$PSNativeCommandUseErrorActionPreference = $false

function Stop-Demo([string]$Message) {
    [Console]::Error.WriteLine("EROARE: $Message")
    exit 1
}

$curl = Get-Command curl.exe -CommandType Application -ErrorAction SilentlyContinue
if (-not $curl) { Stop-Demo 'curl.exe este necesar pentru verificarea prin nginx.' }

Push-Location -LiteralPath (Join-Path $PSScriptRoot '..')
try {
    # PowerShell 5.1 poate trata stderr de la comenzi native ca eroare chiar si la succes.
    $ErrorActionPreference = 'Continue'
    Write-Host 'Pornesc FileGrid (prima construire poate dura cateva minute)...'
    $engineName = if ($PSVersionTable.PSEdition -eq 'Desktop') { 'powershell.exe' } else { 'pwsh.exe' }
    $engine = Join-Path $PSHOME $engineName
    $startScript = Join-Path $PSScriptRoot 'start.ps1'
    # start.ps1 foloseste Out-Host. Procesul separat permite ascunderea iesirii directe de consola.
    $global:LASTEXITCODE = 1
    & $engine -NoProfile -NonInteractive -File $startScript *> $null
    if ($LASTEXITCODE -ne 0) {
        Stop-Demo 'Pornirea FileGrid a esuat. Vezi detaliile ruland .\scripts\start.ps1 intr-un terminal privat.'
    }

    Write-Host 'Scalez API la 3 replici...'
    & docker compose up -d --no-deps --scale api=3 api *> $null
    if ($LASTEXITCODE -ne 0) {
        Stop-Demo 'Scalarea API la 3 replici a esuat. Verifica serviciul api cu docker compose ps api.'
    }

    $healthFormat = '{{.Name}} {{if and .State.Running .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
    $deadline = (Get-Date).AddSeconds(600)
    while ($true) {
        $ids = @(& docker compose ps --status running -q api 2>$null)
        if ($LASTEXITCODE -ne 0) { Stop-Demo 'Nu am putut lista replicile API.' }
        $healthy = 0
        if ($ids.Count -gt 0) {
            $replicas = @(& docker inspect --type container --format $healthFormat @ids 2>$null)
            if ($LASTEXITCODE -eq 0) {
                $healthy = @($replicas -cmatch '^\S+ healthy$').Count
            }
        }
        if ($ids.Count -eq 3 -and $healthy -eq 3) { break }
        if ((Get-Date) -ge $deadline) {
            Stop-Demo "Timeout dupa 600 s: asteptam 3 replici API healthy, gasite $($ids.Count) pornite si $healthy healthy."
        }
        Start-Sleep -Seconds 3
    }

    $healthUrl = 'https://localhost/api/v1/health'
    $deadline = (Get-Date).AddSeconds(60)
    while ($true) {
        $code = & $curl.Source -ks --max-time 5 -o NUL -w '%{http_code}' $healthUrl 2>$null
        if ($LASTEXITCODE -eq 0 -and $code -eq '200') { break }
        if ((Get-Date) -ge $deadline) {
            Stop-Demo "nginx nu a confirmat HTTP 200 la $healthUrl in 60 s."
        }
        Start-Sleep -Seconds 2
    }

    Write-Host ''
    Write-Host 'Replici API:'
    foreach ($replica in $replicas) {
        Write-Host ('  ' + $replica.TrimStart('/'))
    }
    Write-Host "Health prin nginx: HTTP 200 ($healthUrl)"
    Write-Host 'FileGrid: https://localhost'
    Write-Host 'Grafana:  https://localhost/grafana/'
} finally {
    Pop-Location
}
