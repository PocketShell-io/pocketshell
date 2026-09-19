#!/usr/bin/env bash
set -euo pipefail

# check-nightly-fault-run.sh — release-gate guard (issue #851, epic #848).
#
# The #848 test-reliability audit found the safety suites run in NO blocking
# gate: the toxiproxy network-fault proofs + the bootstrap setup-scenario matrix
# live ONLY in the scheduled journey workflow — since the rewrite that is the
# `app2 journey suite` job of .github/workflows/app2.yml. D37's cadence moved
# there with the deleted nightly-extensive.yml, every phase of which targeted
# the removed `:app` module.
#
# WHAT THE VERDICT IS NOW. The old nightly split its run into phases and
# synthesised a `fault_verdict=` field from the network-fault + bootstrap phase
# statuses. app2's lane (issue #2474) runs its whole instrumented set — the
# toxiproxy network-fault journeys among them — in ONE unfiltered
# instrumentation process, so there are no phases to combine: the JOB'S OWN
# CONCLUSION is the verdict, and this guard already reads exactly that
# (`jobConclusion`). The producer-side synthesiser was deleted with the suite;
# the consumer below is unchanged in substance, only repointed.
#
# ISSUE #1201 — WHAT THIS GUARD NOW READS:
# The extensive shard job mixes the ACTUAL fault suite (network-fault +
# bootstrap) with the flaky full journey/E2E suite AND the #822 Slice C/D
# expected-fail lane (TDD specs for unbuilt features, designed RED). Reading the
# whole extensive-job conclusion meant the job was essentially never `success`,
# so every recent release had to WAIVE this gate — a permanently-waived gate
# protects nothing. The suite now emits a machine-readable fault verdict from
# the network-fault + bootstrap phases ONLY, and the workflow exposes it as a
# dedicated, NON-continue-on-error `app2 journey suite` job. This
# guard reads THAT job's conclusion, so it GREENs when the fault phases passed
# even though an unrelated journey / expected-fail test is red, and REDs only
# when a fault phase itself failed (or no verdict was produced — genuine
# nightly-infra breakage).
#
# This guard makes the release gate FAIL when the latest nightly fault-verdict
# run is red / cancelled / stale / missing. It is wired as an early required
# step in scripts/release-emulator-validation.sh.
#
# ISSUE #2379 / DECISION D37 — THIS GUARD HAS NO OFF SWITCH.
# There is deliberately NO environment variable, workflow input, or "deliberately
# waived release" branch that skips this check. Waiving it was routine
# (v0.4.31–v0.4.38, v0.4.45 all shipped with the fault verdict un-enforced) and
# #1671 traced the #1610 reconnect storm reaching the maintainer to exactly that.
# When this guard BLOCKS, the two sanctioned ways forward are:
#   (a) fix the failing test/journey, or
#   (b) quarantine the offending test/journey class through the D36(4) flake
#       mechanism (auto-filed issue, non-blocking lane, 2-week expiry), so the
#       verdict covers a genuinely smaller but still-real suite.
# Never a skip of the whole verdict. Reintroducing a skip flag anywhere under
# scripts/ or .github/workflows/ fails scripts/check-release-gate-bypass-absent.sh
# on the next push.
#
# ISSUE #2379 ROUND 2 — AND NOTHING IN THE ENVIRONMENT CAN CHANGE THE VERDICT.
# Deleting the named escape hatch was not enough. This guard used to take
# NIGHTLY_FAULT_RUN_FIXTURE / _WORKFLOW / _JOB_NEEDLE / _RELEASE_HEAD from the
# ENVIRONMENT, behind a comment claiming they only selected WHICH run is read.
# That was false: the fixture does not select a run, it SUPPLIES THE ANSWER.
#     NIGHTLY_FAULT_RUN_FIXTURE=green.json scripts/check-nightly-fault-run.sh
#     -> "PASS: nightly fault-injection safety verdict is green ...", exit 0
# and scripts/release-emulator-validation.sh inherited the caller's environment
# straight into this script, so one exported variable plus a three-line JSON
# file wrote a fabricated green into the release summary. That is WORSE than the
# deleted hatch, which at least printed "SKIPPED: ... escape hatch" — a
# fabricated fixture makes the release artifact lie about having checked.
#
# So: every one of those knobs is now a COMMAND-LINE FLAG and no environment
# variable is read at all. Environment inheritance cannot set a flag, and the
# release path passes only --release-head. `gh` is additionally pinned to this
# checkout's origin repository with --repo and invoked with GH_REPO/GH_HOST
# scrubbed, so the environment cannot point the query at some other green
# repository either.
#
# Honest scope of that claim: this guard still trusts git, the checkout, and
# `gh`'s credentials — an actor who can rewrite the working tree does not need a
# bypass. What it no longer trusts is ANY variable in its environment.
# scripts/check-release-gate-bypass-absent.sh (C4) asserts exactly this, per
# push, by driving the guard with a hostile environment.
#
# ISSUE #2706 — RUN-LEVEL CANCELLATIONS LEAVE A VERDICT GAP, NOT A RED.
# A run-level cancellation (a manual or API cancel of the whole run) kills
# every job in it, and NO workflow-level configuration can shield a job from
# one: job-level `if: !cancelled()` guards only job-dependency cancels, and
# app2.yml's concurrency groups are never-cancelled for every event anyway
# (own schedule group + queue: max, #2736/D40/#2600). What killed the journey
# job of run
# 34402741674 (workflow_dispatch on release/v0.5.4 during the v0.5.4 window,
# ~8.5 min after an unrelated portfwd Docker-lane failure in the same run) was
# exactly this out-of-band shape. The producer cannot prevent it, so the
# CONSUMER refuses to let a verdict-less run erase a real one: a run whose
# journey job produced NO verdict proves nothing either way — job absent,
# `skipped` (a PR run, or a push whose lane selection skipped app2), or
# `cancelled` (a run-level kill) — and every one of them is a VERDICT GAP the
# run-resolution walk now steps past, to the newest run that produced a real
# terminal verdict. D37 is not weakened: only `success` on a run covering the
# release HEAD ever passes; a red (or timed_out — the producer RAN and hit its
# budget, a real signal) verdict still stops the walk and blocks; if nothing in
# the window carries a verdict, the guard blocks and says so. Every stepped-
# past run prints a NOTE line, so the release state shows the gap
# explicitly instead of silently reading an older run.
#
# ISSUE #2754 — STALE/MISSING VERDICTS SELF-HEAL INSTEAD OF PAGING A HUMAN.
# Four Release Emulator Validation runs (35051420588, 35092088955, 35096339243,
# 35140845314 — 2026-09-16) went red on the same structural race: the nightly
# chain ran REV on a release HEAD whose journey verdict belonged to the PARENT
# commit (a push landed between chain stages), so this guard blocked STALE and
# the sanctioned fix was the same manual loop every time: dispatch app2.yml on
# the release commit, wait ~25 min for the 'app2 journey suite' job, re-run REV.
# This guard now runs that loop itself, BEFORE failing:
#
#   1. `self_heal_required` decides — ONLY a stale verdict (real verdict whose
#      head does not contain the release HEAD) or a missing one (no run in the
#      window carries a terminal journey verdict) triggers self-heal.
#   2. `self_heal_dispatch` POSTs the workflow dispatch FOR the RELEASE SHA —
#      `gh api repos/$REPO/actions/workflows/$WORKFLOW/dispatches -f ref=<branch>`,
#      pinned to this checkout's repository like every other gh call here. The
#      ref is a BRANCH NAME resolved from the release SHA (#2822: the API
#      rejects a raw commit SHA with HTTP 422 "No ref found"), and only a
#      branch whose HEAD *is* that commit qualifies, so the dispatched run's
#      headSha is the release commit the poll loop matches on. An 'app2' run
#      already in flight on the release commit is ADOPTED instead of
#      dispatching a duplicate suite attempt; a release commit that is no
#      branch's head BLOCKS with an actionable manual-dispatch message instead
#      of a doomed API call.
#   3. The new run's journey/fault-verdict conclusion is polled with a bounded
#      budget (--self-heal-timeout, default 3600s; the suite ran 24m54s on
#      2026-09-16) and the SAME pure decision function re-evaluates the fresh
#      verdict.
#
# D37 is not weakened and there is no override path: the self-heal closes ONLY
# the stale/missing gap. A genuinely RED verdict (failure/timed_out) on a
# covering run never triggers a dispatch — it blocks exactly as before, with
# the same message (anti-flake-masking, #2754 AC4: at most ONE dispatch per
# guard run, never a second suite attempt on a red). A dispatch failure, a
# poll-budget timeout, and a completed-but-verdict-less self-heal run all
# BLOCK fail-closed. Offline tests drive the whole story through
# --self-heal-fixture: every gh interaction is canned and the dispatch is
# RECORDED to "<fixture>.dispatchlog", never sent — a test never dispatches a
# live workflow (#2754 hard constraint), and a --fixture dry run skips the
# self-heal entirely, blocking STALE exactly as it did before.
#
# Two layers, kept separate so the decision logic is unit-testable WITHOUT any
# network/`gh` dependency:
#
#   1. evaluate_nightly_fault_run — PURE decision function. Given the run's
#      status, the fault-verdict-JOB conclusion, the run headSha, the release
#      HEAD, and whether headSha is an ancestor-or-equal of the release HEAD, it
#      prints a PASS/BLOCK verdict + reason and returns 0 (pass) / 1 (block). No
#      git, no gh, no I/O. The release HEAD ancestry is computed by the caller.
#
#   2. The `gh`/git fetch layer (main path) — queries the latest scheduled run that
#      ACTUALLY RAN the fault-verdict job (skips guard-skipped runs), extracts
#      that job's conclusion, resolves ancestry against the release HEAD, then
#      calls the pure function.
#
# Self-test: `scripts/check-nightly-fault-run.sh --self-test` exercises the pure
# decision function across the red / cancelled / stale / missing / pass matrix
# with NO network, PLUS a fixture-driven end-to-end dry run proving the guard
# GREENs on a fault-verdict-green run and REDs on a fault-verdict-red run — this
# is the dry-run rejection proof the issue asks for — and (#2706) array-fixture
# dry runs proving the cancellation-gap walk-back reaches an older covering
# verdict, still blocks on red/stale/all-cancelled, and prints the gap NOTE.
#
# Usage:
#   check-nightly-fault-run.sh [--release-head <sha>]     # the release path
#   check-nightly-fault-run.sh --self-test                # offline proof matrix
#
# TEST-ONLY flags. They exist so the decision path can be proven offline (no
# network, no `gh` creds). They are flags, not environment variables, precisely
# so that no exported variable can reach them, and the release path passes none
# of them — check-release-gate-bypass-absent.sh C4-static asserts that:
#   --fixture <path>      read this JSON (one element of `gh run list --json ...`
#                         PLUS a `jobConclusion` field carrying the fault-verdict
#                         job's conclusion) instead of calling `gh`. Every line of
#                         output is then marked "[FIXTURE DRY RUN]", so a fixture
#                         verdict can never be pasted into, or mistaken for, a
#                         release summary.
#   --workflow <file>     workflow file (default: app2.yml).
#   --job-needle <needle> substring identifying the journey job
#                         (default: "app2 journey suite").
#   --self-heal-fixture <path>  (issue #2754 offline harness) drive EVERY gh
#                         interaction of the resolve walk AND the self-heal
#                         dispatch/poll loop from this canned JSON; the
#                         dispatch is RECORDED to "<path>.dispatchlog", never
#                         sent. Output is marked "[FIXTURE DRY RUN]" like
#                         --fixture, and a real dispatch is impossible.
#   --self-heal-timeout <s>  self-heal poll budget in seconds (default 3600).
#   --self-heal-poll <s>     self-heal poll interval in seconds (default 60).
#
# --release-head <sha> overrides the release HEAD (default: `git rev-parse HEAD`).

