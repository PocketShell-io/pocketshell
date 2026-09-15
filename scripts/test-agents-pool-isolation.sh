#!/usr/bin/env bash
set -euo pipefail

# Agents-fixture pool isolation harness (issue #1842).
#
# THE DEFECT this harness exists to stop coming back: `connected-test.sh --pool`
# promises that concurrent journey lanes claim distinct `(emulator, agents-port)`
# fixtures and therefore never collide (#724). It did not hold. A lane holding
# `agents-2243` had that container recreated underneath it MID-RUN by a sibling
# lane, twice, in separate rounds, costing #1819 and #1820 a review round each.
#
# There were TWO independent ways a sibling reached a claimed lane's fixture, and
# fixing either alone leaves the guarantee broken:
#
#   RC1 — the port lock was CHECKOUT-anchored, not machine-anchored. It lived at
#         `$root_dir/build/.agents-port-lock-$port`, so two `--pool` lanes driven
#         from `.worktrees/issue-A` and `.worktrees/issue-B` flocked DIFFERENT
#         inodes and both "won" the same port. This is precisely the defect
#         #1657 found and fixed in the AVD half; the Docker half never got the
#         same treatment. Observed in #1820 round 3: both lanes on 2243,
#         `pocketshell-test-agents-2243` "Up 7 seconds" mid-run.
#
#   RC2 — port 2222 was the FIRST pool candidate, and ~12 non-pool scripts
#         recreate the 2222 fixture unconditionally (some `--force-recreate`)
#         without ever taking the port lock. No lock can defend 2222. Observed in
#         #1819 det2: the allocator handed the lane 2222 and a sibling
#         `--no-pool` run wiped it (`Up 25 seconds` mid-loop); that whole
#         measurement round was discarded as contaminated.
#
# And a third property, because locks are advisory and `docker` is machine-wide:
#
#   RC3 — a disturbed lane failed SILENTLY. A wiped tmux server presents to the
#         test as an EMPTY SESSION LIST, byte-identical to the signature of two
#         real product defects under investigation at the time (#1810, #1820).
#         Both affected lanes only avoided chasing a phantom product bug because
#         they had already characterised the real one. The claim must therefore
#         be VERIFIABLE, and a disturbed lane must fail unmistakably.
#
# ...and one more, found by the reviewer of the first attempt at this fix:
#
#   RC4 — the claim API INVITED a call shape that silently un-does all three.
#         `pocketshell_claim_agents_port` mutates the calling shell and used to
#         also print the port to stdout, which made `port="$(claim ...)"` read
#         like correct code. It is not: in a subshell the exports and the
#         flock-holding EXIT trap die at the closing paren, so that one line
#         restores RC1 (no lock), RC2 (with $POCKETSHELL_AGENTS_PORT unset,
#         connected-test.sh skips the `agentsPort` arg and the lane falls back
#         to the indefensible 2222) and RC3 (no fingerprint) at once. Round 1
#         documented the footgun and claimed it was pinned; the reviewer mutated
#         the sole production caller into that shape and this harness reported
#         green. Checks 8-11 close that: a static scan of every caller, the
#         runtime refusal, the empty stdout, and the matching release-side check.
#
# ...and the one issue #2501 re-derived, after RC1's fix was (correctly) ruled
# out as the explanation for still-fresh cross-lane collisions:
#
#   RC5 — the per-port flock defended only the writers that took it. The claim
#         path does; `agents-pool.sh up|down <port>` did NOT — it called the
#         fixture lifecycle helpers with no lock at all, so a warm-up or
#         teardown could recreate / `down -v` a HELD port's container mid-run.
#         The #2487 docker-events capture is exactly that shape: `kill` +
#         `destroy` of `pocketshell-test-agents-2243` issued from a sibling
#         worktree while another lane held the port (a claim never issues
#         `down`). The lock existed; this entry point never consulted it —
#         check-then-act where half the actors skip the check. Checks 13-15
#         pin the CLI as a lock-taking writer (atomic critical section, refusal
#         on a held port), sequential/legacy usage unchanged, and — since
#         #1842 could silently regress the same way — that two concurrent
#         claims still resolve to DISTINCT ports.
#
# No docker daemon, no emulator, no Gradle: `docker` is stubbed on PATH and the
# lock anchor is sandboxed via POCKETSHELL_AVD_LOCK_DIR. Racing the real fixture
# pool would corrupt a sibling agent's run — the exact bug under test.
#
# WHY THIS LIVES IN scripts/ AND NOT tests/scripts/. Its closest sibling,
# `tests/scripts/avd-lock-sharing-test.sh` (the #1657 harness that pins the very
# same property for the emulator half), is run by NOTHING: no workflow, no
# Gradle task, no gate references `tests/scripts/` at all. That directory is
# unwired legacy. Every shell test that actually executes per-push is a
# `scripts/test-*.sh` invoked by the Unit job, so this one is too — an unwired
# regression test is not a regression test (G9). The #1657 harness being unwired
# is itself worth a follow-up: the property it pins regressed here undetected.

# A driving shell may already hold pool/AVD state; inherited, it would
# short-circuit the claims below and make the harness self-contend (the #1702
# lesson from the AVD sibling harness). Scrub it.
unset POCKETSHELL_AGENTS_PORT \
      POCKETSHELL_AGENTS_HOLDER_PID \
      POCKETSHELL_AGENTS_OWNER_PID \
      POCKETSHELL_AGENTS_FIXTURE_IDENTITY \
      POCKETSHELL_AGENTS_POOL_PORTS \
      POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_FD \
      POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Hard-fail early rather than silently testing an empty tree: a wrong ROOT_DIR
# makes every check below fail for the wrong reason (it did, when this file
# moved from tests/scripts/ to scripts/ and kept the two-level ../..).
[[ -f "$ROOT_DIR/scripts/lib/agents-pool.sh" ]] \
  || { printf 'FAIL: ROOT_DIR=%s does not look like the repo root\n' "$ROOT_DIR" >&2; exit 2; }

FAILURES=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  FAILURES=$((FAILURES + 1))
  return 1
}

pass() {
  printf '  ok: %s\n' "$1"
}

# Build a fake "worktree": a private root carrying its own copy of the helpers,
# exactly like `.worktrees/issue-N/` carries its own checkout. Sourcing THIS copy
# and passing THIS root is what an agent in that worktree does.
make_worktree() {
  local root="$1"
  mkdir -p "$root/scripts/lib" "$root/build" "$root/tests/docker"
  cp "$ROOT_DIR/scripts/lib/agents-pool.sh" "$root/scripts/lib/agents-pool.sh"
  cp "$ROOT_DIR/scripts/lib/avd-lock.sh" "$root/scripts/lib/avd-lock.sh"
  # Issue #2381: agents-pool.sh also sources this to stamp the fixture's
  # `pocketshell --version` with the APK's derived version. A synthetic
  # worktree missing it makes every `source` in the harness print a
  # "No such file" error, so keep the copied helper set complete.
  cp "$ROOT_DIR/scripts/lib/agents-fixture-version.sh" "$root/scripts/lib/agents-fixture-version.sh"
  cp "$ROOT_DIR/tests/docker/docker-compose.yml" "$root/tests/docker/docker-compose.yml"
}

