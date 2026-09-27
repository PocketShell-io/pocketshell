#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
RUNNER="$ROOT_DIR/scripts/connected-js-composer-docker.sh"
EXTRACTOR="$ROOT_DIR/scripts/extract-js-composer-artifacts.py"
JOURNEY="$ROOT_DIR/android/app/src/androidTest/java/com/pocketshell/app/smoke/JsComposerDockerJourneyTest.java"
USAGE_PORTS_JOURNEY="$ROOT_DIR/android/app/src/androidTest/java/com/pocketshell/app/smoke/UsagePortsDockerJourneyTest.java"
PROMPT_COMPOSER="$ROOT_DIR/src/components/PromptComposer.vue"
RECORDING_MODE="$ROOT_DIR/src/components/ComposerRecordingMode.vue"
DICTATION_UNIT_TEST="$ROOT_DIR/tests/unit/composerDictationCancellation.test.ts"
HOST_ORACLE="$ROOT_DIR/scripts/composer-host-byte-oracle.py"
HOST_ORACLE_CHECKER="$ROOT_DIR/scripts/check-js-composer-host-oracle.py"
TOOLCACHE_PRUNER="$ROOT_DIR/scripts/ci-emulator-prune-toolcache.sh"
PACKAGED_LANES="$ROOT_DIR/scripts/ci-js-first-packaged-lanes.sh"

[[ -f "$WORKFLOW" && -x "$RUNNER" && -f "$EXTRACTOR" && -f "$JOURNEY" \
  && -f "$USAGE_PORTS_JOURNEY" \
  && -f "$PROMPT_COMPOSER" && -f "$RECORDING_MODE" && -f "$DICTATION_UNIT_TEST" \
  && -x "$HOST_ORACLE" && -x "$HOST_ORACLE_CHECKER" \
  && -x "$TOOLCACHE_PRUNER" && -x "$PACKAGED_LANES" ]] || {
  printf 'FAIL: rewrite composer gate inputs are missing\n' >&2
  exit 1
}

bash -n "$RUNNER"
python3 - "$WORKFLOW" "$RUNNER" "$EXTRACTOR" "$TOOLCACHE_PRUNER" "$PACKAGED_LANES" \
  "$JOURNEY" "$USAGE_PORTS_JOURNEY" "$PROMPT_COMPOSER" "$RECORDING_MODE" "$DICTATION_UNIT_TEST" \
  "$HOST_ORACLE" "$HOST_ORACLE_CHECKER" <<'PY'
import ast
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

workflow_path, runner_path, extractor_path, toolcache_pruner_path, packaged_lanes_path, journey_path, \
    usage_ports_journey_path, prompt_composer_path, recording_mode_path, dictation_unit_test_path, \
    host_oracle_path, host_oracle_checker_path = map(Path, sys.argv[1:])
workflow = workflow_path.read_text()
runner = runner_path.read_text()
packaged_lanes = packaged_lanes_path.read_text()
journey = journey_path.read_text()
usage_ports_journey = usage_ports_journey_path.read_text()
disk_cleanup = (toolcache_pruner_path.parent / "ci-emulator-free-disk.sh").read_text()
extractor = extractor_path.read_text()
prompt_composer = prompt_composer_path.read_text()
recording_mode = recording_mode_path.read_text()
dictation_unit_test = dictation_unit_test_path.read_text()
host_oracle = host_oracle_path.read_text()
host_oracle_checker = host_oracle_checker_path.read_text()
ast.parse(extractor, filename=str(extractor_path))
subprocess.run(["bash", "-n", str(toolcache_pruner_path)], check=True)
subprocess.run(["bash", "-n"], input=disk_cleanup, text=True, check=True)
if "scripts/ci-emulator-prune-toolcache.sh" not in disk_cleanup:
    raise AssertionError("disk preflight does not invoke the tested toolcache-preservation helper")