WORKFLOW="app2.yml"
JOB_NEEDLE="app2 journey suite"
FIXTURE=""
RELEASE_HEAD_OVERRIDE=""
FIXTURE_PREFIX=""

# Issue #2754 self-heal knobs. Flags, never environment variables (same rule as
# every knob above): the release path passes none of them — the budget lives in
# the defaults — and check-release-gate-bypass-absent.sh (C4-static) fails if
# the release script ever grows one of these flags.
SELF_HEAL_FIXTURE=""
SELF_HEAL_TIMEOUT_SECONDS="3600"
SELF_HEAL_POLL_SECONDS="60"

usage() {
  sed -n '/^# Usage:/,/^# --release-head/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# ---------------------------------------------------------------------------
# Layer 1: PURE decision function (no git, no gh, no I/O).
#
# Args:
#   $1 run_status        the run's `status` (e.g. completed / in_progress)
#   $2 job_conclusion    the FAULT-VERDICT-job conclusion (success/failure
#                        /cancelled/timed_out/skipped/"" when the job did not
#                        exist). This is the network-fault + bootstrap verdict
#                        ONLY — never the flaky journey suite / #822 lane.
#   $3 run_head_sha      the run's headSha ("" if no run found)
#   $4 release_head_sha  the commit being released
#   $5 head_is_ancestor  "yes" if the run COVERS the release HEAD — i.e.
#                        release_head_sha is an ancestor-or-equal of
#                        run_head_sha, so the release commit is contained in the
#                        line the nightly actually tested. "no" otherwise (the
#                        release HEAD advanced PAST the nightly's sha → STALE).
#
# Prints "PASS: <reason>" / "BLOCK: <reason>"; returns 0 (pass) or 1 (block).
# ---------------------------------------------------------------------------
evaluate_nightly_fault_run() {
  local run_status="$1"
  local job_conclusion="$2"
  local run_head_sha="$3"
  local release_head_sha="$4"
  local head_is_ancestor="$5"

  # No run at all → the fault suite has never reported for this line. Block.
  # Also the landing spot for "every recent run was guard-skipped or its
  # journey job was cancelled" (the walk-back fall-through, #2706).
  if [[ -z "$run_head_sha" ]]; then
    echo "BLOCK: no scheduled journey run with a fault verdict found for workflow '$WORKFLOW' — the safety suite has produced no signal to release on (never ran, guard-skipped, or every recent run's journey job was cancelled by a run-level cancellation — a verdict gap). Trigger the 'app2' workflow (workflow_dispatch) on the release commit and let the journey job finish."
    return 1
  fi

  # Run did not complete (in_progress / queued / waiting / requested) → no
  # final verdict yet. Block rather than release on an unfinished run.
  if [[ "$run_status" != "completed" ]]; then
    echo "BLOCK: latest nightly fault run is status='$run_status' (not completed) — no final fault verdict yet. Wait for it to finish (or re-run it) before releasing."
    return 1
  fi

  # Stale: the run tested an older line that does NOT contain the release HEAD.
  # Releasing on it would ship commits the fault suite never exercised.
  if [[ "$head_is_ancestor" != "yes" ]]; then
    echo "BLOCK: latest journey run tested headSha=$run_head_sha which does NOT contain the release HEAD ($release_head_sha) — the run is STALE for this release. Re-run the 'app2' workflow on the release commit."
    return 1
  fi

  # The load-bearing signal is the dedicated `app2 journey suite`
  # JOB conclusion (issue #1201) — the network-fault + bootstrap verdict, with
  # the flaky journey suite and the #822 expected-fail lane excluded. Anything
  # other than `success` is a block.
  case "$job_conclusion" in
    success)
      echo "PASS: journey fault-injection safety verdict is green (app2 journey job conclusion=success) and covers the release HEAD ($release_head_sha). The network-fault + bootstrap safety journeys passed on this line."
      return 0
      ;;
    cancelled)
      # The run-resolution walk steps PAST cancelled journey jobs (#2706), so a
      # live release run only reaches this branch through the decision function
      # directly (self-test matrix) or if the walk/consumer contract drifts.
      # Kept fail-closed on purpose: a cancelled job is a GAP, never a green.
      echo "BLOCK: journey job was CANCELLED (conclusion=cancelled) — a run-level cancellation killed the fault-verdict producer mid-run. This is a verdict GAP, not a red: the suite neither passed nor failed, so it proves nothing. Re-run the 'app2' workflow (workflow_dispatch) on the release commit and let the journey job finish."
      return 1
      ;;
    "")
      echo "BLOCK: latest journey run did not run the journey job (no job matching '$JOB_NEEDLE' — it was guard-skipped or never started). There is no fault signal. Force-run the 'app2' workflow on the release commit."
      return 1
      ;;
    *)
      echo "BLOCK: latest nightly fault-injection safety verdict is RED (fault-verdict job conclusion='$job_conclusion'). The safety suite (toxiproxy network-fault + bootstrap matrix) failed on the release line — fix the failure or re-run before releasing."
      return 1
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Self-test: drives the pure function across the full matrix with NO network.
# This is the dry-run rejection proof the issue asks for.
# ---------------------------------------------------------------------------
self_test() {
  local failures=0
  local out rc

  assert_verdict() {
    local label="$1" expect_rc="$2"
    shift 2
    set +e
    out="$(evaluate_nightly_fault_run "$@")"
    rc=$?
    set -e
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected rc=%s got rc=%s :: %s\n' "$label" "$expect_rc" "$rc" "$out"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] rc=%s :: %s\n' "$label" "$rc" "$out"
    fi
  }

  local REL="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  local OLD="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  # PASS: completed, fault-verdict job success, covers the release HEAD.
  assert_verdict "green-and-current" 0 completed success "$REL" "$REL" yes
  # BLOCK: fault-verdict job failed (a fault phase actually failed).
  assert_verdict "red-job"           1 completed failure "$REL" "$REL" yes
  # BLOCK: run cancelled.
  assert_verdict "cancelled"         1 completed cancelled "$REL" "$REL" yes
  # BLOCK: timed_out.
  assert_verdict "timed-out"         1 completed timed_out "$REL" "$REL" yes
  # BLOCK: fault-verdict job absent (guard-skipped / never ran).
  assert_verdict "job-absent"        1 completed "" "$OLD" "$REL" yes
  # BLOCK: no run found at all.
  assert_verdict "no-run"            1 completed success "" "$REL" no
  # BLOCK: run not completed yet.
  assert_verdict "in-progress"       1 in_progress "" "$REL" "$REL" yes
  # BLOCK: green BUT stale (tested an older line not containing release HEAD).
  assert_verdict "green-but-stale"   1 completed success "$OLD" "$REL" no

  echo
  # -------------------------------------------------------------------------
  # Issue #1201 — fixture-driven END-TO-END dry run through the real fetch +
  # decide path (Layer 2 → Layer 1), proving BOTH load-bearing directions:
  #   * fault-verdict job GREEN (fault phases passed even though the extensive
  #     shard is red from an unrelated journey / #822 test) -> guard exits 0;
  #   * fault-verdict job RED (a fault phase itself failed)  -> guard exits 1.
  # The fixture's `jobConclusion` IS the dedicated fault-verdict job conclusion,
  # so this exercises exactly what the guard reads on a real run. headSha ==
  # release head so the ancestry short-circuit avoids any git dependency.
  # -------------------------------------------------------------------------
  # Each case also pins the BLOCK/PASS REASON, not just the exit code: the
  # round-1 "no verdict" case exited 1 through the STALE branch (the tab-split
  # bug below) and no assertion noticed, so the branch it claimed to cover was
  # never executed.
  local self_path="${BASH_SOURCE[0]}"
  fixture_dry_run() {
    local label="$1" expect_rc="$2" job_conclusion="$3" reason_needle="$4"
    local sha="cccccccccccccccccccccccccccccccccccccccc"
    local tmp; tmp="$(mktemp)"
    printf '{"status":"completed","jobConclusion":"%s","headSha":"%s","databaseId":424242}\n' \
      "$job_conclusion" "$sha" > "$tmp"
    # Flags, not environment variables — see the D37 round-2 note in the header.
    set +e
    out="$(bash "$self_path" --fixture "$tmp" --release-head "$sha" 2>&1)"
    rc=$?
    set -e
    rm -f "$tmp"
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected guard rc=%s got rc=%s\n%s\n' "$label" "$expect_rc" "$rc" "$out"
      failures=$((failures + 1))
    elif ! printf '%s' "$out" | grep -qF -- "$reason_needle"; then
      printf 'FAIL [%s]: rc=%s was right but the reason was not "%s"\n%s\n' \
        "$label" "$rc" "$reason_needle" "$out"
      failures=$((failures + 1))
    elif ! printf '%s' "$out" | grep -qF -- "[FIXTURE DRY RUN]"; then
      printf 'FAIL [%s]: fixture output was not marked as a dry run\n%s\n' "$label" "$out"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] guard rc=%s :: %s\n' "$label" "$rc" "$(printf '%s' "$out" | tail -1)"
    fi
  }

  echo "--- fixture-driven end-to-end guard dry run (issue #1201) ---"
  # Fault phases PASSED (verdict job success) while the extensive shard is red
  # from unrelated journey / #822 flakes -> the guard GREENs (exit 0). This is
  # the exact case that used to force a release-wide waiver of the gate.
  fixture_dry_run "fault-verdict-green-shard-red" 0 success   "safety verdict is green"
  # A fault phase itself FAILED (verdict job failure) -> the guard BLOCKS (exit 1).
  fixture_dry_run "fault-verdict-red"             1 failure   "safety verdict is RED"
  # The fault-verdict job NEVER RAN (empty conclusion). Issue #2379 round 2: this
  # must reach the "no fault signal" branch. Before the tab-split fix the empty
  # middle field shifted headSha into job_conclusion and databaseId into
  # run_head_sha, so this blocked as STALE with headSha=424242.
  fixture_dry_run "journey-job-missing"               1 ""        "did not run the journey job"
  # A CANCELLED journey job through the single-run fixture path blocks as a GAP
  # (run-level cancellation — #2706), never as a green.
  fixture_dry_run "journey-cancelled-single-run"      1 cancelled "verdict GAP"

  echo
  # -------------------------------------------------------------------------
  # Issue #2706 — the cancellation-gap WALK, driven through array fixtures
  # (runs listed newest-first, exactly what the live `gh run list` walk sees).
  # A cancelled journey job is a verdict GAP the walk steps past to the newest
  # run with a real terminal verdict; the gap NOTE must be visible in the
  # output, because the release state has to show the gap explicitly.
  # -------------------------------------------------------------------------
  fixture_walk_dry_run() {
    local label="$1" expect_rc="$2" runs_json="$3" reason_needle="$4" want_gap_note="$5"
    local tmp; tmp="$(mktemp)"
    printf '%s\n' "$runs_json" > "$tmp"
    set +e
    out="$(bash "$self_path" --fixture "$tmp" --release-head "cccccccccccccccccccccccccccccccccccccccc" 2>&1)"
    rc=$?
    set -e
    rm -f "$tmp"
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected guard rc=%s got rc=%s\n%s\n' "$label" "$expect_rc" "$rc" "$out"
      failures=$((failures + 1))
    elif ! printf '%s' "$out" | grep -qF -- "$reason_needle"; then
      printf 'FAIL [%s]: rc=%s was right but the reason was not "%s"\n%s\n' \
        "$label" "$rc" "$reason_needle" "$out"
      failures=$((failures + 1))
    elif [[ "$want_gap_note" == "yes" ]] && ! printf '%s' "$out" | grep -qF -- "NOTE (#2706)"; then
      printf 'FAIL [%s]: the cancellation-gap NOTE (#2706) was missing from the release-state output\n%s\n' "$label" "$out"
      failures=$((failures + 1))
    elif ! printf '%s' "$out" | grep -qF -- "[FIXTURE DRY RUN]"; then
      printf 'FAIL [%s]: fixture output was not marked as a dry run\n%s\n' "$label" "$out"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] guard rc=%s :: %s\n' "$label" "$rc" "$(printf '%s' "$out" | tail -1)"
    fi
  }

  echo "--- cancellation-gap walk-back dry run (issue #2706) ---"
  # The exact shape that killed the v0.5.4 window (run 34402741674: journey job
  # cancelled by a run-level cancellation ~8.5 min after an unrelated portfwd
  # Docker-lane failure): the cancel must NOT erase the older covering green.
  fixture_walk_dry_run "gap-walk-reaches-older-green" 0 \
    '[{"status":"completed","jobConclusion":"cancelled","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9001},{"status":"completed","jobConclusion":"success","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9002}]' \
    "safety verdict is green" yes
  # A cancelled newest run must not mask a RED verdict behind it either.
  fixture_walk_dry_run "gap-walk-stops-at-red" 1 \
    '[{"status":"completed","jobConclusion":"cancelled","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9001},{"status":"completed","jobConclusion":"failure","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9002}]' \
    "safety verdict is RED" yes
  # Walking back to a green that does NOT cover the release HEAD still blocks STALE.
  fixture_walk_dry_run "gap-walk-to-stale-green" 1 \
    '[{"status":"completed","jobConclusion":"cancelled","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9001},{"status":"completed","jobConclusion":"success","headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","databaseId":9002}]' \
    "STALE" yes
  # Everything in the window cancelled/skipped → block as no-signal, gap explicit.
  fixture_walk_dry_run "gap-all-cancelled-blocks" 1 \
    '[{"status":"completed","jobConclusion":"cancelled","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9001},{"status":"completed","jobConclusion":"","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9002}]' \
    "no scheduled journey run with a fault verdict" yes
  # A PR/lane-skip run (journey job conclusion=skipped) is the same gap: the
  # walk steps past it instead of halting on it and mis-reporting the skip as RED.
  fixture_walk_dry_run "gap-walk-past-pr-skip" 0 \
    '[{"status":"completed","jobConclusion":"skipped","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9003},{"status":"completed","jobConclusion":"success","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9004}]' \
    "safety verdict is green" yes
  # Skip-only window → still no signal, still blocks.
  fixture_walk_dry_run "gap-skip-only-blocks" 1 \
    '[{"status":"completed","jobConclusion":"skipped","headSha":"cccccccccccccccccccccccccccccccccccccccc","databaseId":9003}]' \
    "no scheduled journey run with a fault verdict" yes

  echo
  # -------------------------------------------------------------------------
  # Issue #2754 — the SELF-HEAL harness. --self-heal-fixture drives EVERY gh
  # interaction (the resolve walk, the dispatch, the poll loop, the job view)
  # from one canned JSON; the dispatch is RECORDED, never sent, so the full
  # stale/missing -> dispatch -> poll -> fresh-verdict story runs offline with
  # zero network. Each case pins the verdict, the REASON, the "[FIXTURE DRY
  # RUN]" marking, and the dispatch RECORD COUNT — the bounded-cost property
  # (#2754 AC4): one dispatch on stale/missing, ZERO on red/green, never a
  # second suite attempt.
  # -------------------------------------------------------------------------
  # $7 (dispatch_needle, #2822) pins the CONTENT of the recorded dispatch —
  # "ref=<branch> sha=<release sha>". Counting records alone could not tell a
  # branch ref from the raw SHA the API answers with HTTP 422, which is exactly
  # how the broken shape shipped green.
  self_heal_case() {
    local label="$1" expect_rc="$2" reason_needle="$3" want_dispatches="$4" \
      fixture_json="$5" extra_flags="${6:-}" dispatch_needle="${7:-}" \
      absent_needle="${8:-}"
    local tmp; tmp="$(mktemp)"
    printf '%s' "$fixture_json" > "$tmp"
    local log="${tmp}.dispatchlog"
    set +e
    # A 1s poll keeps the canned cases fast; extra_flags (later args win) can
    # still override either self-heal knob for a specific case.
    # shellcheck disable=SC2086  # extra_flags word-splits into separate args
    out="$(bash "$self_path" --self-heal-fixture "$tmp" --release-head "$REL" \
      --self-heal-poll 1 $extra_flags 2>&1)"
    rc=$?
    set -e
    local got_dispatches=0 log_body=""
    # grep -c exits 1 on a zero count — `|| true` keeps the "0" it printed
    # without tripping the self-test's errexit.
    [[ -f "$log" ]] && got_dispatches="$(grep -c '^DISPATCH ' "$log" || true)"
    [[ -f "$log" ]] && log_body="$(cat "$log")"
    rm -f "$tmp" "$log"
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected rc=%s got rc=%s\n%s\n' "$label" "$expect_rc" "$rc" "$out"
      failures=$((failures + 1))
    elif ! printf '%s' "$out" | grep -qF -- "$reason_needle"; then
      printf 'FAIL [%s]: rc=%s was right but the reason was not "%s"\n%s\n' \
        "$label" "$rc" "$reason_needle" "$out"
      failures=$((failures + 1))
    elif [[ "$got_dispatches" != "$want_dispatches" ]]; then
      printf 'FAIL [%s]: expected %s dispatch record(s), got %s\n%s\n' \
        "$label" "$want_dispatches" "$got_dispatches" "$out"
      failures=$((failures + 1))
    elif [[ -n "$absent_needle" ]] && printf '%s' "$out" | grep -qF -- "$absent_needle"; then
      printf 'FAIL [%s]: output must NOT contain "%s"\n%s\n' "$label" "$absent_needle" "$out"
      failures=$((failures + 1))
    elif [[ -n "$dispatch_needle" ]] && ! printf '%s' "$log_body" | grep -qF -- "$dispatch_needle"; then
      printf 'FAIL [%s]: dispatch record did not contain "%s"\nrecorded: %s\n%s\n' \
        "$label" "$dispatch_needle" "${log_body:-<none>}" "$out"
      failures=$((failures + 1))
    elif ! printf '%s' "$out" | grep -qF -- "[FIXTURE DRY RUN]"; then
      printf 'FAIL [%s]: self-heal fixture output was not marked as a dry run\n%s\n' "$label" "$out"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] guard rc=%s dispatches=%s :: %s\n' \
        "$label" "$rc" "$got_dispatches" "$(printf '%s' "$out" | tail -1)"
    fi
  }

  echo "--- self-heal dispatch-and-wait harness (issue #2754) ---"
  local SH_GREEN='{"jobs":[{"name":"app2 journey suite (emulator + Docker agents)","conclusion":"success"}]}'
  local SH_RED='{"jobs":[{"name":"app2 journey suite (emulator + Docker agents)","conclusion":"failure"}]}'
  local SH_CANCELLED='{"jobs":[{"name":"app2 journey suite (emulator + Docker agents)","conclusion":"cancelled"}]}'

  # AC1: the 35140845314 shape — green verdict on the PARENT commit, release
  # HEAD one commit ahead. Self-heal dispatches on the release SHA, the fresh
  # run comes back green, the guard PASSES with no human in the loop.
  self_heal_case "selfheal-stale-green-recovers" 0 "safety verdict is green" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1002,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"in_progress","conclusion":null,"createdAt":"2026-09-16T19:40:00Z"}],
        [{"databaseId":1002,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1002":'"$SH_GREEN"'}}' \
    "" "ref=main sha=$REL"

  # AC1: MISSING verdict — nothing in the window carries a terminal journey
  # verdict (all skipped/cancelled or never ran). Same recovery.
  self_heal_case "selfheal-missing-green-recovers" 0 "safety verdict is green" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["main"],
      "runList":[],
      "pollRunLists":[
        [],
        [{"databaseId":1003,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"queued","conclusion":null,"createdAt":"2026-09-16T19:41:00Z"}],
        [{"databaseId":1003,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:41:00Z"}]],
      "jobViews":{"1003":'"$SH_GREEN"'}}' \
    "" "ref=main sha=$REL"

  # AC2: the fresh verdict comes back RED -> the guard blocks with the UNCHANGED
  # D37 red message — and dispatches exactly ONCE (never a second suite attempt
  # on red, AC4).
  self_heal_case "selfheal-fresh-red-still-blocks" 1 "safety verdict is RED" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1004,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"failure","createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1004":'"$SH_RED"'}}' \
    "" "ref=main sha=$REL"

  # AC4/anti-flake-masking: a genuinely RED verdict on a COVERING run never
  # triggers a dispatch at all — zero dispatch records.
  self_heal_case "selfheal-red-covering-no-dispatch" 1 "safety verdict is RED" 0 \
    '{"dispatchOk":true,
      "runList":[{"databaseId":1005,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"failure","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[],
      "jobViews":{"1005":'"$SH_RED"'}}'

  # Cost bound: a green CURRENT verdict passes with zero dispatches.
  self_heal_case "selfheal-green-current-no-dispatch" 0 "safety verdict is green" 0 \
    '{"dispatchOk":true,
      "runList":[{"databaseId":1006,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[],
      "jobViews":{"1006":'"$SH_GREEN"'}}'

  # Fail-closed: the dispatch call itself fails -> BLOCK, no poll, no green.
  self_heal_case "selfheal-dispatch-failure-blocks" 1 "self-heal dispatch of the 'app2.yml' workflow on the release commit failed" 0 \
    '{"dispatchOk":false,
      "branchesWhereHead":["main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[],
      "jobViews":{"1001":'"$SH_GREEN"'}}'

  # Fail-closed: the fresh run never terminates inside the budget -> BLOCK on
  # the poll budget (short --self-heal-timeout drives this offline).
  self_heal_case "selfheal-poll-timeout-blocks" 1 "poll budget exhausted" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1007,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"in_progress","conclusion":null,"createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"'}}' \
    "--self-heal-timeout 3 --self-heal-poll 1" "ref=main sha=$REL"

  # Fail-closed: the self-healed run completes but a run-level cancellation
  # killed its journey job -> a verdict GAP blocks; no second dispatch.
  self_heal_case "selfheal-gap-no-second-attempt" 1 "verdict GAP" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1008,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"cancelled","createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1008":'"$SH_CANCELLED"'}}' \
    "" "ref=main sha=$REL"

  # Cost bound: an app2 run already in flight on the release commit is ADOPTED
  # instead of dispatching a duplicate suite attempt — zero dispatch records.
  self_heal_case "selfheal-adopts-inflight-no-second-dispatch" 0 "safety verdict is green" 0 \
    '{"dispatchOk":true,
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"},
         {"databaseId":1009,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"in_progress","conclusion":null,"createdAt":"2026-09-16T19:39:00Z"}],
        [{"databaseId":1009,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:39:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1009":'"$SH_GREEN"'}}'

  echo
  # -------------------------------------------------------------------------
  # Issue #2822 — THE DISPATCH REF. `gh ... /dispatches -f ref=<sha>` is HTTP
  # 422 "No ref found" (run 35426894423), so the ref is resolved to a branch
  # whose HEAD is the release commit. These arms pin BOTH directions of that
  # resolution end-to-end: what gets recorded as the dispatch ref, and what
  # happens when no branch qualifies (BLOCK with the manual-dispatch
  # instruction — never a doomed SHA dispatch).
  # -------------------------------------------------------------------------
  echo "--- #2822 dispatch-ref resolution (end-to-end) ---"

  # AC1: the release commit IS main's tip -> self-heal dispatches --ref main
  # (and records the release SHA alongside it), and the gate recovers.
  self_heal_case "selfheal-dispatches-main-not-sha" 0 "safety verdict is green" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1010,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1010":'"$SH_GREEN"'}}' \
    "" "DISPATCH repo= workflow=app2.yml ref=main sha=$REL"

  # A release-branch head resolves to THAT branch, not to main and not to the
  # SHA — the release/vX.Y.Z stabilisation case (#2754's 34402741674 shape).
  self_heal_case "selfheal-dispatches-release-branch-head" 0 "safety verdict is green" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["release/v0.5.4"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1011,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1011":'"$SH_GREEN"'}}' \
    "" "ref=release/v0.5.4 sha=$REL"

  # Several branch heads -> 'main' wins deterministically.
  self_heal_case "selfheal-prefers-main-branch-head" 0 "safety verdict is green" 1 \
    '{"dispatchOk":true,
      "branchesWhereHead":["zz-topic","main"],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
        [{"databaseId":1012,"headSha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:40:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"',"1012":'"$SH_GREEN"'}}' \
    "" "ref=main sha=$REL"

  # AC2: the release commit is no branch's head -> the explicit manual-dispatch
  # BLOCK and ZERO dispatch records. The old code sent the SHA here and took an
  # HTTP 422 instead.
  self_heal_case "selfheal-unresolvable-ref-blocks-with-manual-message" 1 \
    "dispatch 'app2.yml' manually on a branch containing $REL" 0 \
    '{"dispatchOk":true,
      "branchesWhereHead":[],
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"'}}' \
    "--self-heal-timeout 3 --self-heal-poll 1" "" "poll budget exhausted"

  # ...and it must NOT be reported as a failed gh call: that message told the
  # 2026-09-19 on-call to look for a "gh error above" that never happened.
  self_heal_case "selfheal-unresolvable-ref-is-not-a-gh-failure" 1 \
    "no branch has that commit as its HEAD" 0 \
    '{"dispatchOk":true,
      "branchesWhereHead":[],
      "runList":[],
      "pollRunLists":[[]],
      "jobViews":{}}' \
    "--self-heal-timeout 3 --self-heal-poll 1" "" "gh error above"

  # Missing data fails closed too: a fixture with no branch-head information at
  # all must BLOCK, never default to some plausible branch.
  self_heal_case "selfheal-absent-branch-head-data-blocks" 1 \
    "no branch has that commit as its HEAD" 0 \
    '{"dispatchOk":true,
      "runList":[{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}],
      "pollRunLists":[
        [{"databaseId":1001,"headSha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","status":"completed","conclusion":"success","createdAt":"2026-09-16T19:00:00Z"}]],
      "jobViews":{"1001":'"$SH_GREEN"'}}' \
    "--self-heal-timeout 3 --self-heal-poll 1" "" "poll budget exhausted"

  echo
  echo "--- #2822 dispatch-ref resolution (pure) ---"
  # The pure half: branch-head candidates in, the ref workflow_dispatch gets
  # out. No gh, no fixture, no I/O.
  ref_pick_case() {
    local label="$1" expect_rc="$2" expect_ref="$3" candidates="$4"
    local got="" rc=0
    set +e
    got="$(self_heal_pick_dispatch_ref "$candidates")"
    rc=$?
    set -e
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected rc=%s got rc=%s (ref=%s)\n' "$label" "$expect_rc" "$rc" "${got:-<none>}"
      failures=$((failures + 1))
    elif [[ "$got" != "$expect_ref" ]]; then
      printf 'FAIL [%s]: expected ref "%s" got "%s"\n' "$label" "$expect_ref" "$got"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] rc=%s ref=%s\n' "$label" "$rc" "${got:-<none>}"
    fi
  }
  ref_pick_case "refpick-main-head"           0 "main"             "main"
  ref_pick_case "refpick-main-preferred"      0 "main"             $'zz-topic\nmain'
  ref_pick_case "refpick-main-preferred-rev"  0 "main"             $'main\nzz-topic'
  # 'aa-topic' sorts BEFORE 'main', so this arm reddens if the main preference
  # degrades into "lexicographically first" — the two rules only differ here.
  ref_pick_case "refpick-main-beats-earlier"  0 "main"             $'aa-topic\nmain'
  ref_pick_case "refpick-release-branch"      0 "release/v0.5.4"   "release/v0.5.4"
  ref_pick_case "refpick-deterministic-first" 0 "alpha"            $'zeta\nalpha'
  ref_pick_case "refpick-no-branch-head"      1 ""                 ""
  ref_pick_case "refpick-blank-candidates"    1 ""                 $'\n\n'

  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "SELF-TEST PASS: all pure-decision + fixture-driven + self-heal cases produced the expected verdict."
    return 0
  fi
  echo "SELF-TEST FAIL: $failures case(s) wrong."
  return 1
}

# ---------------------------------------------------------------------------
# Layer 2: fetch + resolve, then call the pure function.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# #2706 — the walk's stop rule, shared by the live and array-fixture paths.
#
# Only a REAL terminal verdict stops the walk: success, failure, timed_out
# (the producer RAN and hit its budget — a real signal about the lane). Every
# no-verdict state is a VERDICT GAP the walk steps past, reaching the newest
# run that actually produced one:
#   * ""            the job did not run in that run at all (pre-existing);
#   * "cancelled"   a run-level cancellation killed it mid-flight — no
#                   workflow-level configuration can prevent one (#2706);
#   * "skipped"     the job existed but its `if:` gate did not fire (a PR run,
#                   or a push whose lane selection skipped app2 — the state the
#                   "pick the first run that RAN the job" intent always meant,
#                   which the old any-non-empty stop rule missed: it halted on
#                   the first PR run and mis-reported a skip as "RED").
# ---------------------------------------------------------------------------
fault_verdict_stops_walk() {
  local conclusion="$1"
  case "$conclusion" in
    success | failure | timed_out) return 0 ;;
    *) return 1 ;;
  esac
}

