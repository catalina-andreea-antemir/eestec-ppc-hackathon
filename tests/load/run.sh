#!/usr/bin/env bash
# Ruleaza un scenariu k6 FileGrid (F8) in Docker, fara instalare locala.
#   tests/load/run.sh smoke
#   tests/load/run.sh meter-readings --short
#   tests/load/run.sh peak --localhost -- --vus 10     # argumentele de dupa -- ajung la k6
# Parolele se citesc din .env (FILEGRID_USER_*_PASSWORD) si se dau containerului prin nume (-e VAR),
# deci valorile nu apar in linia de comanda, in loguri sau in fisiere.
set -euo pipefail

K6_IMAGE=grafana/k6:1.3.0
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ENV_FILE="$ROOT/.env"

scenario="${1:-}"
case "$scenario" in
  smoke|meter-readings|mixed|peak|security|storm|big-upload) shift ;;
  *) echo "Utilizare: $0 {smoke|meter-readings|mixed|peak|security|storm|big-upload} [--short] [--localhost] [--dashboard] [-- argumente k6]" >&2; exit 2 ;;
esac

short=0; localhost=0; dashboard=0; k6args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --short) short=1 ;;
    --localhost) localhost=1 ;;
    --dashboard) dashboard=1 ;;
    --) shift; k6args=("$@"); break ;;
    *) k6args+=("$1") ;;
  esac
  shift
done

[ -f "$ENV_FILE" ] || { echo ".env lipseste in $ROOT (porneste stack-ul cu scripts/start.sh)" >&2; exit 1; }
names=(FILEGRID_USER_DISPECER_PASSWORD FILEGRID_USER_TEREN_PASSWORD FILEGRID_USER_RACORDARI_PASSWORD
       FILEGRID_USER_CONTOR_PASSWORD FILEGRID_USER_ADMIN_PASSWORD)
for n in "${names[@]}"; do
  v="$(grep -E "^[[:space:]]*${n}[[:space:]]*=" "$ENV_FILE" | tail -n1 | sed -E 's/^[^=]*=[[:space:]]*//; s/[[:space:]]*$//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' | tr -d '\r')"
  [ -n "$v" ] || { echo "$n lipseste din .env" >&2; exit 1; }
  export "$n=$v"
done

mkdir -p "$HERE/results"
mount="$HERE"
# Git Bash pe Windows: fara conversia automata a cailor /scripts
export MSYS_NO_PATHCONV=1
if command -v cygpath >/dev/null 2>&1; then mount="$(cygpath -w "$HERE")"; fi

args=(run --rm -i -v "$mount:/scripts" -w /scripts)
if [ "$localhost" = 1 ]; then
  args+=(--add-host host.docker.internal:host-gateway -e BASE_URL=https://host.docker.internal/api/v1)
else
  # storage-net contine si nginx si api: utilizatorii trec prin nginx, iar la peak citirile merg direct la api,
  # ca meter-simulator (vezi README).
  args+=(--network filegrid_storage-net -e BASE_URL=https://nginx/api/v1)
  if [ "$scenario" = peak ] && [ -z "${METER_BASE_URL:-}" ]; then args+=(-e METER_BASE_URL=http://api:8080/api/v1); fi
fi
[ -n "${METER_BASE_URL:-}" ] && args+=(-e METER_BASE_URL)
for n in "${names[@]}"; do args+=(-e "$n"); done
[ "$short" = 1 ] && args+=(-e SHORT=1)
if [ "$dashboard" = 1 ]; then
  # Dashboard-ul web al k6, live pe http://localhost:5665 cat ruleaza testul; la final, raport HTML in results/.
  # storage-net e "internal" si Docker nu publica porturi acolo; filegrid_public (doar nginx) permite portul.
  [ "$localhost" = 1 ] || args+=(--network filegrid_public)
  args+=(-p 127.0.0.1:5665:5665 -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 -e K6_WEB_DASHBOARD_PERIOD=2s
         -e "K6_WEB_DASHBOARD_EXPORT=results/$scenario-$(date +%Y-%m-%dT%H-%M-%S)-dashboard.html")
  echo "Dashboard live: http://localhost:5665 (deschide-l in browser cat ruleaza testul)"
fi
for n in K6_DEBUG_ERRORS RUN_ID PEAK_RATE MIXED_RATE METER_RATE DURATION DUP_PCT STORM_FACTOR BIG_SIZE_MB; do
  [ -n "${!n:-}" ] && args+=(-e "$n")
done
args+=("$K6_IMAGE" run)
[ ${#k6args[@]} -gt 0 ] && args+=("${k6args[@]}")
args+=("$scenario.js")

echo "k6 $scenario ($K6_IMAGE) -> rezultate in tests/load/results/"
exec docker "${args[@]}"
