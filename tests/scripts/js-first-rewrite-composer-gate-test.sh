#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
RUNNER="$ROOT_DIR/scripts/connected-js-composer-docker.sh"
EXTRACTOR="$ROOT_DIR/scripts/extract-js-composer-artifacts.py"
TOOLCACHE_PRUNER="$ROOT_DIR/scripts/ci-emulator-prune-toolcache.sh"
PACKAGED_LANES="$ROOT_DIR/scripts/ci-js-first-packaged-lanes.sh"

[[ -f "$WORKFLOW" && -x "$RUNNER" && -f "$EXTRACTOR" && -x "$TOOLCACHE_PRUNER" && -x "$PACKAGED_LANES" ]] || {
  printf 'FAIL: rewrite composer gate inputs are missing\n' >&2
  exit 1
}

bash -n "$RUNNER"
python3 - "$WORKFLOW" "$RUNNER" "$EXTRACTOR" "$TOOLCACHE_PRUNER" "$PACKAGED_LANES" <<'PY'
import ast
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

workflow_path, runner_path, extractor_path, toolcache_pruner_path, packaged_lanes_path = map(Path, sys.argv[1:])
repository_root = workflow_path.parents[2]
workflow = workflow_path.read_text()
runner = runner_path.read_text()
packaged_lanes = packaged_lanes_path.read_text()
disk_cleanup = (toolcache_pruner_path.parent / "ci-emulator-free-disk.sh").read_text()
ast.parse(extractor_path.read_text(), filename=str(extractor_path))
subprocess.run(["bash", "-n", str(toolcache_pruner_path)], check=True)
subprocess.run(["bash", "-n"], input=disk_cleanup, text=True, check=True)
if "scripts/ci-emulator-prune-toolcache.sh" not in disk_cleanup:
    raise AssertionError("disk preflight does not invoke the tested toolcache-preservation helper")


