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

COMPOSER_ORDER_GATE="$ROOT_DIR/scripts/check-js-usage-ports-composer-order.py"
[[ -x "$COMPOSER_ORDER_GATE" ]] || {
  printf 'FAIL: Usage/Ports Composer ordering gate is missing\n' >&2
  exit 1
}
"$COMPOSER_ORDER_GATE" --self-test

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
usage_ports_journey_path = workflow_path.parent.parent.parent / (
    "android/app/src/androidTest/java/com/pocketshell/app/smoke/UsagePortsDockerJourneyTest.java"
)
usage_ports_journey = usage_ports_journey_path.read_text()
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
    hotkeys_runner = packaged_script.index("if scripts/connected-js-hotkeys-docker.sh")
    composer_check = packaged_script.index("if ! scripts/check-js-composer-journey-results.py")
    if not composer_check < hotkeys_runner:
        raise AssertionError("the Fast Keys lane must run after both Composer phases are checked")
    for needle in (
        "--suffix i2884ci",
        "--port 2243",
        '--session-prefix "js2884-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"',
        'hotkeys_prefix="js2884-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-"',
        'scripts/check-js-hotkeys-journey-results.py --results-dir "${hotkeys_runs[0]}"',
    ):
        if needle not in packaged_script:
            raise AssertionError(f"packaged Fast Keys lane is missing its run-scoped contract: {needle}")
    if not hotkeys_runner < packaged_script.index("printf 'Packaged API 35 lane statuses:"):
        raise AssertionError("the Fast Keys lane must be included in the packaged status summary")
    if not hotkeys_runner < packaged_script.index("if scripts/connected-js-key-vault-docker.sh"):
        raise AssertionError("the Fast Keys lane must run before the key-vault -> signed-upgrade window")
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
    shared_app = packaged_script.index("if scripts/connected-js-shared-app.sh")
    if not composer < settings_call < shared_app < key_vault < upgrade_call.start():
        raise AssertionError("settings and shared-app run after composer; signed-upgrade runs last, after the suffixed key-vault lane")
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
    for lane in ("smoke_status", "lifecycle_status", "usage_status", "files_status", "composer_status",
                 "hotkeys_status", "hotkeys_junit_status", "durable_status", "settings_status",
                 "account_sync_status", "shared_app_status", "key_vault_status", "signed_upgrade_status", "copy_status"):
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
        ("account-sync", "js-account-sync/js3020-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results"),
        ("account-picker", "js-account-picker/js3063-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results"),
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
        "scripts/check-js-account-sync-results.py",
        "scripts/check-js-account-picker-results.py",
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
                            usage: int = 0, files: int = 0, composer: int = 0, durable: int = 0, settings: int = 0,
                            account_sync: int = 0, account_picker: int = 0, shared_app: int = 0,
                            key_vault: int = 0, signed_upgrade: int = 0, omit_junit: bool = False,
                            fail_junit_copy: bool = False, hotkeys: int = 0,
                            hotkeys_checker: int = 0, omit_hotkeys_junit: bool = False) -> None:
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
            "run_dir=android/app/build/outputs/js-composer/js2891-run-1-composer-fixture\n"
            "for phase in prepare resume; do\n"
            "  mkdir -p \"$run_dir/phase-$phase\"\n"
            "  printf '%s\\n' '<?xml version=\"1.0\"?><testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0.01\"><testcase classname=\"com.pocketshell.app.smoke.JsComposerDockerJourneyTest\" name=\"composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop\" time=\"0.01\"/></testsuite>' > \"$run_dir/phase-$phase/TEST-composer.xml\"\n"
            "done\n"
            "exit \"$FIXTURE_COMPOSER_STATUS\"\n"
        )
        fake_composer.chmod(0o755)
        fake_composer_checker = fake_scripts / "check-js-composer-journey-results.py"
        fake_composer_checker.write_text(
            "#!/bin/bash\n"
            "if [ \"$#\" != 2 ] || [ \"$1\" != --results-dir ] || [ ! -s \"$2/TEST-composer.xml\" ]; then exit 51; fi\n"
            "exit 0\n"
        )
        fake_composer_checker.chmod(0o755)

        fake_hotkeys = fake_scripts / "connected-js-hotkeys-docker.sh"
        fake_hotkeys.write_text(
            "#!/bin/bash\n"
            "printf 'hotkeys\\t%s\\t%s\\n' \"$FIXTURE_HOTKEYS_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "run_id=\"$FIXTURE_HOTKEYS_RUN_ID\"\n"
            "evidence_dir=\"android/app/build/outputs/js-hotkeys/$run_id\"\n"
            "mkdir -p \"$evidence_dir\"\n"
            "if [ \"$FIXTURE_OMIT_HOTKEYS_JUNIT\" != 1 ]; then\n"
            "  printf '%s\\n' '<?xml version=\"1.0\"?><testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"0.01\"><testcase classname=\"com.pocketshell.app.smoke.JsFastKeysDockerJourneyTest\" name=\"fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect\" time=\"0.01\"/></testsuite>' > \"$evidence_dir/TEST-fastkeys.xml\"\n"
            "fi\n"
            "printf 'run_id=%s\\nexit_code=%s\\n' \"$run_id\" \"$FIXTURE_HOTKEYS_STATUS\" > \"$evidence_dir/hotkeys-run-metadata.txt\"\n"
            "exit \"$FIXTURE_HOTKEYS_STATUS\"\n"
        )
        fake_hotkeys.chmod(0o755)
        fake_hotkeys_checker = fake_scripts / "check-js-hotkeys-journey-results.py"
        fake_hotkeys_checker.write_text(
            "#!/bin/bash\n"
            "printf 'hotkeys-check\\t%s\\n' \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "if [ \"$#\" != 2 ] || [ \"$1\" != --results-dir ]; then exit 41; fi\n"
            "if [ ! -s \"$2/TEST-fastkeys.xml\" ] || [ ! -s \"$2/hotkeys-run-metadata.txt\" ]; then exit 42; fi\n"
            "if ! grep -qx 'exit_code=0' \"$2/hotkeys-run-metadata.txt\"; then exit 43; fi\n"
            "exit \"$FIXTURE_HOTKEYS_CHECKER_STATUS\"\n"
        )
        fake_hotkeys_checker.chmod(0o755)
        for script_name, lane_name, status_var in (
            ("connected-js-settings.sh", "settings", "FIXTURE_SETTINGS_STATUS"),
            ("connected-js-account-sync.sh", "account-sync", "FIXTURE_ACCOUNT_SYNC_STATUS"),
            ("connected-js-account-picker.sh", "account-picker", "FIXTURE_ACCOUNT_PICKER_STATUS"),
            ("connected-js-durable-storage.sh", "durable-storage", "FIXTURE_DURABLE_STATUS"),
            ("connected-js-shared-app.sh", "shared-app", "FIXTURE_SHARED_APP_STATUS"),
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
            "FIXTURE_HOTKEYS_STATUS": str(hotkeys),
            "FIXTURE_HOTKEYS_CHECKER_STATUS": str(hotkeys_checker),
            "FIXTURE_HOTKEYS_RUN_ID": "js2884-run-1-hotkeys-fixture",
            "FIXTURE_OMIT_HOTKEYS_JUNIT": "1" if omit_hotkeys_junit else "0",
            "FIXTURE_SETTINGS_STATUS": str(settings),
            "FIXTURE_ACCOUNT_SYNC_STATUS": str(account_sync),
            "FIXTURE_ACCOUNT_PICKER_STATUS": str(account_picker),
            "FIXTURE_DURABLE_STATUS": str(durable),
            "FIXTURE_SHARED_APP_STATUS": str(shared_app),
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
        expected_hotkeys_checker = (
            42 if omit_hotkeys_junit else 43 if hotkeys != 0 else hotkeys_checker
        )
        expected_hotkeys_junit = 0 if expected_hotkeys_checker == 0 else 1
        expected_summary = (
            f"Packaged API 35 lane statuses: smoke={smoke} lifecycle={lifecycle} "
            f"usage-ports={usage} files={files} composer={composer} composer-junit-copy=0 "
            f"composer-junit=0 hotkeys={hotkeys} hotkeys-junit={expected_hotkeys_junit} "
            f"durable-storage={durable} settings={settings} account-sync={account_sync} account-picker={account_picker} shared-app={shared_app} key-vault={key_vault} "
            f"signed-upgrade={signed_upgrade} smoke-junit-copy={expected_copy}"
        )
        expected_exit = 1 if any((smoke, lifecycle, usage, files, composer, hotkeys,
                                  expected_hotkeys_junit, durable, settings, account_sync, account_picker, shared_app, key_vault,
                                  signed_upgrade, expected_copy)) else 0
        if result.returncode != expected_exit or expected_summary not in result.stdout:
            raise AssertionError(
                f"{label}: wrapper did not preserve its lane statuses: exit={result.returncode}, "
                f"stdout={result.stdout!r}, stderr={result.stderr!r}"
            )
        trace_lines = trace.read_text().splitlines()
        if [line.split("\t", 1)[0] for line in trace_lines] != [
            "smoke", "lifecycle", "usage-ports", "files", "durable-storage", "composer", "hotkeys",
            "hotkeys-check", "settings", "account-sync", "account-picker", "shared-app", "key-vault", "signed-upgrade",
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
        expected_hotkeys_args = "--suffix i2884ci --port 2243 --session-prefix js2884-run-1"
        if trace_lines[6] != f"hotkeys\t{hotkeys}\t{expected_hotkeys_args}":
            raise AssertionError(
                f"{label}: Fast Keys lane arguments/status were not preserved: {trace_lines[6]!r}"
            )
        expected_hotkeys_results = (
            "--results-dir android/app/build/outputs/js-hotkeys/js2884-run-1-hotkeys-fixture"
        )
        if trace_lines[7] != f"hotkeys-check\t{expected_hotkeys_results}":
            raise AssertionError(
                f"{label}: Fast Keys result checker was not given the unique same-run report: {trace_lines[7]!r}"
            )
        hotkeys_evidence = (
            fake_repo / "android/app/build/outputs/js-hotkeys/js2884-run-1-hotkeys-fixture"
        )
        if not (hotkeys_evidence / "hotkeys-run-metadata.txt").is_file():
            raise AssertionError(f"{label}: Fast Keys runner did not create run metadata")
        if (hotkeys_evidence / "TEST-fastkeys.xml").is_file() == omit_hotkeys_junit:
            raise AssertionError(f"{label}: Fast Keys JUnit fixture did not match the requested report state")
        if trace_lines[8] != f"settings\t{settings}\t--suffix i2855ci --port 2222 --container pocketshell-test-agents --run-id js2861s-run-1 --test-only":
            raise AssertionError(f"{label}: settings lane arguments/status were not preserved: {trace_lines[8]!r}")
        if trace_lines[9] != f"account-sync\t{account_sync}\t--suffix i2855ci --run-id js3020-run-1 --test-only":
            raise AssertionError(f"{label}: account-sync lane arguments/status were not preserved: {trace_lines[9]!r}")
        if trace_lines[10] != f"account-picker\t{account_picker}\t--suffix i2855ci --run-id js3063-run-1 --test-only":
            raise AssertionError(f"{label}: account-picker lane arguments/status were not preserved: {trace_lines[10]!r}")
        expected_upgrade_args = "--port 2244 --container pocketshell-test-agents-2244 --run-id up2926-run-1"
        if trace_lines[-1] != f"signed-upgrade\t{signed_upgrade}\t{expected_upgrade_args}":
            raise AssertionError(f"{label}: signed-upgrade lane arguments/status were not preserved: {trace_lines[-1]!r}")
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
exercise_packaged_lanes("Fast Keys failure is fail-closed", hotkeys=29)
exercise_packaged_lanes("Fast Keys result check failure is fail-closed", hotkeys_checker=31)
exercise_packaged_lanes("missing Fast Keys JUnit is fail-closed", omit_hotkeys_junit=True)
exercise_packaged_lanes("settings failure is fail-closed", settings=27)
exercise_packaged_lanes("account-sync failure is fail-closed", account_sync=33)
exercise_packaged_lanes("account-picker failure is fail-closed", account_picker=35)
exercise_packaged_lanes("durable-storage failure is fail-closed", durable=31)
exercise_packaged_lanes("shared-app failure is fail-closed", shared_app=27)
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


def journey_method(source: str, name: str) -> str:
    declaration = re.search(
        rf"(?m)^    (?:public|private|protected)\s+[^\n]*\b{re.escape(name)}\s*\(",
        source,
    )
    if declaration is None:
        raise AssertionError(f"Usage/Ports journey is missing {name}()")
    following = re.search(
        r"(?m)^    (?:public|private|protected)\s+|^    @(?:Before|After|Test)\b",
        source[declaration.end():],
    )
    end = declaration.end() + following.start() if following is not None else len(source)
    return source[declaration.start():end]


def require_usage_ports_composer_contract(source: str) -> None:
    primary = journey_method(source, "runUsageAndPortForwardingPoliciesUseDockerAndNativePlugin")
    after = journey_method(source, "closeShell")
    strict_stop = journey_method(source, "stopHttpServerStrictly")
    send = journey_method(source, "sendComposerCommandAndAwaitMarker")
    opener = journey_method(source, "openHomeLiveComposerAndAwaitConnectedTransport")
    physical_open = journey_method(source, "openComposerWithPhysicalDraftTap")
    draft_tap = journey_method(source, "tapComposerDraftCenter")
    physical_tap = journey_method(source, "tapElementCenter")
    physical_launcher_open = journey_method(source, "openComposerWithPhysicalLauncherTap")
    tap_recorder = journey_method(source, "installComposerOpenTapRecorder")
    completed_tap = journey_method(source, "completedTrustedTapExpression")
    completed_draft_tap = journey_method(source, "completedTrustedDraftTapExpression")
    completed_launcher_tap = journey_method(source, "completedTrustedLauncherTapExpression")
    tap_layout_wait = journey_method(source, "awaitComposerDraftTapLayout")
    draft_tap_target = journey_method(source, "composerDraftTapTargetExpression")
    open_diagnostics = journey_method(source, "readComposerOpenState")
    visible = journey_method(source, "visibleComposerExpression")

    for label, open_method in (("Home route opener", opener), ("physical-tap opener", physical_open),
                               ("physical launcher opener", physical_launcher_open)):
        if re.search(r"(?i)\b(?:send[A-Za-z0-9_]*|write[A-Za-z0-9_]*|setValue|clear[A-Za-z0-9_]*)\s*\(", open_method) \
           or re.search(r"(?i)\bclick\s*\([^;\n]*(?:send|submit)", open_method) \
           or re.search(r"(?i)\.value\s*=", open_method):
            raise AssertionError(f"{label} must not send, write terminal input, or mutate a draft")

    route_guard = 'assertEquals("HTTP cleanup must start from the Ports screen", "ports"'
    hidden_guard = 'assertEquals("the live Composer must be absent from the visible Usage/Ports route", "false"'
    route_at = primary.find(route_guard)
    hidden_at = primary.find(hidden_guard)
    route_record_at = primary.find("HTTP_CLEANUP_START route=ports composerVisible=false")
    stop_at = primary.find("stopHttpServerStrictly(")
    if min(route_at, hidden_at, route_record_at, stop_at) < 0 or not route_at < hidden_at < route_record_at < stop_at:
        raise AssertionError("primary cleanup must prove Ports + hidden Composer immediately before strict cleanup")
    cleanup_window = primary[primary.find("int scansBeforeStop"):stop_at]
    if "click(\"[aria-label='PocketShell home']\")" in cleanup_window:
        raise AssertionError("primary journey opens Home before invoking cleanup from Usage/Ports")
    if "document.querySelector('.app-shell')?.dataset.sshPhase ?? ''" not in primary[hidden_at:route_record_at]:
        raise AssertionError("primary cleanup setup must keep a live selected PTY while the Composer is hidden")

    start_marker_at = primary.find('String serverStartedMarker = marker(runId, "HTTP_STARTED")')
    settings_at = primary.find('click("[aria-label=\'Settings\']")')
    start_send_at = primary.find("sendComposerCommandAndAwaitMarker(", start_marker_at, settings_at)
    if min(start_marker_at, settings_at, start_send_at) < 0 or not start_marker_at < start_send_at < settings_at:
        raise AssertionError("HTTP fixture startup must use the packaged Composer before opening Settings")
    start_send = primary[start_send_at:settings_at]
    if "serverStartedMarker" not in start_send or '"start test HTTP service", sessionTag, "HTTP_START"' not in start_send:
        raise AssertionError("HTTP fixture startup must use its start marker, selected session, and HTTP_START evidence phase")
    if "sendCommandAndAwaitMarker(" in primary[start_marker_at:settings_at]:
        raise AssertionError("HTTP fixture startup must not inject the command through native terminal input")

    for label, needle, haystack in (
        ("strict stop routes through the Composer sender", "sendComposerCommandAndAwaitMarker(", strict_stop),
        ("Composer sender opens Home when closed", "openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix, bestEffortAfterFailure);", send),
        ("Composer sender waits for visible state", "awaitJsTrue(visibleComposerExpression(), 15_000);", send),
        ("Composer sender waits for connected transport", "dataset.transportState === 'connected'", send),
        ("Composer sender drafts through the packaged Composer", 'setValue("[data-testid=prompt-draft]", command)', send),
        ("Composer sender submits through the packaged Composer", 'click(".composer-shared-controls .send")', send),
    ):
        if needle not in haystack:
            raise AssertionError(f"Usage/Ports cleanup is missing {label}")
    sender_order = (
        send.index("openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix, bestEffortAfterFailure);"),
        send.index("awaitJsTrue(visibleComposerExpression(), 15_000);"),
        send.index("dataset.transportState === 'connected'"),
        send.index('setValue("[data-testid=prompt-draft]", command)'),
        send.index('click(".composer-shared-controls .send")'),
    )
    if sender_order != tuple(sorted(sender_order)):
        raise AssertionError("cleanup must open and show Home Composer, await its connected transport, then send")

    for label, needle in (
        ("Home route action", 'click("[aria-label=\'PocketShell home\']")'),
        ("Home route readiness", "dataset.route === 'home'"),
        ("session list for reattach", '[data-testid=open-sessions]'),
        ("selected session lookup", "matchingSession"),
        ("selected session reattach", "[data-session-tag=\\\""),
        ("Prompt sheet state on entry", "boolean promptSheetOpenOnEntry = \"true\".equals(evalRaw(promptSheetOpenExpression()));"),
        ("Prompt sheet required closed when a phase starts", 'assertFalse("the Prompt sheet must be closed when " + eventPrefix'),
        ("physical launcher tap when the Prompt sheet is closed", "openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);"),
        ("physical draft target readiness", "awaitJsTrue(composerDraftTapReady, 15_000);"),
        ("Composer visibility and focus check", "boolean composerDraftFocused = "),
        ("physical open only when closed or unfocused", "if (!composerVisible || !composerDraftFocused) {"),
        ("native physical draft tap action", "openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);"),
        ("visible and focused Composer wait", "awaitJsTrue(composerReady, 15_000);"),
        ("focused Composer confirmation", "document.activeElement === document.querySelector('[data-testid=prompt-draft]')"),
        ("connected Composer wait", "dataset.transportState === 'connected'"),
    ):
        if needle not in opener:
            raise AssertionError(f"Usage/Ports closed-Composer opener is missing {label}")
    for label, needle in (
        ("draft snapshot before opening", "String draftBeforeOpen = evalString("),
        ("input counters snapshot before opening", "JSONObject inputBeforeOpen = terminalInputStats();"),
        ("draft unchanged after opening", "opening Home Composer must preserve the existing draft"),
        ("terminal writes unchanged after opening", "opening Home Composer must not write terminal input"),
        ("terminal failures unchanged after opening", "opening Home Composer must not add terminal input failures"),
        ("pending writes unchanged after opening", "opening Home Composer must leave terminal input pending count unchanged"),
    ):
        if needle not in send:
            raise AssertionError(f"Usage/Ports Composer sender is missing {label}")
    opener_live_ready_at = opener.find("dataset.enabled === 'true'")
    opener_launcher_at = opener.find("openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);")
    opener_tap_ready_at = opener.find("awaitJsTrue(composerDraftTapReady, 15_000);")
    opener_action_at = opener.find("openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);")
    opener_visible_at = opener.find("awaitJsTrue(composerReady, 15_000);")
    opener_focus_at = opener.find("String composerReady = visibleComposerExpression()", opener_action_at)
    opener_connected_at = opener.find("dataset.transportState === 'connected'")
    if min(opener_live_ready_at, opener_launcher_at, opener_tap_ready_at, opener_action_at, opener_visible_at,
           opener_focus_at, opener_connected_at) < 0 \
       or not opener_live_ready_at < opener_launcher_at < opener_tap_ready_at < opener_action_at < opener_focus_at < opener_visible_at < opener_connected_at:
        raise AssertionError("Home Composer must become physically tappable, open, and focus before connected transport")
    if "composerDraftTapTargetExpression()" not in opener or "elementFromPoint" not in draft_tap_target \
       or "===draft" not in draft_tap_target:
        raise AssertionError("Home Composer tap readiness must verify the draft itself receives the center hit")
    composer_ready = opener[opener_focus_at:opener_visible_at]
    if "document.activeElement === document.querySelector('[data-testid=prompt-draft]')" not in composer_ready:
        raise AssertionError("mobile Composer readiness must require focus on the Prompt draft")
    if not send.index("String draftBeforeOpen = evalString(") < send.index(
        "openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix, bestEffortAfterFailure);") < send.index(
            "opening Home Composer must preserve the existing draft") < send.index(
                "opening Home Composer must not write terminal input") < send.index(
                    'setValue("[data-testid=prompt-draft]", command)'):
        raise AssertionError("opener must preserve draft and terminal input state before cleanup drafts its command")
    if "tapComposerDraftCenter();" not in physical_open \
       or "event.isTrusted===true&&event.targetIsDraft===true" not in physical_open \
       or 'assertTrue("opening the Composer must follow a trusted Android pointer-down on its draft; before="' not in physical_open:
        raise AssertionError("mobile Composer must open through the physical draft tap and confirm its trusted pointer event")
    retry_loop = "for (int attempt = 1; attempt <= 3; attempt++)"
    retry_guard = "if (attempt < 3) {"
    retry_wait = "after = awaitComposerDraftTapLayout(tap);"
    retry_positions = (
        physical_open.find("tap = tapComposerDraftCenter();"),
        physical_open.find(retry_guard),
        physical_open.find(retry_wait),
    )
    if (
        retry_loop not in physical_open
        or min(retry_positions) < 0
        or retry_positions != tuple(sorted(retry_positions))
    ):
        raise AssertionError("mobile Composer tap must allow at most three attempts and stabilize fresh bounds before each retry")
    if 'trustedDraftTapComplete = "true".equals(evalRaw(completedTrustedDraftTapExpression()));' not in physical_open \
       or 'assertTrue("opening the Composer must record a completed trusted pointerdown/pointerup/click on its draft;' not in physical_open:
        raise AssertionError("mobile Composer open must require its completed trusted click proof")
    if 'assertTrue("the completed physical draft tap must leave the Prompt draft focused;' not in physical_open:
        raise AssertionError("mobile Composer open must preserve its focused-draft assertion")
    tap_event_trace = 'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");'
    if (
        tap_event_trace not in physical_open
        or physical_open.find(tap_event_trace) > physical_open.find('assertTrue("opening the Composer must follow a trusted Android pointer-down')
        or '"; events=" + tapEvents' not in physical_open
    ):
        raise AssertionError("mobile Composer tap failure and success evidence must retain its complete pointer event trace")
    if "for(const type of ['pointerdown','pointerup','click','keydown','keypress','keyup'])" not in tap_recorder:
        raise AssertionError("mobile Composer tap recorder must observe pointerdown, pointerup, and click")
    for event in ("pointerdown", "pointerup", "click"):
        if f"type!=='{event}'" not in completed_tap:
            raise AssertionError(f"mobile Composer completed-tap proof must require {event}")
    for proof in (
        "event.isTrusted===true",
        "event.target===draft",
        "let upIndex=clickIndex-1",
        "let downIndex=upIndex-1",
        "down.pointerId===up.pointerId",
        "click.isTrusted!==true",
        '"||click." + targetFlag + "!==true)',
        '"&&down.isTrusted===true&&down." + targetFlag + "===true"',
    ):
        if proof not in tap_recorder + completed_tap:
            raise AssertionError(f"mobile Composer completed-tap proof is missing {proof}")
    if 'return completedTrustedTapExpression("targetIsDraft");' not in completed_draft_tap \
       or "if(clicks.length!==1)return false;" not in completed_launcher_tap \
       or "!(click.detail>=1)" not in completed_launcher_tap \
       or "targetIsLauncher:!!event.target?.closest?.('[data-testid=prompt-composer-launcher]')" not in tap_recorder:
        raise AssertionError("mobile Composer completed-tap proofs must bind to the draft and the launcher respectively")
    # The structural no-scripted-activation rule lives in the ordering gate;
    # reuse it so this contract cannot drift back to a list of literals.
    try:
        composer_order_gate.validate_launcher(source)
    except composer_order_gate.GateFailure as error:
        raise AssertionError(f"Usage/Ports must open Prompt by a physical launcher tap only: {error}") from error
    for needle in (
        "JSONObject stableLayout = awaitPromptLauncherTapLayout(taps);",
        "tap = tapPromptLauncherCenter();",
        'trustedLauncherTapComplete = "true".equals(evalRaw(completedTrustedLauncherTapExpression()));',
        'assertTrue("opening Prompt must record a completed trusted pointerdown/pointerup/click on its launcher; "',
        'assertTrue("the completed physical launcher tap must open the Prompt sheet; " + evidence, promptSheetOpen);',
        "if (trustedLauncherClickSeen) {",
        'throw new AssertionError("a completed trusted launcher click did not open the Prompt sheet; "',
        'assertEquals("exactly one trusted click may reach the Prompt launcher; " + evidence, 1, launcherClicks);',
        'assertEquals("no keyboard event may target the Prompt launcher; " + evidence, 0, keyboardLauncherEvents);',
        'assertEquals("the Prompt launcher must receive no scripted (untrusted) events; " + evidence,',
    ):
        if needle not in physical_launcher_open:
            raise AssertionError(f"physical Prompt launcher open is missing {needle}")
    if "SystemClock.sleep(" in physical_tap:
        raise AssertionError("mobile Composer tap must not sleep between measuring and tapping")
    if "nativeState.optBoolean(\"imeVisible\")" not in tap_layout_wait \
       or "stableSamples >= 2" not in tap_layout_wait \
       or "SystemClock.uptimeMillis() - startedAt >= 200" not in tap_layout_wait \
       or "composerDraftTapTargetExpression()" not in tap_layout_wait:
        raise AssertionError("mobile Composer retry must wait for stable IME-visible draft bounds")
    shared_tap = (repository_root / "android/app/src/androidTest/java/com/pocketshell/app/smoke/PhysicalTap.java").read_text()
    if "PhysicalTap.tap(screen[0], screen[1])" not in physical_tap \
       or "InputDevice.SOURCE_TOUCHSCREEN" not in shared_tap \
       or "MotionEvent.TOOL_TYPE_FINGER" not in shared_tap \
       or "MotionEvent.ACTION_DOWN" not in shared_tap or "MotionEvent.ACTION_UP" not in shared_tap:
        raise AssertionError("mobile Composer open action must inject Android touchscreen down and up events")
    if 'throw new AssertionError("Android touchscreen tap must be injected: down="' not in shared_tap:
        raise AssertionError("mobile Composer open action must require both touchscreen events to be injected")
    if 'tapElementCenter("[data-testid=prompt-draft]", "Composer draft", false)' not in draft_tap \
       or "centerHitIsTarget:hit===target" not in physical_tap or "draftCenterHitIsDraft" not in open_diagnostics:
        raise AssertionError("mobile Composer tap target must resolve to the Prompt draft")
    if 'assertTrue(label + " center is intercepted by another DOM element: " + point' not in physical_tap:
        raise AssertionError("mobile Composer touch point must not be intercepted by another DOM element")
    for label, needle in (
        ("Home live surface", "shell.dataset.homeSurface==='live'"),
        ("live PTY", "shell.dataset.sshPhase==='live'"),
        ("on-screen bounds", "composer.getBoundingClientRect()"),
        ("visible CSS state", "style.visibility!=='hidden'"),
        ("non-inert route", "composer.closest('[inert],[aria-hidden=true]')"),
    ):
        if needle not in visible:
            raise AssertionError(f"Usage/Ports Composer visibility proof is missing {label}")

    if "sendComposerCommandAndAwaitMarker(" not in after or "sendCommandAndAwaitMarker(" in after:
        raise AssertionError("@After HTTP cleanup must use the Composer-backed sender and opener")
    if 'activeSessionTag, "HTTP_CLEANUP",\n                        /* bestEffortAfterFailure= */ true)' not in after:
        raise AssertionError("@After cleanup must retain its HTTP_CLEANUP evidence phase")
    if "sendComposerCommandAndAwaitMarker(" not in strict_stop or "sendCommandAndAwaitMarker(" in strict_stop:
        raise AssertionError("strict HTTP process stop must use the Composer-backed sender and opener")
    if 'sessionTag, "HTTP_CLEANUP",\n                /* bestEffortAfterFailure= */ false)' not in strict_stop:
        raise AssertionError("strict process stop must retain its HTTP_CLEANUP evidence phase")


import importlib.util

_order_gate_spec = importlib.util.spec_from_file_location(
    "composer_order_gate", repository_root / "scripts/check-js-usage-ports-composer-order.py")
composer_order_gate = importlib.util.module_from_spec(_order_gate_spec)
_order_gate_spec.loader.exec_module(composer_order_gate)

usage_ports_runner = (repository_root / "scripts/connected-js-usage-ports.sh").read_text()
if '"$ROOT_DIR/scripts/check-js-usage-ports-results.py" --results-dir "$RESULTS_DIR" \\\n' \
   '  --launcher-logcat "$LIVE_ASSET_LOGCAT" --run-id "$RUN_ID"' not in usage_ports_runner:
    raise AssertionError("the Usage/Ports runner must require same-run physical Prompt launcher evidence")
print("PASS: Usage/Ports runner requires same-run physical Prompt launcher evidence")

require_usage_ports_composer_contract(usage_ports_journey)


def expect_usage_ports_contract_rejection(label: str, damaged: str) -> None:
    try:
        require_usage_ports_composer_contract(damaged)
    except (AssertionError, ValueError):
        print(f"PASS: {label} fails the closed-Composer route contract")
    else:
        raise AssertionError(f"closed-Composer route contract missed {label}")


sender_source = journey_method(usage_ports_journey, "sendComposerCommandAndAwaitMarker")
opener_source = journey_method(usage_ports_journey, "openHomeLiveComposerAndAwaitConnectedTransport")
physical_open_source = journey_method(usage_ports_journey, "openComposerWithPhysicalDraftTap")
after_source = journey_method(usage_ports_journey, "closeShell")
primary_source = journey_method(usage_ports_journey, "runUsageAndPortForwardingPoliciesUseDockerAndNativePlugin")
expect_usage_ports_contract_rejection(
    "removing the Home/live Composer opener",
    usage_ports_journey.replace(
        sender_source,
        sender_source.replace("openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix, bestEffortAfterFailure);", "", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "removing the route-opening action",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace('click("[aria-label=\'PocketShell home\']");', "", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "removing the physical mobile Composer draft tap",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace("openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);", "", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "removing the physical Prompt launcher tap",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace("openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);", "", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "replacing the physical Prompt launcher tap with a DOM click",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace("openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);",
                              "click(PROMPT_LAUNCHER_SELECTOR);", 1),
        1,
    ),
)
launcher_open_source = journey_method(usage_ports_journey, "openComposerWithPhysicalLauncherTap")
expect_usage_ports_contract_rejection(
    "a scripted .click() on the Prompt launcher before the sheet check",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace(
            "        boolean promptSheetOpenOnEntry",
            "        evalString(\"document.querySelector('[data-testid=prompt-composer-launcher]')?.click()\");\n"
            "        boolean promptSheetOpenOnEntry",
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "a scripted dispatchEvent on the Prompt launcher",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace(
            "        boolean promptSheetOpenOnEntry",
            "        evalString(\"document.querySelector('[data-testid=prompt-composer-launcher]')"
            "?.dispatchEvent(new MouseEvent('click',{bubbles:true}))\");\n"
            "        boolean promptSheetOpenOnEntry",
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "reviewer mutant B: launcher .focus() plus an injected KeyEvent ENTER after the tap",
    usage_ports_journey.replace(
        '            taps.put(tap.put("stableLayout", stableLayout));\n',
        '            taps.put(tap.put("stableLayout", stableLayout));\n            settleLauncherFocus();\n',
        1,
    ).replace(
        "    private JSONObject awaitPromptLauncherTapLayout(",
        "    private void settleLauncherFocus() throws Exception {\n"
        "        evalString(\"document.querySelector(\" + JSONObject.quote(PROMPT_LAUNCHER_SELECTOR) + \")?.focus()\");\n"
        "        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();\n"
        "        long now = SystemClock.uptimeMillis();\n"
        "        automation.injectInputEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN,\n"
        "                android.view.KeyEvent.KEYCODE_ENTER, 0), true);\n"
        "    }\n\n"
        "    private JSONObject awaitPromptLauncherTapLayout(",
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "an injected KeyEvent ENTER without focusing the launcher",
    usage_ports_journey.replace(
        '            taps.put(tap.put("stableLayout", stableLayout));\n',
        '            taps.put(tap.put("stableLayout", stableLayout));\n'
        "            InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent("
        "new android.view.KeyEvent(0, 66), true);\n",
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "retrying after a completed trusted launcher click left the sheet closed",
    usage_ports_journey.replace(
        launcher_open_source,
        launcher_open_source.replace("            if (trustedLauncherClickSeen) {\n", "            if (false) {\n", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "removing the visible physical draft target wait",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace(
            "        String composerDraftTapReady = visibleComposerExpression()\n"
            "                + \" && \" + composerDraftTapTargetExpression();\n"
            "        awaitJsTrue(composerDraftTapReady, 15_000);\n",
            "        // no wait for the Home draft touch target\n",
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "bypassing the physical mobile Composer draft tap",
    usage_ports_journey.replace(
        opener_source,
        opener_source.replace("if (!composerVisible || !composerDraftFocused) {", "if (false) {", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "dropping the trusted physical pointer-down assertion",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            'assertTrue("opening the Composer must follow a trusted Android pointer-down on its draft; before="',
            'assertTrue("Composer pointer-down proof removed; before="',
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "dropping completed trusted pointerup and click proof",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            'trustedDraftTapComplete = "true".equals(evalRaw(completedTrustedDraftTapExpression()));',
            'draftFocused = "true".equals(evalRaw(ready));',
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "removing the fresh physical tap retry after IME resize",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace("after = awaitComposerDraftTapLayout(tap);", "after = readComposerOpenState(\"after-tap\");", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "limiting the physical tap retry cap to two attempts",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            "for (int attempt = 1; attempt <= 3; attempt++)",
            "for (int attempt = 1; attempt <= 2; attempt++)",
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "allowing more than three physical draft tap attempts",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            "for (int attempt = 1; attempt <= 3; attempt++)",
            "for (int attempt = 1; attempt <= 4; attempt++)",
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "omitting the recorded pointer event trace",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");',
            'String tapEvents = "[]";',
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "allowing completed click proof to combine separate taps",
    usage_ports_journey.replace(
        'let upIndex=clickIndex-1;',
        'let upIndex=0;',
        1,
    ),
)

delayed_composer_open = opener_source.replace("openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);", "", 1)
connected_wait_end = delayed_composer_open.index("15_000);", delayed_composer_open.index("dataset.transportState === 'connected'")) + len("15_000);")
delayed_composer_open = (
    delayed_composer_open[:connected_wait_end]
    + "\n            openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);"
    + delayed_composer_open[connected_wait_end:]
)
expect_usage_ports_contract_rejection(
    "physically opening the mobile Composer after its connected transport wait",
    usage_ports_journey.replace(opener_source, delayed_composer_open, 1),
)
late_after_send = usage_ports_journey.replace(
    opener_source,
    opener_source.replace("openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);", "", 1),
    1,
)
late_sender_source = journey_method(late_after_send, "sendComposerCommandAndAwaitMarker")
late_after_send = late_after_send.replace(
    late_sender_source,
    late_sender_source.replace(
        'click(".composer-shared-controls .send");',
        'click(".composer-shared-controls .send");\n        openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);',
        1,
    ),
    1,
)
expect_usage_ports_contract_rejection(
    "physically opening the mobile Composer after sending cleanup",
    late_after_send,
)
expect_usage_ports_contract_rejection(
    "sending cleanup while opening the mobile Composer",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            "tapComposerDraftCenter();",
            'click(".composer-shared-controls .send");\n        tapComposerDraftCenter();',
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "mutating the Prompt draft while opening the mobile Composer",
    usage_ports_journey.replace(
        physical_open_source,
        physical_open_source.replace(
            "tapComposerDraftCenter();",
            'setValue("[data-testid=prompt-draft]", "unexpected command");\n        tapComposerDraftCenter();',
            1,
        ),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "falsifying the Ports hidden-Composer precondition",
    usage_ports_journey.replace(
        'assertEquals("HTTP cleanup must start from the Ports screen", "ports"',
        'assertEquals("HTTP cleanup must start from the Ports screen", "home"',
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "routing @After through direct terminal input",
    usage_ports_journey.replace(
        after_source,
        after_source.replace("sendComposerCommandAndAwaitMarker(", "sendCommandAndAwaitMarker(", 1),
        1,
    ),
)
expect_usage_ports_contract_rejection(
    "routing HTTP fixture startup through direct terminal input",
    usage_ports_journey.replace(
        primary_source,
        primary_source.replace(
            'sendComposerCommandAndAwaitMarker(\n                "python3 -m http.server',
            'sendCommandAndAwaitMarker(\n                "python3 -m http.server',
            1,
        ),
        1,
    ),
)

print("PASS: rewrite composer and Usage/Ports CI run on API 35, validate exact JUnit, and upload run-scoped evidence")
print("PASS: packaged lanes execute fail-closed in one shell and preserve the captured Node/pnpm runtime")
print("PASS: Usage/Ports regression opens and verifies the focused Home Composer before cleanup and @After fallback")
PY
