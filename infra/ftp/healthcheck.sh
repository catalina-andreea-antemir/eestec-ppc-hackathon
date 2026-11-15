#!/bin/sh
# Healthcheck: the FTP server answers with a 220 banner on port 21.
printf 'QUIT\r\n' | nc -w 3 127.0.0.1 21 2>/dev/null | grep -q '^220'
