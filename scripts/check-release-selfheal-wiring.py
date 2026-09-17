#!/usr/bin/python3 -I
"""Fail closed when the #2754 self-heal wiring silently vanishes or is neutered.

Issue #2754 taught REV's D37 guard to self-heal a stale/missing app2 journey
verdict: dispatch the app2 workflow on the RELEASE commit, poll the fresh
journey conclusion within a bounded budget, and re-evaluate. That is exactly
the kind of shape that silently rots — the #2675 lesson: dead-notify `if:`
gates looked fine in prose while never firing, and nothing in the repo read
the wiring. This guard pins the LOAD-BEARING TEXT of the wiring, not the
comments around it:

  1. The dispatch is the REAL workflow-dispatches API call, targets the
     RELEASE sha (`-f ref="$release_sha"`, never the stale run's head), and is
     reachable ONLY past the canned-fixture branch — a harness test can never
     dispatch a live workflow.
  2. The trigger fires ONLY for MISSING (no verdict in the window) and STALE
     (verdict does not cover the release HEAD) — never for a genuine RED, so
     the anti-flake-masking property (one dispatch max, no second suite
     attempt on red) cannot regress without this guard going red.
  3. The poll is BOUNDED (deadline from the timeout knob, checked in the loop)
     and every failure path blocks — there is no override route to green.
  4. The fresh verdict is judged by the SAME pure decision function, and the
     D37 red message is unchanged.
  5. The callers are intact: the release script still invokes the guard with
     only --release-head (self-heal default-on), the REV workflow grants
     `actions: write` + GH_TOKEN (silently dropping either 403s the dispatch
     — self-heal dead while looking wired), the guard's --fixture dry run
     never self-heals, and the CI lanes still run this guard.

The self-test mutates copies and demands a RED for each regression and a GREEN
for comment-only rewording (a prose-satisfiable assertion is the G6 shape,
docs/ci-pitfalls.md).

Usage:
  scripts/check-release-selfheal-wiring.py              # check the real tree
  scripts/check-release-selfheal-wiring.py --self-test  # red->green mutations
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
GUARD_SH = ROOT / "scripts" / "check-nightly-fault-run.sh"
RELEASE_YML = ROOT / ".github" / "workflows" / "release-emulator-validation.yml"
RELEASE_SH = ROOT / "scripts" / "release-emulator-validation.sh"
CI_SELECTION_SH = ROOT / "scripts" / "ci-test-selection-guards.sh"
TESTS_YML = ROOT / ".github" / "workflows" / "tests.yml"

VALIDATION_STEP_NAME = "Run emulator-only release validation"
STEP_KEY = re.compile(r"^      - ")
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
REV_JOB = "emulator-release-validation"


class GuardFailure(ValueError):
    """The self-heal wiring no longer means what the guard needs."""


def extract_func(text: str, name: str) -> str:
    """Return a bash function's body: from `name() {` to the next `^}`."""
    lines = text.splitlines()
    starts = [
        index
        for index, line in enumerate(lines)
        if line.startswith(f"{name}() ") or line == f"{name}() {{"
    ]
    if len(starts) != 1:
        raise GuardFailure(f"expected exactly one {name!r} function, found {len(starts)}")
    start = starts[0]
    end = next(
        (index for index in range(start + 1, len(lines)) if lines[index] == "}"),
        len(lines),
    )
    return "\n".join(lines[start : end + 1])


def extract_step(block: str, step_name: str) -> str:
    lines = block.splitlines()
    wanted = f"- name: {step_name}"
    starts = [index for index, line in enumerate(lines) if line.strip() == wanted]
    if len(starts) != 1:
        raise GuardFailure(
            f"expected exactly one step named {step_name!r}, found {len(starts)}"
        )
    start = starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if STEP_KEY.match(lines[index])
        ),
        len(lines),
    )
    return "\n".join(lines[start:end])


def extract_job(workflow: str, job_name: str) -> str:
    lines = workflow.splitlines()
    starts = [
        index
        for index, line in enumerate(lines)
        if (match := JOB_KEY.match(line)) and match.group(1) == job_name
    ]
    if len(starts) != 1:
        raise GuardFailure(f"expected exactly one {job_name!r} job, found {len(starts)}")
    start = starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if JOB_KEY.match(lines[index])
        ),
        len(lines),
    )
    return "\n".join(lines[start:end])


def require(haystack: str, needle: str, where: str) -> None:
    if needle not in haystack:
        raise GuardFailure(f"{where} must contain {needle!r}")


def require_once(haystack: str, needle: str, where: str) -> None:
    count = haystack.count(needle)
    if count != 1:
        raise GuardFailure(f"{where} must contain {needle!r} exactly once, found {count}")


