#!/usr/bin/python3 -I
"""Fail closed when the #2675 workflow `if:` gates regress into dead CI.

Issue #2675 shipped two workflow `if:` expressions whose semantics did not
match the behavior their own comments promised, and no self-test in the repo
reads workflow `if:` text — scripts/check-test-execution-ledger-wiring.py
pins invocations and step names, not gate semantics — so both could silently
return:

  1. full-suite-notify.yml's notify-release-emulator-red job gated on
     `github.event.workflow_run.event == 'schedule'`, but Release Emulator
     Validation has NO schedule trigger. In this workflow_run context
     github.event.workflow_run IS the REV run and its .event is
     'workflow_run' (fired by the nightly scheduled Tests chain) or
     'workflow_dispatch' (manual) — never 'schedule'. The job could never
     fire: a never-firing red-run notify.
  2. release-emulator-validation.yml's validation step was gated
     `!cancelled() && steps.boot_check.outcome == 'success'`, so a failed
     boot preflight SKIPPED the very step its comments called "the bounded
     boot retry (attempt 2)" — the in-run retry could never happen.

This guard parses the `if:` expressions out of the committed workflow YAML
TEXT (folded `>-` scalars and inline `${{ }}` both) and requires the exact
gate clauses. The self-test mutates copies and demands a RED for each
regression — including both shipped defects — and a GREEN for comment-only
rewording (a prose-satisfiable assertion is the G6 shape,
docs/ci-pitfalls.md).

Usage:
  scripts/check-release-red-notify-wiring.py              # check the real tree
  scripts/check-release-red-notify-wiring.py --self-test  # red->green mutations
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
NOTIFY_YML = ROOT / ".github" / "workflows" / "full-suite-notify.yml"
RELEASE_YML = ROOT / ".github" / "workflows" / "release-emulator-validation.yml"

JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
STEP_KEY = re.compile(r"^      - ")

BOOT_STEP_NAME = "Boot-check hosted emulator (issue #2675 bounded boot retry)"
VALIDATION_STEP_NAME = "Run emulator-only release validation"
REV_ON_WORKFLOWS = 'workflows: ["Tests", "Release Emulator Validation"]'


class GuardFailure(ValueError):
    """A workflow `if:` gate no longer means what the notify/retry needs."""


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


def extract_if(block: str, where: str) -> str:
    """Return the `if:` expression as ONE line of text.

    Handles both `if: ${{ ... }}` inline values and folded (`>-`) block
    scalars. Unparseable text fails closed: a gate this guard cannot read is
    a gate nobody is watching.
    """
    lines = block.splitlines()
    for index, line in enumerate(lines):
        stripped = line.strip()
        if not stripped.startswith("if:"):
            continue
        indent = len(line) - len(line.lstrip())
        inline = stripped[len("if:"):].strip()
        if inline not in (">-", "|-", ">", "|"):
            return inline
        gathered: list[str] = []
        for later in lines[index + 1 :]:
            if not later.strip():
                break
            if len(later) - len(later.lstrip()) > indent:
                gathered.append(later.strip())
            else:
                break
        if not gathered:
            raise GuardFailure(f"{where}: folded if: scalar has no continuation lines")
        return " ".join(gathered)
    raise GuardFailure(f"{where}: no if: found")


def require(haystack: str, needle: str, where: str) -> None:
    if needle not in haystack:
        raise GuardFailure(f"{where} must contain {needle!r}")


def require_once(haystack: str, needle: str, where: str) -> None:
    count = haystack.count(needle)
    if count != 1:
        raise GuardFailure(f"{where} must contain {needle!r} exactly once, found {count}")


def validate_notify_yml(text: str) -> None:
    # The notify workflow must still LISTEN for the Release Emulator
    # Validation workflow at all; otherwise the job below is unreachable
    # however correct its `if:`.
    require(text, REV_ON_WORKFLOWS, "full-suite-notify.yml on.workflow_run.workflows")

    # The #2675 single-red REV pager: must select the REV lane by workflow
    # NAME, gate on the REV run's REAL event value ('workflow_run'), and fire
    # only on failure.
    job = extract_job(text, "notify-release-emulator-red")
    expr = extract_if(job, "notify-release-emulator-red job")
    require(
        expr,
        "github.event.workflow_run.name == 'Release Emulator Validation'",
        "notify-release-emulator-red if: must select the REV lane by workflow NAME",
    )
    require(
        expr,
        "github.event.workflow_run.event == 'workflow_run'",
        "notify-release-emulator-red if: must gate on the REV run's own event "
        "value 'workflow_run' (REV has no schedule trigger; its nightly-chain "
        "runs carry .event == 'workflow_run', manual ones 'workflow_dispatch')",
    )
    require(
        expr,
        "github.event.workflow_run.conclusion == 'failure'",
        "notify-release-emulator-red if: must fire only on a RED run",
    )
    if "event == 'schedule'" in expr:
        raise GuardFailure(
            "notify-release-emulator-red if: gates on event == 'schedule', but a "
            "Release Emulator Validation run's .event is never 'schedule' — that "
            "is the never-firing notify issue #2675 exists to kill"
        )

    # The Tests lane above keeps 'schedule' — THERE the workflow_run context
    # IS a Tests run and Tests genuinely has a schedule trigger. Pin the
    # asymmetry so a copy-paste unification in either direction reddens.
    tests_job = extract_job(text, "notify")
    tests_expr = extract_if(tests_job, "notify (Tests) job")
    require(
        tests_expr,
        "github.event.workflow_run.name == 'Tests'",
        "notify (Tests) if: must select the Tests lane by workflow NAME",
    )
    require(
        tests_expr,
        "github.event.workflow_run.event == 'schedule'",
        "notify (Tests) if: must keep event == 'schedule' (Tests runs DO carry "
        "a schedule trigger; only the REV branch needed the different value)",
    )


def validate_release_yml(text: str) -> None:
    # The boot preflight must still exist (by its boot-named step and id):
    # delete it and the "attempt 1" half of the retry story disappears.
    boot = extract_step(text, BOOT_STEP_NAME)
    require_once(boot, "id: boot_check", "boot-check preflight step")

    # The validation step must run UNLESS the run was cancelled: a failed
    # boot preflight flows INTO it as the bounded boot retry (attempt 2).
    # Any gate referencing the preflight outcome skips exactly the run where
    # attempt 1 died — the shipped blocker-2 defect.
    step = extract_step(text, VALIDATION_STEP_NAME)
    expr = extract_if(step, "release validation step")
    if "boot_check.outcome" in expr:
        raise GuardFailure(
            "release validation step if: references steps.boot_check.outcome — "
            "a failed boot preflight would skip the step instead of flowing in "
            "as the bounded boot retry (attempt 2), issue #2675 blocker 2"
        )
    require(
        expr,
        "!cancelled()",
        "release validation step if: must keep !cancelled() (a default gate "
        "also skips the step once the failed preflight failed the job)",
    )
    require_once(
        step,
        "script: scripts/release-emulator-validation.sh",
        "release validation step must invoke the real validation chain",
    )


def validate_tree(root: Path = ROOT) -> None:
    validate_notify_yml((root / ".github/workflows/full-suite-notify.yml").read_text())
    validate_release_yml(
        (root / ".github/workflows/release-emulator-validation.yml").read_text()
    )


def self_test() -> None:
    # Reproduce-first: the committed tree is checked live first. On the
    # pre-fix tree this is RED (both shipped gates); after this fix it is
    # GREEN, and the mutations below prove each requirement is load-bearing.
    checks = 0
    try:
        validate_tree()
        live_green = True
    except GuardFailure as exc:
        live_green = False
        live_error = str(exc)

    notify = NOTIFY_YML.read_text()
    release = RELEASE_YML.read_text()

    def write_tree(tmp: Path, **files: str) -> None:
        mapping = {
            ".github/workflows/full-suite-notify.yml": notify,
            ".github/workflows/release-emulator-validation.yml": release,
        }
        mapping.update(files)
        for rel, content in mapping.items():
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

    def expect_red(label: str, **files: str) -> None:
        nonlocal checks
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            write_tree(tmp, **files)
            try:
                validate_tree(tmp)
            except GuardFailure:
                checks += 1
            else:
                raise GuardFailure(f"self-test accepted an unsafe mutation: {label}")

    def expect_green(label: str, **files: str) -> None:
        """A change that must NOT redden the guard.

        The assertions read `if:` expressions, not prose — so rewording the
        comments around them must stay green. (A guard that reddens on
        comment edits gets edited around until it lies again.)
        """
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            write_tree(tmp, **files)
            try:
                validate_tree(tmp)
            except GuardFailure as exc:
                raise GuardFailure(
                    f"self-test rejected a harmless change: {label} ({exc})"
                ) from exc

    # The SHIPPED blocker-1 defect, regressed: the REV pager gated on
    # 'schedule', an event value a REV run can never carry.
    expect_red(
        "REV pager gate regresses to the never-firing event == 'schedule'",
        **{
            ".github/workflows/full-suite-notify.yml": notify.replace(
                "github.event.workflow_run.event == 'workflow_run'",
                "github.event.workflow_run.event == 'schedule'",
            )
        },
    )
    expect_red(
        "REV pager drops the workflow-NAME pin (lanes could cross-file)",
        **{
            ".github/workflows/full-suite-notify.yml": notify.replace(
                "github.event.workflow_run.name == 'Release Emulator Validation' &&\n"
                "      ",
                "",
            )
        },
    )
    expect_red(
        "REV pager fires on green runs too (no conclusion == 'failure')",
        **{
            ".github/workflows/full-suite-notify.yml": notify.replace(
                "github.event.workflow_run.conclusion == 'failure'",
                "github.event.workflow_run.conclusion == 'success'",
            )
        },
    )
    expect_red(
        "notify workflow stops listening for Release Emulator Validation",
        **{
            ".github/workflows/full-suite-notify.yml": notify.replace(
                REV_ON_WORKFLOWS,
                'workflows: ["Tests"]',
            )
        },
    )
    expect_red(
        "Tests notify lane loses its 'schedule' gate (copy-paste unification)",
        **{
            ".github/workflows/full-suite-notify.yml": notify.replace(
                "github.event.workflow_run.name == 'Tests' &&\n"
                "      github.event.workflow_run.event == 'schedule'",
                "github.event.workflow_run.name == 'Tests' &&\n"
                "      github.event.workflow_run.event == 'workflow_run'",
            )
        },
    )
    # The SHIPPED blocker-2 defect, regressed: a failed preflight skips the
    # suite attempt instead of retrying the boot.
    expect_red(
        "validation step skips itself when the boot preflight failed",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "if: ${{ !cancelled() }}",
                "if: ${{ !cancelled() && steps.boot_check.outcome == 'success' }}",
            )
        },
    )
    expect_red(
        "validation step loses !cancelled() (default gate also skips on the "
        "failed job)",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "        if: ${{ !cancelled() }}\n",
                "",
            )
        },
    )
    expect_red(
        "boot-check preflight step loses its boot_check id",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "id: boot_check",
                "id: boot_probe",
            )
        },
    )
    expect_red(
        "validation step stops invoking the real validation chain",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "script: scripts/release-emulator-validation.sh",
                "script: scripts/true",
            )
        },
    )
    expect_green(
        "rewording the boot-retry COMMENT (assertions read if: text, not prose)",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "as the bounded boot retry (attempt 2), and the ledger's",
                "as boot attempt two, and the ledger's",
            )
        },
    )

    expected = 9
    if checks != expected:
        raise GuardFailure(f"self-test ran {checks} red mutations, expected {expected}")

    if not live_green:
        raise GuardFailure(
            "live tree is RED (this is the reproduce-first proof on the "
            f"pre-fix gates, and a failure after wiring): {live_error}"
        )
    print(
        f"PASS: release red-notify / boot-retry `if:` wiring self-test "
        f"({checks} red mutations + live tree green)"
    )


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if argv:
        print("usage: check-release-red-notify-wiring.py [--self-test]", file=sys.stderr)
        return 2
    try:
        validate_tree()
    except GuardFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "PASS: full-suite-notify REV pager and release boot-retry `if:` gates "
        "mean what their comments say"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
