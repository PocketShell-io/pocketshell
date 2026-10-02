#!/usr/bin/python3 -I
"""Pin Usage/Ports cleanup to a physically opened, ready Composer."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "android/app/src/androidTest/java/com/pocketshell/app/smoke/UsagePortsDockerJourneyTest.java"
# The one physical-tap injector every packaged journey shares (#2884, #2946).
PHYSICAL_TAP_SOURCE = ROOT / "android/app/src/androidTest/java/com/pocketshell/app/smoke/PhysicalTap.java"


class GateFailure(ValueError):
    pass


def method_body(source: str, method_name: str) -> str:
    signature = re.search(rf"\b(?:public|private|protected)\s+[\w<>]+\s+{re.escape(method_name)}\s*\(", source)
    if signature is None:
        raise GateFailure(f"missing method {method_name}")
    start = signature.start()
    opening = source.find("{", start)
    if opening < 0:
        raise GateFailure(f"method {method_name} has no body")
    depth = 0
    quote = ""
    escaped = False
    line_comment = False
    block_comment = False
    index = opening
    while index < len(source):
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
        elif block_comment:
            if char == "*" and following == "/":
                block_comment = False
                index += 1
        elif quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
        elif char == "/" and following == "/":
            line_comment = True
            index += 1
        elif char == "/" and following == "*":
            block_comment = True
            index += 1
        elif char in ('"', "'"):
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:index]
        index += 1
    raise GateFailure(f"method {method_name} has an unterminated body")


def validate_physical_tap(tap_source: str) -> None:
    """The shared tap stamps a finger DOWN/UP pair up front and injects them back to back."""
    for text in (tap_source,):
        found = NON_POINTER_INPUT.search(text)
        if found:
            raise GateFailure(f"the shared physical tap may inject only a finger touch, not {found.group(0)}")
    tap = method_body(tap_source.replace("public static Result tap(", "public Result tap(", 1), "tap")
    touch = method_body(tap_source.replace("static MotionEvent touch(", "private MotionEvent touch(", 1), "touch")
    if tap.count("injectInputEvent(") != 2 or "automation.injectInputEvent(down, false)" not in tap \
       or "automation.injectInputEvent(up, true)" not in tap:
        raise GateFailure("the shared tap must queue ACTION_DOWN and inject ACTION_UP synchronously right after it")
    if "long upTime = downTime + TAP_DURATION_MS;" not in tap \
       or "touch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)" not in tap \
       or "touch(downTime, upTime, MotionEvent.ACTION_UP, x, y)" not in tap:
        raise GateFailure("the shared tap must stamp both events before injecting either")
    if tap.index("touch(downTime, upTime, MotionEvent.ACTION_UP, x, y)") > tap.index("injectInputEvent(down, false)"):
        raise GateFailure("the ACTION_UP must be built before ACTION_DOWN is injected")
    if "sleep" in tap:
        raise GateFailure("the shared tap must not sleep between ACTION_DOWN and ACTION_UP")
    if touch.count("MotionEvent.obtain(") != 1 or "InputDevice.SOURCE_TOUCHSCREEN" not in touch \
       or "properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;" not in touch:
        raise GateFailure("the shared tap must be a single-finger touchscreen MotionEvent")


def validate(source: str, tap_source: str | None = None) -> None:
    validate_physical_tap(PHYSICAL_TAP_SOURCE.read_text(encoding="utf-8") if tap_source is None else tap_source)
    primary = method_body(source, "runUsageAndPortForwardingPoliciesUseDockerAndNativePlugin")
    start_marker_at = primary.find('String serverStartedMarker = marker(runId, "HTTP_STARTED")')
    settings_at = primary.find('click("[aria-label=\'Settings\']")')
    start_send_at = primary.find("sendComposerCommandAndAwaitMarker(", start_marker_at, settings_at)
    if min(start_marker_at, settings_at, start_send_at) < 0 or not start_marker_at < start_send_at < settings_at:
        raise GateFailure("HTTP fixture startup must use the packaged Composer before opening Settings")
    start_send = primary[start_send_at:settings_at]
    if "serverStartedMarker" not in start_send or '"start test HTTP service", sessionTag, "HTTP_START"' not in start_send:
        raise GateFailure("HTTP fixture startup must retain its marker and HTTP_START evidence phase")
    if "sendCommandAndAwaitMarker(" in primary[start_marker_at:settings_at]:
        raise GateFailure("HTTP fixture startup must not inject the command through native terminal input")

    helper = method_body(source, "openHomeLiveComposerAndAwaitConnectedTransport")
    focused_draft = "document.activeElement === document.querySelector('[data-testid=prompt-draft]')"
    required = (
        "boolean promptSheetOpenOnEntry = \"true\".equals(evalRaw(promptSheetOpenExpression()));",
        "if (bestEffortAfterFailure && promptSheetOpenOnEntry) {",
        "} else {",
        "assertFalse(\"the Prompt sheet must be closed when \" + eventPrefix",
        "promptSheetOpenOnEntry);",
        "openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);",
        "String composerDraftTapReady = visibleComposerExpression()",
        "awaitJsTrue(composerDraftTapReady, 15_000);",
        "boolean composerVisible = \"true\".equals(evalRaw(visibleComposerExpression()))",
        "boolean composerDraftFocused = \"true\".equals(evalRaw(",
        "if (!composerVisible || !composerDraftFocused)",
        "openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);",
        "awaitJsTrue(composerReady, 15_000);",
        "document.querySelector('[data-testid=prompt-composer]')?.dataset.transportState === 'connected'",
    )
    positions: list[int] = []
    for token in required:
        position = helper.find(token)
        if position < 0:
            raise GateFailure(f"Composer helper is missing required step: {token}")
        positions.append(position)
    if positions != sorted(positions):
        raise GateFailure("the returned Home Composer must be visibly tappable before its state or transport is read")

    tap_ready_match = re.search(r"String\s+composerDraftTapReady\s*=\s*(.*?);", helper, re.DOTALL)
    if tap_ready_match is None:
        raise GateFailure("Composer must define a physical draft target readiness predicate")
    tap_ready_predicate = tap_ready_match.group(1)
    if "visibleComposerExpression()" not in tap_ready_predicate or "composerDraftTapTargetExpression()" not in tap_ready_predicate:
        raise GateFailure("Composer tap readiness must require a visible surface and hittable draft center")
    if "awaitJsTrue(composerDraftTapReady, 15_000);" not in helper:
        raise GateFailure("Composer tap readiness must be awaited before checking focus or opening it")
    tap_target = method_body(source, "composerDraftTapTargetExpression")
    if "elementFromPoint" not in tap_target or "===draft" not in tap_target:
        raise GateFailure("Composer tap readiness must verify Android's CSS center maps to the draft itself")

    ready_match = re.search(r"String\s+composerReady\s*=\s*(.*?);", helper, re.DOTALL)
    if ready_match is None:
        raise GateFailure("Composer must define a readiness predicate before waiting for it")
    ready_predicate = ready_match.group(1)
    if "visibleComposerExpression()" not in ready_predicate or focused_draft not in ready_predicate:
        raise GateFailure("Composer readiness wait must require visible UI and focused Prompt draft")

    connected_read = helper.find("dataset.transportState === 'connected'")
    connected_wait_start = helper.rfind("awaitJsTrue(", 0, connected_read)
    if connected_read < 0 or connected_wait_start < 0:
        raise GateFailure("connected Composer transport must be awaited after the open action")
    connected_wait = helper[connected_wait_start:connected_read]
    if "visibleComposerExpression()" not in connected_wait or focused_draft not in connected_wait:
        raise GateFailure("connected transport may only be read after visible, focused Composer state")

    tap_helper = method_body(source, "openComposerWithPhysicalDraftTap")
    if "tapComposerDraftCenter()" not in tap_helper:
        raise GateFailure("closed Composer must open through the native draft-center touch path")
    draft_tap = method_body(source, "tapComposerDraftCenter")
    if 'tapElementCenter("[data-testid=prompt-draft]", "Composer draft", false)' not in draft_tap:
        raise GateFailure("draft-center tap must hit the draft itself through the shared native tap path")
    tap_method = method_body(source, "tapElementCenter")
    if tap_method.count("PhysicalTap.tap(screen[0], screen[1])") != 1:
        raise GateFailure("draft-center tap must be delivered through the shared Android touchscreen tap")
    if "event.isTrusted===true&&event.targetIsDraft===true" not in tap_helper:
        raise GateFailure("Composer opening must prove a trusted pointer-down on the draft")
    retry_loop = "for (int attempt = 1; attempt <= 3; attempt++)"
    retry_guard = "if (attempt < 3) {"
    retry_wait = "after = awaitComposerDraftTapLayout(tap);"
    tap_at = tap_helper.find("tap = tapComposerDraftCenter();")
    retry_guard_at = tap_helper.find(retry_guard)
    retry_wait_at = tap_helper.find(retry_wait)
    if retry_loop not in tap_helper or retry_guard_at < 0 or retry_wait_at < 0 \
       or not tap_at < retry_guard_at < retry_wait_at:
        raise GateFailure("a missed tap must allow at most three fresh-coordinate attempts, stabilizing the draft before each retry")
    if 'trustedDraftTapComplete = "true".equals(evalRaw(completedTrustedDraftTapExpression()));' not in tap_helper \
       or 'assertTrue("opening the Composer must record a completed trusted pointerdown/pointerup/click on its draft;' not in tap_helper:
        raise GateFailure("Composer open must require a completed trusted draft tap, including its click")
    if 'assertTrue("the completed physical draft tap must leave the Prompt draft focused;' not in tap_helper:
        raise GateFailure("Composer open must retain its focused-draft assertion after the trusted click")
    tap_event_trace = 'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");'
    first_tap_assertion = tap_helper.find('assertTrue("opening the Composer must follow a trusted Android pointer-down')
    if tap_event_trace not in tap_helper or tap_helper.find(tap_event_trace) > first_tap_assertion \
       or '"; events=" + tapEvents' not in tap_helper:
        raise GateFailure("Composer tap failures and success evidence must retain the complete pointer event trace")
    recorder = method_body(source, "installComposerOpenTapRecorder")
    if 'return completedTrustedTapExpression("targetIsDraft");' not in method_body(source, "completedTrustedDraftTapExpression"):
        raise GateFailure("completed draft tap proof must pair trusted events on the draft")
    completed_tap = method_body(source, "completedTrustedTapExpression")
    if "for(const type of ['pointerdown','pointerup','click','keydown','keypress','keyup'])" not in recorder \
       or "detail:typeof event.detail==='number'?event.detail:-1" not in recorder:
        raise GateFailure("the tap recorder must record click detail and every keyboard event")
    for event_type in ("pointerdown", "pointerup", "click"):
        if f"'{event_type}'" not in recorder or f"type!=='{event_type}'" not in completed_tap:
            raise GateFailure(f"completed trusted tap proof must record and require {event_type}")
    for proof in (
        "event.isTrusted===true",
        "event.target===draft",
        "let upIndex=clickIndex-1",
        "let downIndex=upIndex-1",
        "down.pointerId===up.pointerId",
        "click.isTrusted!==true",
        '"||click." + targetFlag + "!==true)',
        '"const down=events[downIndex];return up.isTrusted===true&&up." + targetFlag + "===true"',
        '"&&down.isTrusted===true&&down." + targetFlag + "===true"',
        "targetIsLauncher:!!event.target?.closest?.('[data-testid=prompt-composer-launcher]')",
    ):
        if proof not in recorder + completed_tap:
            raise GateFailure(f"completed trusted tap proof is missing {proof}")
    if "SystemClock.sleep(" in tap_method:
        raise GateFailure("draft tap must not sleep between measuring and tapping (stale coordinates)")
    layout_wait = method_body(source, "awaitComposerDraftTapLayout")
    if "nativeState.optBoolean(\"imeVisible\")" not in layout_wait \
       or "stableSamples >= 2" not in layout_wait \
       or "SystemClock.uptimeMillis() - startedAt >= 200" not in layout_wait \
       or "composerDraftTapTargetExpression()" not in layout_wait:
        raise GateFailure("fresh tap retry must wait for IME and stable, hittable draft bounds")
    for diagnostic in (
        "draftDisabled",
        "route:shell?.dataset.route||''",
        "activeElement:label(document.activeElement)",
        "keyboardVisible:shell?.dataset.keyboardVisible==='true'",
        "imeVisible",
    ):
        if diagnostic not in source:
            raise GateFailure(f"physical-open diagnostics are missing {diagnostic}")

    validate_launcher(source)

    sender = method_body(source, "sendComposerCommandAndAwaitMarker")
    sender_order = (
        "openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix, bestEffortAfterFailure);",
        "awaitJsTrue(visibleComposerExpression(), 15_000);",
        "setValue(\"[data-testid=prompt-draft]\", command);",
        "click(\".composer-shared-controls .send\");",
    )
    sender_positions = [sender.find(token) for token in sender_order]
    if any(position < 0 for position in sender_positions) or sender_positions != sorted(sender_positions):
        raise GateFailure("cleanup command must wait for the opened Composer before setting and sending its draft")


# Anything that names the Prompt launcher (its test id, selector constant, class,
# aria text, group or emitted event) or a JS variable/method derived from it.
LAUNCHER_ROOTS = (
    "launcher",
    "open prompt composer",
    "mobile-hotkeys__key",
    "mobile-hotkeys-prompt-group",
    "prompt input",
    "dock-label",
    "opencomposer",
)
# Any way to activate a DOM element or flip the sheet without trusted input.
SCRIPTED_ACTIVATION = re.compile(
    r"click\s*\("                       # Java click(...) helper, JS el.click(), el?.click(), jsClick(...)
    r"|\[\s*['\"]click['\"]\s*\]\s*\("     # el['click']()
    r"|dispatchevent"
    r"|new\s+\w*event\s*\("               # new MouseEvent/PointerEvent/TouchEvent/Event(...)
    r"|initmouseevent|initevent"
    r"|\bonclick\b"
    r"|(?-i:\.focus)\b|\[\s*['\"]focus['\"]\s*\]"  # focusing the launcher arms keyboard activation
    r"|requestsubmit|\.submit\s*\("
    r"|promptcomposeropen\s*=(?!=)"      # dataset write that fakes the open state
    r"|mobilepromptcomposeropen"
    r"|__vue",
    re.IGNORECASE,
)
# App-state writes the journey may never make, whatever element they name.
FORBIDDEN_ANYWHERE = re.compile(
    r"promptcomposeropen\s*=(?!=)|mobilepromptcomposeropen|__vue|_vnode|setupstate|openpromptcomposer",
    re.IGNORECASE,
)


def _code_units(source: str) -> list[tuple[str, str]]:
    """Split Java into statements outside strings/comments.

    Returns (code_without_strings, code_with_joined_strings) per statement, so a
    selector split across `"..." + "..."` still reads as one token.
    """
    units: list[tuple[str, str]] = []
    bare: list[str] = []
    full: list[str] = []
    quote = ""
    escaped = False
    index = 0
    while index < len(source):
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if quote:
            full.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
                bare.append(char)
        elif char == "/" and following == "/":
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
            continue
        elif char == "/" and following == "*":
            end = source.find("*/", index + 2)
            index = len(source) if end < 0 else end + 2
            continue
        elif char in ('"', "'"):
            quote = char
            bare.append(char)
            full.append(char)
        elif char in ";{}":
            units.append(("".join(bare), "".join(full)))
            bare, full = [], []
        else:
            bare.append(char)
            full.append(char)
        index += 1
    units.append(("".join(bare), "".join(full)))
    return [(b, re.sub(r'"\s*\+\s*"', "", f)) for b, f in units if f.strip()]


def _methods(source: str) -> dict[str, tuple[str, str]]:
    methods: dict[str, tuple[str, str]] = {}
    for match in re.finditer(r"\b(?:public|private|protected)\s+(?:static\s+)?([\w<>\[\]]+)\s+(\w+)\s*\(", source):
        try:
            methods[match.group(2)] = (match.group(1), method_body(source, match.group(2)))
        except GateFailure:
            continue
    return methods


def reject_scripted_launcher_activation(source: str) -> None:
    """Structural D22 rule: no statement may both reach the launcher and script an activation.

    A statement "reaches" the launcher when it names it directly or through any
    local/field or String-returning method whose value was derived from it. A
    statement "scripts an activation" when it contains a click/dispatch/state
    write token, or calls any method that (transitively) does.
    """
    # Join Java ("a" + "b") and JS ('a'+'b') literal splits before matching.
    joined = re.sub(r"//[^\n]*|/\*.*?\*/", "", source, flags=re.DOTALL)
    joined = re.sub(r"[\"']\s*\+\s*[\"']", "", joined)
    forbidden = FORBIDDEN_ANYWHERE.search(joined)
    if forbidden:
        raise GateFailure(f"the journey must never write Prompt open state from script: {forbidden.group(0)}")
    units = _code_units(source)
    methods = _methods(source)

    tainted: set[str] = set()

    def reaches_launcher(text: str, code: str | None = None) -> bool:
        lowered = text.lower()
        if any(root in lowered for root in LAUNCHER_ROOTS):
            return True
        # Tainted Java identifiers only count in code, not inside JS strings.
        code = text if code is None else code
        return any(re.search(rf"(?<![\w.]){re.escape(name)}\b", code) for name in tainted)

    changed = True
    while changed:
        changed = False
        for bare, full in units:
            if not reaches_launcher(full, bare):
                continue
            assigned = re.search(r"(\w+)\s*(?:\+)?=(?!=)", bare)
            if assigned and assigned.group(1) not in tainted:
                tainted.add(assigned.group(1))
                changed = True
        for name, (return_type, body) in methods.items():
            if name not in tainted and return_type == "String" and any(
                    reaches_launcher(full, bare) for bare, full in _code_units(body)):
                tainted.add(name)
                changed = True

    code = {name: _code_units(body) for name, (_, body) in methods.items()}
    activators: set[str] = {name for name, units_of in code.items()
                            if any(SCRIPTED_ACTIVATION.search(full) for _, full in units_of)}
    changed = True
    while changed:
        changed = False
        for name, units_of in code.items():
            if name in activators:
                continue
            bare_body = "\n".join(bare for bare, _ in units_of)
            if any(re.search(rf"(?<![\w.]){re.escape(activator)}\s*\(", bare_body) for activator in activators):
                activators.add(name)
                changed = True

    for bare, full in units:
        activation = SCRIPTED_ACTIVATION.search(full)
        called = next((name for name in activators
                       if re.search(rf"(?<![\w.]){re.escape(name)}\s*\(", bare)), None)
        if not activation and called is None:
            continue
        if reaches_launcher(full, bare) or "elementfrompoint" in full.lower():
            token = activation.group(0) if activation else f"{called}(...)"
            raise GateFailure("Prompt must not be opened by a scripted DOM activation of its launcher "
                              f"({token}): {full.strip()[:160]}")


# Native input that is not the one sanctioned finger tap: keyboard injection,
# shell/accessibility/view-level activation, and other test drivers.
NON_POINTER_INPUT = re.compile(
    r"\bKeyEvent\b|KEYCODE_|\bsendKey\w*|sendCharacterSync|sendStringSync|sendText\s*\("
    r"|injectKeyEvent|dispatchKey\w*|dispatchTouchEvent|dispatchGenericMotionEvent|onTouchEvent\s*\("
    r"|performClick|callOnClick|performLongClick|performAccessibilityAction|AccessibilityNodeInfo|ACTION_CLICK"
    r"|InputConnection|commitText|executeShellCommand|Runtime\.getRuntime|ProcessBuilder"
    r"|uiautomator|\bUiDevice\b|\bUiObject2?\b|espresso|\bonView\s*\(",
)


def reject_non_pointer_input(source: str) -> None:
    """The only native input allowed is tapElementCenter's finger ACTION_DOWN/ACTION_UP pair."""
    code = "\n".join(bare for bare, _ in _code_units(source))
    # _code_units keeps strings in `full`; check both so a string-built shell
    # command or reflective class name is caught too.
    full = "\n".join(full for _, full in _code_units(source))
    for text in (code, full):
        found = NON_POINTER_INPUT.search(text)
        if found:
            raise GateFailure(f"the journey may inject only the physical finger tap, not {found.group(0)}")
    tap = method_body(source, "tapElementCenter")
    for token in ("injectInputEvent", "MotionEvent.obtain", "getUiAutomation().inject"):
        if token in source:
            raise GateFailure(f"{token} may only be used by the shared PhysicalTap helper")
    if source.count("PhysicalTap.tap(") != 1 or tap.count("PhysicalTap.tap(screen[0], screen[1])") != 1:
        raise GateFailure("the journey must inject exactly one tap shape: the shared PhysicalTap from tapElementCenter")


