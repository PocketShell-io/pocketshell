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
FORCE_FIRST_POST_ATTACH_TAP_MISS="false"
PROMPT_FOCUS_MAX_ATTEMPTS="2"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-hotkeys-docker.sh --port 2243|2244|2245 [--session-prefix NAME] [--suffix TOKEN] [--force-first-post-attach-tap-miss] [--prompt-focus-max-attempts 1|2]

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
    --force-first-post-attach-tap-miss)
      FORCE_FIRST_POST_ATTACH_TAP_MISS="true"
      shift
      ;;
    --prompt-focus-max-attempts)
      [[ $# -ge 2 ]] || fail '--prompt-focus-max-attempts needs a value of 1 or 2'
      PROMPT_FOCUS_MAX_ATTEMPTS="$2"
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
[[ "$PROMPT_FOCUS_MAX_ATTEMPTS" =~ ^[12]$ ]] || fail '--prompt-focus-max-attempts must be 1 or 2'
ARTIFACT_RUN_ID="${SESSION_BASE}-$(date +%s%N)"
evidence_dir="$ROOT_DIR/android/app/build/outputs/js-hotkeys/$ARTIFACT_RUN_ID"
mkdir -p "$evidence_dir"
cat > "$evidence_dir/hotkeys-run-metadata.txt" <<EOF
run_id=$ARTIFACT_RUN_ID
session_prefix=$SESSION_BASE
docker_port=$PORT
app_suffix=$SUFFIX
force_first_post_attach_tap_miss=$FORCE_FIRST_POST_ATTACH_TAP_MISS
prompt_focus_max_attempts=$PROMPT_FOCUS_MAX_ATTEMPTS
started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)
EOF
[[ -x "$ADB" ]] || fail "adb is missing or not executable: $ADB"
[[ -f "$ROOT_DIR/tests/docker/test_key" ]] || fail 'Docker fixture test key is missing'

"$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py" --self-test
"$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py" --self-test
python3 "$ROOT_DIR/scripts/check-js-hotkeys-pty-geometry.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"

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
ssh_remote() { ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"; }
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
pocketshell_android_input_preflight "$ADB" "$ANDROID_SERIAL" "$evidence_dir/input-preflight.txt" \
  || fail "Android input preflight failed on $ANDROID_SERIAL; see $evidence_dir/input-preflight.txt"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

DEVICE_KEY_PATH="/data/local/tmp/pocketshell-$SUFFIX-key.pem"
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
write_run_exit_metadata() {
  local run_exit_code="$1"
  python3 - "$evidence_dir/hotkeys-run-metadata.txt" "$run_exit_code" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
exit_code = sys.argv[2]
preserved = [line for line in path.read_text(encoding="utf-8").splitlines()
             if not line.startswith(("exit_code=", "finished_utc="))]
preserved.extend((f"exit_code={exit_code}",))
path.write_text("\n".join(preserved) + "\n", encoding="utf-8")
PY
  printf 'finished_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$evidence_dir/hotkeys-run-metadata.txt"
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
    shopt -s nullglob
    local test_artifact
    for test_artifact in "$RESULTS_DIR"/*/logcat-*.txt "$RESULTS_DIR"/*/test-result.textproto; do
      if [[ -f "$test_artifact" ]]; then
        cp -- "$test_artifact" "$evidence_dir/wrapper-$(basename -- "$test_artifact")"
      fi
    done
    shopt -u nullglob
    for diagnostic in diagnostics-logcat.txt diagnostics-input-method.txt diagnostics-screen.png; do
      if [[ -f "$RESULTS_DIR/$diagnostic" ]]; then cp -- "$RESULTS_DIR/$diagnostic" "$evidence_dir/wrapper-$diagnostic"; fi
    done
  fi
  write_run_exit_metadata "$exit_status"
  if [[ -d "$RESULTS_DIR" ]]; then
    cp -- "$evidence_dir/hotkeys-run-metadata.txt" "$RESULTS_DIR/hotkeys-run-metadata.txt"
  fi
  pocketshell_release_all
  exit "$exit_status"
}
trap finish_hotkeys_run EXIT

prepare_asset_logcat_path "$asset_logcat"
[[ "$asset_logcat" != "$RESULTS_DIR/"* ]] || fail 'live artifact collector output must survive Gradle result cleanup'
printf 'PASS: live artifact collector output is writable and outside Gradle result cleanup\n'
"$ADB" -s "$ANDROID_SERIAL" logcat -c
pocketshell_start_without_avd_lock_fd "$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime \
  -s PS2884Asset:I PS2884Geometry:I PS2884DictationFailure:I PS2897Prompt:I > "$asset_logcat" 2>&1
asset_logcat_pid="$POCKETSHELL_AVD_CHILD_PID"
sleep 0.2
kill -0 "$asset_logcat_pid" 2>/dev/null || fail 'could not start the live fast-key artifact logcat collector'
printf 'Running packaged fast-key Docker journey on %s (API %s), Docker port %s, sessions %s-*.\n' \
  "$ANDROID_SERIAL" "$device_api" "$PORT" "$SESSION_BASE"
"$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/test_key" "$DEVICE_KEY_PATH" >/dev/null
if pocketshell_run_without_avd_lock_fd_to_log "$evidence_dir/hotkeys-gradle.log" \
    "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyPath=$DEVICE_KEY_PATH" \
    "-Pandroid.testInstrumentationRunnerArguments.sshSessionName=$SESSION_BASE" \
    "-Pandroid.testInstrumentationRunnerArguments.artifactRunId=$ARTIFACT_RUN_ID" \
    "-Pandroid.testInstrumentationRunnerArguments.fastKeysForceFirstPostAttachTapMiss=$FORCE_FIRST_POST_ATTACH_TAP_MISS" \
    "-Pandroid.testInstrumentationRunnerArguments.fastKeysPromptFocusMaxAttempts=$PROMPT_FOCUS_MAX_ATTEMPTS" \
    --stacktrace --console=plain; then
  :
else
  test_exit_code=$?
  stop_asset_logcat
  failure_raw="/tmp/$SESSION_BASE-keys-dictation.raw"
  failure_geometry="${failure_raw}.geometry"
  failure_oracle="$evidence_dir/hotkeys-host-failure-diagnostics.txt"
  {
    printf 'run_id=%s\nsession_prefix=%s\n' "$ARTIFACT_RUN_ID" "$SESSION_BASE"
    ssh_remote "if [ -f '$failure_raw' ]; then printf 'raw_bytes='; wc -c < '$failure_raw'; printf 'raw_sha256='; sha256sum '$failure_raw'; printf 'raw_hex='; od -An -tx1 '$failure_raw' | tr -d '[:space:]'; printf '\\n'; else printf 'raw_file_missing=%s\\n' '$failure_raw'; fi" \
      || printf 'raw_host_query_failed=1\n'
    ssh_remote "if [ -f '$failure_geometry' ]; then printf 'geometry_samples='; wc -l < '$failure_geometry'; printf 'geometry_sha256='; sha256sum '$failure_geometry'; printf 'geometry_size_counts=\\n'; sort '$failure_geometry' | uniq -c; else printf 'geometry_file_missing=%s\\n' '$failure_geometry'; fi" \
      || printf 'geometry_host_query_failed=1\n'
  } > "$failure_oracle" 2>&1
  printf 'Preserved failure-mode host receiver diagnostics: %s\n' "$failure_oracle"
  mkdir -p "$RESULTS_DIR"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 5000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  pocketshell_android_capture_input_diagnostics "$ADB" "$ANDROID_SERIAL" "$evidence_dir/failure-android-input"
  "$ROOT_DIR/scripts/check-android-input-diagnostics.py" --dir "$evidence_dir/failure-android-input" >&2 || true
  "$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py" --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" \
    --output-dir "$evidence_dir" --preserve-test-failure || true
  exit "$test_exit_code"
fi

stop_asset_logcat
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1

# A @Ignore'd (D36-quarantined) journey legitimately produces no PTY evidence,
# so the host-side byte oracle below has nothing to verify. Route on the exact
# journey's JUnit outcome: only a skip sanctioned by the fail-closed
# check-js-hotkeys-journey-results.py contract (an unexpired, well-formed
# journey-quarantine.txt row) may exit green here; anything else fails.
journey_junit_outcome="$(python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

REQUIRED_CLASS = "com.pocketshell.app.smoke.JsFastKeysDockerJourneyTest"
REQUIRED_METHOD = "fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect"

results = Path(sys.argv[1])
outcomes = []
for path in sorted(results.rglob("TEST-*.xml")):
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise SystemExit(f"FAIL: could not parse {path}: {exc}")
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    for suite in suites:
        for case in suite.findall("testcase"):
            if (case.attrib.get("classname"), case.attrib.get("name")) != (REQUIRED_CLASS, REQUIRED_METHOD):
                continue
            if list(case.iter("skipped")):
                outcomes.append("skipped")
            elif list(case.iter("failure")) or list(case.iter("error")):
                outcomes.append("failed")
            else:
                outcomes.append("passed")
if not outcomes:
    print("missing")
elif len(outcomes) == 1:
    print(outcomes[0])
else:
    print("duplicate")
PY
)"
case "$journey_junit_outcome" in
  passed)
    : # the executed journey re-verified host-side below
    ;;
  skipped)
    write_run_exit_metadata 0
    cp -- "$evidence_dir/hotkeys-run-metadata.txt" "$RESULTS_DIR/hotkeys-run-metadata.txt"
    if ! "$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py" --results-dir "$RESULTS_DIR"; then
      fail 'fast-key journey was skipped without an unexpired D36 quarantine row'
    fi
    printf 'Evidence directory: %s\n' "$evidence_dir"
    exit 0
    ;;
  *)
    fail "packaged fast-key journey JUnit outcome is ${journey_junit_outcome:-unreadable} despite a green gradle run"
    ;;
