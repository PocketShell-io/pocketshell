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
FORCE_FIRST_POST_ATTACH_TAP_MISS=0
COMPOSER_FOCUS_MAX_ATTEMPTS=""

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-composer-docker.sh --port 2243|2244|2245 [--session-prefix NAME] [--suffix TOKEN]
       [--force-first-post-attach-tap-miss] [--composer-focus-max-attempts 1|2]

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
    --force-first-post-attach-tap-miss)
      FORCE_FIRST_POST_ATTACH_TAP_MISS=1
      shift
      ;;
    --composer-focus-max-attempts)
      [[ $# -ge 2 ]] || fail '--composer-focus-max-attempts needs a value'
      COMPOSER_FOCUS_MAX_ATTEMPTS="$2"
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
if [[ -n "$COMPOSER_FOCUS_MAX_ATTEMPTS" && ! "$COMPOSER_FOCUS_MAX_ATTEMPTS" =~ ^[12]$ ]]; then
  fail '--composer-focus-max-attempts must be 1 or 2'
fi
ARTIFACT_RUN_ID="${SESSION_BASE}-$(date +%s%N)"
evidence_dir="$ROOT_DIR/android/app/build/outputs/js-composer/$ARTIFACT_RUN_ID"
mkdir -p "$evidence_dir"
started_epoch="$(date +%s)"
cat > "$evidence_dir/composer-run-metadata.txt" <<EOF
run_id=$ARTIFACT_RUN_ID
session_prefix=$SESSION_BASE
docker_port=$PORT
app_suffix=$SUFFIX
started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
: > "$evidence_dir/composer-host-oracle.txt"
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
printf 'android_serial=%s\nandroid_api=%s\n' "$ANDROID_SERIAL" "$device_api" \
  >> "$evidence_dir/composer-run-metadata.txt"
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
test_class='com.pocketshell.app.smoke.JsComposerDockerJourneyTest'
focus_test_args=()
if [[ "$FORCE_FIRST_POST_ATTACH_TAP_MISS" == 1 ]]; then
  focus_test_args+=("-Pandroid.testInstrumentationRunnerArguments.composerForceFirstPostAttachTapMiss=true")
fi
if [[ -n "$COMPOSER_FOCUS_MAX_ATTEMPTS" ]]; then
  focus_test_args+=("-Pandroid.testInstrumentationRunnerArguments.composerFocusMaxAttempts=$COMPOSER_FOCUS_MAX_ATTEMPTS")
fi
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
finish_composer_run() {
  local exit_status=$?
  set +e
  stop_asset_logcat
  if [[ -d "$RESULTS_DIR" ]]; then
    shopt -s nullglob
    local report
    for report in "$RESULTS_DIR"/TEST-*.xml; do
      cp -- "$report" "$evidence_dir/"
    done
    shopt -u nullglob
    for diagnostic in diagnostics-logcat.txt diagnostics-input-method.txt diagnostics-screen.png; do
      if [[ -f "$RESULTS_DIR/$diagnostic" ]]; then
        cp -- "$RESULTS_DIR/$diagnostic" "$evidence_dir/wrapper-$diagnostic"
      fi
    done
  fi
  {
    printf 'exit_code=%s\n' "$exit_status"
    printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'duration_seconds=%s\n' "$(( $(date +%s) - started_epoch ))"
  } >> "$evidence_dir/composer-run-metadata.txt"
  pocketshell_release_all
  exit "$exit_status"
}
trap finish_composer_run EXIT

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
    "${focus_test_args[@]}" \
    --stacktrace --console=plain 2>&1 | tee "$evidence_dir/composer-gradle.log"; then
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
    --output-dir "$evidence_dir" || true
  exit "$test_exit_code"
fi

stop_asset_logcat
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1
"$ROOT_DIR/scripts/check-js-composer-journey-results.py" --results-dir "$RESULTS_DIR"

"$ROOT_DIR/scripts/extract-js-composer-artifacts.py" \
  --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" --output-dir "$evidence_dir" \
  --expected-terminal-marker "PS2857_SENT_$SESSION_BASE"
exec > >(tee -a "$evidence_dir/composer-host-oracle.txt") 2>&1
inline_preview="$evidence_dir/inline-dictation-preview.png"
[[ -s "$inline_preview" ]] || fail 'same-run inline dictation screenshot is missing or empty'
file "$inline_preview"
python3 - "$inline_preview" <<'PY'
from pathlib import Path
import sys

payload = Path(sys.argv[1]).read_bytes()
if len(payload) < 1024 or not payload.startswith(b"\x89PNG\r\n\x1a\n"):
    raise SystemExit("FAIL: inline dictation evidence is not a non-empty PNG")
print(f"PASS: validated inline dictation PNG ({len(payload)} bytes)")
PY
sha256sum "$evidence_dir/composer-keyboard.png"
sha256sum "$evidence_dir/composer-post-send.png"
sha256sum "$inline_preview"

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

sent_marker="PS2857_SENT_$SESSION_BASE"
sent_output_marker="$(ssh_remote "cat /tmp/$bytes_session-sent-output.marker | tr -d '\\n'")"
[[ "$sent_output_marker" == "$sent_marker" ]] \
  || fail "remote sent-output marker mismatch: expected $sent_marker, got ${sent_output_marker:-<empty>}"
printf 'PASS: host PTY output contained %s\n' "$sent_output_marker"

dictation_marker="PS2857_DICTATION_EDITED_$SESSION_BASE"
dictation_output_marker="$(ssh_remote "cat /tmp/$bytes_session-dictation.marker | tr -d '\\n'")"
[[ "$dictation_output_marker" == "$dictation_marker" ]] \
  || fail "edited composer dictation did not reach the host PTY exactly: expected $dictation_marker, got ${dictation_output_marker:-<empty>}"
dictation_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --bytes 4096")"
[[ "$dictation_capture" == *"$dictation_marker"* ]] \
  || fail 'independent host PTY capture did not contain the edited dictation text'
printf 'PASS: edited controlled-recognition text reached Docker PTY and host capture as %s\n' "$dictation_marker"

inline_marker="PS2857_INLINE_$SESSION_BASE"
inline_file_state='absent'
for attempt in $(seq 1 30); do
  inline_file_state="$(ssh_remote "if test -f /tmp/$bytes_session-inline-submitted.marker; then printf present; else printf absent; fi")"
  [[ "$inline_file_state" == present ]] && break
  sleep 0.2
done
[[ "$inline_file_state" == present ]] || fail 'explicit Enter did not execute the inserted inline dictation command'
inline_hex="$(ssh_remote "cat /tmp/$bytes_session-inline-utf8.hex")"
[[ "$inline_hex" == '636166c3a920f09fa7aa' ]] \
  || fail "inline dictation Unicode bytes mismatch: expected 636166c3a920f09fa7aa, got ${inline_hex:-<empty>}"
inline_submitted_marker="$(ssh_remote "cat /tmp/$bytes_session-inline-submitted.marker | tr -d '\\n'")"
[[ "$inline_submitted_marker" == "$inline_marker" ]] \
  || fail "inline dictation marker mismatch: expected $inline_marker, got ${inline_submitted_marker:-<empty>}"
inline_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --bytes 4096")"
[[ "$inline_capture" == *"$inline_marker"* ]] \
  || fail 'independent host PTY capture did not contain the inline dictation command'
echo "PASS: explicit-stop inline dictation reached the host as exact UTF-8 bytes $inline_hex and executed only after Enter"

background_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --bytes 4096")"
background_uncertain_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$uncertain_session' --bytes 4096")"
for marker in "PS2857_BG_PARTIAL_$SESSION_BASE" "PS2857_BG_LATE_$SESSION_BASE"; do
  [[ "$background_capture" != *"$marker"* ]] || fail "background-cancelled transcript marker reached the bytes-session PTY: $marker"
  [[ "$background_uncertain_capture" != *"$marker"* ]] || fail "background-cancelled transcript marker reached the uncertain-session PTY: $marker"
done
background_file_state="$(ssh_remote "if test -e /tmp/$bytes_session-inline-background.marker; then printf present; else printf absent; fi")"
[[ "$background_file_state" == absent ]] || fail 'late background transcript executed on the host after app resume'
background_recovery_marker="PS2857_BG_RECOVERY_$SESSION_BASE"
background_recovery_output="$(ssh_remote "cat /tmp/$bytes_session-inline-background-recovery.marker | tr -d '\\n'")"
[[ "$background_recovery_output" == "$background_recovery_marker" ]] \
  || fail "fresh dictation did not recover after background cancellation: expected $background_recovery_marker, got ${background_recovery_output:-<empty>}"
[[ "$background_capture" == *"$background_recovery_marker"* ]] \
  || fail 'independent host PTY capture did not contain the post-background recovery dictation'
printf 'PASS: background appStateChange cancelled partial/late text; fresh dictation reached Docker PTY as %s\n' \
  "$background_recovery_marker"

target_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$uncertain_session' --bytes 4096")"
target_bytes_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --bytes 4096")"
for marker in "PS2857_TARGET_PARTIAL_$SESSION_BASE" "PS2857_TARGET_LATE_$SESSION_BASE"; do
  [[ "$target_capture" != *"$marker"* ]] || fail "target-change-cancelled transcript marker reached the target PTY: $marker"
  [[ "$target_bytes_capture" != *"$marker"* ]] || fail "target-change-cancelled transcript marker reached the original bytes-session PTY: $marker"
done
target_file_state="$(ssh_remote "if test -e /tmp/$bytes_session-inline-target-change.marker; then printf present; else printf absent; fi")"
[[ "$target_file_state" == absent ]] || fail 'late transcript executed after switching the live target session'
printf 'PASS: live target change cancelled partial/late text before either PTY accepted a write\n'

insert_marker="PS2857_INSERT_$SESSION_BASE"
insert_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --bytes 4096")"
[[ "$insert_capture" == *"$insert_marker"* ]] || fail 'remote PTY history did not contain the inserted prompt line'
insert_file_state="$(ssh_remote "if test -e /tmp/$bytes_session-insert.marker; then printf present; else printf absent; fi")"
[[ "$insert_file_state" == absent ]] || fail 'Insert executed the command instead of leaving it at the prompt'
printf 'PASS: app Xterm and remote PTY history contain the inserted %s prompt line; marker file is absent\n' "$insert_marker"

uncertain_marker="PS2857_UNCERTAIN_$SESSION_BASE"
uncertain_file_state="$(ssh_remote "if test -e /tmp/$uncertain_session-uncertain.marker; then printf present; else printf absent; fi")"
[[ "$uncertain_file_state" == absent ]] || fail 'uncertain delivery command ran after the transport drop'
printf 'PASS: uncertain command %s was not replayed after reconnect\n' "$uncertain_marker"

printf 'Evidence directory: %s\n' "$evidence_dir"