def validate_launcher(source: str) -> None:
    """The Android Prompt sheet closes off Home; reopen it only by a physical launcher tap (D22: no DOM fallback)."""
    reject_scripted_launcher_activation(source)
    reject_non_pointer_input(source)
    if "openComposerIfClosedAndAwaitTransport" in source:
        raise GateFailure("the #2897 DOM-click opener must stay deleted (D22)")
    primary_flags = re.findall(r"/\* bestEffortAfterFailure= \*/ (true|false)\)", source)
    after_body = method_body(source, "closeShell")
    if primary_flags.count("true") != 1 or primary_flags.count("false") != 2 \
       or "/* bestEffortAfterFailure= */ true)" not in after_body:
        raise GateFailure("only the @After fallback may accept an already-open Prompt sheet; "
                          "both journey phases must require it closed and physically opened")
    sheet_open = method_body(source, "promptSheetOpenExpression")
    for proof in ("promptComposerOpen==='true'", "getAttribute('role')==='dialog'", "getAttribute('aria-modal')==='true'"):
        if proof not in sheet_open:
            raise GateFailure(f"Prompt sheet open state must require {proof}")
    launcher = method_body(source, "openComposerWithPhysicalLauncherTap")
    ordered = (
        "awaitJsTrue(launcherIdle, 15_000);",
        "installComposerOpenTapRecorder();",
        "for (int attempt = 1; attempt <= 3; attempt++)",
        "JSONObject stableLayout = awaitPromptLauncherTapLayout(taps);",
        "tap = tapPromptLauncherCenter();",
        "trustedLauncherClickSeen = \"true\".equals(evalRaw(",
        '"(window.__ps2908ComposerOpenPointerEvents||[]).some(event=>event.type===\'click\'"',
        '+ "&&event.isTrusted===true&&event.targetIsLauncher===true)"));',
        'promptSheetOpen = "true".equals(evalRaw(promptSheetOpenExpression()));',
        "if (promptSheetOpen) break;",
        "if (trustedLauncherClickSeen) {",
        'throw new AssertionError("a completed trusted launcher click did not open the Prompt sheet; "',
        'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");',
        'boolean trustedLauncherTapComplete = "true".equals(evalRaw(completedTrustedLauncherTapExpression()));',
        "JSONObject input = new JSONObject(evalString(launcherInputCountsExpression()));",
        'assertTrue("opening Prompt must record a completed trusted pointerdown/pointerup/click on its launcher; "',
        'assertTrue("the completed physical launcher tap must open the Prompt sheet; " + evidence, promptSheetOpen);',
        'assertEquals("exactly one trusted click may reach the Prompt launcher; " + evidence, 1, launcherClicks);',
        'assertEquals("the Prompt launcher click must be pointer-generated (detail >= 1), never a keyboard "',
        '+ "or synthetic activation; " + evidence, 0, zeroDetailLauncherClicks);',
        'assertEquals("no keyboard event may target the Prompt launcher; " + evidence, 0, keyboardLauncherEvents);',
        'assertEquals("no keyboard input may occur while the Prompt launcher is being opened; " + evidence,',
        "0, keyEvents);",
        'assertEquals("the Prompt launcher must receive no scripted (untrusted) events; " + evidence,',
        "0, untrustedLauncherEvents);",
        '+ " sheetOpenOnEntry=" + sheetOpenOnEntry',
        '+ " attempts=" + attempts',
        '+ " trustedLauncherTapComplete=" + trustedLauncherTapComplete',
        '+ " launcherClicks=" + launcherClicks',
        '+ " zeroDetailLauncherClicks=" + zeroDetailLauncherClicks',
        '+ " keyboardLauncherEvents=" + keyboardLauncherEvents',
        '+ " keyEvents=" + keyEvents',
        '+ " untrustedLauncherEvents=" + untrustedLauncherEvents',
    )
    positions = [launcher.find(token) for token in ordered]
    for token, position in zip(ordered, positions):
        if position < 0:
            raise GateFailure(f"physical launcher open is missing required step: {token}")
    if positions != sorted(positions):
        raise GateFailure("physical launcher open must settle, tap, then prove a trusted click opened the sheet")
    loop_start = launcher.find("for (int attempt = 1; attempt <= 3; attempt++)")
    loop_end = launcher.find('String tapEvents = evalString(')
    loop = launcher[loop_start:loop_end]
    fail_fast = loop[loop.find("if (trustedLauncherClickSeen) {"):]
    if "continue" in loop or '+ "; events=" + ignoredClickEvents' not in fail_fast \
       or 'String ignoredClickEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");' not in fail_fast:
        raise GateFailure("a completed trusted launcher click that leaves the sheet closed must fail at once "
                          "with its event trace; only a missed tap may be retried")
    launcher_proof = method_body(source, "completedTrustedLauncherTapExpression")
    for proof in (
        "event.type==='click'",
        "&&event.isTrusted===true&&event.targetIsLauncher===true);",
        "if(clicks.length!==1)return false;",
        "!(click.detail>=1)",
        "click.pointerType!=='touch'",
        "const up=events[clickIndex-1],down=events[clickIndex-2];",
        "return up.type==='pointerup'&&down.type==='pointerdown'",
        "&&[up,down].every(event=>event.isTrusted===true&&event.targetIsLauncher===true",
        "&&event.pointerType==='touch')&&down.pointerId===up.pointerId;",
    ):
        if proof not in launcher_proof:
            raise GateFailure(f"launcher tap proof must bind one pointer click to its own adjacent touch pair: {proof}")
    if "completedTrustedTapExpression(" in launcher_proof or "while(" in launcher_proof:
        raise GateFailure("launcher tap proof must not walk back across events to borrow an earlier pointer pair")
    counts = method_body(source, "launcherInputCountsExpression")
    for proof in (
        "launcherClicks:events.filter(event=>event.type==='click'&&event.isTrusted===true&&launcher(event)).length",
        "zeroDetailLauncherClicks:events.filter(event=>event.type==='click'&&launcher(event)&&!(event.detail>=1)).length",
        "keyboardLauncherEvents:events.filter(event=>key(event)&&launcher(event)).length",
        "keyEvents:events.filter(key).length",
        "untrustedLauncherEvents:events.filter(event=>launcher(event)&&event.isTrusted!==true).length",
        "const key=event=>event.type.startsWith('key');",
    ):
        if proof not in counts:
            raise GateFailure(f"launcher input-modality counts are missing {proof}")
    if '"; events=" + tapEvents' not in launcher:
        raise GateFailure("launcher tap evidence must retain the complete pointer event trace")
    if "tapElementCenter(PROMPT_LAUNCHER_SELECTOR, \"Prompt launcher\", true)" not in method_body(source, "tapPromptLauncherCenter"):
        raise GateFailure("launcher tap must use the shared native touch path")
    layout_wait = method_body(source, "awaitPromptLauncherTapLayout")
    for proof in (
        'latest.optBoolean("launcherCenterHitIsLauncher")',
        'nativeState.optInt("webViewHeightPx")',
        'nativeState.optBoolean("imeVisible")',
        "stableSamples >= 5 && now - stableSince >= 400",
    ):
        if proof not in layout_wait:
            raise GateFailure(f"launcher tap must wait for a stable, hittable dock layout: {proof}")


