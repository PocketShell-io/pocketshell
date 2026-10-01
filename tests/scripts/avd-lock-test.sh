#!/usr/bin/env bash
set -euo pipefail

# Hermetic harness (issue #1702). A driving shell that already holds the machine
# AVD lock -- e.g. pre-release-confidence-gate.sh, which calls
# pocketshell_acquire_avd_lock BEFORE running `assembleDebug check` -> this suite
# -- EXPORTS the acquire STATE (POCKETSHELL_AVD_LOCK_ACQUIRED and friends). When
# these cases fork a fresh gate, that inherited state short-circuits
# pocketshell_acquire_avd_lock (it early-returns "already acquired"), so the lock
# is never actually taken and the ownership assertions fail against the gate's
# own lock -- self-contention, not a product bug. Scrub the process-internal
# acquire state so the harness is correct whether or not a gate holds the real
# lock. The real #1663 machine-anchoring behaviour is untouched (only avd-lock.sh
# defines it; this only clears inherited runtime state).
unset POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_FD \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_AVD_LOCK_CONTINUOUS \
      POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

CASE_COUNT=0

with_tmpdir() {
  local tmpdir
  tmpdir="$(mktemp -d)"
  "$@" "$tmpdir"
  rm -rf "$tmpdir"
  CASE_COUNT=$((CASE_COUNT + 1))
  printf '  ok: %s\n' "$1"
}

child_processes_do_not_hold_lock() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1"
    bash -c "sleep 10" &
    child_pid="$!"
    pocketshell_release_avd_lock
    flock -n "$POCKETSHELL_AVD_LOCK_FILE" true
    kill "$child_pid" 2>/dev/null || true
    wait "$child_pid" 2>/dev/null || true
  ' bash "$ROOT_DIR" || fail "child process inherited the AVD lock"
}

continuous_log_pipeline_does_not_inherit_lock_fd() {
  local tmpdir="$1"
  local lock_file="$tmpdir/continuous-avd.lock"
  local log_file="$tmpdir/connected-command.log"
  local async_log="$tmpdir/connected-async-command.log"
  local probe="$tmpdir/probe-lock-fd.sh"

  cat > "$probe" <<'PROBE'
#!/usr/bin/env bash
set -euo pipefail
lock_file="$1"
for fd in "/proc/$BASHPID/fd/"*; do
  [[ -e "$fd" ]] || continue
  target="$(readlink -f "$fd" 2>/dev/null || true)"
  if [[ "$target" == "$lock_file" ]]; then
    printf 'connected command inherited continuous AVD lock fd=%s\n' "${fd##*/}" >&2
    exit 47
  fi
done
printf 'connected command completed without AVD lock fd\n'
PROBE
  chmod +x "$probe"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" \
    POCKETSHELL_AVD_LOCK_CONTINUOUS=1 \
    bash -c '
      set -euo pipefail
      source "$1/scripts/lib/avd-lock.sh"
      pocketshell_acquire_avd_lock "$1"
      pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
      pocketshell_run_without_avd_lock_fd_to_log "$2" bash "$3" "$POCKETSHELL_AVD_LOCK_FILE"
      grep -Fq "connected command completed without AVD lock fd" "$2"
      pocketshell_start_without_avd_lock_fd bash "$3" "$POCKETSHELL_AVD_LOCK_FILE" > "$4" 2>&1
      child_pid="$POCKETSHELL_AVD_CHILD_PID"
      wait "$child_pid"
      grep -Fq "connected command completed without AVD lock fd" "$4"
      pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"
      pocketshell_release_avd_lock
      flock -n "$POCKETSHELL_AVD_LOCK_FILE" true
    ' bash "$ROOT_DIR" "$log_file" "$probe" "$async_log" \
    || fail "connected command inherited the continuous AVD lock"
}

nested_gates_do_not_reacquire_or_release() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1"

    timeout 1s bash -c "
      set -euo pipefail
      source \"\$1/scripts/lib/avd-lock.sh\"
      pocketshell_acquire_avd_lock \"\$1\"
      pocketshell_release_avd_lock
    " bash "$1"

    if flock -n "$POCKETSHELL_AVD_LOCK_FILE" true; then
      echo "nested gate released the outer AVD lock" >&2
      exit 1
    fi

    pocketshell_release_avd_lock
    flock -n "$POCKETSHELL_AVD_LOCK_FILE" true
  ' bash "$ROOT_DIR" || fail "nested gate lock ownership was not preserved"
}

help_mode_does_not_wait_for_lock() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  ( flock "$lock_file" sleep 2 ) &
  local holder_pid="$!"
  sleep 0.1

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" timeout 1s bash -c '
    set -euo pipefail
    source "$1/scripts/lib/avd-lock.sh"
    pocketshell_acquire_avd_lock "$1" --help
  ' bash "$ROOT_DIR" || {
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
    fail "help mode waited for the AVD lock"
  }

  kill "$holder_pid" 2>/dev/null || true
  wait "$holder_pid" 2>/dev/null || true
}

# Issue #2481: this used to drive scripts/phone-walkthrough.sh, deleted with the
# `app` module androidTest classes it ran. The property is not
# phone-walkthrough-specific: it is "a script that takes the AVD lock BEFORE it
# parses --help must still release the lock when --help exits". The visual pass
# has exactly that shape (pocketshell_acquire_avd_lock runs above its usage()),
# so it is the carrier now.
visual_pass_late_help_releases_lock() {
  local tmpdir="$1"
  local lock_file="$tmpdir/avd.lock"

  POCKETSHELL_AVD_LOCK_FILE="$lock_file" \
    LOG_ROOT="$tmpdir/walkthrough-visual-pass" \
    RUN_ID="late-help" \
    timeout 5s "$ROOT_DIR/scripts/capture-walkthrough-screenshots.sh" --help \
    > "$tmpdir/late-help.out" 2> "$tmpdir/late-help.err" ||
    fail "visual pass late help did not exit successfully"

  if ! flock -n "$lock_file" true; then
    fuser -k "$lock_file" >/dev/null 2>&1 || true
    fail "visual pass late help leaked the AVD lock holder"
  fi
}

with_tmpdir child_processes_do_not_hold_lock
with_tmpdir continuous_log_pipeline_does_not_inherit_lock_fd
with_tmpdir nested_gates_do_not_reacquire_or_release
with_tmpdir help_mode_does_not_wait_for_lock
with_tmpdir visual_pass_late_help_releases_lock

# Issue #2113: a harness that exits 0 having run nothing is the vacuous green
# process.md catalogues. The count line is what makes the JVM assertion about
# behaviour rather than about bash's exit status.
(( CASE_COUNT == 5 )) || fail "expected 5 cases to run, saw $CASE_COUNT"
printf 'PASS: avd-lock helper (%s cases)\n' "$CASE_COUNT"
