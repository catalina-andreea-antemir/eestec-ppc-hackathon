#!/usr/bin/env bash
# minio-init (C3/C5): creeaza bucket-ul si utilizatorul aplicatiei, cu o politica limitata la acel bucket.
# Idempotent (se poate rula la fiecare pornire) si iese cu cod 0. Credentialele vin doar din variabile de mediu (.env).
set -euo pipefail

: "${MINIO_ROOT_USER:?Lipseste MINIO_ROOT_USER}"
: "${MINIO_ROOT_PASSWORD:?Lipseste MINIO_ROOT_PASSWORD}"
: "${S3_ACCESS_KEY:?Lipseste S3_ACCESS_KEY}"
: "${S3_SECRET_KEY:?Lipseste S3_SECRET_KEY}"
bucket=${MINIO_BUCKET:-filegrid}
policy=filegrid-app
config_dir=$(dirname -- "${BASH_SOURCE[0]}")

export MC_CONFIG_DIR=/tmp/mc
work=$(mktemp -d)
trap 'rm -rf -- "$work" "$MC_CONFIG_DIR"' EXIT

# Alias-ul se scrie in MC_CONFIG_DIR (temporar); nimic din iesire nu contine secrete.
for attempt in $(seq 1 30); do
    if mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1; then
        break
    fi
    if [[ $attempt -eq 30 ]]; then
        echo "MinIO nu raspunde la http://minio:9000." >&2
        exit 1
    fi
    sleep 2
done

mc mb --ignore-existing "local/$bucket" >/dev/null
mc anonymous set none "local/$bucket" >/dev/null

# Imaginea are doar bash si coreutils (fara sed/grep), deci inlocuirea si cautarea se fac in bash.
template=$(<"$config_dir/filegrid-app-policy.json")
printf '%s\n' "${template//__BUCKET__/$bucket}" >"$work/policy.json"
mc admin policy create local "$policy" "$work/policy.json" >/dev/null

# `user add` actualizeaza parola daca utilizatorul exista deja.
mc admin user add local "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null
# `policy attach` esueaza daca politica e deja atasata, deci verificam rezultatul, nu codul de iesire.
mc admin policy attach local "$policy" --user "$S3_ACCESS_KEY" >/dev/null 2>&1 || true
user_info=$(mc admin user info local "$S3_ACCESS_KEY" --json 2>/dev/null || true)
compact=${user_info//[[:space:]]/}
if [[ "$compact" != *"\"policyName\":\"$policy\""* ]]; then
    echo "Politica $policy nu este atasata utilizatorului aplicatiei." >&2
    exit 1
fi

echo "MinIO: bucket-ul '$bucket' si utilizatorul aplicatiei sunt pregatite."
