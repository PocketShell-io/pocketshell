#!/usr/bin/env bash
# Run the packaged mobile fast-key journey against an already healthy agents lane.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="$(printenv ANDROID_SDK || printenv ANDROID_SDK_ROOT || printenv ANDROID_HOME || printf '/home/alexey/Android/Sdk')"
ADB="$(printenv ADB || printf '%s/platform-tools/adb' "$ANDROID_SDK")"
SUFFIX="i2884"
PORT=""
SESSION_BASE="js2884-$(date +%s)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-hotkeys-docker.sh --port 2243|2244|2245 [--session-prefix NAME] [--suffix TOKEN]

Builds and runs the packaged Android fast-key journey against a healthy agents
fixture lane, then compares the captured PTY files with an independent SSH
host-side byte oracle. The run records API 35 IME, Back, docked tray, key
reachability, and screenshot evidence. It uses the shared Gradle-output and Android-device locks.

Start an unclaimed lane with scripts/agents-pool.sh up PORT first. This runner
does not create or tear down Docker state.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port)
      [[ $# -ge 2 ]] || fail '--port needs a value'
      PORT="$2"
      shift 2
      ;;
    --session-prefix)
      [[ $# -ge 2 ]] || fail '--session-prefix needs a value'
      SESSION_BASE="$2"
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

[[ "$PORT" =~ ^(2243|2244|2245)$ ]] || fail '--port must be one of the isolated pool ports 2243, 2244, or 2245'
[[ "$SESSION_BASE" =~ ^[A-Za-z0-9-]{8,32}$ ]] || fail '--session-prefix must be 8-32 letters, digits, or dashes'
[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail '--suffix must match [A-Za-z0-9._]+'
ARTIFACT_RUN_ID="${SESSION_BASE}-$(date +%s%N)"
evidence_dir="$ROOT_DIR/android/app/build/outputs/js-hotkeys/$ARTIFACT_RUN_ID"
mkdir -p "$evidence_dir"
cat > "$evidence_dir/hotkeys-run-metadata.txt" <<EOF
run_id=$ARTIFACT_RUN_ID
session_prefix=$SESSION_BASE
docker_port=$PORT
app_suffix=$SUFFIX
started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
[[ -x "$ADB" ]] || fail "adb is missing or not executable: $ADB"
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] || fail 'Docker fixture test key is missing'

"$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py" --self-test
"$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-hotkeys-docker.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-hotkeys-docker.sh suffix=$SUFFIX port=$PORT"

container="pocketshell-test-agents-$PORT"
health="$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
[[ "$health" == healthy ]] || fail "agents lane $PORT must already be healthy; $container reports ${health:-missing}"

ssh_key_copy="$ROOT_DIR/android/app/build/outputs/js-hotkeys-fixture-key"
mkdir -p "$(dirname -- "$ssh_key_copy")"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$ssh_key_copy"
source_key_hash="$(sha256sum "$ROOT_DIR/tests/docker/test_key" | awk '{print $1}')"
copy_key_hash="$(sha256sum "$ssh_key_copy" | awk '{print $1}')"
[[ "$source_key_hash" == "$copy_key_hash" ]] || fail 'mode-0600 SSH key copy differs from the committed fixture key'
printf 'Fixture SSH key copy verified: %s\n' "$copy_key_hash"
ssh_opts=(-i "$ssh_key_copy" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 \
  'command -v a >/dev/null && command -v aplexer >/dev/null && command -v pocketshell >/dev/null' \
  || fail "agents lane $PORT does not authenticate with the committed test key or lacks the aplexer tools"

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
  || fail "fast-key keyboard evidence requires API 35+; device reports ${device_api:-unknown}"
printf 'android_serial=%s\nandroid_api=%s\n' "$ANDROID_SERIAL" "$device_api" \
  >> "$evidence_dir/hotkeys-run-metadata.txt"
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
test_class='com.pocketshell.app.smoke.JsFastKeysDockerJourneyTest'
asset_logcat="$evidence_dir/hotkeys-assets-live-logcat.txt"
asset_logcat_pid=""
prepare_asset_logcat_path() {
  mkdir -p "$(dirname -- "$1")"
  : > "$1" || fail "cannot create live artifact logcat output: $1"
  [[ -f "$1" && -w "$1" ]] || fail "live artifact logcat output is not writable: $1"
}
stop_asset_logcat() {
  if [[ -n "$asset_logcat_pid" ]]; then
    kill "$asset_logcat_pid" 2>/dev/null || true
    wait "$asset_logcat_pid" 2>/dev/null || true
    asset_logcat_pid=""
  fi
}
finish_hotkeys_run() {
  local exit_status=$?
  set +e
  stop_asset_logcat
  if [[ -d "$RESULTS_DIR" ]]; then
    shopt -s nullglob
    local report
    for report in "$RESULTS_DIR"/TEST-*.xml; do cp -- "$report" "$evidence_dir/"; done
    shopt -u nullglob
    for diagnostic in diagnostics-logcat.txt diagnostics-input-method.txt diagnostics-screen.png; do
      if [[ -f "$RESULTS_DIR/$diagnostic" ]]; then cp -- "$RESULTS_DIR/$diagnostic" "$evidence_dir/wrapper-$diagnostic"; fi
    done
  fi
  {
    printf 'exit_code=%s\n' "$exit_status"
    printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >> "$evidence_dir/hotkeys-run-metadata.txt"
  pocketshell_release_all
  exit "$exit_status"
}
trap finish_hotkeys_run EXIT

prepare_asset_logcat_path "$asset_logcat"
[[ "$asset_logcat" != "$RESULTS_DIR/"* ]] || fail 'live artifact collector output must survive Gradle result cleanup'
printf 'PASS: live artifact collector output is writable and outside Gradle result cleanup\n'
"$ADB" -s "$ANDROID_SERIAL" logcat -c
"$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime -s PS2884Asset:I PS2884Geometry:I > "$asset_logcat" 2>&1 &
asset_logcat_pid=$!
sleep 0.2
kill -0 "$asset_logcat_pid" 2>/dev/null || fail 'could not start the live fast-key artifact logcat collector'
printf 'Running packaged fast-key Docker journey on %s (API %s), Docker port %s, sessions %s-*.\n' \
  "$ANDROID_SERIAL" "$device_api" "$PORT" "$SESSION_BASE"
if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyBase64=$encoded_key" \
    "-Pandroid.testInstrumentationRunnerArguments.sshSessionName=$SESSION_BASE" \
    "-Pandroid.testInstrumentationRunnerArguments.artifactRunId=$ARTIFACT_RUN_ID" \
    --stacktrace --console=plain 2>&1 | tee "$evidence_dir/hotkeys-gradle.log"; then
  :
else
  test_exit_code=$?
  stop_asset_logcat
  mkdir -p "$RESULTS_DIR"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 5000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  "$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py" --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" \
    --output-dir "$evidence_dir" || true
  exit "$test_exit_code"
fi

stop_asset_logcat
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1
"$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py" --results-dir "$RESULTS_DIR"
"$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py" \
  --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" --output-dir "$evidence_dir"

ssh_remote() { ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"; }
first_raw="$SESSION_BASE-keys-bytes.raw"
resumed_raw="$SESSION_BASE-keys-resumed-bytes.raw"
dictation_raw="$SESSION_BASE-keys-dictation.raw"
first_hex="$(ssh_remote "od -An -tx1 /tmp/$first_raw | tr -d '[:space:]'")"
resumed_hex="$(ssh_remote "od -An -tx1 /tmp/$resumed_raw | tr -d '[:space:]'")"
dictation_oracle="$(python3 - "$evidence_dir/fastkeys-journey.json" <<'PY'
import json
import re
import sys

with open(sys.argv[1], encoding="utf-8") as source:
    journey = json.load(source)
dictation = journey.get("dictation")
if not isinstance(dictation, dict):
    raise SystemExit("FAIL: integrated dictation evidence is missing")
raw_file = dictation.get("rawFile")
expected_hex = dictation.get("expectedHostHex")
expected_count = dictation.get("expectedByteCount")
if not isinstance(raw_file, str) or not re.fullmatch(r"/tmp/[A-Za-z0-9._-]+-keys-dictation\.raw", raw_file):
    raise SystemExit("FAIL: dictation raw file path is unsafe or malformed")
if not isinstance(expected_hex, str) or not re.fullmatch(r"(?:[0-9a-f]{2})+", expected_hex):
    raise SystemExit("FAIL: dictation expected host bytes are unsafe or malformed")
if isinstance(expected_count, bool) or not isinstance(expected_count, int) or expected_count != len(expected_hex) // 2:
    raise SystemExit("FAIL: dictation byte count does not match the journey manifest")
print(f"{raw_file}\t{expected_hex}\t{expected_count}")
PY
)" || fail 'could not read the integrated dictation host byte oracle'
IFS=$'\t' read -r dictation_raw_path expected_dictation_hex expected_dictation_count <<< "$dictation_oracle"
[[ "$dictation_raw_path" == "/tmp/$dictation_raw" ]] || fail "unexpected dictation raw file: ${dictation_raw_path:-<empty>}"
expected_first='1b5b411b5b421b091b5b5a110303030404040d'
expected_resumed='1b5b41'
[[ "$first_hex" == "$expected_first" ]] \
  || fail "remote fast-key PTY bytes mismatch: expected $expected_first, got ${first_hex:-<empty>}"
[[ "$resumed_hex" == "$expected_resumed" ]] \
  || fail "reattached-session PTY bytes mismatch: expected $expected_resumed, got ${resumed_hex:-<empty>}"
dictation_hex="$(ssh_remote "od -An -tx1 /tmp/$dictation_raw | tr -d '[:space:]'")"
[[ "$dictation_hex" == "$expected_dictation_hex" ]] \
  || fail "dictation PTY bytes mismatch: expected $expected_dictation_hex, got ${dictation_hex:-<empty>}"
first_count="$(ssh_remote "wc -c < /tmp/$first_raw | tr -d '[:space:]'")"
resumed_count="$(ssh_remote "wc -c < /tmp/$resumed_raw | tr -d '[:space:]'")"
dictation_count="$(ssh_remote "wc -c < /tmp/$dictation_raw | tr -d '[:space:]'")"
[[ "$first_count" == 19 && "$resumed_count" == 3 ]] \
  || fail "remote raw byte file lengths mismatch: first=${first_count:-?} resumed=${resumed_count:-?}"
[[ "$dictation_count" == "$expected_dictation_count" ]] \
  || fail "dictation raw byte file length mismatch: expected=$expected_dictation_count got=${dictation_count:-?}"
{
  printf 'PASS: first live session exact PTY bytes (%s bytes): %s\n' "$first_count" "$first_hex"
  printf 'PASS: reattached live session exact PTY bytes (%s bytes): %s\n' "$resumed_count" "$resumed_hex"
  printf 'PASS: docked dictation exact PTY bytes (%s bytes): %s\n' "$dictation_count" "$dictation_hex"
  printf 'screenshot_sha256='; sha256sum "$evidence_dir/fastkeys-ime-open.png" "$evidence_dir/fastkeys-sheet-main-ime-open.png" \
    "$evidence_dir/fastkeys-sheet-main-tail-ime-open.png" "$evidence_dir/fastkeys-sheet-ctrl-ime-open.png" \
    "$evidence_dir/fastkeys-sheet-ctrl-tail-ime-open.png" "$evidence_dir/fastkeys-tray-ime-dismissed.png" \
    "$evidence_dir/fastkeys-tray-closed.png" \
    "$evidence_dir/fastkeys-reconnected-ime-open.png" \
    "$evidence_dir/fastkeys-dictation-listening-ime-open.png" \
    "$evidence_dir/fastkeys-dictation-reattached-ime-open.png"
} | tee "$evidence_dir/hotkeys-host-oracle.txt"

printf 'Evidence directory: %s\n' "$evidence_dir"
