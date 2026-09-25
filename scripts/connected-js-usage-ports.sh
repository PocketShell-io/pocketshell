#!/usr/bin/env bash
# Run the registered packaged API 35+ usage and forwarding journey on Docker.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="i2859"
PORT=""
CONTAINER=""
RUN_ID="js2859-$(date -u '+%Y%m%dT%H%M%S')"
PREPARE_ONLY=0
TEST_ONLY=0

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-usage-ports.sh --port PORT --container NAME [options]

Run the packaged JS usage/ports journey against the healthy Docker agents
fixture on an API 35+ emulator. The fixture image must contain this checkout's
tests/docker/agent-fixtures/pocketshell-usage.ndjson.

Options:
  --port PORT          Docker fixture port as reached from the emulator
  --container NAME     Docker agents fixture container name
  --suffix TOKEN       Debug package suffix (default: i2859)
  --run-id ID          Unique artifact and host session prefix
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
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
[[ -x "$ROOT_DIR/scripts/check-js-usage-ports-results.py" ]] || fail 'exact usage/ports JUnit checker is missing'
[[ -x "$ROOT_DIR/scripts/check-js-usage-ports-host-evidence.py" ]] || fail 'usage/ports host evidence checker is missing'
[[ -x "$ROOT_DIR/scripts/extract-js-lifecycle-artifacts.py" ]] || fail 'same-run artifact extractor is missing'

"$ROOT_DIR/scripts/check-js-usage-ports-results.py" --self-test
"$ROOT_DIR/scripts/check-js-usage-ports-host-evidence.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-usage-ports.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-usage-ports.sh suffix=$SUFFIX run=$RUN_ID"

APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ "$TEST_ONLY" != 1 ]]; then
  command -v pnpm >/dev/null 2>&1 || fail 'pnpm is required to build the packaged web assets'
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi
[[ -s "$APK" ]] || fail "debug APK is missing: $APK"
[[ -s "$TEST_APK" ]] || fail "androidTest APK is missing: $TEST_APK"

if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: packaged usage/ports APKs prepared for suffix %s\n' "$SUFFIX"
  exit 0
fi

docker inspect "$CONTAINER" >/dev/null 2>&1 || fail "Docker fixture container is missing: $CONTAINER"
fixture_health="$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)"
[[ "$fixture_health" == healthy ]] || fail "Docker fixture must be healthy; $CONTAINER reports ${fixture_health:-no health status}"
docker exec "$CONTAINER" sh -lc 'command -v ss >/dev/null && ss -tln >/dev/null' \
  || fail "Docker fixture must provide ss for meaningful listener discovery: $CONTAINER"
source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
expected_fixture_version="$(pocketshell_apk_version_name "$APK" 2>/dev/null || true)"
[[ -n "$expected_fixture_version" ]] || fail 'could not read the installed app version from the packaged debug APK'
container_fixture_version="$(docker exec "$CONTAINER" cat /opt/pocketshell-agent-fixture-version 2>/dev/null || true)"
[[ "$container_fixture_version" == "$expected_fixture_version" ]] \
  || fail "Docker fixture version mismatch: APK=$expected_fixture_version container=${container_fixture_version:-missing}; rebuild via scripts/agents-pool.sh"
expected_usage_hash="$(sha256sum "$ROOT_DIR/tests/docker/agent-fixtures/pocketshell-usage.ndjson" | awk '{print $1}')"
container_usage_hash="$(docker exec "$CONTAINER" sha256sum /opt/pocketshell-agent-fixtures/pocketshell-usage.ndjson 2>/dev/null | awk '{print $1}')"
[[ "$container_usage_hash" == "$expected_usage_hash" ]] || fail 'Docker agents image has a stale usage fixture; rebuild/recreate it from this checkout before running'
printf 'Verified Docker fixture version %s and usage fixture SHA-256 %s\n' "$container_fixture_version" "$container_usage_hash"

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online_emulators < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online_emulators[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online_emulators[@]}"
  export ANDROID_SERIAL="${online_emulators[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] \
  || fail "selected Android device is not online: $ANDROID_SERIAL"
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) \
  || fail "usage/ports journey requires API 35+; $ANDROID_SERIAL reports API ${device_api:-unknown}"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
ARTIFACTS_DIR="$ROOT_DIR/android/app/build/outputs/js-usage-ports/$RUN_ID"
[[ ! -e "$ARTIFACTS_DIR" ]] || fail "refusing to overwrite existing same-run evidence: $ARTIFACTS_DIR"
mkdir -p "$ARTIFACTS_DIR"
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

HOST_SERVER_PID_PATH="/tmp/pocketshell-$RUN_ID-usage-ports.pid"
HOST_SERVER_STOP_CHECK_PATH="/tmp/pocketshell-$RUN_ID-usage-ports.stop-check"
LIVE_ASSET_LOGCAT_PID=""
cleanup_remote_fixture() {
  docker exec -u testuser "$CONTAINER" /bin/sh -c \
    'pid=$(cat "$1" 2>/dev/null || true); case "$pid" in ""|*[!0-9]*) exit 0;; esac; test -r "/proc/$pid/cmdline" || exit 0; args=$(tr "\000" " " < "/proc/$pid/cmdline" 2>/dev/null || true); case "$args" in *"python3 -m http.server "*" --bind 127.0.0.1"*) kill "$pid" 2>/dev/null || true;; esac' \
    usage-ports-cleanup "$HOST_SERVER_PID_PATH" >/dev/null 2>&1 || true
  local session_id=""
  if [[ -s "$ARTIFACTS_DIR/$RUN_ID/journey-summary.json" ]]; then
    session_id="$(python3 - "$ARTIFACTS_DIR/$RUN_ID/journey-summary.json" <<'PY'
import json
import sys
from pathlib import Path

try:
    value = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
except (OSError, json.JSONDecodeError):
    value = {}
session_id = value.get("sessionId", "") if isinstance(value, dict) else ""
print(session_id if isinstance(session_id, str) and len(session_id) == 36 else "")
PY
    )"
  else
    local snapshot=""
    snapshot="$(docker exec -u testuser -e HOME=/home/testuser "$CONTAINER" /usr/bin/a snapshot --json 2>/dev/null || true)"
    session_id="$(python3 - "$snapshot" "$RUN_ID-usage" <<'PY'
