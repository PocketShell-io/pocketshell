#!/usr/bin/env bash
# Issue #2993: prove user-data writes survive a process kill that lands right
# after the UI acknowledged them. Three host-owned instrumentation phases:
# seed (then an external force-stop), mutate (which SIGKILLs its own process
# --kill-delay-ms after logging the acknowledgement) and verify. The measured
# ACK->KILL gap must stay below one second, so a slow kill cannot let a
# lazily-flushing store pass vacuously.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="i2993durable"
RUN_ID="js2993-$(date +%s)"
PREPARE_ONLY=0
TEST_ONLY=0
KILL_DELAY_MS=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-durable-storage.sh [--suffix TOKEN] [--run-id ID]
       [--kill-delay-ms 0-500] [--prepare-only|--test-only]

Builds (unless --test-only) and runs the packaged durable-storage restart
journey: seed -> force-stop -> mutate -> SIGKILL --kill-delay-ms (default 0)
after the UI acknowledged the writes -> verify. The measured ACK->KILL gap
must stay below one second.
Needs no Docker fixture. Holds the shared Gradle-output and per-serial AVD
locks; selects ANDROID_SERIAL or the only online API 35+ emulator.

Evidence: android/app/build/outputs/js-durable-storage/<run-id>/
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix)
      [[ $# -ge 2 ]] || fail '--suffix needs a value'
      SUFFIX="$2"
      shift 2
      ;;
    --run-id)
      [[ $# -ge 2 ]] || fail '--run-id needs a value'
      RUN_ID="$2"
      shift 2
      ;;
    --kill-delay-ms)
      [[ $# -ge 2 ]] || fail '--kill-delay-ms needs a value'
      KILL_DELAY_MS="$2"
      shift 2
      ;;
    --prepare-only)
      PREPARE_ONLY=1
      shift
      ;;
    --test-only)
      TEST_ONLY=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail '--suffix must match [A-Za-z0-9._]+'
[[ "$RUN_ID" =~ ^[A-Za-z0-9-]{6,64}$ ]] || fail '--run-id must be 6-64 letters, digits, or dashes'
[[ "$KILL_DELAY_MS" =~ ^[0-9]{1,3}$ ]] && (( KILL_DELAY_MS <= 500 )) || fail '--kill-delay-ms must be 0-500'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
[[ -x "$ADB" || "$PREPARE_ONLY" == 1 ]] || fail "adb is missing or not executable: $ADB"

"$ROOT_DIR/scripts/check-js-durable-storage-results.py" --self-test >/dev/null
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-durable-storage.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-durable-storage.sh suffix=$SUFFIX"

if [[ "$TEST_ONLY" != 1 ]]; then
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi
if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: packaged durable-storage APKs prepared for suffix %s\n' "$SUFFIX"
  exit 0
fi

evidence_dir="$ROOT_DIR/android/app/build/outputs/js-durable-storage/$RUN_ID"
python3 - "$evidence_dir" <<'PY'
from pathlib import Path
import shutil
import sys

path = Path(sys.argv[1])
if path.exists():
    shutil.rmtree(path)
path.mkdir(parents=True)
PY

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online_emulators < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online_emulators[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online_emulators[@]}"
  export ANDROID_SERIAL="${online_emulators[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] \
  || fail "selected Android device is not online: $ANDROID_SERIAL"
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) \
  || fail "the durable-storage journey runs on API 35+; device reports ${device_api:-unknown}"
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

test_class='com.pocketshell.app.smoke.DurableStorageRestartJourneyTest'
APP_PACKAGE="com.pocketshell.app.$SUFFIX"
TEST_PACKAGE="$APP_PACKAGE.test"
INSTRUMENTATION_COMPONENT="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
APP_APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" ]] || fail "packaged app APK is missing: $APP_APK"
[[ -f "$TEST_APK" ]] || fail "packaged AndroidTest APK is missing: $TEST_APK"
# Other lanes build into the same output path with their own suffix; refuse to
# install (and test) an APK that belongs to a different package.
AAPT="$(find "$ANDROID_SDK/build-tools" -mindepth 2 -maxdepth 2 -name aapt -type f 2>/dev/null | sort -V | tail -n 1)"
[[ -x "$AAPT" ]] || fail "aapt is missing under $ANDROID_SDK/build-tools"
apk_package() {
  "$AAPT" dump badging "$1" 2>/dev/null | sed -n "s/^package: name='\([^']*\)'.*/\1/p"
}
[[ "$(apk_package "$APP_APK")" == "$APP_PACKAGE" ]] \
  || fail "$APP_APK is not the $APP_PACKAGE build; run without --test-only"
[[ "$(apk_package "$TEST_APK")" == "$TEST_PACKAGE" ]] \
  || fail "$TEST_APK is not the $TEST_PACKAGE build; run without --test-only"
"$ADB" -s "$ANDROID_SERIAL" install -r "$APP_APK" > "$evidence_dir/install-app.log" 2>&1 \
  || fail "could not install $APP_APK; see $evidence_dir/install-app.log"
"$ADB" -s "$ANDROID_SERIAL" install -r "$TEST_APK" > "$evidence_dir/install-android-test.log" 2>&1 \
  || fail "could not install $TEST_APK; see $evidence_dir/install-android-test.log"
"$ADB" -s "$ANDROID_SERIAL" shell cmd package wait-for-handler --timeout 60000
installed_runner="$("$ADB" -s "$ANDROID_SERIAL" shell pm list instrumentation | tr -d '\r')"
[[ "$installed_runner" == *"$INSTRUMENTATION_COMPONENT"* ]] \
  || fail "the installed instrumentation runner is missing: $INSTRUMENTATION_COMPONENT"

# Run-unique tag: a fresh host identity and chip labels per run, so state left
# by an earlier run on the same install can never satisfy this one.
durable_tag="$(printf '%s' "$RUN_ID" | tr -cd 'A-Za-z0-9' | tail -c 12)$(printf '%04x' $((RANDOM % 65536)))"
cat > "$evidence_dir/run-metadata.txt" <<EOF
run_id=$RUN_ID
durable_tag=$durable_tag
app_package=$APP_PACKAGE
android_serial=$ANDROID_SERIAL
android_api=$device_api
started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF

finish_durable_run() {
  local exit_status=$?
  set +e
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -s PS2993Durable:I DurableStorage:V \
    > "$evidence_dir/durable-logcat.txt" 2>&1
  printf 'exit_code=%s\nfinished_utc=%s\n' "$exit_status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    >> "$evidence_dir/run-metadata.txt"
  pocketshell_release_all
  exit "$exit_status"
}
trap finish_durable_run EXIT

run_instrumentation_phase() {
  local phase="$1"
  local phase_dir="$evidence_dir/phase-$phase"
  local phase_args=(-e class "$test_class" -e durablePhase "$phase" -e durableTag "$durable_tag"
    -e durableKillDelayMs "$KILL_DELAY_MS")
  mkdir -p "$phase_dir"
  local instrumentation_status=0
  "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r "${phase_args[@]}" \
    "$INSTRUMENTATION_COMPONENT" > "$phase_dir/instrumentation.log" 2>&1 \
    || instrumentation_status=$?
  # Whatever the phase outcome, the next phase must start a new process.
  "$ADB" -s "$ANDROID_SERIAL" shell \
    "p=\$(pidof $APP_PACKAGE); if [ -n \"\$p\" ]; then am force-stop $APP_PACKAGE; fi; q=\$(pidof $APP_PACKAGE); echo \"pid_before_stop=\$p\"; echo \"pid_after_stop=\$q\"" \
    | tr -d '\r' > "$phase_dir/stop.txt"
  cat "$phase_dir/instrumentation.log"
  [[ -z "$(sed -n 's/^pid_after_stop=//p' "$phase_dir/stop.txt")" ]] \
    || fail "force-stop did not stop $APP_PACKAGE after phase $phase"
  (( instrumentation_status == 0 )) || fail "adb am instrument exited $instrumentation_status in phase $phase"

  if [[ "$phase" == mutate ]]; then
    # The mutate phase ends in its own SIGKILL, so its instrumentation result
    # is the platform's crash report, never a JUnit pass. It counts only with
    # the ACK -> KILL pair in logcat and no assertion failure before the kill.
    if grep -q '^INSTRUMENTATION_STATUS_CODE: -[1-4]' "$phase_dir/instrumentation.log"; then
      fail "mutate phase failed an assertion before reaching its kill"
    fi
    grep -q 'Process crashed' "$phase_dir/instrumentation.log" \
      || fail "mutate phase did not end in the expected process kill"
    "$ADB" -s "$ANDROID_SERIAL" logcat -d -v raw -s PS2993Durable:I | tr -d '\r' \
      | grep -F "$durable_tag" > "$phase_dir/ack-kill-logcat.txt" || true
    local ack_epoch_ms killed_epoch_ms
    ack_epoch_ms="$(grep '^ACK|' "$phase_dir/ack-kill-logcat.txt" | tail -n 1 | awk -F'|' '{print $2}')"
    killed_epoch_ms="$(grep '^KILL|' "$phase_dir/ack-kill-logcat.txt" | tail -n 1 | awk -F'|' '{print $2}')"
    [[ "$ack_epoch_ms" =~ ^[0-9]{13}$ && "$killed_epoch_ms" =~ ^[0-9]{13}$ ]] \
      || fail "mutate phase ACK/KILL timestamps are missing from logcat; see $phase_dir/ack-kill-logcat.txt"
    local process_after_kill=absent
    [[ -z "$(sed -n 's/^pid_before_stop=//p' "$phase_dir/stop.txt")" ]] || process_after_kill=present
    printf 'ack_epoch_ms=%s\nkilled_epoch_ms=%s\nprocess_after_kill=%s\ngap_ms=%s\n' \
      "$ack_epoch_ms" "$killed_epoch_ms" "$process_after_kill" "$((killed_epoch_ms - ack_epoch_ms))" \
      | tee "$evidence_dir/kill-gap.txt"
    return 0
  fi

  grep -q '^INSTRUMENTATION_CODE: -1' "$phase_dir/instrumentation.log" \
    || fail "instrumentation phase $phase did not finish cleanly"
  "$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh" \
    --log "$phase_dir/instrumentation.log" \
    --out "$phase_dir/TEST-durable-storage.xml" \
    --suite "$test_class/$phase" \
    || fail "instrumentation phase $phase produced no testcase for $test_class"
  "$ROOT_DIR/scripts/check-js-durable-storage-results.py" --results-dir "$phase_dir" \
    || fail "durable-storage phase $phase failed its exact result gate"
}

printf 'Running packaged durable-storage restart journey on %s (API %s), tag %s, kill %s ms after ACK\n' \
  "$ANDROID_SERIAL" "$device_api" "$durable_tag" "$KILL_DELAY_MS"
run_instrumentation_phase seed
run_instrumentation_phase mutate
run_instrumentation_phase verify
"$ROOT_DIR/scripts/check-js-durable-storage-results.py" --run-dir "$evidence_dir"
printf 'Evidence directory: %s\n' "$evidence_dir"