def require_dictate_prompt_journey(source: str) -> None:
    required = (
        'capturePromptComposerRoute(runId);',
        'emitCurrentScreen(runId, "composer-route.png")',
        'emitArtifact(runId, "composer-route.json"',
        'const inlineMic=document.querySelector(\'[data-testid=inline-dictation-toggle]\')',
        '"Dictate to terminal".equals(state.getString("inlineMicLabel"))',
        'titleState.put("expectedDictatePromptAccessibleName", "Dictate prompt")',
        'titleState.put("expectedDictatePromptVisibleLabel", "")',
        'titleState.getString("dictatePromptText").isEmpty()',
        'titleState.getBoolean("dictatePromptGlyphPresent")',
        'titleState.getString("dictatePromptAccessibleName")',
        'titleState.getBoolean("dictatePromptVisible")',
        'titleState.getBoolean("dictatePromptEnabled")',
        'getDouble("width") >= 48.0',
        'getDouble("height") >= 48.0',
        'emitCurrentScreen(runId, "composer-title.png")',
        'emitArtifact(runId, "composer-title.json"',
    )
    for needle in required:
        if needle not in source:
            raise AssertionError(f"composer journey is missing the separate Prompt and inline dictation route contract: {needle}")
    start = source.index("private void exerciseComposerDictationMode")
    end = source.index("private void awaitComposerReadyToSend", start)
    dictation_method = source[start:end]
    route_capture = dictation_method.index("capturePromptComposerRoute(runId);")
    prompt_entry_tap = dictation_method.index('tapDomCenter("[data-testid=prompt-composer-launcher]")')
    title_assertion = dictation_method.index("assertGenericComposerTitleAndSessionChrome(runId);")
    action_tap = dictation_method.index('tapDomCenter("[data-testid=composer-dictate]")')
    if not route_capture < prompt_entry_tap < title_assertion < action_tap:
        raise AssertionError("journey must capture the idle terminal route, open Prompt, verify its Dictate action, then enter dictation")


require_dictate_prompt_journey(journey)


def require_open_composer_physical_target_settles(source: str) -> None:
    set_draft = source[source.index("private void setComposerDraft("):source.index("private void awaitPromptDictateTargetSettled(")]
    if "if (openedComposer) awaitPromptDictateTargetSettled();" not in set_draft:
        raise AssertionError("a newly opened composer must settle its physical Dictate target before the next tap")
    start = source.index("private void awaitPromptDictateTargetSettled(")
    end = source.index("private void openComposerAfterInlineWithEvidence(", start)
    settle = source[start:end]
    required = (
        "awaitNativeWindowFocus(true)",
        "panel.getAttribute('role')==='dialog'",
        "panel.getAttribute('aria-modal')==='true'",
        "button.getClientRects().length>0",
        "buttonRect.width>=48&&buttonRect.height>=48",
        "buttonRect.bottom<=viewport.height",
        "document.elementFromPoint(x,y)",
        "hit?.closest?.('[data-testid=composer-dictate]')",
        "Math.abs(value-previous.geometry[index])<0.25",
        "samples>=3",
    )
    for needle in required:
        if needle not in settle:
            raise AssertionError(f"composer Dictate physical target settling is missing: {needle}")


require_open_composer_physical_target_settles(journey)

def require_usage_ports_composer_opener(source: str) -> None:
    helper_start = source.index("private void openComposerIfClosedAndAwaitTransport()")
    helper_end = source.index("private void awaitTerminalReady()", helper_start)
    helper = source[helper_start:helper_end]
    required = (
        'if (!"true".equals(evalString("!!document.querySelector(\'[data-testid=prompt-composer]\')")))',
        'click("[data-testid=prompt-composer-launcher]")',
        "getAttribute('role') === 'dialog'",
        "getAttribute('aria-modal') === 'true'",
        "dataset.transportState === 'connected'",
    )
    for needle in required:
        if needle not in helper:
            raise AssertionError(f"Usage/Ports composer helper is missing {needle}")
    positions = [helper.index(needle) for needle in required]
    if positions != sorted(positions):
        raise AssertionError("Usage/Ports must open the composer when closed, await its modal dialog, then await connected transport")

    send_start = source.index("private void sendComposerCommandAndAwaitMarker(")
    send_end = source.index("private void openComposerIfClosedAndAwaitTransport()", send_start)
    send_helper = source[send_start:send_end]
    ensure_composer = send_helper.index("openComposerIfClosedAndAwaitTransport();")
    draft_write = send_helper.index('setValue("[data-testid=prompt-draft]", command);')
    send_click = send_helper.index('click(".composer-shared-controls .send");')
    if not ensure_composer < draft_write < send_click:
        raise AssertionError("Usage/Ports cleanup must open and ready the composer before writing or sending the command")


