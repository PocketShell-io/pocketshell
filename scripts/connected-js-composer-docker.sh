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
cat > "$evidence_dir/composer-run-metadata.txt" <<EOF
run_id=$ARTIFACT_RUN_ID
session_prefix=$SESSION_BASE
docker_port=$PORT
app_suffix=$SUFFIX
started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
[[ -x "$ADB" ]] || fail "adb is missing or not executable: $ADB"
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] || fail 'Docker fixture test key is missing'

"$ROOT_DIR/scripts/check-js-composer-journey-results.py" --self-test
"$ROOT_DIR/scripts/extract-js-composer-artifacts.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"

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

"$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX" 2>&1 | tee "$evidence_dir/composer-gradle.log"
"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
  "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain \
  2>&1 | tee -a "$evidence_dir/composer-gradle.log"

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
pocketshell_android_input_preflight "$ADB" "$ANDROID_SERIAL" "$evidence_dir/input-preflight.txt" \
  || fail "Android input preflight failed on $ANDROID_SERIAL; see $evidence_dir/input-preflight.txt"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
test_class='com.pocketshell.app.smoke.JsComposerDockerJourneyTest'
APP_PACKAGE="com.pocketshell.app.$SUFFIX"
DEVICE_KEY_PATH="/data/local/tmp/pocketshell-$SUFFIX-key.pem"
TEST_PACKAGE="$APP_PACKAGE.test"
INSTRUMENTATION_COMPONENT="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
APP_APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" ]] || fail "packaged app APK is missing: $APP_APK"
[[ -f "$TEST_APK" ]] || fail "packaged AndroidTest APK is missing: $TEST_APK"
"$ADB" -s "$ANDROID_SERIAL" install -r "$APP_APK" 2>&1 | tee "$evidence_dir/install-app.log"
"$ADB" -s "$ANDROID_SERIAL" install -r "$TEST_APK" 2>&1 | tee "$evidence_dir/install-android-test.log"
"$ADB" -s "$ANDROID_SERIAL" shell cmd package wait-for-handler --timeout 60000
installed_runner="$("$ADB" -s "$ANDROID_SERIAL" shell pm list instrumentation | tr -d '\r')"
[[ "$installed_runner" == *"$INSTRUMENTATION_COMPONENT"* ]] \
  || fail "the installed instrumentation runner is missing: $INSTRUMENTATION_COMPONENT"
resolved_activity="$("$ADB" -s "$ANDROID_SERIAL" shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$APP_PACKAGE" \
  | tr -d '\r' | tail -n 1)"
[[ "$resolved_activity" == "$APP_PACKAGE/"* ]] \
  || fail "could not resolve the launchable activity for $APP_PACKAGE: ${resolved_activity:-<empty>}"
printf 'app_package=%s\ntest_package=%s\ninstrumentation=%s\nlaunchable_activity=%s\n' \
  "$APP_PACKAGE" "$TEST_PACKAGE" "$INSTRUMENTATION_COMPONENT" "$resolved_activity" \
  > "$evidence_dir/installed-test-packages.txt"
composer_instrumentation_args=()
if [[ "$FORCE_FIRST_POST_ATTACH_TAP_MISS" == 1 ]]; then
  composer_instrumentation_args+=(-e composerForceFirstPostAttachTapMiss true)
fi
if [[ -n "$COMPOSER_FOCUS_MAX_ATTEMPTS" ]]; then
  composer_instrumentation_args+=(-e composerFocusMaxAttempts "$COMPOSER_FOCUS_MAX_ATTEMPTS")
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
pocketshell_start_without_avd_lock_fd "$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime \
  -s PS2857Asset:I > "$asset_logcat" 2>&1
asset_logcat_pid="$POCKETSHELL_AVD_CHILD_PID"
sleep 0.2
kill -0 "$asset_logcat_pid" 2>/dev/null || fail 'could not start the live composer artifact logcat collector'
printf 'Running packaged composer Docker journey on %s (API %s), Docker port %s, sessions %s-*\n' \
  "$ANDROID_SERIAL" "$device_api" "$PORT" "$SESSION_BASE"