# Gap notes go to STDERR: stdout of resolve_latest_fault_run is a single
# machine-parsed TSV line, and the release gate captures stdout+stderr into the
# same summary log — so the gap shows in the release state without corrupting
# the parse.
note_cancelled_gap() {
  local id="${1:-?}"
  echo "NOTE (#2706): run ${id}'s journey job concluded 'cancelled' — a run-level cancellation killed the fault-verdict producer mid-run (a verdict GAP, not a red); stepping past it to older runs." >&2
}

note_no_verdict_run() {
  local id="${1:-?}" conclusion="${2:-?}"
  echo "NOTE (#2706): run ${id}'s journey job produced no verdict (conclusion='${conclusion}' — lane skipped or gate did not fire); stepping past it." >&2
}

note_no_verdict_in_window() {
  echo "NOTE (#2706): no run in the recent window carries a real fault verdict (every journey job was guard-skipped or cancelled). There is no signal to release on." >&2
}

# Pin `gh` to THIS checkout's repository (issue #2379 round 2). An unpinned
# `gh run list` honours the inherited GH_REPO environment variable, which is the
# same class of bypass as the fixture one: point the guard at a green fork and
# the release gate reads someone else's verdict. Derived from remote.origin.url;
# unresolvable means BLOCK, never "query unpinned".
resolve_repo_slug() {
  local url name rest owner
  url="$(git config --get remote.origin.url 2>/dev/null || true)"
  [[ -n "$url" ]] || return 1
  url="${url%.git}"
  url="${url%/}"
  name="${url##*/}"
  rest="${url%/*}"
  owner="${rest##*[:/]}"
  [[ -n "$owner" && -n "$name" && "$owner" != "$url" ]] || return 1
  printf '%s/%s\n' "$owner" "$name"
}