def require_before(haystack: str, first: str, then: str, where: str) -> None:
    """`first` must appear in the text BEFORE `then` does (gate ordering)."""
    if first not in haystack:
        raise GuardFailure(f"{where} must contain {first!r}")
    if then not in haystack:
        raise GuardFailure(f"{where} must contain {then!r}")
    if haystack.index(first) > haystack.index(then):
        raise GuardFailure(
            f"{where}: {first!r} must appear before {then!r} (the canned-fixture "
            "branch must gate the live call, so a harness run can never dispatch)"
        )


def validate_guard_sh(text: str) -> None:
    # --- the trigger: ONLY missing/stale, never a red verdict. ---
    trigger = extract_func(text, "self_heal_required")
    require_once(trigger, "-z \"$run_head_sha\"", "self_heal_required (MISSING trigger)")
    require_once(
        trigger,
        '"$head_is_ancestor" != "yes"',
        "self_heal_required (STALE trigger)",
    )
    require_once(
        trigger,
        "return 1 # green, red, timed_out, cancelled, skipped, incomplete: not ours",
        "self_heal_required (non-stale/missing verdicts must fall through to the "
        "D37 block, never to a dispatch)",
    )

    # --- the dispatch: real API, release sha, canned branch first. ---
    dispatch = extract_func(text, "self_heal_dispatch")
    require_once(
        dispatch,
        'gh api "repos/$repo/actions/workflows/$WORKFLOW/dispatches"',
        "self_heal_dispatch (the workflow-dispatches API call)",
    )
    require_once(
        dispatch,
        '-f ref="$release_sha"',
        "self_heal_dispatch (must dispatch the RELEASE sha — not the stale "
        "run's headSha, not a ref name)",
    )
    require_before(
        dispatch,
        '[[ -n "$SELF_HEAL_FIXTURE" ]]',
        "gh api",
        "self_heal_dispatch (the canned-fixture branch must gate the live gh "
        "call — offline tests record the dispatch, never send it)",
    )

    # --- the wait: bounded budget, terminal-verdict stop rule. ---
    wait = extract_func(text, "self_heal_wait_for_fresh_verdict")
    require_once(
        wait,
        "deadline=$((SECONDS + SELF_HEAL_TIMEOUT_SECONDS))",
        "self_heal_wait_for_fresh_verdict (the poll budget must derive from the "
        "timeout knob)",
    )
    require_once(
        wait,
        "(( SECONDS >= deadline ))",
        "self_heal_wait_for_fresh_verdict (the deadline must be CHECKED in the "
        "poll loop)",
    )
    require_once(
        wait,
        'sleep "$SELF_HEAL_POLL_SECONDS"',
        "self_heal_wait_for_fresh_verdict (the poll interval knob)",
    )
    require_once(
        wait,
        'fault_verdict_stops_walk "$job_conclusion"',
        "self_heal_wait_for_fresh_verdict (a fresh verdict counts only under "
        "the same terminal-verdict stop rule the resolve walk uses)",
    )

    # --- main wiring: dry runs never self-heal; fresh verdicts go through the
    # ONE pure decision function (no second, override-shaped path). ---
    require_once(
        text,
        "[[ -z \"$FIXTURE\" ]] && self_heal_required",
        "main (a --fixture dry run must skip self-heal entirely and block "
        "STALE exactly as before)",
    )
    require_once(
        text,
        'self_heal_wait_for_fresh_verdict "$release_head"',
        "main (the self-heal wait is actually invoked)",
    )
    require_once(
        text,
        'evaluate_nightly_fault_run "$run_status" "$job_conclusion" '
        '"$run_head_sha" "$release_head" "$head_is_ancestor"',
        "main (the fresh verdict must be evaluated by the SAME pure decision "
        "function — the only route to PASS)",
    )
    require_once(
        text,
        "safety verdict is RED (fault-verdict job conclusion=",
        "evaluate_nightly_fault_run (the D37 red message, unchanged by #2754)",
    )


def validate_release_yml(text: str) -> None:
    # `actions: write` is what makes the dispatch legal in CI; silently
    # dropping it back to read 403s every self-heal while the wiring looks
    # perfect — the #2675 rot class.
    job = extract_job(text, REV_JOB)
    # Count the real 6-space-indented YAML key, not prose mentions in
    # comments (the job comment block names `actions: write` too).
    require_once(
        job,
        "\n      actions: write",
        f"{REV_JOB} permissions (the dispatch needs write)",
    )

    step = extract_step(text, VALIDATION_STEP_NAME)
    require_once(
        step,
        "GH_TOKEN: ${{ github.token }}",
        f"the {VALIDATION_STEP_NAME!r} step (gh auth for the guard's dispatch/poll)",
    )


