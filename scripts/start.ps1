<#
FileGrid PPC - pornirea intregului sistem cu o singura comanda (ADR-002). Din radacina repo-ului:
    .\scripts\start.ps1
1) verifica Docker Compose >= 2.20; 2) creeaza .env cu secrete aleatorii daca lipseste (si completeaza variabilele
care lipsesc); 3) docker compose up -d --build; 4) asteapta health-ul api; 5) afiseaza adresa si utilizatorii de test.
Merge in Windows PowerShell 5.1 si in PowerShell 7.
#>
[CmdletBinding()]
param(
    [int]$HealthTimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repoRoot
$envFile = Join-Path $repoRoot '.env'

function Write-Info([string]$Message) { Write-Host "==> $Message" }
function Stop-WithError([string]$Message) {
    Write-Host "EROARE: $Message" -ForegroundColor Red
    exit 1
}

# Ruleaza o comanda nativa fara ca stderr-ul ei sa devina eroare PowerShell (5.1); intoarce codul de iesire.
function Invoke-Native {
    param([string]$File, [string[]]$Arguments, [switch]$Quiet)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($Quiet) { & $File @Arguments *> $null } else { & $File @Arguments 2>&1 | ForEach-Object { "$_" } | Out-Host }
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
}

function Get-NativeOutput {
    param([string]$File, [string[]]$Arguments)
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $File @Arguments 2>$null
        if ($LASTEXITCODE -ne 0) { return $null }
        return (($out | ForEach-Object { "$_" }) -join "`n").Trim()
    } finally {
        $ErrorActionPreference = $previous
    }
}

# --- 1. Docker si Docker Compose --------------------------------------------------------------------------------
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Stop-WithError 'nu gasesc comanda docker. Instaleaza Docker Desktop.'
}
if ((Invoke-Native docker @('info') -Quiet) -ne 0) {
    Stop-WithError 'Docker nu raspunde. Porneste Docker Desktop si incearca din nou.'
}
$composeVersion = Get-NativeOutput docker @('compose', 'version', '--short')
if (-not $composeVersion) { Stop-WithError 'lipseste pluginul Docker Compose v2 (docker compose).' }
$composeVersion = $composeVersion.TrimStart('v')
if ($composeVersion -notmatch '^(\d+)\.(\d+)') {
    Stop-WithError "nu pot citi versiunea Docker Compose ($composeVersion)."
}
$major = [int]$Matches[1]; $minor = [int]$Matches[2]
if ($major -lt 2 -or ($major -eq 2 -and $minor -lt 20)) {
    Stop-WithError "Docker Compose $composeVersion este prea vechi; e nevoie de minim 2.20 (pentru include)."
}
Write-Info "Docker Compose $composeVersion"

# --- 2. .env cu secrete aleatorii --------------------------------------------------------------------------------
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

# Parola alfanumerica cu cel putin o litera mare, una mica si o cifra (politica SQL Server, CHECK_POLICY = ON).
function New-RandomPassword([int]$Length = 24) {
    $bytes = New-Object byte[] 1
    while ($true) {
        $sb = New-Object System.Text.StringBuilder
        while ($sb.Length -lt $Length) {
            $rng.GetBytes($bytes)
            # 248 = 4 x 62: respingem restul, ca distributia sa fie uniforma.
            if ($bytes[0] -lt 248) { [void]$sb.Append($alphabet[$bytes[0] % 62]) }
        }
        $value = $sb.ToString()
        if ($value -cmatch '[A-Z]' -and $value -cmatch '[a-z]' -and $value -match '[0-9]') { return $value }
    }
}
function New-RandomBase64([int]$Count) {
    $bytes = New-Object byte[] $Count
    $rng.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes)
}

