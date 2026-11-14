#!/usr/bin/env bash
# FileGrid PPC - pornirea intregului sistem cu o singura comanda (ADR-002). Din radacina repo-ului:
#   ./scripts/start.sh
# 1) verifica Docker Compose >= 2.20; 2) creeaza .env cu secrete aleatorii daca lipseste (si completeaza variabilele
# care lipsesc); 3) docker compose up -d --build; 4) asteapta health-ul api; 5) afiseaza adresa si utilizatorii de test.
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$repo_root"
env_file=.env
health_timeout=${FILEGRID_START_TIMEOUT:-600}

info() { printf '==> %s\n' "$*"; }
fail() { printf 'EROARE: %s\n' "$*" >&2; exit 1; }

# --- 1. Docker si Docker Compose --------------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "nu gasesc comanda docker. Instaleaza Docker (Engine sau Desktop)."
docker info >/dev/null 2>&1 || fail "Docker nu raspunde. Porneste Docker si incearca din nou."
compose_version=$(docker compose version --short 2>/dev/null) ||
    fail "lipseste pluginul Docker Compose v2 (docker compose)."
compose_version=${compose_version#v}
IFS=. read -r c_major c_minor _ <<<"$compose_version"
if ! [[ "$c_major" =~ ^[0-9]+$ && "${c_minor%%[!0-9]*}" =~ ^[0-9]+$ ]] ||
   ((c_major < 2 || (c_major == 2 && ${c_minor%%[!0-9]*} < 20))); then
    fail "Docker Compose $compose_version este prea vechi; e nevoie de minim 2.20 (pentru include)."
fi
info "Docker Compose $compose_version"

# --- 2. .env cu secrete aleatorii --------------------------------------------------------------------------------
# Parola alfanumerica cu cel putin o litera mare, una mica si o cifra (politica SQL Server, CHECK_POLICY = ON).
random_password() {
    local length=${1:-24} value
    while :; do
        value=$(head -c 512 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9')
        value=${value:0:length}
        if ((${#value} == length)) && [[ "$value" =~ [A-Z] && "$value" =~ [a-z] && "$value" =~ [0-9] ]]; then
            printf '%s' "$value"
            return
        fi
    done
}
random_base64() { head -c "$1" /dev/urandom | base64 | tr -d '\n'; }

# Valoarea unei variabile din .env (gol daca lipseste).
env_value() { sed -n "s/^$1=//p" "$env_file" 2>/dev/null | tail -n 1 | tr -d '\r'; }

created_env=false
if [[ ! -f "$env_file" ]]; then
    (umask 077 && printf '# Generat de scripts/start.* la %s. NU se comite (e in .gitignore).\n' "$(date -u +%FT%TZ)" >"$env_file")
    created_env=true
fi

added=()
# Adauga variabila daca lipseste sau e goala; valorile existente nu se schimba niciodata
# (parolele SQL sunt deja in volumul mssql).
ensure_var() {
    local name=$1 value=$2 tmp
    if [[ -n "$(env_value "$name")" ]]; then
        return
    fi
    if grep -q "^$name=" "$env_file"; then
        tmp=$(mktemp "$env_file.XXXXXX")
        grep -v "^$name=" "$env_file" >"$tmp" || true
        cat "$tmp" >"$env_file"
        rm -f -- "$tmp"
    fi
    printf '%s=%s\n' "$name" "$value" >>"$env_file"
    added+=("$name")
}
# Variabilele optionale se scriu o singura data, cu valoarea implicita (pot ramane goale).
ensure_optional_var() {
    local name=$1 value=$2
    grep -q "^$name=" "$env_file" || { printf '%s=%s\n' "$name" "$value" >>"$env_file"; added+=("$name"); }
}

ensure_var MSSQL_SA_PASSWORD "$(random_password 24)"
ensure_var META_DB_PASSWORD "$(random_password 24)"
ensure_var STORAGE_DB_PASSWORD "$(random_password 24)"
ensure_optional_var STORAGE_DB_HOST mssql
ensure_optional_var STORAGE_DB_SA_PASSWORD ""
ensure_var MINIO_ROOT_USER filegrid-root
ensure_var MINIO_ROOT_PASSWORD "$(random_password 32)"
ensure_var MINIO_BUCKET filegrid
ensure_var S3_ACCESS_KEY filegrid-app
ensure_var S3_SECRET_KEY "$(random_password 32)"
ensure_var FTP_USER filegrid
ensure_var FTP_PASSWORD "$(random_password 24)"
ensure_var GRAFANA_ADMIN_PASSWORD "$(random_password 20)"
ensure_var JWT_SECRET "$(random_base64 48)"
ensure_var DOC_ENCRYPTION_KEY "$(random_base64 32)"
ensure_var DOWNLOAD_LINK_SECRET "$(random_base64 48)"
test_users=(dispecer:DISPATCH teren:FIELD racordari:CONNECTIONS contor:METER_SERVICE admin:ADMIN)
for entry in "${test_users[@]}"; do
    user=${entry%%:*}
    ensure_var "FILEGRID_USER_$(printf '%s' "$user" | tr '[:lower:]' '[:upper:]')_PASSWORD" "$(random_password 16)"
done
chmod 600 "$env_file" 2>/dev/null || true

if $created_env; then
    info ".env creat cu valori aleatorii ($env_file)."
elif ((${#added[@]} > 0)); then
    info ".env completat cu: ${added[*]}"
else
    info ".env exista deja; il folosesc neschimbat."
fi

# --- 3. Pornire -------------------------------------------------------------------------------------------------
info "docker compose up -d --build (prima construire dureaza cateva minute)"
if ! docker compose up -d --build; then
    printf '\nUltimele linii din mssql-init si minio-init:\n' >&2
    docker compose logs --no-color --tail 30 mssql-init minio-init >&2 || true
    fail "docker compose up a esuat (detalii mai sus; docker compose logs <serviciu>)."
fi

# --- 4. Asteptarea health-ului ---------------------------------------------------------------------------------
# Healthcheck-ul containerului api foloseste GET /api/v1/health (UP cand api si FileGridMeta raspund); apoi se
# verifica acelasi endpoint prin nginx, pe HTTPS, cum il vede interfata.
info "astept health-ul api (maxim ${health_timeout}s)"
deadline=$((SECONDS + health_timeout))
while :; do
    ids=$(docker compose ps -q api)
    healthy=0 total=0
    for id in $ids; do
        total=$((total + 1))
        [[ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$id")" == healthy ]] &&
            healthy=$((healthy + 1))
    done
    if ((total > 0 && healthy == total)); then
        break
    fi
    if ((SECONDS >= deadline)); then
        docker compose ps -a >&2 || true
        docker compose logs --no-color --tail 40 api >&2 || true
        fail "api nu a devenit healthy in ${health_timeout}s."
    fi
    sleep 3
done
info "api: $healthy/$total instante healthy"

if command -v curl >/dev/null 2>&1; then
    # nginx re-rezolva api la 5 s, deci primele cereri pot da 502/503 cateva secunde.
    nginx_deadline=$((SECONDS + 60))
    while :; do
        code=$(curl -ks -o /dev/null -w '%{http_code}' https://localhost/api/v1/health || true)
        [[ "$code" == 200 ]] && break
        if ((SECONDS >= nginx_deadline)); then
            docker compose logs --no-color --tail 20 nginx >&2 || true
            fail "GET https://localhost/api/v1/health -> ${code:-fara raspuns} (api e healthy, dar nginx nu il serveste)."
        fi
        sleep 2
    done
    info "GET https://localhost/api/v1/health -> 200"
fi

# --- 5. Rezumat -------------------------------------------------------------------------------------------------
cat <<EOF

FileGrid PPC ruleaza:  https://localhost   (certificat self-signed: accepta-l in browser)
Grafana:               https://localhost/grafana/   (utilizator admin, parola GRAFANA_ADMIN_PASSWORD din .env)

Utilizatori de test (parolele sunt in .env):
EOF
for entry in "${test_users[@]}"; do
    user=${entry%%:*} role=${entry#*:}
    var="FILEGRID_USER_$(printf '%s' "$user" | tr '[:lower:]' '[:upper:]')_PASSWORD"
    printf '  %-10s %-14s %s\n' "$user" "$role" "$(env_value "$var")"
done
printf '\nOprire: docker compose down    Resetare completa (sterge datele): docker compose down -v\n'
