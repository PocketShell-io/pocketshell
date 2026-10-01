#!/usr/bin/env bash
# Contract test for scripts/lib/js-fixture-port-lock.sh: one lane owns an
# agents fixture port at a time, a waiting lane gets it once the owner exits,
# and a long-lived child (a Gradle daemon, an adb server) never keeps it.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/js-fixture-port-lock-test.XXXXXX")"
trap 'rm -rf -- "$SCRATCH"' EXIT
export POCKETSHELL_AVD_LOCK_DIR="$SCRATCH/locks"
PORT=40999

lane() {
  # $1 = seconds to hold, $2 = wait seconds, $3 = marker file, $4 = optional orphan child
  bash -c '
    set -euo pipefail
    source "$0/scripts/lib/avd-lock.sh"
    source "$0/scripts/lib/js-fixture-port-lock.sh"
    POCKETSHELL_FIXTURE_PORT_WAIT_SECONDS="$2" pocketshell_acquire_js_fixture_port_lock "$1" 2>/dev/null || exit 3
    printf "held\n" > "$4"
    if [[ "${5:-}" == orphan ]]; then (sleep 30 &) ; fi
    sleep "$3"
  ' "$ROOT_DIR" "$PORT" "$2" "$1" "$3" "${4:-}"
}

lane 3 5 "$SCRATCH/first" orphan &
first=$!
for _ in $(seq 1 50); do [[ -e "$SCRATCH/first" ]] && break; sleep 0.1; done
[[ -e "$SCRATCH/first" ]] || { echo 'FAIL: first lane never acquired the port lock' >&2; exit 1; }

if lane 0 1 "$SCRATCH/blocked"; then
  echo 'FAIL: a second lane acquired a held fixture port' >&2; exit 1
fi
[[ ! -e "$SCRATCH/blocked" ]] || { echo 'FAIL: blocked lane ran' >&2; exit 1; }
echo 'ok: a held fixture port refuses a second lane'

lane 0 20 "$SCRATCH/waiter" &
waiter=$!
wait "$first"
wait "$waiter" || { echo 'FAIL: a waiting lane did not get the port after the owner exited (a child kept the lock?)' >&2; exit 1; }
[[ -e "$SCRATCH/waiter" ]] || { echo 'FAIL: waiting lane never acquired the port' >&2; exit 1; }
echo 'ok: the port passes to a waiting lane once the owner exits, despite a surviving child'

# 3. Under a continuous AVD lock, neither the port holder nor its sleep may
#    carry the emulator lock descriptor, and both locks are free after exit.
bash -c '
  set -euo pipefail
  source "$0/scripts/lib/avd-lock.sh"
  source "$0/scripts/lib/js-fixture-port-lock.sh"
  export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
  export POCKETSHELL_AVD_LOCK_FILE="$POCKETSHELL_AVD_LOCK_DIR/avd-lock-emulator-test"
  pocketshell_acquire_avd_lock "$0" 2>/dev/null
  pocketshell_acquire_js_fixture_port_lock "$1" 2>/dev/null
  for pid in "$POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID" $(pgrep -P "$POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID" || true); do
    if ls -l "/proc/$pid/fd" 2>/dev/null | grep -q "avd-lock-emulator-test"; then
      echo "FAIL: port-lock process $pid inherited the AVD lock descriptor" >&2; exit 1
    fi
  done
' "$ROOT_DIR" "$PORT"
flock -n "$POCKETSHELL_AVD_LOCK_DIR/avd-lock-emulator-test" true \
  || { echo 'FAIL: the AVD lock is still held after the lane exited' >&2; exit 1; }
flock -n "$POCKETSHELL_AVD_LOCK_DIR/agents-port-lock-$PORT" true \
  || { echo 'FAIL: the fixture port lock is still held after the lane exited' >&2; exit 1; }
echo 'ok: no port-lock process carries the AVD lock, and both locks free on exit'
echo 'PASS: js fixture port lock contract (3/3)'
