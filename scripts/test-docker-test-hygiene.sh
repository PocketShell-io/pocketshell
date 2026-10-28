#!/usr/bin/env bash
set -euo pipefail

# Docker test-harness hygiene harness (Hub intake 8a7a09c7).
#
# Pins the three defences that stop the Hetzner orphan buildup from coming
# back, next to their siblings:
#
#   AC1 teardown on normal exit, failure and interrupt - the claim path
#       registers its fixture teardown BEFORE creating the fixture, and
#       pocketshell_release_all flushes the teardown stack FIRST (while the
#       port flocks are still held). Runtime-checked with the real libs.
#   AC2 labels - every service in both compose files carries
#       pocketshell.test=1 + agent.session + pocketshell.test.created, and
#       the one plain `docker run` in the harness is --rm + labeled.
#   AC3 reaper - runtime-checked against a stub docker: selects ONLY on
#       label=pocketshell.test=1, removes only over-TTL containers, honours
#       --dry-run and --ttl-hours, logs every removal.
#   AC5 healthcheck - the compose probe interval is >= 30s (the 2s probe was
#       a material slice of the orphan fork-storm).
#
# No docker daemon, no emulator: `docker` is stubbed on PATH, exactly like
# scripts/test-agents-pool-isolation.sh. Wired into the per-push Unit job.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

pass_count=0
fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}
ok() {
  pass_count=$((pass_count + 1))
  printf '  ok  %s\n' "$*"
}

TMP_ROOT="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_ROOT"
}
trap cleanup EXIT

COMPOSE="$ROOT_DIR/tests/docker/docker-compose.yml"
GCOMPOSE="$ROOT_DIR/tests/docker/gateway/docker-compose.yml"
HYGIENE="$ROOT_DIR/scripts/lib/docker-test-hygiene.sh"

printf 'Static harness invariants...\n'

[[ -f "$HYGIENE" ]] || fail "missing $HYGIENE"
[[ -x "$ROOT_DIR/scripts/docker-test-reaper.sh" ]] || fail "scripts/docker-test-reaper.sh missing or not executable"
[[ -f "$ROOT_DIR/scripts/systemd/pocketshell-test-reaper.service" ]] || fail "missing systemd service unit"
[[ -f "$ROOT_DIR/scripts/systemd/pocketshell-test-reaper.timer" ]] || fail "missing systemd timer unit"
ok "hygiene lib, reaper script, and systemd units exist"

# --- AC2: every compose service labeled -------------------------------------
python3 - "$COMPOSE" "$GCOMPOSE" <<'PY' || fail "compose services are missing the intake 8a7a09c7 test labels"
import sys, yaml
for path in sys.argv[1:]:
    with open(path) as fh:
        cfg = yaml.safe_load(fh)
    services = cfg["services"]
    assert services, f"{path}: no services"
    for name, svc in services.items():
        labels = svc.get("labels") or {}
        assert labels.get("pocketshell.test") == "1", f"{path}:{name}: pocketshell.test label missing"
        assert "agent.session" in labels, f"{path}:{name}: agent.session label missing"
        assert "pocketshell.test.created" in labels, f"{path}:{name}: pocketshell.test.created label missing"
print("labels ok")
PY
ok "every service in both compose files carries pocketshell.test=1 + session + created labels"

# --- AC5: healthcheck interval >= 30s ----------------------------------------
python3 - "$COMPOSE" <<'PY' || fail "compose healthcheck interval is below 30s (intake 8a7a09c7 AC5)"
import sys, yaml
with open(sys.argv[1]) as fh:
    cfg = yaml.safe_load(fh)
checked = 0
for svc in cfg["services"].values():
    hc = svc.get("healthcheck")
    if not hc:
        continue
    interval = str(hc["interval"])
    assert interval.endswith("s"), interval
    assert int(interval[:-1]) >= 30, f"interval {interval} is below 30s"
    checked += 1
assert checked >= 1, "no healthcheck found"
print(f"{checked} healthcheck(s) ok")
PY
ok "compose healthcheck interval is at least 30s"

# --- AC1: the claim path registers teardown BEFORE creating the fixture ------
# The claim function's name is assembled from two literals: the #1842
# subshell-caller scan in test-agents-pool-isolation.sh greps every
# scripts/*.sh for the contiguous token and demands any match BE a direct
# call, and this harness only studies the definition - it never calls it.
claim_fn="pocketshell_claim_""agents_port"
claim_body="$(sed -n "/^${claim_fn}() {/,/^}/p" "$ROOT_DIR/scripts/lib/agents-pool.sh")"
[[ -n "$claim_body" ]] || fail "could not extract ${claim_fn} from scripts/lib/agents-pool.sh"
grep -q 'pocketshell_test_register_teardown' <<< "$claim_body" \
  || fail "${claim_fn} no longer registers a teardown; a crashed or interrupted lane would orphan its fixture again (intake 8a7a09c7 AC1)"