def require_contract(source: str, packaged_script: str) -> None:
    required = (
        ("isolated fixtures", "scripts/agents-pool.sh up 2244 2245"),
        ("single packaged-lanes wrapper invocation", "script: scripts/ci-js-first-packaged-lanes.sh"),
        ("always-run exact result guard", "- name: Require exact reports for every packaged JS lane"),
        ("smoke result guard", "scripts/check-js-smoke-results.py"),
        ("lifecycle result guard", "scripts/check-js-lifecycle-results.py"),
        ("Files result guard", "scripts/check-js-files-results.py"),
        ("Usage/Ports result guard", "scripts/check-js-usage-ports-results.py"),
        ("Composer result guard", "scripts/check-js-composer-journey-results.py"),
        ("key-vault result guard", "scripts/check-js-key-vault-results.py"),
        ("signed-upgrade result guard", "scripts/check-js-signed-upgrade-results.py"),
        ("signed-upgrade guard self-test", "scripts/check-js-signed-upgrade-results.py --self-test"),
        ("signed-upgrade evidence upload", "android/app/build/outputs/js-key-vault-upgrade/**"),
        ("native Android unit gate", "        run: scripts/run-android-unit-gate.sh\n"),
        ("run-scoped artifact output", "android/app/build/outputs/js-composer/"),
        ("usage/ports run-scoped artifacts", "android/app/build/outputs/js-usage-ports/"),
        ("always-run artifact upload", "name: Upload packaged JS composer run evidence"),
        ("artifact uploader", "uses: actions/upload-artifact@v6"),
        ("JUnit upload", "android/app/build/outputs/androidTest-results/connected/debug/TEST-*.xml"),
        ("post-cleanup runtime preflight step", "- name: Capture and verify emulator JS runtime after disk cleanup"),
        ("captured node path", 'node_path="$(command -v node)"'),
        ("captured pnpm path", 'pnpm_path="$(command -v pnpm)"'),
        ("runtime PATH forwarded to the emulator action", "PATH: ${{ steps.emulator-js-runtime.outputs.path }}"),
        ("runtime pnpm path forwarded to the emulator action", "PNPM: ${{ steps.emulator-js-runtime.outputs.pnpm }}"),
    )
    for label, needle in required:
        if needle not in source:
            raise AssertionError(f"workflow is missing {label}: {needle}")
    action_start = source.index("- name: Run packaged JS journeys on API 35")
    action_end = source.index("\n      - name:", action_start + 8)
    emulator_action = source[action_start:action_end]
    for needle in (
        "        env:\n          PATH: ${{ steps.emulator-js-runtime.outputs.path }}\n"
        "          PNPM: ${{ steps.emulator-js-runtime.outputs.pnpm }}\n",
    ):
        if needle not in emulator_action:
            raise AssertionError("Node/pnpm outputs must be inherited from the emulator action's step-level env")
    if "PATH='${{ steps.emulator-js-runtime.outputs.path }}'" in emulator_action:
        raise AssertionError("runtime PATH should be passed through the action environment, not an inline workaround")
    if "scripts/connected-js-smoke.sh --suffix i2855ci --test-only" not in packaged_script:
        raise AssertionError("packaged wrapper omits the smoke lane")
    if "--port 2222" not in packaged_script or "--container pocketshell-test-agents" not in packaged_script:
        raise AssertionError("packaged wrapper changed the lifecycle Docker fixture contract")
    usage = packaged_script.index("scripts/connected-js-usage-ports.sh")
    files = packaged_script.index("scripts/connected-js-files-docker.sh")
    composer = packaged_script.index("scripts/connected-js-composer-docker.sh")
    lifecycle = packaged_script.index("scripts/connected-js-lifecycle.sh")
    if not lifecycle < usage < files < composer:
        raise AssertionError("usage/ports and Files must run after lifecycle, in order, before the composer journey")
    if "--run-id \"js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}\"" not in packaged_script:
        raise AssertionError("usage/ports run identity is not forwarded to the packaged journey")
    if "--suffix i2858ci" not in packaged_script or "--port 2222" not in packaged_script or \
       "--container pocketshell-test-agents" not in packaged_script or \
       "--run-id \"js2858-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}\"" not in packaged_script or \
       "--test-only" not in packaged_script:
        raise AssertionError("Files lane invocation must preserve its packaged fixture arguments and run identity")
    # The signed 0.5.6 upgrade (#2926) is part of the blocking packaged run:
    # it must be executed (not commented/documented), last, on its isolated
    # fixture, and its status must reach the wrapper's exit.
    upgrade_call = re.search(
        r"(?m)^if scripts/connected-js-key-vault-signed-upgrade\.sh \\\n"
        r"  --port 2244 \\\n"
        r"  --container pocketshell-test-agents-2244 \\\n"
        r"  --run-id \"up2926-\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}\"; then$",
        packaged_script,
    )
    if upgrade_call is None:
        raise AssertionError("packaged wrapper does not execute the signed-upgrade migration lane")
    key_vault = packaged_script.index("if scripts/connected-js-key-vault-docker.sh")
    settings_call = packaged_script.index("if scripts/connected-js-settings.sh")
    if not composer < settings_call < key_vault < upgrade_call.start():
        raise AssertionError("signed-upgrade must run last, after the suffixed key-vault lane")
    # Issue #2993: the durable-storage lane rebuilds the shared app-debug.apk
    # under its own suffix, so it must stay outside the key-vault build ->
    # signed-upgrade window (here: after Files, before composer and key-vault).
    durable_call = re.search(
        r"(?m)^if scripts/connected-js-durable-storage\.sh \\\n"
        r"  --suffix i2993ci \\\n"
        r"  --run-id \"js2993-\$\{GITHUB_RUN_ID\}-\$\{GITHUB_RUN_ATTEMPT\}\"; then$",
        packaged_script,
    )
    if durable_call is None:
        raise AssertionError("packaged wrapper does not execute the durable-storage lane with its own suffix and run identity")
    if not files < durable_call.start() < composer:
        raise AssertionError("the durable-storage lane must run after Files and before the composer and key-vault builds")
    if "durable_status != 0" not in packaged_script:
        raise AssertionError("packaged wrapper ignores the durable-storage lane status")
    if "signed_upgrade_status != 0" not in packaged_script:
        raise AssertionError("packaged wrapper ignores the signed-upgrade lane status")
    for lane in ("smoke_status", "lifecycle_status", "usage_status", "files_status", "durable_status", "composer_status",
                 "settings_status", "key_vault_status", "signed_upgrade_status", "copy_status"):
        if lane not in packaged_script:
            raise AssertionError(f"packaged wrapper does not aggregate {lane}")
    if "TEST-*.xml" not in packaged_script or "cp -a --" not in packaged_script:
        raise AssertionError("packaged wrapper must copy smoke JUnit evidence")
    if "android/app/build/outputs/js-smoke-results" not in packaged_script:
        raise AssertionError("packaged wrapper must save the smoke JUnit copy under its upload path")
    guard_start = source.index("- name: Require exact reports for every packaged JS lane")
    guard_end = source.index("- name:", guard_start + 8)
    guard = source[guard_start:guard_end]
    if "if: always()" not in guard or "shell: bash" not in guard:
        raise AssertionError("exact lane report checks must run after the emulator step even when it fails")
    exact_lane_reports = (
        ("smoke", "android/app/build/outputs/js-smoke-results"),
        ("lifecycle", "js-lifecycle/js2861-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results"),
        ("Files", "js-files/js2858-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results"),
        ("Usage/Ports", "js-usage-ports/js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results"),
        ("key-vault", "js-key-vault/js2926-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results"),
        ("signed-upgrade", "js-key-vault-upgrade/up2926-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"),
        ("durable-storage", '--run-dir "android/app/build/outputs/js-durable-storage/js2993-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"'),
    )
    for lane, report_path in exact_lane_reports:
        if report_path not in guard:
            raise AssertionError(f"the {lane} result guard must use its preserved run report: {report_path}")
    if 'composer_prefix="js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-"' not in guard or \
       'composer_runs=("$composer_root/$composer_prefix"*)' not in guard or \
       'composer_run="${composer_runs[0]}"' not in guard:
        raise AssertionError("the Composer checkers must use the unique run-scoped artifact directory")
    for phase in ("prepare", "resume"):
        if f'--results-dir "$composer_run/phase-{phase}"' not in guard:
            raise AssertionError(f"the Composer {phase} result guard must read that phase's isolated JUnit")
    for checker in (
        "scripts/check-js-smoke-results.py",
        "scripts/check-js-lifecycle-results.py",
        "scripts/check-js-files-results.py",
        "scripts/check-js-usage-ports-results.py",
        "scripts/check-js-composer-journey-results.py",
        "scripts/check-js-key-vault-results.py",
        "scripts/check-js-signed-upgrade-results.py",
        "scripts/check-js-durable-storage-results.py",
    ):
        if f"run_check " not in guard or checker not in guard:
            raise AssertionError(f"the always-run result guard does not invoke {checker}")
    if "if (( failed != 0 )); then" not in guard:
        raise AssertionError("any missing exact lane report must fail the overall workflow step")
    upload_start = source.index("- name: Upload packaged JS composer run evidence")
    upload_end = source.index("- name:", upload_start + 8)
    upload = source[upload_start:upload_end]
    if "if: always()" not in upload or "if-no-files-found: error" not in upload:
        raise AssertionError("composer evidence upload must run always and fail if the bundle is absent")
    if "android/app/build/outputs/js-composer/" not in upload:
        raise AssertionError("composer artifact upload omits the run-scoped evidence directory")
    if "${{ github.run_id }}-${{ github.run_attempt }}" not in upload:
        raise AssertionError("composer artifact name must identify its workflow run and attempt")


