#!/usr/bin/env bash
set -euo pipefail

# Issue #2946: the emulator start path must re-enable a HOME launcher that a
# SIGKILLed lane left disabled. This executes scripts/start-local-avd.sh
# against a fake adb/emulator (already-booted path) and checks the device
# state it leaves behind:
#   1. several booted emulators and no ANDROID_SERIAL: every serial is checked;
#   2. the caller pins and owns that serial's AVD lock: recovery still runs;
#   3. another process holds one serial's lock: only that serial is skipped,
#      and the skip is logged.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-start-recovery.XXXXXX")"
HOLDER_PID=""
cleanup() {
  [[ -n "$HOLDER_PID" ]] && kill "$HOLDER_PID" 2> /dev/null || true
  python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)' "$SANDBOX"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

LAUNCHER=com.google.android.apps.nexuslauncher

cat > "$SANDBOX/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_ADB_STATE:?}"
serial="${ANDROID_SERIAL:-}"
if [[ "${1:-}" == -s ]]; then serial="$2"; shift 2; fi
printf '%s|%s\n' "${serial:-<none>}" "$*" >> "$state/commands"
case "$*" in
  start-server) ;;
  devices|'devices -l')
    printf 'List of devices attached\n'
    for s in $FAKE_DEVICES; do printf '%s\tdevice\n' "$s"; done
    ;;
  get-serialno)
    set -- $FAKE_DEVICES
    if [[ -z "$serial" && $# -gt 1 ]]; then
      printf 'error: more than one device/emulator\n' >&2
      exit 1
    fi
    printf '%s\n' "${serial:-$1}"
    ;;
  'shell getprop sys.boot_completed') printf '1\n' ;;
  'shell getprop') printf '[sys.boot_completed]: [1]\n' ;;
  'shell pm list packages -d')
    [[ -n "$serial" ]] || { printf 'error: more than one device/emulator\n' >&2; exit 1; }
    for f in "$state/$serial".disabled-*; do [[ -e "$f" ]] && printf 'package:%s\n' "${f##*.disabled-}"; done
    exit 0
    ;;
  'shell pm enable '*)
    [[ -n "$serial" ]] || { printf 'error: more than one device/emulator\n' >&2; exit 1; }
    args="$*"
    pkg="${args##* }"
    rm -f "$state/$serial.disabled-$pkg"
    printf 'Package %s new state: enabled\n' "$pkg"
    ;;
  *)
    printf 'unexpected adb args: %s\n' "$*" >&2
    exit 1
    ;;
esac
ADB
chmod +x "$SANDBOX/adb"

cat > "$SANDBOX/emulator" <<'EMU'
#!/usr/bin/env bash
case "$*" in
  -list-avds) printf 'test\n' ;;
  *) printf 'unexpected emulator args: %s\n' "$*" >&2; exit 1 ;;
esac
EMU
chmod +x "$SANDBOX/emulator"

CASES=0
pass() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

# Device state: a SIGKILLed lane left LAUNCHER disabled on every serial and
# recorded it next to that serial's AVD lock.
new_case() {
  CASE_DIR="$SANDBOX/$1"
  mkdir -p "$CASE_DIR/state" "$CASE_DIR/locks" "$CASE_DIR/logs"
  export FAKE_ADB_STATE="$CASE_DIR/state"
  local serial
  for serial in emulator-5554 emulator-5556; do
    touch "$CASE_DIR/state/$serial.disabled-$LAUNCHER"
    printf '%s\n' "$LAUNCHER" > "$CASE_DIR/locks/avd-lock-$serial.disabled-launchers"
  done
}

