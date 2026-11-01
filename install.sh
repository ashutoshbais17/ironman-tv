#!/usr/bin/env bash
# Put the Iron Man home screen on an Android TV / Google TV, over your Wi-Fi. Mac and Linux.
#
#   ./install.sh <tv-ip>                                   the Iron Man wallpaper and beam
#   ./install.sh <tv-ip> --device thomson-sppl-4k-sw6h     plus the settings for a TV listed in devices/
#   ./install.sh <tv-ip> --wallpaper me.jpg --palms "0.42,0.31"   your own picture (palms from ironman/calibrate.html)
#   ./install.sh <tv-ip> --fx snow                         one background effect instead of all of them in turn
#
# Options: --fx fire|snow|fireice|storm|cycle|off (default cycle), --no-darken (keep your picture's colours),
# --apk <file> (a launcher APK you built yourself).
# Before running: on the TV, Settings > Device Preferences > About > press OK on "Build" 7 times, then
# Developer options > turn on USB debugging (and Network debugging, if the TV shows it).
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"
REPO="ashutoshbais17/ironman-tv"

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m%s\033[0m\n' "$*" >&2; exit 1; }
usage() { sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }

TV="" DEVICE="" WALL="" PALMS="" DARKEN=1 APK="" FX=""
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE="${2:?--device needs a name}"; shift 2 ;;
    --wallpaper) WALL="${2:?--wallpaper needs a file}"; shift 2 ;;
    --palms) PALMS="${2:?--palms needs x,y pairs}"; shift 2 ;;
    --no-darken) DARKEN=0; shift ;;
    --apk) APK="${2:?--apk needs a file}"; shift 2 ;;
    --fx) FX="${2:?--fx needs fire, snow, fireice, storm, cycle or off}"; shift 2 ;;
    -h|--help) usage ;;
    -*) die "unknown option $1 (see ./install.sh --help)" ;;
    *) [ -z "$TV" ] || die "one TV address only"; TV="$1"; shift ;;
  esac
done
[ -n "$TV" ] || usage
case "${FX:-cycle}" in fire|snow|fireice|storm|cycle|off) ;; *) die "--fx is one of fire, snow, fireice, storm, cycle, off" ;; esac
if [ -n "$DEVICE" ] && [ ! -f "devices/$DEVICE.json" ]; then
  die "no devices/$DEVICE.json. Known: $(ls devices | sed 's/\.json$//' | tr '\n' ' ')"
fi
if [ -n "$WALL" ] && [ -z "$PALMS" ]; then
  die "--wallpaper needs --palms too. Open ironman/calibrate.html, load your picture and click the palms."
fi

command -v python3 >/dev/null || die "Python 3 is needed: install it from https://www.python.org/downloads/ and run this again."

say "Setting up the tools (first run only takes a minute)"
[ -x tools/venv/bin/python ] || python3 -m venv tools/venv
tools/venv/bin/pip install -q --disable-pip-version-check -r tools/requirements.txt pillow numpy
PY="$ROOT/tools/venv/bin/python"
export TV_HOST="$TV"

say "Connecting to the TV at $TV"
echo "If the TV shows \"Allow USB debugging?\", tick \"Always allow\" and press OK."
ok=0
for try in 1 2; do
  if "$PY" -I tools/tv.py --probe; then ok=1; break; fi
  [ "$try" -lt 2 ] && { echo "Retrying in 10 seconds..."; sleep 10; }
done
[ "$ok" = 1 ] || die "Could not reach the TV. Check the IP (TV: Settings > Network), that it is on the same Wi-Fi, and that USB debugging is on."
SDK="$("$PY" -I tools/tv.py 'getprop ro.build.version.sdk' | tr -dc '0-9')"
[ -n "$SDK" ] && [ "$SDK" -ge 26 ] || die "This TV runs Android SDK ${SDK:-?}; the launcher needs Android 8.0 (SDK 26) or newer."

say "Getting the launcher app"
DEST="launcher/app/build/outputs/apk/release/app-release.apk"
mkdir -p "$(dirname "$DEST")"
if [ -n "$APK" ]; then
  cp "$APK" "$DEST"
