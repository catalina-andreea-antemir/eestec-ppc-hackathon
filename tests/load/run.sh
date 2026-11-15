#!/usr/bin/env bash
# Runs a FileGrid k6 scenario (F8) in Docker, no local install needed.
#   tests/load/run.sh smoke
#   tests/load/run.sh meter-readings --short
#   tests/load/run.sh peak --localhost -- --vus 10     # arguments after -- are passed to k6
# Passwords are read from .env (FILEGRID_USER_*_PASSWORD) and passed to the container by name (-e VAR),
# so the values never appear on the command line, in logs or in files.
set -euo pipefail

K6_IMAGE=grafana/k6:1.3.0
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ENV_FILE="$ROOT/.env"

scenario="${1:-}"
case "$scenario" in
  smoke|meter-readings|mixed|peak|security|storm|big-upload) shift ;;
  *) echo "Usage: $0 {smoke|meter-readings|mixed|peak|security|storm|big-upload} [--short] [--localhost] [--dashboard] [-- k6 args]" >&2; exit 2 ;;
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

[ -f "$ENV_FILE" ] || { echo ".env is missing in $ROOT (start the stack with scripts/start.sh)" >&2; exit 1; }
names=(FILEGRID_USER_DISPECER_PASSWORD FILEGRID_USER_TEREN_PASSWORD FILEGRID_USER_RACORDARI_PASSWORD
       FILEGRID_USER_CONTOR_PASSWORD FILEGRID_USER_ADMIN_PASSWORD)
for n in "${names[@]}"; do
  v="$(grep -E "^[[:space:]]*${n}[[:space:]]*=" "$ENV_FILE" | tail -n1 | sed -E 's/^[^=]*=[[:space:]]*//; s/[[:space:]]*$//; s/^"(.*)"$/\1/; s/^'"'"'(.*)'"'"'$/\1/' | tr -d '\r')"
  [ -n "$v" ] || { echo "$n is missing from .env" >&2; exit 1; }
  export "$n=$v"
done

mkdir -p "$HERE/results"
mount="$HERE"
# Git Bash on Windows: disable automatic conversion of /scripts paths
export MSYS_NO_PATHCONV=1
if command -v cygpath >/dev/null 2>&1; then mount="$(cygpath -w "$HERE")"; fi

args=(run --rm -i -v "$mount:/scripts" -w /scripts)
if [ "$localhost" = 1 ]; then
  args+=(--add-host host.docker.internal:host-gateway -e BASE_URL=https://host.docker.internal/api/v1)
else
  # storage-net holds both nginx and api: users go through nginx, while in `peak` the readings go straight to api,
  # like meter-simulator (see README).
  args+=(--network filegrid_storage-net -e BASE_URL=https://nginx/api/v1)
  if [ "$scenario" = peak ] && [ -z "${METER_BASE_URL:-}" ]; then args+=(-e METER_BASE_URL=http://api:8080/api/v1); fi
fi
[ -n "${METER_BASE_URL:-}" ] && args+=(-e METER_BASE_URL)
for n in "${names[@]}"; do args+=(-e "$n"); done
[ "$short" = 1 ] && args+=(-e SHORT=1)
if [ "$dashboard" = 1 ]; then
  # k6 web dashboard, live on http://localhost:5665 while the test runs; an HTML report lands in results/ at the end.
  # storage-net is "internal" and Docker cannot publish ports there; filegrid_public (nginx only) allows it.
  [ "$localhost" = 1 ] || args+=(--network filegrid_public)
  args+=(-p 127.0.0.1:5665:5665 -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_HOST=0.0.0.0 -e K6_WEB_DASHBOARD_PERIOD=2s
         -e "K6_WEB_DASHBOARD_EXPORT=results/$scenario-$(date +%Y-%m-%dT%H-%M-%S)-dashboard.html")
  echo "Live dashboard: http://localhost:5665 (open it in the browser while the test runs)"
fi
for n in K6_DEBUG_ERRORS RUN_ID PEAK_RATE MIXED_RATE METER_RATE DURATION DUP_PCT STORM_FACTOR BIG_SIZE_MB; do
  [ -n "${!n:-}" ] && args+=(-e "$n")
done
args+=("$K6_IMAGE" run)
[ ${#k6args[@]} -gt 0 ] && args+=("${k6args[@]}")
args+=("$scenario.js")

echo "k6 $scenario ($K6_IMAGE) -> results in tests/load/results/"
exec docker "${args[@]}"
