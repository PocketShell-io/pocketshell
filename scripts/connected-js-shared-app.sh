#!/usr/bin/env bash
# Run the shared-app journeys (#2936, #2952): a launch that opts into the shared
# PocketShell app (the pocketshell.shell=shared extra; legacy screens stay the
# default) must list, attach, re-attach and type — through a real, scripted
# Android input method — into this run's own sessions on an isolated
# agents-pool lane (--port, never 2222), whose port lock the run claims. A
# raw-mode reader on the host records the exact bytes the IME journey typed;
# the result checker compares the host's record, not just the screen. Shares the machine-wide AVD and Android output-tree
# locks with the other packaged JS lanes.
#
# #2953: the same class proves the shared host-key prompt. The runner computes
# the fixtures' real host keys on the Docker host (the oracle the app's prompt
# is checked against), brings up the `sshd-rekeyed` fixture (port 2246; new
# host keys on every container start) under that port's
# machine-wide lock, and serves a rotation controller the journey calls to
# restart it mid-run, so "the host key changed" is a real rotation.



set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
PNPM="${PNPM:-pnpm}"
SUFFIX="i2936shared"
PORT=""
# The sshd-rekeyed compose service's fixed host port (tests/docker/docker-compose.yml).
REKEYED_PORT=2246
PREPARE_ONLY=0
TEST_ONLY=0

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-shared-app.sh --port PORT [--suffix TOKEN] [--prepare-only|--test-only]

