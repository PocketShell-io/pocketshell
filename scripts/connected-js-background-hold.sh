#!/usr/bin/env bash
# Packaged background grace hold journey (#3160) against an isolated
# agents-pool fixture. The app runs WITHOUT instrumentation: an instrumented
# process is pinned to a foreground-service state by ActivityManager, so it is
# never frozen or network-blocked in the background and cannot show the bug.
# The journey drives the WebView over its DevTools socket from the host
# (scripts/js-background-hold-journey.mjs) and presses the real HOME key.
set -euo pipefail
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX=i3160hold
PORT=""
RUN_ID=""
CASES=""
PREPARE_ONLY=0
TEST_ONLY=0
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-background-hold.sh --port PORT [options]

Background grace hold journey (#3160): HOME within grace keeps the same SSH
connection and PTY, the Doze variant too, beyond grace re-attaches the same
session with its scrollback, and a reconnect that gave up while the network was
unusable recovers by itself when it returns. The fixture must be an isolated,
healthy agents-pool lane (scripts/agents-pool.sh up PORT).

Options:
  --port PORT       agents-pool fixture port (never 2222)
  --suffix TOKEN    debug package suffix (default: i3160hold)
  --run-id ID       5-31 lowercase tag-safe characters (default: generated)
  --cases LIST      comma-separated subset (default: all four)
  --prepare-only    build the APK only
  --test-only       run the previously built APK
USAGE
}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix) SUFFIX="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --run-id) RUN_ID="$2"; shift 2;;
    --cases) CASES="$2"; shift 2;;
    --prepare-only) PREPARE_ONLY=1; shift;;
    --test-only) TEST_ONLY=1; shift;;
    --help|-h) usage; exit 0;;
    *) fail "unknown argument $1";;
  esac
done
[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail 'invalid suffix'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail 'prepare-only and test-only conflict'
"$ROOT_DIR/scripts/check-js-background-hold-results.py" --self-test
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/agents-pool.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" connected-js-background-hold.sh
APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
APK_COPY="$ROOT_DIR/android/app/build/outputs/lane-apks/app-debug-$SUFFIX.apk"
if [[ "$TEST_ONLY" != 1 ]]; then
  pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-background-hold.sh suffix=$SUFFIX"
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  mkdir -p "$(dirname "$APK_COPY")"
  # Keep this lane's own APK: later lanes rebuild the shared output path.
  cp "$APK" "$APK_COPY"
fi
if [[ "$PREPARE_ONLY" == 1 ]]; then exit 0; fi
[[ -f "$APK_COPY" ]] || fail "no prepared APK for suffix $SUFFIX: $APK_COPY"
[[ "$PORT" =~ ^[0-9]+$ && "$PORT" != 2222 ]] || fail 'an isolated healthy agents-pool port is required'
command -v node >/dev/null 2>&1 || fail 'node is required to drive the WebView over CDP'
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t devices < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2=="device" {print $1}')
  (( ${#devices[@]} == 1 )) || fail 'select one emulator with ANDROID_SERIAL'
  export ANDROID_SERIAL="${devices[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state)" == device ]] || fail 'emulator offline'
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) || fail "background hold journey requires API 35+; got ${device_api:-unknown}"
port_lock_file="$(pocketshell_agents_lock_file_for_port "$ROOT_DIR" "$PORT")"
exec {port_lock_fd}>"$port_lock_file"
flock -n "$port_lock_fd" || fail 'fixture port already claimed'
container="$(pocketshell_agents_container_for_port "$PORT")"
[[ "$(docker inspect --format='{{.State.Health.Status}}' "$container")" == healthy ]] || fail 'fixture must be healthy'
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
RUN="${RUN_ID:-bg$(date +%s | tail -c 8)$RANDOM}"
[[ "$RUN" =~ ^[a-z0-9][a-z0-9-]{4,30}$ ]] || fail "invalid --run-id"
APP_ID="com.pocketshell.app.$SUFFIX"
EVIDENCE_DIR="$ROOT_DIR/android/app/build/outputs/js-background-hold/$RUN"
[[ ! -e "$EVIDENCE_DIR" ]] || fail "refusing to overwrite existing same-run evidence: $EVIDENCE_DIR"
mkdir -p "$EVIDENCE_DIR"
LIFECYCLE_LOGCAT_PID=""
restore_device() {
  [[ -n "$LIFECYCLE_LOGCAT_PID" ]] && kill "$LIFECYCLE_LOGCAT_PID" 2>/dev/null
  # Never leave the shared emulator offline, idle or unplugged.
  "$ADB" -s "$ANDROID_SERIAL" shell cmd connectivity airplane-mode disable >/dev/null 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys battery reset >/dev/null 2>&1 || true
  docker exec -u testuser "$container" sh -lc \
    "PATH=\"\$HOME/.local/bin:\$PATH\" pocketshell sessions kill -- shell-$RUN >/dev/null 2>&1" || true
  "$ADB" -s "$ANDROID_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1 || true
  pocketshell_release_all
}
trap restore_device EXIT
"$ADB" -s "$ANDROID_SERIAL" uninstall "$APP_ID" >/dev/null 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" install -r -t "$APK_COPY" >/dev/null
"$ADB" -s "$ANDROID_SERIAL" logcat -b all -c || true
# The whole run's lifecycle trail: hold decisions, FGS starts/stops, activity
# state, crashes. The end-of-run dump is capped and loses the early cases.
"$ADB" -s "$ANDROID_SERIAL" logcat -v threadtime PocketShellHold:V ActivityManager:I ActivityTaskManager:I \
  Capacitor:I AndroidRuntime:E '*:S' > "$EVIDENCE_DIR/lifecycle-logcat.txt" 2>&1 &
LIFECYCLE_LOGCAT_PID=$!
set +e
node "$ROOT_DIR/scripts/js-background-hold-journey.mjs" --adb "$ADB" --serial "$ANDROID_SERIAL" \
  --package "$APP_ID" --port "$PORT" --container "$container" --key "$ROOT_DIR/tests/docker/test_key" \
  --run-id "$RUN" --out "$EVIDENCE_DIR" ${CASES:+--cases "$CASES"} 2>&1 | tee "$EVIDENCE_DIR/journey.log"
status=${PIPESTATUS[0]}
set -e
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime -t 8000 > "$EVIDENCE_DIR/logcat.txt" 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" logcat -d -b crash > "$EVIDENCE_DIR/crash-logcat.txt" 2>&1 || true
"$ADB" -s "$ANDROID_SERIAL" shell dumpsys notification --noredact > "$EVIDENCE_DIR/notifications-end.txt" 2>&1 || true
docker logs --timestamps "$container" > "$EVIDENCE_DIR/docker-agents.log" 2>&1 || true
check_args=(--results-dir "$EVIDENCE_DIR")
[[ -n "$CASES" ]] && check_args+=(--cases "$CASES")
"$ROOT_DIR/scripts/check-js-background-hold-results.py" "${check_args[@]}" || exit 1
(( status == 0 )) || exit "$status"
printf 'PASS: background grace hold evidence is in %s\n' "$EVIDENCE_DIR"