esac

"$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py" \
  --run-id "$ARTIFACT_RUN_ID" --logcat "$asset_logcat" --output-dir "$evidence_dir"

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
geometry_oracle_file = dictation.get("geometryOracleFile")
expected_hex = dictation.get("expectedHostHex")
expected_count = dictation.get("expectedByteCount")
if not isinstance(raw_file, str) or not re.fullmatch(r"/tmp/[A-Za-z0-9._-]+-keys-dictation\.raw", raw_file):
    raise SystemExit("FAIL: dictation raw file path is unsafe or malformed")
if not isinstance(expected_hex, str) or not re.fullmatch(r"(?:[0-9a-f]{2})+", expected_hex):
    raise SystemExit("FAIL: dictation expected host bytes are unsafe or malformed")
if isinstance(expected_count, bool) or not isinstance(expected_count, int) or expected_count != len(expected_hex) // 2:
    raise SystemExit("FAIL: dictation byte count does not match the journey manifest")
if geometry_oracle_file != f"{raw_file}.geometry":
    raise SystemExit("FAIL: dictation PTY geometry oracle path does not match its raw byte receiver")
print(f"{raw_file}\t{expected_hex}\t{expected_count}\t{geometry_oracle_file}")
PY
)" || fail 'could not read the integrated dictation host byte oracle'
IFS=$'\t' read -r dictation_raw_path expected_dictation_hex expected_dictation_count dictation_geometry_path <<< "$dictation_oracle"
[[ "$dictation_raw_path" == "/tmp/$dictation_raw" ]] || fail "unexpected dictation raw file: ${dictation_raw_path:-<empty>}"
[[ "$dictation_geometry_path" == "/tmp/$dictation_raw.geometry" ]] || fail "unexpected dictation geometry path: ${dictation_geometry_path:-<empty>}"
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
dictation_geometry_samples="$evidence_dir/dictation-pty-geometry-samples.txt"
ssh_remote "test -s '$dictation_geometry_path' && cat -- '$dictation_geometry_path'" > "$dictation_geometry_samples" \
  || fail 'independent remote PTY geometry sample file is missing or unreadable'
python3 "$ROOT_DIR/scripts/check-js-hotkeys-pty-geometry.py" \
  --journey "$evidence_dir/fastkeys-journey.json" --samples "$dictation_geometry_samples" \
  | tee "$evidence_dir/hotkeys-pty-geometry-oracle.txt"
{
  printf 'PASS: first live session exact PTY bytes (%s bytes): %s\n' "$first_count" "$first_hex"
  printf 'PASS: reattached live session exact PTY bytes (%s bytes): %s\n' "$resumed_count" "$resumed_hex"
  printf 'PASS: docked dictation exact PTY bytes (%s bytes): %s\n' "$dictation_count" "$dictation_hex"
  printf 'screenshot_sha256=\n'; sha256sum "$evidence_dir"/*.png
} | tee "$evidence_dir/hotkeys-host-oracle.txt"

write_run_exit_metadata 0
cp -- "$evidence_dir/hotkeys-run-metadata.txt" "$RESULTS_DIR/hotkeys-run-metadata.txt"
"$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py" --results-dir "$RESULTS_DIR"
printf 'Evidence directory: %s\n' "$evidence_dir"
