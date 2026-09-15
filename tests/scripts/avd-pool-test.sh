#!/usr/bin/env bash
set -euo pipefail

# AVD pool claim/release regression harness (issue #674).
#
# These tests exercise the REAL scripts/connected-test.sh --pool path and the
# pool claim/release helpers in scripts/lib/avd-lock.sh using a fake `adb` and a
# stub `./gradlew`, so NO real emulator is needed. They prove the load-bearing
# property of AC2: after a connected-test run finishes, every claimed pool
# serial is reclaimable again (its per-serial flock is free).
#
# The key regression is `reclaim_after_full_pool_run`: it simulates a COMPLETE
# (not killed) `connected-test.sh --pool` run. Before the exec->child fix the
# wrapper ended with `exec ./gradlew`, which replaced the shell so bash never
# fired its EXIT trap; the backgrounded per-serial flock holder was orphaned and
# kept the claim held forever. With a 3-emulator pool that stranded one serial
# per run. This test would have caught it: it asserts `claimable-after-run`
# equals the pool size, not size-1.
#
# Hermetic harness (issue #1702): a driving shell that already holds the machine
# AVD lock (pre-release-confidence-gate.sh acquires it BEFORE `assembleDebug
# check` -> this suite) EXPORTS the acquire STATE below. Inherited, it
# short-circuits pocketshell_acquire_avd_lock inside the wrapper (early-returns
# "already acquired"), so the base lock is never created and the non-pool
# assertion fails -- the gate self-contends. Scrub the process-internal acquire
# state so the harness is correct whether or not a gate holds the real lock; the
# #1663 anchoring is untouched.
unset POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID \
      ANDROID_SERIAL \
      ADB_SERIAL

# Issue #1989: connected-test.sh now refuses to start below a free-space floor.
# This harness is about POOL CLAIM/RELEASE, and it runs on hosted runners whose
# free space is not its business — a pool test that goes red because the runner
# is 70% full is a test that gets disabled. Pin the floor to 0 MiB: a threshold,
# not a skip, so the preflight still runs on every fixture here.
# tests/scripts/disk-preflight-test.sh owns the disk behaviour.
export POCKETSHELL_DISK_MIN_FREE_MB=0
export POCKETSHELL_DISK_WARN_FREE_MB=0

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

# Build an isolated sandbox containing:
#   * a fake `adb` reporting POOL_SERIALS as online emulators
#   * a stub `./gradlew` that records its invocation and exits 0 (or with a
#     caller-chosen exit code) WITHOUT touching a real device
#   * a private ROOT clone plus a private POCKETSHELL_AVD_LOCK_DIR so this
#     test's lock files never touch the REAL machine-wide lock dir (which a
#     live emulator run may be holding). Since issue #1657 the lock is anchored
#     to the machine, not to $root_dir, so isolating the root is no longer
#     enough on its own -- the lock dir must be sandboxed explicitly.
# The sandbox is a directory; the caller passes it to the *_in_sandbox helpers.
make_sandbox() {
  local sandbox="$1"
  shift
  local pool_serials="$1"   # space-separated emulator-XXXX serials

  mkdir -p "$sandbox/bin" "$sandbox/root/scripts/lib" "$sandbox/root/build"

  # Issue #1657: point the machine-wide lock anchor at this sandbox so the test
  # cannot contend with (or corrupt) a real connected-test run on this box.
  export POCKETSHELL_AVD_LOCK_DIR="$sandbox/locks"
  # Issue #2007: same reasoning for the Gradle output-tree lock the wrapper now
  # takes. Its anchor is machine-wide per-user too, so without this every run of
  # this harness would leave one lock file per fixture root in the real
  # ~/.cache/pocketshell/gradle-output-locks and (more importantly) a fixture
  # that ever resolved a real checkout could queue behind a real build.
  export POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$sandbox/gradle-output-locks"

  # Issue #2712: the #2556 fixture-vintage gate in the copied REAL
  # connected-test.sh runs whenever ANY docker is on PATH (ubuntu-latest
  # runners ship one) and reads the pins contract from the CHECKOUT UNDER
  # TEST -- here the sandbox root, which had no pins file, so every lane died
  # fail-closed ("no PIN lines ...") before the pool claim/release this
  # harness exists to exercise. Copy the real file so every lane runs the
  # gate's full comparison -- the gate is never skipped, weakened, or stubbed
  # out. The container side of the contract is served by the docker stub
  # below (issue #2712).
  mkdir -p "$sandbox/root/tests/docker"
  cp "$ROOT_DIR/tests/docker/fixture-pins.txt" \
    "$sandbox/root/tests/docker/fixture-pins.txt"
  export AVDPOOL_HARNESS_FIXTURE_PINS="$ROOT_DIR/tests/docker/fixture-pins.txt"

  # Fake adb: only the `devices` subcommand matters for pool claim. Report each
  # pool serial as a `device`-state emulator. Everything else is a harmless no-op.
  cat > "$sandbox/bin/adb" <<ADB
#!/usr/bin/env bash
case "\$1" in
  devices)
    printf 'List of devices attached\n'
    for s in $pool_serials; do
      printf '%s\tdevice\n' "\$s"
    done
    ;;
  start-server) : ;;
  *) : ;;
