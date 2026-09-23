#!/usr/bin/env bash
# Run the packaged JS composer against an already healthy, isolated agents lane.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="$(printenv ANDROID_SDK || printenv ANDROID_SDK_ROOT || printenv ANDROID_HOME || printf '/home/alexey/Android/Sdk')"
ADB="$(printenv ADB || printf '%s/platform-tools/adb' "$ANDROID_SDK")"
SUFFIX="i2857"
PORT=""
SESSION_BASE="js2857-$(date +%s)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-composer-docker.sh --port 2243|2244|2245 [--session-prefix NAME] [--suffix TOKEN]

Builds and runs the packaged composer journey against an already healthy
agents fixture pool lane, then checks exact bytes and insert/uncertain markers
from the host side. It uses the shared Gradle-output and Android-device locks.

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
[[ -x "$ADB" ]] || fail "adb is missing or not executable: $ADB"
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] || fail 'Docker fixture test key is missing'

"$ROOT_DIR/scripts/check-js-composer-journey-results.py" --self-test
"$ROOT_DIR/scripts/extract-js-composer-artifacts.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-composer-docker.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-composer-docker.sh suffix=$SUFFIX port=$PORT"

container="pocketshell-test-agents-$PORT"
health="$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
[[ "$health" == healthy ]] || fail "agents lane $PORT must already be healthy; $container reports ${health:-missing}"

ssh_key_copy="$ROOT_DIR/android/app/build/outputs/js-composer-fixture-key"
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
  || fail "composer keyboard evidence requires API 35+; device reports ${device_api:-unknown}"
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
tmp_root="$(printenv TMPDIR || printf '/tmp')"
evidence_dir="$tmp_root/pocketshell-js2857-$SESSION_BASE"
mkdir -p "$evidence_dir"
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

encoded_key="$(base64 -w0 "$ROOT_DIR/tests/docker/test_key")"
test_class='com.pocketshell.app.smoke.JsComposerDockerJourneyTest'
asset_logcat="$evidence_dir/composer-assets-live-logcat.txt"
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
trap 'stop_asset_logcat; pocketshell_release_all' EXIT

# Capture only the run-scoped artifact channel while the test emits it. A
# post-hoc tail of the shared emulator buffer can silently lose a burst of PNG
# chunks before extraction gets a chance to detect the gap.
prepare_asset_logcat_path "$asset_logcat"
[[ "$asset_logcat" != "$RESULTS_DIR/"* ]] || fail 'live artifact collector output must survive Gradle result cleanup'
printf 'PASS: live artifact collector output is writable and outside Gradle result cleanup\n'
"$ADB" -s "$ANDROID_SERIAL" logcat -c
"$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime -s PS2857Asset:I > "$asset_logcat" 2>&1 &
asset_logcat_pid=$!
sleep 0.2
kill -0 "$asset_logcat_pid" 2>/dev/null || fail 'could not start the live composer artifact logcat collector'
printf 'Running packaged composer Docker journey on %s (API %s), Docker port %s, sessions %s-*\n' \
  "$ANDROID_SERIAL" "$device_api" "$PORT" "$SESSION_BASE"
if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyBase64=$encoded_key" \
    "-Pandroid.testInstrumentationRunnerArguments.sshSessionName=$SESSION_BASE" \
    "-Pandroid.testInstrumentationRunnerArguments.artifactRunId=$ARTIFACT_RUN_ID" \
    --stacktrace --console=plain; then
  :
else
  test_exit_code=$?
  stop_asset_logcat
  mkdir -p "$RESULTS_DIR"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 4000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  "$ROOT_DIR/scripts/extract-js-composer-artifacts.py" --preserve-on-failure \
    --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" \
    --output-dir "$RESULTS_DIR/composer-artifacts" || true
  exit "$test_exit_code"
fi

stop_asset_logcat
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1
"$ROOT_DIR/scripts/check-js-composer-journey-results.py" --results-dir "$RESULTS_DIR"

"$ROOT_DIR/scripts/extract-js-composer-artifacts.py" \
  --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" --output-dir "$evidence_dir"
sha256sum "$evidence_dir/composer-keyboard.png"

ssh_remote() {
  ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"
}

bytes_session="$SESSION_BASE-bytes"
uncertain_session="$SESSION_BASE-uncertain"
unicode_hex="$(ssh_remote "cat /tmp/$bytes_session-unicode.hex")"
multiline_hex="$(ssh_remote "od -An -tx1 /tmp/$bytes_session-multiline.raw | tr -d '[:space:]'")"
[[ "$unicode_hex" == '636166c3a920f09fa7aa' ]] \
  || fail "remote Unicode bytes mismatch: expected 636166c3a920f09fa7aa, got ${unicode_hex:-<empty>}"
[[ "$multiline_hex" == '1b5b3230307e616c7068610aceb26574610af09f99821b5b3230317e' ]] \
  || fail "remote bracketed multiline bytes mismatch: expected 1b5b3230307e616c7068610aceb26574610af09f99821b5b3230317e, got ${multiline_hex:-<empty>}"
printf 'PASS: remote Unicode PTY bytes %s\n' "$unicode_hex"
printf 'PASS: remote multiline PTY bytes %s\n' "$multiline_hex"

insert_marker="PS2857_INSERT_$SESSION_BASE"
insert_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --screen --plain")"
[[ "$insert_capture" == *"$insert_marker"* ]] || fail 'Insert line was not left visible in the session screen capture'
insert_file_state="$(ssh_remote "if test -e /tmp/$bytes_session-insert.marker; then printf present; else printf absent; fi")"
[[ "$insert_file_state" == absent ]] || fail 'Insert executed the command instead of leaving it at the prompt'
printf 'PASS: Insert left %s at the prompt; marker file is absent\n' "$insert_marker"

uncertain_marker="PS2857_UNCERTAIN_$SESSION_BASE"
uncertain_file_state="$(ssh_remote "if test -e /tmp/$uncertain_session-uncertain.marker; then printf present; else printf absent; fi")"
[[ "$uncertain_file_state" == absent ]] || fail 'uncertain delivery command ran after the transport drop'
printf 'PASS: uncertain command %s was not replayed after reconnect\n' "$uncertain_marker"

printf 'Evidence directory: %s\n' "$evidence_dir"
