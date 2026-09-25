#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
RUNNER="$ROOT_DIR/scripts/connected-js-hotkeys-docker.sh"
LANES="$ROOT_DIR/scripts/ci-js-first-packaged-lanes.sh"
EXTRACTOR="$ROOT_DIR/scripts/extract-js-hotkeys-artifacts.py"
RESULT_CHECKER="$ROOT_DIR/scripts/check-js-hotkeys-journey-results.py"
JOURNEY="$ROOT_DIR/android/app/src/androidTest/java/com/pocketshell/app/smoke/JsFastKeysDockerJourneyTest.java"
MOBILE_HOTKEYS="$ROOT_DIR/src/components/MobileHotkeys.vue"
APP="$ROOT_DIR/src/App.vue"

[[ -f "$WORKFLOW" && -x "$RUNNER" && -x "$LANES" && -f "$EXTRACTOR" && -f "$RESULT_CHECKER" && -f "$JOURNEY" && -f "$MOBILE_HOTKEYS" && -f "$APP" ]] || {
  printf 'FAIL: rewrite fast-key gate inputs are missing\n' >&2
  exit 1
}

bash -n "$RUNNER"
bash -n "$LANES"
python3 - "$WORKFLOW" "$RUNNER" "$LANES" "$EXTRACTOR" "$RESULT_CHECKER" "$JOURNEY" "$MOBILE_HOTKEYS" "$APP" <<'PY'
import ast
import re
import sys
from pathlib import Path

workflow_path, runner_path, lanes_path, extractor_path, checker_path, journey_path, mobile_hotkeys_path, app_path = map(Path, sys.argv[1:])
workflow = workflow_path.read_text()
runner = runner_path.read_text()
lanes = lanes_path.read_text()
extractor = extractor_path.read_text()
checker = checker_path.read_text()
journey = journey_path.read_text()
mobile_hotkeys = mobile_hotkeys_path.read_text()
app = app_path.read_text()
unit_test_manifest = (app_path.parent.parent / "scripts/js-unit-test-manifest.json").read_text()
extractor_module = ast.parse(extractor, filename=str(extractor_path))
ast.parse(checker, filename=str(checker_path))
asset_sets = {}
for node in extractor_module.body:
    if (isinstance(node, ast.Assign) and len(node.targets) == 1
            and isinstance(node.targets[0], ast.Name)
            and node.targets[0].id in {"SCREENSHOTS", "VIEWPORT_SCREENSHOTS"}):
        asset_sets[node.targets[0].id] = set(ast.literal_eval(node.value))
captured_assets = set(re.findall(r'capture(?:TerminalViewport)?Screenshot\("([^"]+)"', journey))
extracted_assets = asset_sets.get("SCREENSHOTS", set()) | asset_sets.get("VIEWPORT_SCREENSHOTS", set())
if captured_assets != extracted_assets:
    missing = sorted(extracted_assets - captured_assets)
    unextracted = sorted(captured_assets - extracted_assets)
    raise AssertionError(f"same-run screenshot capture/extraction mismatch: missing={missing}, unextracted={unextracted}")


def require_contract(source: str, packaged_lanes: str, packaged_runner: str, artifact_extractor: str) -> None:
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
        ("same-run resize and geometry trace collector", "-s PS2884Asset:I PS2884Geometry:I"),
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
        ("dictation final byte oracle is read from this run's journey", 'journey.get("dictation")'),
        ("dictation bytes are checked on the host fixture", 'dictation PTY bytes mismatch'),
        ("keyboard-up viewport cap is established before the dock opens", "keyboardComposerMode.value"),
        ("viewport cap is limited to the accepted 144px budget", "terminalViewportDockPreferredCapPx = 144"),
        ("fractional measured viewport caps round down under the 144px maximum", "Math.min(terminalViewportDockPreferredCapPx, Math.floor(height))"),
        ("narrow toolbar scroll reachability is checked", "narrowToolbarReachability"),
        ("Android dock heights reserve the status, key, and catalog rows", "INLINE_DICTATION_STATUS_ROW_HEIGHT_PX"),
        ("catalog scroller stays within the 144px sheet", "catalogScrollerInsideSheet"),
        ("dock stays within its clipping terminal panel", "insideTerminalPanel"),
        ("terminal slot stays within its clipping panel", "terminalSlotInsideTerminalPanel"),
        ("live keyboard row must not overflow", "live-width persistent row is clipped"),
        ("clipped hit-area intersection is checked", "visibleHeightInKeybar"),
        ("narrow-toolbar scroll checks visible hit-area intersection", "visibleHeight\""),
        ("dictation raw byte count is checked", 'dictation raw byte file length mismatch'),
        ("dictation listening screenshot is uploaded", "fastkeys-dictation-listening-ime-open.png"),
        ("dictation transcribing screenshot is uploaded", "fastkeys-dictation-transcribing-ime-open.png"),
        ("dictation stopped screenshot is uploaded", "fastkeys-dictation-stopped-ime-open.png"),
        ("dictation error screenshot is uploaded", "fastkeys-dictation-error-ime-open.png"),
        ("composer actions screenshot is uploaded", "fastkeys-main-composer-actions-ime-open.png"),
        ("dictation reattach screenshot is uploaded", "fastkeys-dictation-reattached-ime-open.png"),
        ("closed fast-key row screenshot is uploaded", "fastkeys-row-closed-ime-open.png"),
        ("catalog sheet front and tail screenshots are hashed for review", "fastkeys-sheet-ctrl-tail-ime-open.png"),
        ("closed row has same-run terminal viewport evidence", "fastkeys-row-closed-ime-open-viewport.png"),
        ("main catalog tail has same-run terminal viewport evidence", "fastkeys-sheet-main-tail-ime-open-viewport.png"),
        ("Ctrl catalog tail has same-run terminal viewport evidence", "fastkeys-sheet-ctrl-tail-ime-open-viewport.png"),
        ("dictation listening has same-run terminal viewport evidence", "fastkeys-dictation-listening-ime-open-viewport.png"),
        ("dictation stopped has same-run terminal viewport evidence", "fastkeys-dictation-stopped-ime-open-viewport.png"),
        ("dictation error has same-run terminal viewport evidence", "fastkeys-dictation-error-ime-open-viewport.png"),
    )
    for label, needle in required:
        combined = source + packaged_lanes + packaged_runner + artifact_extractor + app
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