esac
exit 0
ADB
  chmod +x "$sandbox/bin/adb"

  # Issue #2712: with ANY docker on PATH -- a stub counts -- the wrapper runs
  # the #2556 fixture-vintage gate, which execs the port-2222 fixture
  # container and reads its build-time-baked pins. The stub emulates exactly
  # that one interaction: a pocketshell-test-agents container (the static
  # 2222 name, scripts/lib/agents-pool.sh) whose
  # /opt/pocketshell-fixture/pins.txt was baked from the REAL checkout's
  # tests/docker/fixture-pins.txt -- what a real `docker compose up -d
  # --build` from this checkout produces. Not a gate bypass: the gate still
  # greps the SANDBOX checkout's own pins file and diffs it against this
  # answer, so a sandbox whose copied pins drifted (or lost the file) still
  # fails closed before instrumentation -- proven by
  # fixture_vintage_gate_stays_fail_closed_in_pool_lanes below. An unset
  # AVDPOOL_HARNESS_FIXTURE_PINS makes the stub die loudly, which the gate
  # reports as a mismatch: this provision can only ever fail CLOSED. Every
  # other subcommand is a harmless no-op, like the fake adb above -- in
  # particular `docker inspect` answers empty, which the #1842/#2574
  # fingerprint guards read as "unknown" (disarmed), never as a failure, and
  # which also keeps these sandbox lanes from ever touching the REAL docker
  # daemon or its 2222 fixture.
  cat > "$sandbox/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
if [[ "${1:-}" == "exec" && "${3:-}" == "cat" \
      && "${4:-}" == "/opt/pocketshell-fixture/pins.txt" ]]; then
  cat "${AVDPOOL_HARNESS_FIXTURE_PINS:?AVDPOOL_HARNESS_FIXTURE_PINS must name the checkout tests/docker/fixture-pins.txt}"
  exit 0
fi
exit 0
DOCKER
  chmod +x "$sandbox/bin/docker"

  # Copy the real scripts into the sandbox root so connected-test.sh runs the
  # ACTUAL production logic against sandboxed lock state.
  #
  # Copy the WHOLE scripts/lib: connected-test.sh sources agents-pool.sh (#724)
  # and scope-run.sh (#730), both of which it grew AFTER this harness was
  # written. The harness only ever copied avd-lock.sh, so every case died at
  # `source .../agents-pool.sh: No such file or directory` -- this suite has been
  # failing on `main` (it is wired to no lane, so nobody saw it). Globbing the
  # directory keeps it from rotting again the next time a lib is added.
  cp "$ROOT_DIR/scripts/connected-test.sh" "$sandbox/root/scripts/connected-test.sh"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$sandbox/root/scripts/lib/"

  # Stub gradlew at the sandbox root: connected-test.sh invokes `./gradlew`
  # relative to ROOT_DIR (which it cd's into). Record args + a marker file so the
  # test can assert gradle actually ran, then exit with $STUB_GRADLEW_RC (default 0).
