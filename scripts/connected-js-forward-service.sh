#!/usr/bin/env bash
# Packaged shared-app J18/J22. Existing isolated agents-pool fixture only;
# no service policy or tunnel sockets are mocked.
set -euo pipefail
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX=i3097forward
PORT=""
RUN_ID=""
PREPARE_ONLY=0
TEST_ONLY=0
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix) SUFFIX="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --run-id) RUN_ID="$2"; shift 2;;
    --prepare-only) PREPARE_ONLY=1; shift;;
    --test-only) TEST_ONLY=1; shift;;
    *) fail "unknown argument $1";;
  esac
done
[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail 'invalid suffix'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail 'prepare-only and test-only conflict'
"$ROOT_DIR/scripts/check-js-forward-service-results.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/agents-pool.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" connected-js-forward-service.sh
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-forward-service.sh suffix=$SUFFIX"
if [[ "$TEST_ONLY" != 1 ]]; then
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi
if [[ "$PREPARE_ONLY" == 1 ]]; then exit 0; fi
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" != 2222 ]] || fail 'an isolated healthy agents-pool port is required'
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t devices < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2=="device" {print $1}')
  (( ${#devices[@]} == 1 )) || fail 'select one emulator with ANDROID_SERIAL'
  export ANDROID_SERIAL="${devices[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state)" == device ]] || fail 'emulator offline'
port_lock_file="$(pocketshell_agents_lock_file_for_port "$ROOT_DIR" "$PORT")"
exec {port_lock_fd}>"$port_lock_file"
flock -n "$port_lock_fd" || fail 'fixture port already claimed'
container="$(pocketshell_agents_container_for_port "$PORT")"
[[ "$(docker inspect --format='{{.State.Health.Status}}' "$container")" == healthy ]] || fail 'fixture must be healthy'
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
SESSION_RUN="${RUN_ID:-ps3097-$(date +%s)-$RANDOM}"
[[ "$SESSION_RUN" =~ ^[a-z0-9-]{6,40}$ ]] || fail "invalid --run-id"
RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
EVIDENCE_DIR="$ROOT_DIR/android/app/build/outputs/js-forward-service/$SESSION_RUN"
mkdir -p "$EVIDENCE_DIR"
fixture_key="$EVIDENCE_DIR/fixture-key"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$fixture_key"
fixture() { ssh -q -i "$fixture_key" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 "$1"; }
cleanup() {
  # Independent cleanup also runs if Android test teardown could not reach SSH.
  for journey in J18 J22; do
    fixture "stem=/tmp/PS_${journey}_$SESSION_RUN; if test -r \$stem.pid && test -r \$stem.port; then pid=\$(cat \$stem.pid); port=\$(cat \$stem.port); case \$pid in ''|*[!0-9]*) exit 0;; esac; if test -r /proc/\$pid/cmdline; then args=\$(tr '\\000' ' ' < /proc/\$pid/cmdline); case \"\$args\" in *\"python3 -u -m http.server \$port --bind 127.0.0.1 --directory /tmp\"*) kill \$pid;; esac; fi; fi" || true
  done
  fixture "pocketshell sessions kill -- $SESSION_RUN:$SESSION_RUN >/dev/null 2>&1" || true
  # Delete only this runner's own temporary private-key copy, never user keys.
  python3 - "$fixture_key" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).unlink(missing_ok=True)
PY
  pocketshell_release_all
}
trap cleanup EXIT
fixture "mkdir -p ~/$SESSION_RUN && pocketshell sessions create --json --cwd ~/$SESSION_RUN -- $SESSION_RUN >/dev/null"
agents_host_keys="$("$ROOT_DIR/scripts/shared-app-host-key-fixture.py" keys --port "$PORT")"
DEVICE_KEY_PATH="/data/local/tmp/pocketshell-$SUFFIX-forward-key.pem"
"$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/test_key" "$DEVICE_KEY_PATH" >/dev/null
# Isolate old Gradle XML before this invocation; each lane retains its own copy.
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil, sys
p=Path(sys.argv[1])
if p.exists(): shutil.rmtree(p)
PY
set +e
"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
  "-PpocketshellAppIdSuffix=$SUFFIX" \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.J18AutoForwardResumeJourney,com.pocketshell.app.smoke.J22ForwardServiceFgsJourney \
  "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
  "-Pandroid.testInstrumentationRunnerArguments.sessionRun=$SESSION_RUN" \
  "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyPath=$DEVICE_KEY_PATH" \
  "-Pandroid.testInstrumentationRunnerArguments.agentsHostKeysBase64=$(printf '%s' "$agents_host_keys" | base64 -w0)" \
  --stacktrace --console=plain
status=$?
set -e
if [[ -d "$RESULTS_DIR" ]]; then cp -a "$RESULTS_DIR" "$EVIDENCE_DIR/instrumentation-results"; fi
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 6000 > "$EVIDENCE_DIR/logcat.txt" 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" shell dumpsys activity services > "$EVIDENCE_DIR/services.txt" 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" shell dumpsys notification > "$EVIDENCE_DIR/notifications.txt" 2>&1 || true
for shot in j18-auto-without-ports j18-resumed j22-forward-active j22-stopped "PS_J18_$SESSION_RUN" "PS_J22_$SESSION_RUN"; do
  "$ADB" -s "$ANDROID_SERIAL" pull "/data/local/tmp/ps2952-$shot.png" "$EVIDENCE_DIR/$shot.png" >/dev/null 2>&1 || true
done
for journey in J18 J22; do
  "$ADB" -s "$ANDROID_SERIAL" pull "/data/local/tmp/PS_${journey}_$SESSION_RUN.json" "$EVIDENCE_DIR/$journey-diagnostics.json" >/dev/null 2>&1 || true
  docker exec -u testuser "$container" cat "/tmp/PS_${journey}_$SESSION_RUN.log" > "$EVIDENCE_DIR/$journey-http.log" 2>/dev/null || true
done
if (( status != 0 )); then
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$EVIDENCE_DIR/failure-screen.png" 2>/dev/null || true
  exit "$status"
fi
"$ROOT_DIR/scripts/check-js-forward-service-results.py" --results-dir "$EVIDENCE_DIR/instrumentation-results" --host-evidence-dir "$EVIDENCE_DIR" --run-id "$SESSION_RUN"