# A `docker` stub. `compose ... up` is a no-op; `inspect` answers from files so a
# test can simulate a container being recreated under a lane by rewriting them.
install_docker_stub() {
  local bindir="$1" statedir="$2"
  mkdir -p "$bindir" "$statedir"
  printf 'healthy\n' > "$statedir/health"
  printf 'sha256:AAAA 2026-07-29T00:00:00Z\n' > "$statedir/identity"
  cat > "$bindir/docker" <<'DOCKER_STUB'
#!/usr/bin/env bash
set -u
state="${AGENTS_POOL_TEST_STATE:?}"
printf '%s\n' "$*" >> "$state/docker.log"
if [[ "${1:-}" == "compose" ]]; then
  exit 0
fi
if [[ "${1:-}" == "inspect" ]]; then
  case "${2:-}" in
    *Health.Status*) cat "$state/health"; exit 0 ;;
    *.Id*)           cat "$state/identity"; exit 0 ;;
  esac
  exit 1
fi
exit 0
DOCKER_STUB
  chmod +x "$bindir/docker"
}

# Claim a port from $root_dir in its OWN process (the claim keys release on $$
# and installs an EXIT trap). Writes the claimed port to $ready, then holds the
# claim until $release appears. Echoes the claimer's PID.
#
# NOTE the call shape: the claim is invoked DIRECTLY, and the port is read from
# $POCKETSHELL_AGENTS_PORT. A claim made inside a subshell cannot survive (see
# checks 8 and 9 below, which pin that both statically and behaviourally). An
# earlier revision of this harness got this wrong and manufactured a convincing
# false RED -- both lanes "won" the port on the FIXED code.
start_claimer() {
  local root_dir="$1" ready="$2" release="$3" ports="$4" wait_seconds="${5:-60}"
  setsid bash -c '
    set -uo pipefail
    root_dir="$1"; ready="$2"; release="$3"; ports="$4"
    export POCKETSHELL_AGENTS_POOL_PORTS="$ports"
    export POCKETSHELL_AGENTS_WAIT_SECONDS="$5"
    source "$root_dir/scripts/lib/agents-pool.sh"
    pocketshell_claim_agents_port "$root_dir" >/dev/null 2>&1 || exit 1
    printf "%s\n" "${POCKETSHELL_AGENTS_PORT:?}" > "$ready"
    while [[ ! -e "$release" ]]; do sleep 0.05; done
    pocketshell_release_agents_port
  ' bash "$root_dir" "$ready" "$release" "$ports" "$wait_seconds" >/dev/null 2>&1 &
  printf '%s\n' "$!"
}

wait_for_file() {
  local target="$1" timeout="${2:-5}"
  local waited=0
  local limit=$(( timeout * 20 ))
  while [[ ! -e "$target" ]]; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
  return 0
}

kill_group() {
  local pid="${1:-}"
  [[ -n "$pid" ]] || return 0
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

# --------------------------------------------------------------------------
# 1. RC1, THE BUG (red on base): two worktrees must CONTEND for one port.
#
# This is #1820 round 3 reduced to two processes. On the buggy code lane B
# claims the very port lane A is holding, because their lock files are distinct
# inodes under their own worktrees.
# --------------------------------------------------------------------------
two_worktrees_cannot_both_claim_the_same_port() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"

  make_worktree "$tmp/wt-a"
  make_worktree "$tmp/wt-b"

  # ONE candidate port, so "both lanes got 2243" is unambiguous.
  local a_pid b_pid
  a_pid="$(start_claimer "$tmp/wt-a" "$tmp/a.ready" "$tmp/a.release" "2243")"
  if ! wait_for_file "$tmp/a.ready" 15; then
    kill_group "$a_pid"
    fail "worktree A never claimed the only candidate port"
    return 1
  fi

  # B must keep RETRYING long enough to outlive the 6s "must not claim" probe
  # below and the subsequent handover, or a timed-out B would look like a wedge.
  b_pid="$(start_claimer "$tmp/wt-b" "$tmp/b.ready" "$tmp/b.release" "2243" 90)"

  # THE LOAD-BEARING ASSERTION. B must NOT get the port A is holding.
  if wait_for_file "$tmp/b.ready" 6; then
    local a_port b_port
    a_port="$(cat "$tmp/a.ready")"
    b_port="$(cat "$tmp/b.ready")"
    touch "$tmp/a.release" "$tmp/b.release"
    kill_group "$a_pid"; kill_group "$b_pid"
    fail "worktree B claimed port $b_port while worktree A held port $a_port -- the agents-port flock is per-worktree, so it serialises NOTHING (issue #1842 / the #1657 defect in the Docker half)"
    return 1
  fi

  # ... and the claim must QUEUE, not wedge: releasing A must hand the port over.
  touch "$tmp/a.release"
  if ! wait_for_file "$tmp/b.ready" 15; then
    kill_group "$a_pid"; kill_group "$b_pid"
    fail "worktree B never claimed the port after A released it (the claim wedged instead of queuing)"
    return 1
  fi
  touch "$tmp/b.release"
  kill_group "$a_pid"; kill_group "$b_pid"
  pass "two worktrees contend for one agents port (B waits for A, then gets it)"
}

# --------------------------------------------------------------------------
# 2. RC1, the property directly: the lock path must not be checkout-derived.
# --------------------------------------------------------------------------
port_lock_path_is_machine_wide_not_checkout_relative() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  make_worktree "$tmp/path-a"
  make_worktree "$tmp/path-b"

  local a b
  a="$(bash -c 'source "$1/scripts/lib/agents-pool.sh"; pocketshell_agents_lock_file_for_port "$1" 2243' bash "$tmp/path-a")"
  b="$(bash -c 'source "$1/scripts/lib/agents-pool.sh"; pocketshell_agents_lock_file_for_port "$1" 2243' bash "$tmp/path-b")"

  if [[ "$a" != "$b" ]]; then
    fail "the same port resolves to DIFFERENT lock files across worktrees ($a vs $b) -- flock on distinct files serialises nothing (issue #1842)"
    return 1
  fi
  if [[ "$a" == *"path-a"* || "$a" == *"path-b"* || "$a" == *"/build/"* ]]; then
    fail "the agents-port lock resolves under a worktree root ($a); it must be anchored to the machine (issue #1842)"
    return 1
  fi
  # Distinct ports must still be distinct locks, or the pool would serialise
  # every lane behind every other -- a worse regression than the bug (G6).
  local other
  other="$(bash -c 'source "$1/scripts/lib/agents-pool.sh"; pocketshell_agents_lock_file_for_port "$1" 2244' bash "$tmp/path-a")"
  if [[ "$other" == "$a" ]]; then
    fail "distinct ports collapsed onto ONE lock file ($a) -- that would queue every pool lane behind every other"
    return 1
  fi
  pass "agents-port lock is machine-anchored, shared across worktrees, still per-port"
}

