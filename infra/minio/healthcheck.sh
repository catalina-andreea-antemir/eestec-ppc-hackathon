#!/usr/bin/env bash
# Healthcheck MinIO: GET /minio/health/live (fara autentificare). Imaginea nu are curl, deci folosim /dev/tcp din bash.
set -eu
exec 3<>/dev/tcp/127.0.0.1/9000
printf 'GET /minio/health/live HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r -t 3 status <&3
[[ "$status" == *" 200 "* ]]
