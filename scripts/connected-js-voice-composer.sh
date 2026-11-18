#!/usr/bin/env bash
# Packaged one-tap voice composer proof (#3128): J32VoiceComposerJourney on the
# shared shell at the Pixel 7a phone class (411x914dp: 1080x2400 at 420dpi)
# with gesture navigation, against this run's own session on an isolated
# agents-pool lane (--port, never 2222). The session runs a raw-mode byte reader
# on the Docker host; after the journey the runner reads that record over its
# OWN SSH connection (not the app's) and the exact-result checker requires the
# three tests plus exactly the Insert bytes (no Enter) and the Send bytes (one
# CR), with nothing from the HOME-while-listening phase.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
SUFFIX="i3128voice"
PORT=""
PREPARE_ONLY=0
TEST_ONLY=0

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-voice-composer.sh --port PORT [--suffix TOKEN] [--prepare-only|--test-only]

Build and run the packaged one-tap voice composer journey (#3128).

  --port PORT      an isolated, healthy agents-pool lane (scripts/agents-pool.sh up PORT); not 2222
  --suffix TOKEN   isolated debug package suffix (default: i3128voice)
  --prepare-only   build the suffixed app and androidTest APKs, no emulator
  --test-only      run the already-prepared APKs
Evidence (JUnit, host byte record, screenshots, logcat) lands in
android/app/build/outputs/js-voice-composer/. ANDROID_SERIAL picks the emulator;
the display is set to 1080x2400 @ 420dpi with gesture navigation and restored.
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
"$ROOT_DIR/scripts/check-js-voice-composer-results.py" --self-test

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/agents-pool.sh"
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-voice-composer.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-voice-composer.sh suffix=$SUFFIX"

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

OUT="$ROOT_DIR/android/app/build/outputs/js-voice-composer"
RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
python3 - "$OUT" "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil, sys
for p in map(Path, sys.argv[1:]):
    if p.is_dir():
        shutil.rmtree(p)
PY
mkdir -p "$OUT"
key_copy="$ROOT_DIR/android/app/build/outputs/js-voice-composer-fixture-key"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$key_copy"
ssh_opts=(-i "$key_copy" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
fixture() { ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"; }

# This run's own session, in its own folder, with the host's byte reader staged
# next to it (the journey starts it in the pane; it appends every PTY byte).
SESSION_RUN="ps3128-$(date +%s)-$RANDOM"
FOLDER="$SESSION_RUN-voice"
fixture "mkdir -p ~/$FOLDER && pocketshell sessions create --json --cwd ~/$FOLDER -- $FOLDER >/dev/null" \
  || fail "could not create fixture session $FOLDER on lane $PORT"
# a4: a second session in the same folder, the tab the journey switches to.
fixture "pocketshell sessions create --json --cwd ~/$FOLDER -- $SESSION_RUN-other >/dev/null" \
  || fail "could not create fixture session $SESSION_RUN-other on lane $PORT"
fixture "cat > ~/$FOLDER/ps3128-reader.py" <<'READER' || fail "could not stage the host byte reader on lane $PORT"
# #3128 host oracle: raw mode (no echo, no line editing, no CR/NL translation,
# no signals), append every byte this PTY receives to argv[1], flushed per read.
# It also counts SIGWINCH (every remote resize of this PTY) into argv[1].winch,
# and prints N lines of OUTPUT when the journey drops N into argv[1].fill, so a
# test can put the cursor on the bottom row without sending a single input byte.
import os, signal, sys, termios, threading, time, tty
out = os.path.expanduser(sys.argv[1])
fd = sys.stdin.fileno()
tty.setraw(fd)
winches = 0
def on_winch(_signum, _frame):
    global winches
    winches += 1
    with open(out + ".winch", "w") as count:
        count.write(str(winches))
signal.signal(signal.SIGWINCH, on_winch)
with open(out + ".winch", "w") as count:
    count.write("0")
def fill():
    while True:
        time.sleep(0.2)
        try:
            with open(out + ".fill") as request:
                n = int(request.read().strip() or "0")
            os.remove(out + ".fill")
        except (OSError, ValueError):
            continue
        for i in range(1, n + 1):
            os.write(1, b"PS3128_FILL %d\r\n" % i)
        os.write(1, b"PS3128_FILL_DONE\r\n")
threading.Thread(target=fill, daemon=True).start()
with open(out + ".pid", "w") as pid:
    pid.write(str(os.getpid()))
with open(out + ".tty", "w") as tty_name:
    tty_name.write(os.ttyname(fd))
with open(out, "ab") as record:
    os.write(1, b"PS3128_READY\r\n")
    while True:
        data = os.read(fd, 1024)
        if not data:
            break
        record.write(data)
        record.flush()
READER

PKG="com.pocketshell.app.$SUFFIX"
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
nav_before="$("$ADB" -s "$ANDROID_SERIAL" shell cmd overlay list android 2>/dev/null | tr -d '\r' | awk '/navbar/ && /\[x\]/ { print $2 }' | head -1)"
on_exit() {
  "$ADB" -s "$ANDROID_SERIAL" shell wm density reset >/dev/null 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell wm size reset >/dev/null 2>&1 || true
  if [[ -n "$nav_before" && "$nav_before" != com.android.internal.systemui.navbar.gestural ]]; then
    "$ADB" -s "$ANDROID_SERIAL" shell cmd overlay enable-exclusive --category "$nav_before" >/dev/null 2>&1 || true
  fi
  fixture "pocketshell sessions kill -- $FOLDER:$FOLDER >/dev/null 2>&1; pocketshell sessions kill -- $FOLDER:$SESSION_RUN-other >/dev/null 2>&1; rm -rf ~/$FOLDER" >/dev/null 2>&1 || true
  pocketshell_release_all
}
trap on_exit EXIT

# The Pixel 7a class (#3118 drafts): 1080x2400 at 420dpi = 411x914dp, gesture navigation.
"$ADB" -s "$ANDROID_SERIAL" shell wm size 1080x2400 >/dev/null
"$ADB" -s "$ANDROID_SERIAL" shell wm density 420 >/dev/null
"$ADB" -s "$ANDROID_SERIAL" shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural >/dev/null 2>&1 || true
sleep 3
printf 'emulator %s | %s | %s | nav %s\n' "$ANDROID_SERIAL" \
  "$("$ADB" -s "$ANDROID_SERIAL" shell wm size | tr -d '\r' | tr '\n' ' ')" \
  "$("$ADB" -s "$ANDROID_SERIAL" shell wm density | tr -d '\r' | tr '\n' ' ')" \
  "$("$ADB" -s "$ANDROID_SERIAL" shell cmd overlay list android | tr -d '\r' | awk '/navbar/ && /\[x\]/ { print $2 }' | tr '\n' ' ')" \
  | tee "$OUT/device.txt"

# First use must meet Android's RECORD_AUDIO prompt: no grant left from a
# previous run's install (an interrupted run can leave the package behind).
"$ADB" -s "$ANDROID_SERIAL" uninstall "$PKG" >/dev/null 2>&1 || true
for f in $("$ADB" -s "$ANDROID_SERIAL" shell ls /data/local/tmp | tr -d '\r' | grep '^ps2952-j32-' || true); do
  "$ADB" -s "$ANDROID_SERIAL" shell rm -f "/data/local/tmp/$f" >/dev/null
done
DEVICE_KEY_PATH="/data/local/tmp/pocketshell-$SUFFIX-voice-key.pem"
"$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/test_key" "$DEVICE_KEY_PATH" >/dev/null
agents_host_keys="$("$ROOT_DIR/scripts/shared-app-host-key-fixture.py" keys --port "$PORT")" || fail "no host keys on port $PORT"
pocketshell_android_input_preflight "$ADB" "$ANDROID_SERIAL" "$OUT/input-preflight.txt" || fail 'Android input preflight failed'
"$ADB" -s "$ANDROID_SERIAL" logcat -c

status=0
"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.J32VoiceComposerJourney \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sessionRun=$SESSION_RUN" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyPath=$DEVICE_KEY_PATH" \
    "-Pandroid.testInstrumentationRunnerArguments.agentsHostKeysBase64=$(printf '%s' "$agents_host_keys" | base64 -w0)" \
    --stacktrace --console=plain || status=$?

# The host's own record, over the runner's own SSH connection.
fixture "python3 -c 'import sys;print(open(sys.argv[1],\"rb\").read().hex())' ~/$FOLDER/ps3128-bytes.bin" \
  > "$OUT/host-bytes.hex" 2>/dev/null || rm -f "$OUT/host-bytes.hex"
# Every SIGWINCH the reader's PTY took over the whole lane (any remote resize).
fixture "cat ~/$FOLDER/ps3128-bytes.bin.winch" > "$OUT/host-winch.txt" 2>/dev/null || rm -f "$OUT/host-winch.txt"
cp -R "$RESULTS_DIR/." "$OUT/" 2>/dev/null || true
"$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true
for f in $("$ADB" -s "$ANDROID_SERIAL" shell ls /data/local/tmp | tr -d '\r' | grep '^ps2952-j32-' || true); do
  "$ADB" -s "$ANDROID_SERIAL" pull "/data/local/tmp/$f" "$OUT/${f#ps2952-}" >/dev/null
  "$ADB" -s "$ANDROID_SERIAL" shell rm -f "/data/local/tmp/$f" >/dev/null
done
if (( status != 0 )); then
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$OUT/diagnostics-screen.png" 2>/dev/null || true
fi

check_status=0
"$ROOT_DIR/scripts/check-js-voice-composer-results.py" --results-dir "$OUT" || check_status=$?
printf 'Voice composer lane: gradle=%s checker=%s evidence=%s\n' "$status" "$check_status" "$OUT"
(( status == 0 && check_status == 0 )) || exit 1