# --------------------------------------------------------------------------
# 3. RC1 mutation guard: the CLAIM must lock the file the resolver names.
#
# The defect survived partly because the claim path built the worktree-relative
# path a SECOND time by hand, so `agents-pool.sh status` and the claim could
# disagree about which file is the lock. Fixing only the resolver would leave
# the bug live; this test fails in exactly that case.
# --------------------------------------------------------------------------
claim_locks_the_file_the_resolver_names() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-claim"

  local pid
  pid="$(start_claimer "$tmp/wt-claim" "$tmp/c.ready" "$tmp/c.release" "2245")"
  if ! wait_for_file "$tmp/c.ready" 15; then
    kill_group "$pid"
    fail "claimer never claimed port 2245"
    return 1
  fi

  local lock_file
  lock_file="$(bash -c 'source "$1/scripts/lib/agents-pool.sh"; pocketshell_agents_lock_file_for_port "$1" 2245' bash "$tmp/wt-claim")"

  # A non-blocking flock on the resolver's file must FAIL while the claim is
  # held. If it succeeds, the claim locked some other file.
  if ( flock -n 9 ) 9>"$lock_file" 2>/dev/null; then
    touch "$tmp/c.release"; kill_group "$pid"
    fail "the resolver's lock file ($lock_file) is FREE while a claim is held -- the claim path locks a different file than the status readout reports (issue #1842)"
    return 1
  fi
  touch "$tmp/c.release"; kill_group "$pid"
  pass "the claim holds exactly the lock file the resolver names"
}