# Resolve the latest nightly run that ACTUALLY ran the fault-verdict job, plus
# that job's conclusion. Emits four tab-separated fields on stdout:
#   <run_status>\t<job_conclusion>\t<run_head_sha>\t<databaseId>
# job_conclusion is "" when no run ran a matching job.
#
# Reads the --fixture file when one was passed (a JSON object with the run fields
# PLUS a `jobConclusion` field) — no `gh` call. Otherwise queries `gh`, pinned to
# this repository and with GH_REPO/GH_HOST scrubbed out of the environment.
resolve_latest_fault_run() {
  if [[ -n "$FIXTURE" ]]; then
    [[ -f "$FIXTURE" ]] ||
      { echo "fixture not found: $FIXTURE" >&2; return 2; }
    # A fixture is ONE run object — read verbatim as "the run the consumer
    # resolved", feeding the pure decision function directly (this is the shape
    # the C2/C3/C4 behavioural checks and the branch matrix pin) — or an ARRAY
    # of run objects newest-first, which exercises the live walk INCLUDING the
    # #2706 cancellation-gap stop rule.
    if jq -e 'type == "array"' "$FIXTURE" >/dev/null; then
      local n i row status conclusion sha db_id
      n="$(jq 'length' "$FIXTURE")"
      for ((i = 0; i < n; i++)); do
        row="$(jq -c ".[$i]" "$FIXTURE")"
        status="$(jq -r '.status // ""' <<<"$row")"
        conclusion="$(jq -r '.jobConclusion // ""' <<<"$row")"
        sha="$(jq -r '.headSha // ""' <<<"$row")"
        db_id="$(jq -r '(.databaseId // "") | tostring' <<<"$row")"
        if fault_verdict_stops_walk "$conclusion"; then
          printf '%s\t%s\t%s\t%s\n' "$status" "$conclusion" "$sha" "$db_id"
          return 0
        fi
        if [[ "$conclusion" == "cancelled" ]]; then
          note_cancelled_gap "$db_id"
        else
          note_no_verdict_run "$db_id" "$conclusion"
        fi
      done
      note_no_verdict_in_window
      printf '%s\t%s\t%s\t%s\n' "completed" "" "" ""
      return 0
    fi
    jq -r '[(.status // ""), (.jobConclusion // ""), (.headSha // ""), ((.databaseId // "") | tostring)] | @tsv' \
      "$FIXTURE"
    return 0
  fi

  command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; return 2; }
  # Issue #2754: with a self-heal harness fixture set, every gh interaction is
  # canned by the guard_gh_* wrappers below — no gh binary, no repo slug and
  # no network are needed, and none is consulted.
  if [[ -z "$SELF_HEAL_FIXTURE" ]]; then
    command -v gh >/dev/null 2>&1 || { echo "gh CLI is required" >&2; return 2; }
  fi

  local repo=""
  if [[ -z "$SELF_HEAL_FIXTURE" ]]; then
    repo="$(resolve_repo_slug)" || {
      echo "could not derive owner/repo from remote.origin.url — refusing to query gh unpinned (an unpinned query trusts \$GH_REPO)" >&2
      return 2
    }
  fi

  # Pull recent runs. Walk newest→oldest and pick the FIRST whose fault-verdict
  # job produced a real terminal verdict (fault_verdict_stops_walk): a
  # guard-skipped run never ran the suite, and a cancelled journey job is a
  # verdict GAP (#2706) — neither is a signal, so the walk steps past them to
  # the newest run that actually produced one.
  local runs
  runs="$(guard_gh_run_list "$repo")" || {
    echo "gh run list failed for workflow '$WORKFLOW' in ${repo:-<unresolved>}" >&2
    return 2
  }

  local count
  count="$(jq 'length' <<<"$runs")"
  local i id sha status
  for ((i = 0; i < count; i++)); do
    id="$(jq -r ".[$i].databaseId" <<<"$runs")"
    sha="$(jq -r ".[$i].headSha" <<<"$runs")"
    status="$(jq -r ".[$i].status" <<<"$runs")"

    # Inspect this run's jobs; find the fault-verdict job by name needle.
    local jobs job_conclusion
    jobs="$(guard_gh_run_jobs "$repo" "$id")" || continue
    job_conclusion="$(jq -r --arg needle "$JOB_NEEDLE" \
      'first(.jobs[] | select(.name | test($needle)) | .conclusion) // ""' \
      <<<"$jobs")"

    if fault_verdict_stops_walk "$job_conclusion"; then
      printf '%s\t%s\t%s\t%s\n' "$status" "$job_conclusion" "$sha" "$id"
      return 0
    fi
    if [[ "$job_conclusion" == "cancelled" ]]; then
      note_cancelled_gap "$id"
    else
      note_no_verdict_run "$id" "$job_conclusion"
    fi
  done

  # No run in the window actually ran the fault-verdict job, or every one that
  # did was cancelled (gap notes above) — no verdict signal anywhere.
  note_no_verdict_in_window
  printf '%s\t%s\t%s\t%s\n' "completed" "" "" ""
  return 0
}

