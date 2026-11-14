#!/bin/sh
# Ruleaza la fiecare pornire (docker-entrypoint.d al imaginii nginx). Genereaza certificatul self-signed o singura
# data, in volumul nginx-certs; nu se comite nimic. Pentru un certificat nou: docker compose down -v (sau stergi volumul).
set -eu

cert_dir=/etc/nginx/certs
crt=$cert_dir/tls.crt
key=$cert_dir/tls.key

if [ -s "$crt" ] && [ -s "$key" ]; then
    exit 0
fi

echo "10-self-signed-cert: generez certificatul self-signed pentru localhost"
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -sha256 -days 825 \
    -subj "/CN=localhost/O=FileGrid PPC" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" \
    -addext "keyUsage=digitalSignature,keyEncipherment" \
    -addext "extendedKeyUsage=serverAuth" \
    -keyout "$key.tmp" -out "$crt.tmp" 2>/dev/null
mv "$key.tmp" "$key"
mv "$crt.tmp" "$crt"
chmod 600 "$key"
chmod 644 "$crt"
