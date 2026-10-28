#!/usr/bin/env bash
# Temporary helper for the ui-screenshot workflow. Captures the editor screen of
# the previous release and of the current debug build with a display cutout and
# a status bar active, then verifies from the UI hierarchy that the top bar
# clears the system bars. PNG screenshots are best effort (the software renderer
# often produces black frames); the XML dumps are the ground truth.
set -euo pipefail

ADB_TIMEOUT=300
adb() { command adb -s emulator-5554 "$@"; }

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

install_with_retry() {
  local apk="$1" attempt=1
  # The package verifier occasionally times out on slow emulators
  # (INSTALL_FAILED_VERIFICATION_FAILURE). Disable it and retry a few times.
  adb shell settings put global verifier_verify_adb_installs 0 || true
  adb shell settings put global package_verifier_enable 0 || true
  until adb install -r "${apk}"; do
    attempt=$((attempt + 1))
    if [ "${attempt}" -gt 3 ]; then
      echo "install kept failing: ${apk}" >&2
      return 1
    fi
    echo "install failed (attempt $((attempt - 1))), retrying in 15s"
    sleep 15
  done
}

wait_for_boot

# Tablet-ish viewport in landscape.
adb shell wm size 1600x1000 | tee wm-size.txt || true
adb shell wm density 180 | tee wm-density.txt || true
adb shell settings put system accelerometer_rotation 0 || true
adb shell settings put system user_rotation 0 || true
sleep 3

# A tall display cutout makes the safe-drawing inset large, which is exactly
# the situation where a top bar can end up hidden underneath the system UI.
# "enable --category" is not a valid form; the overlay package itself is what
# needs to be enabled (see developer.android.com display-cutout docs).
echo "--- cutout overlays available on this image ---"
adb shell cmd overlay list | grep -i cutout || true
adb shell cmd overlay enable com.android.internal.display.cutout.emulation.tall || true
sleep 5
echo "--- display cutout / stable insets after enabling the overlay ---"
adb shell dumpsys window displays | grep -iE "cutout|stable" | head -8 || true

capture() {
  local prefix="$1"
  adb shell am force-stop com.pianocanvas.midi || true
  adb shell pm clear com.pianocanvas.midi > /dev/null 2>&1 || true
  adb shell am start -W -n com.pianocanvas.midi/.MainActivity || true
  sleep 12
  adb exec-out screencap -p > "${prefix}-welcome.png" || true
  adb shell rm -f /sdcard/window.xml || true
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
  adb exec-out screencap -p > "${prefix}-editor.png" || true
  adb shell rm -f /sdcard/window.xml || true
  adb shell uiautomator dump /sdcard/window.xml > /dev/null 2>&1 || true
  adb pull /sdcard/window.xml "${prefix}-editor-window.xml" > /dev/null 2>&1 || true
  # Save the window/inset state so the verification step and any failure can be
  # diagnosed from the artifacts alone.
  adb shell dumpsys window displays > "${prefix}-displays.txt" || true
  adb shell dumpsys window >> "${prefix}-displays.txt" 2>/dev/null || true
}

RELEASE_APK=$(ls release/*.apk | head -1)
echo "Installing previous release: ${RELEASE_APK}"
adb uninstall com.pianocanvas.midi > /dev/null 2>&1 || true
install_with_retry "${RELEASE_APK}"
capture before

echo "Installing current debug build"
adb uninstall com.pianocanvas.midi > /dev/null 2>&1 || true
install_with_retry app/build/outputs/apk/debug/app-debug.apk
capture after

# Assert from the UI hierarchy that the top bar of the new build clears the
# status bar and the display cutout, and render wireframe SVGs for review.
python3 .github/scripts/verify_top_bar.py

echo "Screenshots:"
ls -l ./*.png ./*.svg 2>/dev/null || true
