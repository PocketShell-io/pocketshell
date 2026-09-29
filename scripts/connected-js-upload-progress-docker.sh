#!/usr/bin/env bash
# Run the packaged J20 upload-progress journey through an isolated Toxiproxy lane.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="$(printenv ANDROID_SDK || printenv ANDROID_SDK_ROOT || printenv ANDROID_HOME || printf '/home/alexey/Android/Sdk')"
ADB="$(printenv ADB || printf '%s/platform-tools/adb' "$ANDROID_SDK")"
AGENTS_PORT=""
ARTIFACT_RUN_ID=""
SUFFIX="i2929"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-upload-progress-docker.sh --agents-port 2243|2244|2245 --artifact-run-id ID [--suffix TOKEN]

Builds and runs the packaged J20 composer upload-progress journey against an
already healthy isolated agents + Toxiproxy fixture lane. It requires an API
35+ emulator, checks the exact J20 JUnit method, pulls both keyboard-open
screenshots, then independently verifies and removes the three host payloads.
It does not create or tear down Docker fixture containers.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --agents-port)
      [[ $# -ge 2 ]] || fail '--agents-port needs a value'
      AGENTS_PORT="$2"
      shift 2
      ;;
    --artifact-run-id)
      [[ $# -ge 2 ]] || fail '--artifact-run-id needs a value'
      ARTIFACT_RUN_ID="$2"
      shift 2
      ;;
    --suffix)
      [[ $# -ge 2 ]] || fail '--suffix needs a value'
      SUFFIX="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$AGENTS_PORT" =~ ^(2243|2244|2245)$ ]] || fail '--agents-port must be one of the isolated pool ports 2243, 2244, or 2245'
[[ "$ARTIFACT_RUN_ID" =~ ^[A-Za-z0-9_-]{4,48}$ ]] || fail '--artifact-run-id must be 4-48 safe characters'
[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail '--suffix must match [A-Za-z0-9._]+'
PROXY_PORT=$((AGENTS_PORT + 10))
TOXIPROXY_API_PORT=$((8474 + AGENTS_PORT - 2222))
[[ -x "$ADB" ]] || fail "adb is missing or not executable: $ADB"
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] || fail 'Docker fixture test key is missing'

"$ROOT_DIR/scripts/check-js-upload-progress-results.py" --self-test
"$ROOT_DIR/scripts/verify-j20-upload-host-oracle.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/agents-pool.sh"

CONTAINER="$(pocketshell_agents_container_for_port "$AGENTS_PORT")"
PROXY_CONTAINER="$(pocketshell_network_fault_container_for_port "$AGENTS_PORT")"
health="$(docker inspect --format='{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)"
[[ "$health" == healthy ]] || fail "agents lane $AGENTS_PORT must already be healthy; $CONTAINER reports ${health:-missing}"
proxy_running="$(docker inspect --format='{{.State.Running}}' "$PROXY_CONTAINER" 2>/dev/null || true)"
[[ "$proxy_running" == true ]] || fail "Toxiproxy lane for agents $AGENTS_PORT must already be running; $PROXY_CONTAINER reports ${proxy_running:-missing}"
curl -fsS "http://127.0.0.1:$TOXIPROXY_API_PORT/proxies" >/dev/null \
  || fail "Toxiproxy API is unavailable on host port $TOXIPROXY_API_PORT"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-upload-progress-docker.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-upload-progress-docker.sh suffix=$SUFFIX agentsPort=$AGENTS_PORT"

EVIDENCE_DIR="$ROOT_DIR/android/app/build/outputs/js-upload-progress/$ARTIFACT_RUN_ID"
python3 - "$EVIDENCE_DIR" <<'PY'
from pathlib import Path
import sys

directory = Path(sys.argv[1])
if directory.exists() and any(directory.iterdir()):
    raise SystemExit(f"FAIL: run-scoped evidence directory is not empty: {directory}")
directory.mkdir(parents=True, exist_ok=True)
PY

KEY_COPY="$EVIDENCE_DIR/fixture-test-key"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$KEY_COPY"
source_key_hash="$(sha256sum "$ROOT_DIR/tests/docker/test_key" | awk '{print $1}')"
copy_key_hash="$(sha256sum "$KEY_COPY" | awk '{print $1}')"
[[ "$source_key_hash" == "$copy_key_hash" ]] || fail 'fixture key copy differs from the committed test key'
ssh_opts=(-i "$KEY_COPY" -p "$AGENTS_PORT" -o BatchMode=yes -o ConnectTimeout=5
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 \
  'command -v a >/dev/null && command -v aplexer >/dev/null && command -v pocketshell >/dev/null' \
  || fail "agents lane $AGENTS_PORT does not authenticate with the committed key or lacks required tools"

"$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
  "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online_emulators < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online_emulators[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online_emulators[@]}"
  export ANDROID_SERIAL="${online_emulators[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] \
  || fail "selected Android device is not online: $ANDROID_SERIAL"
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) \
  || fail "J20 requires API 35+; selected device reports ${device_api:-unknown}"

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

encoded_key="$(base64 -w0 "$ROOT_DIR/tests/docker/test_key")"
logcat_file="$EVIDENCE_DIR/j20-logcat.txt"
logcat_pid=""
stop_logcat() {
  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true
    logcat_pid=""
  fi
}
trap 'stop_logcat; pocketshell_release_all' EXIT

"$ADB" -s "$ANDROID_SERIAL" logcat -c
"$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime -s J20ComposerUploadProgress:I > "$logcat_file" 2>&1 &
logcat_pid=$!
sleep 0.2
kill -0 "$logcat_pid" 2>/dev/null || fail 'could not start the J20 logcat collector'

test_class='com.pocketshell.app.smoke.J20ComposerUploadProgressJourney'
printf 'Running packaged J20 on %s (API %s), agents port %s, Toxiproxy port %s, run %s\n' \
  "$ANDROID_SERIAL" "$device_api" "$AGENTS_PORT" "$PROXY_PORT" "$ARTIFACT_RUN_ID"
if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PROXY_PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.agentsPort=$AGENTS_PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.toxiproxyApiPort=$TOXIPROXY_API_PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyBase64=$encoded_key" \
    "-Pandroid.testInstrumentationRunnerArguments.artifactRunId=$ARTIFACT_RUN_ID" \
    --stacktrace --console=plain 2>&1 | tee "$EVIDENCE_DIR/gradle-connected.log"; then
  :
else
  test_exit_code=$?
  stop_logcat
  mkdir -p "$RESULTS_DIR"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  python3 "$ROOT_DIR/scripts/verify-j20-upload-host-oracle.py" \
    --port "$AGENTS_PORT" --key "$KEY_COPY" --run-id "$ARTIFACT_RUN_ID" \
    --logcat "$logcat_file" --output "$EVIDENCE_DIR/host-payload-cleanup-oracle.json" || true
  exit "$test_exit_code"
fi

stop_logcat
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1
"$ROOT_DIR/scripts/check-js-upload-progress-results.py" --results-dir "$RESULTS_DIR"

mkdir -p "$EVIDENCE_DIR/junit"
cp "$RESULTS_DIR"/TEST-*.xml "$EVIDENCE_DIR/junit/"
midflight_name="i2929-j20-mid-flight-$ARTIFACT_RUN_ID.png"
completed_name="i2929-j20-completed-$ARTIFACT_RUN_ID.png"
"$ADB" -s "$ANDROID_SERIAL" pull "/sdcard/Download/$midflight_name" "$EVIDENCE_DIR/$midflight_name"
"$ADB" -s "$ANDROID_SERIAL" pull "/sdcard/Download/$completed_name" "$EVIDENCE_DIR/$completed_name"
python3 - "$EVIDENCE_DIR" "$midflight_name" "$completed_name" <<'PY'
from pathlib import Path
import sys

directory = Path(sys.argv[1])
for name in sys.argv[2:]:
    image = directory / name
    payload = image.read_bytes()
    if len(payload) < 1024 or not payload.startswith(b"\x89PNG\r\n\x1a\n"):
        raise SystemExit(f"FAIL: J20 screenshot is missing or invalid: {image}")
    print(f"PASS: captured same-run J20 PNG {image.name} ({len(payload)} bytes)")
PY

python3 "$ROOT_DIR/scripts/verify-j20-upload-host-oracle.py" \
  --port "$AGENTS_PORT" --key "$KEY_COPY" --run-id "$ARTIFACT_RUN_ID" \
  --logcat "$logcat_file" --output "$EVIDENCE_DIR/host-payload-cleanup-oracle.json"
sha256sum "$EVIDENCE_DIR"/*.png
printf 'PASS: J20 packaged upload-progress evidence saved under %s\n' "$EVIDENCE_DIR"