require_usage_ports_composer_opener(usage_ports_journey)

missing_launcher = usage_ports_journey.replace(
    'click("[data-testid=prompt-composer-launcher]");',
    "",
    1,
)
try:
    require_usage_ports_composer_opener(missing_launcher)
except (AssertionError, ValueError):
    print("PASS: removing the Usage/Ports composer launcher tap fails its gate contract")
else:
    raise AssertionError("Usage/Ports helper gate missed a removed composer launcher tap")

helper_start = usage_ports_journey.index("private void openComposerIfClosedAndAwaitTransport()")
helper_end = usage_ports_journey.index("private void awaitTerminalReady()", helper_start)
helper = usage_ports_journey[helper_start:helper_end]
dialog_start = helper.index("getAttribute('role') === 'dialog'")
transport_start = helper.index("dataset.transportState === 'connected'")
dialog_wait = helper[dialog_start:transport_start]
transport_wait = helper[transport_start:]
reordered_helper = helper[:dialog_start] + transport_wait + dialog_wait
transport_before_dialog = usage_ports_journey.replace(helper, reordered_helper, 1)
try:
    require_usage_ports_composer_opener(transport_before_dialog)
except (AssertionError, ValueError):
    print("PASS: checking Usage/Ports transport before the dialog fails its gate contract")
else:
    raise AssertionError("Usage/Ports helper gate missed transport checked before opening the dialog")


def require_icon_only_stop_contract(source: str, extractor_source: str) -> None:
    required = (
        "stop?.innerText.trim()===''",
        "stop?.getAttribute('aria-label')==='Stop dictation and keep the recognized text in the editable draft'",
        "Math.abs(stopRect.width-48.0)<0.5",
        "Math.abs(stopRect.height-48.0)<0.5",
        "!!stopGlyph",
        '"stopText,stopAccessibleName,"',
        '"stopVisible:!!stopButton',
        '"stopEnabled:!!stopButton&&!stopButton.disabled,"',
        "svg[aria-hidden='true'] > rect[x='6'][y='6'][width='12'][height='12'][rx='1'][fill='currentColor']",
        '"stopGlyphPresent:!!stopButton?.querySelector(',
    )
    for needle in required:
        if needle not in source:
            raise AssertionError(f"composer journey is missing icon-only Stop evidence: {needle}")
    if "textContent.includes('Stop')" in source:
        raise AssertionError("composer journey must not require visible Stop text")
    extractor_cases = (
        '"recording Stop exposes visible label text"',
        '"recording Stop has the wrong accessible name"',
        '"recording Stop is hidden"',
        '"recording Stop is disabled"',
        '"recording Stop omits the square SVG glyph"',
        '"recording Stop bounds are not 48dp square"',
    )
    for needle in extractor_cases:
        if needle not in extractor_source:
            raise AssertionError(f"composer artifact extractor lacks a Stop regression case: {needle}")


require_icon_only_stop_contract(journey, extractor)