cat > "$sandbox/root/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$STUB_GRADLEW_ARGS_FILE"
printf 'ran\n' > "$STUB_GRADLEW_MARKER"
if [[ "${STUB_GRADLEW_RC:-0}" == "0" && -n "${POCKETSHELL_CONNECTED_TEST_REPORT_DIR:-}" ]]; then
  mkdir -p "$POCKETSHELL_CONNECTED_TEST_REPORT_DIR"
  cat > "$POCKETSHELL_CONNECTED_TEST_REPORT_DIR/TEST-stub.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="stub" tests="1" skipped="0" failures="0" errors="0"><testcase name="stub" classname="stub"/></testsuite>
XML
fi
exit "${STUB_GRADLEW_RC:-0}"
GRADLEW
  chmod +x "$sandbox/root/gradlew"
}

# Is a given serial's per-serial lock currently FREE (i.e. reclaimable)?
# Returns 0 if a non-blocking flock succeeds (free), 1 if held.
serial_lock_free() {
  local root_dir="$1"
  local serial="$2"
  local lock_file
  lock_file="$(
    source "$root_dir/scripts/lib/avd-lock.sh"
    pocketshell_avd_lock_file_for_serial "$root_dir" "$serial"
  )"
  # If the lock file doesn't exist yet, it was never claimed -> trivially free.
  [[ -e "$lock_file" ]] || return 0
  flock -n "$lock_file" true
}

count_reclaimable() {
  local root_dir="$1"
  shift
  local n=0 s
  for s in "$@"; do
    if serial_lock_free "$root_dir" "$s"; then
      n=$((n + 1))
    fi
  done
  printf '%s\n' "$n"
}

# REGRESSION: a complete connected-test.sh --pool run must leave the claimed
# serial reclaimable. With a 2-serial pool, two sequential runs should each
# claim+release cleanly, and at the end BOTH serials are reclaimable
# (claimable-after-run == pool size, not size-1).
reclaim_after_full_pool_run() {
  local sandbox="$1"
  local pool_serials="emulator-5556 emulator-5558"
  make_sandbox "$sandbox" "$pool_serials"
  local sroot="$sandbox/root"

  local run
  for run in 1 2; do
    local args_file="$sandbox/gradlew-args-$run.txt"
    local marker="$sandbox/gradlew-marker-$run.txt"
    # Run the REAL wrapper to completion. PATH puts the fake adb first; ADB/ANDROID_SDK
    # point at it too so the helper's default resolution also finds the fake.
    PATH="$sandbox/bin:$PATH" \
      ADB="$sandbox/bin/adb" \
      ANDROID_SDK="$sandbox" \
      POCKETSHELL_POOL_WAIT_SECONDS=5 \
      POCKETSHELL_POOL_SERIALS="$pool_serials" \
      POCKETSHELL_AGENTS_PORT=2222 \
      STUB_GRADLEW_ARGS_FILE="$args_file" \
      STUB_GRADLEW_MARKER="$marker" \
      STUB_GRADLEW_RC=0 \
      bash "$sroot/scripts/connected-test.sh" --pool \
      > "$sandbox/run-$run.out" 2> "$sandbox/run-$run.err" \
      || fail "connected-test.sh --pool run $run exited non-zero (see $sandbox/run-$run.err)"

    # Gradle must actually have run as a CHILD (the exec->child fix). If `exec`
    # were still used the trap couldn't fire; the marker also proves gradle ran.
    [[ -e "$marker" ]] || fail "stub gradlew did not run on pool run $run"
    grep -q ':app2:connectedDebugAndroidTest' "$args_file" \
      || fail "gradle was not invoked with the connected test task on run $run"
  done

  # THE assertion: after two complete runs, ALL pool serials are reclaimable.
  # $pool_serials is a deliberately space-separated serial list -> word-split it.
  local claimable
  # shellcheck disable=SC2086
  claimable="$(count_reclaimable "$sroot" $pool_serials)"
  local expected
  # shellcheck disable=SC2086
  expected="$(printf '%s\n' $pool_serials | wc -l)"
  printf 'claimable-after-run=%s expected=%s\n' "$claimable" "$expected" >&2
  [[ "$claimable" == "$expected" ]] \
    || fail "pool serial leaked: claimable-after-run=$claimable expected=$expected (exec->child release regression)"
}