# ---------------------------------------------------------------------------
# Issue #2754 — the SELF-HEAL layer.
#
# guard_gh_run_list / guard_gh_run_jobs / self_heal_dispatch are the ONLY gh
# touchpoints of the resolve walk and the self-heal loop. With a self-heal
# harness fixture set they are CANNED — the dispatch is recorded, never sent —
# so offline tests exercise the real wiring shape without a network call.
# ---------------------------------------------------------------------------

guard_gh_run_list() {
  # $1 repo. Prints the workflow's recent runs, newest first (the same query
  # the resolve walk has always made).
  if [[ -n "$SELF_HEAL_FIXTURE" ]]; then
    jq -c '.runList // []' "$SELF_HEAL_FIXTURE"
    return 0
  fi
  env -u GH_REPO -u GH_HOST gh run list --repo "$1" --workflow="$WORKFLOW" --limit 15 \
    --json databaseId,headSha,status,conclusion,createdAt 2>/dev/null
}

guard_gh_run_jobs() {
  # $1 repo, $2 run id. Prints that run's jobs JSON.
  if [[ -n "$SELF_HEAL_FIXTURE" ]]; then
    jq -c --arg id "$2" '.jobViews[$id] // {"jobs":[]}' "$SELF_HEAL_FIXTURE"
    return 0
  fi
  env -u GH_REPO -u GH_HOST gh run view --repo "$1" "$2" --json jobs 2>/dev/null
}

