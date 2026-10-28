#!/usr/bin/env bash
# Temporary helper for the ui-screenshot workflow. Captures the editor screen
# of the previous release and of the current debug build so the top bar can be
# compared visually.
set -euo pipefail

ADB_TIMEOUT=180
adb() { command adb -s emulator-5554 "$@"; }
export -f adb 2>/dev/null || true

wait_for_boot() {
  local waited=0
  while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
    sleep 5
    waited=$((waited + 5))
    if [ "${waited}" -ge "${ADB_TIMEOUT}" ]; then
      echo "device did not report boot_completed" >&2
      return 1
    fi
  done
  sleep 5
}

wait_for_boot

# Tablet-ish viewport in landscape.
adb shell wm size 1600x1000 || true
adb shell wm density 180 || true
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 0 || true
sleep 3

# A tall display cutout makes the safe-drawing inset large, which is exactly
# the situation where a top bar can end up hidden underneath the system UI.
adb shell cmd overlay enable-exclusive --category \
  com.android.internal.display.cutout.emulation.tall > /dev/null 2>&1 || true
sleep 3

# Sanity check: if even the home screen captures black, the emulator GPU is the
# problem rather than the app.
adb exec-out screencap -p > home.png
adb shell dumpsys window displays | grep -iE "cutout|stable|mCurrentFocus" | head -12 || true

capture() {
  local prefix="$1"
  adb shell am force-stop com.pianocanvas.midi || true
  adb shell pm clear com.pianocanvas.midi > /dev/null 2>&1 || true
  adb shell am start -W -n com.pianocanvas.midi/.MainActivity || true
  sleep 12
  adb exec-out screencap -p > "${prefix}-welcome.png"
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || true
  adb pull /sdcard/window.xml "${prefix}-window.xml" > /dev/null 2>&1 || true

  local coords=""
  if [ -f "${prefix}-window.xml" ]; then
    coords=$(python3 - "${prefix}-window.xml" <<'PY' || true
import re
import sys

try:
    xml = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    raise SystemExit(0)
patterns = [
    r'text="新しい曲を作成"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
    r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="新しい曲を作成"',
    r'content-desc="新しい曲を作成"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
]
for pattern in patterns:
    match = re.search(pattern, xml)
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}")
        break
PY
  )
  fi

  if [ -z "${coords}" ]; then
    local size w h
    size=$(adb shell wm size | tr -d '\r' | awk -F' ' '{print $3}')
    w=${size%x*}
    h=${size#*x}
    coords="$((w / 2)) $((h * 42 / 100))"
  fi

  echo "Tap coordinates for ${prefix}: ${coords}"
  # shellcheck disable=SC2086
  adb shell input tap ${coords}
  sleep 12
  adb exec-out screencap -p > "${prefix}-editor.png"
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || true
  adb pull /sdcard/window.xml "${prefix}-editor-window.xml" > /dev/null 2>&1 || true
  # Dump the current focus/insets so a failure can be diagnosed from the log.
  adb shell dumpsys window | grep -E "mCurrentFocus|mSystemUiFlags" | head -5 || true
}

RELEASE_APK=$(ls release/*.apk | head -1)
echo "Installing previous release: ${RELEASE_APK}"
adb uninstall com.pianocanvas.midi > /dev/null 2>&1 || true
adb install -r "${RELEASE_APK}"
capture before

echo "Installing current debug build"
adb uninstall com.pianocanvas.midi > /dev/null 2>&1 || true
adb install -r app/build/outputs/apk/debug/app-debug.apk
capture after

echo "Screenshots:"
ls -l ./*.png
