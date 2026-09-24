#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
RUNNER="$ROOT_DIR/scripts/connected-js-composer-docker.sh"
EXTRACTOR="$ROOT_DIR/scripts/extract-js-composer-artifacts.py"
TOOLCACHE_PRUNER="$ROOT_DIR/scripts/ci-emulator-prune-toolcache.sh"

[[ -f "$WORKFLOW" && -x "$RUNNER" && -f "$EXTRACTOR" && -x "$TOOLCACHE_PRUNER" ]] || {
  printf 'FAIL: rewrite composer gate inputs are missing\n' >&2
  exit 1
}

bash -n "$RUNNER"
python3 - "$WORKFLOW" "$RUNNER" "$EXTRACTOR" "$TOOLCACHE_PRUNER" <<'PY'
import ast
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

workflow_path, runner_path, extractor_path, toolcache_pruner_path = map(Path, sys.argv[1:])
workflow = workflow_path.read_text()
runner = runner_path.read_text()
disk_cleanup = (toolcache_pruner_path.parent / "ci-emulator-free-disk.sh").read_text()
ast.parse(extractor_path.read_text(), filename=str(extractor_path))
subprocess.run(["bash", "-n", str(toolcache_pruner_path)], check=True)
subprocess.run(["bash", "-n"], input=disk_cleanup, text=True, check=True)
if "scripts/ci-emulator-prune-toolcache.sh" not in disk_cleanup:
    raise AssertionError("disk preflight does not invoke the tested toolcache-preservation helper")


def require_contract(source: str) -> None:
    required = (
        ("isolated fixture", "scripts/agents-pool.sh up 2245"),
        ("composer runner", "scripts/connected-js-composer-docker.sh --suffix i2891ci --port 2245"),
        ("exact result guard", "scripts/check-js-composer-journey-results.py"),
        ("run-scoped artifact output", "android/app/build/outputs/js-composer/"),
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
    action_start = source.index("- name: Run packaged JS smoke suite on API 35")
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
    composer = source.index("scripts/connected-js-composer-docker.sh --suffix i2891ci --port 2245")
    lifecycle = source.index("scripts/connected-js-lifecycle.sh --suffix i2855ci --port 2222")
    if lifecycle >= composer:
        raise AssertionError("composer journey must run after the smoke/lifecycle portion of the API 35 script")
    guard_start = source.index("- name: Assert the packaged JS composer journey executed exactly once")
    guard_end = source.index("- name:", guard_start + 8)
    guard = source[guard_start:guard_end]
    if "if: always()" not in guard or "--results-dir android/app/build/outputs/androidTest-results/connected/debug" not in guard:
        raise AssertionError("the exact JUnit result guard must run after the emulator step even when it fails")
    upload_start = source.index("- name: Upload packaged JS composer run evidence")
    upload_end = source.index("- name:", upload_start + 8)
    upload = source[upload_start:upload_end]
    if "if: always()" not in upload or "if-no-files-found: error" not in upload:
        raise AssertionError("composer evidence upload must run always and fail if the bundle is absent")
    if "android/app/build/outputs/js-composer/" not in upload:
        raise AssertionError("composer artifact upload omits the run-scoped evidence directory")
    if "${{ github.run_id }}-${{ github.run_attempt }}" not in upload:
        raise AssertionError("composer artifact name must identify its workflow run and attempt")


require_contract(workflow)

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
        require_contract(damaged)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed removed {label}")

for label, damaged in (
    ("composer invocation", workflow.replace(
        '            scripts/connected-js-composer-docker.sh --suffix i2891ci --port 2245 --session-prefix "js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"\n',
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
        require_contract(damaged)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed a removed {label}")

# The emulator action executes this embedded block as Bash. Parse it here so
# quoting/continuations fail in the cheap contract job before an AVD is used.
section_start = workflow.index("- name: Run packaged JS smoke suite on API 35")
section_end = workflow.index("\n      - name:", section_start + 8)
section = workflow[section_start:section_end]
lines = section.splitlines()
script_index = next(index for index, line in enumerate(lines) if re.match(r"\s+script:\s*\|\s*$", line))
script_indent = len(lines[script_index]) - len(lines[script_index].lstrip())
script_lines = []
for line in lines[script_index + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= script_indent:
        break
    script_lines.append(line[script_indent + 2:] if line.startswith(" " * (script_indent + 2)) else "")
subprocess.run(["bash", "-n"], input="\n".join(script_lines), text=True, check=True)

# android-emulator-runner v2.37.0's parseScript splits the `script` input into
# individual lines and starts a separate `sh -c` for each one. Each child gets
# the action's process environment, including step-level env. Model that exact
# inheritance so the captured runtime is tested through the real composer
# command without inventing a missing-env failure.
action_lines = [line.strip() for line in script_lines if line.strip() and not line.strip().startswith("#")]
composer_command = next(
    line for line in action_lines if "scripts/connected-js-composer-docker.sh" in line
)
with tempfile.TemporaryDirectory(prefix="js rewrite action ") as temporary:
    action_root = Path(temporary)
    runtime_bin = action_root / "runtime bin"
    runtime_bin.mkdir()
    fake_pnpm = runtime_bin / "pnpm"
    fake_pnpm.write_text("#!/bin/sh\nprintf 'fixture-pnpm-ok\\n'\n")
    fake_pnpm.chmod(0o755)

    fake_repo = action_root / "repo"
    fake_runner = fake_repo / "scripts/connected-js-composer-docker.sh"
    fake_runner.parent.mkdir(parents=True)
    capture = action_root / "captured-runtime.txt"
    fake_runner.write_text(
        "#!/bin/sh\n"
        "printf '%s\\n' \"$PNPM\" \"$PATH\" >> \"$CAPTURE_RUNTIME\"\n"
        "\"$PNPM\" --version >> \"$CAPTURE_RUNTIME\"\n"
        "printf '%s\\n' \"$*\" >> \"$CAPTURE_RUNTIME\"\n"
    )
    fake_runner.chmod(0o755)

    subprocess.run(
        ["/bin/sh", "-c", composer_command],
        cwd=fake_repo,
        env={
            "PATH": f"{runtime_bin}:/usr/bin:/bin",
            "PNPM": str(fake_pnpm),
            "CAPTURE_RUNTIME": str(capture),
            "GITHUB_RUN_ID": "run",
            "GITHUB_RUN_ATTEMPT": "1",
        },
        check=True,
        text=True,
        capture_output=True,
    )
    captured_runtime = capture.read_text().splitlines()
    if captured_runtime != [
        str(fake_pnpm),
        f"{runtime_bin}:/usr/bin:/bin",
        "fixture-pnpm-ok",
        "--suffix i2891ci --port 2245 --session-prefix js2891-run-1",
    ]:
        raise AssertionError(
            "the isolated emulator action command did not pass its captured Node/pnpm runtime to the composer runner: "
            f"{captured_runtime!r}"
        )

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

print("PASS: rewrite composer CI invokes the isolated API 35 journey after lifecycle, validates exact JUnit, and uploads run-scoped evidence")
print("PASS: composer runner and embedded emulator workflow scripts parse successfully")
PY