run_start() {
  env \
    ADB="$SANDBOX/adb" \
    EMULATOR="$SANDBOX/emulator" \
    ANDROID_SDK="$SANDBOX/sdk" \
    AVD_NAME=test \
    LOG_ROOT="$CASE_DIR/logs" \
    RUN_ID=recovery \
    BOOT_TIMEOUT_SECONDS=5 \
    POCKETSHELL_AVD_LOCK_DIR="$CASE_DIR/locks" \
    FAKE_DEVICES="emulator-5554 emulator-5556" \
    "$@" \
    "$ROOT_DIR/scripts/start-local-avd.sh" > "$CASE_DIR/start.out" 2> "$CASE_DIR/start.err" \
    || { cat "$CASE_DIR/start.err" >&2; fail "start-local-avd.sh exited non-zero in $CASE_DIR"; }
}

recovered() {
  local serial="$1"
  [[ ! -e "$CASE_DIR/state/$serial.disabled-$LAUNCHER" ]] \
    && [[ ! -e "$CASE_DIR/locks/avd-lock-$serial.disabled-launchers" ]]
}

# 1. Several emulators, no ANDROID_SERIAL: every booted serial is recovered.
new_case all-devices
run_start ANDROID_SERIAL=
recovered emulator-5554 || fail 'emulator-5554 launcher was not recovered with no ANDROID_SERIAL'
recovered emulator-5556 || fail 'emulator-5556 launcher was not recovered with no ANDROID_SERIAL'
grep -Fq 'STALE_LAUNCHER_RECOVERY: emulator-5554 checked (AVD lock idle)' "$CASE_DIR/start.err" \
  || fail 'start path did not log checking emulator-5554'
grep -Fq "RECOVERED_STALE_DISABLED_LAUNCHER: $LAUNCHER (recorded in" "$CASE_DIR/start.err" \
  || fail 'start path did not log the recovered launcher'
pass 'several booted emulators without ANDROID_SERIAL are all recovered'

# 2. The caller pins emulator-5554 and start-local-avd.sh takes that serial's
#    own AVD lock first: recovery must run under that lock, not skip itself.
new_case own-lock
run_start ANDROID_SERIAL=emulator-5554 POCKETSHELL_AVD_LOCK_FILE="$CASE_DIR/locks/avd-lock-emulator-5554"
recovered emulator-5554 || fail 'recovery skipped the serial whose lock the start path itself holds'
[[ -e "$CASE_DIR/state/emulator-5556.disabled-$LAUNCHER" ]] \
  || fail 'a pinned ANDROID_SERIAL must not touch other emulators'
grep -Fq "STALE_LAUNCHER_RECOVERY: emulator-5554 checked under this process's own AVD lock" "$CASE_DIR/start.err" \
  || fail 'own-lock recovery is not logged'
pass 'recovery runs under the start path'"'"'s own per-serial lock'

# 3. Another process (a live lane) holds emulator-5556's lock: that serial is
#    skipped and logged, emulator-5554 is still recovered.
new_case foreign-lock
( exec 9> "$CASE_DIR/locks/avd-lock-emulator-5556"; flock 9; exec sleep 300 ) &
HOLDER_PID=$!
for _ in $(seq 1 50); do
  flock -n "$CASE_DIR/locks/avd-lock-emulator-5556" true 2> /dev/null || break
  sleep 0.05
done
run_start ANDROID_SERIAL=
recovered emulator-5554 || fail 'emulator-5554 was not recovered next to a busy emulator'
[[ -e "$CASE_DIR/state/emulator-5556.disabled-$LAUNCHER" ]] \
  || fail 'start path re-enabled a launcher under a live lane'
[[ -e "$CASE_DIR/locks/avd-lock-emulator-5556.disabled-launchers" ]] \
  || fail 'start path dropped the record of a live lane'
grep -Fq 'STALE_LAUNCHER_RECOVERY: emulator-5556 skipped, its AVD lock is held by another process' "$CASE_DIR/start.err" \
  || fail 'skipping a busy emulator is not logged'
kill "$HOLDER_PID" 2> /dev/null || true
wait "$HOLDER_PID" 2> /dev/null || true
HOLDER_PID=""
pass 'only the emulator whose lock another process holds is skipped'

(( CASES == 3 )) || fail "ran $CASES/3 cases"
printf 'PASS: start-local-avd launcher recovery (%s/3 cases)\n' "$CASES"