capture_phase_failure() {
  local phase="$1"
  local phase_dir="$evidence_dir/phase-$phase"
  stop_asset_logcat
  mkdir -p "$RESULTS_DIR"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 4000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  pocketshell_android_capture_input_diagnostics "$ADB" "$ANDROID_SERIAL" "$evidence_dir/phase-$phase-android-input"
  "$ROOT_DIR/scripts/check-android-input-diagnostics.py" --dir "$evidence_dir/phase-$phase-android-input" >&2 || true
  "$ROOT_DIR/scripts/extract-js-composer-artifacts.py" --preserve-on-failure \
    --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" \
    --output-dir "$evidence_dir" || true
  local report
  shopt -s nullglob
  for report in "$RESULTS_DIR"/TEST-*.xml; do cp -- "$report" "$phase_dir/"; done
  shopt -u nullglob
  for diagnostic in diagnostics-logcat.txt diagnostics-input-method.txt diagnostics-screen.png; do
    if [[ -f "$RESULTS_DIR/$diagnostic" ]]; then
      cp -- "$RESULTS_DIR/$diagnostic" "$evidence_dir/wrapper-$phase-$diagnostic"
    fi
  done
}

run_instrumentation_phase() {
  local phase="$1"
  local phase_dir="$evidence_dir/phase-$phase"
  "$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/test_key" "$DEVICE_KEY_PATH" >/dev/null
  local phase_args=(-e class "$test_class" -e sshHost 10.0.2.2
    -e sshPort "$PORT" -e sshPrivateKeyPath "$DEVICE_KEY_PATH"
    -e sshSessionName "$SESSION_BASE" -e artifactRunId "$ARTIFACT_RUN_ID"
    -e composerPhase "$phase")
  mkdir -p "$phase_dir"
  if [[ "$phase" == resume ]]; then
    phase_args+=(-e oldAppPid "$old_app_pid" -e stoppedAppStatus absent -e resolvedActivity "$resolved_activity")
  fi
  phase_args+=("${composer_instrumentation_args[@]}")
  python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY
  local instrumentation_status=0
  pocketshell_run_without_avd_lock_fd_to_log "$phase_dir/composer-instrumentation.log" \
    "$ADB" -s "$ANDROID_SERIAL" shell am instrument -w -r "${phase_args[@]}" \
    "$INSTRUMENTATION_COMPONENT" || instrumentation_status=$?
  if (( instrumentation_status != 0 )) || ! grep -q '^INSTRUMENTATION_CODE: -1$' "$phase_dir/composer-instrumentation.log"; then
    capture_phase_failure "$phase"
    fail "instrumentation phase $phase did not finish cleanly (adb status $instrumentation_status)"
  fi
  if ! "$ROOT_DIR/scripts/instrumentation-log-to-junit-xml.sh" \
      --log "$phase_dir/composer-instrumentation.log" \
      --out "$RESULTS_DIR/TEST-composer.xml" \
      --suite "$test_class/$phase" --require-class "$test_class"; then
    capture_phase_failure "$phase"
    fail "instrumentation phase $phase did not produce a non-skipped result for $test_class"
  fi
  if ! "$ROOT_DIR/scripts/check-js-composer-journey-results.py" --results-dir "$RESULTS_DIR"; then
    capture_phase_failure "$phase"
    fail "the exact packaged composer result gate failed during phase $phase"
  fi
  cp -- "$RESULTS_DIR/TEST-composer.xml" "$phase_dir/"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -s PS2885Storage:I \
    > "$phase_dir/snippet-storage-logcat.txt"
  printf 'phase=%s\nresult=PASS\n' "$phase" > "$phase_dir/phase-result.txt"
}

run_instrumentation_phase prepare

resolved_activity="$("$ADB" -s "$ANDROID_SERIAL" shell cmd package resolve-activity --brief \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$APP_PACKAGE" \
  | tr -d '\r' | tail -n 1)"