require_contract(workflow, lanes, runner, extractor)

styles = app_path.parent.joinpath("styles.css").read_text()
if "live keyboard row must contain all persistent controls without clipping or scrolling" not in journey:
    raise AssertionError("Android journey must reject live-width fast-key row clipping")
if ("row.getDouble(\"top\") - draft.getDouble(\"bottom\") >= 2" not in journey
        or "row.get(\"top\", 0) - draft.get(\"bottom\", 10**9) < 2" not in extractor
        or ".app-shell[data-keyboard-visible=\"true\"][data-inline-dictation-status=\"true\"] .live-workspace .composer-actions {\n    margin-top: -2px;" not in styles):
    raise AssertionError("status-visible composer must lift its 48px actions while keeping a 2px editor gap")
if ("TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX = 0.01" not in journey
        or "TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX = 0.01" not in extractor
        or "terminal viewport overlap greater than 0.01px CSS rejected" not in extractor):
    raise AssertionError("Fast Keys geometry must document the 0.01px viewport rounding epsilon and reject larger overlaps")
if ("target.getDouble(\"bottom\") <= imeEdgeCssY - 4" not in journey
        or "require_ime_clearance_for_targets" not in extractor
        or "status Send target without 4px IME clearance rejected" not in extractor):
    raise AssertionError("every status composer action target must keep at least 4px of IME clearance")
if ("Math.min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_DP," not in journey
        or "private JSONObject assertAcceptedKeyboardUpViewport" not in journey
        or "38, grid.getInt(\"cols\")" not in journey
        or "6, grid.getInt(\"rows\")" not in journey
        or "acceptedKeyboardGrid, runtimeGrid(afterNavigationTaps)" not in journey
        or "acceptedKeyboardGrid, runtimeGrid(afterReconnectGeometry)" not in journey):
    raise AssertionError("Android journey must lock the API 35 144px/38x6 keyboard baseline through navigation and reattach")
if ("terminalViewportDockCapPx.value = terminalViewportDockBaseCapPx.value;" not in app
        or '(afterGeometry.optBoolean("inlineDictationStatusVisible") ? 16 : 0)' in journey):
    raise AssertionError("dictation status must not shrink the captured terminal viewport cap")
if ("min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX, math.floor(idle_viewport_height))" not in extractor
        or "min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX,\n                                math.floor(baseline_viewport_height))" not in extractor
        or "API35_ACCEPTED_TERMINAL_GRID = (38, 6)" not in extractor
        or "API 35 keyboard-up baseline must lock the accepted 144px / 38×6 terminal grid" not in extractor
        or "with_api35_expanded_keyboard_viewport(sample_journey()), False" not in extractor):
    raise AssertionError("artifact gate must reject a 172px viewport and enforce the accepted API35 144px/38x6 grid")
if "the Android dictation status is one readable 16dp line" not in journey:
    raise AssertionError("Android journey must keep the status above persistent keys at the Kotlin-aligned height")
if "gap: 0;" not in styles or "margin-top: 0;" not in styles:
    raise AssertionError("keyboard-up catalog must return reclaimed gap and remove dock overflow margin")
