#!/usr/bin/env bash
# Undo install.sh: the TV's own home screen comes back and the Iron Man launcher is removed. Mac and Linux.
#   ./uninstall.sh <tv-ip>
set -euo pipefail
cd "$(dirname "$0")"
TV="${1:?usage: ./uninstall.sh <tv-ip>}"
export TV_HOST="$TV"
PY="tools/venv/bin/python"
[ -x "$PY" ] || { python3 -m venv tools/venv && tools/venv/bin/pip install -q -r tools/requirements.txt; }
tv() { "$PY" -I tools/tv.py "$@"; }
OURS=com.gathin.tvhome
HOME_Q='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

echo "Removing the Iron Man theme..."
tools/theme.sh --reset || true

# install.sh disabled the stock launcher once the new one owned Home; turn it back on.
DISABLED="$(tv 'pm list packages -d --user 0' | tr -d '\r' | sed -n 's/^package://p')"
STOCK=""
for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.leanbacklauncher com.mitv.tvhome; do
  if printf '%s\n' "$DISABLED" | grep -qx "$p"; then
    echo "Turning the stock launcher back on: $p"
    tv "pm enable --user 0 $p" >/dev/null
    STOCK="$p"
  fi
done
if [ -n "$STOCK" ]; then
  tv "cmd package set-home-activity --user 0 \$($HOME_Q $STOCK | tail -n 1)" >/dev/null || true
fi

# Something else must be able to answer Home before ours goes, or the TV has no home screen.
OTHERS="$(tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
  | tr -d '\r' | grep -oE '^ *[A-Za-z0-9_.]+/' | tr -d ' /' | sort -u | grep -vx "$OURS" || true)"
if [ -z "$OTHERS" ]; then
  echo "No other home screen is enabled on this TV, so the Iron Man launcher stays installed (removing it would leave"
  echo "the TV with no Home). Re-enable your TV's launcher in Settings > Apps, then run this again."
  exit 1
fi

echo "Removing the Iron Man launcher..."
tv "pm uninstall $OURS"
echo "Home now: $(tv "$HOME_Q" | tail -n 1)"
echo "Done. Press Home on the remote. Remember to switch USB debugging off again on the TV."
