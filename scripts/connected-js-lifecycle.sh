#!/usr/bin/env bash
# Run the required packaged lifecycle/session-switch journey against live aplexer.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="i2861"
PORT=""
CONTAINER=""
RUN_ID="js2861-$(date -u '+%Y%m%dT%H%M%S')"
PREPARE_ONLY=0
TEST_ONLY=0

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-lifecycle.sh --port PORT --container NAME [options]

Build or run the packaged API 35+ SSH/session/lifecycle journey. The Docker
agents fixture must already be running and healthy; this script independently
checks its live aplexer rows and PTY screens after the emulator run.

Options:
  --port PORT          Docker fixture port as reached from the emulator
  --container NAME     Docker agents fixture container name
  --suffix TOKEN       Debug package suffix (default: i2861)
  --run-id ID          Unique A/B/C tag prefix (default: generated)
  --prepare-only       Build app/androidTest APKs without running an emulator
  --test-only          Run previously prepared APKs
  --help               Show this help
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)
      [[ $# -ge 2 ]] || fail '--port needs a value'
      PORT="$2"
      shift 2
      ;;
    --container)
      [[ $# -ge 2 ]] || fail '--container needs a value'
      CONTAINER="$2"
      shift 2
      ;;
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
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$ ]] || fail 'run ID must be 3-39 tag-safe characters'
[[ "$PORT" =~ ^[0-9]{1,5}$ ]] && (( PORT >= 1 && PORT <= 65535 )) || fail '--port must be an integer between 1 and 65535'
[[ "$CONTAINER" =~ ^[A-Za-z0-9_.-]+$ ]] || fail '--container must be a Docker container name'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
[[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'generated android/gradlew is missing'
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] || fail 'committed Docker test key is missing'
[[ -x "$ADB" ]] || fail "adb is not executable: $ADB"
[[ -x "$ROOT_DIR/scripts/check-js-lifecycle-results.py" ]] || fail 'lifecycle result verifier is missing'
[[ -x "$ROOT_DIR/scripts/check-js-lifecycle-host-evidence.py" ]] || fail 'independent host evidence verifier is missing'
[[ -x "$ROOT_DIR/scripts/watch-js-lifecycle-host-connections.py" ]] || fail 'Docker SSH socket watcher is missing'
command -v tesseract >/dev/null 2>&1 || fail 'Tesseract OCR is required to prove the screenshot contains the current terminal marker'
printf 'Using screenshot OCR engine: %s\n' "$(tesseract --version | head -n1)"
command -v convert >/dev/null 2>&1 || fail 'ImageMagick convert is required to crop the measured terminal marker before OCR'
"$ROOT_DIR/scripts/check-js-lifecycle-host-evidence.py" --self-test
"$ROOT_DIR/scripts/test-js-lifecycle-cleanup.sh"
[[ -x "$ROOT_DIR/scripts/extract-js-lifecycle-artifacts.py" ]] || fail 'lifecycle artifact extractor is missing'

  "$ROOT_DIR/scripts/check-js-lifecycle-results.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/js-lifecycle-cleanup.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-lifecycle.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-lifecycle.sh suffix=$SUFFIX run=$RUN_ID"

if [[ "$TEST_ONLY" != 1 ]]; then
  command -v pnpm >/dev/null 2>&1 || fail 'pnpm is required to build the packaged web assets'
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi

if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: packaged lifecycle APKs prepared for suffix %s\n' "$SUFFIX"
  exit 0
fi

docker inspect "$CONTAINER" >/dev/null 2>&1 || fail "Docker fixture container is missing: $CONTAINER"
fixture_health="$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)"
[[ "$fixture_health" == healthy ]] || fail "Docker fixture must be healthy; $CONTAINER reports ${fixture_health:-no health status}"

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online_emulators < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online_emulators[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online_emulators[@]}"
  export ANDROID_SERIAL="${online_emulators[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] \
  || fail "selected Android device is not online: $ANDROID_SERIAL"
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) \
  || fail "lifecycle journey requires API 35+; $ANDROID_SERIAL reports API ${device_api:-unknown}"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
ARTIFACTS_DIR="$ROOT_DIR/android/app/build/outputs/js-lifecycle/$RUN_ID"
if [[ -e "$ARTIFACTS_DIR" ]]; then
  fail "refusing to overwrite existing same-run evidence: $ARTIFACTS_DIR"
fi
mkdir -p "$ARTIFACTS_DIR"
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

ssh_key_base64="$(base64 -w0 "$ROOT_DIR/tests/docker/test_key")"
printf 'Running packaged JS lifecycle journey on %s (API %s), fixture %s:%s, run %s\n' \
  "$ANDROID_SERIAL" "$device_api" "$CONTAINER" "$PORT" "$RUN_ID"

record_host_timebase() {
  local phase="$1"
  python3 - "$ADB" "$ANDROID_SERIAL" "$ARTIFACTS_DIR/host-time-offset.json" "$phase" <<'PY'
import json
import subprocess
import sys
import time
from pathlib import Path

adb, serial, output_name, phase = sys.argv[1:]
before = time.time_ns() // 1_000_000
device = subprocess.run(
    [adb, "-s", serial, "shell", "date", "+%s%3N"], check=True, text=True,
    capture_output=True, timeout=10,
)
after = time.time_ns() // 1_000_000
device_epoch = int(device.stdout.strip())
entry = {
    "hostBeforeEpochMs": before,
    "hostAfterEpochMs": after,
    "deviceEpochMs": device_epoch,
    "offsetMs": round((before + after) / 2) - device_epoch,
}
path = Path(output_name)
value = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {"schema": 1}
if value.get("schema") != 1:
    raise SystemExit("host/device timebase has an unsupported schema")
value[phase] = entry
path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"Recorded host/device epoch offset ({phase}): {entry['offsetMs']} ms")
PY
}

