#!/usr/bin/env bash
# Run the focused Voice settings acceptance journey against the packaged app.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="i2857voice"
RUN_ID="js2857voice-$(date -u '+%Y%m%dT%H%M%S')"
PREPARE_ONLY=0
TEST_ONLY=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-voice-settings.sh [--suffix TOKEN] [--run-id ID] [--prepare-only|--test-only]

Build and run the production Voice settings screen journey in the packaged
JS-first Android app. The journey enters BCP-47 language text and changes the
silence range through Android touch input, then verifies storage after route
navigation and a fresh Activity relaunch. It captures same-run screen,
DOM geometry, accessible semantics, and instrumentation XML evidence.

Options:
  --suffix TOKEN   Isolate this debug package (default: i2857voice)
  --run-id ID      Unique artifact identity (default: generated UTC timestamp)
  --prepare-only   Build app/androidTest APKs without touching an emulator
  --test-only      Run previously prepared APKs on API 35+ without rebuilding
  --help           Show this help
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$SUFFIX" =~ ^[A-Za-z0-9][A-Za-z0-9._]*$ ]] || fail 'suffix must start with a letter/digit and contain only letters, digits, dots, or underscores'
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,47}$ ]] || fail 'run id must be 3-48 letters, digits, dashes, or underscores'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
[[ -x "$ADB" ]] || fail "adb is not executable: $ADB"
[[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'generated android/gradlew is missing'

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-voice-settings.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-voice-settings.sh suffix=$SUFFIX run=$RUN_ID"

if [[ "$TEST_ONLY" != 1 ]]; then
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi

if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: Voice settings journey APKs prepared for suffix %s\n' "$SUFFIX"
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
  || fail "Voice settings journey requires API 35+; $ANDROID_SERIAL reports API ${device_api:-unknown}"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
ARTIFACTS_ROOT="${POCKETSHELL_ARTIFACTS_DIR:-/data/agents/pocketshell/artifacts/issue-2857/dictation-settings}"
ARTIFACT_DIR="$ARTIFACTS_ROOT/$RUN_ID"
APP_ID="com.pocketshell.app.$SUFFIX"
[[ ! -e "$ARTIFACT_DIR" ]] || fail "refusing to overwrite existing evidence: $ARTIFACT_DIR"
mkdir -p "$ARTIFACT_DIR"

python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys

results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

test_class='com.pocketshell.app.smoke.J24SettingsReachJourney'
test_method='voiceLanguageAndSilenceControlsPersistAcrossNavigationAndActivityRelaunch'
printf 'Installing isolated packaged app %s on %s (API %s)\n' "$APP_ID" "$ANDROID_SERIAL" "$device_api"
"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:installDebug \
  "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
"$ADB" -s "$ANDROID_SERIAL" shell pm clear "$APP_ID" >/dev/null
printf 'PASS: cleared only isolated app storage before this journey\n'
printf 'Running %s#%s with run id %s\n' "$test_class" "$test_method" "$RUN_ID"

test_exit_code=0
connected_log="$ARTIFACT_DIR/connected-gradle-test.log"
artifacts=(voice-settings-controls.png voice-settings-changed.png voice-settings-after-navigation.png \
  voice-settings-after-activity-relaunch.png voice-settings-auto-language.png \
  voice-settings-invalid-language.png voice-settings-journey.json)

# Gradle removes the target and test APKs during connected-test cleanup. Harvest
# app-private evidence while the packaged process is still under test.
collect_live_artifacts() {
  local file_list artifact temporary
  file_list="$("$ADB" -s "$ANDROID_SERIAL" shell run-as "$APP_ID" ls files 2>/dev/null | tr -d '\r' || true)"
  [[ -n "$file_list" ]] || return 0
  for artifact in "${artifacts[@]}"; do
    [[ "$file_list" == *"$artifact"* ]] || continue
    [[ "$artifact" == voice-settings-journey.json || ! -s "$ARTIFACT_DIR/$artifact" ]] || continue
    temporary="$ARTIFACT_DIR/.$artifact.part"
    if ! "$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$APP_ID" cat "files/$artifact" > "$temporary" 2>/dev/null; then
      python3 - "$temporary" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).unlink(missing_ok=True)
PY
      continue
    fi
    if python3 - "$temporary" "$artifact" <<'PY'
from pathlib import Path
import json
import sys

path, name = Path(sys.argv[1]), sys.argv[2]
data = path.read_bytes()
if name.endswith('.png'):
    valid = len(data) >= 1024 and data.startswith(b'\x89PNG\r\n\x1a\n') and data.endswith(b'\x00\x00\x00\x00IEND\xaeB`\x82')
else:
    try:
        valid = json.loads(data).get('schema') == 1
    except (ValueError, AttributeError):
        valid = False
raise SystemExit(0 if valid else 1)
PY
    then
      python3 - "$temporary" "$ARTIFACT_DIR/$artifact" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).replace(sys.argv[2])
PY
    else
      python3 - "$temporary" <<'PY'
from pathlib import Path
import sys
Path(sys.argv[1]).unlink(missing_ok=True)
PY
    fi
  done
}

