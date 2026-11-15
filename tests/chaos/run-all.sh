#!/usr/bin/env bash
# Testele de haos F9 din bash (Git Bash pe Windows, Linux, macOS). Scenariile sunt scrise o singura data, in
# PowerShell (tests/chaos/*.ps1, compatibile cu Windows PowerShell 5.1 si PowerShell 7); acest wrapper le ruleaza cu
# pwsh daca exista, altfel cu powershell.exe (Windows). Argumentele trec mai departe:
#   ./tests/chaos/run-all.sh                 # toate scenariile
#   ./tests/chaos/run-all.sh -Only a,e       # doar unele
#   ./tests/chaos/run-all.sh a-ftp-down      # un singur scenariu (numele fisierului, fara .ps1)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if command -v pwsh >/dev/null 2>&1; then ps=pwsh
elif command -v powershell.exe >/dev/null 2>&1; then ps=powershell.exe
else echo "Lipseste PowerShell: instaleaza pwsh (https://aka.ms/powershell)." >&2; exit 2; fi

script="run-all.ps1"
if [[ $# -gt 0 && -f "$here/$1.ps1" ]]; then script="$1.ps1"; shift; fi
path="$here/$script"
# powershell.exe vrea cale Windows
if [[ "$ps" == powershell.exe ]] && command -v cygpath >/dev/null 2>&1; then path="$(cygpath -w "$path")"; fi
exec "$ps" -NoProfile -ExecutionPolicy Bypass -File "$path" "$@"