HOST_SOCKET_WATCHER_PID=""
LIVE_ASSET_LOGCAT_PID=""
stop_host_socket_watcher() {
  if [[ -n "$HOST_SOCKET_WATCHER_PID" ]]; then
    kill "$HOST_SOCKET_WATCHER_PID" 2>/dev/null || true
    wait "$HOST_SOCKET_WATCHER_PID" 2>/dev/null || true
    HOST_SOCKET_WATCHER_PID=""
  fi
  if [[ -n "$LIVE_ASSET_LOGCAT_PID" ]]; then
    kill "$LIVE_ASSET_LOGCAT_PID" 2>/dev/null || true
    wait "$LIVE_ASSET_LOGCAT_PID" 2>/dev/null || true
    LIVE_ASSET_LOGCAT_PID=""
  fi
}
pocketshell_install_js_lifecycle_cleanup_trap
record_host_timebase before || fail 'could not capture the host/device clock offset before the packaged journey'
LIVE_ASSET_LOGCAT="$ARTIFACTS_DIR/lifecycle-assets-live-logcat.txt"
: > "$LIVE_ASSET_LOGCAT" || fail 'could not create the live artifact logcat file'
[[ "$LIVE_ASSET_LOGCAT" != "$RESULTS_DIR/"* ]] || fail 'live artifact logcat must survive Gradle result cleanup'
printf 'Capturing artifact logcat live outside Gradle cleanup: %s\n' "$LIVE_ASSET_LOGCAT"
"$ADB" -s "$ANDROID_SERIAL" logcat -c
"$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime -s SshPtyDockerJourney PocketshellJourneyAsset \
  > "$LIVE_ASSET_LOGCAT" 2>&1 &
LIVE_ASSET_LOGCAT_PID=$!
sleep 0.2
kill -0 "$LIVE_ASSET_LOGCAT_PID" 2>/dev/null || fail 'could not start the live artifact logcat collector'
python3 "$ROOT_DIR/scripts/watch-js-lifecycle-host-connections.py" \
  --container "$CONTAINER" --output "$ARTIFACTS_DIR/host-ssh-connections.jsonl" &
HOST_SOCKET_WATCHER_PID=$!
sleep 1
kill -0 "$HOST_SOCKET_WATCHER_PID" 2>/dev/null || fail 'Docker SSH socket watcher exited before the packaged journey'
if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.SshPtyDockerJourneyTest \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyBase64=$ssh_key_base64" \
    "-Pandroid.testInstrumentationRunnerArguments.sshSessionName=$RUN_ID" \
    --stacktrace --console=plain 2>&1 | tee "$ARTIFACTS_DIR/gradle-connected.log"; then
  record_host_timebase after || fail 'could not capture the host/device clock offset after the packaged journey'
  stop_host_socket_watcher
else
  test_exit_code=$?
  record_host_timebase after || true
  stop_host_socket_watcher
  printf 'Packaged lifecycle journey failed; collecting emulator and fixture evidence.\n' >&2
  mkdir -p "$ARTIFACTS_DIR/failure-diagnostics"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -s SshPtyDockerJourney PocketshellJourneyAsset \
    > "$ARTIFACTS_DIR/failure-diagnostics/lifecycle-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p \
    > "$ARTIFACTS_DIR/failure-diagnostics/device-screen.png" 2>&1 || true
  docker logs --timestamps "$CONTAINER" \
    > "$ARTIFACTS_DIR/failure-diagnostics/docker-agents.log" 2>&1 || true
  exit "$test_exit_code"
fi

cp -a "$RESULTS_DIR" "$ARTIFACTS_DIR/instrumentation-results"
"$ROOT_DIR/scripts/check-js-lifecycle-results.py" --results-dir "$RESULTS_DIR"
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -s SshPtyDockerJourney PocketshellJourneyAsset \
  > "$ARTIFACTS_DIR/lifecycle-logcat.txt"
"$ROOT_DIR/scripts/extract-js-lifecycle-artifacts.py" \
  --run-id "$RUN_ID" --logcat "$LIVE_ASSET_LOGCAT" \
  --output-dir "$ARTIFACTS_DIR/$RUN_ID"
docker inspect "$CONTAINER" > "$ARTIFACTS_DIR/docker-agents-inspect.json"
docker logs --timestamps "$CONTAINER" > "$ARTIFACTS_DIR/docker-agents.log" 2>&1 || true
"$ROOT_DIR/scripts/check-js-lifecycle-host-evidence.py" \
  --run-id "$RUN_ID" --container "$CONTAINER" --artifact-directory "$ARTIFACTS_DIR/$RUN_ID" \
  --host-connections "$ARTIFACTS_DIR/host-ssh-connections.jsonl" \
  --timebase "$ARTIFACTS_DIR/host-time-offset.json"
printf 'PASS: complete packaged lifecycle and A→B→C→A evidence is in %s\n' "$ARTIFACTS_DIR"