# --------------------------------------------------------------------------
# 4. RC2, THE OTHER BUG (red on base): a pool candidate must be a port that
#    ONLY the pool ever writes.
#
# Derived from the scripts themselves rather than hard-coding 2222, so this
# keeps holding if someone adds a new unlocked fixture script or moves the
# default port. Any `docker compose ... up ... agents` that does NOT go through
# scripts/lib/agents-pool.sh hits the compose default AGENTS_HOST_PORT and takes
# no port lock; that port can never be safely handed to a lane.
# --------------------------------------------------------------------------
default_pool_candidates_exclude_unlocked_fixture_ports() {
  local compose="$ROOT_DIR/tests/docker/docker-compose.yml"

  # The port an unlocked `up -d agents` publishes = the compose default.
  local default_port
  default_port="$(sed -n 's/.*AGENTS_HOST_PORT:-\([0-9]\+\).*/\1/p' "$compose" | head -n1)"
  if [[ -z "$default_port" ]]; then
    fail "could not derive AGENTS_HOST_PORT default from $compose"
    return 1
  fi

  # Scripts that recreate the agents fixture without the pool lock.
  local unlocked
  unlocked="$(grep -rln 'docker compose -f "\$[A-Z_]*COMPOSE_FILE[^"]*" up -d' "$ROOT_DIR/scripts" 2>/dev/null \
    | xargs -r grep -l 'agents' \
    | grep -v '/lib/agents-pool.sh$' \
    | grep -v '/agents-pool.sh$' || true)"
  if [[ -z "$unlocked" ]]; then
    fail "expected to find scripts that recreate the agents fixture without the port lock; the detector matched nothing, so this guard would pass vacuously"
    return 1
  fi

  local candidates
  candidates="$(bash -c 'source "'"$ROOT_DIR"'/scripts/lib/agents-pool.sh"; pocketshell_agents_pool_ports')"

  local port
  for port in $candidates; do
    if [[ "$port" == "$default_port" ]]; then
      fail "port $default_port is a --pool candidate, but these scripts recreate that fixture WITHOUT taking the port lock, so no lock can defend it (issue #1842 / #1819 det2): $(printf '%s ' $unlocked)"
      return 1
    fi
  done
  pass "no --pool candidate is a port the unlocked single-lane scripts recreate (default $default_port excluded; $(printf '%s' "$unlocked" | wc -l) unlocked writers found)"
}

# --------------------------------------------------------------------------
# 5. RC3 (red on base): a disturbed fixture must fail LOUDLY, and its message
#    must be impossible to mistake for an empty-session-list product bug.
# --------------------------------------------------------------------------
a_disturbed_fixture_fails_with_an_unmistakable_signature() {
  local tmp="$1"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  # shellcheck source=scripts/lib/agents-pool.sh
  source "$ROOT_DIR/scripts/lib/agents-pool.sh"

  printf 'sha256:BEFORE 2026-07-29T00:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  pocketshell_agents_record_fixture_identity 2243

  # Undisturbed: must be quiet and pass.
  if ! pocketshell_agents_assert_fixture_undisturbed 2243 2>/dev/null; then
    fail "an UNDISTURBED fixture was reported as disturbed -- this guard would cry wolf on every run"
    return 1
  fi

  # A sibling recreates the container: new id AND new start time.
  printf 'sha256:AFTER 2026-07-29T01:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"

  local out rc=0
  out="$(pocketshell_agents_assert_fixture_undisturbed 2243 2>&1)" || rc=$?
  if (( rc == 0 )); then
    fail "a fixture recreated mid-run was NOT detected -- the lane would report an empty session list as a product defect (issue #1842)"
    return 1
  fi

  # The whole point: the message must name the collision AND explicitly
  # inoculate the reader against the #1810/#1820 product signature.
  local phrase
  for phrase in "AGENTS FIXTURE DISTURBED" "2243" "empty session list" "NOT evidence of a product" "1842"; do
    if [[ "$out" != *"$phrase"* ]]; then
      fail "the disturbed-fixture banner is missing '$phrase'; it must be impossible to mistake for an empty-session-list product bug. Got: $out"
      return 1
    fi
  done

  # A restart alone (same id, new StartedAt) must also be caught.
  printf 'sha256:BEFORE 2026-07-29T02:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  if pocketshell_agents_assert_fixture_undisturbed 2243 >/dev/null 2>&1; then
    fail "a RESTARTED fixture (same container id, new StartedAt) was not detected"
    return 1
  fi
  pass "a disturbed fixture fails loudly and names the collision, not the product"
}

# --------------------------------------------------------------------------
# 6. RC3 (red on base): a disturbed lane must void the run in BOTH directions.
#    A disturbed PASS is exactly as worthless as a disturbed FAIL.
# --------------------------------------------------------------------------
a_disturbed_fixture_voids_the_run_in_both_directions() {
  local tmp="$1"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  # shellcheck source=scripts/lib/agents-pool.sh
  source "$ROOT_DIR/scripts/lib/agents-pool.sh"

  printf 'sha256:BEFORE 2026-07-29T00:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  pocketshell_agents_record_fixture_identity 2243
  export POCKETSHELL_AGENTS_PORT=2243

  # Undisturbed: the wrapper's verdict is passed through untouched.
  local got
  got="$(pocketshell_agents_final_rc 0 2>/dev/null)"
  [[ "$got" == "0" ]] || { fail "undisturbed pass was rewritten to rc=$got"; return 1; }
  got="$(pocketshell_agents_final_rc 1 2>/dev/null)"
  [[ "$got" == "1" ]] || { fail "undisturbed failure was rewritten to rc=$got"; return 1; }

  printf 'sha256:AFTER 2026-07-29T01:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"

  # THE LOAD-BEARING ASSERTION: a GREEN run on a disturbed fixture must not be
  # reported as green. It asserted against a fixture that stopped existing.
  got="$(pocketshell_agents_final_rc 0 2>/dev/null)"
  if [[ "$got" == "0" ]]; then
    fail "a PASS on a disturbed fixture was reported as a pass -- the run asserted against a container that was replaced mid-run, so the green is void (issue #1842)"
    return 1
  fi
  [[ "$got" == "90" ]] || { fail "expected the distinct disturbed rc 90, got $got"; return 1; }

  got="$(pocketshell_agents_final_rc 1 2>/dev/null)"
  [[ "$got" == "90" ]] || { fail "a FAIL on a disturbed fixture kept rc=$got; it must be relabelled 90 so it is not read as a product defect"; return 1; }

  unset POCKETSHELL_AGENTS_PORT
  pass "a disturbed fixture voids the run in both directions with a distinct rc 90"
}

# --------------------------------------------------------------------------
# 6c. ISSUE #2574, the SEMANTICS the arming point relies on. The wrapper
#     records the claim-time fingerprint inside pocketshell_claim_agents_port,
#     then does its own fixture work (the per-lane network-fault bring-up)
#     before instrumentation. The window that must be guarded is arming -> end,
#     so the wrapper RE-ARMS there (see the #2574 block in
#     scripts/connected-test.sh). This pins, on the stubbed docker, the
#     sequence the arming produces:
#
#       record(claim) -> [wrapper churn] -> record(arm) -> [mid-run churn] -> end-check
#
#     Churn BEFORE the re-arm must be quiet: it is the wrapper's own doing and
#     happened before instrumentation, so printing the #1842 banner for it is
#     exactly the self-inflicted rc 90 issue #2574 exists to kill. Churn AFTER
#     it must still void the run in both directions, exactly as check 6
#     requires. The static half — that connected-test.sh really calls the
#     re-arm after its last fixture action — is pinned by
#     wrapper_arms_the_fingerprint_after_its_last_fixture_action below. Neither
#     check is sufficient alone: this one stays green if the wrapper's call
#     site moves, that one stays green if the semantics drift.
# --------------------------------------------------------------------------
rearm_makes_pre_instrumentation_churn_quiet_and_post_arming_churn_fatal() {
  local tmp="$1"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state-2574"
  mkdir -p "$AGENTS_POOL_TEST_STATE"
  # shellcheck source=scripts/lib/agents-pool.sh
  source "$ROOT_DIR/scripts/lib/agents-pool.sh"

  printf 'sha256:CLAIM 2026-07-29T00:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  pocketshell_agents_record_fixture_identity 2243

  # Wrapper-inflicted churn between claim and instrumentation (the #2561
  # class), then the #2574 arming point.
  printf 'sha256:CHURN 2026-07-29T00:00:10Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  pocketshell_agents_record_fixture_identity 2243
  export POCKETSHELL_AGENTS_PORT=2243

  # The pre-instrumentation churn must NOT print the banner or void the verdict.
  local got
  if ! got="$(pocketshell_agents_final_rc 0 2>"$tmp/note-2574")"; then
    fail "the re-armed guard rejected an UNDISTURBED instrumentation window (issue #2574)"
    return 1
  fi
  [[ "$got" == "0" ]] || { fail "churn before the arming point rewrote a green verdict to rc=$got (issue #2574)"; return 1; }
  if grep -q "AGENTS FIXTURE DISTURBED" "$tmp/note-2574"; then
    fail "the #1842 banner fired for churn that happened BEFORE instrumentation — the self-inflicted rc 90 issue #2574 exists to kill"
    return 1
  fi

  # Churn after the arming point is a REAL mid-run disturbance: still fatal in
  # both directions.
  printf 'sha256:RECREATED 2026-07-29T00:01:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  got="$(pocketshell_agents_final_rc 0 2>/dev/null)"
  [[ "$got" == "90" ]] || { fail "churn after the arming point did not void a green run (got rc=$got, want 90) (issue #2574)"; return 1; }
  got="$(pocketshell_agents_final_rc 1 2>/dev/null)"
  [[ "$got" == "90" ]] || { fail "churn after the arming point did not void a red run (got rc=$got, want 90) (issue #2574)"; return 1; }

  unset POCKETSHELL_AGENTS_PORT
  pass "churn before the #2574 arming point is quiet; churn after it still voids the run (rc 90 both directions)"
}

# --------------------------------------------------------------------------
# 6d. ISSUE #2574, the STATIC half of the ordering: in
#     scripts/connected-test.sh the arming call
#     (pocketshell_agents_record_fixture_identity) must sit AFTER the last
#     wrapper action that can touch the claimed container (the per-lane
#     network-fault bring-up; the claim itself is earlier still) and BEFORE
#     instrumentation starts. No harness drives the real wrapper against a
#     real fixture, so only this source-level pin catches the ordering moving
#     back — the exact regression that re-opens the self-inflicted rc 90.
# --------------------------------------------------------------------------
wrapper_arms_the_fingerprint_after_its_last_fixture_action() {
  local connected="$ROOT_DIR/scripts/connected-test.sh"
  [[ -f "$connected" ]] || { fail "scripts/connected-test.sh is missing; cannot pin the #2574 arming order"; return 1; }

  # Reuse the module-level CLAIM_FN (assembled from two literals) so this file
  # never contains the contiguous claim-function token outside a comment — the
  # check-8 scan reads this file's source too, and a grep pattern or fail
  # message carrying the bare token reads as a banned call shape to it.
  local claim_line fault_line arm_line gradle_line
  claim_line="$(grep -n "${CLAIM_FN} \"\$ROOT_DIR\"" "$connected" | head -n1 | cut -d: -f1)"
  fault_line="$(grep -n 'pocketshell_network_fault_fixture_up "\$ROOT_DIR"' "$connected" | head -n1 | cut -d: -f1)"
  arm_line="$(grep -n 'pocketshell_agents_record_fixture_identity "\$POCKETSHELL_AGENTS_PORT"' "$connected" | head -n1 | cut -d: -f1)"
  gradle_line="$(grep -n 'pocketshell_run_guarded_mutation pocketshell_scope_run' "$connected" | head -n1 | cut -d: -f1)"

  # Non-vacuous guards first: every anchor must resolve, and the arming call
  # must be the ONLY record call in the wrapper (the claim-side record lives in
  # scripts/lib/agents-pool.sh, so a second call site here means two arming
  # points fighting over one fingerprint).
  if [[ -z "$claim_line" ]]; then
    fail "connected-test.sh no longer calls ${CLAIM_FN}; the anchors this ordering check needs are gone (issue #2574)"
    return 1
  fi
  if [[ -z "$fault_line" ]]; then
    fail "connected-test.sh no longer calls pocketshell_network_fault_fixture_up; the anchor this ordering check needs is gone (issue #2574)"
    return 1
  fi
  if [[ -z "$arm_line" ]]; then
    fail "connected-test.sh lost its #2574 arming call — the #1842 fingerprint is claim-time only again, so any wrapper fixture work between claim and instrumentation can self-inflict the DISTURBED banner (issue #2574)"
    return 1
  fi
  if [[ -z "$gradle_line" ]]; then
    fail "could not find the gradle anchor (pocketshell_run_guarded_mutation pocketshell_scope_run) in connected-test.sh; the arming-before-instrumentation half is unverifiable (issue #2574)"
    return 1
  fi
  local records
  records="$(grep -c 'pocketshell_agents_record_fixture_identity "\$POCKETSHELL_AGENTS_PORT"' "$connected" || true)"
  if (( records != 1 )); then
    fail "expected exactly ONE arming call in connected-test.sh, found $records — multiple arming points fight over the fingerprint (issue #2574)"
    return 1
  fi

  if (( arm_line <= claim_line )); then
    fail "connected-test.sh arms the #1842 fingerprint (line $arm_line) at or before the claim (line $claim_line) — impossible ordering (issue #2574)"
    return 1
  fi
  if (( arm_line <= fault_line )); then
    fail "connected-test.sh arms the #1842 fingerprint (line $arm_line) at or before the fault bring-up (line $fault_line) — the bring-up is back inside the guarded window and self-inflicts the DISTURBED banner (issue #2574)"
    return 1
  fi
  if (( arm_line >= gradle_line )); then
    fail "connected-test.sh arms the #1842 fingerprint (line $arm_line) at or after the gradle invocation (line $gradle_line) — the guard no longer spans the instrumentation window (issue #2574)"
    return 1
  fi
  pass "connected-test.sh arms the #1842 fingerprint after the fault bring-up (line $arm_line > $fault_line) and before instrumentation (line $gradle_line)"
}