if "the persistent mic must remain fully inside the key row" not in journey:
    raise AssertionError("Android journey must measure the mic inside the persistent row")
if ("assertCompactStatusComposerLayout(\"listening status\", listening)" not in journey
        or "assertCompactStatusComposerLayout(\"Ctrl catalog while dictation is listening\", ctrlListening)" not in journey
        or "composerDraft:composerDraftRect" not in journey
        or "imeEdgeCssY:" not in journey or "actionTargets=" not in journey):
    raise AssertionError("Android journey must measure the compact 25px editor and composer actions with status open and closed")
if ("height: 80px;" not in styles or "height: 25px;" not in styles
        or "padding: 2px 8px;" not in styles or "font: 14px/20px var(--font-mono);" not in styles):
    raise AssertionError("status-visible composer must reclaim its 16px row with an 80px panel and 25px editor")
if "validate_compact_status_composer" not in extractor or "less than a 25px editor rejected" not in extractor:
    raise AssertionError("artifact self-tests must enforce the compact status composer geometry")
if "dictation status shrinking the pre-dock viewport cap rejected" not in extractor:
    raise AssertionError("artifact self-tests must reject the former 16px viewport shrink")
if "keeps active dictation status above the persistent controls on both catalog pages" not in unit_test_manifest:
    raise AssertionError("full JS unit gate manifest must include the active status row component test")
if '"IME-up Ctrl catalog while inline dictation is listening"' not in extractor:
    raise AssertionError("artifact gate must enforce the four-pixel composer clearance for Ctrl catalog dictation")
if '.mobile-hotkeys--dictation-available .mobile-hotkeys__dictation-dock {\n  border-top: 1px solid var(--border-soft);' not in mobile_hotkeys:
    raise AssertionError("Android inline dictation must use its one-pixel hairline as the dock boundary")
if ("data-testid=\"mobile-hotkeys-enter-divider\"" not in mobile_hotkeys
        or ".mobile-hotkeys__enter-divider { width: 1px; height: 24px;" not in mobile_hotkeys):
    raise AssertionError("persistent arrows and Enter must keep the Kotlin divider without consuming a hit target")
if ("mobile-hotkeys-launcher-label" in mobile_hotkeys
        or "inline-dictation-label" in mobile_hotkeys
        or ".terminal-dictation-button__label" in styles):
    raise AssertionError("More keys and dictation must remain icon-only without crowding the persistent row")
if ("More terminal keys" not in mobile_hotkeys
        or "border: 0;\n  border-radius: var(--r-md);\n  background: transparent;" not in styles):
    raise AssertionError("More keys and the mic must use the shared quiet toolbar treatment with accessible names")
if (".mobile-hotkeys--dictation-available.mobile-hotkeys--main-open,\n"
        ".mobile-hotkeys--dictation-available.mobile-hotkeys--ctrl-open { height: 193px; }") not in mobile_hotkeys:
    raise AssertionError("Android catalog without visible dictation status must match the 193px Kotlin-aligned dock budget")
if (".mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--main-open,\n"
        ".mobile-hotkeys--dictation-available.mobile-hotkeys--dictation-status-open.mobile-hotkeys--ctrl-open { height: 209px; }") not in mobile_hotkeys:
    raise AssertionError("Android catalog with visible dictation status must match the 209px Kotlin-aligned dock budget")
if "translateY(0.95px)" in mobile_hotkeys:
    raise AssertionError("Android inline dictation must not translate 48dp controls out of the clipped toolbar row")
if "scrollbar-width: none;" not in mobile_hotkeys or ".mobile-hotkeys__bar::-webkit-scrollbar { display: none; }" not in mobile_hotkeys:
    raise AssertionError("Android narrow-width toolbar must preserve full target height while retaining swipe scrolling")
if "dictationModeSelector" in journey or "dictationModeOptions" in journey or "inlineDictationMode" in app:
    raise AssertionError("the Kotlin-aligned inline mic/status dock must not retain the JS-only Prompt/Command mode state")
if "terminalSlotInsideTerminalPanel" not in journey or "terminalSlotInsideTerminalPanel" not in extractor:
    raise AssertionError("Fast Keys must explicitly verify the full terminal slot stays within its clipped panel")
if "10.333px past its panel rejected" not in extractor:
    raise AssertionError("artifact self-tests must reject the listening + Ctrl catalog slot overflow regression")
if ("catalogScrollerRect.bottom<=catalogSheet.bottom+" not in journey
        or "catalog scroller overflow above the 0.01px CSS rounding epsilon rejected" not in extractor):
    raise AssertionError("catalog containment must allow only 0.01px float noise and reject larger overflow")

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
                         damaged if label != "evidence upload" else lanes, runner, extractor)
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

