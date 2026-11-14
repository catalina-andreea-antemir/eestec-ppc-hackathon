#!/usr/bin/env bash
# Genereaza datele de test FileGrid in sample-data/ (taskul F3). Echivalentul bash al lui generate-sample-data.ps1.
#
#   meter-readings/     loturi JSON {meterId, zone, readings:[{ts,kwh}]} de 1-50 KB + anomalies-expected.csv
#   outage-records/     fisiere CSV de avarie de 1-10 MB + manifest.json (zone, stationId, occurredAt)
#   connection-docs/    PDF-uri si PNG-uri mici, cu date personale EXCLUSIV FICTIVE
#   inspection-videos/  .mp4 de 100 MiB, 500 MiB si 1,1 GiB (ignorate de Git), scrise in streaming
#
# Datele (in afara de continutul video-urilor) sunt deterministe si identice octet cu octet cu cele din
# varianta PowerShell. Idempotent: fisierele existente sunt sarite; --force le regenereaza.
#
# Utilizare:
#   bash scripts/generate-sample-data.sh [--out DIR] [--force] [--skip-large-videos] [--skip-videos] [--seed N]
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$(dirname "$SCRIPT_DIR")/sample-data"
FORCE=0; SKIP_LARGE=0; SKIP_VIDEOS=0; SEED=20261114

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --skip-large-videos) SKIP_LARGE=1; shift ;;
    --skip-videos) SKIP_VIDEOS=1; shift ;;
    --seed) SEED="$2"; shift 2 ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "Parametru necunoscut: $1 (vezi --help)" >&2; exit 2 ;;
  esac
done

CREATED=0; SKIPPED=0

# ---------------------------------------------------------------- utilitare

# Generator MINSTD (Park-Miller), acelasi ca in varianta PowerShell.
RNG=1
rng_seed() { RNG=$(( ($1 % 2147483646) + 1 )); }
rand() { RNG=$(( RNG * 48271 % 2147483647 )); R=$(( RNG % $1 )); }   # rezultatul in $R

file_size() { wc -c < "$1" | tr -d ' '; }

# need_file CALE [MARIME_EXACTA] -> 0 daca fisierul trebuie (re)generat
need_file() {
  local path="$1" expected="${2:-0}" len
  [ "$FORCE" = 1 ] && return 0
  [ -f "$path" ] || return 0
  len=$(file_size "$path")
  [ "$len" -eq 0 ] && return 0
  [ "$expected" -gt 0 ] && [ "$len" -ne "$expected" ] && return 0
  SKIPPED=$((SKIPPED + 1))
  return 1
}
tmp_path() { echo "$(dirname "$1")/.tmp-$(basename "$1")"; }
commit_file() { mv -f "$1" "$2"; CREATED=$((CREATED + 1)); }
write_text() { local tmp; tmp=$(tmp_path "$1"); printf '%s' "$2" > "$tmp"; commit_file "$tmp" "$1"; }
fmt_milli() { printf -v M '%d.%03d' $(( $1 / 1000 )) $(( $1 % 1000 )); }   # rezultatul in $M

# ---------------------------------------------------------------- pregatire

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
D_METER="$OUT/meter-readings"; D_OUTAGE="$OUT/outage-records"; D_DOCS="$OUT/connection-docs"; D_VIDEO="$OUT/inspection-videos"
for d in "$D_METER" "$D_OUTAGE" "$D_DOCS" "$D_VIDEO"; do
  mkdir -p "$d"
  rm -f "$d"/.tmp-*    # resturi de la o rulare intrerupta
done
echo "Generez date de test in $OUT (samanta $SEED)"

# ---------------------------------------------------------------- 1. citiri contoare