Build and run the shared-app packaged journey (#2936).
The default runs both preparation and connected tests. CI can prepare before
booting an emulator, then run with --test-only inside its emulator step.

Options:
  --port PORT      An isolated, healthy agents-pool lane (not 2222); the run
                   claims its port lock and creates its own sessions there
  --suffix TOKEN   Isolate the debug package (default: i2936shared)
  --prepare-only   Build the suffixed app and androidTest APKs, without an AVD
  --test-only      Run the already-prepared APKs on one API 35+ emulator
  --help           Show this help

The connected phase selects ANDROID_SERIAL when supplied, otherwise it
requires exactly one online emulator. Both Gradle output and AVD mutations are
protected by the shared PocketShell locks.

The connected phase also brings up the sshd-rekeyed fixture on 2246 (#2953)
under that port's machine-wide lock and restarts it once mid-journey.
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix)
      [[ $# -ge 2 ]] || fail '--suffix needs a token'
      SUFFIX="$2"
      shift 2
      ;;
    --port)
      [[ $# -ge 2 ]] || fail '--port needs a value'
      PORT="$2"
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
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail "suffix must match [A-Za-z0-9._]+ (got: $SUFFIX)"
[[ "$PREPARE_ONLY" == 1 || "$PORT" =~ ^[0-9]+$ ]] || fail '--port PORT is required to run the journey'
# Its own agents lane: 2222 is the shared lane every other packaged journey
# leaves sessions on, so it is refused (#2936 review).
[[ "$PREPARE_ONLY" == 1 || "$PORT" != 2222 ]] || fail '--port must be an isolated agents-pool lane, not the shared 2222 fixture'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
[[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'generated android/gradlew is missing; initialize the JS-first Android project first'
[[ -x "$ROOT_DIR/scripts/check-js-shared-app-results.py" ]] || fail 'shared-app result verifier is missing'
"$ROOT_DIR/scripts/check-js-shared-app-results.py" --self-test
HOST_KEY_FIXTURE="$ROOT_DIR/scripts/shared-app-host-key-fixture.py"
[[ -x "$HOST_KEY_FIXTURE" ]] || fail 'shared-app host-key fixture helper is missing'
"$HOST_KEY_FIXTURE" --self-test

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/agents-pool.sh"

pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-shared-app.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-shared-app.sh suffix=$SUFFIX"

if [[ "$TEST_ONLY" != 1 ]]; then
  command -v "$PNPM" >/dev/null 2>&1 || fail 'pnpm is required to build the packaged web assets'
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi

if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: packaged JS app and androidTest APKs prepared for suffix %s\n' "$SUFFIX"
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
  || fail "safe-area instrumentation requires API 35+; $ANDROID_SERIAL reports API ${device_api:-unknown}"

# Claim the lane for this run: the same per-port flock agents-pool.sh and
# --pool lanes take, held on this shell's FD until exit.
port_lock_file="$(pocketshell_agents_lock_file_for_port "$ROOT_DIR" "$PORT")"
exec {port_lock_fd}>"$port_lock_file"
flock -n "$port_lock_fd" || fail "agents lane $PORT is claimed by another run ($port_lock_file)"
container="$(pocketshell_agents_container_for_port "$PORT")"
health="$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
[[ "$health" == healthy ]] || fail "agents lane $PORT must already be healthy (scripts/agents-pool.sh up $PORT); $container reports ${health:-missing}"

ssh_key_copy="$ROOT_DIR/android/app/build/outputs/js-shared-app-fixture-key"
mkdir -p "$(dirname -- "$ssh_key_copy")"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$ssh_key_copy"
ssh_opts=(-i "$ssh_key_copy" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
fixture() { ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 "$1"; }

# This run's own three sessions, each in its own folder, so the journeys never
# depend on (or click) whatever earlier runs left on the lane. Session c gets
# the raw-mode byte reader the IME journey starts (#2952).
# SHARED_APP_RUN_RANDOM pins the run id's random part (1-5 digits), so a run
# can be aimed at a line length: on the 35-column phone layout a 4-digit id
# makes the journey's tagged output line fill a row exactly (#2949).
RUN_RANDOM="${SHARED_APP_RUN_RANDOM:-$RANDOM}"
[[ "$RUN_RANDOM" =~ ^[0-9]{1,5}$ ]] || fail "SHARED_APP_RUN_RANDOM must be 1-5 digits (got: $RUN_RANDOM)"
SESSION_RUN="ps2936-$(date +%s)-$RUN_RANDOM"
SIDES=(a b c)
for side in "${SIDES[@]}"; do
  fixture "mkdir -p ~/$SESSION_RUN-$side && pocketshell sessions create --json --cwd ~/$SESSION_RUN-$side -- $SESSION_RUN-$side >/dev/null" \
    || fail "could not create fixture session $SESSION_RUN-$side on lane $PORT"
done
fixture "cat > ~/$SESSION_RUN-c/ps2952-capture.py" <<'CAPTURE' \
  || fail "could not stage the host byte reader on lane $PORT"
# #2952 host oracle: put this PTY in raw mode (no echo, no line editing, no
# CR/NL translation, no signals) and record every byte the terminal sends
# until Ctrl+D, then print and store them as hex.
import os, sys, termios, tty
out = os.path.expanduser(sys.argv[1])
fd = sys.stdin.fileno()
saved = termios.tcgetattr(fd)
tty.setraw(fd)
received = bytearray()
try:
    os.write(1, b"PS2952_READY\r\n")
    while True:
        byte = os.read(fd, 1)
        if not byte or byte == b"\x04":
            break
        received += byte
finally:
    termios.tcsetattr(fd, termios.TCSADRAIN, saved)
with open(out, "w") as record:
    record.write(received.hex() + "\n")
print("PS2952_HEX:" + received.hex() + ":END")
CAPTURE
cleanup_fixture_sessions() {
  for side in "${SIDES[@]}"; do
    # `sessions create --cwd ~/X -- X` names the session `X:X` (folder:tag).
    fixture "pocketshell sessions kill -- $SESSION_RUN-$side:$SESSION_RUN-$side >/dev/null 2>&1; rm -rf ~/$SESSION_RUN-$side" || true
  done
}
trap cleanup_fixture_sessions EXIT
printf 'Fixture lane %s: created sessions %s-{a,b,c}\n' "$PORT" "$SESSION_RUN"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
# The AVD lock acquire just replaced the EXIT trap with
# pocketshell_release_all, dropping cleanup_fixture_sessions. Re-install ONE
# handler for everything this run holds, right now, so no `fail` from here on
# (rekeyed fixture setup included) can leak this run's fixture sessions.
rotation_pid=""
on_exit() {
  if [[ -n "$rotation_pid" ]]; then kill "$rotation_pid" 2>/dev/null || true; fi
  cleanup_fixture_sessions
  pocketshell_release_all
}
trap on_exit EXIT

# --- #2953 host-key fixtures ------------------------------------------------
source "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh"
COMPOSE_FILE="$ROOT_DIR/tests/docker/docker-compose.yml"
REKEYED_CONTAINER=pocketshell-test-ssh-rekeyed
HOST_KEY_DIR="$ROOT_DIR/android/app/build/outputs/js-shared-app-host-keys"
mkdir -p "$HOST_KEY_DIR"

# The rekeyed fixture is restarted mid-run: hold its port's machine-wide lock
# (the one agents-pool.sh and --pool lanes take) so no sibling uses it meanwhile.
rekeyed_lock_file="$(pocketshell_agents_lock_file_for_port "$ROOT_DIR" "$REKEYED_PORT")"
mkdir -p "$(dirname "$rekeyed_lock_file")"
exec 7>"$rekeyed_lock_file"
flock -w 900 7 || fail "port $REKEYED_PORT (sshd-rekeyed) is held by another run"

docker compose -f "$COMPOSE_FILE" up -d --build --no-deps sshd-rekeyed 7>&- \
  || fail 'could not start the sshd-rekeyed fixture'
wait_for_container_healthy "$COMPOSE_FILE" sshd-rekeyed "$HOST_KEY_DIR/sshd-rekeyed-health.log" 90 \
  || fail 'the sshd-rekeyed fixture did not become healthy'

agents_host_keys="$("$HOST_KEY_FIXTURE" keys --port "$PORT")" || fail "no host keys answered on agents port $PORT"
rekeyed_host_keys="$("$HOST_KEY_FIXTURE" keys --port "$REKEYED_PORT")" || fail "no host keys answered on rekeyed port $REKEYED_PORT"
printf 'agents %s: %s\nrekeyed %s: %s\n' "$PORT" "$agents_host_keys" "$REKEYED_PORT" "$rekeyed_host_keys" \
  | tee "$HOST_KEY_DIR/host-keys-before.txt"

rotation_port_file="$HOST_KEY_DIR/rotation-port"
rm -f -- "$rotation_port_file"
"$HOST_KEY_FIXTURE" serve --ssh-port "$REKEYED_PORT" --container "$REKEYED_CONTAINER" \
  --port-file "$rotation_port_file" > "$HOST_KEY_DIR/rotation-controller.log" 2>&1 7>&- &
# on_exit (installed after the AVD lock) stops it.
rotation_pid=$!
for _ in $(seq 1 50); do
  [[ -s "$rotation_port_file" ]] && break
  sleep 0.1
done
[[ -s "$rotation_port_file" ]] || fail 'the rotation controller did not start'
rotation_port="$(tr -d '[:space:]' < "$rotation_port_file")"

RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
SHARED_APP_RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/js-shared-app-results"
HOST_BYTES_FILE="$ROOT_DIR/android/app/build/outputs/js-shared-app-host-ime-bytes.hex"
python3 - "$RESULTS_DIR" "$SHARED_APP_RESULTS_DIR" "$HOST_BYTES_FILE" <<'PY'
from pathlib import Path
import shutil
import sys

for stale in map(Path, sys.argv[1:]):
    if stale.is_dir():
        shutil.rmtree(stale)
    elif stale.exists():
        stale.unlink()
PY
# The host's own record of the IME journey's bytes (the independent oracle).
fetch_host_bytes() {
  fixture "cat ~/$SESSION_RUN-c/ps2952-bytes.hex" > "$HOST_BYTES_FILE" 2>/dev/null || rm -f "$HOST_BYTES_FILE"
}

# The key reaches the app only through the key vault's content-URI import;
# the raw copy sits outside every app package and the test deletes it.
DEVICE_KEY_PATH="/data/local/tmp/pocketshell-$SUFFIX-shared-key.pem"
"$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/test_key" "$DEVICE_KEY_PATH" >/dev/null

printf 'Running shared-app journeys on %s (API %s), suffix %s\n' "$ANDROID_SERIAL" "$device_api" "$SUFFIX"
if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.SharedAppDockerJourneyTest,com.pocketshell.app.ime.ScriptedImeSelectionTest \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.sessionRun=$SESSION_RUN" \
    "-Pandroid.testInstrumentationRunnerArguments.sshPrivateKeyPath=$DEVICE_KEY_PATH" \
    "-Pandroid.testInstrumentationRunnerArguments.rekeyedPort=$REKEYED_PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.rotationPort=$rotation_port" \
    "-Pandroid.testInstrumentationRunnerArguments.agentsHostKeysBase64=$(printf '%s' "$agents_host_keys" | base64 -w0)" \
    "-Pandroid.testInstrumentationRunnerArguments.rekeyedHostKeysBase64=$(printf '%s' "$rekeyed_host_keys" | base64 -w0)" \
    ${POCKETSHELL_EVIDENCE_HOLD_MS:+"-Pandroid.testInstrumentationRunnerArguments.evidenceHoldMillis=$POCKETSHELL_EVIDENCE_HOLD_MS"} \
    --stacktrace --console=plain 7>&-; then
  :
else
  test_exit_code=$?
  printf 'Shared-app journeys failed; capturing emulator diagnostics.\n' >&2
  mkdir -p "$RESULTS_DIR"
  fetch_host_bytes
  [[ -f "$HOST_BYTES_FILE" ]] && cp "$HOST_BYTES_FILE" "$RESULTS_DIR/host-ime-bytes.hex"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d 7>&- -v threadtime -t 4000 \
    > "$RESULTS_DIR/diagnostics-logcat.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys input_method 7>&- \
    > "$RESULTS_DIR/diagnostics-input-method.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell dumpsys window 7>&- \
    > "$RESULTS_DIR/diagnostics-window.txt" 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p 7>&- \
    > "$RESULTS_DIR/diagnostics-screen.png" 2>&1 || true
  # Later packaged lanes clear the shared connected-results directory, so the
  # failed run's JUnit XML, per-method logcats and diagnostics are kept in
  # this lane's own output (uploaded by the workflow) — never only where the
  # next lane deletes them (#2954: a CI red with no evidence left).
  mkdir -p "$SHARED_APP_RESULTS_DIR/failed-run"
  cp -R "$RESULTS_DIR/." "$SHARED_APP_RESULTS_DIR/failed-run/" 2>/dev/null || true
  exit "$test_exit_code"
fi
fetch_host_bytes
"$ROOT_DIR/scripts/check-js-shared-app-results.py" --results-dir "$RESULTS_DIR" \
  --host-bytes "$HOST_BYTES_FILE" --evidence-dir "$SHARED_APP_RESULTS_DIR"
# Screenshots the journeys took of the typed terminal (evidence, not a gate).
for shot in typed-after-reattach ime-bytes; do
  "$ADB" -s "$ANDROID_SERIAL" pull "/data/local/tmp/ps2952-$shot.png" "$SHARED_APP_RESULTS_DIR/$shot.png" >/dev/null 2>&1 || true
  "$ADB" -s "$ANDROID_SERIAL" shell rm -f "/data/local/tmp/ps2952-$shot.png" >/dev/null 2>&1 || true
done