# Successive poll iterations read successive .pollRunLists entries; once the
# list is exhausted the LAST entry repeats (steady state), so a short
# --self-heal-timeout drives the timeout case offline. Entry 0 is the
# pre-dispatch state (the adopt scan reads it). The index is a PARAMETER, not
# a counter global: $() command substitution runs in a subshell, so a counter
# mutated inside this function would never advance for the caller.
self_heal_poll_list_at() {
  # $1 repo, $2 index into the canned poll sequence.
  if [[ -n "$SELF_HEAL_FIXTURE" ]]; then
    local n i
    n="$(jq '.pollRunLists | length' "$SELF_HEAL_FIXTURE")"
    i="$2"
    if (( i >= n )); then i=$((n - 1)); fi
    jq -c ".pollRunLists[$i]" "$SELF_HEAL_FIXTURE"
    return 0
  fi
  guard_gh_run_list "$1"
}

# ---------------------------------------------------------------------------
# ISSUE #2822 — A workflow_dispatch REF IS A BRANCH/TAG NAME, NEVER A SHA.
#
# #2754 shipped the dispatch as `-f ref="$release_sha"`, asserting in code and
# comment that the dispatches API accepts a commit SHA. It does not. Release
# gate run 35426894423 (release commit d30585322, 2026-09-19) got HTTP 422
# "No ref found for d30585322..." back, so D37's ONE sanctioned recovery path
# failed exactly when it was needed and the gate stayed red until an unrelated
# push happened to produce a fresh app2 run on a new head.
#
# The ref is therefore RESOLVED first, and only to a branch whose HEAD *is* the
# release commit (`branches-where-head`). That shape is the only one that both
# satisfies the API and guarantees the dispatched run carries the release SHA
# as its headSha — the exact field the poll loop below matches on. A branch
# that merely CONTAINS the commit would run the suite on a different head and
# could never produce the verdict this gate is waiting for, so it is not a
# fallback: when nothing has the commit as its head the guard BLOCKS with an
# actionable manual-dispatch message. It never re-sends the SHA (that is the
# 422 above) and never silently retargets a moved head.
# ---------------------------------------------------------------------------
guard_gh_branches_where_head() {
  # $1 repo, $2 sha. Prints the name of every branch whose HEAD commit is $2,
  # one per line (no output when none). Canned under a self-heal fixture like
  # every other gh call here, so a test never touches the network.
  if [[ -n "$SELF_HEAL_FIXTURE" ]]; then
    jq -r '(.branchesWhereHead // [])[]' "$SELF_HEAL_FIXTURE"
    return 0
  fi
  env -u GH_REPO -u GH_HOST gh api "repos/$1/commits/$2/branches-where-head" \
    --jq '.[].name' 2>/dev/null
}