# A non-zero gradle exit must STILL release the claim (trap fires on failure too)
# and the wrapper must propagate gradle's exit code.
failed_run_still_releases_and_propagates_rc() {
  local sandbox="$1"
  local pool_serials="emulator-5560 emulator-5562"
  make_sandbox "$sandbox" "$pool_serials"
  local sroot="$sandbox/root"

  set +e
  PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    POCKETSHELL_POOL_WAIT_SECONDS=5 \
    POCKETSHELL_POOL_SERIALS="$pool_serials" \
    POCKETSHELL_AGENTS_PORT=2222 \
    STUB_GRADLEW_ARGS_FILE="$sandbox/args.txt" \
    STUB_GRADLEW_MARKER="$sandbox/marker.txt" \
    STUB_GRADLEW_RC=7 \
    bash "$sroot/scripts/connected-test.sh" --pool \
    > "$sandbox/run.out" 2> "$sandbox/run.err"
  local rc=$?
  set -e

  [[ "$rc" == "7" ]] || fail "wrapper did not propagate gradle exit code (got $rc, want 7)"

  local claimable
  # shellcheck disable=SC2086
  claimable="$(count_reclaimable "$sroot" $pool_serials)"
  local expected
  # shellcheck disable=SC2086
  expected="$(printf '%s\n' $pool_serials | wc -l)"
  [[ "$claimable" == "$expected" ]] \
    || fail "failed run leaked a pool serial: claimable=$claimable expected=$expected"
}

# Issue #1944: the optional evidence capture must run before either lane lock is
# released, on success and failure, and must preserve the wrapper result.
same_run_evidence_is_captured_under_lock_for_success_and_failure() {
  local sandbox="$1"
  local pool_serials="emulator-5570"
  make_sandbox "$sandbox" "$pool_serials"
  local sroot="$sandbox/root"
  mkdir -p "$sroot/tests/docker"
  printf 'fake-key\n' > "$sroot/tests/docker/test_key"
  local serial_lock
  serial_lock="$(
    source "$sroot/scripts/lib/avd-lock.sh"
    pocketshell_avd_lock_file_for_serial "$sroot" "emulator-5570"
  )"

  cat > "$sandbox/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
lock_state=free
if ! flock -n "$EXPECTED_SERIAL_LOCK" true; then lock_state=held; fi
printf '%s|lock=%s\n' "$*" "$lock_state" >> "$FAKE_DOCKER_CALLS"
case "${1:-}" in
  inspect)
    if [[ "$*" == *Health.Status* ]]; then printf 'healthy\n'
    elif [[ "$*" == *State.StartedAt* ]]; then printf 'fixture-id 2026-08-02T00:00:00Z\n'
    else printf '[{"Id":"fixture-id","State":{"StartedAt":"2026-08-02T00:00:00Z"}}]\n'
    fi
    ;;
  # Issue #2712: the #2556 fixture-vintage gate execs the port-2222 container
  # for its baked pins; serve the REAL checkout pins (same provision as
  # make_sandbox's stub -- the gate still greps the sandbox's own copy, so
  # this cannot mask a drift).
  exec)
    if [[ "${3:-}" == "cat" && "${4:-}" == "/opt/pocketshell-fixture/pins.txt" ]]; then
      cat "${AVDPOOL_HARNESS_FIXTURE_PINS:?AVDPOOL_HARNESS_FIXTURE_PINS must name the checkout tests/docker/fixture-pins.txt}"
      exit 0
    fi
    ;;
  ps) printf 'fixture-id pocketshell-test-agents\n' ;;
  logs) printf '2026-08-02T00:00:01Z fixture same-run log\n' ;;
esac
DOCKER
  cat > "$sandbox/bin/ssh" <<'SSH'
