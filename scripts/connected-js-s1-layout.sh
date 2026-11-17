#!/usr/bin/env bash
# Packaged mobile layout proof (#3120, S1 of #3118): J26S1LayoutJourney at
# 412x915dp and 360x800dp on one emulator (`wm density`, always restored).
# It measures the live WebView DOM of the real packaged app - 48x48 CSS px hit
# targets under the real `pointer: coarse`, and the workspace bottom edge
# against the window height minus --ps-inset-bottom with non-zero native bar
# insets - against this run's own isolated agents-pool lane. The exact-result
# checker requires one passing journey per width plus non-empty evidence.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="i3120s1"
PORT=""
PREPARE_ONLY=0
TEST_ONLY=0

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-s1-layout.sh --port PORT [--suffix TOKEN] [--prepare-only|--test-only]

Build and run the packaged S1 layout journey (#3120) at 412x915dp and 360x800dp.

  --port PORT      an isolated, healthy agents-pool lane (scripts/agents-pool.sh up PORT); not 2222
  --suffix TOKEN   isolated debug package suffix (default: i3120s1)
  --prepare-only   build the suffixed app and androidTest APKs, no emulator
  --test-only      run the already-prepared APKs
Evidence (per-width JUnit, layout JSON, screenshots, logcat) lands in
android/app/build/outputs/js-s1-layout/. ANDROID_SERIAL picks the emulator.
USAGE
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix) [[ $# -ge 2 ]] || fail '--suffix needs a token'; SUFFIX="$2"; shift 2 ;;
    --port) [[ $# -ge 2 ]] || fail '--port needs a value'; PORT="$2"; shift 2 ;;
    --prepare-only) PREPARE_ONLY=1; shift ;;
    --test-only) TEST_ONLY=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail "suffix must match [A-Za-z0-9._]+ (got: $SUFFIX)"
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
[[ "$PREPARE_ONLY" == 1 || "$PORT" =~ ^[0-9]+$ ]] || fail '--port PORT is required to run the journey'
[[ "$PREPARE_ONLY" == 1 || "$PORT" != 2222 ]] || fail '--port must be an isolated agents-pool lane, not the shared 2222 fixture'
[[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'generated android/gradlew is missing; initialize the JS-first Android project first'
"$ROOT_DIR/scripts/check-js-s1-layout-results.py" --self-test

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/agents-pool.sh"
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-s1-layout.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-s1-layout.sh suffix=$SUFFIX"

if [[ "$TEST_ONLY" != 1 ]]; then
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi
if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: packaged JS app and androidTest APKs prepared for suffix %s\n' "$SUFFIX"
  exit 0
fi

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online[@]}"
  export ANDROID_SERIAL="${online[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] || fail "$ANDROID_SERIAL is not online"
api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" =~ ^[0-9]+$ ]] && (( api >= 35 )) || fail "needs API 35+; $ANDROID_SERIAL reports ${api:-unknown}"

port_lock_file="$(pocketshell_agents_lock_file_for_port "$ROOT_DIR" "$PORT")"
exec {port_lock_fd}>"$port_lock_file"
flock -n "$port_lock_fd" || fail "agents lane $PORT is claimed by another run"
container="$(pocketshell_agents_container_for_port "$PORT")"
[[ "$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || true)" == healthy ]] \
  || fail "agents lane $PORT must be healthy (scripts/agents-pool.sh up $PORT)"

OUT="$ROOT_DIR/android/app/build/outputs/js-s1-layout"
RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
python3 - "$OUT" <<'PY'
from pathlib import Path
import shutil, sys
p = Path(sys.argv[1])
if p.is_dir():
    shutil.rmtree(p)
PY
mkdir -p "$OUT"
key_copy="$ROOT_DIR/android/app/build/outputs/js-s1-layout-fixture-key"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$key_copy"
ssh_opts=(-i "$key_copy" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
fixture() { ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"; }
SESSION_RUN="ps3120-$(date +%s)-$RANDOM"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
on_exit() {
  "$ADB" -s "$ANDROID_SERIAL" shell wm density reset >/dev/null 2>&1 || true
  fixture 'for s in $(ls -d ~/ps-s1-* 2>/dev/null); do rm -rf "$s"; done' >/dev/null 2>&1 || true
  pocketshell_release_all
}
trap on_exit EXIT
printf 'emulator %s %s @%s\n' "$ANDROID_SERIAL" "$("$ADB" -s "$ANDROID_SERIAL" shell wm size | tr -d '\r' | tr '\n' ' ')" \
  "$("$ADB" -s "$ANDROID_SERIAL" shell wm density | tr -d '\r' | tr '\n' ' ')" | tee "$OUT/device.txt"

DEVICE_KEY_PATH="/data/local/tmp/pocketshell-$SUFFIX-s1-key.pem"
agents_host_keys="$("$ROOT_DIR/scripts/shared-app-host-key-fixture.py" keys --port "$PORT")" || fail "no host keys on port $PORT"
pocketshell_android_input_preflight "$ADB" "$ANDROID_SERIAL" "$OUT/input-preflight.txt" || fail 'Android input preflight failed'

run_one() {
  local label="$1" density="$2" rc=0 f
  "$ADB" -s "$ANDROID_SERIAL" shell wm density "$density" >/dev/null
  sleep 3
  printf '== %s: %s\n' "$label" "$("$ADB" -s "$ANDROID_SERIAL" shell wm density | tr -d '\r' | tr '\n' ' ')"
  "$ADB" -s "$ANDROID_SERIAL" logcat -c
  # The test support consumes (deletes) the staged key, so every width stages its own copy.
  "$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/test_key" "$DEVICE_KEY_PATH" >/dev/null
  for f in $("$ADB" -s "$ANDROID_SERIAL" shell ls /data/local/tmp | tr -d '\r' | grep "^ps2952-s1-" || true); do
    "$ADB" -s "$ANDROID_SERIAL" shell rm -f "/data/local/tmp/$f" >/dev/null
  done
  rm -rf -- "$RESULTS_DIR"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
      "-PpocketshellAppIdSuffix=$SUFFIX" \
      -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.J26S1LayoutJourney \
      "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
      "-Pandroid.testInstrumentationRunnerArguments.sessionRun=$SESSION_RUN-${label//x/-}" \
      "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyPath=$DEVICE_KEY_PATH" \
      "-Pandroid.testInstrumentationRunnerArguments.agentsHostKeysBase64=$(printf '%s' "$agents_host_keys" | base64 -w0)" \
      "-Pandroid.testInstrumentationRunnerArguments.layoutLabel=$label" \
      --stacktrace --console=plain || rc=$?
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime | grep -a 'S1_LAYOUT_CHUNK\|S1_VIOLATION\|S1_EVIDENCE_SAVE_FAILED' > "$OUT/logcat-$label.log" || true
  for f in $("$ADB" -s "$ANDROID_SERIAL" shell ls /data/local/tmp | tr -d '\r' | grep "^ps2952-s1-" || true); do
    "$ADB" -s "$ANDROID_SERIAL" pull "/data/local/tmp/$f" "$OUT/${f#ps2952-}" >/dev/null
    "$ADB" -s "$ANDROID_SERIAL" shell rm -f "/data/local/tmp/$f" >/dev/null
  done
  if [[ -f "$OUT/s1-layout-$label.json" ]]; then
    mv -- "$OUT/s1-layout-$label.json" "$OUT/layout-$label.json"
  else
    # No pulled file: reconstruct from the logcat S1_LAYOUT_JSON line, only if it is complete valid JSON.
    # A truncated line leaves the file absent, so the checker still fails closed.
    python3 - "$OUT/logcat-$label.log" "$OUT/layout-$label.json" "$label" <<'RECON' || true
import base64, json, re, sys
log, out, label = sys.argv[1:4]
pat = re.compile(r"S1_LAYOUT_CHUNK " + re.escape(label) + r" (\d+)/(\d+) (\S+)")
chunks, total = {}, None
for line in open(log, encoding="utf-8", errors="replace").read().splitlines():
    m = pat.search(line)
    if m:
        chunks[int(m.group(1))] = m.group(3)
        total = int(m.group(2))
# Only a complete chunk set that decodes to valid JSON becomes evidence; anything else leaves the file absent.
if total and sorted(chunks) == list(range(total)):
    try:
        data = json.loads(b"".join(base64.b64decode(chunks[i]) for i in range(total)).decode("utf-8"))
    except ValueError:
        sys.exit(0)
    open(out, "w", encoding="utf-8").write(json.dumps(data, indent=2))
RECON
  fi
  mkdir -p "$OUT/junit-$label"
  cp -R "$RESULTS_DIR/." "$OUT/junit-$label/" 2>/dev/null || true
  return "$rc"
}

# 1080px wide: 420dpi = 411dp (the 412x915 class), 480dpi = 360dp (the 360x800 class).
status=0
run_one 412x915 420 || status=$?
run_one 360x800 480 || status=$?
check_status=0
"$ROOT_DIR/scripts/check-js-s1-layout-results.py" --results-dir "$OUT" || check_status=$?
printf 'S1 layout lane: gradle=%s checker=%s evidence=%s\n' "$status" "$check_status" "$OUT"
(( status == 0 && check_status == 0 )) || exit 1
