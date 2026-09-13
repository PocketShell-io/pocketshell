#!/usr/bin/env bash
set -euo pipefail

# Root-guard regression harness (issue #2500).
#
# connected-test.sh resolves ROOT_DIR from the SCRIPT's own location, so
# invoking the root checkout's copy by absolute path from inside an agent
# worktree built and instrumented the ROOT tree (typically plain main) and
# reported a normal-looking green for changes that tree does not contain
# (#2500 — hit independently by two different agents on 2026-09-13). The
# wrapper must refuse that invocation loudly, announce the checkout it will
# test on every run, and keep same-checkout invocations working.
#
# This harness runs the REAL wrapper from real git worktrees of a scratch
# repo against a fake adb that reports no online emulator. Every case
# therefore terminates at the wrapper's own no-emulator refusal — no
# emulator, Docker daemon, Gradle build, or real checkout state is touched.
# The load-bearing discriminator is WHICH refusal the wrapper produces: the
# #2500 root guard must fire BEFORE any disk/output-lock/emulator machinery
# for a cross-checkout invocation, and must not fire for a same-checkout one.

unset POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_FD \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID \
      POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT \
      POCKETSHELL_AGENTS_PORT \
      ANDROID_SERIAL

# Issue #1989: pin the disk floor to 0 MiB like the sibling harnesses — this
# harness is about the checkout root, not the box's free space, and an
# ownership test that reddens on a full runner is a test that gets disabled.
export POCKETSHELL_DISK_MIN_FREE_MB=0
export POCKETSHELL_DISK_WARN_FREE_MB=0

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WRAPPER="$ROOT_DIR/scripts/connected-test.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

SANDBOX=""

make_fake_adb() {
  local bin="$1"
  mkdir -p "$bin"
  cat > "$bin/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  devices)
    # No devices attached: every case ends at the wrapper's own
    # no-online-emulator refusal, before any claim or mutation.
    printf 'List of devices attached\n'
    ;;
  *)
    printf 'FAIL: root-guard harness adb stub got an unexpected invocation: %s\n' "$*" >&2
    exit 90
    ;;
esac
ADB
  chmod +x "$bin/adb"
}

# A scratch checkout with one real git worktree of itself:
#   $SANDBOX/repo     a git repo holding a snapshot of the wrapper + libs
#   $SANDBOX/repo-wt  a `git worktree add` of that repo (the caller's seat)
make_fixture() {
  make_fake_adb "$SANDBOX/bin"
  mkdir -p "$SANDBOX/tmp" "$SANDBOX/locks" "$SANDBOX/output-locks" \
    "$SANDBOX/repo/scripts/lib"
  cp "$ROOT_DIR/scripts/connected-test.sh" \
    "$SANDBOX/repo/scripts/connected-test.sh"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$SANDBOX/repo/scripts/lib/"
  chmod +x "$SANDBOX/repo/scripts/connected-test.sh"
  git -C "$SANDBOX/repo" init -q
  git -C "$SANDBOX/repo" add scripts
  git -C "$SANDBOX/repo" \
    -c user.name=root-guard-harness -c user.email=harness@example.invalid \
    commit -qm "wrapper snapshot"
  git -C "$SANDBOX/repo" worktree add -q "$SANDBOX/repo-wt" HEAD
}

# Run the wrapper from an explicit caller cwd inside the sandboxed
# environment. Results land in RUN_RC / RUN_OUT / RUN_ERR.
RUN_RC=0
RUN_OUT=""
RUN_ERR=""
run_wrapper() {
  local caller_cwd="$1" wrapper="$2"
  shift 2
  RUN_OUT="$SANDBOX/run.out"
  RUN_ERR="$SANDBOX/run.err"
  set +e
  (
    cd "$caller_cwd" || exit 97
    exec env \
      TMPDIR="$SANDBOX/tmp" \
      PATH="$SANDBOX/bin:$PATH" \
      ADB="$SANDBOX/bin/adb" \
      ANDROID_SDK="$SANDBOX" \
      POCKETSHELL_AVD_LOCK_DIR="$SANDBOX/locks" \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$SANDBOX/output-locks" \
      POCKETSHELL_SCOPE_ALLOW_BARE=1 \
      bash "$wrapper" "$@"
  ) > "$RUN_OUT" 2> "$RUN_ERR"
  RUN_RC=$?
  set -e
}

assert_refused() {
  local err="$1" caller_root="$2" script_root="$3"
  (( RUN_RC != 0 )) || fail "cross-checkout invocation unexpectedly succeeded"
  grep -q 'refusing to run connected-test.sh from a different checkout (issue #2500)' "$err" \
    || fail "wrapper did not refuse a cross-checkout invocation; stderr: $(tail -n 15 "$err")"
  grep -qF "$caller_root" "$err" \
    || fail "refusal does not name the caller's checkout ($caller_root)"
  grep -qF "$script_root" "$err" \
    || fail "refusal does not name the checkout the script would have tested ($script_root)"
  grep -q 'POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT' "$err" \
    || fail "refusal does not document the deliberate override"
  if grep -q 'no online emulator' "$err"; then
    fail "refusal happened after emulator machinery -- the #2500 guard must fire first; stderr: $(tail -n 15 "$err")"
  fi
}

assert_passed_guard_and_reached_serial_machinery() {
  local err="$1" tested_root="$2" context="$3"
  (( RUN_RC != 0 )) \
    || fail "$context unexpectedly succeeded (sandbox has no emulator)"
  if grep -q 'refusing to run connected-test.sh from a different checkout' "$err"; then
    fail "$context was refused by the #2500 root guard; stderr: $(tail -n 15 "$err")"
  fi
  grep -qF "testing checkout $tested_root" "$err" \
    || fail "$context did not announce the checkout under test ($tested_root); stderr: $(tail -n 15 "$err")"
  grep -q 'no online emulator' "$err" \
    || fail "$context never reached the emulator-claim machinery; stderr: $(tail -n 15 "$err")"
}