#!/usr/bin/env bash
printf 'issue1944 ssh ready tmux 3.4\n'
SSH
  chmod +x "$sandbox/bin/docker" "$sandbox/bin/ssh"

  local wanted_rc
  for wanted_rc in 0 7; do
    local evidence="$sandbox/evidence-$wanted_rc"
    local calls="$sandbox/docker-$wanted_rc.calls"
    set +e
    PATH="$sandbox/bin:$PATH" \
      ADB="$sandbox/bin/adb" \
      ANDROID_SDK="$sandbox" \
      POCKETSHELL_POOL_WAIT_SECONDS=5 \
      POCKETSHELL_POOL_SERIALS="$pool_serials" \
      POCKETSHELL_AGENTS_PORT=2222 \
      POCKETSHELL_AGENTS_FIXTURE_IDENTITY="fixture-id 2026-08-02T00:00:00Z" \
      POCKETSHELL_CONNECTED_EVIDENCE_DIR="$evidence" \
      EXPECTED_SERIAL_LOCK="$serial_lock" \
      FAKE_DOCKER_CALLS="$calls" \
      STUB_GRADLEW_ARGS_FILE="$sandbox/args-$wanted_rc.txt" \
      STUB_GRADLEW_MARKER="$sandbox/marker-$wanted_rc.txt" \
      STUB_GRADLEW_RC="$wanted_rc" \
      bash "$sroot/scripts/connected-test.sh" --pool --suffix "evidence$wanted_rc" \
      > "$sandbox/evidence-$wanted_rc.out" 2> "$sandbox/evidence-$wanted_rc.err"
    local actual_rc=$?
    set -e

    [[ "$actual_rc" == "$wanted_rc" ]] \
      || fail "evidence run rc=$actual_rc, expected $wanted_rc"
    [[ -s "$evidence/docker-ssh-readiness.log" ]] || fail "missing readiness evidence for rc=$wanted_rc"
    [[ -s "$evidence/docker-inspect-start.json" ]] || fail "missing start inspect for rc=$wanted_rc"
    [[ -s "$evidence/docker-inspect-end.json" ]] || fail "missing end inspect for rc=$wanted_rc"
    [[ -s "$evidence/docker-agents.log" ]] || fail "missing Docker log for rc=$wanted_rc"
    [[ -s "$evidence/SHA256SUMS" ]] || fail "missing evidence hashes for rc=$wanted_rc"
    grep -q "wrapper_exit_rc=$wanted_rc" "$evidence/run-manifest-end.txt" \
      || fail "manifest lost wrapper rc=$wanted_rc"
    grep -q 'claim_fingerprint=fixture-id 2026-08-02T00:00:00Z' "$evidence/run-manifest-end.txt" \
      || fail "manifest lost claim fingerprint"
    grep -q 'final_fingerprint=fixture-id 2026-08-02T00:00:00Z' "$evidence/run-manifest-end.txt" \
      || fail "manifest lost final fingerprint"
    if grep -q 'lock=free' "$calls"; then
      fail "evidence command escaped serial ownership before exit (rc=$wanted_rc)"
    fi
    flock -n "$serial_lock" true || fail "evidence run leaked serial lock for rc=$wanted_rc"
  done
}

# Issue #1737: the default non-pool path must resolve the one online emulator
# and acquire/release the SAME per-serial ownership lock used by --pool. The old
# global base lock was a split domain: pool and legacy could both mutate one AVD.
non_pool_suffix_run_acquires_and_releases_serial_lock() {
  local sandbox="$1"
  make_sandbox "$sandbox" "emulator-5556"
  local sroot="$sandbox/root"

  PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    STUB_GRADLEW_ARGS_FILE="$sandbox/args.txt" \
    STUB_GRADLEW_MARKER="$sandbox/marker.txt" \
    STUB_GRADLEW_RC=0 \
    bash "$sroot/scripts/connected-test.sh" --suffix i674 \
    > "$sandbox/run.out" 2> "$sandbox/run.err" \
    || fail "non-pool --suffix run exited non-zero (see $sandbox/run.err)"

  [[ -e "$sandbox/marker.txt" ]] || fail "stub gradlew did not run on non-pool path"
  grep -q -- '-PpocketshellAppIdSuffix=i674' "$sandbox/args.txt" \
    || fail "suffix was not threaded into gradle on non-pool path"

  grep -q 'Pinned legacy lane to owned emulator.*ANDROID_SERIAL=emulator-5556' "$sandbox/run.err" \
    || fail "non-pool run did not pin the sole online emulator"

  local serial_lock
  serial_lock="$(
    source "$sroot/scripts/lib/avd-lock.sh"
    pocketshell_avd_lock_file_for_serial "$sroot" "emulator-5556"
  )"
  [[ -e "$serial_lock" ]] || fail "per-serial ownership lock was never created on non-pool path"
  flock -n "$serial_lock" true \
    || fail "non-pool run leaked the per-serial ownership lock"
  [[ ! -e "$POCKETSHELL_AVD_LOCK_DIR/avd-lock" ]] \
    || fail "non-pool run regressed to the split global lock domain"
}

