#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
RUNNER="$ROOT_DIR/scripts/connected-js-hotkeys-docker.sh"
LANES="$ROOT_DIR/scripts/ci-js-first-packaged-lanes.sh"
EXTRACTOR="$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py"
RESULT_CHECKER="$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py"
JOURNEY="$ROOT_DIR/android/app/src/androidTest/java/com/pocketshell/app/smoke/JsFastKeysDockerJourneyTest.java"

[[ -f "$WORKFLOW" && -x "$RUNNER" && -x "$LANES" && -f "$EXTRACTOR" && -f "$RESULT_CHECKER" && -f "$JOURNEY" ]] || {
  printf 'FAIL: rewrite fast-key gate inputs are missing\n' >&2
  exit 1
}

bash -n "$RUNNER"
bash -n "$LANES"
python3 - "$WORKFLOW" "$RUNNER" "$LANES" "$EXTRACTOR" "$RESULT_CHECKER" "$JOURNEY" <<'PY'
import ast
import sys
from pathlib import Path

workflow_path, runner_path, lanes_path, extractor_path, checker_path, journey_path = map(Path, sys.argv[1:])
workflow = workflow_path.read_text()
runner = runner_path.read_text()
lanes = lanes_path.read_text()
extractor = extractor_path.read_text()
checker = checker_path.read_text()
journey = journey_path.read_text()
ast.parse(extractor, filename=str(extractor_path))
ast.parse(checker, filename=str(checker_path))


def require_contract(source: str, packaged_lanes: str, packaged_runner: str) -> None:
    required = (
        ("hotkeys gate self-test is wired into CI", "tests/scripts/js-first-rewrite-hotkeys-gate-test.sh"),
        ("isolated fast-key Docker fixture starts", "scripts/agents-pool.sh up 2243"),
        ("packaged-lanes wrapper runs in the emulator action", "script: scripts/ci-js-first-packaged-lanes.sh"),
        ("always-run exact fast-key JUnit guard", "name: Assert the packaged JS mobile fast-key journey executed exactly once"),
        ("exact JUnit self-test", "scripts/check-js-hotkeys-journey-results.py --self-test"),
        ("artifact timing validator self-test", 'extract-js-hotkeys-artifacts.py" --self-test'),
        ("same-run fast-key JUnit result", "--results-dir android/app/build/outputs/js-hotkeys"),
        ("run-scoped fast-key artifact upload", "name: Upload packaged JS fast-key run evidence"),
        ("fast-key output bundle", "android/app/build/outputs/js-hotkeys/"),
        ("isolated fixture diagnostics", "docker logs pocketshell-test-agents-2243"),
        ("composer result copy upload", "android/app/build/outputs/js-composer-results/TEST-*.xml"),
        ("composer lane JUnit copy before fastkeys", "composer_results_dir=\"android/app/build/outputs/js-composer-results\""),
        ("fast-key invocation", "scripts/connected-js-hotkeys-docker.sh \\\n  --suffix i2884ci \\\n  --port 2243"),
        ("fast-key aggregate status", "hotkeys_status=$?"),
        ("exact fast-key JUnit status", "hotkeys_junit_status=0"),
        ("exact fast-key result check", 'scripts/check-js-hotkeys-journey-results.py --results-dir "$connected_results_dir"'),
        ("fail-closed fast-key aggregate", "hotkeys_status != 0 || hotkeys_junit_status != 0"),
        ("isolated fixture teardown", "scripts/agents-pool.sh down 2243 2245"),
        ("fast-key runner exact JUnit guard", 'check-js-hotkeys-journey-results.py" --results-dir "$RESULTS_DIR"'),
        ("run-scoped Android evidence", "android/app/build/outputs/js-hotkeys/$ARTIFACT_RUN_ID"),
    )
    for label, needle in required:
        combined = source + packaged_lanes + packaged_runner
        if needle not in combined:
            raise AssertionError(f"fast-key workflow is missing {label}: {needle}")
    start = source.index("scripts/agents-pool.sh up 2243")
    run = packaged_lanes.index("scripts/connected-js-hotkeys-docker.sh")
    stop = source.index("scripts/agents-pool.sh down 2243 2245")
    if not start < source.index("script: scripts/ci-js-first-packaged-lanes.sh") < stop:
        raise AssertionError("the fast-key fixture must start before the packaged lanes and stop after them")
    if packaged_lanes.index("scripts/connected-js-composer-docker.sh") > run:
        raise AssertionError("the fast-key lane must run after composer")
    composer_copy = packaged_lanes.index("composer_results_dir=\"android/app/build/outputs/js-composer-results\"")
    if not packaged_lanes.index("scripts/connected-js-composer-docker.sh") < composer_copy < run:
        raise AssertionError("composer JUnit must be copied before Gradle output is reused by fastkeys")

    guard_start = source.index("- name: Assert the packaged JS mobile fast-key journey executed exactly once")
    guard_end = source.index("- name:", guard_start + 8)
    guard = source[guard_start:guard_end]
    if "if: always()" not in guard or "--results-dir android/app/build/outputs/js-hotkeys" not in guard:
        raise AssertionError("the always-run checker must validate the run-scoped fast-key JUnit copy")
    upload_start = source.index("- name: Upload packaged JS fast-key run evidence")
    upload_end = source.index("- name:", upload_start + 8)
    upload = source[upload_start:upload_end]
    if "if: always()" not in upload or "if-no-files-found: error" not in upload:
        raise AssertionError("fast-key evidence upload must run after failures and fail when no evidence exists")
    if "android/app/build/outputs/js-hotkeys/" not in upload:
        raise AssertionError("fast-key upload omits the packaged journey evidence directory")


