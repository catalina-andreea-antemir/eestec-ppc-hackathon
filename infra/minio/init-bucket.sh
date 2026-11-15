#!/usr/bin/env bash
# minio-init (C3/C5): creates the bucket and the app user with a policy scoped to that bucket.
# Idempotent (safe on every start), exits 0. Credentials come only from environment variables (.env).
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

# The alias is written to a temporary MC_CONFIG_DIR; nothing printed contains secrets.
for attempt in $(seq 1 30); do
    if mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1; then
        break
    fi
    if [[ $attempt -eq 30 ]]; then
        echo "MinIO is not responding at http://minio:9000." >&2
        exit 1
    fi
    sleep 2
done

mc mb --ignore-existing "local/$bucket" >/dev/null
mc anonymous set none "local/$bucket" >/dev/null

# The image only has bash and coreutils (no sed/grep), so substitution and matching are done in bash.
template=$(<"$config_dir/filegrid-app-policy.json")
printf '%s\n' "${template//__BUCKET__/$bucket}" >"$work/policy.json"
mc admin policy create local "$policy" "$work/policy.json" >/dev/null

# `user add` updates the password if the user already exists.
mc admin user add local "$S3_ACCESS_KEY" "$S3_SECRET_KEY" >/dev/null
# `policy attach` fails if the policy is already attached, so check the result instead of the exit code.
mc admin policy attach local "$policy" --user "$S3_ACCESS_KEY" >/dev/null 2>&1 || true
user_info=$(mc admin user info local "$S3_ACCESS_KEY" --json 2>/dev/null || true)
compact=${user_info//[[:space:]]/}
if [[ "$compact" != *"\"policyName\":\"$policy\""* ]]; then
    echo "Policy $policy is not attached to the app user." >&2
    exit 1
fi

echo "MinIO: bucket '$bucket' and the app user are ready."