HOUR_FACTOR=(60 60 60 60 60 60 80 115 115 100 100 100 100 100 100 100 100 110 125 125 125 125 90 90)
METERS=(RO-MUN-0004821:Muntenia RO-MUN-0009920:Muntenia RO-MUN-0012345:Muntenia RO-MUN-0020417:Muntenia
        RO-BAN-0001377:Banat RO-BAN-0003051:Banat RO-BAN-0007712:Banat RO-BAN-0010458:Banat
        RO-DOB-0000412:Dobrogea RO-DOB-0002290:Dobrogea RO-DOB-0005538:Dobrogea RO-DOB-0008804:Dobrogea)
# Loturi: meterId:zona:ziua din noiembrie 2026 (start 00:00 UTC):numar de citiri
BATCHES=()
for day in 13 14; do
  for m in "${METERS[@]}"; do
    count=96
    # Contorul "tacut" din contract: ultima citire la 10:30 (43 de citiri), apoi nimic.
    [ "${m%%:*}" = RO-DOB-0000412 ] && [ "$day" = 14 ] && count=43
    BATCHES+=("$m:$day:$count")
  done
done
BATCHES+=(RO-MUN-0030001:Muntenia:14:15 RO-BAN-0030002:Banat:14:15 RO-DOB-0030003:Dobrogea:14:15
          RO-MUN-0040001:Muntenia:7:700 RO-DOB-0040002:Dobrogea:7:700)

# Consumuri anormale (kWh * 1000) puse intentionat: "meterId_zi_indexCitire" => valoare.
spike_for() {
  case "$1" in
    RO-BAN-0001377_14_13) S=6840 ;;   # 2026-11-14T03:15Z, exact ca exemplul din contract
    RO-MUN-0009920_14_35) S=4120 ;;   # 2026-11-14T08:45Z, exact ca exemplul din contract
    RO-DOB-0002290_13_8)  S=5310 ;;   # 2026-11-13T02:00Z
    RO-BAN-0007712_13_79) S=4960 ;;   # 2026-11-13T19:45Z
    RO-MUN-0040001_7_300) S=7250 ;;   # 2026-11-10T03:00Z
    RO-MUN-0040001_7_512) S=5880 ;;   # 2026-11-12T08:00Z
    *) S=0 ;;
  esac
}

ANOM=$'type,meterId,zone,readingTs,kwh,note\n'
NL=$'\n'
k=0
for b in "${BATCHES[@]}"; do
  IFS=: read -r meter zone day count <<< "$b"
  printf -v file '%s/%s_2026-11-%02d.json' "$D_METER" "$meter" "$day"
  rng_seed $(( SEED + 7919 * k ))
  rand 250; base=$(( 150 + R ))
  json="{$NL  \"meterId\": \"$meter\",$NL  \"zone\": \"$zone\",$NL  \"readings\": [$NL"
  for (( j = 0; j < count; j++ )); do
    t=$(( 15 * j )); d=$(( day + t / 1440 )); hh=$(( (t % 1440) / 60 )); mm=$(( t % 60 ))
    rand 21; milli=$(( base * HOUR_FACTOR[hh] * (90 + R) / 10000 ))
    printf -v ts '2026-11-%02dT%02d:%02d:00Z' "$d" "$hh" "$mm"
    spike_for "${meter}_${day}_${j}"
    if [ "$S" -gt 0 ]; then
      milli=$S; fmt_milli "$milli"
      ANOM+="SPIKE,$meter,$zone,$ts,$M,consum anormal pus intentionat$NL"
    fi
    fmt_milli "$milli"
    sep=','; [ "$j" -eq $(( count - 1 )) ] && sep=''
    json+="    {$NL      \"ts\": \"$ts\",$NL      \"kwh\": $M$NL    }$sep$NL"
    if [ "$meter" = RO-DOB-0000412 ] && [ "$j" -eq $(( count - 1 )) ] && [ "$count" -eq 43 ]; then
      ANOM+="SILENT,$meter,$zone,$ts,$M,ultima citire; contorul nu mai trimite nimic dupa acest moment$NL"
    fi
  done
  json+="  ]$NL}$NL"
  if need_file "$file"; then write_text "$file" "$json"; fi
  k=$(( k + 1 ))
