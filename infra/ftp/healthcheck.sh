#!/bin/sh
# Healthcheck: serverul FTP raspunde cu bannerul 220 pe portul 21.
printf 'QUIT\r\n' | nc -w 3 127.0.0.1 21 2>/dev/null | grep -q '^220'
