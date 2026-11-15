#!/usr/bin/env bash
# F9 chaos tests from bash (Git Bash on Windows, Linux, macOS). Scenarios are written once, in PowerShell
# (tests/chaos/*.ps1, compatible with Windows PowerShell 5.1 and PowerShell 7); this wrapper runs them with
# pwsh if available, otherwise powershell.exe (Windows). Arguments are passed through:
#   ./tests/chaos/run-all.sh                 # all scenarios
#   ./tests/chaos/run-all.sh -Only a,e       # a subset
#   ./tests/chaos/run-all.sh a-ftp-down      # a single scenario (file name without .ps1)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
if command -v pwsh >/dev/null 2>&1; then ps=pwsh
elif command -v powershell.exe >/dev/null 2>&1; then ps=powershell.exe
else echo "PowerShell is missing: install pwsh (https://aka.ms/powershell)." >&2; exit 2; fi

script="run-all.ps1"
if [[ $# -gt 0 && -f "$here/$1.ps1" ]]; then script="$1.ps1"; shift; fi
path="$here/$script"
# powershell.exe needs a Windows path
if [[ "$ps" == powershell.exe ]] && command -v cygpath >/dev/null 2>&1; then path="$(cygpath -w "$path")"; fi
exec "$ps" -NoProfile -ExecutionPolicy Bypass -File "$path" "$@"
