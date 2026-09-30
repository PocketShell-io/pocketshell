#!/usr/bin/python3 -I
"""Pin Usage/Ports cleanup to a physically opened, ready Composer."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = ROOT / "android/app/src/androidTest/java/com/pocketshell/app/smoke/UsagePortsDockerJourneyTest.java"


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


def validate(source: str) -> None:
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
        "boolean promptSheetOpen = \"true\".equals(evalRaw(promptSheetOpenExpression()));",
        "if (!promptSheetOpen) {",
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
    if "injectInputEvent" not in tap_method:
        raise GateFailure("draft-center tap must be delivered through Android touchscreen input")
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
    if 'return completedTrustedTapExpression("targetIsLauncher");' not in method_body(source, "completedTrustedLauncherTapExpression"):
        raise GateFailure("completed launcher tap proof must pair trusted events on the launcher")
    completed_tap = method_body(source, "completedTrustedTapExpression")
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
    if "SystemClock.sleep(16);" not in tap_method or "SystemClock.sleep(60);" in tap_method:
        raise GateFailure("draft tap must not keep the 60 ms stale-coordinate interval")
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
        "openHomeLiveComposerAndAwaitConnectedTransport(sessionTag, eventPrefix);",
        "awaitJsTrue(visibleComposerExpression(), 15_000);",
        "setValue(\"[data-testid=prompt-draft]\", command);",
        "click(\".composer-shared-controls .send\");",
    )
    sender_positions = [sender.find(token) for token in sender_order]
    if any(position < 0 for position in sender_positions) or sender_positions != sorted(sender_positions):
        raise GateFailure("cleanup command must wait for the opened Composer before setting and sending its draft")


def validate_launcher(source: str) -> None:
    """The Android Prompt sheet closes off Home; reopen it only by a physical launcher tap (D22: no DOM fallback)."""
    for forbidden in (
        "openComposerIfClosedAndAwaitTransport",
        'click("[data-testid=prompt-composer-launcher]")',
        "click(PROMPT_LAUNCHER_SELECTOR)",
    ):
        if forbidden in source:
            raise GateFailure(f"Prompt must not be opened by a synthetic DOM launcher click: {forbidden}")
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
        'trustedLauncherTapComplete = "true".equals(evalRaw(completedTrustedLauncherTapExpression()));',
        'promptSheetOpen = "true".equals(evalRaw(promptSheetOpenExpression()));',
        "if (promptSheetOpen) break;",
        'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");',
        'assertTrue("opening Prompt must record a completed trusted pointerdown/pointerup/click on its launcher; "',
        'assertTrue("the completed physical launcher tap must open the Prompt sheet; before=" + before',
    )
    positions = [launcher.find(token) for token in ordered]
    for token, position in zip(ordered, positions):
        if position < 0:
            raise GateFailure(f"physical launcher open is missing required step: {token}")
    if positions != sorted(positions):
        raise GateFailure("physical launcher open must settle, tap, then prove a trusted click opened the sheet")
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
            "for(const type of ['pointerdown','pointerup','click'])",
            "for(const type of ['pointerdown','pointerup'])",
        ),
        (
            "stale 60 ms coordinate interval restored",
            "SystemClock.sleep(16);",
            "SystemClock.sleep(60);",
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
            "for (int attempt = 1; attempt <= 3; attempt++) {\n            // Measure only once",
            "for (int attempt = 1; attempt <= 4; attempt++) {\n            // Measure only once",
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
            "launcher proof pairs events on the draft instead",
            'return completedTrustedTapExpression("targetIsLauncher");',
            'return completedTrustedTapExpression("targetIsDraft");',
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
            'String tapEvents = evalString("JSON.stringify(window.__ps2908ComposerOpenPointerEvents||[])");\n\n'
            '        assertTrue("opening Prompt must record',
            'String tapEvents = "[]";\n\n        assertTrue("opening Prompt must record',
        ),
    )
    total = len(mutants) + 1
    print(f"ok [1/{total}] startup and cleanup commands use the visible packaged Composer")
    for index, (label, old, new) in enumerate(mutants, start=2):
        if source.count(old) != 1:
            raise GateFailure(f"self-test setup drifted for mutant: {label}")
        mutant = source.replace(old, new, 1)
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