def validate_release_sh(text: str) -> None:
    # The release path invokes the guard with ONLY --release-head: self-heal is
    # default-on there and no test-only flag can sneak in (mirrors the D37
    # C4-static invariant so this guard reddens independently of that one).
    require_once(
        text,
        'scripts/check-nightly-fault-run.sh --release-head "$(git rev-parse HEAD)"',
        "release-emulator-validation.sh (the guard invocation)",
    )


def validate_ci_selection_sh(text: str) -> None:
    # The wiring guard must be invoked by the guards lane in BOTH forms
    # (self-test mutations + live tree), or it can vanish from CI silently.
    require_once(
        text,
        "check-release-selfheal-wiring.py --self-test",
        "ci-test-selection-guards.sh (the mutation self-test lane)",
    )
    count = text.count("check-release-selfheal-wiring.py")
    if count < 2:
        raise GuardFailure(
            "ci-test-selection-guards.sh must invoke check-release-selfheal-wiring.py "
            f"at least twice (self-test + live), found {count}"
        )


def validate_tests_yml(text: str) -> None:
    # ...and the guards lane itself must still be run by CI. Pinned as the
    # executed 10-space-indented run line — tests.yml also MENTIONS the
    # script in a comment and in the chmod +x line.
    require_once(
        text,
        "\n          scripts/ci-test-selection-guards.sh",
        "tests.yml (the guards-test-selection lane entry)",
    )


def validate_tree(root: Path = ROOT) -> None:
    validate_guard_sh((root / "scripts/check-nightly-fault-run.sh").read_text())
    validate_release_yml(
        (root / ".github/workflows/release-emulator-validation.yml").read_text()
    )
    validate_release_sh((root / "scripts/release-emulator-validation.sh").read_text())
    validate_ci_selection_sh((root / "scripts/ci-test-selection-guards.sh").read_text())
    validate_tests_yml((root / ".github/workflows/tests.yml").read_text())


FILES = (
    "scripts/check-nightly-fault-run.sh",
    ".github/workflows/release-emulator-validation.yml",
    "scripts/release-emulator-validation.sh",
    "scripts/ci-test-selection-guards.sh",
    ".github/workflows/tests.yml",
)


