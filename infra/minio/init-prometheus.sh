#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() {
    echo "MinIO metrics: $1" >&2
    exit 1
}

case "${1:-}" in
    ""|--rotate) ;;
    *) fail "use --rotate to rotate." ;;
esac

dir=/metrics-auth
policy=filegrid-prometheus
export MC_CONFIG_DIR
MC_CONFIG_DIR=$(mktemp -d)
work=$(mktemp -d "$dir/.init.XXXXXXXX")
trap 'rm -rf -- "$work" "$MC_CONFIG_DIR"' EXIT

# The Prometheus images in Compose run with group nobody (65534).
chown 0:65534 "$dir"
chmod 0710 "$dir"

# The admin alias is temporary. No mc output is sent to the log.
mc alias set provision http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null 2>&1 ||
    fail "the provisioning alias could not be configured."

probe() (
    set -e
    local status
    exec 3<>/dev/tcp/minio/9000 || return 1
    printf 'GET /minio/v2/metrics/cluster HTTP/1.1\r\nHost: minio:9000\r\nAuthorization: Bearer %s\r\nConnection: close\r\n\r\n' "$(<"$1")" >&3 || return 1
    IFS= read -r -t 4 status <&3 || return 1
    [[ "$status" == *" 200 "* ]]
)

retire_inactive() {
    local file name
    for file in "$dir"/filegrid-prometheus-*.jwt; do
        [[ -f "$file" && "$file" != "$dir/$active" ]] || continue
        name=${file##*/}
        name=${name%.jwt}
        [[ "$name" =~ ^filegrid-prometheus-[a-f0-9]{24}$ ]] || fail "unexpected identity name."
        mc admin user rm provision "$name" >/dev/null 2>&1 ||
            fail "revoking the previous identity failed; restart the initialization."
        rm -- "$file"
    done
}

active=
if [[ -L "$dir/token" ]]; then
    active=$(readlink "$dir/token")
    [[ "$active" =~ ^filegrid-prometheus-[a-f0-9]{24}\.jwt$ ]] || fail "the token reference is invalid."
fi

if [[ -n "$active" && "${1:-}" != --rotate ]]; then
    probe "$dir/token" 2>/dev/null || fail "the existing token could not be validated; after checking MinIO, run --rotate."
    retire_inactive
    echo "MinIO metrics: the existing token is valid."
    exit 0
fi

cat > "$work/policy.json" <<'JSON'
{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":["admin:Prometheus"]}]}
JSON
mc admin policy create provision "$policy" "$work/policy.json" >/dev/null 2>&1 ||
    fail "the metrics policy could not be installed."

random_hex() {
    local bytes
    bytes=$(od -An -N"$1" -tx1 /dev/urandom)
    printf '%s' "${bytes//[[:space:]]/}"
}

identity=filegrid-prometheus-$(random_hex 12)
secret=$(random_hex 32)
MC_HOST_metrics="http://$identity:$secret@minio:9000" \
    mc --no-color admin prometheus generate metrics cluster --api-version v2 > "$work/scrape.yml" 2>/dev/null ||
    fail "mc did not generate the authenticated configuration."

token=
while IFS= read -r line; do
    if [[ "$line" =~ ^[[:space:]]*bearer_token:[[:space:]]+([a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+\.[a-zA-Z0-9_-]+)$ ]]; then
        token=${BASH_REMATCH[1]}
        break
    fi
done < "$work/scrape.yml"
[[ -n "$token" ]] || fail "the mc configuration contains no JWT token."

# The file also identifies candidates left over after an interrupted bootstrap.
printf '%s\n' "$token" > "$work/token"
chown 0:65534 "$work/token"
chmod 0440 "$work/token"
mv -T -- "$work/token" "$dir/$identity.jwt"
mc admin user add provision "$identity" "$secret" >/dev/null 2>&1 ||
    fail "the metrics identity could not be created."
unset secret token
mc admin policy attach provision "$policy" --user "$identity" >/dev/null 2>&1 ||
    fail "the metrics policy could not be attached."
probe "$dir/$identity.jwt" 2>/dev/null || fail "the candidate token is not authorized."

ln -s "$identity.jwt" "$work/next"
mv -Tf -- "$work/next" "$dir/token"
active=$identity.jwt
retire_inactive
echo "MinIO metrics: the token has been published."