def self_test() -> int:
    source = DEFAULT_SOURCE.read_text(encoding="utf-8")
    validate(source)

    mutants = (
        (
            "physical draft target wait removed",
            "        String composerDraftTapReady = visibleComposerExpression()\n"
            "                + \" && \" + composerDraftTapTargetExpression();\n"
            "        awaitJsTrue(composerDraftTapReady, 15_000);\n\n",
            "        // no wait for Home to become tappable\n\n",
        ),
        (
            "transport readiness read before physical Composer open",
            "        boolean composerVisible = \"true\".equals(evalRaw(visibleComposerExpression()));",
            "        evalRaw(\"document.querySelector('[data-testid=prompt-composer]')?.dataset.transportState === 'connected'\");\n"
            "        boolean composerVisible = \"true\".equals(evalRaw(visibleComposerExpression()));",
        ),
        (
            "native tap removed",
            "openComposerWithPhysicalDraftTap(sessionTag, eventPrefix);",
            "// Composer already appears open",
        ),
        (
            "readiness wait removed",
            "awaitJsTrue(composerReady, 15_000);",
            "// no Composer readiness wait",
        ),
    )
    mutants += (
        (
            "HTTP fixture startup routes through native terminal input",
            'sendComposerCommandAndAwaitMarker(\n                "python3 -m http.server',
            'sendCommandAndAwaitMarker(\n                "python3 -m http.server',
        ),
        (
            "visible Composer readiness predicate removed",
            "String composerReady = visibleComposerExpression()",
            "String composerReady = \"true\"",
        ),
        (
            "focused draft readiness predicate removed",
            "String composerReady = visibleComposerExpression()\n"
            "                + \" && document.activeElement === document.querySelector('[data-testid=prompt-draft]')\";",
            "String composerReady = visibleComposerExpression();",
        ),
        (
            "connected wait no longer requires focused draft",
            "                + \" && document.activeElement === document.querySelector('[data-testid=prompt-draft]')\"\n"
            "                + \" && document.querySelector('[data-testid=prompt-composer]')?.dataset.transportState === 'connected'\",",
            "                + \" && document.querySelector('[data-testid=prompt-composer]')?.dataset.transportState === 'connected'\",",
        ),
        (
            "completed trusted draft click proof removed",
            'trustedDraftTapComplete = "true".equals(evalRaw(completedTrustedDraftTapExpression()));',
            'draftFocused = "true".equals(evalRaw(ready));',
        ),
        (
            "fresh tap after IME resize removed",
            "after = awaitComposerDraftTapLayout(tap);",
            'after = readComposerOpenState("after-physical-draft-tap-attempt-" + attempt);',
        ),
        (
            "trusted click event recorder removed",
            "for(const type of ['pointerdown','pointerup','click','keydown','keypress','keyup'])",
            "for(const type of ['pointerdown','pointerup','keydown','keypress','keyup'])",
        ),
        (
            "stale 60 ms coordinate interval restored",
            "PhysicalTap.Result tapResult = PhysicalTap.tap(screen[0], screen[1]);",
            "SystemClock.sleep(60);\n        PhysicalTap.Result tapResult = PhysicalTap.tap(screen[0], screen[1]);",
        ),
        (
            "completed click no longer paired with the preceding pointer events",
            "let upIndex=clickIndex-1;",
            "let upIndex=0;",
        ),
        (
            "stable IME layout wait removed",
            "SystemClock.uptimeMillis() - startedAt >= 200",
            "SystemClock.uptimeMillis() - startedAt >= 0",
        ),
        (
            "third fresh-coordinate retry removed",
            "for (int attempt = 1; attempt <= 3; attempt++) {\n            JSONObject tap;",
            "for (int attempt = 1; attempt <= 2; attempt++) {\n            JSONObject tap;",
        ),
        (
            "retry cap made unbounded beyond three attempts",
            "for (int attempt = 1; attempt <= 3; attempt++) {\n            JSONObject tap;",
            "for (int attempt = 1; attempt <= 4; attempt++) {\n            JSONObject tap;",
        ),
        (
            "launcher retries made unbounded beyond three attempts",
            "for (int attempt = 1; attempt <= 3; attempt++) {\n            attempts = attempt;",
            "for (int attempt = 1; attempt <= 4; attempt++) {\n            attempts = attempt;",
        ),
        (
            "physical launcher open removed",
            "            openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);\n",
            "            // Prompt sheet assumed open\n",
        ),
        (
            "synthetic DOM launcher click fallback added",
            "            openComposerWithPhysicalLauncherTap(sessionTag, eventPrefix);\n",
            "            click(PROMPT_LAUNCHER_SELECTOR);\n",
        ),
        (
            "launcher stable-layout wait removed",
            "            JSONObject stableLayout = awaitPromptLauncherTapLayout(taps);\n",
            "            JSONObject stableLayout = readComposerOpenState(\"unsettled\");\n",
        ),
        (
            "launcher stable interval shortened",
            "stableSamples >= 5 && now - stableSince >= 400",
            "stableSamples >= 1 && now - stableSince >= 0",
        ),
        (
            "trusted launcher click proof removed",
            'trustedLauncherTapComplete = "true".equals(evalRaw(completedTrustedLauncherTapExpression()));',
            'trustedLauncherTapComplete = true;',
        ),
        (
            "launcher proof walks back to borrow an earlier pointer pair",
            "const up=events[clickIndex-1],down=events[clickIndex-2];",
            "let u=clickIndex-1;while(u>0&&events[u].type!=='pointerup')u--;const up=events[u],down=events[u-1];",
        ),
        (
            "Prompt sheet open state no longer requires the dialog",
            "&&composer?.getAttribute('aria-modal')==='true';",
            ";",
        ),
        (
            "launcher retried after the sheet opened",
            "            if (promptSheetOpen) break;\n",
            "",
        ),
        (
            "completed pointer event trace omitted from evidence",
            'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");\n\n'
            '        assertTrue("opening the Composer must follow',
            'String tapEvents = "[]";\n\n        assertTrue("opening the Composer must follow',
        ),
        (
            "launcher pointer event trace omitted from evidence",
            'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");\n'
            '        String evidence = ',
            'String tapEvents = "[]";\n        String evidence = ',
        ),
    )
    entry = (
        '        boolean promptSheetOpenOnEntry = "true".equals(evalRaw(promptSheetOpenExpression()));\n'
    )

    def before_entry(injected: str) -> tuple[str, str, str]:
        return entry, injected + entry

    mutants += (
        # Reviewer m7/m8 (#2908): any scripted activation of the launcher is red,
        # whatever form it takes, not just a list of literal strings.
        ("JS .click() on the launcher before the sheet check (m7)",) + before_entry(
            "        evalString(\"document.querySelector('[data-testid=prompt-composer-launcher]')?.click()\");\n"),
        ("JS dispatchEvent(new MouseEvent) on the launcher (m8)",) + before_entry(
            "        evalString(\"document.querySelector('[data-testid=prompt-composer-launcher]')"
            "?.dispatchEvent(new MouseEvent('click',{bubbles:true}))\");\n"),
        ("JS dispatchEvent(new PointerEvent) through the selector constant",) + before_entry(
            "        evalString(\"document.querySelector(\" + JSONObject.quote(PROMPT_LAUNCHER_SELECTOR)"
            " + \").dispatchEvent(new PointerEvent('pointerup',{bubbles:true}))\");\n"),
        ("JS ['click']() on the launcher with a split selector literal",) + before_entry(
            "        evalString(\"document.querySelector('[data-testid=prompt-composer-\" + \"launch\" + \"er]')['click']()\");\n"),
        ("Java click() helper reached through a derived local",) + before_entry(
            "        String dock = PROMPT_LAUNCHER_SELECTOR;\n        String target = dock;\n        click(target);\n"),
        ("click() on the launcher's aria label",) + before_entry(
            "        click(\"[aria-label='Open prompt composer to type or dictate a prompt']\");\n"),
        ("scripted click on whatever sits at a point",) + before_entry(
            "        evalString(\"document.elementFromPoint(40,800)?.click()\");\n"),
        ("Prompt open state written directly",) + before_entry(
            "        evalString(\"document.querySelector('.app-shell').dataset.promptComposerOpen='true'\");\n"),
        (
            "new helper that clicks, called with the launcher selector",
            "    private void click(String selector) throws Exception {\n",
            "    private void pressQuietly(String selector) throws Exception { press(selector); }\n\n"
            "    private void press(String selector) throws Exception {\n"
            "        evalString(\"document.querySelector(\" + JSONObject.quote(selector) + \").click()\");\n    }\n\n"
            "    private void openDock() throws Exception { pressQuietly(PROMPT_LAUNCHER_SELECTOR); }\n\n"
            "    private void click(String selector) throws Exception {\n",
        ),
        (
            "retry after a completed trusted launcher click that left the sheet closed",
            "            if (trustedLauncherClickSeen) {\n"
            "                String ignoredClickEvents",
            "            if (false) {\n"
            "                String ignoredClickEvents",
        ),
        (
            "ignored trusted launcher click retried via continue",
            "            if (trustedLauncherClickSeen) {\n",
            "            if (trustedLauncherClickSeen && attempt < 3) continue;\n"
            "            if (trustedLauncherClickSeen) {\n",
        ),
        (
            "ignored-click failure drops its event trace",
            '                        + "; events=" + ignoredClickEvents + "; after=" + after);',
            '                        + "; after=" + after);',
        ),
        (
            "sheet no longer required closed when a phase starts",
            "            assertFalse(\"the Prompt sheet must be closed when \" + eventPrefix\n"
            "                    + \" starts; only a physical launcher tap may open it\", promptSheetOpenOnEntry);\n",
            "",
        ),
        (
            "cleanup phase accepts an already-open sheet",
            '                command, stoppedMarker, "stop test HTTP service", sessionTag, "HTTP_CLEANUP",\n'
            "                /* bestEffortAfterFailure= */ false);",
            '                command, stoppedMarker, "stop test HTTP service", sessionTag, "HTTP_CLEANUP",\n'
            "                /* bestEffortAfterFailure= */ true);",
        ),
        (
            "untrusted launcher event assertion removed",
            'assertEquals("the Prompt launcher must receive no scripted (untrusted) events; " + evidence,\n'
            "                0, untrustedLauncherEvents);",
            "// untrusted launcher events tolerated",
        ),
        (
            "launcher evidence line drops its entry state",
            '+ " sheetOpenOnEntry=" + sheetOpenOnEntry',
            '+ ""',
        ),
    )
    after_tap = '            taps.put(tap.put("stableLayout", stableLayout));\n'

    def after_launcher_tap(injected: str) -> tuple[str, str]:
        return after_tap, after_tap + injected

    helper_anchor = "    private JSONObject awaitPromptLauncherTapLayout(JSONArray previousTaps) throws Exception {\n"

    def with_helper(call: str, helper: str) -> tuple[str, str, str, str]:
        return after_tap, after_tap + call, helper_anchor, helper + "\n" + helper_anchor

    # Input modality (#2908 re-review): the launcher may be activated only by
    # the one finger tap. Keyboard, native non-pointer, programmatic and
    # framework-internal activation are each red, and so is any weakening of
    # the runtime proof that tells them apart.
    modality_mutants = (
        ("keyboard: sendKeyDownUpSync(ENTER) after the launcher tap",) + after_launcher_tap(
            "            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(66);\n"),
        ("keyboard: reviewer mutant B, launcher .focus() + injected KeyEvent ENTER in a helper",) + with_helper(
            "            settleLauncherFocus();\n",
            "    private void settleLauncherFocus() throws Exception {\n"
            "        evalString(\"document.querySelector(\" + JSONObject.quote(PROMPT_LAUNCHER_SELECTOR) + \")?.focus()\");\n"
            "        var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();\n"
            "        long now = SystemClock.uptimeMillis();\n"
            "        automation.injectInputEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN,\n"
            "                android.view.KeyEvent.KEYCODE_ENTER, 0), true);\n"
            "        automation.injectInputEvent(new android.view.KeyEvent(now, SystemClock.uptimeMillis(),\n"
            "                android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0), true);\n"
            "    }\n"),
        ("keyboard: injected key event built by a factory, no KeyEvent/focus tokens",) + with_helper(
            "            pressEnter();\n",
            "    private void pressEnter() throws Exception {\n"
            "        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(enterDown(), true);\n"
            "    }\n"),
        ("keyboard: sendStringSync newline",) + after_launcher_tap(
            "            InstrumentationRegistry.getInstrumentation().sendStringSync(\"\\n\");\n"),
        ("keyboard: shell input keyevent",) + after_launcher_tap(
            "            InstrumentationRegistry.getInstrumentation().getUiAutomation()"
            ".executeShellCommand(\"input keyevent 66\");\n"),
        ("keyboard: WebView.dispatchKeyEvent",) + after_launcher_tap(
            "            scenario.onActivity(activity -> findWebView(activity.getWindow().getDecorView())"
            ".dispatchKeyEvent(null));\n"),
        ("focus: launcher .focus() through the selector constant",) + after_launcher_tap(
            "            evalString(\"document.querySelector(\" + JSONObject.quote(PROMPT_LAUNCHER_SELECTOR) + \")?.focus()\");\n"),
        ("focus: launcher ['focus']() on a split selector",) + after_launcher_tap(
            "            evalString(\"document.querySelector('[data-testid=prompt-composer-\" + \"launcher]')['focus']()\");\n"),
        ("native: accessibility ACTION_CLICK",) + after_launcher_tap(
            "            scenario.onActivity(activity -> findWebView(activity.getWindow().getDecorView())"
            ".performAccessibilityAction(16, null));\n"),
        ("native: performClick on the WebView",) + after_launcher_tap(
            "            scenario.onActivity(activity -> findWebView(activity.getWindow().getDecorView()).performClick());\n"),
        ("native: a second MotionEvent path outside the finger tap helper",) + with_helper(
            "            tapAgain(tap);\n",
            "    private void tapAgain(JSONObject tap) throws Exception {\n"
            "        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 46, 800, 0);\n"
            "        InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(down, true);\n"
            "    }\n"),
        ("native: a second shared tap inside the tap helper",
         "        PhysicalTap.Result tapResult = PhysicalTap.tap(screen[0], screen[1]);\n",
         "        PhysicalTap.Result tapResult = PhysicalTap.tap(screen[0], screen[1]);\n"
         "        PhysicalTap.tap(screen[0], screen[1]);\n"),
        ("programmatic: Vue setupState.openPromptComposer()",) + after_launcher_tap(
            "            evalString(\"document.querySelector('#app')._vnode.component.setupState.openPromptComposer()\");\n"),
        ("programmatic: split '__v'+'ue_app__' access",) + after_launcher_tap(
            "            evalString(\"document.querySelector('#app')['__v'+'ue_app__']\");\n"),
        ("programmatic: split 'mobilePrompt'+'ComposerOpen' write",) + after_launcher_tap(
            "            evalString(\"window['mobilePrompt'+'ComposerOpen']=true\");\n"),
        ("proof: pointer-click detail requirement dropped",
         "if(clickIndex<2||!(click.detail>=1)||click.pointerType!=='touch')return false;",
         "if(clickIndex<2||click.pointerType!=='touch')return false;"),
        ("proof: more than one launcher click accepted",
         "if(clicks.length!==1)return false;", "if(clicks.length<1)return false;"),
        ("proof: touch pointer type no longer required",
         "&&event.pointerType==='touch')&&down.pointerId===up.pointerId;", ")&&down.pointerId===up.pointerId;"),
        ("proof: keyboard events no longer recorded",
         "for(const type of ['pointerdown','pointerup','click','keydown','keypress','keyup'])",
         "for(const type of ['pointerdown','pointerup','click'])"),
        ("proof: click detail no longer recorded",
         "detail:typeof event.detail==='number'?event.detail:-1,", "detail:1,"),
        ("proof: exactly-one launcher click assertion removed",
         'assertEquals("exactly one trusted click may reach the Prompt launcher; " + evidence, 1, launcherClicks);', ""),
        ("proof: zero-detail (keyboard) launcher click assertion removed",
         '        assertEquals("the Prompt launcher click must be pointer-generated (detail >= 1), never a keyboard "\n'
         '                + "or synthetic activation; " + evidence, 0, zeroDetailLauncherClicks);\n', ""),
        ("proof: launcher keyboard event assertion removed",
         'assertEquals("no keyboard event may target the Prompt launcher; " + evidence, 0, keyboardLauncherEvents);', ""),
        ("proof: any-keyboard-input assertion removed",
         '        assertEquals("no keyboard input may occur while the Prompt launcher is being opened; " + evidence,\n'
         "                0, keyEvents);\n", ""),
        ("proof: keyboard count dropped from the evidence line",
         '+ " keyboardLauncherEvents=" + keyboardLauncherEvents', '+ ""'),
    )
    for label, *edits in modality_mutants:
        pairs = list(zip(edits[0::2], edits[1::2]))
        mutants += ((label, pairs),)
    tap_source = PHYSICAL_TAP_SOURCE.read_text(encoding="utf-8")
    tap_mutants = (
        ("shared tap: finger tool type dropped",
         "        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;\n", ""),
        ("shared tap: up stamped when injected instead of up front",
         "long upTime = downTime + TAP_DURATION_MS;", "long upTime = SystemClock.uptimeMillis();"),
        ("shared tap: sleep restored between down and up",
         "            downInjected = automation.injectInputEvent(down, false);\n",
         "            downInjected = automation.injectInputEvent(down, false);\n            SystemClock.sleep(60);\n"),
        ("shared tap: synchronous ACTION_DOWN restored",
         "automation.injectInputEvent(down, false)", "automation.injectInputEvent(down, true)"),
    )
    total = len(mutants) + len(tap_mutants) + 1
    for index, (label, old, new) in enumerate(tap_mutants, start=len(mutants) + 2):
        # Mutate the first occurrence: tap() precedes the delayed-delivery probe.
        if old not in tap_source:
            raise GateFailure(f"self-test setup drifted for mutant: {label}")
        try:
            validate(source, tap_source.replace(old, new, 1))
        except GateFailure:
            print(f"ok [{index}/{total}] {label} is rejected")
        else:
            raise GateFailure(f"source gate accepted invalid mutant: {label}")
    print(f"ok [1/{total}] startup and cleanup commands use the visible packaged Composer")
    for index, (label, *edit) in enumerate(mutants, start=2):
        pairs = edit[0] if len(edit) == 1 else [(edit[0], edit[1])]
        mutant = source
        for old, new in pairs:
            if mutant.count(old) != 1:
                raise GateFailure(f"self-test setup drifted for mutant: {label}")
            mutant = mutant.replace(old, new, 1)
        try:
            validate(mutant)
        except GateFailure:
            print(f"ok [{index}/{total}] {label} is rejected")
        else:
            raise GateFailure(f"source gate accepted invalid mutant: {label}")
    print(f"PASS: Usage/Ports Composer ordering gate checks ({total}/{total})")
    return 0


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        try:
            return self_test()
        except (GateFailure, OSError) as error:
            print(f"FAIL: Usage/Ports Composer ordering gate: {error}", file=sys.stderr)
            return 1
    if len(sys.argv) not in (1, 2):
        print(f"usage: {Path(sys.argv[0]).name} [--self-test] [source-file]", file=sys.stderr)
        return 2
    source_path = Path(sys.argv[1]) if len(sys.argv) == 2 else DEFAULT_SOURCE
    try:
        validate(source_path.read_text(encoding="utf-8"))
    except (GateFailure, OSError) as error:
        print(f"FAIL: Usage/Ports Composer ordering gate: {error}", file=sys.stderr)
        return 1
    print("PASS: Usage/Ports physically opens and focuses Prompt before connected transport readiness")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