"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
  "-PpocketshellAppIdSuffix=$SUFFIX" \
  "-Pandroid.testInstrumentationRunnerArguments.class=$test_class" \
  "-Pandroid.testInstrumentationRunnerArguments.artifactRunId=$RUN_ID" \
  --stacktrace --console=plain > "$connected_log" 2>&1 &
gradle_pid=$!
while kill -0 "$gradle_pid" 2>/dev/null; do
  collect_live_artifacts
  sleep 0.25
done
wait "$gradle_pid" || test_exit_code=$?
collect_live_artifacts
cat "$connected_log"

mkdir -p "$ARTIFACT_DIR/instrumentation-results"
if [[ -d "$RESULTS_DIR" ]]; then
  cp -a "$RESULTS_DIR/." "$ARTIFACT_DIR/instrumentation-results/"
fi
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 12000 > "$ARTIFACT_DIR/logcat.txt" 2>&1 || true

if (( test_exit_code != 0 )); then
  printf 'Connected Voice settings journey failed; preserving logs and any emitted screenshots at %s\n' "$ARTIFACT_DIR" >&2
  exit "$test_exit_code"
fi

python3 - "$RESULTS_DIR" "$test_class" "$test_method" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root_dir, wanted_class, wanted_method = Path(sys.argv[1]), sys.argv[2], sys.argv[3]
reports = sorted(root_dir.rglob('TEST-*.xml'))
if not reports:
    raise SystemExit(f'FAIL: no fresh instrumentation XML under {root_dir}')
cases = []
for report in reports:
    root = ET.parse(report).getroot()
    cases.extend(root.iter('testcase'))
matching = [case for case in cases if case.attrib.get('classname') == wanted_class and case.attrib.get('name') == wanted_method]
if len(cases) != 1 or len(matching) != 1:
    found = ', '.join(f"{case.attrib.get('classname')}#{case.attrib.get('name')}" for case in cases)
    raise SystemExit(f'FAIL: expected exactly {wanted_class}#{wanted_method}; found {found or "<none>"}')
case = matching[0]
if list(case.iter('failure')) or list(case.iter('error')) or list(case.iter('skipped')):
    raise SystemExit(f'FAIL: required Voice settings journey failed or skipped: {wanted_class}#{wanted_method}')
print(f'PASS: exact instrumentation result {wanted_class}#{wanted_method}')
PY

for required in voice-settings-controls.png voice-settings-changed.png voice-settings-after-navigation.png \
  voice-settings-after-activity-relaunch.png voice-settings-auto-language.png \
  voice-settings-invalid-language.png voice-settings-journey.json; do
  [[ -s "$ARTIFACT_DIR/$required" ]] || fail "journey passed but required same-run artifact is missing: $required"
done
sha256sum "$ARTIFACT_DIR"/*.png "$ARTIFACT_DIR/voice-settings-journey.json"
printf 'Evidence directory: %s\n' "$ARTIFACT_DIR"