import json
import sys

try:
    rows = json.loads(sys.argv[1])
except json.JSONDecodeError:
    rows = []
tag = sys.argv[2]
matches = [row.get("id", "") for row in rows if isinstance(row, dict) and row.get("tag") == tag]
print(matches[0] if len(matches) == 1 else "")
PY
    )"
  fi
  if [[ "$session_id" =~ ^[a-f0-9-]{36}$ ]]; then
    docker exec -u testuser -e HOME=/home/testuser "$CONTAINER" /usr/bin/a kill "$session_id" \
      >/dev/null 2>&1 || true
  fi
}

release_usage_ports_locks() {
  if [[ -n "$LIVE_ASSET_LOGCAT_PID" ]]; then
    kill "$LIVE_ASSET_LOGCAT_PID" 2>/dev/null || true
    wait "$LIVE_ASSET_LOGCAT_PID" 2>/dev/null || true
    LIVE_ASSET_LOGCAT_PID=""
  fi
  cleanup_remote_fixture
  if declare -F pocketshell_release_all >/dev/null 2>&1; then pocketshell_release_all; fi
}
trap release_usage_ports_locks EXIT

ssh_key_base64="$(base64 -w0 "$ROOT_DIR/tests/docker/test_key")"
printf 'Running packaged usage/ports journey on %s (API %s), fixture %s:%s, run %s\n' \
  "$ANDROID_SERIAL" "$device_api" "$CONTAINER" "$PORT" "$RUN_ID"
"$ADB" -s "$ANDROID_SERIAL" logcat -c
LIVE_ASSET_LOGCAT="$ARTIFACTS_DIR/usage-ports-assets-live-logcat.txt"
: > "$LIVE_ASSET_LOGCAT"
"$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime -s UsagePortsDockerJourney PocketshellJourneyAsset \
  > "$LIVE_ASSET_LOGCAT" 2>&1 &
LIVE_ASSET_LOGCAT_PID=$!
sleep 0.2
kill -0 "$LIVE_ASSET_LOGCAT_PID" 2>/dev/null || fail 'could not start the same-run artifact logcat collector'

if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.UsagePortsDockerJourneyTest#usageAndPortForwardingPoliciesUseDockerAndNativePlugin \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyBase64=$ssh_key_base64" \
    "-Pandroid.testInstrumentationRunnerArguments.sshSessionName=$RUN_ID" \
    --stacktrace --console=plain 2>&1 | tee "$ARTIFACTS_DIR/gradle-connected.log"; then
  :
else
  test_exit_code=$?
  mkdir -p "$ARTIFACTS_DIR/failure-diagnostics"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -s UsagePortsDockerJourney PocketshellJourneyAsset \
    > "$ARTIFACTS_DIR/failure-diagnostics/usage-ports-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p \
    > "$ARTIFACTS_DIR/failure-diagnostics/device-screen.png" 2>&1 || true
  docker logs --timestamps "$CONTAINER" \
    > "$ARTIFACTS_DIR/failure-diagnostics/docker-agents.log" 2>&1 || true
  docker exec -u testuser "$CONTAINER" /bin/sh -c \
    'test -f "$1" && cat "$1"' usage-ports-stop-check "$HOST_SERVER_STOP_CHECK_PATH" \
    > "$ARTIFACTS_DIR/failure-diagnostics/http-stop-process-check.txt" 2>/dev/null || true
  exit "$test_exit_code"
fi

cp -a "$RESULTS_DIR" "$ARTIFACTS_DIR/instrumentation-results"
"$ROOT_DIR/scripts/check-js-usage-ports-results.py" --results-dir "$RESULTS_DIR"
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -s UsagePortsDockerJourney PocketshellJourneyAsset \
  > "$ARTIFACTS_DIR/usage-ports-logcat.txt"
"$ROOT_DIR/scripts/extract-js-lifecycle-artifacts.py" \
  --run-id "$RUN_ID" --logcat "$LIVE_ASSET_LOGCAT" --output-dir "$ARTIFACTS_DIR/$RUN_ID"
docker exec -u testuser "$CONTAINER" /bin/sh -c \
  'test -f "$1" && cat "$1"' usage-ports-stop-check "$HOST_SERVER_STOP_CHECK_PATH" \
  > "$ARTIFACTS_DIR/$RUN_ID/host-http-stop-process-check.txt"
docker inspect "$CONTAINER" > "$ARTIFACTS_DIR/docker-agents-inspect.json"
docker logs --timestamps "$CONTAINER" > "$ARTIFACTS_DIR/docker-agents.log" 2>&1 || true
"$ROOT_DIR/scripts/check-js-usage-ports-host-evidence.py" \
  --run-id "$RUN_ID" --container "$CONTAINER" --artifact-directory "$ARTIFACTS_DIR/$RUN_ID"
printf 'PASS: complete packaged usage/ports evidence is in %s\n' "$ARTIFACTS_DIR"