require_contract(workflow, lanes, runner)

for label, damaged in (
    ("fast-key invocation", lanes.replace(
        'if scripts/connected-js-hotkeys-docker.sh \\\n',
        "if scripts/connected-js-unrelated-docker.sh \\\n",
        1,
    )),
    ("exact JUnit check", lanes.replace(
        'if ! scripts/check-js-hotkeys-journey-results.py --results-dir "$connected_results_dir"; then\n',
        "if true; then\n",
        1,
    )),
    ("evidence upload", workflow.replace(
        "            android/app/build/outputs/js-hotkeys/\n",
        "            android/app/build/outputs/missing/\n",
        1,
    )),
):
    try:
        require_contract(damaged if label == "evidence upload" else workflow,
                         damaged if label != "evidence upload" else lanes, runner)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the fast-key workflow contract")
    else:
        raise AssertionError(f"workflow contract missed removed {label}")

if '[[ "$health" == healthy ]]' not in runner or 'install -m 600 "$ROOT_DIR/tests/docker/test_key"' not in runner:
    raise AssertionError("dedicated runner must require a healthy fixture and use its committed private key")
if not runner.index('[[ "$health" == healthy ]]') < runner.index('ssh -q "${ssh_opts[@]}"'):
    raise AssertionError("Docker fixture health must be confirmed before SSH setup")
if "expected_first='1b5b411b5b421b091b5b5a110303030404040d'" not in runner \
        or "expected_resumed='1b5b41'" not in runner:
    raise AssertionError("dedicated runner lost its independent first-session or reattached PTY byte oracle")
if "hotkeys-host-oracle.txt" not in runner or 'tee "$evidence_dir/hotkeys-gradle.log"' not in runner:
    raise AssertionError("dedicated runner must preserve host byte and Gradle evidence")
for focus_contract in (
    "reattachEarlyPromptTapWhileHeld",
    "reattachEarlyPromptTapAfterAttach",
    "terminal-enabled-watcher",
    "attach-resize",
    "attach-final-focus",
):
    if focus_contract not in extractor:
        raise AssertionError(f"artifact validator omits attach focus evidence: {focus_contract}")

for timing_field in ("connectToPromptMs", "tapToVisibleOutputMs", "reconnectTapToVisibleOutputMs"):
    if timing_field not in journey or timing_field not in extractor:
        raise AssertionError(f"same-run timing contract omits {timing_field}")
for timing_expression in (
    'journey.put("connectToPromptMs", SystemClock.uptimeMillis() - connectToPromptStartedAt);',
    'journey.put("tapToVisibleOutputMs", SystemClock.uptimeMillis() - tapToVisibleOutputStartedAt);',
    'journey.put("reconnectTapToVisibleOutputMs", SystemClock.uptimeMillis() - reconnectTapToVisibleOutputStartedAt);',
):
    if timing_expression not in journey:
        raise AssertionError(f"same-run timing is not derived from monotonic elapsed time: {timing_expression}")
reconnected_screenshot = 'captureScreenshot("fastkeys-reconnected-ime-open.png");'
if journey.count(reconnected_screenshot) != 4:
    raise AssertionError("reattach screen must be captured once on success and once in each of its three failure paths")
failure_screenshot_contexts = (
    'JSONObject failureGeometry = captureGeometry("reattach-ime-wait-failure");\n'
    '            captureScreenshot("fastkeys-reconnected-ime-open.png");\n'
    '            throw new AssertionError("reattached terminal did not settle',
    'JSONObject failureGeometry = captureGeometry("reconnect-ready-failure");\n'
    '            captureScreenshot("fastkeys-reconnected-ime-open.png");\n'
    '            throw new AssertionError(error.getMessage()',
    '// Preserve the real packaged screen at the failed render boundary before instrumentation tears the app down.\n'
    '            captureScreenshot("fastkeys-reconnected-ime-open.png");\n'
    '            throw new AssertionError(error.getMessage()',
)
for failure_context in failure_screenshot_contexts:
    if failure_context not in journey:
        raise AssertionError("a reattach failure path lost its diagnostic screen capture")
success_capture_checkpoint = 'assertTrue("resumed session must keep the Android IME open", isImeVisible());'
if journey.count(success_capture_checkpoint) != 1:
    raise AssertionError("reattach screenshot success checkpoint must be unique")
success_capture_start = journey.index(success_capture_checkpoint)
success_capture_end = journey.index('click("[data-testid=ssh-disconnect]")', success_capture_start)
if journey[success_capture_start:success_capture_end].count(reconnected_screenshot) != 1:
    raise AssertionError("reattach screenshot must be emitted exactly once on the successful journey path")
if journey.index('journey.put("resumedDoneMarker", resumedDone);') > success_capture_start:
    raise AssertionError("successful reattach screenshot must follow the rendered resumed-output marker")
if "firstDoneMarker" not in journey or "resumedDoneMarker" not in journey:
    raise AssertionError("timings must be paired with the first and reattached rendered-output events")
if "after-reconnect-loss" not in journey or "after-reconnect-loss" not in extractor \
        or "hotkeyControls" not in journey or "hotkeyControls" not in extractor:
    raise AssertionError("post-reattach loss evidence must capture and validate every live-only hotkey control")

print("PASS: rewrite CI runs the API 35 fast-key Docker journey, checks exact JUnit, aggregates lane status, and uploads same-run evidence")
print("PASS: fast-key runner and shared packaged-lanes wrapper parse and preserve focus/IME evidence")
PY
