#!/usr/bin/python3 -I
"""Fail closed when CI does not wire the execution ledger to real results.

Issue #2082: `check-test-execution-ledger.sh` existed and was self-tested, but
no workflow invoked real `--record` / `--verify`, persisted a rolling ledger,
or distinguished selected vs executed vs asserted. A guard that is never
called is decoration.

This script reads the committed workflow YAML, the unit/nightly wrappers,
and the ledger script, and requires the load-bearing invocations. The
self-test mutates copies of those files and demands a RED for each
mutation — including "the wrapper is an unread exit 0", "the selected set
is unscoped unit", and "nightly-phase1 is no longer app/src/androidTest".
A green that would still pass if record/verify never compared the real
selector to the real artifact is not a pass.

Usage:
  scripts/check-test-execution-ledger-wiring.py              # check the real tree
  scripts/check-test-execution-ledger-wiring.py --self-test  # red->green mutations
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
TESTS_YML = ROOT / ".github" / "workflows" / "tests.yml"
JOURNEY_YML = ROOT / ".github" / "workflows" / "app2.yml"
RELEASE_YML = ROOT / ".github" / "workflows" / "release-emulator-validation.yml"
SELECTION_GUARDS = ROOT / "scripts" / "ci-test-selection-guards.sh"
RECORD_WRAPPER = "scripts/ci-record-test-execution-ledger.sh"
LEDGER_SCRIPT_REL = "scripts/check-test-execution-ledger.sh"
LEDGER_SCRIPT = "scripts/check-test-execution-ledger.sh"
LEDGER_PATH = "build/test-execution-ledger.tsv"
PIN_COLD = "com.pocketshell.next.connect.J01ConnectAndTrustJourney"
PIN_WORKFLOW = "com.pocketshell.next.terminal.J03AttachAndTypeJourney"
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
CACHE_KEY_PREFIX = "test-execution-ledger-"
# Issue #2785. The journey lane restores and saves through the SPLIT actions,
# never the monolithic `actions/cache`: that one hard-codes `post-if: "success()"`
# in its own action.yml, so a red journey job never persists the rows it did
# reach, and its `save-always` input is documented in that same file as not
# working ("save-always does not work as intended and will be removed in a
# future release. A separate `actions/cache/restore` step should be used
# instead."). `actions/cache/save` needs no such input — it is an ordinary main
# step, so `if: always()` on the step is the mechanism.
RESTORE_ACTION = "uses: actions/cache/restore@v5"
SAVE_ACTION = "uses: actions/cache/save@v5"
RESTORE_STEP_TITLE = "Restore test-execution ledger"
RECORD_STEP_TITLE = "Record journey execution"
SAVE_STEP_TITLE = "Save test-execution ledger"
UPLOAD_STEP_TITLE = "Upload app2 journey reports"
# Item 3: without the run id, two runs of the same sha at attempt 1 (a push run
# and a later workflow_dispatch on that commit) compute an identical key; the
# second restore exact-hits the first entry and the second save is rejected as a
# duplicate — swallowed into a warning by saveImpl's catch-all, so the run reads
# green while its rows are discarded.
RUN_SCOPE = "${{ github.run_id }}"
STEP_KEY_RE = re.compile(r"^[ \t]*key:[ \t]*(\S.*?)[ \t]*$", re.M)


class GuardFailure(ValueError):
    """The workflow no longer wires record/verify to real JUnit results."""


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


# A step ends at the next thing written at STEP indentation — the next `- name:`
# OR the comment block that introduces it. Bounding on `- name:` alone is not
# enough and is not a hypothetical: this guard's own #2785 change added a
# comment above the save step explaining why it carries `if: always()`, and that
# comment sits inside a `- name:`-bounded slice of the step BEFORE it. The
# record step's slice therefore contained the literal `if: always()` from its
# neighbour's prose, and the mutation that strips always() off the record step
# went green. Same family as the whole-job slice the comment below describes,
# one step smaller.
STEP_BOUNDARY_RE = re.compile(r"^      (?:#|- )", re.M)


def step_slice(job: str, title: str, where: str = "app2-journey") -> str:
    """The text of ONE named step, bounded at the next step's first line.

    Running a slice to the end of the job is how an earlier version of this
    guard went vacuous: every later `if: always()` step vouched for the ledger
    step, so deleting always() from the ledger step alone stayed green.

    `where` names the lane in the failure message. Issue #2787 made this shared
    across three lanes, and a hard-coded "app2-journey" would have pointed a
    reader at the wrong workflow for two of them.
    """
    at = job.find(title)
    if at < 0:
        raise GuardFailure(f"{where} has no {title!r} step")
    match = STEP_BOUNDARY_RE.search(job, at)
    return job[at : match.start() if match else len(job)]


def step_key(step: str, where: str) -> str:
    match = STEP_KEY_RE.search(step)
    if not match:
        raise GuardFailure(f"{where} has no `key:` line")
    return match.group(1)


# Issue #2787: the same key-collision property, asserted identically on all
# three lanes. Every lane's ledger job ALSO carries a Gradle `actions/cache@v5`,
# so this takes a restore STEP slice, never a whole job — the #2744 comment
# below records what an unscoped `job.find("uses: actions/cache@v5")` did to the
# first draft of the journey lane's ordering check.
def require_run_scoped_key(restore_step: str, where: str) -> str:
    key = step_key(restore_step, where)
    if RUN_SCOPE not in key:
        raise GuardFailure(
            f"the {where} cache key must be scoped by {RUN_SCOPE} (got {key!r}); "
            "sha+run_attempt alone is byte-identical across two runs of the same "
            "commit at attempt 1 (a push/workflow_run run and a later "
            "workflow_dispatch), so the second run's restore exact-hits the "
            "first entry, its own save is dropped, and the rows it recorded are "
            "silently discarded behind a green lane"
        )
    return key


def require_once(haystack: str, needle: str, where: str) -> None:
    count = haystack.count(needle)
    if count != 1:
        raise GuardFailure(f"{where} must contain {needle!r} exactly once, found {count}")


def validate_tests_yml(text: str) -> None:
    job = extract_job(text, "unit")
    require(job, "Restore test-execution ledger", "tests.yml unit job")
    # Scope every cache assertion to the ledger restore STEP. The unit job also
    # carries a Gradle `actions/cache@v5` with its own `key:`, so a whole-job
    # search answers "does this job cache ANYTHING" — which is how the journey
    # lane's first ordering check went vacuously green (#2744), and what would
    # let #2787's run-id assertion be satisfied by the Gradle key instead.
    restore_step = step_slice(job, RESTORE_STEP_TITLE, "tests.yml unit job")
    require(restore_step, "uses: actions/cache@v5", "tests.yml unit ledger cache step")
    require(restore_step, f"path: {LEDGER_PATH}", "tests.yml unit ledger cache path")
    require(restore_step, f"key: {CACHE_KEY_PREFIX}", "tests.yml unit ledger cache key")
    require(restore_step, "restore-keys:", "tests.yml unit ledger restore-keys")
    # Issue #2787: same collision the journey lane carried before #2785.
    require_run_scoped_key(restore_step, "tests.yml unit ledger restore step")
    require(job, RECORD_WRAPPER, "tests.yml unit job")
    require(
        job,
        '--variant "${{ matrix.variant }}"',
        "tests.yml unit ledger step must pass --variant so attendance matches the shard",
    )
    require(
        job,
        "if: always() && steps.unit_tests.conclusion != 'skipped'",
        "tests.yml unit ledger step must run even when Gradle is red",
    )
    # Record must happen after the test run, not instead of the count guard.
    record_at = job.find(RECORD_WRAPPER)
    tests_at = job.find("Run JVM unit tests")
    if tests_at < 0 or record_at < tests_at:
        raise GuardFailure("tests.yml must record the ledger AFTER the JVM unit tests run")


def validate_journey_ledger(job: str) -> None:
    """The CONNECTED lane records into the rolling ledger.

    This used to validate a dedicated shard-merging wrapper
    (scripts/ci-nightly-execution-ledger.sh) for the six-shard nightly. app2's
    lane is ONE unfiltered run (issue #2474), so the wrapper collapsed into two
    inline calls in the journey job and `--merge-attendance` no longer applies —
    there are no shards to merge. What still has to hold is that the lane
    RECORDS and is held to the wholesale selected set, with the load-bearing
    journeys pinned by name.

    Issue #2744: recording is only half of it. The rolling ledger lives in the
    shared `test-execution-ledger-<os>-` Actions cache chain, so a lane that
    `--record`s WITHOUT restoring/saving that cache writes its rows into a file
    the runner deletes at job end. app2-journey shipped in exactly that state:
    the recorder was present and green, and not one journey class was ever
    credited. No other lane can substitute — the release gate runs this same
    suite through raw `adb shell am instrument`, which emits no JUnit XML for
    app2 at all. Demand the same restore/save pair the unit and release lanes
    already carry.
    """
    require(job, LEDGER_SCRIPT_REL, "journey job must invoke the execution ledger")
    # The flag AND its argument: a bare "--record" substring is satisfied by
    # "--record-not", so the mutation that removes recording would still pass.
    require(job, "--record app2/", "journey job must --record its connected JUnit XML")
    require(job, "--attendance", "journey job must run current-run attendance")
    require(job, "--selected-from app2-journey", "journey attendance must use the wholesale selected set")
    require(job, "--require-class", "journey attendance must --require-class the pins")
    require(job, PIN_COLD, "journey attendance must pin the connect/trust journey")
    require(job, PIN_WORKFLOW, "journey attendance must pin the attach/type journey")


def validate_journey_yml(text: str) -> None:
    """app2.yml's journey job is the CONNECTED lane's ledger recorder.

    The nightly equivalent needed a separate `execution-ledger` aggregator job,
    because six parallel shards cannot each save the rolling cache without
    clobbering it. app2's lane is a single unfiltered run (issue #2474), so
    there is nothing to aggregate and the recording lives in the journey job
    itself — which is why this no longer looks for an aggregator, a
    `--aggregate` flag or `download-artifact`.
    """
    job = extract_job(text, "app2-journey")
    if re.search(r"^    continue-on-error:\s*true\s*$", job, re.M):
        raise GuardFailure(
            "app2-journey must not be continue-on-error — a truncated run would "
            "otherwise look like a passing attendance verdict"
        )
    validate_journey_ledger(job)
    # The recording step must survive a RED suite, or the lane only ever records
    # its own good news.
    # Bound the slice to THIS step. Running it to the end of the job means every
    # later `if: always()` (docker logs, artifact upload) vouches for the ledger
    # step, so deleting always() from the ledger step alone stayed green.
    step_at = job.find("Record journey execution")
    if step_at < 0:
        raise GuardFailure("app2-journey has no 'Record journey execution' step")
    # Issue #2744: recording is only half of it — the rolling ledger lives in
    # the shared `test-execution-ledger-<os>-` Actions cache chain, so a lane
    # that records WITHOUT restoring/saving that cache writes its rows into a
    # file the runner deletes at job end. Scope every assertion to the restore
    # STEP: the job also carries a Gradle `actions/cache@v5`, and an unscoped
    # `job.find("uses: actions/cache@v5")` silently resolves to that one —
    # which made the first draft of this ordering check vacuously green.
    restore_at = job.find("Restore test-execution ledger")
    if restore_at < 0:
        raise GuardFailure(
            "app2-journey must persist the rolling ledger: it has no 'Restore "
            "test-execution ledger' cache step, so every --record below writes "
            "rows into a file the runner throws away (#2744)"
        )
    restore_step = step_slice(job, RESTORE_STEP_TITLE)
    # Issue #2785: the restore-only action, never the monolithic `actions/cache`.
    # The monolithic one's save is its POST step, pinned `post-if: "success()"`
    # in the action's own action.yml, so the rows a RED journey job did reach
    # are thrown away — the runs whose ledger is worth the most are exactly the
    # ones that never persist. Its `save-always` input is not an escape hatch:
    # the same action.yml marks it deprecated and says to split the steps.
    require(restore_step, RESTORE_ACTION, "journey ledger restore step")
    require(restore_step, f"path: {LEDGER_PATH}", "journey ledger cache path")
    require(restore_step, f"key: {CACHE_KEY_PREFIX}", "journey ledger cache key")
    require(restore_step, "restore-keys:", "journey ledger restore-keys")
    # The cache must be restored BEFORE the record merges into it. A cache step
    # sitting after the recorder satisfies every requirement above while saving
    # a ledger that dropped each pre-existing row — a persistence fix that
    # erases history instead.
    if restore_at > step_at:
        raise GuardFailure(
            "app2-journey must restore the ledger cache BEFORE the record step, "
            "or the saved ledger loses every class recorded by an earlier lane"
        )
    ledger_step = step_slice(job, RECORD_STEP_TITLE)
    if "if: always()" not in ledger_step:
        raise GuardFailure(
            "the journey ledger step must run with if: always() — a lane that "
            "records only on success cannot show which classes a red run reached"
        )

    # Issue #2785 (1/3): the save half of the split.
    save_at = job.find(SAVE_STEP_TITLE)
    if save_at < 0:
        raise GuardFailure(
            "app2-journey restores the ledger with the restore-only action but "
            f"has no {SAVE_STEP_TITLE!r} step — nothing writes the merged ledger "
            "back to the shared cache chain, so every --record is discarded"
        )
    save_step = step_slice(job, SAVE_STEP_TITLE)
    require(save_step, SAVE_ACTION, "journey ledger save step")
    require(save_step, f"path: {LEDGER_PATH}", "journey ledger save path")
    # THE point of the split. `actions/cache/save` is an ordinary main step, so
    # a plain always() actually works here where the monolithic action's
    # `post-if: "success()"` could not be overridden at all.
    if "if: always()" not in save_step:
        raise GuardFailure(
            "the journey ledger SAVE step must run with if: always() — the only "
            "reason to split restore from save is that a RED journey job still "
            "persists the classes it reached; an if: success() save is exactly "
            "the monolithic post-if the split exists to escape"
        )
    # Saving after the record, or the entry holds pre-record content: a lane
    # that looks fully wired and credits nothing.
    if save_at < step_at:
        raise GuardFailure(
            "app2-journey must SAVE the ledger cache AFTER the record step, or "
            "the saved entry is the restored file with this run's rows missing"
        )
    # A save under a drifted key lands outside the prefix chain the next lane's
    # restore-keys reads: green step, orphaned entry.
    restore_key = step_key(restore_step, "journey ledger restore step")
    save_key = step_key(save_step, "journey ledger save step")
    if save_key != restore_key:
        raise GuardFailure(
            "the journey ledger save key must be byte-identical to the restore "
            f"step's primary key (restore={restore_key!r} save={save_key!r}); a "
            "drifted key saves outside the chain the next restore reads"
        )
    # Issue #2785 (3/3): key scoping. Without the run id, a push run and a
    # workflow_dispatch run on the SAME sha both compute attempt 1, the second
    # restore exact-hits the first entry, and the second save is rejected as a
    # duplicate key — which `actions/cache/save` swallows into a warning, so the
    # run reads green while its rows are silently dropped.
    # Issue #2787 folded this into the shared helper the unit and release lanes
    # now use, so the three lanes cannot drift into asserting three different
    # things about the same property. The lane name in `where` keeps each one's
    # failure message distinct.
    require_run_scoped_key(restore_step, "journey ledger restore step")

    # Issue #2785 (2/3): the recorded ledger must leave the runner as an
    # artifact, the way tests.yml's unit lane folds it into its reports upload.
    # Without it the only copy is a cache entry, and answering "what did the
    # record step write?" means downloading the cache and replaying the recorder
    # by hand — which is what the #2744 on-call had to do.
    upload_step = step_slice(job, UPLOAD_STEP_TITLE)
    require(
        upload_step,
        LEDGER_PATH,
        "app2-journey reports upload must include the ledger file (#2785)",
    )


def validate_release_yml(text: str) -> None:
    job = extract_job(text, "emulator-release-validation")
    require(job, "Restore test-execution ledger", "release job")
    # Step-scoped for the same reason as the unit lane above: this job carries a
    # Gradle `actions/cache@v5` too, so the whole-job form these three lines
    # used to take could not tell the two apart.
    restore_step = step_slice(job, RESTORE_STEP_TITLE, "release job")
    require(restore_step, "uses: actions/cache@v5", "release ledger cache")
    require(restore_step, f"path: {LEDGER_PATH}", "release ledger cache path")
    require(restore_step, f"key: {CACHE_KEY_PREFIX}", "release ledger cache key")
    require(restore_step, "restore-keys:", "release ledger restore-keys")
    # Issue #2787: same collision the journey lane carried before #2785.
    require_run_scoped_key(restore_step, "release ledger restore step")
    require(job, "check-test-execution-ledger.sh --record", "release must --record real JUnit results")
    require(job, "check-test-execution-ledger.sh --verify", "release must --verify the rolling ledger")
    record_at = job.find("check-test-execution-ledger.sh --record")
    run_at = job.find("Run emulator-only release validation")
    if run_at < 0 or record_at < run_at:
        raise GuardFailure("release must record the ledger AFTER the emulator validation run")


def validate_unit_wrapper(text: str) -> None:
    """The unit wrapper body is load-bearing. A YAML string pointing at an
    unread `exit 0` script is the G6 hole: CI looks wired while --record /
    --attendance / --verify never run.
    """
    require(text, 'bash "$GUARD" --record', "unit ledger wrapper must --record this run's JUnit XML")
    require(text, 'bash "$GUARD" --attendance', "unit ledger wrapper must run current-run attendance")
    require(text, 'bash "$GUARD" --verify', "unit ledger wrapper must --verify the rolling ledger")
    require(text, "--selected-from", "unit ledger wrapper must pass --selected-from")
    require(text, "--source-set", "unit ledger wrapper must pass --source-set")
    require(text, 'SELECTED_FROM="unit-debug"', "unit ledger wrapper must select unit-debug on Debug")
    require(text, 'SELECTED_FROM="unit-release"', "unit ledger wrapper must select unit-release on Release")
    require(text, "--variant", "unit ledger wrapper must take --variant Debug|Release")
    # Unscoped `--selected-from unit` includes testRelease on a Debug job.
    if re.search(r"--selected-from[ \t]+unit([ \t\n\"'\\]|$)", text):
        raise GuardFailure(
            "unit ledger wrapper must not pass unscoped --selected-from unit "
            "(Debug cannot emit src/testRelease; use unit-debug / unit-release)"
        )


def validate_ledger_script(text: str) -> None:
    require(text, "unit-debug", "ledger must know the Debug unit selected set")
    require(text, "unit-release", "ledger must know the Release unit selected set")

    # THE JOURNEY LANE IS ONE MODULE'S androidTest SET, AND THAT IS CHECKED AS
    # CODE, NOT AS A PATH LITERAL.
    #
    # This used to `require(text, "app/src/androidTest", ...)`. That is a
    # substring search over the whole file, so a COMMENT mentioning the path
    # satisfied it — and after the rewrite repointed the lane at app2 that is
    # exactly what happened: the guard stayed green because a block comment
    # still contained the words, while the code it was meant to pin had moved.
    # A prose-satisfiable assertion is the G6 shape (docs/ci-pitfalls.md), so
    # both halves of the real property are asserted against code lines instead:
    # the lane derives its root from the suite that runs it, and it refuses to
    # do so if that suite ever grows a class filter.
    code = "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )
    require(
        code,
        "journey_lane_android_test_dir",
        "journey-lane selected set must derive its androidTest root from the suite that runs it",
    )
    require(
        code,
        "src/androidTest",
        "journey-lane selected set must be restricted to an androidTest root",
    )
    require(
        code,
        "testInstrumentationRunnerArguments",
        "journey-lane derivation must reject a suite that filters which classes run",
    )


def validate_selection_guards(text: str) -> None:
    require(
        text,
        "check-test-execution-ledger-wiring.py --self-test",
        "ci-test-selection-guards.sh",
    )
    if text.count("check-test-execution-ledger-wiring.py") < 2:
        raise GuardFailure(
            "ci-test-selection-guards.sh must invoke the wiring check itself "
            "AND --self-test (a self-test nobody runs is decoration)"
        )


def validate_tree(root: Path = ROOT) -> None:
    validate_tests_yml((root / ".github/workflows/tests.yml").read_text())
    validate_journey_yml((root / ".github/workflows/app2.yml").read_text())
    validate_release_yml((root / ".github/workflows/release-emulator-validation.yml").read_text())
    validate_unit_wrapper((root / "scripts/ci-record-test-execution-ledger.sh").read_text())
    validate_ledger_script((root / "scripts/check-test-execution-ledger.sh").read_text())
    validate_selection_guards((root / "scripts/ci-test-selection-guards.sh").read_text())


def self_test() -> None:
    # Reproduce-first: the committed tree is checked live first. On current
    # main this is RED (no workflow wiring). After this issue's wiring it is
    # GREEN, and the mutations below prove each requirement is load-bearing.
    checks = 0
    try:
        validate_tree()
        live_green = True
    except GuardFailure as exc:
        live_green = False
        live_error = str(exc)

    tests = TESTS_YML.read_text()
    journey = JOURNEY_YML.read_text()
    release = RELEASE_YML.read_text()
    guards = SELECTION_GUARDS.read_text()
    unit_wrapper = (ROOT / RECORD_WRAPPER).read_text()
    ledger_script = (ROOT / LEDGER_SCRIPT).read_text()

    def write_tree(tmp: Path, **files: str) -> None:
        mapping = {
            ".github/workflows/tests.yml": tests,
            ".github/workflows/app2.yml": journey,
            ".github/workflows/release-emulator-validation.yml": release,
            "scripts/ci-test-selection-guards.sh": guards,
            RECORD_WRAPPER: unit_wrapper,
            LEDGER_SCRIPT: ledger_script,
        }
        mapping.update(files)
        for rel, content in mapping.items():
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

    def expect_red(label: str, expect: str | None = None, **files: str) -> None:
        """A mutation that must redden — optionally for a NAMED reason.

        Issue #2787: "it went red" is a weaker claim than it looks once three
        lanes assert the same property. A mutation to the release lane's key
        that reddened on the unit lane's assertion instead would pass this
        function silently while proving nothing about the lane it names. Pass
        `expect` with a distinctive fragment of the intended failure message and
        the mutation is pinned to the assertion it exists to exercise.
        """
        nonlocal checks
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            write_tree(tmp, **files)
            try:
                validate_tree(tmp)
            except GuardFailure as exc:
                if expect is not None and expect not in str(exc):
                    raise GuardFailure(
                        f"self-test mutation {label!r} reddened for the WRONG "
                        f"reason: expected a failure mentioning {expect!r}, got "
                        f"{str(exc)!r}"
                    ) from exc
                checks += 1
            else:
                raise GuardFailure(f"self-test accepted an unsafe mutation: {label}")

    def expect_green(label: str, **files: str) -> None:
        """A change that must NOT redden the guard.

        Every assertion here is a substring search, and a substring search over a
        whole file happily matches PROSE. That is not hypothetical: the
        `app/src/androidTest` requirement went on passing after the lane moved to
        app2 purely because a block comment still contained the words. So the
        comment-only edit below is now a first-class case — the guard must be
        indifferent to it, which is only true if the assertion reads code.
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

    # Mutations that must redden — each names the property it exists to prove.
    expect_red(
        "unit job without --record wrapper",
        **{".github/workflows/tests.yml": tests.replace(RECORD_WRAPPER, "scripts/true")},
    )
    expect_red(
        "unit job without ledger cache path",
        **{".github/workflows/tests.yml": tests.replace(f"path: {LEDGER_PATH}", "path: /tmp/not-the-ledger")},
    )
    expect_red(
        "journey job stops recording into the ledger",
        **{
            ".github/workflows/app2.yml": journey.replace(
                "--record app2/build/outputs", "--record-not app2/build/outputs"
            )
        },
    )
    # The literal blocks the #2744/#2785 mutations below rewrite. Building them
    # from the real file (rather than retyping them) is deliberate: if the YAML
    # drifts, `journey.replace(...)` becomes a no-op, the mutated tree stays
    # green, and expect_red raises "self-test accepted an unsafe mutation" —
    # drift fails loudly instead of quietly disarming a mutation.
    journey_job = extract_job(journey, "app2-journey")
    restore_block = step_slice(journey_job, RESTORE_STEP_TITLE)
    save_block = step_slice(journey_job, SAVE_STEP_TITLE)
    upload_block = step_slice(journey_job, UPLOAD_STEP_TITLE)
    for label, block in (
        ("restore", restore_block),
        ("save", save_block),
        ("upload", upload_block),
    ):
        if journey.count(block) != 1:
            raise GuardFailure(
                f"self-test cannot anchor the journey {label} step uniquely "
                f"(found {journey.count(block)} occurrences)"
            )

    # Issue #2744: THE regression mutation. This is the exact tree shape that
    # shipped — recorder present, cache absent — and the guard was green on it.
    expect_red(
        "journey job records but never persists the rolling ledger cache (#2744)",
        **{".github/workflows/app2.yml": journey.replace(restore_block, "")},
    )
    # The save must merge into restored history, not replace it. Move the whole
    # restore step to sit AFTER the record step, leaving both steps otherwise
    # byte-identical, so the ordering check is the only thing that can redden.
    expect_red(
        "journey ledger cache restored AFTER the record step (#2744)",
        **{
            ".github/workflows/app2.yml": journey.replace(restore_block, "").replace(
                save_block, restore_block + save_block, 1
            )
        },
    )
    # Issue #2785, item 2: the monolithic action cannot save a red run at all —
    # `post-if: "success()"` is in its action.yml, and `save-always` is marked
    # deprecated-and-non-functional in that same file.
    expect_red(
        "journey ledger reverts to the monolithic actions/cache (#2785)",
        **{
            ".github/workflows/app2.yml": journey.replace(
                RESTORE_ACTION, "uses: actions/cache@v5", 1
            )
        },
    )
    expect_red(
        "journey ledger has a restore but no save step (#2785)",
        **{".github/workflows/app2.yml": journey.replace(save_block, "")},
    )
    # The load-bearing one: an if: success() save is precisely the monolithic
    # post-if the split exists to escape, so the split would buy nothing.
    expect_red(
        "journey ledger save runs only on success — a red run persists nothing (#2785)",
        **{
            ".github/workflows/app2.yml": journey.replace(
                save_block,
                save_block.replace(
                    "if: always() && steps.journey.conclusion != 'skipped'",
                    "if: success()",
                ),
                1,
            )
        },
    )
    expect_red(
        "journey ledger saves BEFORE the record step (#2785)",
        **{
            ".github/workflows/app2.yml": journey.replace(save_block, "").replace(
                restore_block, restore_block + save_block, 1
            )
        },
    )
    # A drifted save key is a green step writing an orphaned entry: the next
    # lane's restore-keys prefix never reads it back.
    expect_red(
        "journey ledger save key drifts from the restore key (#2785)",
        **{
            ".github/workflows/app2.yml": journey.replace(
                save_block,
                save_block.replace(
                    f"key: {CACHE_KEY_PREFIX}", "key: some-other-cache-chain-"
                ),
                1,
            )
        },
    )
    # Issue #2785, item 3: sha+run_attempt alone collides across two runs of the
    # same commit, and the losing save is swallowed into a warning. Both keys at
    # once, deliberately: dropping the run id from one of them would redden on
    # the save-key-drift check above instead, which is a different property.
    expect_red(
        "journey ledger cache key drops the run-id scope (#2785)",
        expect="journey ledger restore step cache key must be scoped by",
        **{".github/workflows/app2.yml": journey.replace(RUN_SCOPE + "-", "")},
    )

    # ---------------------------------------------------------------------
    # Issue #2787: the SAME key collision on the two lanes #2785 left alone as
    # scope discipline. Two mutations per lane. The first is the bug itself.
    # The second is the one that matters for this guard's own honesty: every
    # ledger job ALSO carries a Gradle `actions/cache@v5` with its own `key:`,
    # so it drops the run id from the LEDGER key while adding it to the GRADLE
    # key in the same job. A whole-job assertion — which is what both lanes
    # carried before this change — goes GREEN on that tree while the collision
    # it claims to prevent is fully present.
    #
    # The blocks are cut from the real files rather than retyped, for the same
    # reason #2785 did it: if the YAML drifts, `replace(...)` becomes a no-op,
    # the mutated tree stays green, and expect_red raises "accepted an unsafe
    # mutation" — drift fails loudly instead of quietly disarming a mutation.
    # Anchoring on the step also keeps the mutation off the release workflow's
    # unrelated `github.run_id` uses in its notify jobs, which a whole-file
    # replace would have rewritten too.
    tests_job = extract_job(tests, "unit")
    unit_restore_block = step_slice(tests_job, RESTORE_STEP_TITLE, "tests.yml unit job")
    release_job = extract_job(release, "emulator-release-validation")
    release_restore_block = step_slice(release_job, RESTORE_STEP_TITLE, "release job")
    gradle_key = "key: ${{ runner.os }}-gradle-"
    for label, lane_text, lane_job, lane_block in (
        ("tests.yml unit", tests, tests_job, unit_restore_block),
        ("release", release, release_job, release_restore_block),
    ):
        if lane_text.count(lane_block) != 1:
            raise GuardFailure(
                f"self-test cannot anchor the {label} ledger restore step "
                f"uniquely (found {lane_text.count(lane_block)} occurrences)"
            )
        if lane_text.count(lane_job) != 1:
            raise GuardFailure(
                f"self-test cannot anchor the {label} ledger job uniquely "
                f"(found {lane_text.count(lane_job)} occurrences)"
            )
        if lane_job.count(gradle_key) != 1:
            raise GuardFailure(
                f"self-test expected exactly one Gradle cache key inside the "
                f"{label} ledger job (found {lane_job.count(gradle_key)}); the "
                "wrong-step-satisfies-it mutation below has no second key to "
                "move the run id onto, so it would prove nothing"
            )

    expect_red(
        "tests.yml unit ledger cache key drops the run-id scope (#2787)",
        expect="tests.yml unit ledger restore step cache key must be scoped by",
        **{
            ".github/workflows/tests.yml": tests.replace(
                unit_restore_block,
                unit_restore_block.replace(RUN_SCOPE + "-", ""),
                1,
            )
        },
    )
    expect_red(
        "tests.yml unit ledger key loses the run-id scope while the unit job's "
        "GRADLE cache key gains it (#2787 vacuous-slice guard)",
        expect="tests.yml unit ledger restore step cache key must be scoped by",
        **{
            ".github/workflows/tests.yml": tests.replace(
                tests_job,
                tests_job.replace(
                    unit_restore_block,
                    unit_restore_block.replace(RUN_SCOPE + "-", ""),
                    1,
                ).replace(gradle_key, gradle_key + RUN_SCOPE + "-", 1),
                1,
            )
        },
    )
    expect_red(
        "release ledger cache key drops the run-id scope (#2787)",
        expect="release ledger restore step cache key must be scoped by",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                release_restore_block,
                release_restore_block.replace(RUN_SCOPE + "-", ""),
                1,
            )
        },
    )
    expect_red(
        "release ledger key loses the run-id scope while the release job's "
        "GRADLE cache key gains it (#2787 vacuous-slice guard)",
        expect="release ledger restore step cache key must be scoped by",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                release_job,
                release_job.replace(
                    release_restore_block,
                    release_restore_block.replace(RUN_SCOPE + "-", ""),
                    1,
                ).replace(gradle_key, gradle_key + RUN_SCOPE + "-", 1),
                1,
            )
        },
    )
    # ---------------------------------------------------------------------

    # Issue #2785, item 1: the recorded ledger must leave the runner.
    expect_red(
        "journey reports upload drops the ledger file (#2785)",
        **{
            ".github/workflows/app2.yml": journey.replace(
                upload_block,
                upload_block.replace(f"            {LEDGER_PATH}\n", ""),
                1,
            )
        },
    )
    expect_red(
        "journey attendance drops the wholesale selected set",
        **{
            ".github/workflows/app2.yml": journey.replace(
                "--selected-from app2-journey", "--selected-from unit"
            )
        },
    )
    expect_red(
        "journey ledger step only records on success (a red run shows nothing)",
        **{
            ".github/workflows/app2.yml": journey.replace(
                "      - name: Record journey execution into the rolling ledger (#2082)\n"
                "        if: always() && steps.journey.conclusion != 'skipped'",
                "      - name: Record journey execution into the rolling ledger (#2082)\n"
                "        if: steps.journey.conclusion == 'success'",
            )
        },
    )
    expect_red(
        "missing connect/trust journey pin",
        **{".github/workflows/app2.yml": journey.replace(PIN_COLD, "com.example.NotThePin")},
    )
    expect_red(
        "missing attach/type journey pin",
        **{".github/workflows/app2.yml": journey.replace(PIN_WORKFLOW, "com.example.NotThePin")},
    )
    expect_red(
        "release without --verify",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "check-test-execution-ledger.sh --verify",
                "true",
            )
        },
    )
    expect_red(
        "release without --record",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "check-test-execution-ledger.sh --record",
                "true --record-not",
            )
        },
    )
    expect_red(
        "selection-guards job no longer runs this wiring check",
        **{
            "scripts/ci-test-selection-guards.sh": guards.replace(
                "check-test-execution-ledger-wiring.py --self-test",
                "true",
            )
        },
    )
    expect_red(
        "unit wrapper unread / no-op (YAML still names it)",
        **{RECORD_WRAPPER: "#!/bin/bash\nexit 0\n"},
    )
    expect_red(
        "unit wrapper drops --record",
        **{RECORD_WRAPPER: unit_wrapper.replace('bash "$GUARD" --record', "true --record-not")},
    )
    expect_red(
        "unit wrapper drops --attendance",
        **{RECORD_WRAPPER: unit_wrapper.replace('bash "$GUARD" --attendance', "true --attendance-not")},
    )
    expect_red(
        "unit wrapper drops --verify",
        **{RECORD_WRAPPER: unit_wrapper.replace('bash "$GUARD" --verify', "true --verify-not")},
    )
    expect_red(
        "unit wrapper uses unscoped --selected-from unit",
        **{
            RECORD_WRAPPER: unit_wrapper.replace(
                '--selected-from "$SELECTED_FROM"',
                "--selected-from unit",
            )
        },
    )
    expect_red(
        "unit wrapper drops unit-debug selected set",
        **{RECORD_WRAPPER: unit_wrapper.replace('SELECTED_FROM="unit-debug"', 'SELECTED_FROM="unit"')},
    )
    # Three mutations, because validate_ledger_script now pins three separate
    # code properties instead of one greppable path literal. The middle one is
    # the regression that motivated the change: replacing the path ONLY inside a
    # comment must NOT be enough to redden the guard, and replacing it in code
    # must be — the old single assertion could not tell those apart.
    expect_red(
        "ledger script drops the journey-lane root derivation",
        **{LEDGER_SCRIPT: ledger_script.replace("journey_lane_android_test_dir", "some_other_helper")},
    )
    expect_red(
        "ledger script drops the androidTest root restriction from CODE",
        **{
            LEDGER_SCRIPT: "\n".join(
                line
                if line.lstrip().startswith("#")
                else line.replace("src/androidTest", "src/NOT_ANDROID_TEST")
                for line in ledger_script.splitlines()
            )
        },
    )
    expect_red(
        "ledger script stops rejecting a class-filtered journey suite",
        **{
            LEDGER_SCRIPT: ledger_script.replace(
                "testInstrumentationRunnerArguments", "someOtherRunnerArgument"
            )
        },
    )
    expect_green(
        "rewording a COMMENT that mentions an androidTest path",
        **{
            LEDGER_SCRIPT: "\n".join(
                line.replace("src/androidTest", "src/SOME_PROSE_PATH")
                if line.lstrip().startswith("#")
                else line
                for line in ledger_script.splitlines()
            )
        },
    )
    expect_red(
        "tests.yml unit step does not pass --variant",
        **{
            ".github/workflows/tests.yml": tests.replace(
                '--variant "${{ matrix.variant }}"',
                "--not-variant",
            )
        },
    )

    # 20 + the two #2744 ledger-persistence mutations (cache absent, cache
    # restored after the record step) + #2785's seven: monolithic-action revert,
    # save step absent, save on success only, save before record, save key
    # drift, run-id scope dropped, and the artifact upload dropping the ledger.
    # + #2787's four: the unit and release lanes each get the plain run-id-scope
    # drop AND the vacuous-slice arm that hands the run id to the same job's
    # Gradle cache key instead, which is the tree the pre-#2787 whole-job
    # assertions accepted.
    expected = 33
    if checks != expected:
        raise GuardFailure(f"self-test ran {checks} red mutations, expected {expected}")

    if not live_green:
        raise GuardFailure(
            "live tree is RED (this is the reproduce-first proof on an unwired "
            f"main, and a failure after wiring): {live_error}"
        )
    print(f"PASS: test-execution-ledger wiring guard self-test ({checks} red mutations + live tree green)")


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if argv:
        print("usage: check-test-execution-ledger-wiring.py [--self-test]", file=sys.stderr)
        return 2
    try:
        validate_tree()
    except GuardFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print("PASS: unit, journey, and release workflows wire --record/--verify/attendance to real JUnit results")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