def require_kotlin_dictation_contract(source: str, extractor_source: str) -> None:
    journey_evidence = (
        "actions?.getAttribute('role')==='group'",
        '"Prompt dictation"',
        '"Review dictation"',
        "cancel?.textContent.trim()==='Discard'",
        "cancel?.getAttribute('aria-label')==='Discard recording without transcribing'",
        '"timerBesideWaveform:!!timerRect&&!!waveformRect&&timerRect.bottom>waveformRect.top"',
        '"recordingControlsAccessible:actionRow?.getAttribute(\'role\')===\'group\'"',
        '"recordingControlsSeparate:!!actionRow&&!!mode&&!mode.contains(actionRow),',
        '"previewAccessible:!!preview&&preview.getAttribute(\'id\')===\'composer-recording-preview\'"',
        '"reviewEditable:!!draft&&!draft.readOnly&&draft.getAttribute(\'aria-readonly\')===\'false\',',
        "textContent.includes('Transcript ready')",
        '"recording actions must follow the Kotlin composer row: Discard, Insert, Send, Stop"',
        '"transcribing Cancel must be distinct from recording Discard"',
    )
    for needle in journey_evidence:
        if needle not in source:
            raise AssertionError(f"composer journey is missing a Kotlin dictation contract assertion: {needle}")

    extractor_evidence = (
        'mode_geometry.get("cancelText") != "Discard"',
        'mode_geometry.get("composerHeading") != expected_heading',
        'route_state.get("inlineMicLabel") != "Dictate to terminal"',
        'mode_geometry.get("cancelText") != "Cancel"',
        'mode_geometry.get("timerBesideWaveform") is not True',
        'mode_geometry.get("timerVisible") is not True',
        'mode_geometry.get("insertAccessible") is not True',
        'mode_geometry.get("previewVisible") is not True',
        '_timer_sits_beside_waveform(mode_geometry.get("timer"), mode_geometry.get("waveform"))',
        'mode_geometry.get("previewAccessible") is not True',
        'mode_geometry.get("recordingControlsSeparate") is not True',
        'mode_geometry.get("reviewEditable") is not True',
        '"recording timer is stacked below its waveform"',
        '"recording transcript lacks live accessible text"',
        '"recording controls are nested inside the status card"',
        '"post-stop review is no longer editable"',
    )
    for needle in extractor_evidence:
        if needle not in extractor_source:
            raise AssertionError(f"composer artifact extractor is missing a Kotlin dictation criterion: {needle}")

    subprocess.run([sys.executable, str(extractor_path), "--self-test"], check=True)


require_kotlin_dictation_contract(journey, extractor)


def require_obvious_prompt_dictation_mode() -> None:
    composer_evidence = (
        "const composerTitle = computed(() => dictationPhase.value === 'review'",
        "'Review dictation'",
        "'Prompt dictation'",
        "<h3 id=\"composer-title\">{{ composerTitle }}</h3>",
        'title="Dictate a prompt" aria-label="Dictate prompt"',
        '<DictationMicIcon :size="20" />',
        "composer-recording-preview composer-status",
        "Prompt dictation draft, read only during capture",
    )
    for needle in composer_evidence:
        if needle not in prompt_composer:
            raise AssertionError(f"mobile prompt composer does not expose its distinct dictation mode: {needle}")
    for needle in ('class="recording-mode__phase">Listening</span>', "Transcribing prompt…"):
        if needle not in recording_mode:
            raise AssertionError(f"prompt dictation feedback is not phase-specific: {needle}")
    unit_evidence = (
        "places the mobile Dictate prompt microphone in the composer action row",
        "expect(textContent(mic!)).toBe('')",
        "expect(textContent(findAll(root, (candidate) => candidate.props.id === 'composer-title')[0])).toBe('Prompt dictation')",
        "expect(textContent(findAll(root, (candidate) => candidate.props.id === 'composer-title')[0])).toBe('Review dictation')",
        "toEqual(['composer-recording-cancel', 'composer-insert', 'composer-dictation-send', 'composer-recording-stop'])",
        "expect(writePty).not.toHaveBeenCalled();",
    )
    for needle in unit_evidence:
        if needle not in dictation_unit_test:
            raise AssertionError(f"dictation UX regression test is missing its failing assertion: {needle}")
    # The extractor self-test below feeds small and clipped route controls into
    # the same artifact validator used by the packaged screenshot journey.
    if '"idle terminal {label} is clipped or below the 48dp touch target"' not in extractor:
        raise AssertionError("route screenshot validation does not protect the separate Prompt and inline mic touch targets")
    subprocess.run([sys.executable, str(host_oracle_checker_path), "--self-test"], check=True)


