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
#   1. The HOME launcher (Pixel Launcher on the CI image, Launcher3 locally)
#      is disabled for the lane (`pm disable-user` + `am force-stop`), so the
#      app that ANRed in every recorded hosted failure cannot run at all.
#      Android's built-in Settings FallbackHome (a blank activity that does
#      no work) is HOME meanwhile. The lane's EXIT path re-enables exactly
#      the packages this preflight disabled (pocketshell_release_all calls
#      pocketshell_android_restore_launchers while the AVD lock is held).
#      Round 2 of #2946 showed this is required: hosted run 36792962871
#      attempt 3 got a Pixel Launcher ANR dialog mid-lane although
#      hide_error_dialogs=1 was already set.
#   2. `hide_error_dialogs=1` asks ActivityTaskManager not to show ANR and
#      crash dialogs for any other app. It is best effort (see above), so it
#      is not relied on alone. A PocketShell ANR still fails the journey.
#   3. Any error dialog that is already on screen is dismissed by
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

# Print every package that provides a HOME activity, except Android's own
# FallbackHome (com.android.settings) and the framework resolver.
# Fails (status 1) when the query itself did not answer, so an adb error is
# never mistaken for "no launcher".
pocketshell_android_home_packages() {
  local adb="$1" serial="$2" output
  output="$("$adb" -s "$serial" shell cmd package query-activities --brief \
      -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1 | tr -d '\r')" || return 1
  [[ "$output" == *"activities found"* || "$output" == *"No activities found"* ]] || return 1
  sed -nE 's#^ *([A-Za-z0-9._]+)/[A-Za-z0-9._$]+$#\1#p' <<< "$output" \
    | { grep -vxE 'com\.android\.settings|android' || true; } \
    | sort -u
}

# Launchers known to be safe to re-enable when found disabled with no live
# lane owning the emulator (recovery of a lane killed before its restore).
POCKETSHELL_KNOWN_LAUNCHERS=(com.google.android.apps.nexuslauncher com.android.launcher3)

POCKETSHELL_DISABLED_LAUNCHERS=()
POCKETSHELL_LAUNCHER_ADB=""
POCKETSHELL_LAUNCHER_SERIAL=""

# Host-side record of launchers a lane disabled on SERIAL, next to that
# serial's AVD lock. It is written BEFORE `pm disable-user` and an entry is
# removed only after `pm enable` succeeded, so a SIGKILLed lane or an
# emulator that disconnected mid-restore leaves a record the next lane (or
# the emulator start path) uses to re-enable the launcher.
pocketshell_android_launcher_record() {
  local serial="$1" dir
  if declare -F pocketshell_avd_lock_dir > /dev/null 2>&1; then
    dir="$(pocketshell_avd_lock_dir)"
  else
    dir="${POCKETSHELL_AVD_LOCK_DIR:-${HOME:-/tmp}/.cache/pocketshell/avd-locks}"
  fi
  mkdir -p "$dir" 2> /dev/null || true
  # Same token as the serial's AVD lock file (pocketshell_avd_lock_file_for_serial).
  local token="${serial//[^A-Za-z0-9._-]/_}"
  printf '%s/avd-lock-%s.disabled-launchers\n' "${dir%/}" "${token:-default}"
}

_pocketshell_launcher_record_add() {
  local record="$1" package="$2"
  grep -Fxq -- "$package" "$record" 2> /dev/null || printf '%s\n' "$package" >> "$record"
}

_pocketshell_launcher_record_remove() {
  local record="$1" package="$2" rest
  [[ -f "$record" ]] || return 0
  rest="$(grep -Fxv -- "$package" "$record" || true)"
  if [[ -n "$rest" ]]; then
    printf '%s\n' "$rest" > "$record.tmp" && mv -f "$record.tmp" "$record"
  else
    rm -f -- "$record"
  fi
}

# `pm enable` and report success only when the package really is enabled.
_pocketshell_enable_package() {
  local adb="$1" serial="$2" package="$3" output
  output="$("$adb" -s "$serial" shell pm enable "$package" 2>&1 | tr -d '\r')" || return 1
  [[ "$output" == *"new state: enabled"* ]]
}