[[ "$resolved_activity" == "$APP_PACKAGE/"* ]] \
  || fail "could not re-resolve a launchable activity for $APP_PACKAGE: ${resolved_activity:-<empty>}"
current_app_pid="$("$ADB" -s "$ANDROID_SERIAL" shell pidof "$APP_PACKAGE" 2>/dev/null \
  | tr -d '\r' | awk 'NR == 1 { print $1 }' || true)"
if [[ -z "$current_app_pid" ]]; then
  "$ADB" -s "$ANDROID_SERIAL" shell am start -W -n "$resolved_activity" \
    | tee "$evidence_dir/app-before-external-force-stop.txt"
fi
old_app_pid=""
for _ in {1..20}; do
  old_app_pid="$("$ADB" -s "$ANDROID_SERIAL" shell pidof "$APP_PACKAGE" 2>/dev/null \
    | tr -d '\r' | awk 'NR == 1 { print $1 }' || true)"
  [[ -n "$old_app_pid" ]] && break
  sleep 0.25
done
[[ "$old_app_pid" =~ ^[0-9]+$ ]] \
  || fail "could not capture a numeric app PID before force-stop: ${old_app_pid:-<empty>}"
"$ADB" -s "$ANDROID_SERIAL" shell am force-stop "$APP_PACKAGE"
stopped_app_pid="$old_app_pid"
for _ in {1..20}; do
  stopped_app_pid="$("$ADB" -s "$ANDROID_SERIAL" shell pidof "$APP_PACKAGE" 2>/dev/null \
    | tr -d '\r' | awk 'NR == 1 { print $1 }' || true)"
  [[ -z "$stopped_app_pid" ]] && break
  sleep 0.25
done
[[ -z "$stopped_app_pid" ]] || fail "external force-stop did not stop $APP_PACKAGE (pid $stopped_app_pid)"
printf 'target_app_package=%s\nresolved_activity=%s\nold_pid=%s\nforce_stop=am-force-stop\nstopped_status=absent\n' \
  "$APP_PACKAGE" "$resolved_activity" "$old_app_pid" \
  | tee "$evidence_dir/snippet-process-boundary.txt"
run_instrumentation_phase resume

stop_asset_logcat
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1

"$ROOT_DIR/scripts/extract-js-composer-artifacts.py" \
  --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" --output-dir "$evidence_dir" \
  --expected-terminal-marker "PS2857_SENT_$SESSION_BASE"
sha256sum "$evidence_dir/composer-keyboard.png" | tee -a "$evidence_dir/composer-host-oracle.txt"
sha256sum "$evidence_dir/composer-post-send.png" | tee -a "$evidence_dir/composer-host-oracle.txt"

ssh_remote() {
  ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"
}

bytes_session="$SESSION_BASE-bytes"
uncertain_session="$SESSION_BASE-uncertain"
unicode_hex="$(ssh_remote "cat /tmp/$bytes_session-unicode.hex")"
multiline_hex="$(ssh_remote "od -An -tx1 /tmp/$bytes_session-multiline.raw | tr -d '[:space:]'")"
snippet_lines_hex="$(ssh_remote "od -An -tx1 /tmp/$bytes_session-snippet-lines.txt | tr -d '[:space:]'")"
snippet_before_send="$(ssh_remote "cat /tmp/$bytes_session-snippet-before-send.marker")"
[[ "$unicode_hex" == '636166c3a920f09fa7aa' ]] \
  || fail "remote Unicode bytes mismatch: expected 636166c3a920f09fa7aa, got ${unicode_hex:-<empty>}"
[[ "$multiline_hex" == '1b5b3230307e616c7068610aceb26574610af09f99821b5b3230317e' ]] \
  || fail "remote bracketed multiline bytes mismatch: expected 1b5b3230307e616c7068610aceb26574610af09f99821b5b3230317e, got ${multiline_hex:-<empty>}"
[[ "$snippet_lines_hex" == '616c7068610aceb26574610af09f99820a' ]] \
  || fail "remote literal snippet output mismatch: expected 616c7068610aceb26574610af09f99820a, got ${snippet_lines_hex:-<empty>}"
