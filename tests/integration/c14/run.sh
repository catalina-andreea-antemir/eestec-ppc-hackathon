#!/usr/bin/env bash
# C14 - test de integrare reproductibil: 3 replici api reale in spatele nginx, upload in bucati (ADR-003).
# Ruleaza intr-un proiect Docker IZOLAT (filegrid-c14); stack-ul principal (filegrid) nu se atinge.
# Din radacina repo-ului:
#   tests/integration/c14/run.sh up      # porneste nginx + 3 x api (heap 256 MB) si dependentele
#   tests/integration/c14/run.sh small   # upload mic: reluare, retrimitere idempotenta, ordine, hash, descarcare
#   tests/integration/c14/run.sh big     # upload de 1,1 GiB in streaming + memoria replicilor
#   tests/integration/c14/run.sh all     # up + small + big
#   tests/integration/c14/run.sh stop    # opreste containerele (volumele raman; nu se ruleaza down -v)
# Variabile: C14_WORKDIR (fisierele de test, implicit $TMPDIR/filegrid-c14), C14_BIG_SIZE (octeti, implicit 1,1 GiB),
# C14_HTTPS_PORT (implicit 18443). Necesita bash, curl, dd, sha256sum.
set -euo pipefail
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(cd -- "$here/../../.." && pwd)
cd -- "$root"
project=filegrid-c14
env_file=tests/integration/c14/.env.c14
dc=(docker compose -p "$project" --env-file "$env_file" -f docker-compose.yml -f tests/integration/c14/docker-compose.c14.yml)
base="https://127.0.0.1:${C14_HTTPS_PORT:-18443}/api/v1"
work=${C14_WORKDIR:-${TMPDIR:-/tmp}/filegrid-c14}
big_size=${C14_BIG_SIZE:-1181116006}   # 1,1 GiB
mkdir -p "$work"

pass=0 fail=0
ok()   { printf 'PASS  %s\n' "$*"; pass=$((pass + 1)); }
bad()  { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }
info() { printf '==>   %s\n' "$*"; }
check() { if [[ "$2" == "$3" ]]; then ok "$1 ($2)"; else bad "$1: asteptat '$3', primit '$2'"; fi; }
# Un camp scalar dintr-un JSON plat (fara jq).
jget() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\).*/\1/p" | head -n 1; }

