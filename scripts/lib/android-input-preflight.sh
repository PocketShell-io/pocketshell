#!/usr/bin/env bash
# Android input-delivery preflight and diagnostics for the packaged JS lanes
# (issue #2946).
#
# Root cause this guards against: on some hosted API 35 boots a system app
# (Pixel Launcher, Messaging, SDK setup) ANRs shortly after boot on the
# CPU-starved runner, and its "isn't responding" dialog owns window focus.
# Every Android-injected key and tap then goes to that dialog while the app
# still reports its own WebView as focused, so the smoke lane's taps/Back and
# the lifecycle lane's first Enter were silently lost until the dialog's
# process died. Evidence: hosted runs 36566079993, 36587126665, 36610880719
# and 36779335838 all log `Input channel ... Application Not Responding:
# <system package>` being disposed after both lanes had failed.
#
# The fix is deterministic and happens before each lane touches the device:
#   1. `hide_error_dialogs=1` makes ActivityTaskManager stop showing ANR and
#      crash dialogs, so a later system-app ANR kills that app instead of
#      stealing focus. A PocketShell ANR still kills PocketShell, which fails
#      the journey loudly; nothing about the product is hidden.
#   2. Any error dialog that is already on screen is dismissed by
#      force-stopping the package it belongs to (the dialog is removed with
#      its process record), then the window list is re-read to prove it is
#      gone. A dialog that survives fails the lane before any test runs.
#      A dialog owned by a com.pocketshell* package is never dismissed: it is
#      a PocketShell ANR/crash, so the lane fails loudly instead.
# Every action is logged to the lane's evidence file; nothing is retried
# silently.

# Print the live system error dialog titles, one per line, from
# `dumpsys window windows`. Only the live window list is read: plain
# `dumpsys window` also prints the LAST ANR snapshot, which names windows
# that no longer exist.
pocketshell_android_error_windows() {
  local adb="$1" serial="$2"
  "$adb" -s "$serial" shell dumpsys window windows 2>/dev/null \
    | tr -d '\r' \
    | sed -nE 's/^ *Window #[0-9]+ Window\{[0-9a-f]+ u[0-9]+ ((Application Not Responding|Application Error)(: [^}]*)?)\}:.*$/\1/p'
}

# Usage: pocketshell_android_input_preflight ADB SERIAL EVIDENCE_FILE
pocketshell_android_input_preflight() {
  local adb="$1" serial="$2" evidence="$3"
  local deadline title package value remaining
  mkdir -p "$(dirname -- "$evidence")"
  : > "$evidence" || return 1
  {
    printf 'android input preflight (#2946) on %s at %s\n' "$serial" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    "$adb" -s "$serial" shell settings put global hide_error_dialogs 1
    value="$("$adb" -s "$serial" shell settings get global hide_error_dialogs | tr -d '\r')"
    printf 'hide_error_dialogs=%s\n' "$value"
  } >> "$evidence" 2>&1
  if [[ "$value" != 1 ]]; then
    printf 'FAIL: could not disable system error dialogs on %s (hide_error_dialogs=%s)\n' "$serial" "$value" | tee -a "$evidence" >&2
    return 1
  fi

  deadline=$((SECONDS + ${POCKETSHELL_INPUT_PREFLIGHT_DISMISS_SECONDS:-30}))
  while :; do
    mapfile -t remaining < <(pocketshell_android_error_windows "$adb" "$serial")
    if (( ${#remaining[@]} == 0 )); then
      break
    fi
    if (( SECONDS >= deadline )); then
      printf 'FAIL: system error dialog still owns the screen on %s after dismissal: %s\n' \
        "$serial" "${remaining[*]}" | tee -a "$evidence" >&2
      return 1
    fi
    for title in "${remaining[@]}"; do
      package="${title#*: }"
      if [[ "$package" == "$title" || ! "$package" =~ ^[A-Za-z0-9._]+$ ]]; then
        printf 'FAIL: system error dialog has no dismissible package: %s\n' "$title" | tee -a "$evidence" >&2
        return 1
      fi
      if [[ "$package" == com.pocketshell || "$package" == com.pocketshell.* ]]; then
        # A PocketShell ANR/crash is a product failure, never harness noise.
        printf 'FAIL: POCKETSHELL_ERROR_DIALOG: PocketShell itself owns a system error dialog on %s: %s. Refusing to dismiss it; investigate the app ANR/crash.\n' \
          "$serial" "$title" | tee -a "$evidence" >&2
        return 1
      fi
      printf 'DISMISSED_SYSTEM_ERROR_DIALOG: %s (am force-stop %s)\n' "$title" "$package" | tee -a "$evidence" >&2
      "$adb" -s "$serial" shell am force-stop "$package" >> "$evidence" 2>&1 || true
    done
    sleep 1
  done

  {
    printf 'system error dialogs: none\n'
    "$adb" -s "$serial" shell dumpsys window displays | tr -d '\r' | grep -E 'mCurrentFocus=|mFocusedApp=' || true
  } >> "$evidence" 2>&1
  printf 'PASS: Android input preflight on %s (no system error dialog; hide_error_dialogs=1)\n' "$serial"
}

# Usage: pocketshell_android_capture_input_diagnostics ADB SERIAL OUTPUT_DIR
# Writes the files scripts/check-android-input-diagnostics.py requires.
pocketshell_android_capture_input_diagnostics() {
  local adb="$1" serial="$2" output="$3"
  mkdir -p "$output"
  "$adb" -s "$serial" shell dumpsys input > "$output/dumpsys-input.txt" 2>&1 || true
  "$adb" -s "$serial" shell dumpsys window windows > "$output/dumpsys-window-windows.txt" 2>&1 || true
  "$adb" -s "$serial" shell dumpsys window displays > "$output/dumpsys-window-displays.txt" 2>&1 || true
  "$adb" -s "$serial" shell dumpsys window lastanr > "$output/dumpsys-window-lastanr.txt" 2>&1 || true
  "$adb" -s "$serial" shell dumpsys input_method > "$output/dumpsys-input-method.txt" 2>&1 || true
  "$adb" -s "$serial" shell dumpsys activity activities > "$output/dumpsys-activity-activities.txt" 2>&1 || true
  "$adb" -s "$serial" logcat -d -b all -v threadtime > "$output/logcat-all.txt" 2>&1 || true
  "$adb" -s "$serial" exec-out screencap -p > "$output/device-screen.png" 2>/dev/null || true
}