require_obvious_prompt_dictation_mode()


def require_pre_action_host_byte_oracle() -> None:
    for stage, action in (
        ("recording-insert", 'tapDomCenter("[data-testid=composer-insert]")'),
        ("transcribing-insert", 'tapDomCenter("[data-testid=composer-insert]")'),
        ("transcribing-send", 'tapDomCenter("[data-testid=composer-dictation-send]")'),
    ):
        before = journey.index(f'captureHostBeforeExplicitAction("{stage}",')
        after = journey.index(action, before)
        if before >= after:
            raise AssertionError(f"host byte snapshot for {stage} must precede the explicit action")
    review_snapshot = journey.index('captureHostBeforeExplicitAction("stop-review",')
    review_action = journey.index('tapDomCenter(".composer-shared-controls .send")', review_snapshot)
    if review_snapshot >= review_action:
        raise AssertionError("Stop must leave an editable draft and no PTY write before a later explicit Send")
    for needle in (
        "a capture --workspace /home/testuser --tag",
        '"source": "docker-host-a-capture"',
        '"noPtyWriteBeforeExplicitAction": result.returncode == 0 and not marker_present',
    ):
        if needle not in host_oracle:
            raise AssertionError(f"host oracle is missing independent pre-action PTY evidence: {needle}")
    if '"recording-insert", "transcribing-insert", "transcribing-send", "stop-review"' not in host_oracle_checker:
        raise AssertionError("host-byte checker does not require every pre-action stage")
    for needle in (
        '"ptyWriteObserved") is not False',
        '"noPtyWriteBeforeExplicitAction") is not True',
        "marker.encode(\"utf-8\") in capture",
        "composer-host-oracle-pre-{stage}.json",
    ):
        if needle not in host_oracle_checker:
            raise AssertionError(f"host-byte evidence checker is missing a fail-closed condition: {needle}")
    runner_evidence = (
        '"$ADB" -s "$ANDROID_SERIAL" reverse "tcp:$host_oracle_port" "tcp:$host_oracle_port"',
        '"-Pandroid.testInstrumentationRunnerArguments.hostOraclePort=$host_oracle_port"',
        '"$ROOT_DIR/scripts/check-js-composer-host-oracle.py"',
        '--evidence-dir "$evidence_dir" --run-id "$ARTIFACT_RUN_ID" --session "$SESSION_BASE-bytes"',
    )
    for needle in runner_evidence:
        if needle not in runner:
            raise AssertionError(f"packaged composer runner does not wire host-byte verification: {needle}")