[[ "$snippet_before_send" == 'NOT_EXECUTED' ]] \
  || fail "selected snippet ran before explicit Send: Docker marker says ${snippet_before_send:-<empty>}"
printf 'PASS: remote Unicode PTY bytes %s\n' "$unicode_hex" | tee -a "$evidence_dir/composer-host-oracle.txt"
printf 'PASS: remote multiline PTY bytes %s\n' "$multiline_hex" | tee -a "$evidence_dir/composer-host-oracle.txt"
printf 'PASS: host file contains exact literal snippet output bytes %s\n' "$snippet_lines_hex" \
  | tee -a "$evidence_dir/composer-host-oracle.txt"
printf 'PASS: Docker check before explicit Send saw snippet command marker %s\n' "$snippet_before_send" \
  | tee -a "$evidence_dir/composer-host-oracle.txt"

sent_marker="PS2857_SENT_$SESSION_BASE"
sent_output_marker="$(ssh_remote "cat /tmp/$bytes_session-sent-output.marker | tr -d '\\n'")"
[[ "$sent_output_marker" == "$sent_marker" ]] \
  || fail "remote sent-output marker mismatch: expected $sent_marker, got ${sent_output_marker:-<empty>}"
printf 'PASS: host PTY output contained %s\n' "$sent_output_marker" | tee -a "$evidence_dir/composer-host-oracle.txt"

insert_marker="PS2857_INSERT_$SESSION_BASE"
insert_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$bytes_session' --bytes 4096")"
[[ "$insert_capture" == *"$insert_marker"* ]] || fail 'remote PTY history did not contain the inserted prompt line'
insert_file_state="$(ssh_remote "if test -e /tmp/$bytes_session-insert.marker; then printf present; else printf absent; fi")"
[[ "$insert_file_state" == absent ]] || fail 'Insert executed the command instead of leaving it at the prompt'
printf 'PASS: app Xterm and remote PTY history contain the inserted %s prompt line; marker file is absent\n' "$insert_marker" \
  | tee -a "$evidence_dir/composer-host-oracle.txt"

uncertain_marker="PS2857_UNCERTAIN_$SESSION_BASE"
uncertain_file_state="$(ssh_remote "if test -e /tmp/$uncertain_session-uncertain.marker; then printf present; else printf absent; fi")"
[[ "$uncertain_file_state" == absent ]] || fail 'uncertain delivery command ran after the transport drop'
printf 'PASS: uncertain command %s was not replayed after reconnect\n' "$uncertain_marker" \
  | tee -a "$evidence_dir/composer-host-oracle.txt"

# Issue #3060: Send during dictation waited for a final result SHORTER than the
# last partial and still delivered the whole utterance (the redirect is the tail
# the recognizer dropped), and the draft kept across screen-off, a scrim tap and
# the force-stop was never written to the PTY by any of those paths.
dictation_session="$SESSION_BASE-dictation"
tail_marker="PS3060_TAIL_$SESSION_BASE"
tail_state="$(ssh_remote "if test -e /tmp/$dictation_session-tail.marker; then cat /tmp/$dictation_session-tail.marker; else printf absent; fi")"
[[ "$tail_state" == "$tail_marker" ]] \
  || fail "dictated Send lost its tail: expected /tmp/$dictation_session-tail.marker to hold $tail_marker, got ${tail_state:-<empty>}"
dictation_capture="$(ssh_remote "a capture --workspace /home/testuser --tag '$dictation_session' --bytes 8192")"
[[ "$dictation_capture" == *"$tail_marker"* ]] || fail 'remote PTY history did not contain the dictated Send'
[[ "$dictation_capture" != *'dictated before the screen turned off'* ]] \
  || fail 'a draft kept across screen-off/scrim/restart was written to the PTY without an explicit Send'
printf 'PASS: dictated Send delivered the whole utterance (%s written) and the kept draft never reached the PTY\n' \
  "$tail_marker" | tee -a "$evidence_dir/composer-host-oracle.txt"

printf 'Evidence directory: %s\n' "$evidence_dir"