# Re-enable launchers left disabled by a lane that never restored them
# (SIGKILL, lost adb). Call only while holding SERIAL's AVD lock, or after
# proving no lane holds it. Usage: ADB SERIAL EVIDENCE_FILE
pocketshell_android_recover_stale_launchers() {
  local adb="$1" serial="$2" evidence="$3"
  local record package disabled
  record="$(pocketshell_android_launcher_record "$serial")"
  if [[ -s "$record" ]]; then
    while IFS= read -r package; do
      [[ -n "$package" ]] || continue
      if _pocketshell_enable_package "$adb" "$serial" "$package"; then
        _pocketshell_launcher_record_remove "$record" "$package"
        printf 'RECOVERED_STALE_DISABLED_LAUNCHER: %s (recorded in %s)\n' "$package" "$record" | tee -a "$evidence" >&2
      else
        printf 'FAIL: could not re-enable stale disabled launcher %s on %s (record kept: %s)\n' \
          "$package" "$serial" "$record" | tee -a "$evidence" >&2
        return 1
      fi
    done < <(cat -- "$record")
  fi
  disabled="$("$adb" -s "$serial" shell pm list packages -d 2> /dev/null | tr -d '\r' | sed -n 's/^package://p' || true)"
  for package in "${POCKETSHELL_KNOWN_LAUNCHERS[@]}"; do
    grep -Fxq -- "$package" <<< "$disabled" || continue
    if _pocketshell_enable_package "$adb" "$serial" "$package"; then
      printf 'RECOVERED_STALE_DISABLED_LAUNCHER: %s (unrecorded)\n' "$package" | tee -a "$evidence" >&2
    else
      printf 'FAIL: could not re-enable stale disabled launcher %s on %s\n' "$package" "$serial" | tee -a "$evidence" >&2
      return 1
    fi
  done
}

