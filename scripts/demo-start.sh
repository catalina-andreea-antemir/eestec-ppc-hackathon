#!/usr/bin/env bash
# Porneste FileGrid pentru demo si verifica cele trei replici API.
set -euo pipefail

fail() { printf 'EROARE: %s\n' "$*" >&2; exit 1; }

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo_root"
command -v curl >/dev/null 2>&1 || fail 'curl este necesar pentru verificarea prin nginx.'

printf 'Pornesc FileGrid (prima construire poate dura cateva minute)...\n'
# Scriptul de pornire poate afisa loguri de servicii la esec. Terminalul de demo arata doar etapa esuata.
if ! bash scripts/start.sh >/dev/null 2>&1; then
    fail 'Pornirea FileGrid a esuat. Vezi detaliile ruland bash scripts/start.sh intr-un terminal privat.'
fi

printf 'Scalez API la 3 replici...\n'
if ! docker compose up -d --no-deps --scale api=3 api >/dev/null 2>&1; then
    fail 'Scalarea API la 3 replici a esuat. Verifica serviciul api cu docker compose ps api.'
fi

health_format='{{.Name}} {{if and .State.Running .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}'
deadline=$((SECONDS + 600))
while :; do
    api_ids=$(docker compose ps --status running -q api 2>/dev/null) ||
        fail 'Nu am putut lista replicile API.'
    ids=()
    while IFS= read -r id; do
        if [[ -n "$id" ]]; then
            ids+=("$id")
        fi
    done <<<"$api_ids"
    running=${#ids[@]}
    healthy=0
    if ((running > 0)) && replicas=$(docker inspect --type container --format "$health_format" "${ids[@]}" 2>/dev/null); then
        while IFS=' ' read -r name state; do
            if [[ -n "$name" && "$state" == healthy ]]; then
                healthy=$((healthy + 1))
            fi
        done <<<"$replicas"
    fi
    if ((running == 3 && healthy == 3)); then
        break
    fi
    if ((SECONDS >= deadline)); then
        fail "Timeout dupa 600 s: asteptam 3 replici API healthy, gasite $running pornite si $healthy healthy."
    fi
    sleep 3
done

health_url=https://localhost/api/v1/health
deadline=$((SECONDS + 60))
while :; do
    if code=$(curl -ks --max-time 5 -o /dev/null -w '%{http_code}' "$health_url" 2>/dev/null) && [[ "$code" == 200 ]]; then
        break
    fi
    if ((SECONDS >= deadline)); then
        fail "nginx nu a confirmat HTTP 200 la $health_url in 60 s."
    fi
    sleep 2
done

printf '\nReplici API:\n'
while IFS= read -r replica; do
    printf '  %s\n' "${replica#/}"
done <<<"$replicas"
printf 'Health prin nginx: HTTP 200 (%s)\n' "$health_url"
printf 'FileGrid: https://localhost\nGrafana:  https://localhost/grafana/\n'