for sheet_screenshot in (
    "fastkeys-sheet-main-ime-open.png",
    "fastkeys-sheet-main-tail-ime-open.png",
    "fastkeys-sheet-ctrl-ime-open.png",
    "fastkeys-sheet-ctrl-tail-ime-open.png",
):
    if f'captureScreenshot("{sheet_screenshot}")' not in journey or sheet_screenshot not in extractor:
        raise AssertionError(f"same-run sheet screenshot is not captured and extracted: {sheet_screenshot}")
for viewport_screenshot in (
    "fastkeys-row-closed-ime-open-viewport.png",
    "fastkeys-sheet-main-ime-open-viewport.png",
    "fastkeys-sheet-main-tail-ime-open-viewport.png",
    "fastkeys-sheet-ctrl-ime-open-viewport.png",
    "fastkeys-sheet-ctrl-tail-ime-open-viewport.png",
    "fastkeys-dictation-listening-ime-open-viewport.png",
    "fastkeys-dictation-stopped-ime-open-viewport.png",
    "fastkeys-dictation-error-ime-open-viewport.png",
):
    if f'captureTerminalViewportScreenshot("{viewport_screenshot}"' not in journey or viewport_screenshot not in extractor:
        raise AssertionError(f"same-run full-device and terminal viewport evidence is not paired: {viewport_screenshot}")
if "private void scrollCatalogToStart(String selector)" not in journey:
    raise AssertionError("catalog sheet screenshots must include the visible first scroll position")

for swipe_contract in (
    "private void swipeFastKeyIntoView(String selector)",
    "MAX_CATALOG_SWIPE_ATTEMPTS = 8",
    "private void injectSwipe(float startX, float startY, float endX, float endY)",
    "MotionEvent.ACTION_MOVE",
    "towardEnd ? offsetDelta > 0.5 : offsetDelta < -0.5",
    "insideContent:r.left>=c.left-0.5",
    "clearOfButtons",
    "insideScroller",
    "double startX = anchor.getDouble(\"x\")",
    "double startY = anchor.getDouble(\"y\")",
    "lastInjectedSwipe",
    "screenStartX",
    "writesBeforeReachabilitySwipes",
    "a physical catalog swipe must not activate a key or write to the PTY",
):
    if swipe_contract not in journey:
        raise AssertionError(f"packaged fast-key journey omits physical catalog swipe proof: {swipe_contract}")
for mutation in ("container.scrollLeft+=", "container.scrollLeft-=", "container.scrollTop+=", "container.scrollTop-="):
    if mutation in journey:
        raise AssertionError(f"catalog reachability may not be faked by mutating scroll position in JS: {mutation}")
for geometry_contract in ("intersectsComposerPanel", "inlineDictationBarInsideTray"):
    if geometry_contract not in journey or geometry_contract not in extractor:
        raise AssertionError(f"fast-key tray evidence omits the no-overlap geometry contract: {geometry_contract}")
if "mobile-hotkeys__mode-selector" in mobile_hotkeys or "InlineDictationMode" in mobile_hotkeys:
    raise AssertionError("Kotlin-aligned inline dictation must use the existing persistent Mic/Stop row")
if "dictationModeSelector" in extractor or "dictationModeOptions" in extractor:
    raise AssertionError("Fast Keys artifact validation must not retain the removed JS-only selector contract")
if "narrowToolbarReachability" not in journey or "scrollWidth" not in journey:
    raise AssertionError("Fast Keys acceptance omits measured narrow-width horizontal reachability")
for catalog_contract in ("catalogHeaderControlsDoNotOverlap", "catalogTitle", "scrollHeight", "mobile-hotkeys__ctrl-grid"):
    if catalog_contract not in journey or catalog_contract not in extractor:
        raise AssertionError(f"Fast Keys acceptance omits visible title/header fit or vertical Ctrl reachability: {catalog_contract}")
for dictation_contract in (
    "inlineDictationMicInsideBar",
    "inlineDictationMicCount",
    "inlineDictationTargetKey",
    "sshAttachEpoch",
    "dictation-final-awaiting-stopped",
    "dictation-attach-cancel-complete",
    "dictation-background-cancel-resumed",
):
    if dictation_contract not in journey or dictation_contract not in extractor:
        raise AssertionError(f"combined fast-key journey omits the integrated dictation contract: {dictation_contract}")
if "validate_dictation_behavior(journey)" not in extractor or "expectedHostHex" not in extractor:
    raise AssertionError("artifact validator must fail closed on partial/final/Stop/error/reattach dictation behavior")

print("PASS: rewrite CI runs the API 35 fast-key Docker journey, checks exact JUnit, aggregates lane status, and uploads same-run evidence")
print("PASS: fast-key runner and shared packaged-lanes wrapper parse and preserve focus/IME evidence")
PY
