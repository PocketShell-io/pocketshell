#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"

python3 - "$ROOT_DIR" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

root = Path(sys.argv[1])
workflow_path = root / ".github/workflows/js-first-rewrite.yml"
runner_path = root / "scripts/connected-js-composer-docker.sh"
workflow = workflow_path.read_text()
runner = runner_path.read_text()


def step(source: str, name: str) -> str:
    start = source.index(f"- name: {name}")
    end = source.find("\n      - name:", start + 8)
    return source[start:] if end < 0 else source[start:end]


def require(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)


def validate(source: str, runner_source: str) -> None:
    dispatch = step(source, "Check JS-first connected-test dispatch and lock contracts")
    require("tests/scripts/js-first-rewrite-composer-gate-test.sh" in dispatch,
            "workflow does not run this composer gate self-test")

    fixture = step(source, "Start isolated Docker agents lane for the composer journey")
    require("export_agents_fixture_version_for_run 0 android/app/build/outputs/apk/debug/app-debug.apk" in fixture,
            "composer Docker fixture is not stamped from the APK under test")
    require("scripts/agents-pool.sh up 2245" in fixture,
            "workflow does not start and wait for the isolated composer Docker fixture")

    action = step(source, "Run packaged JS smoke suite on API 35")
    smoke = action.index("scripts/connected-js-smoke.sh --suffix i2855ci --test-only")
    lifecycle = action.index("scripts/connected-js-lifecycle.sh")
    composer = action.index("scripts/connected-js-composer-docker.sh")
    require(smoke < lifecycle < composer, "the exact composer journey is not run after the existing API 35 lanes")
    require(action.count("scripts/connected-js-composer-docker.sh") == 1,
            "the composer journey invocation is missing or duplicated")
    require('--port 2245 --session-prefix "$session_prefix" --suffix i2857ci' in action,
            "composer journey is not bound to the isolated fixture and app id")
    require('session_prefix="js2857-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"' in action,
            "composer journey lacks a unique workflow-run session prefix")
    require('export POCKETSHELL_JS_COMPOSER_EVIDENCE_DIR="$GITHUB_WORKSPACE/android/app/build/outputs/js-composer/$session_prefix"' in action,
            "composer artifacts are not directed into the uploadable run-scoped output tree")
    action_lines = action.splitlines()
    script_index = next(index for index, line in enumerate(action_lines)
                        if re.match(r"\s+script:\s*\|\s*$", line))
    script_indent = len(action_lines[script_index]) - len(action_lines[script_index].lstrip())
    script_lines = []
    for line in action_lines[script_index + 1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= script_indent:
            break
        script_lines.append(line[script_indent + 2:]
                            if line.startswith(" " * (script_indent + 2)) else "")
    subprocess.run(["bash", "-n"], input="\n".join(script_lines), text=True, check=True)
    subprocess.run(["bash", "-n", str(runner_path)], check=True)
    require('POCKETSHELL_JS_COMPOSER_EVIDENCE_DIR:-$tmp_root/pocketshell-js2857-$SESSION_BASE' in runner_source,
            "composer runner does not support the workflow evidence output override")

    result_guard = step(source, "Assert the packaged JS composer journey executed exactly once")
    require("if: always()" in result_guard, "composer result guard does not run after an earlier lane failure")
    self_test = result_guard.index("scripts/check-js-composer-journey-results.py --self-test")
    result_check = result_guard.index("--results-dir android/app/build/outputs/androidTest-results/connected/debug")
    require(self_test < result_check, "exact composer result guard is absent or does not run its self-test")

    diagnostics = step(source, "Collect isolated composer fixture diagnostics")
    require("if: always()" in diagnostics and "docker logs pocketshell-test-agents-2245" in diagnostics,
            "the isolated composer fixture diagnostics are not collected on failures")

    upload = step(source, "Upload packaged JS composer run evidence")
    require("if: always()" in upload and "uses: actions/upload-artifact@v6" in upload,
            "composer evidence upload does not run on failures")
    require("if-no-files-found: error" in upload,
            "missing composer evidence is not fail-closed")
    require("android/app/build/outputs/js-composer/" in upload,
            "upload omits the run-scoped folder containing all seven extracted artifacts")
    require("android/app/build/outputs/androidTest-results/connected/debug/TEST-*.xml" in upload,
            "composer JUnit is not uploaded")
    require("${{ github.run_id }}-${{ github.run_attempt }}" in upload,
            "composer artifact name is not unique to the workflow run")

    cleanup = step(source, "Stop isolated composer Docker agents lane")
    require("if: always()" in cleanup and "scripts/agents-pool.sh down 2245" in cleanup,
            "isolated composer fixture is not stopped after the job")
    require(source.index("Start isolated Docker agents lane for the composer journey")
            < source.index("Run packaged JS smoke suite on API 35")
            < source.index("Assert the packaged JS composer journey executed exactly once")
            < source.index("Upload packaged JS composer run evidence")
            < source.index("Stop isolated composer Docker agents lane"),
            "composer fixture, run, result guard, upload, and teardown are out of order")


validate(workflow, runner)
print("PASS: rewrite workflow starts the APK-matched isolated fixture and runs the exact API 35 composer journey")
print("PASS: composer result guard is fail-closed and all seven artifacts/JUnit/diagnostics are uploaded before teardown")

for label, damaged in (
    ("isolated fixture startup", workflow.replace("scripts/agents-pool.sh up 2245", "scripts/agents-pool.sh up 2244", 1)),
    ("composer invocation", workflow.replace("scripts/connected-js-composer-docker.sh", "scripts/connected-js-lifecycle.sh", 1)),
    ("exact result guard", workflow.replace("--results-dir android/app/build/outputs/androidTest-results/connected/debug", "--results-dir missing", 1)),
    ("artifact upload", workflow.replace("android/app/build/outputs/js-composer/", "android/app/build/outputs/other/", 1)),
    ("unconditional fixture teardown", workflow.replace("if: always()\n        run: scripts/agents-pool.sh down 2245", "run: scripts/agents-pool.sh down 2245", 1)),
):
    try:
        validate(damaged, runner)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the workflow contract")
    else:
        raise AssertionError(f"workflow contract did not detect missing {label}")
PY
