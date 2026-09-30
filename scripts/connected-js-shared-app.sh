#!/usr/bin/env bash
# Run the shared-app journey (#2936): the default launch mounts the shared
# PocketShell app over Android's PocketShellApi, and it must list, attach and
# type into a session on one Docker agents fixture (--port, an isolated
# scripts/agents-pool.sh lane). Shares the machine-wide AVD and Android
# output-tree locks with the other packaged JS lanes.

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
PNPM="${PNPM:-pnpm}"
SUFFIX="i2936shared"
PORT=""
PREPARE_ONLY=0
TEST_ONLY=0

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-shared-app.sh --port PORT [--suffix TOKEN] [--prepare-only|--test-only]

Build and run the shared-app packaged journey (#2936).
The default runs both preparation and connected tests. CI can prepare before
booting an emulator, then run with --test-only inside its emulator step.

Options:
  --port PORT      Host port of a healthy agents fixture (e.g. 2243)
  --suffix TOKEN   Isolate the debug package (default: i2936shared)
  --prepare-only   Build the suffixed app and androidTest APKs, without an AVD
  --test-only      Run the already-prepared APKs on one API 35+ emulator
  --help           Show this help

The connected phase selects ANDROID_SERIAL when supplied, otherwise it
requires exactly one online emulator. Both Gradle output and AVD mutations are
protected by the shared PocketShell locks.
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix)
      [[ $# -ge 2 ]] || fail '--suffix needs a token'
      SUFFIX="$2"
      shift 2
      ;;
    --port)
      [[ $# -ge 2 ]] || fail '--port needs a value'
      PORT="$2"
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
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail "suffix must match [A-Za-z0-9._]+ (got: $SUFFIX)"
[[ "$PREPARE_ONLY" == 1 || "$PORT" =~ ^[0-9]+$ ]] || fail '--port PORT is required to run the journey'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
[[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'generated android/gradlew is missing; initialize the JS-first Android project first'
[[ -x "$ROOT_DIR/scripts/check-js-shared-app-results.py" ]] || fail 'shared-app result verifier is missing'
"$ROOT_DIR/scripts/check-js-shared-app-results.py" --self-test

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-shared-app.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-shared-app.sh suffix=$SUFFIX"

if [[ "$TEST_ONLY" != 1 ]]; then
  command -v "$PNPM" >/dev/null 2>&1 || fail 'pnpm is required to build the packaged web assets'
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi

if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: packaged JS app and androidTest APKs prepared for suffix %s\n' "$SUFFIX"
  exit 0
fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online_emulators < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online_emulators[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online_emulators[@]}"
  export ANDROID_SERIAL="${online_emulators[0]}"
fi

[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] \
  || fail "selected Android device is not online: $ANDROID_SERIAL"
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) \
  || fail "safe-area instrumentation requires API 35+; $ANDROID_SERIAL reports API ${device_api:-unknown}"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

printf 'Running shared-app journey on %s (API %s), suffix %s\n' "$ANDROID_SERIAL" "$device_api" "$SUFFIX"
if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.SharedAppDockerJourneyTest \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyBase64=$(base64 -w0 "$ROOT_DIR/tests/docker/test_key")" \
    --stacktrace --console=plain; then
  :
else
  test_exit_code=$?
  printf 'Shared-app journey failed; capturing emulator diagnostics.\n' >&2
  mkdir -p "$RESULTS_DIR"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 4000 \
    > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method \
    > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys window \
    > "$RESULTS_DIR/diagnostics-window.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p \
    > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  exit "$test_exit_code"
fi
SHARED_APP_RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/js-shared-app-results"
"$ROOT_DIR/scripts/check-js-shared-app-results.py" --results-dir "$RESULTS_DIR" \
  --evidence-dir "$SHARED_APP_RESULTS_DIR"