done
if need_file "$D_METER/anomalies-expected.csv"; then write_text "$D_METER/anomalies-expected.csv" "$ANOM"; fi
echo "  meter-readings:    ${#BATCHES[@]} loturi JSON"

# ---------------------------------------------------------------- 2. avarii (1-10 MB)

# Distributie intentionat inegala: Muntenia 7, Dobrogea 4, Banat 2 (varfuri la 8-9 in Muntenia/Dobrogea, 14 in Banat).
# idx:zona:stationId (gol = fara statie):ziua:ora:minutul:marimea tinta in KiB
OUTAGES=(
  1:Muntenia:ST-BRASOV-114:14:9:32:4608
  2:Muntenia:ST-PLOIESTI-031:14:8:47:9600
  3:Muntenia:ST-PITESTI-207:13:9:5:2048
  4:Muntenia:ST-BRASOV-114:12:8:20:7168
  5:Muntenia:ST-BUCURESTI-NORD-002:11:9:58:3072
  6:Muntenia::10:14:15:1100
  7:Muntenia:ST-PLOIESTI-031:9:8:5:5120
  8:Dobrogea:ST-CONSTANTA-020:14:8:10:6144
  9:Dobrogea:ST-TULCEA-008:13:8:40:2560
  10:Dobrogea:ST-MEDGIDIA-016:12:19:25:9216
  11:Dobrogea:ST-CONSTANTA-020:8:8:55:1536
  12:Banat:ST-TIMISOARA-045:13:14:30:3584
  13:Banat:ST-RESITA-012:11:14:5:8192
)

MANIFEST=""
for o in "${OUTAGES[@]}"; do
  IFS=: read -r idx zone station day hour minute kib <<< "$o"
  if [ -z "$station" ]; then printf -v name 'avarie-%02d-%s-fara-statie.csv' "$idx" "$zone"
  else printf -v name 'avarie-%02d-%s.csv' "$idx" "$station"; fi
  file="$D_OUTAGE/$name"
  printf -v occurred '2026-11-%02dT%02d:%02d:00Z' "$day" "$hour" "$minute"

  if need_file "$file"; then
    rng_seed $(( SEED + 1000003 * idx ))
    rand 4800; out_rows=$(( 2400 + R ))     # avaria dureaza 10-30 de minute (randuri la 250 ms)
    t0=$(( ((day * 24 + hour) * 60 + minute) * 60000 - 600000 ))
    tmp=$(tmp_path "$file")
    # awk scrie rapid sute de mii de randuri; aritmetica e exacta (valori < 2^53).
    awk -v rng="$RNG" -v out_rows="$out_rows" -v t0="$t0" -v target=$(( kib * 1024 )) \
        -v station="$station" -v feeder="F0$(( idx % 6 + 1 ))" 'BEGIN {
      header = "ts,stationId,feeder,voltageKv,currentA,frequencyHz,event"
      printf "%s\n", header
      written = length(header) + 1; fault = 2400
      for (i = 0; written < target; i++) {
        rng = (rng * 48271) % 2147483647
        v = 10940 + rng % 121; cur = 250 + int(rng / 121) % 250; f = 4995 + int(rng / 30250) % 10
        ev = "NORMAL"
        if (i == fault) { ev = "FAULT"; v = int(v / 2); cur = cur * 6 }
        else if (i > fault && i < fault + out_rows) { v = 0; cur = 0; f = 0; ev = (i == fault + 1) ? "TRIP" : "OUTAGE" }
        else if (i == fault + out_rows) ev = "RECLOSE"
        ts = t0 + i * 250; d = int(ts / 86400000); r = ts % 86400000
        line = sprintf("2026-11-%02dT%02d:%02d:%02d.%03dZ,%s,%s,%d.%02d,%d,%d.%02d,%s",
          d, int(r / 3600000), int((r % 3600000) / 60000), int((r % 60000) / 1000), r % 1000,
          station, feeder, int(v / 100), v % 100, cur, int(f / 100), f % 100, ev)
        printf "%s\n", line
        written += length(line) + 1
      }
    }' > "$tmp"
    commit_file "$tmp" "$file"
  fi
  size=$(file_size "$file")
  if [ -z "$station" ]; then sj=null; else sj="\"$station\""; fi
  [ -n "$MANIFEST" ] && MANIFEST+=",$NL"
  MANIFEST+="    {$NL      \"fileName\": \"$name\",$NL      \"category\": \"OUTAGE_RECORD\",$NL      \"zone\": \"$zone\",$NL      \"stationId\": $sj,$NL      \"occurredAt\": \"$occurred\",$NL      \"sizeBytes\": $size$NL    }"