# .env se scrie UTF-8 fara BOM si cu LF, ca sa fie citit identic de Docker Compose si de scripts/start.sh.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function Read-EnvLines {
    if (-not (Test-Path -LiteralPath $envFile)) { return @() }
    $text = [System.IO.File]::ReadAllText($envFile)
    return @($text -split "`r?`n" | Where-Object { $_ -ne '' })
}
function Write-EnvLines([string[]]$Lines) {
    [System.IO.File]::WriteAllText($envFile, (($Lines -join "`n") + "`n"), $utf8NoBom)
}
function Get-EnvValue([string]$Name) {
    $value = ''
    foreach ($line in (Read-EnvLines)) {
        if ($line.StartsWith("$Name=")) { $value = $line.Substring($Name.Length + 1) }
    }
    return $value
}

$createdEnv = $false
if (-not (Test-Path -LiteralPath $envFile)) {
    Write-EnvLines @("# Generat de scripts/start.* la $([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')). NU se comite (e in .gitignore).")
    $createdEnv = $true
}

$script:added = New-Object System.Collections.Generic.List[string]
# Adauga variabila daca lipseste sau e goala; valorile existente nu se schimba niciodata
# (parolele SQL sunt deja in volumul mssql).
function Set-EnvVarIfMissing([string]$Name, [string]$Value) {
    if ((Get-EnvValue $Name) -ne '') { return }
    $lines = @(Read-EnvLines | Where-Object { -not $_.StartsWith("$Name=") })
    Write-EnvLines ($lines + "$Name=$Value")
    $script:added.Add($Name)
}
# Variabilele optionale se scriu o singura data, cu valoarea implicita (pot ramane goale).
function Set-OptionalEnvVar([string]$Name, [string]$Value) {
    $lines = @(Read-EnvLines)
    if (@($lines | Where-Object { $_.StartsWith("$Name=") }).Count -gt 0) { return }
    Write-EnvLines ($lines + "$Name=$Value")
    $script:added.Add($Name)
}

Set-EnvVarIfMissing 'MSSQL_SA_PASSWORD' (New-RandomPassword 24)
Set-EnvVarIfMissing 'META_DB_PASSWORD' (New-RandomPassword 24)
Set-EnvVarIfMissing 'STORAGE_DB_PASSWORD' (New-RandomPassword 24)
Set-OptionalEnvVar 'STORAGE_DB_HOST' 'mssql'
Set-OptionalEnvVar 'STORAGE_DB_SA_PASSWORD' ''
Set-EnvVarIfMissing 'MINIO_ROOT_USER' 'filegrid-root'
Set-EnvVarIfMissing 'MINIO_ROOT_PASSWORD' (New-RandomPassword 32)
Set-EnvVarIfMissing 'MINIO_BUCKET' 'filegrid'
Set-EnvVarIfMissing 'S3_ACCESS_KEY' 'filegrid-app'
Set-EnvVarIfMissing 'S3_SECRET_KEY' (New-RandomPassword 32)
Set-EnvVarIfMissing 'FTP_USER' 'filegrid'
Set-EnvVarIfMissing 'FTP_PASSWORD' (New-RandomPassword 24)
Set-EnvVarIfMissing 'GRAFANA_ADMIN_PASSWORD' (New-RandomPassword 20)
Set-EnvVarIfMissing 'JWT_SECRET' (New-RandomBase64 48)
Set-EnvVarIfMissing 'DOC_ENCRYPTION_KEY' (New-RandomBase64 32)
Set-EnvVarIfMissing 'DOWNLOAD_LINK_SECRET' (New-RandomBase64 48)
$testUsers = [ordered]@{ dispecer = 'DISPATCH'; teren = 'FIELD'; racordari = 'CONNECTIONS'; contor = 'METER_SERVICE'; admin = 'ADMIN' }
foreach ($user in $testUsers.Keys) {
    Set-EnvVarIfMissing ("FILEGRID_USER_{0}_PASSWORD" -f $user.ToUpperInvariant()) (New-RandomPassword 16)
}

if ($createdEnv) { Write-Info ".env creat cu valori aleatorii ($envFile)." }
elseif ($script:added.Count -gt 0) { Write-Info (".env completat cu: " + ($script:added -join ' ')) }
else { Write-Info '.env exista deja; il folosesc neschimbat.' }