# --------------------------------------------------------------------------
# 6b. NON-GOAL GUARD: a single-lane / CI run (no --pool, so no claim and no
#     fingerprint) must pass its verdict through completely untouched. The issue
#     explicitly rules out changing --no-pool behaviour, and a guard that
#     rewrote a legitimate CI exit code would be far worse than the bug.
# --------------------------------------------------------------------------
a_single_lane_run_is_completely_unaffected() {
  local tmp="$1"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  # shellcheck source=scripts/lib/agents-pool.sh
  source "$ROOT_DIR/scripts/lib/agents-pool.sh"

  # Exactly the single-lane state: nothing claimed, nothing fingerprinted.
  unset POCKETSHELL_AGENTS_PORT POCKETSHELL_AGENTS_FIXTURE_IDENTITY

  # Even with the container visibly changing underneath, a non-pool run has made
  # no claim, so there is nothing to enforce and nothing to report.
  printf 'sha256:CHANGED 2026-07-29T09:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"

  local rc code
  for code in 0 1 137; do
    rc="$(pocketshell_agents_final_rc "$code" 2>/dev/null)"
    if [[ "$rc" != "$code" ]]; then
      fail "a single-lane (--no-pool) run had its exit code rewritten $code -> $rc; the guard must be inert without a claim"
      return 1
    fi
  done
  pass "a single-lane / CI run passes its verdict through untouched (guard inert without a claim)"
}

# --------------------------------------------------------------------------
# 7. G2 — the OTHER half of a pool claim. #1657 anchored the emulator-serial
#    lock to the machine; verify that is still true and that both halves of the
#    claim now share ONE anchor, so they cannot drift apart again.
# --------------------------------------------------------------------------
emulator_serial_claim_is_machine_anchored_too() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  make_worktree "$tmp/g2-a"
  make_worktree "$tmp/g2-b"

  local a b
  a="$(bash -c 'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" emulator-5556' bash "$tmp/g2-a")"
  b="$(bash -c 'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" emulator-5556' bash "$tmp/g2-b")"
  if [[ "$a" != "$b" ]]; then
    fail "G2: the emulator-serial lock is per-worktree ($a vs $b) -- the same defect as the port lock"
    return 1
  fi
  if [[ "$a" == *"g2-a"* || "$a" == *"/build/"* ]]; then
    fail "G2: the emulator-serial lock resolves under a worktree root ($a)"
    return 1
  fi

  # Both halves of a lane claim must live in the SAME machine-wide anchor.
  local port_lock
  port_lock="$(bash -c 'source "$1/scripts/lib/agents-pool.sh"; pocketshell_agents_lock_file_for_port "$1" 2243' bash "$tmp/g2-a")"
  if [[ "$(dirname "$a")" != "$(dirname "$port_lock")" ]]; then
    fail "G2: the serial lock ($a) and the port lock ($port_lock) use DIFFERENT anchors; they must share one so a future fix cannot repair only half a claim"
    return 1
  fi
  pass "G2: emulator-serial claim is machine-anchored and shares the port claim's anchor"
}

# --------------------------------------------------------------------------
# 8. THE ROUND-1 HOLE. A claim written in a subshell silently un-fixes all three
#    root causes at once, and nothing caught it.
#
# `pocketshell_claim_agents_port` does not return a value; it MUTATES the
# calling shell (three exports + an EXIT trap holding the flock). Write it as
# `port="$(pocketshell_claim_... )"` and every one of those lands in a process
# that dies at the closing paren: `$$` inside a command substitution is still
# the PARENT's pid, so the subshell's EXIT trap passes the owner check and
# releases the claim immediately. The caller is left with a plausible port
# number, no lock, and an unset $POCKETSHELL_AGENTS_PORT -- which means
# connected-test.sh skips threading `agentsPort` and the lane silently falls
# back to 2222 (RC2), and the fingerprint guard has nothing recorded so a
# disturbed lane goes quiet again (RC3). Round 1 documented this footgun in a
# comment and claimed it was pinned. It was not: the reviewer mutated the sole
# production caller into that shape and this harness reported 8/8 green.
#
# So it is pinned twice now, at both ends:
#   * statically, HERE, across every caller in the shell trees -- this is the
#     check that goes red on that exact mutation;
#   * behaviourally, in check 9, on the runtime guard that refuses the call.
#
# The banned shapes are anything that puts the call in a subshell: `$(...)`,
# backticks, `( ... )`, or a pipeline. Rather than enumerate those, the rule is
# a whitelist -- a call must BE a command, i.e. after stripping `if`/`while`/`!`
# the line begins with the function name.
# --------------------------------------------------------------------------

# Assembled from two literals so this file never itself contains the token in a
# banned shape (the scan below reads its own source, and would flag it).
CLAIM_FN="pocketshell_claim_agents""_port"