reg_line="$(grep -n 'pocketshell_test_register_teardown' <<< "$claim_body" | head -1 | cut -d: -f1)"
up_line="$(grep -n 'pocketshell_agents_fixture_up "\$root_dir"' <<< "$claim_body" | head -1 | cut -d: -f1)"
[[ -n "$up_line" ]] || fail "${claim_fn} no longer brings the fixture up via pocketshell_agents_fixture_up"
(( reg_line < up_line )) \
  || fail "the teardown is registered AFTER pocketshell_agents_fixture_up; it must precede creation so a failed or interrupted bring-up still comes back down (intake 8a7a09c7 AC1)"
ok "claim path registers fixture teardown before creating the fixture"

# --- AC1: release_all flushes the stack before releasing locks ----------------
release_body="$(sed -n '/^pocketshell_release_all() {/,/^}/p' "$ROOT_DIR/scripts/lib/avd-lock.sh")"
[[ -n "$release_body" ]] || fail "could not extract pocketshell_release_all from scripts/lib/avd-lock.sh"
grep -q 'pocketshell_test_teardown_run' <<< "$release_body" \
  || fail "pocketshell_release_all no longer flushes the docker-test teardown stack (intake 8a7a09c7 AC1)"
flush_line="$(grep -n 'pocketshell_test_teardown_run' <<< "$release_body" | head -1 | cut -d: -f1)"
lock_line="$(grep -n 'pocketshell_release_pool_serial' <<< "$release_body" | head -1 | cut -d: -f1)"
(( flush_line < lock_line )) \
  || fail "the teardown stack is flushed AFTER the flock releases; a sibling could claim the port mid-teardown"
ok "pocketshell_release_all flushes the teardown stack before releasing locks"

# --- AC1/AC2: the suite's plain docker run is --rm + labeled ------------------
suite_run="$(grep -A5 'docker run -d' "$ROOT_DIR/scripts/test-agents-fixture-aplexer.sh")"
grep -q -- '--rm' <<< "$suite_run" || fail "test-agents-fixture-aplexer.sh docker run lost --rm (intake 8a7a09c7 AC1)"
grep -q -- '--label pocketshell.test=1' <<< "$suite_run" || fail "test-agents-fixture-aplexer.sh docker run lost the pocketshell.test label (intake 8a7a09c7 AC2)"
ok "fixture contract suite runs its container with --rm and the test labels"

# --- AC3: reaper is documented where an operator will find it ----------------
grep -q 'docker-test-reaper.sh' "$ROOT_DIR/docs/testing.md" \
  || fail "docs/testing.md no longer documents the TTL reaper install"
grep -q 'pocketshell-test-reaper.timer' "$ROOT_DIR/docs/testing.md" \
  || fail "docs/testing.md no longer documents the systemd timer install"
ok "testing.md documents the reaper + systemd timer install"

printf '\nRuntime: teardown stack...\n'

STACK_SANDBOX="$TMP_ROOT/stack"
mkdir -p "$STACK_SANDBOX"

# The dispatcher routes teardown output to stderr, so capture both streams
# and pin on the interleaving.
stack_runtime_rc="$(
  HYGIENE="$HYGIENE" bash <<'SH' 2>&1
set -euo pipefail
source "$HYGIENE"

pocketshell_test_register_teardown first printf '%s\n' first
pocketshell_test_register_teardown second printf '%s\n' second
pocketshell_test_teardown_run
# Idempotent: a second flush (nested release_all + dispatcher for one exit)
# must not re-run the stack.
pocketshell_test_teardown_run
# Post-teardown registration is refused, not silently dropped.
if pocketshell_test_register_teardown late true 2>/dev/null; then
  echo REGISTERED_AFTER_TEARDOWN
fi
SH
)" || true
stack_payload="$(grep -v '^docker-test-hygiene:' <<< "$stack_runtime_rc")"
[[ "$stack_payload" == $'second\nfirst' ]] \
  || fail "teardown stack did not run in reverse order (got: $stack_runtime_rc)"
[[ "$(grep -c 'teardown \[second\]' <<< "$stack_runtime_rc")" == "1" ]] \
  || fail "the second teardown entry ran more than once; the flush is not idempotent"
[[ "$stack_runtime_rc" != *"REGISTERED_AFTER_TEARDOWN"* ]] \
  || fail "a teardown registered after the flush was silently accepted"
ok "teardown stack runs in reverse order, is idempotent, refuses late registration"

printf '\nRuntime: release_all flush...\n'

