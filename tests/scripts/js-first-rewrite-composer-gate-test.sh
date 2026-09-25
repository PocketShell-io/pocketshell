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
        ("isolated fixture", "scripts/agents-pool.sh up 2245"),
        ("single packaged-lanes wrapper invocation", "script: scripts/ci-js-first-packaged-lanes.sh"),
        ("exact result guard", "scripts/check-js-composer-journey-results.py"),
        ("run-scoped artifact output", "android/app/build/outputs/js-composer/"),
        ("usage/ports result guard", "scripts/check-js-usage-ports-results.py"),
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
    composer = packaged_script.index("scripts/connected-js-composer-docker.sh")
    lifecycle = packaged_script.index("scripts/connected-js-lifecycle.sh")
    if not lifecycle < usage < composer:
        raise AssertionError("usage/ports must run after lifecycle and before the composer journey")
    if "--run-id \"js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}\"" not in packaged_script:
        raise AssertionError("usage/ports run identity is not forwarded to the packaged journey")
    for lane in ("smoke_status", "lifecycle_status", "usage_status", "composer_status", "copy_status"):
        if lane not in packaged_script:
            raise AssertionError(f"packaged wrapper does not aggregate {lane}")
    if "TEST-*.xml" not in packaged_script or "cp -a --" not in packaged_script:
        raise AssertionError("packaged wrapper must copy smoke JUnit evidence")
    if "android/app/build/outputs/js-smoke-results" not in packaged_script:
        raise AssertionError("packaged wrapper must save the smoke JUnit copy under its upload path")
    guard_start = source.index("- name: Assert packaged JS composer and Usage/Ports journeys executed exactly once")
    guard_end = source.index("- name:", guard_start + 8)
    guard = source[guard_start:guard_end]
    if "if: always()" not in guard or "--results-dir android/app/build/outputs/androidTest-results/connected/debug" not in guard:
        raise AssertionError("the exact JUnit result guard must run after the emulator step even when it fails")
    if "scripts/check-js-usage-ports-results.py \\" not in guard or \
       'js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results' not in guard:
        raise AssertionError("the usage/ports result guard must check the preserved same-run JUnit report")
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

for label, damaged in (
    ("composer invocation", packaged_lanes.replace(
        "if scripts/connected-js-composer-docker.sh \\\n",
        "",
        1,
    )),
    ("composer artifact path", workflow.replace(
        "            android/app/build/outputs/js-composer/\n",
        "            android/app/build/outputs/other/\n",
        1,
    )),
):
    try:
        if label == "composer invocation":
            require_contract(workflow, damaged)
        else:
            require_contract(damaged, packaged_lanes)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed a removed {label}")

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
                            usage: int = 0, composer: int = 0, omit_junit: bool = False,
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
        fake_composer = fake_scripts / "connected-js-composer-docker.sh"
        fake_composer.write_text(
            "#!/bin/bash\n"
            "printf 'composer\\t%s\\t%s\\n' \"$FIXTURE_COMPOSER_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "printf '%s\\n%s\\n' \"$PNPM\" \"$PATH\" > \"$FIXTURE_RUNTIME_CAPTURE\"\n"
            "\"$PNPM\" --version >> \"$FIXTURE_RUNTIME_CAPTURE\"\n"
            "exit \"$FIXTURE_COMPOSER_STATUS\"\n"
        )
        fake_composer.chmod(0o755)

        env = {
            "PATH": f"{runtime_bin}:/usr/bin:/bin",
            "BASH_ENV": "",
            "PNPM": str(fake_pnpm),
            "FIXTURE_TRACE": str(trace),
            "FIXTURE_RUNTIME_CAPTURE": str(runtime_capture),
            "FIXTURE_SMOKE_STATUS": str(smoke),
            "FIXTURE_LIFECYCLE_STATUS": str(lifecycle),
            "FIXTURE_USAGE_STATUS": str(usage),
            "FIXTURE_COMPOSER_STATUS": str(composer),
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
            f"usage-ports={usage} composer={composer} smoke-junit-copy={expected_copy}"
        )
        expected_exit = 1 if any((smoke, lifecycle, usage, composer, expected_copy)) else 0
        if result.returncode != expected_exit or expected_summary not in result.stdout:
            raise AssertionError(
                f"{label}: wrapper did not preserve its lane statuses: exit={result.returncode}, "
                f"stdout={result.stdout!r}, stderr={result.stderr!r}"
            )
        trace_lines = trace.read_text().splitlines()
        if [line.split("\t", 1)[0] for line in trace_lines] != ["smoke", "lifecycle", "usage-ports", "composer"]:
            raise AssertionError(f"{label}: wrapper failed to execute every lane in order: {trace_lines!r}")
        if "--run-id js2861-run-1" not in trace_lines[1]:
            raise AssertionError(f"{label}: lifecycle run identity was not forwarded: {trace_lines[1]!r}")
        if "--run-id js2859-run-1" not in trace_lines[2]:
            raise AssertionError(f"{label}: usage/ports run identity was not forwarded: {trace_lines[2]!r}")
        if "--session-prefix js2891-run-1" not in trace_lines[3]:
            raise AssertionError(f"{label}: composer session identity was not forwarded: {trace_lines[3]!r}")
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
exercise_packaged_lanes("composer failure is fail-closed", composer=23)
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

print("PASS: rewrite composer and Usage/Ports CI run on API 35, validate exact JUnit, and upload run-scoped evidence")
print("PASS: packaged lanes execute fail-closed in one shell and preserve the captured Node/pnpm runtime")
PY