require_pre_action_host_byte_oracle()


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
    if lifecycle >= composer:
        raise AssertionError("composer journey must run after the smoke/lifecycle portion of the API 35 script")
    if "--force-first-post-attach-tap-miss" not in packaged_script \
            or "--composer-focus-max-attempts 2" not in packaged_script:
        raise AssertionError("composer CI lane must exercise the forced physical miss and bounded two-tap recovery")
    fastkeys = packaged_script.index("scripts/connected-js-hotkeys-docker.sh")
    if not lifecycle < usage < composer < fastkeys:
        raise AssertionError("usage/ports and composer must run before the fast-key journey")
    if "--run-id \"js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}\"" not in packaged_script:
        raise AssertionError("usage/ports run identity is not forwarded to the packaged journey")
    for lane in ("smoke_status", "lifecycle_status", "usage_status", "composer_status",
                 "composer_junit_copy_status", "composer_junit_status", "hotkeys_status",
                 "hotkeys_junit_status", "copy_status"):
        if lane not in packaged_script:
            raise AssertionError(f"packaged wrapper does not aggregate {lane}")
    if "TEST-*.xml" not in packaged_script or "cp -a --" not in packaged_script:
        raise AssertionError("packaged wrapper must copy smoke JUnit evidence")
    if "android/app/build/outputs/js-smoke-results" not in packaged_script:
        raise AssertionError("packaged wrapper must save the smoke JUnit copy under its upload path")
    guard_start = source.index("- name: Assert packaged JS composer and Usage/Ports journeys executed exactly once")
    guard_end = source.index("- name:", guard_start + 8)
    guard = source[guard_start:guard_end]
    if "if: always()" not in guard or "--results-dir android/app/build/outputs/js-composer-results" not in guard:
        raise AssertionError("the exact JUnit result guard must run after the emulator step even when it fails")
    if "scripts/check-js-usage-ports-results.py \\" not in guard or \
       'js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}/instrumentation-results' not in guard:
        raise AssertionError("the usage/ports result guard must check the preserved same-run JUnit report")
    if "android/app/build/outputs/js-composer-results" not in packaged_script:
        raise AssertionError("packaged wrapper must save composer JUnit before the hotkeys lane")
    hotkeys_guard_start = source.index("- name: Assert the packaged JS mobile fast-key journey executed exactly once")
    hotkeys_guard_end = source.index("- name:", hotkeys_guard_start + 8)
    hotkeys_guard = source[hotkeys_guard_start:hotkeys_guard_end]
    if "if: always()" not in hotkeys_guard or "--results-dir android/app/build/outputs/js-hotkeys" not in hotkeys_guard:
        raise AssertionError("the fast-key result guard must run after the emulator step on its preserved result copy")
    upload_start = source.index("- name: Upload packaged JS composer run evidence")
    upload_end = source.index("- name:", upload_start + 8)
    upload = source[upload_start:upload_end]
    if "if: always()" not in upload or "if-no-files-found: error" not in upload:
        raise AssertionError("composer evidence upload must run always and fail if the bundle is absent")
    if "android/app/build/outputs/js-composer/" not in upload:
        raise AssertionError("composer artifact upload omits the run-scoped evidence directory")
    if "android/app/build/outputs/js-composer-results/TEST-*.xml" not in upload:
        raise AssertionError("composer artifact upload omits its result copy before the hotkeys lane reuses Gradle output")
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
    ("forced physical first-miss control", packaged_lanes.replace(
        "  --force-first-post-attach-tap-miss \\\n",
        "",
        1,
    )),
    ("two-tap recovery bound", packaged_lanes.replace(
        "  --composer-focus-max-attempts 2; then",
        "; then",
        1,
    )),
    ("composer artifact path", workflow.replace(
        "            android/app/build/outputs/js-composer/\n",
        "            android/app/build/outputs/other/\n",
        1,
    )),
):
    try:
        if label == "composer artifact path":
            require_contract(damaged, packaged_lanes)
        else:
            require_contract(workflow, damaged)
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
                            usage: int = 0, composer: int = 0, hotkeys: int = 0,
                            omit_junit: bool = False,
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
            "mkdir -p android/app/build/outputs/androidTest-results/connected/debug\n"
            "find android/app/build/outputs/androidTest-results/connected/debug -maxdepth 1 -name 'TEST-*.xml' -delete\n"
            "printf '<testsuite tests=\"1\"><testcase classname=\"com.pocketshell.app.smoke.JsComposerDockerJourneyTest\" name=\"run\"/></testsuite>\\n' "
            "> android/app/build/outputs/androidTest-results/connected/debug/TEST-composer.xml\n"
            "printf '%s\\n%s\\n' \"$PNPM\" \"$PATH\" > \"$FIXTURE_RUNTIME_CAPTURE\"\n"
            "\"$PNPM\" --version >> \"$FIXTURE_RUNTIME_CAPTURE\"\n"
            "exit \"$FIXTURE_COMPOSER_STATUS\"\n"
        )
        fake_composer.chmod(0o755)
        fake_hotkeys = fake_scripts / "connected-js-hotkeys-docker.sh"
        fake_hotkeys.write_text(
            "#!/bin/bash\n"
            "printf 'hotkeys\\t%s\\t%s\\n' \"$FIXTURE_HOTKEYS_STATUS\" \"$*\" >> \"$FIXTURE_TRACE\"\n"
            "exit \"$FIXTURE_HOTKEYS_STATUS\"\n"
        )
        fake_hotkeys.chmod(0o755)
        for checker in ("check-js-composer-journey-results.py", "check-js-hotkeys-journey-results.py"):
            fake_checker = fake_scripts / checker
            fake_checker.write_text("#!/bin/bash\nexit 0\n")
            fake_checker.chmod(0o755)

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
            "FIXTURE_HOTKEYS_STATUS": str(hotkeys),
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
        expected_composer_copy = 31 if fail_junit_copy else 0
        expected_composer_junit = 1 if expected_composer_copy else 0
        expected_summary = (
            f"Packaged API 35 lane statuses: smoke={smoke} lifecycle={lifecycle} "
            f"usage-ports={usage} composer={composer} composer-junit-copy={expected_composer_copy} "
            f"composer-junit={expected_composer_junit} hotkeys={hotkeys} hotkeys-junit=0 "
            f"smoke-junit-copy={expected_copy}"
        )
        expected_exit = 1 if any((smoke, lifecycle, usage, composer, expected_composer_copy,
                                  expected_composer_junit, hotkeys, expected_copy)) else 0
        if result.returncode != expected_exit or expected_summary not in result.stdout:
            raise AssertionError(
                f"{label}: wrapper did not preserve its lane statuses: exit={result.returncode}, "
                f"stdout={result.stdout!r}, stderr={result.stderr!r}"
            )
        trace_lines = trace.read_text().splitlines()
        if [line.split("\t", 1)[0] for line in trace_lines] != ["smoke", "lifecycle", "usage-ports", "composer", "hotkeys"]:
            raise AssertionError(f"{label}: wrapper failed to execute every lane in order: {trace_lines!r}")
        if "--run-id js2861-run-1" not in trace_lines[1]:
            raise AssertionError(f"{label}: lifecycle run identity was not forwarded: {trace_lines[1]!r}")
        if "--run-id js2859-run-1" not in trace_lines[2]:
            raise AssertionError(f"{label}: usage/ports run identity was not forwarded: {trace_lines[2]!r}")
        if "--session-prefix js2891-run-1" not in trace_lines[3]:
            raise AssertionError(f"{label}: composer session identity was not forwarded: {trace_lines[3]!r}")
        if "--force-first-post-attach-tap-miss" not in trace_lines[3] \
                or "--composer-focus-max-attempts 2" not in trace_lines[3]:
            raise AssertionError(
                f"{label}: composer CI gate did not require bounded recovery from a physical post-attach miss: "
                f"{trace_lines[3]!r}"
            )
        if "--session-prefix js2884-run-1" not in trace_lines[4]:
            raise AssertionError(f"{label}: fast-key session identity was not forwarded: {trace_lines[4]!r}")
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
        copied_composer_junit = fake_repo / "android/app/build/outputs/js-composer-results/TEST-composer.xml"
        if copied_composer_junit.exists() != (not fail_junit_copy):
            raise AssertionError(f"{label}: composer JUnit copy did not survive the hotkeys lane")


exercise_packaged_lanes("success")
exercise_packaged_lanes("smoke failure is fail-closed", smoke=17)
exercise_packaged_lanes("lifecycle failure is fail-closed", lifecycle=19)
exercise_packaged_lanes("usage/ports failure is fail-closed", usage=21)
exercise_packaged_lanes("composer failure is fail-closed", composer=23)
exercise_packaged_lanes("fast-key failure is fail-closed", hotkeys=29)
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