# THE defect (#2500): the root checkout's absolute path invoked from inside an
# unrelated git worktree. Must refuse loudly, name BOTH trees and the override,
# and refuse before any disk/lock/emulator machinery runs.
foreign_worktree_invocation_refuses_before_any_machinery() {
  make_fixture
  run_wrapper "$SANDBOX/repo-wt" "$WRAPPER" --suffix i2500
  assert_refused "$RUN_ERR" "$SANDBOX/repo-wt" "$ROOT_DIR"
}

# Legitimate use the guard must keep working (issue #2500 AC2): the checkout's
# own copy invoked from that same checkout.
same_checkout_invocation_announces_and_runs() {
  make_fixture
  run_wrapper "$ROOT_DIR" "$WRAPPER" --suffix i2500
  assert_passed_guard_and_reached_serial_machinery "$RUN_ERR" "$ROOT_DIR" \
    "same-checkout invocation"
}

# The other legitimate shape: a worktree's own copy invoked from that
# worktree's root — the correct invocation the refusal message recommends.
worktree_copy_from_its_own_root_passes_the_guard() {
  make_fixture
  run_wrapper "$SANDBOX/repo-wt" \
    "$SANDBOX/repo-wt/scripts/connected-test.sh" --suffix i2500
  assert_passed_guard_and_reached_serial_machinery "$RUN_ERR" "$SANDBOX/repo-wt" \
    "worktree-copy invocation"
}

# The documented deliberate override proceeds past the guard, still announcing
# exactly which tree will be tested.
foreign_invocation_with_override_announces_and_proceeds() {
  make_fixture
  export POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT=1
  run_wrapper "$SANDBOX/repo-wt" "$WRAPPER" --suffix i2500
  unset POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT
  assert_passed_guard_and_reached_serial_machinery "$RUN_ERR" "$ROOT_DIR" \
    "overridden cross-checkout invocation"
}

# --cleanup-suffixes mutates no checkout and is the recovery path for a
# contended box (issue #776); gating recovery on the caller's cwd would be the
# classic self-lockout, so it stays exempt from the guard.
cleanup_from_foreign_cwd_stays_exempt() {
  make_fixture
  run_wrapper "$SANDBOX/repo-wt" "$WRAPPER" --cleanup-suffixes
  (( RUN_RC != 0 )) \
    || fail "cleanup without any online emulator unexpectedly succeeded"
  if grep -q 'refusing to run connected-test.sh from a different checkout' "$RUN_ERR"; then
    fail "--cleanup-suffixes must stay exempt from the #2500 root guard; stderr: $(tail -n 15 "$RUN_ERR")"
  fi
  grep -q 'no online emulator' "$RUN_ERR" \
    || fail "exempt cleanup never reached the emulator-claim machinery; stderr: $(tail -n 15 "$RUN_ERR")"
}

# Issue #2500 AC3: the correct invocation pattern is documented in the
# wrapper's own --help and cross-referenced from the worktree/testing docs.
help_and_docs_document_the_root_rule() {
  bash "$WRAPPER" --help > "$SANDBOX/help.out" 2> "$SANDBOX/help.err"
  cat "$SANDBOX/help.out" "$SANDBOX/help.err" > "$SANDBOX/help.all"
  grep -q 'POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT' "$SANDBOX/help.all" \
    || fail "--help does not document the #2500 override"
  grep -q 'different checkout' "$SANDBOX/help.all" \
    || fail "--help does not document the cross-checkout refusal"
  grep -q '#2500' "$ROOT_DIR/docs/worktrees.md" \
    || fail "docs/worktrees.md has no #2500 cross-reference for the invocation rule"
  grep -q 'connected-test.sh' "$ROOT_DIR/docs/worktrees.md" \
    || fail "docs/worktrees.md does not name connected-test.sh's invocation rule"
  grep -q '#2500' "$ROOT_DIR/docs/testing.md" \
    || fail "docs/testing.md has no #2500 cross-reference for the invocation rule"
}

CASES=(
  foreign_worktree_invocation_refuses_before_any_machinery
  same_checkout_invocation_announces_and_runs
  worktree_copy_from_its_own_root_passes_the_guard
  foreign_invocation_with_override_announces_and_proceeds
  cleanup_from_foreign_cwd_stays_exempt
  help_and_docs_document_the_root_rule
)
# Issue #2113: the full-suite size is hardcoded so DELETING an entry from the
# CASES array reddens this harness on its own — comparing the loop counter
# with `${#CASES[@]}` would only ever compare the loop with itself.
EXPECTED_FULL_CASES=6
FILTERED=0
if [[ $# -gt 0 ]]; then
  CASES=("$@")
  FILTERED=1
fi
(( FILTERED == 1 || ${#CASES[@]} == EXPECTED_FULL_CASES )) ||
  fail "expected $EXPECTED_FULL_CASES cases in the full suite, the array declares ${#CASES[@]}"

CASE_COUNT=0
for case_name in "${CASES[@]}"; do
  SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-root-guard.XXXXXX")"
  "$case_name"
  rm -rf "$SANDBOX"
  SANDBOX=""
  CASE_COUNT=$((CASE_COUNT + 1))
  printf '  ok: %s\n' "$case_name"
done

(( CASE_COUNT == ${#CASES[@]} && CASE_COUNT > 0 )) ||
  fail "expected ${#CASES[@]} cases to run, saw $CASE_COUNT"
printf 'PASS: connected-test root guard (issue #2500) (%s cases)\n' "$CASE_COUNT"
