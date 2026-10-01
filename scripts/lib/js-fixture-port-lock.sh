#!/usr/bin/env bash
# Hold the Docker agents fixture's per-port lock for a packaged JS lane.
#
# A lane that connects the emulator to a shared fixture (for example
# 10.0.2.2:2222) must own it for the whole run: a sibling agent's journey on
# the same fixture opens a second SSH socket mid-run, which a host-side oracle
# reads as the app's own extra connection (#2861 review 5922644857). The lock
# is the same machine-wide file the agents pool claims
# (`pocketshell_avd_lock_path "agents-port-lock-<port>"`), so pool claims and
# these lanes exclude each other too.
#
# Like the AVD lock, a separate holder process owns the flock, so Gradle
# daemons, adb servers and other children never inherit the descriptor and
# keep the fixture locked after the lane exits.
#
# Requires scripts/lib/avd-lock.sh (pocketshell_avd_lock_path,
# pocketshell_release_all). Call after the AVD lock is acquired: it re-registers
# the combined EXIT handler with this release first.

_pocketshell_hold_js_fixture_port_lock() {
  local lock_file="$1"
  local ready_file="$2"
  local wait_seconds="$3"
  exec >/dev/null 2>/dev/null
  exec 9>"$lock_file" || exit 1
  flock -w "$wait_seconds" 9 || exit 1
  printf 'ready\n' > "$ready_file"
  local sleep_pid=""
  trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
  while :; do
    sleep 3600 9>&- &
    sleep_pid="$!"
    wait "$sleep_pid" || true
  done
}

pocketshell_release_js_fixture_port_lock() {
  if [[ -n "${POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID:-}" ]]; then
    kill "$POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID" 2>/dev/null || true
    wait "$POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID" 2>/dev/null || true
    unset POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID
  fi
}

# pocketshell_acquire_js_fixture_port_lock PORT
# Waits up to POCKETSHELL_FIXTURE_PORT_WAIT_SECONDS (default 1800) for the lock.
pocketshell_acquire_js_fixture_port_lock() {
  local port="$1"
  local wait_seconds="${POCKETSHELL_FIXTURE_PORT_WAIT_SECONDS:-1800}"
  local token="${port//[^A-Za-z0-9._-]/_}"
  local lock_file
  lock_file="$(pocketshell_avd_lock_path "agents-port-lock-${token:-default}")"
  mkdir -p "$(dirname "$lock_file")"
  local state_dir
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-fixture-port.XXXXXX")"
  # Start the holder with the continuous AVD lock descriptor closed at process
  # creation (pocketshell_start_without_avd_lock_fd): otherwise the holder and
  # its sleep would keep the emulator locked after the lane released it. Its
  # own `exec 9>` replaces any inherited fd 9 (the Gradle output lock).
  if declare -F pocketshell_start_without_avd_lock_fd >/dev/null 2>&1; then
    pocketshell_start_without_avd_lock_fd _pocketshell_hold_js_fixture_port_lock "$lock_file" "$state_dir/ready" "$wait_seconds"
  else
    _pocketshell_hold_js_fixture_port_lock "$lock_file" "$state_dir/ready" "$wait_seconds" &
    POCKETSHELL_AVD_CHILD_PID="$!"
  fi
  local holder_pid="$POCKETSHELL_AVD_CHILD_PID"
  printf 'Waiting for the agents fixture port lock: %s\n' "$lock_file" >&2
  while [[ ! -e "$state_dir/ready" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      rm -rf "$state_dir"
      printf 'FAIL: could not acquire agents fixture port %s lock within %ss: %s\n' "$port" "$wait_seconds" "$lock_file" >&2
      return 1
    fi
    sleep 0.2
  done
  rm -rf "$state_dir"
  export POCKETSHELL_JS_FIXTURE_PORT_HOLDER_PID="$holder_pid"
  printf 'Acquired agents fixture port lock: %s\n' "$lock_file" >&2
  trap 'pocketshell_release_js_fixture_port_lock; pocketshell_release_all' EXIT
}