no_production_caller_captures_the_claim_in_a_subshell() {
  local hits
  hits="$(grep -rn -F "$CLAIM_FN" "$ROOT_DIR/scripts" "$ROOT_DIR/tests" \
    --include='*.sh' 2>/dev/null || true)"
  if [[ -z "$hits" ]]; then
    fail "the caller scan matched NOTHING, so this guard would pass vacuously -- did $CLAIM_FN get renamed?"
    return 1
  fi

  local call_sites=0 bad="" line code normalised piped
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    code="${line#*:*:}"
    # Prose about the call is not a call.
    [[ "$code" =~ ^[[:space:]]*\# ]] && continue
    # The definition itself is not a call.
    [[ "$code" =~ ^[[:space:]]*"$CLAIM_FN"\(\) ]] && continue
    call_sites=$((call_sites + 1))
    normalised="$(printf '%s' "$code" \
      | sed -E 's/^[[:space:]]*//; s/^(if|elif|while|until)[[:space:]]+//; s/^[[:space:]]*![[:space:]]*//; s/^[[:space:]]*//')"
    # `||` is a list operator, not a pipe; drop it before looking for a pipe.
    piped="${code//||/}"
    if [[ "$normalised" != "$CLAIM_FN"* || "$piped" == *"|"* ]]; then
      bad+="  $line"$'\n'
    fi
  done <<< "$hits"

  if (( call_sites == 0 )); then
    fail "the caller scan found only comments and the definition -- with no real call site this guard proves nothing (issue #1842)"
    return 1
  fi
  if [[ -n "$bad" ]]; then
    fail "a caller invokes $CLAIM_FN in a SUBSHELL. The claim mutates the calling shell (exports + the flock-holding EXIT trap), so a subshell discards it and releases the lock at the closing paren -- while looking like it worked. That single line silently restores RC1 (no lock), RC2 (POCKETSHELL_AGENTS_PORT unset, so the lane falls back to 2222) and RC3 (no fingerprint, so a disturbed fixture goes undetected). Call it directly and read \$POCKETSHELL_AGENTS_PORT. Offending line(s):
$bad"
    return 1
  fi
  pass "no production caller captures the claim in a subshell ($call_sites call site(s) checked)"
}

# --------------------------------------------------------------------------
# 9. ... and the API refuses the mistake at runtime, so a caller the static scan
#    cannot see (a new script, an interactive shell) fails LOUDLY rather than
#    running unprotected. Red on base, which has no guard at all: there the
#    subshell call "succeeds", hands back a port, and leaves no lock behind.
# --------------------------------------------------------------------------
a_subshell_claim_is_refused_loudly() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-sub"

  # Generated rather than written literally, for the same reason CLAIM_FN is
  # split: this file must not contain the banned shape.
  local probe="$tmp/subshell-probe.sh"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'set -uo pipefail\n'
    printf 'export POCKETSHELL_AGENTS_POOL_PORTS=2246\n'
    printf 'export POCKETSHELL_AGENTS_WAIT_SECONDS=5\n'
    printf 'source "%s/scripts/lib/agents-pool.sh"\n' "$tmp/wt-sub"
    printf 'captured="$(%s "%s")" && rc=0 || rc=$?\n' "$CLAIM_FN" "$tmp/wt-sub"
    printf 'printf "rc=%%s\\n" "$rc"\n'
    printf 'printf "captured=%%s\\n" "${captured:-<EMPTY>}"\n'
    printf 'printf "port_env=%%s\\n" "${POCKETSHELL_AGENTS_PORT:-<UNSET>}"\n'
  } > "$probe"

  local out="$tmp/subshell-probe.out" err="$tmp/subshell-probe.err"
  local probe_pid
  setsid bash "$probe" > "$out" 2> "$err" &
  probe_pid=$!
  wait "$probe_pid" 2>/dev/null || true
  # A base-tree run really does start a lock holder; do not leave it sleeping.
  kill_group "$probe_pid"

  local rc port_env
  rc="$(sed -n 's/^rc=//p' "$out" | head -n1)"
  port_env="$(sed -n 's/^port_env=//p' "$out" | head -n1)"

  if [[ "$rc" == "0" ]]; then
    fail "a claim made inside a command substitution REPORTED SUCCESS (captured='$(sed -n 's/^captured=//p' "$out" | head -n1)', port_env='$port_env'). It cannot have succeeded: the exports and the flock-holding EXIT trap died with the subshell. Silently succeeding here is what restores RC1/RC2/RC3 in one line (issue #1842)"
    return 1
  fi

  local phrase
  for phrase in "CALLED IN A SUBSHELL" "POCKETSHELL_AGENTS_PORT" "1842"; do
    if ! grep -qF "$phrase" "$err"; then
      fail "the subshell refusal is missing '$phrase'; it must name the mistake and the correct call shape. Got: $(cat "$err")"
      return 1
    fi
  done

  # Refusing must mean refusing: no half-claim may be left behind.
  local lock_file
  lock_file="$(bash -c 'source "$1/scripts/lib/agents-pool.sh"; pocketshell_agents_lock_file_for_port "$1" 2246' bash "$tmp/wt-sub")"
  if [[ -e "$lock_file" ]] && ! ( flock -n 9 ) 9>"$lock_file" 2>/dev/null; then
    fail "the refused subshell claim still left port 2246 locked ($lock_file) -- a refusal must take no lock at all"
    return 1
  fi
  pass "a claim attempted in a subshell is refused loudly, with no lock left behind"
}

# --------------------------------------------------------------------------
# 10. ... and the API stops INVITING the mistake in the first place. The claim
#     used to `printf` the port to stdout on success, which is what made
#     `port="$(claim ...)"` read like correct code -- a function that prints its
#     result looks like a function you may capture. There is no result: the port
#     arrives via $POCKETSHELL_AGENTS_PORT. Stdout must stay empty so a capture
#     can never look like it worked (issue #1842).
#
# Claimed from a fresh `bash script` (its own $$, so a real main-shell claim,
# not a subshell) with stdout redirected to a file.
# --------------------------------------------------------------------------
a_successful_claim_returns_nothing_on_stdout() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-stdout"

  local probe="$tmp/stdout-probe.sh"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'set -uo pipefail\n'
    printf 'export POCKETSHELL_AGENTS_POOL_PORTS=2247\n'
    printf 'export POCKETSHELL_AGENTS_WAIT_SECONDS=5\n'
    printf 'source "%s/scripts/lib/agents-pool.sh"\n' "$tmp/wt-stdout"
    printf '%s "%s" || exit 1\n' "$CLAIM_FN" "$tmp/wt-stdout"
    printf 'printf "PORT_ENV=%%s\\n" "${POCKETSHELL_AGENTS_PORT:-<UNSET>}" >&2\n'
  } > "$probe"

  local out="$tmp/stdout-probe.out" err="$tmp/stdout-probe.err"
  local probe_pid
  setsid bash "$probe" > "$out" 2> "$err" &
  probe_pid=$!
  wait "$probe_pid" 2>/dev/null || true
  kill_group "$probe_pid"

  if ! grep -qF 'PORT_ENV=2247' "$err"; then
    fail "the direct claim did not succeed, so this check cannot say anything about its stdout (guard against a vacuous pass). stderr: $(cat "$err")"
    return 1
  fi
  if [[ -s "$out" ]]; then
    fail "a successful claim wrote '$(tr -d '\n' < "$out")' to STDOUT. It must write nothing there: printing the port is what makes \`port=\"\$(claim ...)\"\` read like working code, and that capture silently discards the lock, the exports and the fingerprint (issue #1842). The port is \$POCKETSHELL_AGENTS_PORT; the human-readable line belongs on stderr"
    return 1
  fi
  pass "a successful claim returns nothing on stdout (no value to capture, so no capture to get wrong)"
}