self_heal_pick_dispatch_ref() {
  # $1 newline-separated branch names (branches whose head IS the release
  # commit). Prints the ref to dispatch; returns 1 when there is none. PURE —
  # no gh, no git, no I/O — so the self-test drives it directly.
  #
  # 'main' wins whenever it is a candidate: that is the release-gate's common
  # case and the ref the issue's acceptance criterion names. Otherwise the
  # lexicographically first candidate, so a commit that is the head of several
  # branches resolves deterministically instead of "whatever the API listed
  # first".
  local candidates="$1" branch="" chosen=""
  while IFS= read -r branch; do
    [[ -z "$branch" ]] && continue
    if [[ "$branch" == "main" ]]; then
      printf 'main\n'
      return 0
    fi
    if [[ -z "$chosen" || "$branch" < "$chosen" ]]; then
      chosen="$branch"
    fi
  done <<<"$candidates"
  [[ -z "$chosen" ]] && return 1
  printf '%s\n' "$chosen"
}

self_heal_resolve_dispatch_ref() {
  # $1 repo, $2 release sha -> the branch name to hand workflow_dispatch.
  local candidates=""
  candidates="$(guard_gh_branches_where_head "$1" "$2")" || candidates=""
  self_heal_pick_dispatch_ref "$candidates"
}

self_heal_dispatch() {
  # $1 repo, $2 release sha. THE dispatch (issue #2754 step 1) — the exact
  # call the sanctioned manual loop ran by hand, with #2822's ref resolution in
  # front of it. The repo is in the API path, so $GH_REPO cannot redirect it
  # (same pinning rule as every gh call in this script).
  #
  # Exit codes: 0 dispatched, 2 no acceptable ref (the BLOCK is printed here
  # and NOTHING is sent), 1 the dispatch call itself failed.
  #
  # HARD CONSTRAINT (#2754): with a self-heal harness fixture set this
  # RECORDS the dispatch to "<fixture>.dispatchlog" and never touches the
  # network — a test never dispatches a live workflow.
  local repo="$1" release_sha="$2" dispatch_ref=""
  if ! dispatch_ref="$(self_heal_resolve_dispatch_ref "$repo" "$release_sha")"; then
    echo "BLOCK: self-heal cannot dispatch the '$WORKFLOW' workflow for release commit $release_sha — no branch has that commit as its HEAD, and workflow_dispatch takes branch/tag refs only (a raw SHA is HTTP 422 'No ref found', #2822). The stale/missing verdict still blocks the release (D37): dispatch '$WORKFLOW' manually on a branch containing $release_sha, then re-run the gate." >&2
    return 2
  fi
  note_self_heal_dispatch_ref "$dispatch_ref" "$release_sha"
  if [[ -n "$SELF_HEAL_FIXTURE" ]]; then
    if [[ "$(jq -r '.dispatchOk // false' "$SELF_HEAL_FIXTURE")" == "true" ]]; then
      printf 'DISPATCH repo=%s workflow=%s ref=%s sha=%s\n' "$repo" "$WORKFLOW" "$dispatch_ref" "$release_sha" \
        >> "${SELF_HEAL_FIXTURE}.dispatchlog"
      return 0
    fi
    return 1
  fi
  env -u GH_REPO -u GH_HOST gh api "repos/$repo/actions/workflows/$WORKFLOW/dispatches" -f ref="$dispatch_ref"
}

# The TRIGGER (issue #2754 step 0). Self-heal fires ONLY for:
#   * MISSING — no run in the window carries a terminal journey verdict
#     (empty resolved headSha; the #2706 walk fell through), or
#   * STALE — a real verdict whose run does not cover the release HEAD
#     (head_is_ancestor != yes).
# A RED verdict (failure/timed_out) on a covering run stops the walk and NEVER
# reaches this function — no dispatch, no second suite attempt (D37 /
# anti-flake-masking). An incomplete run likewise never triggers: the producer
# is already working on exactly the run we would dispatch.
self_heal_required() {
  # $1 run_status (unused here; part of the stable call contract)
  # $2 job_conclusion (ditto — the pure function owns the verdict semantics)
  local run_status="$1" job_conclusion="$2" run_head_sha="$3" head_is_ancestor="$4"
  if [[ -z "$run_head_sha" ]]; then
    return 0 # MISSING: no verdict anywhere in the window
  fi
  if [[ "$head_is_ancestor" != "yes" ]]; then
    return 0 # STALE: the verdict tests a line that does not contain the release HEAD
  fi
  return 1 # green, red, timed_out, cancelled, skipped, incomplete: not ours
}

note_self_heal_start() {
  local reason="$1" release_head="$2" timeout="$3"
  echo "NOTE (#2754): the journey verdict is $reason for the release HEAD ($release_head) — self-healing instead of blocking: dispatching the '$WORKFLOW' workflow on the release commit and waiting for a fresh 'app2 journey suite' verdict (budget ${timeout}s). A genuinely RED verdict still blocks (D37, no override)." >&2
}

note_self_heal_adopt() {
  local id="${1:-?}"
  echo "NOTE (#2754): found an '$WORKFLOW' run (${id}) already in flight on the release commit — adopting it instead of dispatching a duplicate suite attempt." >&2
}

note_self_heal_dispatch_ref() {
  local ref="${1:-?}" sha="${2:-?}"
  echo "NOTE (#2822): dispatching '$WORKFLOW' on ref '${ref}' — the branch whose HEAD is the release commit ${sha}. workflow_dispatch takes branch/tag refs only; a raw SHA is HTTP 422." >&2
}

note_self_heal_poll() {
  local id="${1:-?}" status="${2:-?}"
  echo "NOTE (#2754): self-heal run ${id} is status='${status}'; polling for a terminal journey verdict." >&2
}

# Wait for the dispatched/adopted run's journey verdict (issue #2754 steps 2+3).
# On success sets SELF_HEAL_FRESH_* and returns 0. Any failure prints a BLOCK
# reason and returns 1 — never a green, never a second dispatch.
self_heal_wait_for_fresh_verdict() {
  # $1 release_head
  local release_head="$1"
  local repo=""
  if [[ -z "$SELF_HEAL_FIXTURE" ]]; then
    repo="$(resolve_repo_slug)" || {
      echo "BLOCK: self-heal could not resolve the repository slug for the dispatch (see error above)." >&2
      return 1
    }
  fi

  local snapshot before_ids adopted_id="" poll_idx=0
  snapshot="$(self_heal_poll_list_at "$repo" "$poll_idx")" || snapshot="[]"
  poll_idx=$((poll_idx + 1))
  before_ids="$(jq -r '[.[].databaseId | tostring] | join(",")' <<<"$snapshot" 2>/dev/null)" || before_ids=""
  before_ids="[$before_ids]"

  adopted_id="$(jq -r --arg sha "$release_head" \
    '[.[] | select(.headSha == $sha and .status != "completed")]
       | sort_by(.databaseId) | reverse | (.[0].databaseId // "") | tostring' \
    <<<"$snapshot" 2>/dev/null)" || adopted_id=""
  if [[ -n "$adopted_id" ]]; then
    note_self_heal_adopt "$adopted_id"
  else
    # rc 2 (#2822) means no branch has the release commit as its head, so no
    # dispatch was attempted and self_heal_dispatch already printed the
    # actionable BLOCK — there is no "gh error above" to point at.
    local dispatch_rc=0
    self_heal_dispatch "$repo" "$release_head" || dispatch_rc=$?
    if (( dispatch_rc == 2 )); then
      return 1
    elif (( dispatch_rc != 0 )); then
      echo "BLOCK: self-heal dispatch of the '$WORKFLOW' workflow on the release commit failed (gh error above). The stale/missing verdict still blocks the release (D37): dispatch it manually, or fix the failure, then re-run the gate." >&2
      return 1
    fi
  fi

  local deadline=$((SECONDS + SELF_HEAL_TIMEOUT_SECONDS))
  local runs candidate cand_id cand_status jobs job_conclusion
  while :; do
    candidate=""
    if runs="$(self_heal_poll_list_at "$repo" "$poll_idx")"; then
      poll_idx=$((poll_idx + 1))
      candidate="$(jq -c --arg sha "$release_head" --arg adopted "$adopted_id" --argjson before "$before_ids" \
        '[.[] | select(.headSha == $sha)
              | (.databaseId | tostring) as $id
              | select(($id == $adopted) or (($before | index($id)) | not))]
         | sort_by(.databaseId) | reverse | .[0] // empty' \
        <<<"$runs" 2>/dev/null)" || candidate=""
    fi
    if [[ -n "$candidate" ]]; then
      cand_id="$(jq -r '(.databaseId // "") | tostring' <<<"$candidate")"
      cand_status="$(jq -r '.status // ""' <<<"$candidate")"
      if [[ "$cand_status" == "completed" ]]; then
        jobs="$(guard_gh_run_jobs "$repo" "$cand_id")" || jobs='{"jobs":[]}'
        job_conclusion="$(jq -r --arg needle "$JOB_NEEDLE" \
          'first(.jobs[] | select(.name | test($needle)) | .conclusion) // ""' \
          <<<"$jobs")"
        if fault_verdict_stops_walk "$job_conclusion"; then
          SELF_HEAL_FRESH_STATUS="completed"
          SELF_HEAL_FRESH_CONCLUSION="$job_conclusion"
          SELF_HEAL_FRESH_HEAD="$release_head"
          SELF_HEAL_FRESH_ID="$cand_id"
          return 0
        fi
        echo "BLOCK: self-heal run ${cand_id:-?} completed but its journey job produced no terminal verdict (conclusion='${job_conclusion:-none}') — a verdict GAP, not a red. No second suite attempt is made (bounded cost, #2754): the release stays BLOCKED (D37). Understand the gap, then re-run the gate." >&2
        return 1
      fi
      note_self_heal_poll "$cand_id" "$cand_status"
    fi
    if (( SECONDS >= deadline )); then
      echo "BLOCK: self-heal poll budget exhausted (${SELF_HEAL_TIMEOUT_SECONDS}s) without a terminal '$WORKFLOW' journey verdict on the release commit. The stale/missing verdict still blocks the release (D37): check the dispatched run, then re-run the gate." >&2
      return 1
    fi
    sleep "$SELF_HEAL_POLL_SECONDS"
  done
}