require_contract(workflow, packaged_lanes)

runtime_step = workflow.index("- name: Capture and verify emulator JS runtime after disk cleanup")
fixture_step = workflow.index("- name: Start version-matched Docker agents fixture")
cleanup_step = workflow.index("- name: Free disk space for API 35 emulator")
emulator_step = workflow.index("uses: reactivecircus/android-emulator-runner@")
if not fixture_step < cleanup_step < runtime_step < emulator_step:
    raise AssertionError("the Node/pnpm runtime preflight must run after toolcache cleanup and before emulator work")

runtime_end = workflow.index("- name: Repair Android cmdline-tools and accept licenses", runtime_step)
runtime_section = workflow[runtime_step:runtime_end]
runtime_lines = runtime_section.splitlines()
runtime_run_index = next(
    index for index, line in enumerate(runtime_lines)
    if re.match(r"\s+run:\s*\|\s*$", line)
)
runtime_run_indent = len(runtime_lines[runtime_run_index]) - len(runtime_lines[runtime_run_index].lstrip())
runtime_script_lines = []
for line in runtime_lines[runtime_run_index + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= runtime_run_indent:
        break
    runtime_script_lines.append(
        line[runtime_run_indent + 2:]
        if line.startswith(" " * (runtime_run_indent + 2)) else ""
    )
subprocess.run(
    ["bash", "-n"],
    input="\n".join(runtime_script_lines),
    text=True,
    check=True,
)

for needle in (
    '[[ "$node_path" == /* && -x "$node_path" ]] ||',
    '[[ "$pnpm_path" == /* && -x "$pnpm_path" ]] ||',
    'printf \'path=%s\\n\' "$PATH" >> "$GITHUB_OUTPUT"',
    'printf \'pnpm=%s\\n\' "$pnpm_path" >> "$GITHUB_OUTPUT"',
):
    if needle not in workflow:
        raise AssertionError(f"emulator JavaScript runtime preflight is missing: {needle}")

for label, damaged in (
    ("action runtime PATH forwarding", workflow.replace(
        "          PATH: ${{ steps.emulator-js-runtime.outputs.path }}\n",
        "",
        1,
    )),
    ("action runtime pnpm forwarding", workflow.replace(
        "          PNPM: ${{ steps.emulator-js-runtime.outputs.pnpm }}\n",
        "",
        1,
    )),
    ("early runtime preflight", workflow.replace(
        "- name: Capture and verify emulator JS runtime after disk cleanup",
        "- name: Capture runtime without verification",
        1,
    )),
):
    try:
        require_contract(damaged, packaged_lanes)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed removed {label}")

def durable_inside_key_vault_window(source: str) -> str:
    """Move the durable-storage block between the key-vault and signed-upgrade lanes."""
    start = source.index("# Issue #2993: user-data writes")
    end = source.index("if scripts/connected-js-composer-docker.sh")
    block = source[start:end]
    without = source[:start] + source[end:]
    window = without.index("# Signed 0.5.6-to-candidate upgrade")
    moved = without[:window] + block + without[window:]
    if moved == source:
        raise AssertionError("durable-storage move fixture did not change the wrapper")
    return moved


for label, damaged in (
    ("composer invocation", packaged_lanes.replace(
        "if scripts/connected-js-composer-docker.sh \\\n",
        "",
        1,
    )),
    ("signed-upgrade invocation", packaged_lanes.replace(
        "if scripts/connected-js-key-vault-signed-upgrade.sh \\\n",
        "if true \\\n",
        1,
    )),
    ("signed-upgrade exit aggregation", packaged_lanes.replace(
        " || signed_upgrade_status != 0",
        "",
        1,
    )),
    ("signed-upgrade always-run report check", workflow.replace(
        "          run_check Signed-upgrade scripts/check-js-signed-upgrade-results.py \\\n",
        "          true \\\n",
        1,
    )),
    ("native Android unit gate", workflow.replace(
        "        run: scripts/run-android-unit-gate.sh\n",
        "        run: true\n",
        1,
    )),
    ("composer artifact path", workflow.replace(
        "            android/app/build/outputs/js-composer/\n",
        "            android/app/build/outputs/other/\n",
        1,
    )),
    ("durable-storage invocation", packaged_lanes.replace(
        "if scripts/connected-js-durable-storage.sh \\\n",
        "if true \\\n",
        1,
    )),
    ("durable-storage exit aggregation", packaged_lanes.replace(
        " || durable_status != 0",
        "",
        1,
    )),
    ("durable-storage lane outside the key-vault -> signed-upgrade window", durable_inside_key_vault_window(packaged_lanes)),
    ("durable-storage always-run report check", workflow.replace(
        "          run_check \"Durable storage\" scripts/check-js-durable-storage-results.py \\\n",
        "          true \\\n",
        1,
    )),
):
    try:
        if label in {"composer invocation", "signed-upgrade invocation", "signed-upgrade exit aggregation",
                     "durable-storage invocation", "durable-storage exit aggregation",
                     "durable-storage lane outside the key-vault -> signed-upgrade window"}:
            require_contract(workflow, damaged)
        else:
            require_contract(damaged, packaged_lanes)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed a removed {label}")


def workflow_run_script(source: str, step_name: str) -> str:
    step_start = source.index(f"- name: {step_name}")
    step_end = source.find("\n      - name:", step_start + 8)
    if step_end < 0:
        step_end = len(source)
    lines = source[step_start:step_end].splitlines()
    run_index = next(
        index for index, line in enumerate(lines)
        if re.match(r"\s+run:\s*\|\s*$", line)
    )
    run_indent = len(lines[run_index]) - len(lines[run_index].lstrip())
    script_lines = []
    for line in lines[run_index + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= run_indent:
            break
        script_lines.append(
            line[run_indent + 2:]
            if line.startswith(" " * (run_indent + 2)) else ""
        )
    return "\n".join(script_lines)


# #2863: the connected-lane lock harnesses (Gradle output-tree lock and JS
# serial ownership) are only regression coverage if the rewrite branch's
# blocking job actually executes them. Legacy tests.yml reachability does not
# count, so pin each as an executable, fail-closed line of this job's step.
LOCK_HARNESS_JOB = "web-and-android"
LOCK_HARNESS_STEP = "Check JS-first connected-test dispatch and lock contracts"
LOCK_HARNESSES = (
    "scripts/test-gradle-output-lock.sh",
    "tests/scripts/connected-test-serial-ownership-test.sh",
    "tests/scripts/avd-lock-test.sh",
    "tests/scripts/avd-lock-sharing-test.sh",
)


def blocking_job_section(source: str, job: str) -> str:
    match = re.search(rf"(?m)^  {re.escape(job)}:\n", source)
    if match is None:
        raise AssertionError(f"workflow is missing the blocking job {job}")
    following = re.search(r"(?m)^  [A-Za-z0-9_-]+:\n", source[match.end():])
    end = match.end() + following.start() if following else len(source)
    return source[match.start():end]


def require_lock_harness_invocations(source: str) -> None:
    job = blocking_job_section(source, LOCK_HARNESS_JOB)
    if re.search(r"(?m)^    continue-on-error:", job):
        raise AssertionError(f"the {LOCK_HARNESS_JOB} job must stay blocking")
    if f"- name: {LOCK_HARNESS_STEP}\n" not in job:
        raise AssertionError(f"the {LOCK_HARNESS_JOB} job is missing the step: {LOCK_HARNESS_STEP}")
    step_start = job.index(f"- name: {LOCK_HARNESS_STEP}\n")
    step_end = job.find("\n      - name:", step_start + 8)
    step = job[step_start:step_end if step_end >= 0 else len(job)]
    for key in ("if:", "continue-on-error:", "shell:"):
        if re.search(rf"(?m)^        {re.escape(key)}", step):
            raise AssertionError(f"the lock-contract step must run unconditionally with the default fail-fast shell ({key})")
    script = workflow_run_script(job, LOCK_HARNESS_STEP)
    subprocess.run(["bash", "-n"], input=script, text=True, check=True)
    commands = [
        line.strip() for line in script.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    if any(re.match(r"set\s+[+]", command) for command in commands):
        raise AssertionError("the lock-contract step must not disable fail-fast execution")
    for harness in LOCK_HARNESSES:
        if harness not in commands:
            raise AssertionError(f"the {LOCK_HARNESS_JOB} job does not execute {harness} as its own fail-closed command")
        path = repository_root / harness
        if not (path.is_file() and os.access(path, os.X_OK)):
            raise AssertionError(f"lock harness is missing or not executable: {harness}")


require_lock_harness_invocations(workflow)
for harness in LOCK_HARNESSES:
    invocation = f"          {harness}\n"
    if workflow.count(invocation) != 1:
        raise AssertionError(f"lock-harness mutation fixture did not match exactly one {harness} line")
    for label, damaged in (
        ("removed", workflow.replace(invocation, "", 1)),
        ("commented out", workflow.replace(invocation, f"          # {harness}\n", 1)),
        ("failure-masked", workflow.replace(invocation, f"          {harness} || true\n", 1)),
        ("moved to the fixture job", workflow.replace(invocation, "", 1).rstrip("\n")
         + f"\n\n      - name: Relocated lock harness\n        run: {harness}\n"),
    ):
        try:
            require_lock_harness_invocations(damaged)
        except AssertionError:
            print(f"PASS: {label} {harness} fails the rewrite blocking-job lock contract")
        else:
            raise AssertionError(f"rewrite lock contract missed a {label} {harness}")
step_marker = f"      - name: {LOCK_HARNESS_STEP}\n"
for label, damaged in (
    ("non-blocking", workflow.replace(step_marker, step_marker + "        continue-on-error: true\n", 1)),
    ("conditional", workflow.replace(step_marker, step_marker + "        if: false\n", 1)),
    ("fail-fast disabled", workflow.replace(
        step_marker + "        run: |\n",
        step_marker + "        run: |\n          set +e\n",
        1,
    )),
):
    if damaged == workflow:
        raise AssertionError(f"lock-contract {label} mutation fixture did not match the workflow")
    try:
        require_lock_harness_invocations(damaged)
    except AssertionError:
        print(f"PASS: a {label} lock-contract step fails the rewrite blocking-job lock contract")
    else:
        raise AssertionError(f"rewrite lock contract missed a {label} step")
print("PASS: the rewrite blocking job executes the Gradle output-lock, JS serial-ownership, and AVD-lock harnesses")


# Model the reviewer's dormant Files call: its wrapper status can be zero while
# the run-scoped Files directory has no JUnit. The actual always-run workflow
# step must still fail after checking the other lanes.
guard_script = workflow_run_script(workflow, "Require exact reports for every packaged JS lane")
with tempfile.TemporaryDirectory(prefix="js rewrite live lane reports ") as temporary:
    fake_repo = Path(temporary)
    fake_scripts = fake_repo / "scripts"
    fake_scripts.mkdir()
    result_trace = fake_repo / "result-check-trace.txt"
    for checker, lane in (
        ("check-js-smoke-results.py", "smoke"),
        ("check-js-lifecycle-results.py", "lifecycle"),
        ("check-js-usage-ports-results.py", "usage-ports"),
        ("check-js-composer-journey-results.py", "composer"),
        ("check-js-durable-storage-results.py", "durable-storage"),
        ("check-js-key-vault-results.py", "key-vault"),
        ("check-js-signed-upgrade-results.py", "signed-upgrade"),
    ):
        execute = (
            'exec "$PYTHON" "$ACTUAL_COMPOSER_CHECKER" "$@"\n'
            if checker == "check-js-composer-journey-results.py" else ""
        )
        stub = fake_scripts / checker
        stub.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s\\t%s\\n' '{lane}' \"$*\" >> \"$RESULT_CHECK_TRACE\"\n"
            + execute
        )
        stub.chmod(0o755)
    files_checker = fake_scripts / "check-js-files-results.py"
    files_checker.write_text((repository_root / "scripts/check-js-files-results.py").read_text())
    files_checker.chmod(0o755)
    composer_dir = fake_repo / "android/app/build/outputs/js-composer/js2891-fixture-1-123"
    composer_xml = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<testsuite name="com.pocketshell.app.smoke.JsComposerDockerJourneyTest" '
        'tests="1" failures="0" errors="0" skipped="0">'
        '<testcase name="composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop" '
        'classname="com.pocketshell.app.smoke.JsComposerDockerJourneyTest"/></testsuite>\n'
    )
    for relative in ("", "phase-prepare", "phase-resume"):
        report_dir = composer_dir / relative
        report_dir.mkdir(parents=True, exist_ok=True)
        (report_dir / "TEST-composer.xml").write_text(composer_xml)
    result = subprocess.run(
        ["bash", "-e", "-u", "-o", "pipefail"],
        input=guard_script,
        cwd=fake_repo,
        env={
            **os.environ,
            "GITHUB_RUN_ID": "fixture",
            "GITHUB_RUN_ATTEMPT": "1",
            "RESULT_CHECK_TRACE": str(result_trace),
            "PYTHON": sys.executable,
            "ACTUAL_COMPOSER_CHECKER": str(repository_root / "scripts/check-js-composer-journey-results.py"),
        },
        text=True,
        capture_output=True,
    )
    if result.returncode == 0 or "Files result check exited" not in result.stderr or \
       "instrumentation results directory is missing" not in result.stderr:
        raise AssertionError(
            "the always-run lane guard accepted a dormant Files runner: "
            f"exit={result.returncode}, stdout={result.stdout!r}, stderr={result.stderr!r}"
        )
    if "Composer prepare result check exited" in result.stderr or \
       "Composer resume result check exited" in result.stderr:
        raise AssertionError(f"isolated Composer phases did not accept the real run's duplicated summary copy: {result.stderr!r}")
    checked_lines = result_trace.read_text().splitlines()
    checked_lanes = [line.split("\t", 1)[0] for line in checked_lines]
    if checked_lanes != ["smoke", "lifecycle", "usage-ports", "key-vault", "durable-storage", "signed-upgrade", "composer", "composer"]:
        raise AssertionError(f"the always-run report guard stopped before all present lanes: {checked_lanes!r}")
    if "/phase-prepare" not in checked_lines[6] or "/phase-resume" not in checked_lines[7]:
        raise AssertionError(f"the guard did not check the two Composer process phases independently: {checked_lines!r}")
print("PASS: a dormant Files invocation fails the always-run workflow report guard despite a zero lane status")

# The emulator action splits multiline scripts across child shells. Require
# exactly one executable wrapper invocation so all lane statuses share one Bash.
section_start = workflow.index("- name: Run packaged JS journeys on API 35")
section_end = workflow.index("\n      - name:", section_start + 8)
section = workflow[section_start:section_end]
script_lines = [line.strip() for line in section.splitlines() if re.match(r"\s+script:", line)]
if script_lines != ["script: scripts/ci-js-first-packaged-lanes.sh"]:
    raise AssertionError(f"emulator action must invoke one packaged-lanes wrapper line: {script_lines!r}")
action_command = "scripts/ci-js-first-packaged-lanes.sh"
subprocess.run(["bash", "-n", str(packaged_lanes_path)], check=True)


def exercise_packaged_lanes(label: str, *, smoke: int = 0, lifecycle: int = 0,
                            usage: int = 0, files: int = 0, durable: int = 0, composer: int = 0, settings: int = 0,
                            key_vault: int = 0, signed_upgrade: int = 0, omit_junit: bool = False,
                            fail_junit_copy: bool = False) -> None:
    with tempfile.TemporaryDirectory(prefix="js rewrite action ") as temporary:
        fixture = Path(temporary)
        fake_repo = fixture / "fake repo"
        fake_scripts = fake_repo / "scripts"
        fake_scripts.mkdir(parents=True)
        fake_wrapper = fake_scripts / packaged_lanes_path.name
        fake_wrapper.write_text(packaged_lanes)
        fake_wrapper.chmod(0o755)

        trace = fixture / "lane-trace.txt"
        runtime_bin = fixture / "runtime bin"
        runtime_bin.mkdir()
        fake_pnpm = runtime_bin / "pnpm"
        fake_pnpm.write_text("#!/bin/sh\nprintf 'fixture-pnpm-ok\\n'\n")
        fake_pnpm.chmod(0o755)
        if fail_junit_copy:
            fake_cp = runtime_bin / "cp"
            fake_cp.write_text("#!/bin/sh\nexit 31\n")
            fake_cp.chmod(0o755)
        runtime_capture = fixture / "composer-runtime.txt"

        fake_smoke = fake_scripts / "connected-js-smoke.sh"
        fake_smoke.write_text(
            "#!/bin/bash\n"
            "printf 'smoke\\t%s\\t%s\\n' \"$FIXTURE_SMOKE_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "if [ \"$FIXTURE_OMIT_JUNIT\" != 1 ]; then\n"
            "  mkdir -p android/app/build/outputs/androidTest-results/connected/debug\n"
            "  printf '<testsuite tests=\"1\"/>\\n' > android/app/build/outputs/androidTest-results/connected/debug/TEST-smoke.xml\n"
            "fi\n"
            "exit \"$FIXTURE_SMOKE_STATUS\"\n"
        )
        fake_smoke.chmod(0o755)
        fake_lifecycle = fake_scripts / "connected-js-lifecycle.sh"
        fake_lifecycle.write_text(
            "#!/bin/bash\n"
            "printf 'lifecycle\\t%s\\t%s\\n' \"$FIXTURE_LIFECYCLE_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "exit \"$FIXTURE_LIFECYCLE_STATUS\"\n"
        )
        fake_lifecycle.chmod(0o755)
        fake_usage = fake_scripts / "connected-js-usage-ports.sh"
        fake_usage.write_text(
            "#!/bin/bash\n"
            "printf 'usage-ports\\t%s\\t%s\\n' \"$FIXTURE_USAGE_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "exit \"$FIXTURE_USAGE_STATUS\"\n"
        )
        fake_usage.chmod(0o755)
        fake_files = fake_scripts / "connected-js-files-docker.sh"
        fake_files.write_text(
            "#!/bin/bash\n"
            "printf 'files\\t%s\\t%s\\n' \"$FIXTURE_FILES_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "exit \"$FIXTURE_FILES_STATUS\"\n"
        )
        fake_files.chmod(0o755)
        fake_composer = fake_scripts / "connected-js-composer-docker.sh"
        fake_composer.write_text(
            "#!/bin/bash\n"
            "printf 'composer\\t%s\\t%s\\n' \"$FIXTURE_COMPOSER_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "printf '%s\\n%s\\n' \"$PNPM\" \"$PATH\" > \"$FIXTURE_RUNTIME_CAPTURE\"\n"
            "\"$PNPM\" --version >> \"$FIXTURE_RUNTIME_CAPTURE\"\n"
            "exit \"$FIXTURE_COMPOSER_STATUS\"\n"
        )
        fake_composer.chmod(0o755)
        for script_name, lane_name, status_var in (
            ("connected-js-settings.sh", "settings", "FIXTURE_SETTINGS_STATUS"),
            ("connected-js-durable-storage.sh", "durable-storage", "FIXTURE_DURABLE_STATUS"),
            ("connected-js-key-vault-docker.sh", "key-vault", "FIXTURE_KEY_VAULT_STATUS"),
            ("connected-js-key-vault-signed-upgrade.sh", "signed-upgrade", "FIXTURE_SIGNED_UPGRADE_STATUS"),
        ):
            fake_lane = fake_scripts / script_name
            fake_lane.write_text(
                "#!/bin/bash\n"
                f"printf '{lane_name}\\t%s\\t%s\\n' \"${status_var}\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
                f"exit \"${status_var}\"\n"
            )
            fake_lane.chmod(0o755)

        env = {
            "PATH": f"{runtime_bin}:/usr/bin:/bin",
            "BASH_ENV": "",
            "PNPM": str(fake_pnpm),
            "FIXTURE_TRACE": str(trace),
            "FIXTURE_RUNTIME_CAPTURE": str(runtime_capture),
            "FIXTURE_SMOKE_STATUS": str(smoke),
            "FIXTURE_LIFECYCLE_STATUS": str(lifecycle),
            "FIXTURE_USAGE_STATUS": str(usage),
            "FIXTURE_FILES_STATUS": str(files),
            "FIXTURE_COMPOSER_STATUS": str(composer),
            "FIXTURE_SETTINGS_STATUS": str(settings),
            "FIXTURE_DURABLE_STATUS": str(durable),
            "FIXTURE_KEY_VAULT_STATUS": str(key_vault),
            "FIXTURE_SIGNED_UPGRADE_STATUS": str(signed_upgrade),
            "FIXTURE_OMIT_JUNIT": "1" if omit_junit else "0",
            "GITHUB_RUN_ID": "run",
            "GITHUB_RUN_ATTEMPT": "1",
        }
        result = subprocess.run(
            ["/bin/sh", "-c", action_command],
            cwd=fake_repo,
            env=env,
            check=False,
            text=True,
            capture_output=True,
        )
        expected_copy = 31 if fail_junit_copy else (1 if omit_junit else 0)
        expected_summary = (
            f"Packaged API 35 lane statuses: smoke={smoke} lifecycle={lifecycle} "
            f"usage-ports={usage} files={files} durable-storage={durable} composer={composer} settings={settings} "
            f"key-vault={key_vault} "
            f"signed-upgrade={signed_upgrade} smoke-junit-copy={expected_copy}"
        )
        expected_exit = 1 if any((smoke, lifecycle, usage, files, durable, composer, settings, key_vault, signed_upgrade, expected_copy)) else 0
        if result.returncode != expected_exit or expected_summary not in result.stdout:
            raise AssertionError(
                f"{label}: wrapper did not preserve its lane statuses: exit={result.returncode}, "
                f"stdout={result.stdout!r}, stderr={result.stderr!r}"
            )
        trace_lines = trace.read_text().splitlines()
        if [line.split("\t", 1)[0] for line in trace_lines] != [
            "smoke", "lifecycle", "usage-ports", "files", "durable-storage", "composer", "settings", "key-vault", "signed-upgrade",
        ]:
            raise AssertionError(f"{label}: wrapper failed to execute every lane in order: {trace_lines!r}")
        if "--run-id js2861-run-1" not in trace_lines[1]:
            raise AssertionError(f"{label}: lifecycle run identity was not forwarded: {trace_lines[1]!r}")
        if "--run-id js2859-run-1" not in trace_lines[2]:
            raise AssertionError(f"{label}: usage/ports run identity was not forwarded: {trace_lines[2]!r}")
        expected_files_args = (
            "--suffix i2858ci --port 2222 --container pocketshell-test-agents "
            "--run-id js2858-run-1 --test-only"
        )
        if trace_lines[3] != f"files\t{files}\t{expected_files_args}":
            raise AssertionError(
                f"{label}: Files lane arguments/status were not preserved: {trace_lines[3]!r}"
            )
        if trace_lines[4] != f"durable-storage\t{durable}\t--suffix i2993ci --run-id js2993-run-1":
            raise AssertionError(f"{label}: durable-storage lane arguments/status were not preserved: {trace_lines[4]!r}")
        if "--session-prefix js2891-run-1" not in trace_lines[5]:
            raise AssertionError(f"{label}: composer session identity was not forwarded: {trace_lines[5]!r}")
        if trace_lines[6] != f"settings\t{settings}\t--suffix i2855ci --port 2222 --container pocketshell-test-agents --run-id js2861s-run-1 --test-only":
            raise AssertionError(f"{label}: settings lane arguments/status were not preserved: {trace_lines[6]!r}")
        expected_upgrade_args = "--port 2244 --container pocketshell-test-agents-2244 --run-id up2926-run-1"
        if trace_lines[8] != f"signed-upgrade\t{signed_upgrade}\t{expected_upgrade_args}":
            raise AssertionError(f"{label}: signed-upgrade lane arguments/status were not preserved: {trace_lines[8]!r}")
        if runtime_capture.read_text().splitlines() != [
            str(fake_pnpm),
            env["PATH"],
            "fixture-pnpm-ok",
        ]:
            raise AssertionError(
                f"{label}: wrapper did not preserve the action runtime env: "
                f"expected={[str(fake_pnpm), env['PATH'], 'fixture-pnpm-ok']!r}, "
                f"actual={runtime_capture.read_text().splitlines()!r}"
            )
        copied_junit = fake_repo / "android/app/build/outputs/js-smoke-results/TEST-smoke.xml"
        expected_copied_junit = not omit_junit and not fail_junit_copy
        if copied_junit.exists() != expected_copied_junit:
            raise AssertionError(f"{label}: smoke JUnit copy did not match fixture output")


exercise_packaged_lanes("success")
exercise_packaged_lanes("smoke failure is fail-closed", smoke=17)
exercise_packaged_lanes("lifecycle failure is fail-closed", lifecycle=19)
exercise_packaged_lanes("usage/ports failure is fail-closed", usage=21)
exercise_packaged_lanes("Files failure is fail-closed", files=25)
exercise_packaged_lanes("composer failure is fail-closed", composer=23)
exercise_packaged_lanes("settings failure is fail-closed", settings=27)
exercise_packaged_lanes("durable-storage failure is fail-closed", durable=31)
exercise_packaged_lanes("key-vault failure is fail-closed", key_vault=27)
exercise_packaged_lanes("signed-upgrade migration failure is fail-closed", signed_upgrade=29)
exercise_packaged_lanes("missing JUnit is fail-closed", omit_junit=True)
exercise_packaged_lanes("JUnit copy command failure is fail-closed", fail_junit_copy=True)

# Exercise the actual disk-prune helper against a temporary toolcache. It must
# preserve and run the exact JDK/Node/pnpm paths discovered before pruning,
# while deleting unrelated caches. The old JDK-only behavior is also run as a
# negative control and must fail after deleting the active Node root.
toolcache_pruner = toolcache_pruner_path.read_text()
if 'remember_root "$node_path" || true' not in toolcache_pruner:
    raise AssertionError("toolcache regression fixture no longer targets Node-root preservation")


def run_toolcache_fixture(helper_text: str, *, expect_success: bool) -> None:
    with tempfile.TemporaryDirectory(prefix="js rewrite toolcache ") as temporary:
        fixture = Path(temporary)
        toolcache = fixture / "hostedtoolcache"
        if not str(toolcache.resolve()).startswith(str(fixture.resolve()) + "/"):
            raise AssertionError("toolcache regression fixture escaped its temporary directory")
        java_home = toolcache / "JavaFixture" / "21" / "x64"
        java_bin = java_home / "bin"
        node_bin = toolcache / "node" / "22" / "x64" / "bin"
        guard_bin = fixture / "guard-bin"
        codeql = toolcache / "CodeQL" / "cache"
        other = toolcache / "UnusedFixture" / "cache"
        for directory in (java_bin, node_bin, guard_bin, codeql, other):
            directory.mkdir(parents=True, exist_ok=True)
        java = java_bin / "java"
        node = node_bin / "node"
        pnpm = node_bin / "pnpm"
        java.write_text("#!/bin/sh\nprintf 'fixture-java-21\\n'\n")
        node.write_text("#!/bin/sh\nprintf 'fixture-node-22\\n'\n")
        pnpm.write_text('#!/bin/sh\nexec "$(dirname "$0")/node" --version\n')
        for executable in (java, node, pnpm):
            executable.chmod(0o755)
        (codeql / "sentinel").write_text("unused\n")
        (other / "sentinel").write_text("unused\n")
        side_effect_log = fixture / "unexpected-host-side-effect.txt"
        for command in ("sudo", "docker"):
            stub = guard_bin / command
            stub.write_text(
                "#!/bin/sh\n"
                f"printf '%s\\n' '{command} $*' >> '{side_effect_log}'\n"
                "exit 97\n"
            )
            stub.chmod(0o755)
        (guard_bin / "find").write_text(
            "#!/bin/sh\n"
            'case "$1" in "$FIXTURE_TOOLCACHE"|"$FIXTURE_TOOLCACHE"/*) ;;\n'
            f"  *) printf '%s\\n' 'find escaped temporary toolcache: $*' >> '{side_effect_log}'; exit 98 ;;\n"
            "esac\n"
            'exec /usr/bin/find "$@"\n'
        )
        (guard_bin / "find").chmod(0o755)
        (guard_bin / "rm").write_text(
            "#!/bin/sh\n"
            'for arg do\n'
            '  case "$arg" in\n'
            '    -*) ;;\n'
            '    "$FIXTURE_TOOLCACHE"/*) ;;\n'
            f"    *) printf '%s\\n' 'rm escaped temporary toolcache: $*' >> '{side_effect_log}'; exit 99 ;;\n"
            '  esac\n'
            'done\n'
            'exec /usr/bin/rm "$@"\n'
        )
        (guard_bin / "rm").chmod(0o755)

        helper = fixture / "prune-toolcache.sh"
        helper.write_text(helper_text)
        result = subprocess.run(
            ["bash", str(helper)],
            env={
                **os.environ,
                "AGENT_TOOLSDIRECTORY": str(toolcache),
                "JAVA_HOME": str(java_home),
                "FIXTURE_TOOLCACHE": str(toolcache),
                "PATH": f"{node_bin}:{guard_bin}:/usr/bin:/bin",
                "CI_EMULATOR_TOOLCACHE_NO_SUDO": "1",
            },
            text=True,
            capture_output=True,
        )
        if side_effect_log.exists():
            raise AssertionError(
                "toolcache fixture reached a sudo/docker side effect: "
                f"{side_effect_log.read_text()!r}"
            )
        if expect_success:
            if result.returncode != 0:
                raise AssertionError(
                    "safe toolcache fixture failed to preserve the live runtimes: "
                    f"stdout={result.stdout!r} stderr={result.stderr!r}"
                )
            for executable in (java, node, pnpm):
                if not executable.is_file() or not os.access(executable, os.X_OK):
                    raise AssertionError(f"active executable was pruned: {executable}")
            if (toolcache / "CodeQL").exists() or (toolcache / "UnusedFixture").exists():
                raise AssertionError("toolcache fixture did not prune unused entries")
            for executable in (java, node, pnpm):
                subprocess.run([str(executable), "--version"], check=True, capture_output=True, text=True)
        elif result.returncode == 0:
            raise AssertionError("JDK-only cleanup regression unexpectedly preserved Node")


run_toolcache_fixture(toolcache_pruner, expect_success=True)
old_jdk_only_behavior = toolcache_pruner.replace(
    'remember_root "$node_path" || true',
    ': # old cleanup kept only JAVA_HOME',
    1,
).replace(
    'remember_root "$pnpm_path" || true',
    ': # old cleanup kept only JAVA_HOME',
    1,
)
run_toolcache_fixture(old_jdk_only_behavior, expect_success=False)

if "android/app/build/outputs/js-composer/$ARTIFACT_RUN_ID" not in runner:
    raise AssertionError("composer runner evidence is not stored in run-scoped Android build outputs")
if "${TMPDIR:-/tmp}/pocketshell-js2857-" in runner:
    raise AssertionError("composer artifacts still default to ephemeral /tmp storage")
if 'check-js-composer-journey-results.py" --results-dir "$RESULTS_DIR"' not in runner:
    raise AssertionError("composer runner lost its exact same-run JUnit guard")
if "composer-host-oracle.txt" not in runner:
    raise AssertionError("host-side composer acceptance evidence is not staged for upload")
if "trap finish_composer_run EXIT" not in runner or '"$RESULTS_DIR"/TEST-*.xml' not in runner:
    raise AssertionError("composer runner does not preserve JUnit and exit status in its run bundle")
if "--preserve-on-failure" not in runner or '--output-dir "$evidence_dir"' not in runner:
    raise AssertionError("composer runner does not extract contemporaneous failure artifacts into its run bundle")
if 'tee "$evidence_dir/composer-gradle.log"' not in runner:
    raise AssertionError("composer runner does not retain its packaged Gradle output")

def require_android_test_gradle_output_capture(source: str) -> None:
    capture = re.search(
        r'(?m)^"\$ROOT_DIR/android/gradlew" -p "\$ROOT_DIR/android" :app:assembleDebugAndroidTest \\\n'
        r'(?:[ \t]+[^\n]*\\\n)*'
        r'[ \t]+2>&1 \| tee -a "\$evidence_dir/composer-gradle\.log"$',
        source,
    )
    if capture is None:
        raise AssertionError("composer runner does not append AndroidTest Gradle output to its run bundle")


require_android_test_gradle_output_capture(runner)
missing_android_test_append = runner.replace(
    '  2>&1 | tee -a "$evidence_dir/composer-gradle.log"\n',
    "",
    1,
)
if missing_android_test_append == runner:
    raise AssertionError("AndroidTest Gradle append regression fixture did not match the runner")
try:
    require_android_test_gradle_output_capture(missing_android_test_append)
except AssertionError:
    print("PASS: missing AndroidTest Gradle output append fails the composer runner contract")
else:
    raise AssertionError("composer runner contract missed a removed AndroidTest Gradle output append")

print("PASS: rewrite composer and Usage/Ports CI run on API 35, validate exact JUnit, and upload run-scoped evidence")
print("PASS: packaged lanes execute fail-closed in one shell and preserve the captured Node/pnpm runtime")
PY