# --------------------------------------------------------------------------
# 11. The release side of the same `$$` confusion. `pocketshell_release_agents_port`
#     decides "is this claim mine?" by comparing the recorded owner pid against
#     the current one. Keyed on `$$`, a SUBSHELL passes that test -- `$$` there
#     still reports the parent's pid -- so a stray `x="$(pocketshell_release_...)"`
#     tears down a claim the subshell does not own, and the lane keeps running
#     with the port unlocked and the fingerprint unset: RC1 and RC3 back, quietly.
#     Keyed on $BASHPID it cannot. (This is the same defect as check 9 wearing the
#     other hat, which is why both ends are keyed the same way.)
# --------------------------------------------------------------------------
a_subshell_cannot_release_the_owning_shells_claim() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-rel"

  local probe="$tmp/release-probe.sh"
  {
    printf '#!/usr/bin/env bash\n'
    printf 'set -uo pipefail\n'
    printf 'export POCKETSHELL_AGENTS_POOL_PORTS=2248\n'
    printf 'export POCKETSHELL_AGENTS_WAIT_SECONDS=5\n'
    printf 'source "%s/scripts/lib/agents-pool.sh"\n' "$tmp/wt-rel"
    printf '%s "%s" >/dev/null || exit 1\n' "$CLAIM_FN" "$tmp/wt-rel"
    printf 'printf "CLAIMED=%%s\\n" "${POCKETSHELL_AGENTS_PORT:-<UNSET>}" >&2\n'
    # The accident: a release evaluated inside a command substitution.
    printf 'stray="$(pocketshell_release_agents_port)"\n'
    printf 'lock="$(pocketshell_agents_lock_file_for_port "%s" 2248)"\n' "$tmp/wt-rel"
    printf 'if ( flock -n 9 ) 9>"$lock" 2>/dev/null; then\n'
    printf '  printf "LOCK=FREE\\n" >&2\n'
    printf 'else\n'
    printf '  printf "LOCK=HELD\\n" >&2\n'
    printf 'fi\n'
    printf 'trap - EXIT\n'
    printf 'pocketshell_release_agents_port\n'
  } > "$probe"

  local err="$tmp/release-probe.err"
  local probe_pid
  setsid bash "$probe" >/dev/null 2> "$err" &
  probe_pid=$!
  wait "$probe_pid" 2>/dev/null || true
  kill_group "$probe_pid"

  if ! grep -qF 'CLAIMED=2248' "$err"; then
    fail "the probe never claimed port 2248, so this check would pass vacuously. stderr: $(cat "$err")"
    return 1
  fi
  if grep -qF 'LOCK=FREE' "$err"; then
    fail "a release evaluated in a SUBSHELL tore down the owning shell's claim -- the owner check is keyed on \$\$, which a subshell inherits from its parent, so it wrongly matched. The lane would keep running on an UNLOCKED port with no fingerprint recorded: RC1 and RC3, silently (issue #1842)"
    return 1
  fi
  if ! grep -qF 'LOCK=HELD' "$err"; then
    fail "the probe reported neither LOCK=HELD nor LOCK=FREE; the check did not run. stderr: $(cat "$err")"
    return 1
  fi
  pass "a subshell cannot release the owning shell's claim (owner check keyed on \$BASHPID, not \$\$)"
}

# --------------------------------------------------------------------------
# 12. Drift guard on the identity resolvers.
#
# RC1 survived a whole round of fixing because the lock path was written out by
# hand at a SECOND site, so the claim and `agents-pool.sh status` disagreed about
# which file was the lock. Container name and compose project are the same shape
# of state -- "which container is port 2222?" was open-coded at five sites, and a
# fingerprint that watched a different container than the lifecycle helpers
# create would be exactly as silent as the original bug.
#
# They are centralised now; this keeps them that way. Enforced structurally
# rather than asserted in a comment, because round 1 shipped a comment claiming
# the seam was closed while it was open.
# --------------------------------------------------------------------------
compose_identity_is_spelled_out_in_exactly_one_place() {
  local lib="$ROOT_DIR/scripts/lib/agents-pool.sh"
  local name expected sites
  # Each literal, in code (comments stripped), must appear only inside its own
  # resolver -- i.e. on the small number of lines that resolver needs.
  local -a literals=("pocketshell-test-agents" "psagents")
  local -a limits=(2 1)
  local i=0
  for name in "${literals[@]}"; do
    expected="${limits[$i]}"
    i=$((i + 1))
    # shellcheck disable=SC2126  # `grep -c` exits 1 on zero matches, which
    # under `set -e` would abort before the deliberate vacuous-pass check below.
    sites="$(grep -n -F "$name" "$lib" | grep -v ':[[:space:]]*#' | wc -l)"
    if (( sites == 0 )); then
      fail "the literal '$name' vanished from $lib; this drift guard would pass vacuously (issue #1842)"
      return 1
    fi
    if (( sites > expected )); then
      fail "'$name' is written out at $sites code sites in $lib (expected $expected, inside its resolver only). A second hand-written copy of a lane's identity is exactly how RC1 survived its first fix -- the claim and the status readout disagreed about which file was the lock. Route the new site through pocketshell_agents_container_for_port / _project_for_port / _compose_env_for_port instead:
$(grep -n -F "$name" "$lib" | grep -v ':[[:space:]]*#' | sed 's/^/    /')"
      return 1
    fi
  done
  pass "container name and compose project are each resolved in exactly one place"
}

# --------------------------------------------------------------------------
# 13. RC5, THE BUG (red on base): `agents-pool.sh up|down` used to mutate a
#     port's fixture with NO lock, so a warm-up/teardown racing a live lane
#     recreated or `down -v`-ed the lane's container mid-run — the #2487
#     docker-events collision (kill + destroy from a sibling worktree while
#     another lane held the port; a claim never issues `down`). The CLI must
#     take the claim's own per-port flock atomically and REFUSE a held port,
#     issuing no compose command at all.
#
#     A positive control (the same command AFTER the lane releases) proves the
#     CLI can still reach the fixture, so the refusal is not a vacuous pass.
# --------------------------------------------------------------------------
pool_cli_refuses_to_mutate_a_port_a_lane_is_holding() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-cli"

  local pid
  pid="$(start_claimer "$tmp/wt-cli" "$tmp/cli.ready" "$tmp/cli.release" "2249")"
  if ! wait_for_file "$tmp/cli.ready" 15; then
    kill_group "$pid"
    fail "claimer never claimed port 2249; the CLI refusal check would prove nothing"
    return 1
  fi

  # THE LOAD-BEARING ASSERTION, teardown half: a live lane holds 2249, so
  # `down` must refuse AND must not issue `compose down` at all — that command
  # is what killed + destroyed the #2487 lane's container.
  : > "$AGENTS_POOL_TEST_STATE/docker.log"
  if "$ROOT_DIR/scripts/agents-pool.sh" down 2249 >/dev/null 2>&1; then
    touch "$tmp/cli.release"; kill_group "$pid"
    fail "agents-pool.sh down succeeded against port 2249 while a live lane held its flock -- the CLI tears down a running lane's fixture without consulting the port lock (issue #2501 / the #2487 docker-events kill+destroy)"
    return 1
  fi
  if grep -q "down -v" "$AGENTS_POOL_TEST_STATE/docker.log" 2>/dev/null; then
    touch "$tmp/cli.release"; kill_group "$pid"
    fail "the refused down still issued 'compose down -v' before refusing -- the check and the act must be one atomic critical section, not check-then-act (issue #2501)"
    return 1
  fi

  # ... and the recreate half: `up` over a held port would recreate the
  # container under the lane (the 'Recreated' shape the disturbance guard
  # catches ~9s into a run).
  : > "$AGENTS_POOL_TEST_STATE/docker.log"
  if "$ROOT_DIR/scripts/agents-pool.sh" up 2249 >/dev/null 2>&1; then
    touch "$tmp/cli.release"; kill_group "$pid"
    fail "agents-pool.sh up succeeded against port 2249 while a live lane held its flock -- the CLI recreates a running lane's container (issue #2501)"
    return 1
  fi
  if grep -q "up -d" "$AGENTS_POOL_TEST_STATE/docker.log" 2>/dev/null; then
    touch "$tmp/cli.release"; kill_group "$pid"
    fail "the refused up still issued 'compose up -d' before refusing (issue #2501)"
    return 1
  fi

  # Positive control: once the lane releases, the SAME command must succeed
  # AND reach compose -- proving the refusals above were the lock, not a
  # broken CLI that cannot act at all.
  touch "$tmp/cli.release"
  kill_group "$pid"
  : > "$AGENTS_POOL_TEST_STATE/docker.log"
  if ! "$ROOT_DIR/scripts/agents-pool.sh" down 2249 >/dev/null 2>&1; then
    fail "after the lane released 2249, agents-pool.sh down still refused -- sequential teardown must keep working (issue #2501 AC4)"
    return 1
  fi
  if ! grep -q "down -v" "$AGENTS_POOL_TEST_STATE/docker.log"; then
    fail "the post-release down wrote no compose command -- the refusal check above could pass vacuously on a CLI that never reaches docker"
    return 1
  fi
  pass "agents-pool.sh refuses a HELD port (no compose issued) and works once it is free"
}

