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
    signature = re.search(rf"\bprivate\s+[\w<>]+\s+{re.escape(method_name)}\s*\(", source)
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
    helper = method_body(source, "openHomeLiveComposerAndAwaitConnectedTransport")
    focused_draft = "document.activeElement === document.querySelector('[data-testid=prompt-draft]')"
    required = (
        "String composerDraftTapReady = visibleComposerExpression()",
        "awaitJsTrue(composerDraftTapReady, 15_000);",
        "boolean composerVisible = \"true\".equals(evalRaw(visibleComposerExpression()))",
        "boolean composerDraftFocused = \"true\".equals(evalRaw(",
        "if (!composerVisible || !composerDraftFocused)",
        "openComposerWithPhysicalDraftTap(sessionTag);",
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
    if "injectInputEvent" not in method_body(source, "tapComposerDraftCenter"):
        raise GateFailure("draft-center tap must be delivered through Android touchscreen input")
    if "event.isTrusted===true&&event.targetIsDraft===true" not in tap_helper:
        raise GateFailure("Composer opening must prove a trusted pointer-down on the draft")
    for diagnostic in (
        "draftDisabled",
        "route:shell?.dataset.route||''",
        "activeElement:label(document.activeElement)",
        "keyboardVisible:shell?.dataset.keyboardVisible==='true'",
        "imeVisible",
    ):
        if diagnostic not in source:
            raise GateFailure(f"physical-open diagnostics are missing {diagnostic}")

    sender = method_body(source, "sendComposerCommandAndAwaitMarker")
    sender_order = (
        "openHomeLiveComposerAndAwaitConnectedTransport(sessionTag);",
        "awaitJsTrue(visibleComposerExpression(), 15_000);",
        "setValue(\"[data-testid=prompt-draft]\", command);",
        "click(\".composer-shared-controls .send\");",
    )
    sender_positions = [sender.find(token) for token in sender_order]
    if any(position < 0 for position in sender_positions) or sender_positions != sorted(sender_positions):
        raise GateFailure("cleanup command must wait for the opened Composer before setting and sending its draft")


def self_test() -> int:
    source = DEFAULT_SOURCE.read_text(encoding="utf-8")
    validate(source)
    print("ok [1/8] Composer waits for a visible, hittable draft before physical open")

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
            "openComposerWithPhysicalDraftTap(sessionTag);",
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
    )
    for index, (label, old, new) in enumerate(mutants, start=2):
        if source.count(old) != 1:
            raise GateFailure(f"self-test setup drifted for mutant: {label}")
        mutant = source.replace(old, new, 1)
        try:
            validate(mutant)
        except GateFailure:
            print(f"ok [{index}/8] {label} is rejected")
        else:
            raise GateFailure(f"source gate accepted invalid mutant: {label}")
    print("PASS: Usage/Ports Composer ordering gate checks (8/8)")
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
    print("PASS: Usage/Ports opens and focuses Composer before connected transport readiness")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