# --- 3. Pornire -------------------------------------------------------------------------------------------------
Write-Info 'docker compose up -d --build (prima construire dureaza cateva minute)'
if ((Invoke-Native docker @('compose', 'up', '-d', '--build')) -ne 0) {
    Write-Host "`nUltimele linii din mssql-init si minio-init:"
    [void](Invoke-Native docker @('compose', 'logs', '--no-color', '--tail', '30', 'mssql-init', 'minio-init'))
    Stop-WithError 'docker compose up a esuat (detalii mai sus; docker compose logs <serviciu>).'
}

# --- 4. Asteptarea health-ului ---------------------------------------------------------------------------------
# Healthcheck-ul containerului api foloseste GET /api/v1/health (UP cand api si FileGridMeta raspund); apoi se
# verifica acelasi endpoint prin nginx, pe HTTPS, cum il vede interfata.
Write-Info "astept health-ul api (maxim ${HealthTimeoutSeconds}s)"
$deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
while ($true) {
    $idsText = Get-NativeOutput docker @('compose', 'ps', '-q', 'api')
    $ids = @(); if ($idsText) { $ids = @($idsText -split "`n" | Where-Object { $_ }) }
    $healthy = 0
    foreach ($id in $ids) {
        $status = Get-NativeOutput docker @('inspect', '-f', '{{if .State.Health}}{{.State.Health.Status}}{{end}}', $id)
        if ($status -eq 'healthy') { $healthy++ }
    }
    if ($ids.Count -gt 0 -and $healthy -eq $ids.Count) { break }
    if ((Get-Date) -ge $deadline) {
        [void](Invoke-Native docker @('compose', 'ps', '-a'))
        [void](Invoke-Native docker @('compose', 'logs', '--no-color', '--tail', '40', 'api'))
        Stop-WithError "api nu a devenit healthy in ${HealthTimeoutSeconds}s."
    }
    Start-Sleep -Seconds 3
}
Write-Info "api: $healthy/$($ids.Count) instante healthy"

# Verificarea prin nginx; curl.exe exista in Windows 10/11 (in PowerShell 5.1 `curl` e alias, deci .exe explicit).
$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if ($curl) {
    # nginx re-rezolva api la 5 s, deci primele cereri pot da 502/503 cateva secunde.
    $nginxDeadline = (Get-Date).AddSeconds(60)
    while ($true) {
        $code = Get-NativeOutput $curl.Source @('-ks', '-o', 'NUL', '-w', '%{http_code}', 'https://localhost/api/v1/health')
        if ($code -eq '200') { break }
        if ((Get-Date) -ge $nginxDeadline) {
            [void](Invoke-Native docker @('compose', 'logs', '--no-color', '--tail', '20', 'nginx'))
            Stop-WithError "GET https://localhost/api/v1/health -> $code (api e healthy, dar nginx nu il serveste)."
        }
        Start-Sleep -Seconds 2
    }
    Write-Info 'GET https://localhost/api/v1/health -> 200'
}

# --- 5. Rezumat -------------------------------------------------------------------------------------------------
Write-Host ''
Write-Host 'FileGrid PPC ruleaza:  https://localhost   (certificat self-signed: accepta-l in browser)'
Write-Host 'Grafana:               https://localhost/grafana/   (utilizator admin, parola GRAFANA_ADMIN_PASSWORD din .env)'
Write-Host ''
Write-Host 'Utilizatori de test (parolele sunt in .env):'
foreach ($user in $testUsers.Keys) {
    $password = Get-EnvValue ("FILEGRID_USER_{0}_PASSWORD" -f $user.ToUpperInvariant())
    Write-Host ('  {0,-10} {1,-14} {2}' -f $user, $testUsers[$user], $password)
}
Write-Host ''
Write-Host 'Oprire: docker compose down    Resetare completa (sterge datele): docker compose down -v'
