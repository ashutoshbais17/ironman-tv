#!/usr/bin/env bash
# Replay the arc reactor intro and its spoken greeting on the TV, without restarting it. Mac and Linux.
#
#   ./replay-intro.sh <tv-ip>
#
# Windows: $env:TV_HOST='<tv-ip>'; tools\venv\Scripts\python.exe tools\tv.py "am start -n com.gathin.tvhome/.MainActivity --ez com.gathin.tvhome.REPLAY_BOOT_INTRO true"
# Needs USB debugging on, like the installer. Home comes to the front first, then the intro plays over it.
set -euo pipefail
cd "$(dirname "$0")"
[ $# -eq 1 ] || { sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
[ -x tools/venv/bin/python ] || { echo "Run ./install.sh first: it sets up the tools this needs." >&2; exit 1; }
TV_HOST="$1" tools/venv/bin/python tools/tv.py \
  "input keyevent HOME; sleep 1; am start -n com.gathin.tvhome/.MainActivity --ez com.gathin.tvhome.REPLAY_BOOT_INTRO true >/dev/null 2>&1; echo 'Playing on the TV.'"