done
# Manifestul se rescrie mereu (e mic si reflecta marimile reale de pe disc).
write_text "$D_OUTAGE/manifest.json" "{$NL  \"files\": [$NL$MANIFEST$NL  ]$NL}$NL"
echo "  outage-records:    ${#OUTAGES[@]} fisiere CSV + manifest.json"

# ---------------------------------------------------------------- 3. documente de racordare (fictive)

# PDF 1.4 minimal, o pagina A4, Helvetica, doar ASCII (offset-urile xref = numar de caractere).
new_pdf() {   # new_pdf FISIER linie1 linie2 ...
  local file="$1"; shift
  local content=$'BT\n/F1 11 Tf\n50 790 Td\n16 TL\n' l esc pdf xref n
  for l in "$@"; do
    esc="${l//\\/\\\\}"; esc="${esc//(/\\(}"; esc="${esc//)/\\)}"
    content+="($esc) Tj T*$NL"
  done
  content+="ET$NL"
  local objs=(
    "<< /Type /Catalog /Pages 2 0 R >>"
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    "<< /Length ${#content} >>${NL}stream${NL}${content}endstream"
  )
  local offsets=()
  pdf="%PDF-1.4$NL"
  for (( n = 0; n < ${#objs[@]}; n++ )); do
    offsets+=("${#pdf}")
    pdf+="$(( n + 1 )) 0 obj$NL${objs[n]}${NL}endobj$NL"
  done
  xref=${#pdf}
  pdf+="xref${NL}0 $(( ${#objs[@]} + 1 ))${NL}0000000000 65535 f $NL"
  for n in "${offsets[@]}"; do printf -v l '%010d 00000 n \n' "$n"; pdf+="$l"; done
  pdf+="trailer$NL<< /Size $(( ${#objs[@]} + 1 )) /Root 1 0 R >>${NL}startxref$NL$xref$NL%%EOF$NL"
  write_text "$file" "$pdf"
}

CRC_TABLE=()
for (( n = 0; n < 256; n++ )); do
  c=$n
  for (( b = 0; b < 8; b++ )); do
    if (( c & 1 )); then c=$(( 0xEDB88320 ^ (c >> 1) )); else c=$(( c >> 1 )); fi
  done
  CRC_TABLE[n]=$c
done
# crc32 peste elementele tabloului BYTES (octeti ca numere) -> $CRC
crc32_bytes() {
  local c=0xFFFFFFFF x
  for x in "${BYTES[@]}"; do c=$(( CRC_TABLE[(c ^ x) & 255] ^ (c >> 8) )); done
  CRC=$(( c ^ 0xFFFFFFFF ))
}
be32() { BE=($(( ($1 >> 24) & 255 )) $(( ($1 >> 16) & 255 )) $(( ($1 >> 8) & 255 )) $(( $1 & 255 ))); }
# png_chunk TIP -> adauga in PNG (tablou) chunk-ul cu datele din DATA (tablou)
png_chunk() {
  local t="$1" i
  be32 ${#DATA[@]}; PNG+=("${BE[@]}")
  BYTES=()
  for (( i = 0; i < ${#t}; i++ )); do printf -v x '%d' "'${t:i:1}"; BYTES+=("$x"); done
  BYTES+=("${DATA[@]}")
  PNG+=("${BYTES[@]}")
  crc32_bytes; be32 "$CRC"; PNG+=("${BE[@]}")
}
# new_png FISIER LATIME INALTIME FUNCTIE_PIXEL (functia primeste x y si pune valoarea in $PX)
new_png() {
  local file="$1" w="$2" h="$3" fn="$4" x y a=1 bb=0 v len esc tmp
  local raw=()
  for (( y = 0; y < h; y++ )); do
    raw+=(0)
    for (( x = 0; x < w; x++ )); do "$fn" "$x" "$y"; raw+=("$PX"); done
  done
  for v in "${raw[@]}"; do a=$(( (a + v) % 65521 )); bb=$(( (bb + a) % 65521 )); done
  len=${#raw[@]}
  PNG=(137 80 78 71 13 10 26 10)
  be32 "$w"; DATA=("${BE[@]}"); be32 "$h"; DATA+=("${BE[@]}" 8 0 0 0 0); png_chunk IHDR
  DATA=(120 1 1 $(( len & 255 )) $(( len >> 8 )) $(( ~len & 255 )) $(( (~len >> 8) & 255 )) "${raw[@]}")
  be32 $(( bb * 65536 + a )); DATA+=("${BE[@]}"); png_chunk IDAT
  DATA=(); png_chunk IEND
  esc=""
  for v in "${PNG[@]}"; do printf -v x '\\x%02x' "$v"; esc+="$x"; done
  tmp=$(tmp_path "$file"); printf '%b' "$esc" > "$tmp"; commit_file "$tmp" "$file"
}

# Plan de situatie fictiv: chenar, grila, o "cladire" si o linie de racord.
px_plan() {
  local x=$1 y=$2
  if (( x < 2 || y < 2 || x >= 158 || y >= 98 )); then PX=0; return; fi
  if (( x >= 50 && x < 110 && y >= 30 && y < 70 )); then
    if (( x < 52 || x >= 108 || y < 32 || y >= 68 )); then PX=0; else PX=160; fi
    return
  fi
  if (( y == 50 && x >= 110 )); then PX=40; return; fi
  if (( x % 20 == 0 || y % 20 == 0 )); then PX=200; return; fi
  PX=255
}
# "Semnatura" fictiva: o curba in zigzag pe fundal alb.
px_sign() {
  local x=$1 y=$2 p yc dy
  p=$(( x % 24 ))
  if (( p < 12 )); then yc=$(( 8 + 2 * p )); else yc=$(( 8 + 2 * (24 - p) )); fi
  dy=$(( y - yc )); (( dy < 0 )) && dy=$(( -dy ))
  if (( dy <= 1 && x >= 6 && x < 114 )); then PX=0; else PX=255; fi
}

FICT='DOCUMENT FICTIV - DATE DE TEST GENERATE, NU APARTIN NICIUNEI PERSOANE REALE'
f="$D_DOCS/cerere-racordare-fictiv-001.pdf"
need_file "$f" && new_pdf "$f" "$FICT" '' 'CERERE DE RACORDARE LA RETEAUA DE DISTRIBUTIE' 'Nr. inregistrare: TEST-2026-0001 / 14.11.2026' '' \
  'Solicitant: Andrei Testescu' 'CNP: 0000000000001 (fictiv)' 'Adresa: Str. Exemplului nr. 1, Localitatea Fictiva, jud. Test' \
  'Telefon: 0700 000 001' 'E-mail: andrei.testescu@example.invalid' '' \
  'Loc de consum: locuinta individuala, Str. Exemplului nr. 1' 'Putere solicitata: 10 kW, monofazat' 'Zona: Muntenia' \
  '' 'Semnatura: (fictiv)'
f="$D_DOCS/aviz-tehnic-racordare-fictiv-002.pdf"
need_file "$f" && new_pdf "$f" "$FICT" '' 'AVIZ TEHNIC DE RACORDARE' 'Nr. aviz: ATR-TEST-0002 / 14.11.2026' '' \
  'Beneficiar: Maria Exemplu' 'CNP: 0000000000002 (fictiv)' 'Adresa: Bd. Demonstratiei nr. 22, Orasul Inexistent, jud. Test' \
  '' 'Punct de racordare: ST-CONSTANTA-020, fider F03' 'Putere aprobata: 15 kW, trifazat' 'Zona: Dobrogea' \
  'Termen de realizare: 60 de zile'
f="$D_DOCS/contract-racordare-fictiv-003.pdf"
need_file "$f" && new_pdf "$f" "$FICT" '' 'CONTRACT DE RACORDARE' 'Nr. contract: CR-TEST-0003 / 14.11.2026' '' \
  'Utilizator: SC Firma Fictiva SRL (CUI fictiv RO00000000)' 'Reprezentant: Ion Probescu, CNP 0000000000003 (fictiv)' \
  'Sediu: Str. Nicaieri nr. 7, Satul Demo, jud. Test' 'IBAN: RO00TEST0000000000000000 (fictiv)' '' \
  'Tarif de racordare: 4.250,00 lei' 'Zona: Banat'
f="$D_DOCS/certificat-racordare-fictiv-004.pdf"
need_file "$f" && new_pdf "$f" "$FICT" '' 'CERTIFICAT DE RACORDARE' 'Nr. certificat: CER-TEST-0004 / 14.11.2026' '' \
  'Titular: Elena Fictivescu' 'CNP: 0000000000004 (fictiv)' 'Loc de consum: Aleea Probelor nr. 3, Comuna Test, jud. Test' \
  'Contor montat: RO-MUN-0004821' 'Putere maxima simultan absorbita: 7 kW'
f="$D_DOCS/plan-situatie-fictiv-005.png"; need_file "$f" && new_png "$f" 160 100 px_plan
f="$D_DOCS/semnatura-fictiv-006.png";     need_file "$f" && new_png "$f" 120 40 px_sign
echo "  connection-docs:   4 PDF + 2 PNG (date fictive)"

# ---------------------------------------------------------------- 4. video-uri de inspectie

# Antet "ftyp" ca fisierul sa fie recunoscut ca MP4; restul sunt octeti aleatori din /dev/urandom,
# copiati in streaming de head (fisierul nu e tinut in memorie).
write_video() {
  local file="$1" size="$2" tmp
  tmp=$(tmp_path "$file")
  printf '\x00\x00\x00\x18ftypmp42\x00\x00\x00\x00mp42isom' > "$tmp"
  head -c $(( size - 24 )) /dev/urandom >> "$tmp"
  commit_file "$tmp" "$file"
}

VIDEOS=(
  drona-inspectie-100MB.mp4:104857600:0
  drona-inspectie-500MB.mp4:524288000:1
  drona-linie-110kV-tronson-7.mp4:1181116006:1    # 1,1 GiB, numele si marimea din exemplul contractului
)
for v in "${VIDEOS[@]}"; do
  IFS=: read -r name size large <<< "$v"
  if [ "$SKIP_VIDEOS" = 1 ] || { [ "$SKIP_LARGE" = 1 ] && [ "$large" = 1 ]; }; then
    echo "  inspection-videos: sar peste $name"; continue
  fi
  if need_file "$D_VIDEO/$name" "$size"; then
    echo "  inspection-videos: scriu $name ($size octeti)..."
    write_video "$D_VIDEO/$name" "$size"
  fi
done

echo "Gata: $CREATED fisiere create, $SKIPPED existau deja (folositi --force ca sa le regenerati)."