main() {
  if [[ "${1:-}" == "--self-test" ]]; then
    self_test
    exit $?
  fi

  # D37 (#2379): no skip branch belongs here, and no environment variable is
  # consulted anywhere in this script. A red / stale / cancelled / missing fault
  # verdict on the release commit unconditionally fails. An unknown flag is a
  # hard error rather than a silently-ignored argument (fail closed).
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --release-head) RELEASE_HEAD_OVERRIDE="${2:-}"; shift 2 ;;
      --fixture)      FIXTURE="${2:-}";               shift 2 ;;
      --workflow)     WORKFLOW="${2:-}";              shift 2 ;;
      --job-needle)   JOB_NEEDLE="${2:-}";            shift 2 ;;
      --self-heal-fixture) SELF_HEAL_FIXTURE="${2:-}"; shift 2 ;;
      --self-heal-timeout) SELF_HEAL_TIMEOUT_SECONDS="${2:-}"; shift 2 ;;
      --self-heal-poll)    SELF_HEAL_POLL_SECONDS="${2:-}";    shift 2 ;;
      -h|--help)      usage; exit 0 ;;
      *)
        echo "unknown argument: $1" >&2
        usage >&2
        exit 2
        ;;
    esac
  done

  # Fail closed on non-numeric self-heal knobs: a garbage budget must be a hard
  # usage error, not an unbounded or immediately-dead poll loop.
  case "${SELF_HEAL_TIMEOUT_SECONDS}${SELF_HEAL_POLL_SECONDS}" in
    ''|*[!0-9]*)
      echo "error: --self-heal-timeout / --self-heal-poll take non-negative integer seconds" >&2
      exit 2
      ;;
  esac

  # A fixture run is a DRY RUN, never a release verdict. Mark every line so the
  # output cannot be pasted into (or mistaken for) a release summary — the round-1
  # bypass produced a bare "PASS: ... verdict is green" from a fabricated file.
  if [[ -n "$FIXTURE" ]]; then
    FIXTURE_PREFIX="[FIXTURE DRY RUN] "
    echo "${FIXTURE_PREFIX}TEST MODE: reading $FIXTURE instead of the real nightly run. This is NOT a release verdict (D37)."
  fi
  # Issue #2754: a self-heal harness fixture is equally test-only. The canned
  # dispatch RECORDS to a log file and never reaches the network, and the
  # marking keeps a canned green out of any release summary.
  if [[ -n "$SELF_HEAL_FIXTURE" ]]; then
    FIXTURE_PREFIX="[FIXTURE DRY RUN] "
    echo "${FIXTURE_PREFIX}TEST MODE: self-heal harness fixture $SELF_HEAL_FIXTURE drives every gh interaction (dispatch recorded, never sent). This is NOT a release verdict (D37)."
  fi

  local release_head
  release_head="${RELEASE_HEAD_OVERRIDE:-$(git rev-parse HEAD)}"

  local resolved
  resolved="$(resolve_latest_fault_run)" || {
    echo "BLOCK: could not resolve the latest nightly fault run (see error above)." >&2
    exit 1
  }

  # Split the four tab-separated fields WITHOUT `IFS=$'\t' read`: tab is IFS
  # whitespace, so bash collapses a run of delimiters and an empty middle field
  # shifts every later field left. With an empty jobConclusion (the fault-verdict
  # job never ran) that put the headSha into job_conclusion and the databaseId
  # into run_head_sha, and the guard blocked as STALE instead of as "no fault
  # signal" — right exit code, wrong reason, and a "no-verdict" test that passed
  # without ever reaching the branch it claimed to cover (issue #2379 round 2).
  local fields=()
  mapfile -t fields < <(printf '%s\n' "${resolved//$'\t'/$'\n'}")
  if [[ "${#fields[@]}" -ne 4 ]]; then
    echo "${FIXTURE_PREFIX}BLOCK: could not parse the resolved nightly fault run (expected 4 tab-separated fields, got ${#fields[@]})." >&2
    exit 1
  fi
  local run_status="${fields[0]}"
  local job_conclusion="${fields[1]}"
  local run_head_sha="${fields[2]}"
  local db_id="${fields[3]}"

  # Does the run COVER the release HEAD? The nightly tested commit
  # `run_head_sha`; its result is valid for the release commit `release_head`
  # only if `release_head` is contained in the tested line — i.e. release_head
  # is an ancestor-or-equal of run_head_sha. If the release HEAD has advanced
  # PAST the nightly's sha (release_head is a DESCENDANT of run_head_sha), the
  # nightly never tested those newer commits → STALE. Empty headSha → no.
  local head_is_ancestor="no"
  if [[ -n "$run_head_sha" ]]; then
    if [[ "$run_head_sha" == "$release_head" ]]; then
      head_is_ancestor="yes"
    elif git merge-base --is-ancestor "$release_head" "$run_head_sha" 2>/dev/null; then
      head_is_ancestor="yes"
    fi
  fi

  # Issue #2754 — SELF-HEAL before blocking on a stale/missing verdict. Live
  # path only: a --fixture dry run skips this entirely and blocks STALE exactly
  # as it did before the feature (the offline repro of the 35140845314
  # signature), and a --self-heal-fixture harness run exercises this code with
  # canned gh responses. A red verdict on a covering run never enters
  # self_heal_required — the D37 block below is byte-identical to pre-#2754.
  if [[ -z "$FIXTURE" ]] && self_heal_required "$run_status" "$job_conclusion" "$run_head_sha" "$head_is_ancestor"; then
    local self_heal_reason="STALE"
    [[ -z "$run_head_sha" ]] && self_heal_reason="MISSING"
    note_self_heal_start "$self_heal_reason" "$release_head" "$SELF_HEAL_TIMEOUT_SECONDS"
    SELF_HEAL_FRESH_STATUS="" SELF_HEAL_FRESH_CONCLUSION="" SELF_HEAL_FRESH_HEAD="" SELF_HEAL_FRESH_ID=""
    if ! self_heal_wait_for_fresh_verdict "$release_head"; then
      exit 1
    fi
    run_status="$SELF_HEAL_FRESH_STATUS"
    job_conclusion="$SELF_HEAL_FRESH_CONCLUSION"
    run_head_sha="$SELF_HEAL_FRESH_HEAD"
    db_id="$SELF_HEAL_FRESH_ID"
    # The fresh run was selected BECAUSE its head is the release commit, so it
    # covers by construction — recompute honestly rather than asserting.
    head_is_ancestor="no"
    [[ "$run_head_sha" == "$release_head" ]] && head_is_ancestor="yes"
    echo "${FIXTURE_PREFIX}Self-healed verdict (issue #2754): workflow=$WORKFLOW id=${db_id:-none} status=$run_status fault-verdict-job-conclusion=$job_conclusion headSha=$run_head_sha"
  fi

  echo "${FIXTURE_PREFIX}Nightly fault run: workflow=$WORKFLOW id=${db_id:-none} status=${run_status:-?} fault-verdict-job-conclusion=${job_conclusion:-<none>} headSha=${run_head_sha:-<none>}"
  echo "${FIXTURE_PREFIX}Release HEAD=$release_head head_is_ancestor=$head_is_ancestor"

  local verdict rc
  set +e
  verdict="$(evaluate_nightly_fault_run "$run_status" "$job_conclusion" "$run_head_sha" "$release_head" "$head_is_ancestor")"
  rc=$?
  set -e
  echo "${FIXTURE_PREFIX}${verdict}"
  exit "$rc"
}

main "$@"