else
  [ "$REPO" != "__REPO__" ] || die "This copy has no release to download from; build the APK (launcher/build-and-install.sh) and pass --apk."
  curl -fL --retry 3 -o "$DEST" "https://github.com/$REPO/releases/latest/download/ironman-tv.apk" \
    || die "Could not download the app. Get ironman-tv.apk from https://github.com/$REPO/releases and run again with --apk <file>."
fi

say "Preparing the wallpaper"
WORK="$ROOT/.work"
rm -rf "$WORK/theme"
mkdir -p "$WORK/theme/wallpapers"
if [ -z "$WALL" ]; then
  URL="$("$PY" -I -c 'import json;print(json.load(open("ironman/wallpaper.json"))["image"])')"
  PAGE="$("$PY" -I -c 'import json;print(json.load(open("ironman/wallpaper.json"))["page"])')"
  PALMS="$("$PY" -I -c 'import json;print(json.load(open("ironman/wallpaper.json"))["repulsor_origins"])')"
  WALL="$WORK/ironman-original.jpg"
  if [ ! -s "$WALL" ]; then
    curl -fL --retry 3 -o "$WALL" "$URL" || die "Could not download the wallpaper. Open $PAGE, download it, and run again with --wallpaper <file> --palms \"$PALMS\"."
  fi
  SIZE="$("$PY" -I -c 'import sys;from PIL import Image;print("%dx%d" % Image.open(sys.argv[1]).size)' "$WALL")"
  [ "$SIZE" = "8900x5004" ] || echo "Note: the wallpaper came back as $SIZE, not 8900x5004; if the beam misses his palms, calibrate with ironman/calibrate.html."
fi
# The figure is cut out too, so the background effects can pass behind him.
"$PY" -I ironman/prep_wallpaper.py "$WALL" "$WORK/theme/wallpapers/01-wallpaper.jpg" \
  --cutout "$WORK/theme/foreground.png" $([ "$DARKEN" = 1 ] || echo --no-darken) | tee "$WORK/prep.log"
ORIGIN="$(sed -n 's/^foreground_origin //p' "$WORK/prep.log")"

# The theme: the skin's settings, the TV's own (devices/), where the palms are, and the background effect.
FX="$FX" ORIGIN="$ORIGIN" "$PY" -I - "$WORK/theme/config.json" "$PALMS" ${DEVICE:+"devices/$DEVICE.json"} <<'EOF'
import json, os, sys
out, palms, extra = sys.argv[1], sys.argv[2], sys.argv[3:]
cfg = json.load(open("ironman/theme/config.json"))
for path in extra:
    cfg.update(json.load(open(path)))
cfg["repulsor_origins"] = palms
if os.environ["ORIGIN"]:
    cfg["foreground_origin"] = os.environ["ORIGIN"]
if os.environ["FX"]:
    cfg["ambient_fx"] = "" if os.environ["FX"] == "off" else os.environ["FX"]
json.dump(cfg, open(out, "w"), indent=2)
EOF
HKS=0
if [ -n "$DEVICE" ] && grep -q '"settings_key_keycodes"' "devices/$DEVICE.json"; then HKS=1; fi

say "Installing the launcher on the TV"
(cd launcher && SKIP_BUILD=1 HOME_KEY_SERVICE="$HKS" ./build-and-install.sh)

say "Applying the Iron Man theme"
tools/theme.sh "$WORK/theme"

say "Done. Press Home on the remote."
cat <<EOF
Move between the apps: the beam follows from his nearer hand. The arc reactor intro plays on the next power-on.
Background: ${FX:-cycle} (fire, snow, fire & ice and storm in turn with "cycle"; change it with --fx).

Watch the intro now:
  TV_HOST=$TV tools/venv/bin/python tools/tv.py 'am start -n com.gathin.tvhome/.MainActivity --ez com.gathin.tvhome.REPLAY_BOOT_INTRO true'

Undo everything (your old home screen comes back):
  ./uninstall.sh $TV

Finally, switch USB debugging (and Network debugging) off again on the TV: while it's on, anything on your
Wi-Fi can control the TV.
EOF