# Disable every HOME provider until the set stays empty. A post-boot setup
# app (com.google.android.googlesdksetup on the hosted image) can start
# answering HOME only after the launcher is disabled, so one pass is not
# enough. Usage: ADB SERIAL EVIDENCE_FILE
pocketshell_android_disable_launchers() {
  local adb="$1" serial="$2" evidence="$3"
  local package record deadline stable found existing
  local -a current
  POCKETSHELL_LAUNCHER_ADB="$adb"
  POCKETSHELL_LAUNCHER_SERIAL="$serial"
  record="$(pocketshell_android_launcher_record "$serial")"
  deadline=$((SECONDS + ${POCKETSHELL_LAUNCHER_DISABLE_SECONDS:-60}))
  stable=0
  local listing
  while (( stable < ${POCKETSHELL_LAUNCHER_STABLE_SAMPLES:-5} )); do
    if ! listing="$(pocketshell_android_home_packages "$adb" "$serial")"; then
      printf 'FAIL: could not list HOME activities on %s\n' "$serial" | tee -a "$evidence" >&2
      return 1
    fi
    mapfile -t current < <(printf '%s' "$listing" | sed '/^$/d')
    if (( ${#current[@]} == 0 )); then
      stable=$((stable + 1))
    else
      stable=0
      if (( SECONDS >= deadline )); then
        printf 'FAIL: HOME launcher still enabled on %s after disable: %s\n' "$serial" "${current[*]}" | tee -a "$evidence" >&2
        return 1
      fi
      for package in "${current[@]}"; do
        if [[ "$package" == com.pocketshell || "$package" == com.pocketshell.* ]]; then
          printf 'FAIL: a PocketShell package declares a HOME activity: %s\n' "$package" | tee -a "$evidence" >&2
          return 1
        fi
        _pocketshell_launcher_record_add "$record" "$package"
        found=0
        for existing in "${POCKETSHELL_DISABLED_LAUNCHERS[@]}"; do
          [[ "$existing" == "$package" ]] && found=1
        done
        (( found )) || POCKETSHELL_DISABLED_LAUNCHERS+=("$package")
        "$adb" -s "$serial" shell pm disable-user --user 0 "$package" >> "$evidence" 2>&1
        "$adb" -s "$serial" shell am force-stop "$package" >> "$evidence" 2>&1 || true
        printf 'DISABLED_LAUNCHER_FOR_LANE: %s\n' "$package" | tee -a "$evidence" >&2
      done
    fi
    sleep "${POCKETSHELL_LAUNCHER_SAMPLE_SECONDS:-1}"
  done
  local attempt
  for package in "${POCKETSHELL_DISABLED_LAUNCHERS[@]}"; do
    # force-stop normally kills at once. A launcher process that was still
    # starting when it was disabled can outlive the first force-stop, so
    # repeat the force-stop for up to 15 s, then fail the lane.
    for attempt in $(seq 1 30); do
      [[ -z "$("$adb" -s "$serial" shell pidof "$package" 2>/dev/null | tr -d '\r')" ]] && break
      if (( attempt == 30 )); then
        printf 'FAIL: disabled launcher %s is still running on %s\n' "$package" "$serial" | tee -a "$evidence" >&2
        return 1
      fi
      "$adb" -s "$serial" shell am force-stop "$package" >> "$evidence" 2>&1 || true
      sleep 0.5
    done
  done
  printf 'HOME during lane: com.android.settings FallbackHome only (stable for %s samples)\n' \
    "${POCKETSHELL_LAUNCHER_STABLE_SAMPLES:-5}" >> "$evidence"
}

# Re-enable exactly the launchers this process disabled, last-disabled
# first. Called from pocketshell_release_all, so every catchable lane exit
# restores the device; a record entry is dropped only after `pm enable`
# succeeded, so anything left over is recovered by the next lane.
pocketshell_android_restore_launchers() {
  local index package record
  (( ${#POCKETSHELL_DISABLED_LAUNCHERS[@]} > 0 )) || return 0
  record="$(pocketshell_android_launcher_record "$POCKETSHELL_LAUNCHER_SERIAL")"
  for (( index = ${#POCKETSHELL_DISABLED_LAUNCHERS[@]} - 1; index >= 0; index-- )); do
    package="${POCKETSHELL_DISABLED_LAUNCHERS[$index]}"
    if _pocketshell_enable_package "$POCKETSHELL_LAUNCHER_ADB" "$POCKETSHELL_LAUNCHER_SERIAL" "$package"; then
      _pocketshell_launcher_record_remove "$record" "$package"
      printf 'RESTORED_LAUNCHER: %s\n' "$package" >&2
    else
      printf 'WARNING: could not re-enable launcher %s on %s; kept in %s for the next lane to recover\n' \
        "$package" "$POCKETSHELL_LAUNCHER_SERIAL" "$record" >&2
    fi
  done
  POCKETSHELL_DISABLED_LAUNCHERS=()
}

# True when this shell itself holds SERIAL's AVD lock (start-local-avd.sh and
# the lane runners take it before calling us). A fresh `flock -n` probe would
# fail against our own lock, so ownership is read from the lock helper's state.
_pocketshell_caller_owns_avd_lock() {
  local lock_file="$1"
  [[ -n "${POCKETSHELL_AVD_LOCK_ACQUIRED:-}" \
     && "${POCKETSHELL_AVD_LOCK_OWNER_PID:-}" == "$$" \
     && -n "${POCKETSHELL_AVD_LOCK_FILE:-}" \
     && -e "$lock_file" \
     && "$POCKETSHELL_AVD_LOCK_FILE" -ef "$lock_file" ]]
}

# Emulator start path (start-local-avd.sh, avd-pool.sh start): recover stale
# launchers on SERIAL unless ANOTHER process holds its AVD lock, so a live
# lane's deliberately disabled launcher is never re-enabled underneath it.
# Every decision is logged on stderr as STALE_LAUNCHER_RECOVERY.
pocketshell_android_recover_stale_launchers_if_idle() {
  local adb="$1" serial="$2" lock_file
  if [[ -z "$serial" || "$serial" == unknown ]]; then
    printf 'STALE_LAUNCHER_RECOVERY: skipped, no emulator serial\n' >&2
    return 0
  fi
  if ! declare -F pocketshell_avd_lock_file_for_serial > /dev/null 2>&1; then
    printf 'STALE_LAUNCHER_RECOVERY: %s skipped, AVD lock helpers are not loaded\n' "$serial" >&2
    return 0
  fi
  lock_file="$(pocketshell_avd_lock_file_for_serial "" "$serial")"
  if _pocketshell_caller_owns_avd_lock "$lock_file"; then
    printf 'STALE_LAUNCHER_RECOVERY: %s checked under this process'"'"'s own AVD lock\n' "$serial" >&2
    pocketshell_android_recover_stale_launchers "$adb" "$serial" /dev/null
    return
  fi
  (
    exec 9> "$lock_file"
    if flock -n 9; then
      printf 'STALE_LAUNCHER_RECOVERY: %s checked (AVD lock idle)\n' "$serial" >&2
      pocketshell_android_recover_stale_launchers "$adb" "$serial" /dev/null
    else
      printf 'STALE_LAUNCHER_RECOVERY: %s skipped, its AVD lock is held by another process\n' "$serial" >&2
    fi
  )
}

# Recover stale launchers on SERIAL, or on every booted emulator adb lists
# when SERIAL is empty (several emulators, no ANDROID_SERIAL).
pocketshell_android_recover_stale_launchers_on_devices() {
  local adb="$1" serial="${2:-}" status=0
  local -a serials
  if [[ -n "$serial" ]]; then
    serials=("$serial")
  else
    mapfile -t serials < <("$adb" devices 2> /dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')
  fi
  if (( ${#serials[@]} == 0 )); then
    printf 'STALE_LAUNCHER_RECOVERY: skipped, no booted device\n' >&2
    return 0
  fi
  for serial in "${serials[@]}"; do
    pocketshell_android_recover_stale_launchers_if_idle "$adb" "$serial" || status=1
  done
  return "$status"
}

# Injected taps (#2884, #2946): Android and the WebView start the long-press
# timer in real time when ACTION_DOWN is dispatched. On a starved hosted
# emulator the ACTION_UP can reach the app most of a second later, which at the
# stock 400 ms timeout turns a tap into a long press: no click, a text
# selection, and Select-to-Speak taking window focus. Stamping the up with an
# early event time does not help (the timer is not event-time based), so every
# lane raises the system long-press timeout; journeys that hold a key do so far
# below it. Usage: ADB SERIAL EVIDENCE_FILE
pocketshell_android_raise_long_press_timeout() {
  local adb="$1" serial="$2" evidence="$3" value
  local wanted="${POCKETSHELL_LANE_LONG_PRESS_TIMEOUT_MS:-3000}"
  mkdir -p "$(dirname -- "$evidence")"
  {
    "$adb" -s "$serial" shell settings put secure long_press_timeout "$wanted"
    value="$("$adb" -s "$serial" shell settings get secure long_press_timeout | tr -d '\r')"
    printf 'long_press_timeout=%s\n' "$value"
  } >> "$evidence" 2>&1
  if [[ "$value" != "$wanted" ]]; then
    printf 'FAIL: could not raise the long-press timeout on %s (long_press_timeout=%s)\n' "$serial" "$value" | tee -a "$evidence" >&2
    return 1
  fi
}

# Usage: pocketshell_android_input_preflight ADB SERIAL EVIDENCE_FILE
pocketshell_android_input_preflight() {
  local adb="$1" serial="$2" evidence="$3"
  local deadline title package value remaining
  mkdir -p "$(dirname -- "$evidence")"
  : > "$evidence" || return 1
  pocketshell_android_recover_stale_launchers "$adb" "$serial" "$evidence" || return 1
  # On the hosted image the SDK setup app (com.google.android.googlesdksetup)
  # can still be the provisioning HOME right after boot. Mark setup complete
  # before disabling HOME providers: SystemUI keeps the notification shade
  # and other system UI locked while the device is unprovisioned.
  "$adb" -s "$serial" shell settings put global device_provisioned 1 >> "$evidence" 2>&1 || true
  "$adb" -s "$serial" shell settings put secure user_setup_complete 1 >> "$evidence" 2>&1 || true
  pocketshell_android_disable_launchers "$adb" "$serial" "$evidence" || return 1
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
  pocketshell_android_raise_long_press_timeout "$adb" "$serial" "$evidence" || return 1

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
  printf 'PASS: Android input preflight on %s (no system error dialog; hide_error_dialogs=1; long_press_timeout=%s)\n' "$serial" "${POCKETSHELL_LANE_LONG_PRESS_TIMEOUT_MS:-3000}"
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