# Issue #2712: the fixture-vintage gate (#2556) must stay LIVE and fail-closed
# in this harness's pool lanes. make_sandbox provisions the gate honestly (a
# real pins file in the sandbox + a docker stub serving the REAL checkout's
# pins), and this check proves the provision cannot double as a bypass: a
# sandbox whose copied pins DRIFTED from what the stub serves must die at the
# gate BEFORE any gradle invocation, and the stale banner must show the
# stub-served real pin so the refusal is for the right reason (not a dead stub
# answering empty, which would let this guard pass vacuously on <none>).
fixture_vintage_gate_stays_fail_closed_in_pool_lanes() {
  local sandbox="$1"
  local pool_serials="emulator-5574"
  make_sandbox "$sandbox" "$pool_serials"
  local sroot="$sandbox/root"

  # Only the SANDBOX copy drifts; AVDPOOL_HARNESS_FIXTURE_PINS (what the stub
  # serves) stays the real checkout file -- precisely the stale-fixture
  # shape #2556 refuses.
  sed -i 's/^APLEXER_PIN=.*/APLEXER_PIN=9.9.9-avdpool-drift/' \
    "$sroot/tests/docker/fixture-pins.txt"

  local marker="$sandbox/marker.txt"
  local rc=0
  PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    POCKETSHELL_POOL_WAIT_SECONDS=5 \
    POCKETSHELL_POOL_SERIALS="$pool_serials" \
    POCKETSHELL_AGENTS_PORT=2222 \
    STUB_GRADLEW_ARGS_FILE="$sandbox/args.txt" \
    STUB_GRADLEW_MARKER="$marker" \
    STUB_GRADLEW_RC=0 \
    bash "$sroot/scripts/connected-test.sh" --pool \
    > "$sandbox/run.out" 2> "$sandbox/run.err" || rc=$?

  (( rc != 0 )) \
    || fail "pool lane exited 0 although its fixture vintage cannot match the checkout; the #2556 gate is being masked inside this harness (issue #2712)"
  if [[ -e "$marker" ]]; then
    fail "the lane invoked gradle although the fixture-vintage gate must refuse it BEFORE any instrumentation (issue #2712)"
  fi
  grep -qF 'AGENTS FIXTURE STALE' "$sandbox/run.err" \
    || fail "expected the #2556 stale-fixture banner for a drifted pins file, got: $(tail -n 5 "$sandbox/run.err")"
  local real_pin
  real_pin="$(grep -E '^APLEXER_PIN=' "$ROOT_DIR/tests/docker/fixture-pins.txt")"
  [[ -n "$real_pin" ]] || fail "checkout pins file has no APLEXER_PIN line; the guard cannot verify what the stub served"
  grep -qF "$real_pin" "$sandbox/run.err" \
    || fail "the stale banner shows no baked $real_pin, so the stub did not serve the checkout pins and the refusal is for the wrong reason"
}

CASE_COUNT=0

run_case() {
  local name="$1"
  local sandbox
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-avd-pool-test.XXXXXX")"
  "$name" "$sandbox"
  rm -rf "$sandbox"
  CASE_COUNT=$((CASE_COUNT + 1))
  printf '  ok: %s\n' "$name"
}

run_case reclaim_after_full_pool_run
run_case failed_run_still_releases_and_propagates_rc
run_case same_run_evidence_is_captured_under_lock_for_success_and_failure
run_case non_pool_suffix_run_acquires_and_releases_serial_lock
run_case fixture_vintage_gate_stays_fail_closed_in_pool_lanes

# Issue #2113: a harness that exits 0 having run nothing is the vacuous green
# process.md catalogues. The count line is what makes the JVM assertion about
# behaviour rather than about bash's exit status.
(( CASE_COUNT == 5 )) || fail "expected 5 cases to run, saw $CASE_COUNT"
printf 'PASS: avd-pool claim/release (%s cases)\n' "$CASE_COUNT"