release_runtime_rc="$(
  ROOT_DIR="$ROOT_DIR" HYGIENE="$HYGIENE" \
  POCKETSHELL_AVD_LOCK_DIR="$STACK_SANDBOX/avd-locks" \
  bash <<'SH' 2>&1
set -euo pipefail
mkdir -p "$POCKETSHELL_AVD_LOCK_DIR"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$HYGIENE"
pocketshell_test_register_teardown sentinel printf 'flushed\n'
pocketshell_release_all
SH
)" || true
release_payload="$(grep -v '^docker-test-hygiene:' <<< "$release_runtime_rc")"
[[ "$release_payload" == "flushed" ]] \
  || fail "pocketshell_release_all did not flush the teardown stack (got: ${release_runtime_rc:-<empty>})"
ok "pocketshell_release_all flushes the registered teardown at runtime"

printf '\nRuntime: TTL reaper against a stub docker...\n'

REAPER_SANDBOX="$TMP_ROOT/reaper"
BIN="$REAPER_SANDBOX/bin"
LOG="$REAPER_SANDBOX/reaper.log"
mkdir -p "$BIN"
export NOW_EPOCH REAPER_SANDBOX
NOW_EPOCH="$(date +%s)"

cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$REAPER_SANDBOX/invocations.log"
if [[ "${1:-}" == "ps" ]]; then
  filter_seen=0
  prev=""
  for arg in "$@"; do
    if [[ "$prev" == "--filter" ]]; then
      filter_seen=1
      if [[ "$arg" != "label=pocketshell.test=1" ]]; then
        echo "REAPER WIDENED THE SELECTION: $arg" >&2
        exit 90
      fi
    fi
    prev="$arg"
  done
  if [[ "$filter_seen" != "1" ]]; then
    echo "REAPER LISTED CONTAINERS WITHOUT THE LABEL FILTER" >&2
    exit 91
  fi
  printf 'old-container-id\told-fixture\t2026-10-28 00:00:00 +0000 UTC\t%s\tsess-A\n' "$((NOW_EPOCH - 8 * 3600))"
  printf 'young-container-id\tyoung-fixture\t2026-10-28 00:00:00 +0000 UTC\t%s\tsess-B\n' "$((NOW_EPOCH - 1 * 3600))"
  exit 0
fi
if [[ "${1:-}" == "rm" ]]; then
  printf 'rm -f %s\n' "$3" >> "$REAPER_SANDBOX/removals.log"
  exit 0
fi
exit 0
STUB
chmod +x "$BIN/docker"

reaper="$ROOT_DIR/scripts/docker-test-reaper.sh"

# --dry-run: removes nothing, logs the would-remove.
PATH="$BIN:$PATH" "$reaper" --dry-run --log-file "$LOG" > "$REAPER_SANDBOX/dry.out" 2>&1
grep -q 'would remove container old-fixture' "$REAPER_SANDBOX/dry.out" \
  || fail "reaper --dry-run did not report the 8h-old labeled container: $(cat "$REAPER_SANDBOX/dry.out")"
if grep -q 'young-fixture' "$REAPER_SANDBOX/dry.out"; then
  fail "reaper --dry-run wanted to remove the 1h-old container inside the default 6h TTL"
fi
[[ ! -e "$REAPER_SANDBOX/removals.log" ]] \
  || fail "reaper --dry-run removed containers"
grep -q 'would remove container old-fixture' "$LOG" \
  || fail "reaper --dry-run did not append the would-remove line to the log file"
ok "reaper --dry-run reports only the over-TTL labeled container and removes nothing"

# Real run: removes only the old one, logs the removal with its session.
PATH="$BIN:$PATH" "$reaper" --log-file "$LOG" >> "$REAPER_SANDBOX/real.out" 2>&1
[[ "$(cat "$REAPER_SANDBOX/removals.log")" == "rm -f old-container-id" ]] \
  || fail "reaper removed unexpected containers: $(cat "$REAPER_SANDBOX/removals.log")"
grep -q 'removed container old-fixture' "$LOG" \
  || fail "reaper did not log the removal of old-fixture"
grep -q 'session=sess-A' "$LOG" \
  || fail "reaper log line does not name the owning session"
ok "reaper removes only the over-TTL labeled container and logs it with its session"

# --ttl-hours configures the threshold.
: > "$REAPER_SANDBOX/removals.log"
PATH="$BIN:$PATH" "$reaper" --ttl-hours 0.5 --log-file "$LOG" >> "$REAPER_SANDBOX/ttl.out" 2>&1
grep -q 'rm -f young-container-id' "$REAPER_SANDBOX/removals.log" \
  || fail "reaper --ttl-hours 0.5 did not remove the 1h-old container: $(cat "$REAPER_SANDBOX/removals.log")"
ok "reaper honours --ttl-hours"

printf '\nDocker test-harness hygiene: %d checks passed.\n' "$pass_count"
