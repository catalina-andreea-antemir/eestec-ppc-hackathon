#!/usr/bin/env bash
# MinIO healthcheck: GET /minio/health/live (no auth). The image has no curl, so bash's /dev/tcp is used.
set -eu
exec 3<>/dev/tcp/127.0.0.1/9000
printf 'GET /minio/health/live HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r -t 3 status <&3
[[ "$status" == *" 200 "* ]]
