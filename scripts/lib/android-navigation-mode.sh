# shellcheck shell=bash
# Record and restore an emulator's system navigation mode around a lane (#3158).
#
# A journey that changes the navigation mode (J30 did until #3158 round 6) and
# dies before its own cleanup (a WebView crash, an OOM kill on a slow emulator)
# would leak that mode into every later lane on the same emulator. The runner
# therefore records the mode before instrumentation and restores it from its
# EXIT trap, host-side, whatever happened in between. Kept as a guard.
#
# navigation_mode (Settings.Secure): 0 three-button, 1 two-button, 2 gesture.

POCKETSHELL_NAV_MODE_RECORDED=""

pocketshell_android_navigation_overlay() {
  case "$1" in
    0) printf 'com.android.internal.systemui.navbar.threebutton\n' ;;
    1) printf 'com.android.internal.systemui.navbar.twobutton\n' ;;
    2) printf 'com.android.internal.systemui.navbar.gestural\n' ;;
    *) return 1 ;;
  esac
}

pocketshell_android_read_navigation_mode() {
  local adb="$1" serial="$2"
  "$adb" -s "$serial" shell settings get secure navigation_mode 2>/dev/null | tr -d '\r[:space:]'
}

# Usage: pocketshell_android_record_navigation_mode ADB SERIAL
pocketshell_android_record_navigation_mode() {
  local adb="$1" serial="$2" mode
  mode="$(pocketshell_android_read_navigation_mode "$adb" "$serial")"
  if pocketshell_android_navigation_overlay "$mode" >/dev/null; then
    POCKETSHELL_NAV_MODE_RECORDED="$mode"
    printf 'RECORDED_NAVIGATION_MODE: %s on %s\n' "$mode" "$serial"
    return 0
  fi
  POCKETSHELL_NAV_MODE_RECORDED=""
  printf 'WARN: unrecognised navigation_mode %s on %s; it will not be restored\n' "${mode:-<empty>}" "$serial" >&2
  return 1
}

# Usage: pocketshell_android_restore_navigation_mode ADB SERIAL
# A no-op when nothing was recorded or the mode is already the recorded one.
pocketshell_android_restore_navigation_mode() {
  local adb="$1" serial="$2" want="$POCKETSHELL_NAV_MODE_RECORDED" now overlay attempt
  [[ -n "$want" ]] || return 0
  now="$(pocketshell_android_read_navigation_mode "$adb" "$serial")"
  if [[ "$now" == "$want" ]]; then
    printf 'NAVIGATION_MODE_UNCHANGED: %s on %s\n' "$want" "$serial"
    return 0
  fi
  overlay="$(pocketshell_android_navigation_overlay "$want")" || return 1
  "$adb" -s "$serial" shell cmd overlay enable-exclusive --category "$overlay" >/dev/null 2>&1 || true
  for attempt in $(seq 1 "${POCKETSHELL_NAV_MODE_RESTORE_POLLS:-30}"); do
    now="$(pocketshell_android_read_navigation_mode "$adb" "$serial")"
    if [[ "$now" == "$want" ]]; then
      printf 'RESTORED_NAVIGATION_MODE: %s on %s\n' "$want" "$serial"
      return 0
    fi
    sleep "${POCKETSHELL_NAV_MODE_RESTORE_SLEEP:-0.5}"
  done
  printf 'FAIL: navigation_mode on %s is %s after restore, expected %s\n' "$serial" "${now:-<empty>}" "$want" >&2
  return 1
}
