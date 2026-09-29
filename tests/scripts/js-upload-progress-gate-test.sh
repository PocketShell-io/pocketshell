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
runner_path = root / "scripts/connected-js-upload-progress-docker.sh"
journey_path = root / "android/app/src/androidTest/java/com/pocketshell/app/smoke/J20ComposerUploadProgressJourney.java"
result_guard_path = root / "scripts/check-js-upload-progress-results.py"
oracle_path = root / "scripts/verify-j20-upload-host-oracle.py"
workflow = workflow_path.read_text(encoding="utf-8")
runner = runner_path.read_text(encoding="utf-8")
journey = journey_path.read_text(encoding="utf-8")
result_guard = result_guard_path.read_text(encoding="utf-8")
oracle = oracle_path.read_text(encoding="utf-8")


def step(source: str, name: str) -> str:
    start = source.index(f"- name: {name}")
    end = source.find("\n      - name:", start + 8)
    return source[start:] if end < 0 else source[start:end]


def require(condition: bool, label: str) -> None:
    if not condition:
        raise AssertionError(label)


start_proxy = step(workflow, "Start isolated Toxiproxy lane for upload progress")
fixture = step(workflow, "Start isolated Docker agents lane for the composer journey")
action = step(workflow, "Run packaged JS smoke suite on API 35")
guard = step(workflow, "Assert the packaged J20 upload-progress journey executed exactly once")
feature_guard = step(workflow, "Qualify J20 through the JS feature-result checker")
upload = step(workflow, "Upload packaged JS composer run evidence")
teardown = step(workflow, "Stop isolated composer Docker agents lane")
unit_guard = step(workflow, "Check fail-closed feature journey qualification guard")

require("pocketshell_network_fault_fixture_up \"$GITHUB_WORKSPACE\" 2245" in start_proxy,
        "workflow does not start the isolated Toxiproxy fixture without recreating agents")
require("pocketshell_toxiproxy_api_port 2245" in start_proxy and "/proxies" in start_proxy,
        "workflow does not wait for the isolated Toxiproxy API")
require(workflow.index(fixture) < workflow.index(start_proxy) < workflow.index(action),
        "agents, isolated proxy, and API 35 connected suite are out of order")
require("scripts/connected-js-upload-progress-docker.sh" in action,
        "the active packaged API 35 runner does not invoke J20")
require('--agents-port 2245' in action and '--suffix i2857ci' in action,
        "J20 is not bound to the isolated APK-matched agents lane")
require('"i2929-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"' in action,
        "J20 has no run-scoped fixture/evidence id")
require(action.index("scripts/connected-js-upload-progress-docker.sh")
        < action.index("scripts/connected-js-composer-docker.sh"),
        "J20 must execute before the existing composer lane clears Gradle results")

require("if: always()" in guard, "the J20 result guard does not run after an earlier connected failure")
require("scripts/check-js-upload-progress-results.py --self-test" in guard,
        "the J20 result guard self-test is absent")
require("/junit\"" in guard and "--results-dir" in guard,
        "the J20 result guard does not consume the run-scoped connected-test XML copy")
require("if: always()" in feature_guard
        and "scripts/check-js-journey-results.py" in feature_guard
        and "--journey-class J20ComposerUploadProgressJourney" in feature_guard
        and "/junit\"" in feature_guard,
        "the active JS feature-result checker does not consume J20's actual connected-test XML")
require("android/app/build/outputs/js-upload-progress/" in upload,
        "the same-run J20 screens, logs, JUnit, and host oracle are not uploaded")
require("if: always()" in upload and "if-no-files-found: error" in upload,
        "J20 evidence upload is not fail-closed")
require(workflow.index(guard) < workflow.index(feature_guard) < workflow.index(upload) < workflow.index(teardown),
        "J20 result checking, evidence upload, and isolated fixture teardown are out of order")
require("check-js-upload-progress-results.py --self-test" in unit_guard
        and "verify-j20-upload-host-oracle.py --self-test" in unit_guard,
        "the locked unit gate omits J20 checker and host-oracle self-tests")

