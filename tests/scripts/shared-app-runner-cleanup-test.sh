#!/usr/bin/env bash
# Contract test for scripts/connected-js-shared-app.sh (#3002, #2953 review):
# once the runner has created its fixture sessions, EVERY exit path must kill
# them and release every lock. The AVD lock acquire replaces the EXIT trap, so
# a cleanup trap set before it is silently lost. Two exit paths are driven:
#   1. a failure bringing up the sshd-rekeyed fixture (#2953 setup);
#   2. a failed instrumentation run (gradle exits non-zero) — the ordinary
#      end of a run, which #3002 found leaking every session.
# Runs the real runner in a sandboxed copy of the repo with stub
# adb/docker/ssh/ssh-keyscan/ssh-keygen and sandboxed lock directories; no
# emulator or Docker needed.

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-shared-app-cleanup.XXXXXX")"
trap 'rm -rf -- "$TMP"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

SANDBOX="$TMP/repo"
BIN="$TMP/bin"
mkdir -p "$SANDBOX/scripts/lib" "$SANDBOX/android" "$SANDBOX/tests/docker/lib" "$BIN"
cp "$ROOT_DIR/scripts/connected-js-shared-app.sh" \
  "$ROOT_DIR/scripts/check-js-shared-app-results.py" \
  "$ROOT_DIR/scripts/shared-app-host-key-fixture.py" "$SANDBOX/scripts/"
cp "$ROOT_DIR"/scripts/lib/*.sh "$SANDBOX/scripts/lib/"
cp "$ROOT_DIR/tests/docker/lib/wait-for-healthy.sh" "$SANDBOX/tests/docker/lib/"
cp "$ROOT_DIR/tests/docker/test_key" "$ROOT_DIR/tests/docker/docker-compose.yml" "$SANDBOX/tests/docker/"
printf '#!/bin/sh\necho "stub gradlew: instrumentation failed" >&2\nexit 99\n' > "$SANDBOX/android/gradlew"
chmod +x "$SANDBOX/android/gradlew" "$SANDBOX"/scripts/*.sh "$SANDBOX"/scripts/*.py

printf '%s\n' '#!/bin/sh' \
  'case "$*" in' \
  '  devices) printf "List of devices attached\nemulator-5554\tdevice\n" ;;' \
  '  *get-state*) echo device ;;' \
  '  *getprop*) echo 35 ;;' \
  'esac' \
  'exit 0' > "$BIN/adb"
printf '%s\n' '#!/bin/sh' \
  'echo "[127.0.0.1]:2399 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIStubStubStubStubStubStubStubStubStubStub"' > "$BIN/ssh-keyscan"
printf '%s\n' '#!/bin/sh' 'cat >/dev/null' \
  'echo "256 SHA256:stubstubstubstubstubstubstubstubstubstubstu [127.0.0.1]:2399 (ED25519)"' > "$BIN/ssh-keygen"
chmod +x "$BIN/adb" "$BIN/ssh-keyscan" "$BIN/ssh-keygen"

# run_case NAME REKEYED_FAILS EXPECTED_LOG_TEXT
run_case() {
  local name="$1" rekeyed_fails="$2" expected="$3"
  local calls="$TMP/$name.calls" log="$TMP/$name.log" locks="$TMP/$name-locks" glocks="$TMP/$name-gradle-locks"
  : > "$calls"
  mkdir -p "$locks" "$glocks"

  {
    printf '#!/bin/sh\n'
    printf 'echo "docker $*" >> "%s"\n' "$calls"
    printf 'case "$*" in\n'
    printf '  inspect*) echo healthy; exit 0 ;;\n'
    printf '  *" ps -q "*) echo stub-container-id; exit 0 ;;\n'
    if [[ "$rekeyed_fails" == 1 ]]; then
      printf '  *sshd-rekeyed*) echo "simulated sshd-rekeyed start failure" >&2; exit 17 ;;\n'
    fi
    printf 'esac\nexit 0\n'
  } > "$BIN/docker"
  {
    printf '#!/bin/sh\n'
    printf 'for last; do :; done\n'
    printf 'echo "ssh $last" >> "%s"\n' "$calls"
    printf 'exit 0\n'
  } > "$BIN/ssh"
  chmod +x "$BIN/docker" "$BIN/ssh"

  set +e
  PATH="$BIN:$PATH" \
    ADB="$BIN/adb" \
    ANDROID_SERIAL=emulator-5554 \
    POCKETSHELL_AVD_LOCK_DIR="$locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$glocks" \
    POCKETSHELL_DISK_MIN_FREE_MB=1 \
    POCKETSHELL_DISK_WARN_FREE_MB=1 \
    timeout 120 "$SANDBOX/scripts/connected-js-shared-app.sh" --port 2399 --suffix i2953cleanup --test-only \
    < /dev/null > "$log" 2>&1
  local rc=$?
  set -e

  if (( rc == 0 || rc == 124 )); then
    cat "$log" >&2
    fail "$name: the runner must fail on this path (rc=$rc)"
  fi
  if ! grep -q "$expected" "$log"; then
    cat "$log" >&2
    fail "$name: the run did not reach the exit path under test ($expected)"
  fi
  local creates kills
  creates="$(grep -c 'sessions create' "$calls" || true)"
  kills="$(grep -c 'sessions kill' "$calls" || true)"
  (( creates > 0 )) || { cat "$calls" >&2; fail "$name: the runner never created its fixture sessions"; }
  if [[ "$kills" != "$creates" ]]; then
    cat "$calls" >&2
    fail "$name: every fixture session must be killed on exit ($creates created, $kills sessions kill calls)"
  fi
  local lock
  for lock in "$locks"/* "$glocks"/*; do
    [[ -e "$lock" ]] || continue
    flock -n "$lock" true || fail "$name: lock still held after the runner exited: $lock"
  done
  printf 'ok: %s — %s sessions created, all killed, every lock free\n' "$name" "$creates"
}

run_case rekeyed-setup-failure 1 'could not start the sshd-rekeyed fixture'
run_case failed-instrumentation-run 0 'journeys failed; capturing emulator diagnostics'

printf 'PASS: every runner exit path kills its fixture sessions and releases every lock\n'