# --------------------------------------------------------------------------
# 14. AC4: sequential pool usage and the legacy single-lane identity keep
#     working. Warm-up -> lanes -> teardown runs with free locks at every
#     step; `up/down 2222` (explicit legacy management, never a pool
#     candidate, issue #1842) is untouched.
# --------------------------------------------------------------------------
pool_cli_still_manages_free_ports_and_the_legacy_identity() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-seq"

  : > "$AGENTS_POOL_TEST_STATE/docker.log"
  if ! "$ROOT_DIR/scripts/agents-pool.sh" up 2244 >/dev/null 2>&1; then
    fail "agents-pool.sh up 2244 (free port) failed -- sequential warm-up must keep working (issue #2501 AC4)"
    return 1
  fi
  if ! grep -q "up -d --build agents" "$AGENTS_POOL_TEST_STATE/docker.log"; then
    fail "the sequential up wrote no 'compose up -d --build agents' -- the fixture was not brought up"
    return 1
  fi
  : > "$AGENTS_POOL_TEST_STATE/docker.log"
  if ! "$ROOT_DIR/scripts/agents-pool.sh" down 2244 >/dev/null 2>&1; then
    fail "agents-pool.sh down 2244 (free port) failed -- sequential teardown must keep working (issue #2501 AC4)"
    return 1
  fi
  if ! grep -q "down -v" "$AGENTS_POOL_TEST_STATE/docker.log"; then
    fail "the sequential down wrote no compose command"
    return 1
  fi
  # Legacy identity: 2222 is never a pool candidate and stays manageable.
  if ! "$ROOT_DIR/scripts/agents-pool.sh" up 2222 >/dev/null 2>&1 \
    || ! "$ROOT_DIR/scripts/agents-pool.sh" down 2222 >/dev/null 2>&1; then
    fail "agents-pool.sh up/down 2222 failed -- the documented legacy single-lane management must be untouched (issue #1842 / issue #2501 AC4)"
    return 1
  fi
  pass "sequential warm-up/teardown on free ports and the legacy 2222 identity keep working"
}

# --------------------------------------------------------------------------
# 15. AC3, the property the whole issue exists for: two CONCURRENT claims must
#     resolve to two DIFFERENT ports (or one waits) -- never both on one port.
#     Check 1 pins it for a single candidate port; this pins the multi-port
#     production default, so a regression of the #1842 anchor cannot hide by
#     pushing the second lane onto a different-by-accident port.
# --------------------------------------------------------------------------
two_concurrent_claimers_resolve_to_distinct_ports() {
  local tmp="$1"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/shared-locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  make_worktree "$tmp/wt-a15"
  make_worktree "$tmp/wt-b15"

  local a_pid b_pid
  a_pid="$(start_claimer "$tmp/wt-a15" "$tmp/d15a.ready" "$tmp/d15a.release" "2243 2244 2245" 30)"
  b_pid="$(start_claimer "$tmp/wt-b15" "$tmp/d15b.ready" "$tmp/d15b.release" "2243 2244 2245" 30)"
  wait_for_file "$tmp/d15a.ready" 20 && wait_for_file "$tmp/d15b.ready" 20
  touch "$tmp/d15a.release" "$tmp/d15b.release"
  kill_group "$a_pid"; kill_group "$b_pid"

  if [[ ! -e "$tmp/d15a.ready" || ! -e "$tmp/d15b.ready" ]]; then
    fail "two concurrent claimers over the default candidate list did not both claim (a.ready=$([[ -e $tmp/d15a.ready ]] && echo yes || echo NO) b.ready=$([[ -e $tmp/d15b.ready ]] && echo yes || echo NO)) -- the pool lost a lane, which is a wedge, not isolation"
    return 1
  fi
  local a_port b_port
  a_port="$(cat "$tmp/d15a.ready")"
  b_port="$(cat "$tmp/d15b.ready")"
  if [[ "$a_port" == "$b_port" ]]; then
    fail "two concurrent claimers BOTH won port $a_port over the default candidate list -- the per-port flock is not serialising concurrent claims (issue #2501 AC3 / a #1842 regression)"
    return 1
  fi
  pass "two concurrent claimers resolved to distinct ports ($a_port and $b_port)"
}

# --------------------------------------------------------------------------

main() {
  # Deliberately NOT `local`: the EXIT trap fires after main's frame is gone, so
  # a local would be unbound there (and `set -u` would turn cleanup into a
  # spurious non-zero exit that masks the real verdict).
  TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-agents-pool-isolation.XXXXXX")"
  trap 'rm -rf "$TMP_ROOT"' EXIT
  local tmp="$TMP_ROOT"

  install_docker_stub "$tmp/stubbin" "$tmp/docker-state"
  export PATH="$tmp/stubbin:$PATH"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"

  printf 'agents-pool isolation harness (issue #1842)\n'
  two_worktrees_cannot_both_claim_the_same_port "$tmp" || true
  port_lock_path_is_machine_wide_not_checkout_relative "$tmp" || true
  claim_locks_the_file_the_resolver_names "$tmp" || true
  default_pool_candidates_exclude_unlocked_fixture_ports || true
  ( a_disturbed_fixture_fails_with_an_unmistakable_signature "$tmp" ) || FAILURES=$((FAILURES + 1))
  ( a_disturbed_fixture_voids_the_run_in_both_directions "$tmp" ) || FAILURES=$((FAILURES + 1))
  ( rearm_makes_pre_instrumentation_churn_quiet_and_post_arming_churn_fatal "$tmp" ) || FAILURES=$((FAILURES + 1))
  wrapper_arms_the_fingerprint_after_its_last_fixture_action || true
  ( a_single_lane_run_is_completely_unaffected "$tmp" ) || FAILURES=$((FAILURES + 1))
  emulator_serial_claim_is_machine_anchored_too "$tmp" || true
  no_production_caller_captures_the_claim_in_a_subshell || true
  a_subshell_claim_is_refused_loudly "$tmp" || true
  a_successful_claim_returns_nothing_on_stdout "$tmp" || true
  a_subshell_cannot_release_the_owning_shells_claim "$tmp" || true
  compose_identity_is_spelled_out_in_exactly_one_place || true
  pool_cli_refuses_to_mutate_a_port_a_lane_is_holding "$tmp" || true
  pool_cli_still_manages_free_ports_and_the_legacy_identity "$tmp" || true
  two_concurrent_claimers_resolve_to_distinct_ports "$tmp" || true

  if (( FAILURES > 0 )); then
    printf '\nagents-pool isolation: %s FAILING check(s)\n' "$FAILURES" >&2
    exit 1
  fi
  printf '\nagents-pool isolation: all checks passed\n'
}

main "$@"