require("J20ComposerUploadProgressJourney" in runner and "connectedDebugAndroidTest" in runner,
        "J20 runner is not a packaged Android connected-test path")
require("-Pandroid.testInstrumentationRunnerArguments.sshPort=$PROXY_PORT" in runner
        and "-Pandroid.testInstrumentationRunnerArguments.agentsPort=$AGENTS_PORT" in runner
        and "-Pandroid.testInstrumentationRunnerArguments.toxiproxyApiPort=$TOXIPROXY_API_PORT" in runner,
        "J20 runner does not pass the lane's direct SSH, proxy, and API ports")
require("does not create or tear down Docker" in runner,
        "J20 runner may recreate or remove shared Docker state")
require("--results-dir \"$RESULTS_DIR\"" in runner
        and "junit/" in runner
        and "verify-j20-upload-host-oracle.py" in runner,
        "J20 runner does not check, preserve, and independently oracle the actual connected run")
subprocess.run(["bash", "-n", str(runner_path)], check=True)

require("awaitPositiveProgress()" in journey
        and "missing transport reporting must fail this journey" in journey
        and "all three SFTP uploads completed without a nonzero determinate bar" in journey,
        "the load-bearing J20 assertion is no longer mutation-sensitive to absent byte progress")
require("TOXIC_RATE_KBPS = 64" in journey
        and "capturePersistentScreenshot(\"mid-flight\"" in journey
        and "capturePersistentScreenshot(\"completed\"" in journey,
        "the same-run keyboard-open progress and completion screenshots are absent")
require("retainUploadsForExternalHostOracle = true" in journey,
        "the journey must retain successful payloads for the independent host process")
require("expected_payload_sha256" in oracle and "sha256sum --" in oracle
        and "-delete" in oracle and "cleanupVerified" in oracle,
        "the host-side oracle does not independently hash all files and verify cleanup")
require("REQUIRED_METHOD = \"aThreeFileUploadShowsTheBarMidFlightAndLeavesNoResidueAfterCompletion\"" in result_guard,
        "the J20 JUnit guard does not pin the exact named journey method")

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

print("PASS: J20 is in the active API 35 connected lane over its isolated Toxiproxy fixture")
print("PASS: the exact J20 XML method, keyboard screenshots, byte-progress mutation assertion, and independent host cleanup oracle are gated and uploaded")

for label, damaged in (
    ("J20 invocation", workflow.replace("scripts/connected-js-upload-progress-docker.sh", "scripts/connected-js-lifecycle.sh", 1)),
    ("exact J20 result XML", guard.replace("/junit", "/missing", 1)),
    ("J20 feature-result selection", feature_guard.replace("--journey-class J20ComposerUploadProgressJourney", "--journey-class J19HostFormImeJourney", 1)),
    ("run-scoped J20 artifact", upload.replace("android/app/build/outputs/js-upload-progress/", "android/app/build/outputs/other/", 1)),
    ("Toxiproxy setup", workflow.replace("pocketshell_network_fault_fixture_up", "pocketshell_agents_fixture_up", 1)),
):
    try:
        if label == "J20 invocation":
            require("scripts/connected-js-upload-progress-docker.sh" in damaged,
                    "missing J20 invocation mutation was not detected")
        elif label == "exact J20 result XML":
            require("/junit\"" in damaged,
                    "missing J20 XML mutation was not detected")
        elif label == "J20 feature-result selection":
            require("--journey-class J20ComposerUploadProgressJourney" in damaged,
                    "missing J20 feature-result selection mutation was not detected")
        elif label == "run-scoped J20 artifact":
            require("android/app/build/outputs/js-upload-progress/" in damaged,
                    "missing J20 artifact mutation was not detected")
        else:
            require("pocketshell_network_fault_fixture_up" in damaged,
                    "missing Toxiproxy mutation was not detected")
    except AssertionError:
        print(f"PASS: missing {label} is detected")
    else:
        raise AssertionError(f"gate self-test did not detect missing {label}")
PY