# --- .env izolat, cu secrete aleatorii proprii ------------------------------------------------------------------
rnd() { local v; while :; do v=$(head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9'); v=${v:0:$1}
        [[ ${#v} == "$1" && $v =~ [A-Z] && $v =~ [a-z] && $v =~ [0-9] ]] && { printf '%s' "$v"; return; }; done; }
b64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }
ensure_env() {
    [[ -f "$env_file" ]] && return
    umask 077
    cat >"$env_file" <<EOF
# Generat de tests/integration/c14/run.sh. Doar pentru proiectul izolat filegrid-c14; nu se comite.
MSSQL_SA_PASSWORD=$(rnd 24)
META_DB_PASSWORD=$(rnd 24)
STORAGE_DB_PASSWORD=$(rnd 24)
MINIO_ROOT_USER=filegrid-root
MINIO_ROOT_PASSWORD=$(rnd 32)
MINIO_BUCKET=filegrid
S3_ACCESS_KEY=filegrid-app
S3_SECRET_KEY=$(rnd 32)
FTP_USER=filegrid
FTP_PASSWORD=$(rnd 24)
GRAFANA_ADMIN_PASSWORD=$(rnd 20)
JWT_SECRET=$(b64 48)
DOC_ENCRYPTION_KEY=$(b64 32)
DOWNLOAD_LINK_SECRET=$(b64 48)
FILEGRID_USER_TEREN_PASSWORD=$(rnd 16)
FILEGRID_USER_ADMIN_PASSWORD=$(rnd 16)
EOF
}
env_value() { sed -n "s/^$1=//p" "$env_file" | tr -d '\r'; }

api_containers() { docker ps --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=api" --format '{{.Names}}' | sort; }

cmd_up() {
    ensure_env
    info "pornesc proiectul $project (nginx + 3 x api)"
    "${dc[@]}" up -d --build --scale api=3 nginx api
    local deadline=$((SECONDS + 600)) healthy
    while :; do
        healthy=$(docker ps --filter "label=com.docker.compose.project=$project" \
            --filter "label=com.docker.compose.service=api" --filter health=healthy -q | wc -l)
        ((healthy >= 3)) && curl -fsk "$base/health" >/dev/null && break
        ((SECONDS > deadline)) && { bad "api nu a devenit healthy (3 replici) in 10 minute"; exit 1; }
        sleep 3
    done
    ok "3 replici api healthy, nginx raspunde: $(api_containers | tr '\n' ' ')"
}

cmd_stop() { "${dc[@]}" --profile chaos stop; }

token=''
token_at=0
login() {
    if [[ -n "$token" ]] && ((SECONDS - token_at < 600)); then return; fi
    token=$(curl -fsk -X POST "$base/auth/login" -H 'Content-Type: application/json' \
        -d "{\"username\":\"teren\",\"password\":\"$(env_value FILEGRID_USER_TEREN_PASSWORD)\"}" | jget accessToken)
    [[ -n "$token" ]] || { bad "login teren"; exit 1; }
    token_at=$SECONDS
}

# api_call METODA cale [args curl...] -> scrie corpul in $work/resp.json, intoarce codul HTTP pe stdout.
api_call() {
    local method=$1 path=$2; shift 2
    login
    curl -sk -o "$work/resp.json" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $token" "$@" "$base$path"
}

init_upload() { # fisier
    local size; size=$(stat -c %s "$1")
    api_call POST /uploads -H 'Content-Type: application/json' \
        -d "{\"category\":\"INSPECTION_VIDEO\",\"fileName\":\"$(basename "$1").mp4\",\"totalSize\":$size}" >/dev/null
    upload_id=$(jget uploadId <"$work/resp.json")
    chunk_size=$(jget chunkSize <"$work/resp.json")
    [[ -n "$upload_id" && -n "$chunk_size" ]] || { bad "init upload: $(cat "$work/resp.json")"; exit 1; }
}

# put_chunk fisier n -> cod HTTP; bucata se citeste din fisier cu dd (corpul nu trece printr-un buffer al scriptului).
put_chunk() {
    local mib=$((chunk_size / 1048576))
    dd if="$1" bs=1M skip=$(( ($2 - 1) * mib )) count="$mib" status=none |
        api_call PUT "/uploads/$upload_id/chunks/$2" -H 'Content-Type: application/octet-stream' --data-binary @-
}

# upload_all fisier de_la -> trimite bucatile de_la..ultima, cu reincercare la 409 UPLOAD_BUSY / 503.
upload_all() {
    local file=$1 n=$2 total=$3 code tries
    while ((n <= total)); do
        tries=0
        while code=$(put_chunk "$file" "$n"); [[ "$code" != 200 ]]; do
            tries=$((tries + 1))
            ((tries > 20)) && { bad "bucata $n: HTTP $code $(cat "$work/resp.json")"; return 1; }
            sleep 2
        done
        ((n % 10 == 0 || n == total)) && info "bucata $n/$total"
        n=$((n + 1))
    done
}

# Distributia bucatilor pe replici: logul nginx (upstream=IP:port) + IP-urile containerelor api.
distribution() { # upload_id
    local map line ip n name
    map=$(for c in $(api_containers); do
        printf '%s %s\n' "$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' "$c")" "$c"
    done)
    "${dc[@]}" logs --no-log-prefix nginx 2>/dev/null | grep "PUT /api/v1/uploads/$1/chunks/" | grep '" 200 ' |
        while read -r line; do
            n=$(sed -n 's#.*/chunks/\([0-9]*\) .*#\1#p' <<<"$line")
            ip=$(sed -n 's/.*upstream=\([0-9.]*\):.*/\1/p' <<<"$line")
            name=$(grep -w -- "$ip" <<<"$map" | awk '{print $NF}')
            printf '%s %s %s\n' "$n" "$ip" "${name:-?}"
        done | sort -n -u -k1,1
}

check_distribution() { # upload_id total
    local dist instances switches
    dist=$(distribution "$1")
    printf '%s\n' "$dist" >"$work/dist-$1.txt"
    head -n 12 <<<"$dist" | sed 's/^/      bucata /'
    instances=$(awk '{print $3}' <<<"$dist" | sort -u | wc -l)
    switches=$(awk 'NR > 1 && $3 != prev {s++} {prev = $3} END {print s + 0}' <<<"$dist")
    info "bucati pe replica: $(awk '{print $3}' <<<"$dist" | sort | uniq -c | awk '{printf "%s=%s ", $2, $1}')"
    if ((instances >= 2 && switches >= 1)); then
        ok "bucati consecutive pe instante diferite ($instances replici, $switches schimbari de replica)"
    else
        bad "distributie: $instances replici, $switches schimbari"
    fi
}

# Contorul upload_chunk din /actuator/prometheus al fiecarei replici (al doilea identificator, independent de nginx).
chunk_metrics() {
    for c in $(api_containers); do
        printf '      %s upload_chunk=%s\n' "$c" "$(docker exec "$c" curl -fs localhost:8080/actuator/prometheus |
            grep '^filegrid_operation_seconds_count' | grep 'operation="upload_chunk"' |
            awk '{s += $NF} END {printf "%d", s}')"
    done
}

finish_and_verify() { # fisier
    local code sha local_sha file_id
    code=$(api_call POST "/uploads/$upload_id/complete")
    check "complete" "$code" 201
    sha=$(jget sha256 <"$work/resp.json")
    file_id=$(jget id <"$work/resp.json")
    local_sha=$(sha256sum "$1" | awk '{print $1}')
    check "SHA-256 calculat de server = SHA-256 local" "$sha" "$local_sha"
    code=$(api_call POST "/uploads/$upload_id/complete")
    check "complete repetat, idempotent" "$code" 200
    code=$(api_call GET "/files/$file_id/verify")
    check "verify HTTP" "$code" 200
    grep -q '"ok":true' "$work/resp.json" && ok "verify: ok=true" || bad "verify: $(cat "$work/resp.json")"
    login
    local dl_sha
    dl_sha=$(curl -fsk -H "Authorization: Bearer $token" "$base/files/$file_id/download" | sha256sum | awk '{print $1}')
    check "SHA-256 al fisierului descarcat" "$dl_sha" "$local_sha"
}

cmd_small() {
    ensure_env
    local file="$work/small.bin" code total
    [[ -f "$file" ]] || head -c $((40 * 1048576 + 12345)) /dev/urandom >"$file"
    init_upload "$file"
    total=$(( ($(stat -c %s "$file") + chunk_size - 1) / chunk_size ))
    info "upload mic $upload_id: $(stat -c %s "$file") octeti, $total bucati de $chunk_size"

    code=$(put_chunk "$file" 2); check "bucata 2 inaintea bucatii 1 -> 409" "$code" 409
    grep -q CHUNK_OUT_OF_ORDER "$work/resp.json" && ok "cod CHUNK_OUT_OF_ORDER" || bad "$(cat "$work/resp.json")"
    code=$(put_chunk "$file" 1); check "bucata 1" "$code" 200
    local sha1; sha1=$(jget sha256 <"$work/resp.json")
    code=$(put_chunk "$file" 1); check "retrimiterea bucatii 1" "$code" 200
    check "retrimitere: duplicate" "$(jget duplicate <"$work/resp.json")" true
    check "retrimitere: acelasi sha256" "$(jget sha256 <"$work/resp.json")" "$sha1"

    # Reluare: clientul "uita" tot si intreaba starea (ca dupa refresh), apoi continua de unde a ramas.
    token=''
    code=$(api_call GET "/uploads/$upload_id"); check "GET stare la reluare" "$code" 200
    check "nextChunk dupa reluare" "$(jget nextChunk <"$work/resp.json")" 2
    check "receivedBytes dupa reluare" "$(jget receivedBytes <"$work/resp.json")" "$chunk_size"
    upload_all "$file" "$(jget nextChunk <"$work/resp.json")" "$total"
    code=$(put_chunk "$file" "$total"); check "retrimiterea ultimei bucati" "$code" 200
    check "ultima bucata: duplicate" "$(jget duplicate <"$work/resp.json")" true
    finish_and_verify "$file"
    check_distribution "$upload_id" "$total"
    chunk_metrics
}

# Esantioane de memorie la 2 s: docker stats (RSS container) si heap-ul JVM din /actuator/prometheus.
sample_memory() {
    local out=$1 c heap
    : >"$out"
    while [[ -f "$work/sampling" ]]; do
        docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' $(api_containers) |
            awk '{print "rss", $1, $2}' >>"$out"
        for c in $(api_containers); do
            heap=$(docker exec "$c" curl -fs localhost:8080/actuator/prometheus 2>/dev/null |
                grep '^jvm_memory_used_bytes{area="heap"' | awk '{s += $NF} END {printf "%d", s / 1048576}')
            printf 'heap %s %sMiB\n' "$c" "$heap" >>"$out"
        done
        sleep 2
    done
}

report_memory() {
    local out=$1
    info "maxim pe replica (RSS container / heap JVM folosit), heap maxim configurat:"
    for c in $(api_containers); do
        local rss heap max
        rss=$(awk -v c="$c" '$1 == "rss" && $2 == c {v = $3; u = v; sub(/[0-9.]+/, "", u); sub(/[A-Za-z]+/, "", v)
            m = (u == "GiB") ? v * 1024 : (u == "KiB" ? v / 1024 : v); if (m > x) x = m} END {printf "%.0f", x}' "$out")
        heap=$(awk -v c="$c" '$1 == "heap" && $2 == c {v = $3 + 0; if (v > x) x = v} END {printf "%d", x}' "$out")
        max=$(docker exec "$c" curl -fs localhost:8080/actuator/prometheus |
            grep '^jvm_memory_max_bytes{area="heap"' | awk '$NF > 0 {s += $NF} END {printf "%d", s / 1048576}')
        printf '      %s rss_max=%sMiB heap_used_max=%sMiB heap_max=%sMiB restarts=%s\n' "$c" "$rss" "$heap" "$max" \
            "$(docker inspect -f '{{.RestartCount}}' "$c")"
        ((rss < 512 && heap <= 256)) && ok "$c sub limite (RSS < 512 MiB, heap <= 256 MiB)" ||
            bad "$c memorie: rss=$rss heap=$heap"
    done
}

cmd_big() {
    ensure_env
    local file="$work/big.bin" total start
    if [[ ! -f "$file" || $(stat -c %s "$file") != "$big_size" ]]; then
        info "generez $big_size octeti aleatori in $file"
        head -c "$big_size" /dev/urandom >"$file"
    fi
    init_upload "$file"
    total=$(( (big_size + chunk_size - 1) / chunk_size ))
    info "upload mare $upload_id: $big_size octeti, $total bucati de $chunk_size"
    touch "$work/sampling"
    sample_memory "$work/mem.txt" &
    local sampler=$!
    start=$SECONDS
    upload_all "$file" 1 "$total" || true
    local up_s=$((SECONDS - start))
    info "bucati trimise in ${up_s}s (~$((big_size / 1048576 / (up_s > 0 ? up_s : 1))) MiB/s)"
    start=$SECONDS
    finish_and_verify "$file"
    info "complete + verify + descarcare in $((SECONDS - start))s"
    rm -f "$work/sampling"; wait "$sampler" || true
    check_distribution "$upload_id" "$total"
    chunk_metrics
    report_memory "$work/mem.txt"
}

case "${1:-all}" in
    up) cmd_up ;;
    small) cmd_small ;;
    big) cmd_big ;;
    stop) cmd_stop ;;
    all) cmd_up; cmd_small; cmd_big ;;
    *) echo "utilizare: $0 [up|small|big|all|stop]" >&2; exit 2 ;;
esac
printf '\nREZULTAT: %d PASS, %d FAIL\n' "$pass" "$fail"
((fail == 0))