def self_test() -> None:
    checks = 0
    try:
        validate_tree()
        live_green = True
    except GuardFailure as exc:
        live_green = False
        live_error = str(exc)

    originals = {rel: (ROOT / rel).read_text() for rel in FILES}

    def expect_red(label: str, file: str, old: str, new: str) -> None:
        nonlocal checks
        if old not in originals[file]:
            raise GuardFailure(
                f"self-test mutation {label!r} no longer applies — the pinned "
                "code shape drifted; update the mutation"
            )
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            for rel, content in originals.items():
                path = tmp / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content.replace(old, new, 1) if rel == file else content)
            try:
                validate_tree(tmp)
            except GuardFailure:
                checks += 1
            else:
                raise GuardFailure(f"self-test accepted an unsafe mutation: {label}")

    def expect_green(label: str, file: str, old: str, new: str) -> None:
        if old not in originals[file]:
            raise GuardFailure(f"self-test green mutation {label!r} no longer applies")
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            for rel, content in originals.items():
                path = tmp / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content.replace(old, new, 1) if rel == file else content)
            try:
                validate_tree(tmp)
            except GuardFailure as exc:
                raise GuardFailure(
                    f"self-test rejected a harmless change: {label} ({exc})"
                ) from exc

    guard = "scripts/check-nightly-fault-run.sh"
    yml = ".github/workflows/release-emulator-validation.yml"
    rel = "scripts/release-emulator-validation.sh"
    sel = "scripts/ci-test-selection-guards.sh"
    tests = ".github/workflows/tests.yml"

    # RED: the dispatch call is removed/neutered.
    expect_red(
        "dispatch call replaced with a no-op",
        guard,
        'gh api "repos/$repo/actions/workflows/$WORKFLOW/dispatches"',
        'echo "dispatch suppressed"',
    )
    # RED: the dispatch targets the WRONG ref (a ref name instead of the
    # release sha — the suite would run on a line that may not contain it).
    expect_red(
        "dispatch ref is not the release sha",
        guard,
        '-f ref="$release_sha"',
        '-f ref="main"',
    )
    # RED: the canned branch no longer gates the live call (tests dispatch).
    expect_red(
        "canned fixture branch stops gating the live dispatch",
        guard,
        "  if [[ -n \"$SELF_HEAL_FIXTURE\" ]]; then\n"
        "    if [[ \"$(jq -r '.dispatchOk // false' \"$SELF_HEAL_FIXTURE\")\" == \"true\" ]]; then",
        "  if [[ -z \"$NEVER_SET\" ]]; then\n"
        "    if [[ \"$(jq -r '.dispatchOk // false' \"$SELF_HEAL_FIXTURE\")\" == \"true\" ]]; then",
    )
    # RED: the trigger fires on EVERY verdict — a red verdict would get a
    # second suite attempt (anti-flake-masking broken).
    expect_red(
        "trigger fires on red verdicts too",
        guard,
        "return 1 # green, red, timed_out, cancelled, skipped, incomplete: not ours",
        "return 0",
    )
    # RED: the MISSING trigger is gone. (Anchored to the return comment that
    # only exists in self_heal_required — the bare if-line also appears in the
    # resolve walk, and replace(old, new, 1) would neuter the wrong site.)
    expect_red(
        "missing-verdict trigger removed",
        guard,
        'if [[ -z "$run_head_sha" ]]; then\n    return 0 # MISSING: no verdict anywhere in the window',
        'if [[ -z "$unrelated" ]]; then\n    return 0 # MISSING: no verdict anywhere in the window',
    )
    # RED: the STALE trigger is gone. (Same anchoring rationale.)
    expect_red(
        "stale-verdict trigger removed",
        guard,
        'if [[ "$head_is_ancestor" != "yes" ]]; then\n    return 0 # STALE: the verdict tests a line that does not contain the release HEAD',
        'if [[ "$head_is_ancestor" == "impossible" ]]; then\n    return 0 # STALE: the verdict tests a line that does not contain the release HEAD',
    )
    # RED: the poll budget is no longer bounded by the knob.
    expect_red(
        "poll budget unbounded",
        guard,
        "deadline=$((SECONDS + SELF_HEAL_TIMEOUT_SECONDS))",
        "deadline=$((SECONDS + 100000000))",
    )
    # RED: the deadline is no longer checked (unbounded wait).
    expect_red(
        "deadline check removed",
        guard,
        "(( SECONDS >= deadline ))",
        "(( SECONDS >= deadline + 100000000 ))",
    )
    # RED: a --fixture dry run would now dispatch live workflows.
    expect_red(
        "fixture dry run lost its self-heal gate",
        guard,
        "[[ -z \"$FIXTURE\" ]] && self_heal_required",
        "self_heal_required",
    )
    # RED: the fresh verdict takes a path around the pure decision function.
    expect_red(
        "fresh verdict bypasses the pure decision function",
        guard,
        "evaluate_nightly_fault_run \"$run_status\" \"$job_conclusion\" "
        '"$run_head_sha" "$release_head" "$head_is_ancestor"',
        'echo "verdict faked"',
    )
    # RED: the D37 red message changed.
    expect_red(
        "D37 red message edited",
        guard,
        "safety verdict is RED (fault-verdict job conclusion=",
        "safety verdict is yellowish (conclusion=",
    )
    # RED: the workflow permission regresses to read — every dispatch 403s.
    # (Pinned as the indented YAML key so the mutation edits the permission,
    # not the prose mention inside the job's comment block.)
    expect_red(
        "actions:write dropped from the REV job",
        yml,
        "\n      actions: write",
        "\n      actions: read",
    )
    # RED: the release script stops invoking the guard.
    expect_red(
        "release path stops calling the guard",
        rel,
        'scripts/check-nightly-fault-run.sh --release-head "$(git rev-parse HEAD)"',
        "echo skipped",
    )
    # RED: the guards lane stops running this wiring guard.
    expect_red(
        "wiring guard unwired from ci-test-selection-guards.sh",
        sel,
        "check-release-selfheal-wiring.py --self-test",
        "check-release-red-notify-wiring.py --self-test",
    )
    # RED: tests.yml stops running the guards lane. (Pinned as the indented
    # run line so the mutation edits the execution, not the comment mention.)
    expect_red(
        "guards lane unwired from tests.yml",
        tests,
        "\n          scripts/ci-test-selection-guards.sh",
        "\n          echo guards lane removed",
    )
    # GREEN: comment-only rewording near the wiring must stay green.
    expect_green(
        "rewording the self-heal header comment",
        guard,
        "STALE/MISSING VERDICTS SELF-HEAL INSTEAD OF PAGING A HUMAN.",
        "STALE/MISSING VERDICTS SELF-HEAL INSTEAD OF CALLING A HUMAN.",
    )

    expected = 15
    if checks != expected:
        raise GuardFailure(f"self-test ran {checks} red mutations, expected {expected}")

    if not live_green:
        raise GuardFailure(
            "live tree is RED (this is the reproduce-first proof on the "
            f"pre-fix wiring, and a failure after wiring): {live_error}"
        )
    print(
        f"PASS: #2754 self-heal wiring self-test ({checks} red mutations + live tree green)"
    )


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if argv:
        print("usage: check-release-selfheal-wiring.py [--self-test]", file=sys.stderr)
        return 2
    try:
        validate_tree()
    except GuardFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "PASS: #2754 self-heal wiring intact — dispatch targets the release sha "
        "behind the canned-fixture gate, only stale/missing triggers, bounded "
        "poll, one decision function, D37 red message unchanged"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
