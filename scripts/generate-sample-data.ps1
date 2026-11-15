<#
.SYNOPSIS
  Generates the FileGrid test data in sample-data/ (task F3).

.DESCRIPTION
  Creates the 4 file categories from the contract (docs/api/openapi.yaml):
    meter-readings/     JSON batches {meterId, zone, readings:[{ts,kwh}]} of 1-50 KB + anomalies-expected.csv
    outage-records/     outage CSV files of 1-10 MB + manifest.json (zone, stationId, occurredAt)
    connection-docs/    small PDFs and PNGs with STRICTLY FICTIONAL personal data
    inspection-videos/  .mp4 files of 100 MiB, 500 MiB and 1.1 GiB (ignored by Git), written as a stream

  Everything except the video payload is deterministic: same seed => same bytes,
  identical to what scripts/generate-sample-data.sh produces.

  Idempotent: existing files are skipped; -Force regenerates them. Videos with the wrong size
  (e.g. after an interruption) are regenerated automatically. Files are first written as .tmp-<name>
  and renamed only at the end, so an existing file is always complete.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\generate-sample-data.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\generate-sample-data.ps1 -SkipLargeVideos
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\generate-sample-data.ps1 -Force -OutputDir D:\date-test
#>
[CmdletBinding()]
param(
    # Output directory (default <repo>/sample-data).
    [string]$OutputDir = '',
    # Regenerate files that already exist.
    [switch]$Force,
    # Skip the 500 MiB and 1.1 GiB videos (keeps the 100 MiB one).
    [switch]$SkipLargeVideos,
    # Skip all videos.
    [switch]$SkipVideos,
    # PRNG seed (same seed => same data).
    [long]$Seed = 20261114
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$Stats = @{ created = 0; skipped = 0 }

# ---------------------------------------------------------------- helpers

# MINSTD (Park-Miller) generator: the bash script uses the same algorithm, so the data is identical.
$script:Rng = 1L
function Set-RngSeed([long]$value) { $script:Rng = ($value % 2147483646L) + 1L }
function Get-Rand([long]$n) {
    $script:Rng = ($script:Rng * 48271L) % 2147483647L
    return $script:Rng % $n
}
function Div([long]$a, [long]$b) { return [long][Math]::Floor($a / $b) }

function Ensure-Dir([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { New-Item -ItemType Directory -Path $path | Out-Null }
}

# Decides whether a file must be (re)generated. $expectedSize > 0 also requires the exact size.
function Need-File([string]$path, [long]$expectedSize = 0) {
    if ($Force) { return $true }
    if (-not (Test-Path -LiteralPath $path)) { return $true }
    $len = (Get-Item -LiteralPath $path).Length
    if ($len -eq 0) { return $true }
    if ($expectedSize -gt 0 -and $len -ne $expectedSize) { return $true }
    $script:Stats.skipped++
    return $false
}

function Tmp-Path([string]$path) {
    return Join-Path (Split-Path -Parent $path) ('.tmp-' + (Split-Path -Leaf $path))
}

function Commit-File([string]$tmp, [string]$path) {
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    Move-Item -LiteralPath $tmp -Destination $path
    $script:Stats.created++
}

function Write-TextFile([string]$path, [string]$text) {
    $tmp = Tmp-Path $path
    [IO.File]::WriteAllText($tmp, $text, $Utf8NoBom)
    Commit-File $tmp $path
}

function Write-BytesFile([string]$path, [byte[]]$bytes) {
    $tmp = Tmp-Path $path
    [IO.File]::WriteAllBytes($tmp, $bytes)
    Commit-File $tmp $path
}

function Pad([long]$v, [int]$w) { return $v.ToString().PadLeft($w, '0') }
function Fmt-Milli([long]$m) { return '{0}.{1}' -f (Div $m 1000), (Pad ($m % 1000) 3) }

# ---------------------------------------------------------------- setup

# In Windows PowerShell 5.1 $PSScriptRoot is not available in param() defaults, so it is resolved here.
if ($OutputDir -eq '') {
    $scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
    $OutputDir = Join-Path (Split-Path -Parent $scriptDir) 'sample-data'
}
Ensure-Dir $OutputDir
$OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
$dirMeter  = Join-Path $OutputDir 'meter-readings'
$dirOutage = Join-Path $OutputDir 'outage-records'
$dirDocs   = Join-Path $OutputDir 'connection-docs'
$dirVideo  = Join-Path $OutputDir 'inspection-videos'
foreach ($d in @($dirMeter, $dirOutage, $dirDocs, $dirVideo)) {
    Ensure-Dir $d
    # leftovers from an interrupted run
    Get-ChildItem -LiteralPath $d -Force -Filter '.tmp-*' | Remove-Item -Force
}
Write-Host "Generating test data in $OutputDir (seed $Seed)"

# ---------------------------------------------------------------- 1. meter readings

# Hourly consumption factor (percent): low at night, peaks in the morning and evening.
$HourFactor = @(60,60,60,60,60,60,80,115,115,100,100,100,100,100,100,100,100,110,125,125,125,125,90,90)

$meters = @(
    @('RO-MUN-0004821','Muntenia'), @('RO-MUN-0009920','Muntenia'), @('RO-MUN-0012345','Muntenia'), @('RO-MUN-0020417','Muntenia'),
    @('RO-BAN-0001377','Banat'),    @('RO-BAN-0003051','Banat'),    @('RO-BAN-0007712','Banat'),    @('RO-BAN-0010458','Banat'),
    @('RO-DOB-0000412','Dobrogea'), @('RO-DOB-0002290','Dobrogea'), @('RO-DOB-0005538','Dobrogea'), @('RO-DOB-0008804','Dobrogea')
)
# Batches: meterId, zone, day of November 2026 (starting 00:00 UTC), number of readings.
$batches = New-Object System.Collections.ArrayList
foreach ($day in 13, 14) {
    foreach ($m in $meters) {
        $count = 96
        # The "silent" meter from the contract: last reading at 10:30 (43 readings), then nothing.
        if ($m[0] -eq 'RO-DOB-0000412' -and $day -eq 14) { $count = 43 }
        [void]$batches.Add(@($m[0], $m[1], $day, $count))
    }
}
[void]$batches.Add(@('RO-MUN-0030001','Muntenia',14,15))
[void]$batches.Add(@('RO-BAN-0030002','Banat',14,15))
[void]$batches.Add(@('RO-DOB-0030003','Dobrogea',14,15))
[void]$batches.Add(@('RO-MUN-0040001','Muntenia',7,700))
[void]$batches.Add(@('RO-DOB-0040002','Dobrogea',7,700))

# Deliberate consumption anomalies (kWh * 1000): "meterId_day_readingIndex" => value.
$Spikes = @{
    'RO-BAN-0001377_14_13'  = 6840   # 2026-11-14T03:15Z, matches the contract example
    'RO-MUN-0009920_14_35'  = 4120   # 2026-11-14T08:45Z, matches the contract example
    'RO-DOB-0002290_13_8'   = 5310   # 2026-11-13T02:00Z
    'RO-BAN-0007712_13_79'  = 4960   # 2026-11-13T19:45Z
    'RO-MUN-0040001_7_300'  = 7250   # 2026-11-10T03:00Z
    'RO-MUN-0040001_7_512'  = 5880   # 2026-11-12T08:00Z
}

$anomalyLines = New-Object System.Collections.ArrayList
[void]$anomalyLines.Add('type,meterId,zone,readingTs,kwh,note')

for ($k = 0; $k -lt $batches.Count; $k++) {
    $meterId = $batches[$k][0]; $zone = $batches[$k][1]; $day = [long]$batches[$k][2]; $count = [long]$batches[$k][3]
    $file = Join-Path $dirMeter ('{0}_2026-11-{1}.json' -f $meterId, (Pad $day 2))

    Set-RngSeed ($Seed + 7919L * $k)
    $base = 150 + (Get-Rand 250)
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append("{`n  `"meterId`": `"$meterId`",`n  `"zone`": `"$zone`",`n  `"readings`": [`n")
    for ($j = 0; $j -lt $count; $j++) {
        $t = 15L * $j
        $d = $day + (Div $t 1440); $hh = Div ($t % 1440) 60; $mm = $t % 60
        $noise = 90 + (Get-Rand 21)
        $milli = Div ($base * $HourFactor[$hh] * $noise) 10000
        $ts = '2026-11-{0}T{1}:{2}:00Z' -f (Pad $d 2), (Pad $hh 2), (Pad $mm 2)
        $key = '{0}_{1}_{2}' -f $meterId, $day, $j
        if ($Spikes.ContainsKey($key)) {
            $milli = $Spikes[$key]
            [void]$anomalyLines.Add(('SPIKE,{0},{1},{2},{3},consum anormal pus intentionat' -f $meterId, $zone, $ts, (Fmt-Milli $milli)))
        }
        $sep = ','
        if ($j -eq $count - 1) { $sep = '' }
        [void]$sb.Append("    {`n      `"ts`": `"$ts`",`n      `"kwh`": $(Fmt-Milli $milli)`n    }$sep`n")
        if ($meterId -eq 'RO-DOB-0000412' -and $j -eq $count - 1 -and $count -eq 43) {
            [void]$anomalyLines.Add(('SILENT,{0},{1},{2},{3},ultima citire; contorul nu mai trimite nimic dupa acest moment' -f $meterId, $zone, $ts, (Fmt-Milli $milli)))
        }
    }
    [void]$sb.Append("  ]`n}`n")
    if (Need-File $file) { Write-TextFile $file $sb.ToString() }
}
$anomFile = Join-Path $dirMeter 'anomalies-expected.csv'
if (Need-File $anomFile) { Write-TextFile $anomFile (($anomalyLines -join "`n") + "`n") }
Write-Host ("  meter-readings:    {0} JSON batches" -f $batches.Count)

# ---------------------------------------------------------------- 2. outages (1-10 MB)

# Deliberately uneven distribution: Muntenia 7, Dobrogea 4, Banat 2 (peaks at 8-9 in Muntenia/Dobrogea, 14 in Banat).
# idx, zone, stationId ('' = no station), day, hour, minute, target size in KiB
$outages = @(
    @(1,'Muntenia','ST-BRASOV-114',14,9,32,4608),
    @(2,'Muntenia','ST-PLOIESTI-031',14,8,47,9600),
    @(3,'Muntenia','ST-PITESTI-207',13,9,5,2048),
    @(4,'Muntenia','ST-BRASOV-114',12,8,20,7168),
    @(5,'Muntenia','ST-BUCURESTI-NORD-002',11,9,58,3072),
    @(6,'Muntenia','',10,14,15,1100),
    @(7,'Muntenia','ST-PLOIESTI-031',9,8,5,5120),
    @(8,'Dobrogea','ST-CONSTANTA-020',14,8,10,6144),
    @(9,'Dobrogea','ST-TULCEA-008',13,8,40,2560),
    @(10,'Dobrogea','ST-MEDGIDIA-016',12,19,25,9216),
    @(11,'Dobrogea','ST-CONSTANTA-020',8,8,55,1536),
    @(12,'Banat','ST-TIMISOARA-045',13,14,30,3584),
    @(13,'Banat','ST-RESITA-012',11,14,5,8192)
)

function Outage-Name($o) {
    if ($o[2] -eq '') { return 'avarie-{0}-{1}-fara-statie.csv' -f (Pad $o[0] 2), $o[1] }
    return 'avarie-{0}-{1}.csv' -f (Pad $o[0] 2), $o[2]
}

$manifest = New-Object System.Collections.ArrayList
foreach ($o in $outages) {
    $idx = [long]$o[0]; $zone = $o[1]; $station = $o[2]
    $day = [long]$o[3]; $hour = [long]$o[4]; $minute = [long]$o[5]; $target = [long]$o[6] * 1024L
    $name = Outage-Name $o
    $file = Join-Path $dirOutage $name
    $occurredAt = '2026-11-{0}T{1}:{2}:00Z' -f (Pad $day 2), (Pad $hour 2), (Pad $minute 2)

    if (Need-File $file) {
        Set-RngSeed ($Seed + 1000003L * $idx)
        $outRows = 2400 + (Get-Rand 4800)          # the outage lasts 10-30 minutes (one row every 250 ms)
        $feeder = 'F0' + (($idx % 6) + 1)
        $faultIdx = 2400                           # the fault occurs 10 minutes into the file
        $t0 = (($day * 24 + $hour) * 60 + $minute) * 60000 - 600000
        $tmp = Tmp-Path $file
        $w = New-Object System.IO.StreamWriter($tmp, $false, $Utf8NoBom, 1048576)
        $w.NewLine = "`n"
        $header = 'ts,stationId,feeder,voltageKv,currentA,frequencyHz,event'
        $w.Write($header + "`n")
        $written = [long]($header.Length + 1)
        $rng = $script:Rng
        $i = 0L
        while ($written -lt $target) {
            $rng = ($rng * 48271L) % 2147483647L
            $v = 10940 + ($rng % 121)
            $cur = 250 + ([long][Math]::Floor($rng / 121) % 250)
            $f = 4995 + ([long][Math]::Floor($rng / 30250) % 10)
            $ev = 'NORMAL'
            if ($i -eq $faultIdx) { $ev = 'FAULT'; $v = [long][Math]::Floor($v / 2); $cur = $cur * 6 }
            elseif ($i -gt $faultIdx -and $i -lt $faultIdx + $outRows) {
                $v = 0; $cur = 0; $f = 0
                if ($i -eq $faultIdx + 1) { $ev = 'TRIP' } else { $ev = 'OUTAGE' }
            }
            elseif ($i -eq $faultIdx + $outRows) { $ev = 'RECLOSE' }
            $ts = $t0 + $i * 250
            $d = [long][Math]::Floor($ts / 86400000); $r = $ts % 86400000
            $line = [string]::Format('2026-11-{0:D2}T{1:D2}:{2:D2}:{3:D2}.{4:D3}Z,{5},{6},{7}.{8:D2},{9},{10}.{11:D2},{12}',
                $d, [long][Math]::Floor($r / 3600000), [long][Math]::Floor(($r % 3600000) / 60000),
                [long][Math]::Floor(($r % 60000) / 1000), $r % 1000, $station, $feeder,
                [long][Math]::Floor($v / 100), $v % 100, $cur, [long][Math]::Floor($f / 100), $f % 100, $ev)
            $w.Write($line + "`n")
            $written += $line.Length + 1
            $i++
        }
        $w.Close()
        Commit-File $tmp $file
    }
    $size = (Get-Item -LiteralPath $file).Length
    $stationJson = 'null'
    if ($station -ne '') { $stationJson = "`"$station`"" }
    [void]$manifest.Add(("    {{`n      `"fileName`": `"{0}`",`n      `"category`": `"OUTAGE_RECORD`",`n      `"zone`": `"{1}`",`n      `"stationId`": {2},`n      `"occurredAt`": `"{3}`",`n      `"sizeBytes`": {4}`n    }}" -f $name, $zone, $stationJson, $occurredAt, $size))
}
# The manifest is always rewritten (it is small and reflects the actual sizes on disk).
Write-TextFile (Join-Path $dirOutage 'manifest.json') ("{`n  `"files`": [`n" + ($manifest -join ",`n") + "`n  ]`n}`n")
Write-Host ("  outage-records:    {0} CSV files + manifest.json" -f $outages.Count)

# ---------------------------------------------------------------- 3. connection documents (fictional)

function New-Pdf([string[]]$lines) {
    # Minimal PDF 1.4, one A4 page, Helvetica, ASCII only (so xref offsets = character counts).
    $content = "BT`n/F1 11 Tf`n50 790 Td`n16 TL`n"
    foreach ($l in $lines) {
        $esc = $l.Replace('\', '\\').Replace('(', '\(').Replace(')', '\)')
        $content += "($esc) Tj T*`n"
    }
    $content += "ET`n"
    $objs = @(
        "<< /Type /Catalog /Pages 2 0 R >>",
        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        "<< /Length $($content.Length) >>`nstream`n$($content)endstream"
    )
    $pdf = "%PDF-1.4`n"
    $offsets = @()
    for ($n = 0; $n -lt $objs.Count; $n++) {
        $offsets += $pdf.Length
        $pdf += "$($n + 1) 0 obj`n$($objs[$n])`nendobj`n"
    }
    $xref = $pdf.Length
    $pdf += "xref`n0 $($objs.Count + 1)`n0000000000 65535 f `n"
    foreach ($off in $offsets) { $pdf += (Pad $off 10) + " 00000 n `n" }
    $pdf += "trailer`n<< /Size $($objs.Count + 1) /Root 1 0 R >>`nstartxref`n$xref`n%%EOF`n"
    return [Text.Encoding]::ASCII.GetBytes($pdf)
}

$CrcTable = New-Object 'uint32[]' 256
for ($n = 0; $n -lt 256; $n++) {
    [uint32]$c = $n
    for ($b = 0; $b -lt 8; $b++) {
        if ($c -band 1) { $c = [uint32](0xEDB88320L -bxor ($c -shr 1)) } else { $c = $c -shr 1 }
    }
    $CrcTable[$n] = $c
}
function Get-Crc32([byte[]]$data) {
    [uint32]$c = 0xFFFFFFFFL
    foreach ($x in $data) { $c = $CrcTable[($c -bxor $x) -band 0xFF] -bxor ($c -shr 8) }
    return [uint32]($c -bxor 0xFFFFFFFFL)
}
function BE32([long]$v) { return [byte[]]@((($v -shr 24) -band 0xFF), (($v -shr 16) -band 0xFF), (($v -shr 8) -band 0xFF), ($v -band 0xFF)) }
function Png-Chunk([string]$type, [byte[]]$data) {
    $td = [byte[]]([Text.Encoding]::ASCII.GetBytes($type) + $data)
    return [byte[]]((BE32 $data.Length) + $td + (BE32 (Get-Crc32 $td)))
}

# 8-bit grayscale PNG with "stored" (uncompressed) deflate blocks => byte-identical to the bash version.
function New-Png([int]$width, [int]$height, [scriptblock]$pixel) {
    $raw = New-Object 'byte[]' (($width + 1) * $height)
    $p = 0
    for ($y = 0; $y -lt $height; $y++) {
        $raw[$p++] = 0
        for ($x = 0; $x -lt $width; $x++) { $raw[$p++] = [byte](& $pixel $x $y) }
    }
    [long]$a = 1; [long]$b = 0
    foreach ($x in $raw) { $a = ($a + $x) % 65521; $b = ($b + $a) % 65521 }
    $len = $raw.Length
    $z = [byte[]](@(0x78, 0x01, 0x01, ($len -band 0xFF), ($len -shr 8), ((-bnot $len) -band 0xFF), (((-bnot $len) -shr 8) -band 0xFF)) + $raw + (BE32 ($b * 65536 + $a)))
    $ihdr = [byte[]]((BE32 $width) + (BE32 $height) + @(8, 0, 0, 0, 0))
    $sig = [byte[]]@(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    return [byte[]]($sig + (Png-Chunk 'IHDR' $ihdr) + (Png-Chunk 'IDAT' $z) + (Png-Chunk 'IEND' (New-Object 'byte[]' 0)))
}

$fict = 'DOCUMENT FICTIV - DATE DE TEST GENERATE, NU APARTIN NICIUNEI PERSOANE REALE'
$docs = @(
    @('cerere-racordare-fictiv-001.pdf', @(
        $fict, '', 'CERERE DE RACORDARE LA RETEAUA DE DISTRIBUTIE', 'Nr. inregistrare: TEST-2026-0001 / 14.11.2026', '',
        'Solicitant: Andrei Testescu', 'CNP: 0000000000001 (fictiv)', 'Adresa: Str. Exemplului nr. 1, Localitatea Fictiva, jud. Test',
        'Telefon: 0700 000 001', 'E-mail: andrei.testescu@example.invalid', '',
        'Loc de consum: locuinta individuala, Str. Exemplului nr. 1', 'Putere solicitata: 10 kW, monofazat', 'Zona: Muntenia',
        '', 'Semnatura: (fictiv)')),
    @('aviz-tehnic-racordare-fictiv-002.pdf', @(
        $fict, '', 'AVIZ TEHNIC DE RACORDARE', 'Nr. aviz: ATR-TEST-0002 / 14.11.2026', '',
        'Beneficiar: Maria Exemplu', 'CNP: 0000000000002 (fictiv)', 'Adresa: Bd. Demonstratiei nr. 22, Orasul Inexistent, jud. Test',
        '', 'Punct de racordare: ST-CONSTANTA-020, fider F03', 'Putere aprobata: 15 kW, trifazat', 'Zona: Dobrogea',
        'Termen de realizare: 60 de zile')),
    @('contract-racordare-fictiv-003.pdf', @(
        $fict, '', 'CONTRACT DE RACORDARE', 'Nr. contract: CR-TEST-0003 / 14.11.2026', '',
        'Utilizator: SC Firma Fictiva SRL (CUI fictiv RO00000000)', 'Reprezentant: Ion Probescu, CNP 0000000000003 (fictiv)',
        'Sediu: Str. Nicaieri nr. 7, Satul Demo, jud. Test', 'IBAN: RO00TEST0000000000000000 (fictiv)', '',
        'Tarif de racordare: 4.250,00 lei', 'Zona: Banat')),
    @('certificat-racordare-fictiv-004.pdf', @(
        $fict, '', 'CERTIFICAT DE RACORDARE', 'Nr. certificat: CER-TEST-0004 / 14.11.2026', '',
        'Titular: Elena Fictivescu', 'CNP: 0000000000004 (fictiv)', 'Loc de consum: Aleea Probelor nr. 3, Comuna Test, jud. Test',
        'Contor montat: RO-MUN-0004821', 'Putere maxima simultan absorbita: 7 kW'))
)
foreach ($doc in $docs) {
    $file = Join-Path $dirDocs $doc[0]
    if (Need-File $file) { Write-BytesFile $file (New-Pdf $doc[1]) }
}

# Fictional site plan: border, grid, a "building" and a connection line.
$file = Join-Path $dirDocs 'plan-situatie-fictiv-005.png'
if (Need-File $file) {
    Write-BytesFile $file (New-Png 160 100 {
        param($x, $y)
        if ($x -lt 2 -or $y -lt 2 -or $x -ge 158 -or $y -ge 98) { return 0 }
        if ($x -ge 50 -and $x -lt 110 -and $y -ge 30 -and $y -lt 70) {
            if ($x -lt 52 -or $x -ge 108 -or $y -lt 32 -or $y -ge 68) { return 0 }
            return 160
        }
        if ($y -eq 50 -and $x -ge 110) { return 40 }
        if (($x % 20) -eq 0 -or ($y % 20) -eq 0) { return 200 }
        return 255
    })
}
# Fictional "signature": a zigzag curve on a white background.
$file = Join-Path $dirDocs 'semnatura-fictiv-006.png'
if (Need-File $file) {
    Write-BytesFile $file (New-Png 120 40 {
        param($x, $y)
        $p = $x % 24
        if ($p -lt 12) { $yc = 8 + 2 * $p } else { $yc = 8 + 2 * (24 - $p) }
        if ([Math]::Abs($y - $yc) -le 1 -and $x -ge 6 -and $x -lt 114) { return 0 }
        return 255
    })
}
Write-Host ("  connection-docs:   {0} PDF + 2 PNG (fictional data)" -f $docs.Count)

# ---------------------------------------------------------------- 4. inspection videos

# An "ftyp" header so the file is recognized as MP4; the rest is pseudo-random bytes written in 4 MiB blocks
# (the file is never held in memory). Each block is stamped with its index, so chunks differ.
function Write-Video([string]$path, [long]$size) {
    $tmp = Tmp-Path $path
    $fs = New-Object System.IO.FileStream($tmp, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None, 1048576)
    try {
        $fs.SetLength($size)
        $ftyp = [byte[]]@(0,0,0,0x18,0x66,0x74,0x79,0x70,0x6D,0x70,0x34,0x32,0,0,0,0,0x6D,0x70,0x34,0x32,0x69,0x73,0x6F,0x6D)
        $fs.Write($ftyp, 0, $ftyp.Length)
        $block = New-Object 'byte[]' 4194304
        (New-Object System.Random([int]($Seed % 2147483647))).NextBytes($block)
        $left = $size - $ftyp.Length
        $n = 0L
        while ($left -gt 0) {
            [BitConverter]::GetBytes($n).CopyTo($block, 0)
            $len = [int][Math]::Min([long]$block.Length, $left)
            $fs.Write($block, 0, $len)
            $left -= $len
            $n++
        }
    } finally { $fs.Close() }
    Commit-File $tmp $path
}

$videos = @(
    @('drona-inspectie-100MB.mp4', 104857600L, $false),
    @('drona-inspectie-500MB.mp4', 524288000L, $true),
    @('drona-linie-110kV-tronson-7.mp4', 1181116006L, $true)   # 1.1 GiB, name and size from the contract example
)
foreach ($v in $videos) {
    if ($SkipVideos -or ($SkipLargeVideos -and $v[2])) { Write-Host "  inspection-videos: skipping $($v[0])"; continue }
    $file = Join-Path $dirVideo $v[0]
    if (Need-File $file $v[1]) {
        Write-Host ("  inspection-videos: writing {0} ({1:N0} bytes)..." -f $v[0], $v[1])
        Write-Video $file $v[1]
    }
}

Write-Host ("Done: {0} files created, {1} already existed (use -Force to regenerate them)." -f $Stats.created, $Stats.skipped)
