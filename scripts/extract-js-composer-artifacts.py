#!/usr/bin/env python3
"""Rebuild and validate same-run composer screenshots and terminal evidence from Android logcat."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import math
import re
import sys
from pathlib import Path


TAG = "PS2857Asset:"
REQUIRED_NAMES = {
    "composer-keyboard.png",
    "composer-keyboard-geometry.json",
    "composer-post-send.png",
    "composer-post-send-terminal.json",
    "inline-dictation-preview.png",
    "composer-focus-trace.json",
    "composer-recording.png",
    "composer-recording-geometry.json",
    "composer-recording-after-restart.png",
    "composer-recording-after-restart-geometry.json",
    "composer-cancel.png",
    "composer-cancel-geometry.json",
    "composer-background.png",
    "composer-background-geometry.json",
    "composer-transcribing.png",
    "composer-transcribing-geometry.json",
    "composer-review.png",
    "composer-review-geometry.json",
    "composer-dictation-send.json",
    "composer-recording-insert.png",
    "composer-recording-insert-geometry.json",
    "composer-transcribing-send.png",
    "composer-transcribing-send-geometry.json",
    "composer-back-workspace-restored.png",
    "composer-back-workspace-restored.json",
    "composer-route.png",
    "composer-route.json",
    "composer-title.png",
    "composer-title.json",
    "composer-launcher-before-reopen.png",
    "composer-launcher-before-reopen.json",
    "composer-launcher-after-reopen.png",
    "composer-launcher-after-reopen.json",
    "composer-launcher-before-uncertain-first-attach.png",
    "composer-launcher-before-uncertain-first-attach.json",
    "composer-launcher-after-uncertain-first-attach.png",
    "composer-launcher-after-uncertain-first-attach.json",
    "composer-launcher-before-uncertain-reattach.json",
    "composer-launcher-after-uncertain-reattach.json",
}
OPTIONAL_NAMES = {
    "composer-recording-before-fix.png",
    "composer-review-error.png",
    "composer-review-error-geometry.json",
    "composer-review-empty.png",
    "composer-review-empty-geometry.json",
    "composer-mode-ime-failure.png",
    "composer-mode-ime-failure.json",
    "composer-focus-failure.png",
    "composer-focus-failure.json",
    "composer-focus-failure-logcat.txt",
    "composer-back-workspace-failure.png",
    "composer-route.png",
    "composer-back-workspace-failure.json",
}
EXPECTED_NAMES = REQUIRED_NAMES | OPTIONAL_NAMES
FAILURE_SCREENSHOTS = {
    "composer-keyboard.png",
    "inline-dictation-preview.png",
    "composer-recording.png",
    "composer-recording-after-restart.png",
    "composer-cancel.png",
    "composer-background.png",
    "composer-transcribing.png",
    "composer-review.png",
    "composer-review-error.png",
    "composer-review-empty.png",
    "composer-recording-before-fix.png",
    "composer-focus-failure.png",
    "composer-mode-ime-failure.png",
    "composer-recording-insert.png",
    "composer-transcribing-send.png",
    "composer-back-workspace-failure.png",
    "composer-title.png",
    "composer-launcher-before-reopen.png",
    "composer-launcher-after-reopen.png",
    "composer-launcher-before-uncertain-first-attach.png",
    "composer-launcher-after-uncertain-first-attach.png",
}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


class ExtractionFailure(ValueError):
    pass


STOP_ACCESSIBLE_NAME = "Stop dictation and keep the recognized text in the editable draft"


def _has_48dp_square_bounds(bounds: object) -> bool:
    if not isinstance(bounds, dict):
        return False
    try:
        top = float(bounds["top"])
        bottom = float(bounds["bottom"])
        left = float(bounds["left"])
        right = float(bounds["right"])
        width = float(bounds["width"])
        height = float(bounds["height"])
    except (KeyError, TypeError, ValueError):
        return False
    values = (top, bottom, left, right, width, height)
    return (all(math.isfinite(value) for value in values)
            and abs(width - 48.0) < 0.5 and abs(height - 48.0) < 0.5
            and abs((right - left) - width) < 0.5
            and abs((bottom - top) - height) < 0.5)


def _timer_sits_beside_waveform(timer: object, waveform: object) -> bool:
    if not isinstance(timer, dict) or not isinstance(waveform, dict):
        return False
    try:
        timer_top = float(timer["top"])
        timer_bottom = float(timer["bottom"])
        timer_right = float(timer["right"])
        waveform_top = float(waveform["top"])
        waveform_bottom = float(waveform["bottom"])
        waveform_left = float(waveform["left"])
    except (KeyError, TypeError, ValueError):
        return False
    values = (timer_top, timer_bottom, timer_right, waveform_top, waveform_bottom, waveform_left)
    return (all(math.isfinite(value) for value in values)
            and timer_top < waveform_bottom and timer_bottom > waveform_top
            and timer_right < waveform_left)


def _validate_launcher_tap_evidence(launcher_before: object, launcher_after: object,
                                   run_id: str, suffix: str) -> None:
    before_dom = launcher_before.get("dom") if isinstance(launcher_before, dict) else None
    before_native = launcher_before.get("native") if isinstance(launcher_before, dict) else None
    after_dom = launcher_after.get("dom") if isinstance(launcher_after, dict) else None
    after_native = launcher_after.get("native") if isinstance(launcher_after, dict) else None
    tap = launcher_after.get("physicalTap") if isinstance(launcher_after, dict) else None
    if (not isinstance(launcher_before, dict) or launcher_before.get("runId") != run_id
            or not isinstance(before_dom, dict) or before_dom.get("stage") != "before-tap"
            or before_dom.get("route") != "home" or before_dom.get("homeSurface") != "live"
            or before_dom.get("sshPhase") != "live" or before_dom.get("composerPresent") is not False
            or before_dom.get("launcherPresent") is not True or before_dom.get("launcherVisible") is not True
            or before_dom.get("launcherDisabled") is not False
            or before_dom.get("centerHitMatchesLauncher") is not True
            or before_dom.get("documentHasFocus") is not True
            or not isinstance(before_native, dict) or before_native.get("windowHasFocus") is not True
            or before_native.get("webViewHasFocus") is not True):
        raise ExtractionFailure(
            f"pre-tap launcher report for {suffix} does not prove a visible enabled target in the focused live workspace")
    keyboard_visible = before_dom.get("keyboardVisible")
    ime_visible = before_native.get("imeVisible")
    if keyboard_visible == ime_visible and isinstance(keyboard_visible, bool):
        viewport = before_dom.get("visualViewport")
        webview_width = before_native.get("webViewWidthPx")
        webview_height = before_native.get("webViewHeightPx")
        if (isinstance(viewport, dict) and isinstance(webview_width, (int, float)) and webview_width > 0
                and isinstance(webview_height, (int, float)) and webview_height > 0
                and isinstance(viewport.get("width"), (int, float)) and viewport["width"] > 0
                and isinstance(viewport.get("height"), (int, float)) and viewport["height"] > 0):
            scale_x = webview_width / viewport["width"]
            scale_y = webview_height / viewport["height"]
            keyboard_matches_native = abs(scale_x - scale_y) <= max(scale_x, scale_y) * 0.02
        else:
            keyboard_matches_native = False
    else:
        keyboard_matches_native = False
    if not keyboard_matches_native:
        raise ExtractionFailure(
            f"pre-tap keyboard and native IME geometry for {suffix} is missing or inconsistent")
    if (not isinstance(launcher_after, dict) or launcher_after.get("runId") != run_id
            or not isinstance(after_dom, dict) or after_dom.get("stage") != "after-tap"
            or after_dom.get("route") != "home" or after_dom.get("homeSurface") != "live"
            or after_dom.get("sshPhase") != "live" or after_dom.get("composerPresent") is not True
            or after_dom.get("composerVisible") is not True or after_dom.get("composerRole") != "dialog"
            or after_dom.get("composerAriaModal") != "true"
            or not isinstance(after_dom.get("activeElement"), dict)
            or after_dom["activeElement"].get("testid") != "prompt-draft"
            or not isinstance(after_native, dict) or after_native.get("windowHasFocus") is not True
            or after_native.get("webViewHasFocus") is not True or after_native.get("imeVisible") is not True
            or not isinstance(tap, dict) or tap.get("downInjected") is not True
            or tap.get("upInjected") is not True or tap.get("centerHitMatchesTarget") is not True
            or tap.get("selector") != "[data-testid=prompt-composer-launcher]"
            or launcher_after.get("openWaitFailure") is not None):
        raise ExtractionFailure(f"post-tap launcher report for {suffix} does not prove a real touch opened the composer")
    attempts = launcher_after.get("attempts")
    if not isinstance(attempts, list) or len(attempts) not in (1, 2):
        raise ExtractionFailure(f"composer launcher report for {suffix} must retain one or two physical tap attempts")
    if len(attempts) == 1:
        if attempts[0].get("attempt") != 1 or attempts[0].get("failure") is not None:
            raise ExtractionFailure(f"single-tap composer reopen for {suffix} did not open on its first physical tap")
    else:
        first = attempts[0]
        second = attempts[1]
        if not isinstance(first, dict) or not isinstance(second, dict):
            raise ExtractionFailure(f"two-tap composer reopen for {suffix} contains malformed attempt evidence")
        first_state = first.get("state") if isinstance(first, dict) else None
        first_dom = first_state.get("dom") if isinstance(first_state, dict) else None
        first_native = first_state.get("native") if isinstance(first_state, dict) else None
        first_tap = first.get("physicalTap") if isinstance(first, dict) else None
        second_state = second.get("state") if isinstance(second, dict) else None
        second_dom = second_state.get("dom") if isinstance(second_state, dict) else None
        second_tap = second.get("physicalTap") if isinstance(second, dict) else None
        initial_webview_height = before_native.get("webViewHeightPx")
        resized_webview_height = first_native.get("webViewHeightPx") if isinstance(first_native, dict) else None
        if (suffix not in ("uncertain-first-attach", "uncertain-reattach")
                or first.get("attempt") != 1 or second.get("attempt") != 2
                or not isinstance(first.get("failure"), str) or not first.get("failure")
                or launcher_after.get("firstAttemptFailure") != first.get("failure")
                or not isinstance(first_dom, dict) or first_dom.get("composerPresent") is not False
                or first_dom.get("route") != "home" or first_dom.get("homeSurface") != "live"
                or first_dom.get("sshPhase") != "live" or first_dom.get("keyboardVisible") is not True
                or not isinstance(first_native, dict) or first_native.get("imeVisible") is not True
                or not isinstance(initial_webview_height, (int, float))
                or not isinstance(resized_webview_height, (int, float))
                or resized_webview_height >= initial_webview_height
                or not isinstance(first_tap, dict) or first_tap.get("downInjected") is not True
                or first_tap.get("upInjected") is not True
                or first_tap.get("centerHitMatchesTarget") is not True
                or not isinstance(second, dict) or second.get("attempt") != 2 or second.get("failure") is not None
                or not isinstance(second_dom, dict) or second_dom.get("composerPresent") is not True
                or second.get("failure") is not None or second_tap != tap):
            raise ExtractionFailure(
                f"two-tap composer reopen for {suffix} lacks a genuine IME-resize miss followed by a successful physical retry")
    pointer_events = after_dom.get("pointerEvents")
    if not isinstance(pointer_events, list):
        raise ExtractionFailure(f"composer launcher report for {suffix} is missing trusted pointer evidence")
    trusted = {(event.get("type"), event.get("launcher", {}).get("testid"))
               for event in pointer_events if isinstance(event, dict) and event.get("isTrusted") is True
               and isinstance(event.get("launcher"), dict)}
    if not all((event_type, "prompt-composer-launcher") in trusted
               for event_type in ("pointerdown", "pointerup", "click")):
        raise ExtractionFailure(f"composer launcher report for {suffix} lacks trusted physical pointerdown/up/click")


def parse_assets(log_text: str, run_id: str, *, validate_layout: bool = True,
                 expected_terminal_marker: str | None = None,
                 expected_dictation_marker: str | None = None) -> dict[str, bytes]:
    assets: dict[str, dict[str, object]] = {}
    for line in log_text.splitlines():
        if TAG not in line:
            continue
        message = line.split(TAG, 1)[1].strip()
        parts = message.split("|", 4)
        if len(parts) < 3 or parts[1] != run_id:
            continue
        kind, _, name = parts[:3]
        if not SAFE_NAME.fullmatch(name) or name not in EXPECTED_NAMES:
            raise ExtractionFailure(f"unsafe or unexpected artifact name {name!r}")
        if kind == "BEGIN":
            if len(parts) != 5 or name in assets:
                raise ExtractionFailure(f"duplicate or malformed BEGIN record for {name}")
            try:
                chunks = int(parts[3])
            except ValueError as error:
                raise ExtractionFailure(f"invalid chunk count for {name}") from error
            if chunks < 1 or not re.fullmatch(r"[a-f0-9]{64}", parts[4]):
                raise ExtractionFailure(f"invalid chunk count or digest for {name}")
            assets[name] = {"count": chunks, "sha256": parts[4], "parts": {}, "ended": False}
        elif kind == "DATA":
            if len(parts) != 5 or name not in assets or assets[name]["ended"]:
                raise ExtractionFailure(f"DATA record has no BEGIN for {name}")
            try:
                index = int(parts[3])
            except ValueError as error:
                raise ExtractionFailure(f"invalid chunk index for {name}") from error
            chunks = assets[name]["parts"]
            assert isinstance(chunks, dict)
            if index in chunks:
                raise ExtractionFailure(f"duplicate chunk {index} for {name}")
            chunks[index] = parts[4]
        elif kind == "END":
            if len(parts) != 3 or name not in assets or assets[name]["ended"]:
                raise ExtractionFailure(f"END record has no matching BEGIN for {name}")
            assets[name]["ended"] = True
        else:
            raise ExtractionFailure(f"unknown artifact record {kind!r} for {name}")

    required_names = REQUIRED_NAMES if validate_layout else set()
    if not required_names.issubset(assets) or set(assets) - EXPECTED_NAMES:
        raise ExtractionFailure(f"expected at least {sorted(required_names)} without extras, found {sorted(assets)}")
    if not validate_layout and not (FAILURE_SCREENSHOTS & set(assets)):
        raise ExtractionFailure("failure-mode extraction must preserve a composer screenshot")

    decoded: dict[str, bytes] = {}
    for name, asset in assets.items():
        parts = asset["parts"]
        assert isinstance(parts, dict)
        count = asset["count"]
        if not asset["ended"] or len(parts) != count or set(parts) != set(range(count)):
            raise ExtractionFailure(f"artifact {name} is incomplete: {len(parts)}/{count} chunks")
        encoded = "".join(str(parts[index]) for index in range(count))
        try:
            payload = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error:
            raise ExtractionFailure(f"artifact {name} contains invalid base64: {error}") from error
        digest = hashlib.sha256(payload).hexdigest()
        if digest != asset["sha256"]:
            raise ExtractionFailure(f"artifact {name} SHA-256 does not match its logcat manifest")
        decoded[name] = payload

    for name in (name for name in decoded if name.endswith(".png")):
        screenshot = decoded.get(name)
        if screenshot is not None and (not screenshot.startswith(b"\x89PNG\r\n\x1a\n") or len(screenshot) < 1024):
            raise ExtractionFailure(f"{name} is not a non-empty PNG")
    if validate_layout:
        try:
            route_state = json.loads(decoded["composer-route.json"])
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer route JSON is invalid: {error}") from error
        if (not isinstance(route_state, dict) or route_state.get("runId") != run_id
                or route_state.get("route") != "home" or route_state.get("homeSurface") != "live"
                or route_state.get("sshPhase") != "live" or route_state.get("keyboardVisible") is not False
                or route_state.get("composerPresent") is not False
                or route_state.get("launcherVisible") is not True or route_state.get("launcherEnabled") is not True
                or route_state.get("promptAccessibleName") != "Open prompt composer to type or dictate a prompt"
                or route_state.get("expectedPromptAccessibleName") != "Open prompt composer to type or dictate a prompt"
                or route_state.get("promptTitle") != "Open prompt composer to type or dictate a prompt"
                or route_state.get("promptVisibleText") != "Prompt"
                or route_state.get("expectedPromptVisibleText") != "Prompt"
                or route_state.get("promptIconVisible") is not True
                or route_state.get("promptCenterHit") is not True
                or route_state.get("inlineMicVisible") is not True or route_state.get("inlineMicEnabled") is not True
                or route_state.get("inlineMicLabel") != "Dictate at terminal cursor"
                or route_state.get("expectedInlineMicLabel") != "Dictate at terminal cursor"
                or route_state.get("inlineMicTitle") != "Dictate at terminal cursor"
                or route_state.get("inlineMicVisibleText") != "Dictate"
                or route_state.get("terminalDestinationLabels") != []
                or route_state.get("expectedTerminalDestinationLabels") != []
                or route_state.get("terminalDestinationVisible") is not False
                or route_state.get("inlineMicIconVisible") is not True
                or route_state.get("inlineMicCenterHit") is not True
                or route_state.get("targetsSeparated") is not True):
            raise ExtractionFailure("idle terminal evidence does not prove visibly distinct, accessible Prompt and terminal dictation controls")
        route_session = route_state.get("expectedSession")
        route_heading = route_state.get("terminalHeading")
        if (not isinstance(route_session, str) or not route_session or not isinstance(route_heading, str)
                or route_session not in route_heading):
            raise ExtractionFailure("idle terminal route evidence does not identify the attached session")
        route_viewport = route_state.get("viewport")
        if not isinstance(route_viewport, dict):
            raise ExtractionFailure("idle terminal route evidence is missing its visible viewport")
        try:
            route_viewport_width = float(route_viewport["width"])
            route_viewport_height = float(route_viewport["height"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("idle terminal route evidence has invalid viewport bounds") from error
        if (not all(math.isfinite(value) for value in (route_viewport_width, route_viewport_height))
                or route_viewport_width <= 0 or route_viewport_height <= 0):
            raise ExtractionFailure("idle terminal route evidence has invalid viewport bounds")
        route_bounds: dict[str, dict[str, float]] = {}
        for label in ("promptBounds", "inlineMicBounds"):
            bounds = route_state.get(label)
            if not isinstance(bounds, dict):
                raise ExtractionFailure(f"idle terminal route evidence is missing {label}")
            try:
                top, bottom = float(bounds["top"]), float(bounds["bottom"])
                left, right = float(bounds["left"]), float(bounds["right"])
                width, height = float(bounds["width"]), float(bounds["height"])
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"idle terminal route evidence has invalid {label}") from error
            if (not all(math.isfinite(value) for value in (top, bottom, left, right, width, height))
                    or width <= 0 or height <= 0 or abs((right - left) - width) >= 0.5
                    or abs((bottom - top) - height) >= 0.5):
                raise ExtractionFailure(f"idle terminal route evidence has invalid bounds for {label}")
            if (width < 48.0 or height < 48.0 or top < -0.5 or left < -0.5
                    or bottom > route_viewport_height + 0.5 or right > route_viewport_width + 0.5):
                raise ExtractionFailure(f"idle terminal {label} is clipped or below the 48dp touch target")
            route_bounds[label] = {"top": top, "bottom": bottom, "left": left, "right": right}
        if route_bounds["promptBounds"]["right"] > route_bounds["inlineMicBounds"]["left"]:
            raise ExtractionFailure("idle terminal Prompt and inline dictation bounds overlap or are out of order")

        title_bytes = decoded.get("composer-title.json")
        if title_bytes is None or "composer-title.png" not in decoded:
            raise ExtractionFailure("same-run composer title screenshot and report are required")
        try:
            title_state = json.loads(title_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer title JSON is invalid: {error}") from error
        if not isinstance(title_state, dict) or title_state.get("runId") != run_id:
            raise ExtractionFailure("composer title report does not identify this packaged run")
        panel = title_state.get("panelBounds")
        scrim = title_state.get("scrimBounds")
        viewport = title_state.get("viewport")
        buttons = title_state.get("buttons")
        if (title_state.get("state") != "idle" or title_state.get("composerVisible") is not True
                or title_state.get("composerHeading") != "Prompt Composer"
                or title_state.get("expectedComposerHeading") != "Prompt Composer"
                or title_state.get("keyboardVisible") is not True
                or title_state.get("composerHeadingVisible") is not False
                or title_state.get("composerHeadingDisplay") != "none"
                or title_state.get("sheetFullyVisible") is not True
                or title_state.get("dictatePromptText") != ""
                or title_state.get("expectedDictatePromptVisibleLabel") != ""
                or title_state.get("expectedDictatePromptAccessibleName") != "Dictate prompt draft"
                or title_state.get("dictatePromptAccessibleName") != "Dictate prompt draft"
                or title_state.get("dictatePromptGlyphPresent") is not True
                or title_state.get("dictatePromptVisible") is not True
                or title_state.get("dictatePromptEnabled") is not True
                or not isinstance(panel, dict) or not isinstance(scrim, dict) or not isinstance(viewport, dict)
                or not isinstance(title_state.get("draftBounds"), dict)
                or not isinstance(title_state.get("actionsBounds"), dict)
                or not isinstance(viewport.get("width"), (int, float))
                or not isinstance(viewport.get("height"), (int, float))
                or panel.get("top", -1) < 0 or panel.get("bottom", float("inf")) > viewport.get("height", 0) + 0.5
                or scrim.get("top", 1) > 0.5 or scrim.get("left", 1) > 0.5
                or scrim.get("bottom", 0) < viewport.get("height", 0) - 0.5
                or scrim.get("right", 0) < viewport.get("width", 0) - 0.5
                or not isinstance(buttons, dict)
                or any(buttons.get(name) is not True for name in ("dictate", "insert", "send", "keys"))
                or title_state.get("screenScrollTop") != 0 or title_state.get("documentScrollTop") != 0):
            raise ExtractionFailure("composer title report does not prove a fully visible idle sheet and generic heading")
        for name in ("draftBounds", "actionsBounds"):
            bounds = title_state[name]
            try:
                left = float(bounds["left"])
                right = float(bounds["right"])
                title_width = float(viewport["width"])
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"composer title report has invalid {name} horizontal bounds") from error
            if left < 16.0 or title_width - right < 16.0:
                raise ExtractionFailure(f"keyboard-up composer {name} must keep a 16dp horizontal gutter")
        dictate_bounds = title_state.get("dictatePromptBounds")
        open_keys_bounds = title_state.get("composerOpenKeysBounds")
        try:
            dictate_top = float(dictate_bounds["top"])
            dictate_bottom = float(dictate_bounds["bottom"])
            dictate_left = float(dictate_bounds["left"])
            dictate_right = float(dictate_bounds["right"])
            dictate_width = float(dictate_bounds["width"])
            dictate_height = float(dictate_bounds["height"])
            panel_top = float(panel["top"])
            panel_bottom = float(panel["bottom"])
            panel_left = float(panel["left"])
            panel_right = float(panel["right"])
            viewport_width = float(viewport["width"])
            viewport_height = float(viewport["height"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("composer title report has invalid Dictate prompt bounds") from error
        if (dictate_width < 48.0 or dictate_width >= 49.0 or dictate_height < 48.0 or dictate_height >= 49.0
                or abs((dictate_right - dictate_left) - dictate_width) >= 0.5
                or abs((dictate_bottom - dictate_top) - dictate_height) >= 0.5
                or dictate_top < max(0.0, panel_top) or dictate_bottom > min(viewport_height, panel_bottom) + 0.5
                or dictate_left < max(0.0, panel_left) or dictate_right > min(viewport_width, panel_right) + 0.5):
            raise ExtractionFailure("Dictate prompt action is clipped or below the 48dp touch target")
        try:
            keys_top = float(open_keys_bounds["top"])
            keys_bottom = float(open_keys_bounds["bottom"])
            keys_left = float(open_keys_bounds["left"])
            keys_right = float(open_keys_bounds["right"])
            keys_width = float(open_keys_bounds["width"])
            keys_height = float(open_keys_bounds["height"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("composer title report has invalid More terminal keys bounds") from error
        if (keys_width < 48.0 or keys_width >= 49.0 or keys_height < 48.0 or keys_height >= 49.0
                or keys_top < max(0.0, panel_top) or keys_bottom > min(viewport_height, panel_bottom) + 0.5
                or keys_left < max(0.0, panel_left) or keys_right > min(viewport_width, panel_right) + 0.5):
            raise ExtractionFailure("More terminal keys is hidden, clipped, or below the 48dp touch target")
        terminal_heading = title_state.get("terminalHeading")
        expected_session = title_state.get("expectedSessionChrome")
        if (not isinstance(terminal_heading, str) or not isinstance(expected_session, str)
                or not expected_session or expected_session not in terminal_heading):
            raise ExtractionFailure("selected session identity is missing from terminal chrome title evidence")
        try:
            launcher_before = json.loads(decoded["composer-launcher-before-reopen.json"])
            launcher_after = json.loads(decoded["composer-launcher-after-reopen.json"])
            first_attach_before = json.loads(decoded["composer-launcher-before-uncertain-first-attach.json"])
            first_attach_after = json.loads(decoded["composer-launcher-after-uncertain-first-attach.json"])
            uncertain_launcher_before = json.loads(decoded["composer-launcher-before-uncertain-reattach.json"])
            uncertain_launcher_after = json.loads(decoded["composer-launcher-after-uncertain-reattach.json"])
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer launcher tap evidence is invalid JSON: {error}") from error
        for evidence_suffix, launcher_before, launcher_after in (
            ("reopen", launcher_before, launcher_after),
            ("uncertain-first-attach", first_attach_before, first_attach_after),
            ("uncertain-reattach", uncertain_launcher_before, uncertain_launcher_after),
        ):
            _validate_launcher_tap_evidence(launcher_before, launcher_after, run_id, evidence_suffix)

    focus_failure_screenshot = decoded.get("composer-focus-failure.png")
    if focus_failure_screenshot is not None and (
            not focus_failure_screenshot.startswith(b"\x89PNG\r\n\x1a\n") or len(focus_failure_screenshot) < 1024):
        raise ExtractionFailure("composer focus failure screenshot is not a non-empty PNG")
    geometry_bytes = decoded.get("composer-keyboard-geometry.json")
    if geometry_bytes is None:
        if validate_layout:
            raise ExtractionFailure("keyboard geometry artifact is missing")
        return decoded
    try:
        geometry = json.loads(geometry_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        if validate_layout:
            raise ExtractionFailure(f"keyboard geometry JSON is invalid: {error}") from error
        return decoded
    if validate_layout:
        if not isinstance(geometry, dict):
            raise ExtractionFailure("keyboard geometry must be a JSON object")
        if geometry.get("androidImeVisible") is not True:
            raise ExtractionFailure("captured geometry does not prove Android IME visibility")
        viewport = geometry.get("visualViewport")
        draft = geometry.get("draft")
        actions = geometry.get("actions")
        app_bar = geometry.get("appBar")
        terminal = geometry.get("terminalViewport")
        status = geometry.get("status")
        controls = geometry.get("buttons")
        safe_area = geometry.get("safeArea")
        native_insets = geometry.get("nativeInsets")
        if not all(isinstance(value, dict) for value in (viewport, draft, status, actions, app_bar, terminal, controls, safe_area, native_insets)):
            raise ExtractionFailure("keyboard geometry is missing viewport, safe-area, terminal, composer, or button bounds")
        try:
            height = float(viewport["height"])
            width = float(viewport["width"])
            status_inset = float(native_insets["statusBarTopDp"])
            ime_inset = float(native_insets["imeBottomDp"])
            safe_top = float(safe_area["topCss"])
            safe_bottom = float(safe_area["bottomCss"])
            shell_top = float(safe_area["shellTopPadding"])
            shell_bottom = float(safe_area["shellBottomPadding"])
            app_bar_top = float(app_bar["top"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("keyboard geometry has invalid safe-area or viewport bounds") from error
        if ime_inset <= 0 or safe_area.get("keyboardVisible") is not True:
            raise ExtractionFailure("native IME inset or WebView keyboard layout state is missing")
        if (geometry.get("composerIsSheet") is not True
                or geometry.get("composerHeadingVisible") is not False
                or geometry.get("composerHeadingDisplay") != "none"):
            raise ExtractionFailure("keyboard-up Prompt Composer must hide its title copy")
        if abs(safe_bottom) > 0.5 or abs(shell_bottom) > 0.5:
            raise ExtractionFailure(f"keyboard layout retained bottom safe-area padding: CSS={safe_bottom}, shell={shell_bottom}")
        if status_inset <= 0 or safe_top < status_inset - 1 or shell_top < status_inset - 1 or app_bar_top < status_inset - 1:
            raise ExtractionFailure(
                f"app header overlaps the status bar: status inset={status_inset}, safe CSS={safe_top}, "
                f"shell padding={shell_top}, app bar top={app_bar_top}"
            )
        visible_rects = {"draft": draft, "status": status, "actions": actions, "terminal context": terminal}
        for name in ("discard", "insert", "send", "keys"):
            button = controls.get(name)
            if not isinstance(button, dict):
                raise ExtractionFailure(f"keyboard geometry is missing the {name} button bounds")
            try:
                if float(button["bottom"]) - float(button["top"]) < 47.9:
                    raise ExtractionFailure(f"{name} touch target is below the 48dp minimum")
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"keyboard geometry has invalid bounds for {name}") from error
            visible_rects[name] = button
        for name, rect in visible_rects.items():
            try:
                top = float(rect["top"])
                bottom = float(rect["bottom"])
                left = float(rect["left"])
                right = float(rect["right"])
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"keyboard geometry has invalid bounds for {name}") from error
            if top < 0 or bottom > height + 0.5 or left < 0 or right > width + 0.5:
                raise ExtractionFailure(
                    f"{name} is clipped by keyboard viewport: {left}..{right} x {top}..{bottom}, "
                    f"viewport={width}x{height}"
                )
        for name in ("draft", "actions"):
            rect = visible_rects[name]
            try:
                left = float(rect["left"])
                right = float(rect["right"])
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"keyboard-up composer {name} has invalid horizontal bounds") from error
            if left < 16.0 or width - right < 16.0:
                raise ExtractionFailure(f"keyboard-up composer {name} must keep a 16dp horizontal gutter")
        if float(terminal["height"]) < 48:
            raise ExtractionFailure("keyboard layout hides the terminal context instead of preserving a useful viewport")
        if float(actions["top"]) < float(draft["bottom"]):
            raise ExtractionFailure("composer action container overlaps the draft")

        post_send_bytes = decoded.get("composer-post-send-terminal.json")
        if post_send_bytes is None or "composer-post-send.png" not in decoded:
            raise ExtractionFailure("same-run post-send screenshot and terminal record are required")
        try:
            post_send = json.loads(post_send_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"post-send terminal JSON is invalid: {error}") from error
        if not isinstance(post_send, dict) or post_send.get("stage") != "after-send":
            raise ExtractionFailure("post-send terminal record does not identify an after-send capture")
        if post_send.get("captureEnabled") is not True:
            raise ExtractionFailure("post-send terminal capture was not explicitly enabled by instrumentation")
        if post_send.get("terminalEvidenceSource") != "xterm-active-buffer-after-render":
            raise ExtractionFailure("post-send terminal text was not captured from the rendered xterm buffer")
        if post_send.get("sentMarkerAbsentFromSubmittedCommand") is not True:
            raise ExtractionFailure("post-send marker may be a command echo rather than terminal output")
        try:
            send_to_visible_latency = int(post_send["sendToVisibleOutputLatencyMs"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("post-send terminal record is missing a valid Send-to-visible-output latency") from error
        if send_to_visible_latency < 0:
            raise ExtractionFailure("Send-to-visible-output latency cannot be negative")
        if post_send.get("sendToVisibleOutputTiming") != (
            "Android uptime from Send touch-up to the first 60ms WebView poll with both executed rows rendered inside the visible xterm screen"
        ):
            raise ExtractionFailure("post-send latency does not identify its packaged visible-output measurement")
        recorded_marker = post_send.get("expectedMarker")
        if not isinstance(recorded_marker, str) or not recorded_marker:
            raise ExtractionFailure("post-send terminal record is missing its expected marker")
        if expected_terminal_marker is None or recorded_marker != expected_terminal_marker:
            raise ExtractionFailure("post-send terminal marker does not match this packaged journey")
        visible_text = post_send.get("visibleTerminalText")
        if not isinstance(visible_text, str) or recorded_marker not in visible_text:
            raise ExtractionFailure("post-send terminal text does not contain the executed command marker")
        dom_text = post_send.get("terminalDomText")
        if not isinstance(dom_text, str) or recorded_marker not in dom_text:
            raise ExtractionFailure("post-send rendered terminal DOM does not contain the executed command marker")
        try:
            delivery_count = int(post_send["appTerminalDeliveryCount"])
            missing_ref_count = int(post_send["appTerminalMissingRefCount"])
            write_count = int(post_send["terminalWriteCount"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("post-send app-to-terminal delivery counters are invalid") from error
        if delivery_count < 1 or missing_ref_count != 0 or write_count < 1:
            raise ExtractionFailure("post-send PTY output did not reach the mounted terminal component")
        if not isinstance(post_send.get("deliveryStatus"), str) or "Sent to the terminal" not in post_send["deliveryStatus"]:
            raise ExtractionFailure("post-send terminal record does not prove successful Send status")
        terminal_rect = post_send.get("terminalViewport")
        post_viewport = post_send.get("visualViewport")
        if not isinstance(terminal_rect, dict) or not isinstance(post_viewport, dict):
            raise ExtractionFailure("post-send terminal record is missing viewport bounds")
        try:
            terminal_top = float(terminal_rect["top"])
            terminal_bottom = float(terminal_rect["bottom"])
            terminal_left = float(terminal_rect["left"])
            terminal_right = float(terminal_rect["right"])
            terminal_height = float(terminal_rect["height"])
            post_height = float(post_viewport["height"])
            post_width = float(post_viewport["width"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("post-send terminal viewport bounds are invalid") from error
        if (terminal_top < 0 or terminal_bottom > post_height + 0.5
                or terminal_left < 0 or terminal_right > post_width + 0.5 or terminal_height < 48):
            raise ExtractionFailure("post-send terminal viewport is clipped or too small")
        if post_send.get("keyboardVisible") is not False:
            raise ExtractionFailure("post-send terminal screenshot was not captured after the Android keyboard closed")
        app_bar = post_send.get("appBar")
        heading = post_send.get("terminalHeading")
        composer = post_send.get("composer")
        byte_row = post_send.get("byteOutputRow")
        marker_row = post_send.get("markerRow")
        if not all(isinstance(rect, dict) for rect in (app_bar, heading, composer, byte_row, marker_row)):
            raise ExtractionFailure("post-send capture is missing app bar, terminal heading, output row, or composer bounds")
        try:
            app_bar_top = float(app_bar["top"])
            app_bar_bottom = float(app_bar["bottom"])
            heading_top = float(heading["top"])
            heading_bottom = float(heading["bottom"])
            composer_top = float(composer["top"])
            composer_bottom = float(composer["bottom"])
            byte_top = float(byte_row["top"])
            byte_bottom = float(byte_row["bottom"])
            marker_top = float(marker_row["top"])
            marker_bottom = float(marker_row["bottom"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("post-send app bar, heading, composer, or row bounds are invalid") from error
        if (app_bar_top < 0 or app_bar_bottom > post_height + 0.5 or app_bar_bottom > heading_top
                or heading_bottom > terminal_top or heading_bottom > composer_top
                or not isinstance(heading.get("text"), str) or not heading["text"].strip()):
            raise ExtractionFailure("post-send terminal heading is clipped or hidden behind the composer sheet")
        if (composer_top < 0 or composer_bottom > post_height + 0.5 or composer_bottom <= composer_top):
            raise ExtractionFailure("post-send composer sheet is clipped by the viewport")
        if (byte_top < terminal_top or byte_bottom > terminal_bottom
                or marker_top < terminal_top or marker_bottom > terminal_bottom
                or byte_bottom > composer_top or marker_bottom > composer_top
                or post_send.get("terminalOutputRowVisible") is not True):
            raise ExtractionFailure("post-send byte and marker output rows are not visible above the composer sheet")
        if (post_send.get("capturedBeforeScroll") is not True
                or post_send.get("screenScrollTop") != 0
                or post_send.get("documentScrollTop") != 0):
            raise ExtractionFailure("post-send screenshot required scrolling to expose terminal output")
        focus_trace_bytes = decoded.get("composer-focus-trace.json")
        try:
            focus_trace = json.loads(focus_trace_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer focus trace JSON is invalid: {error}") from error
        if not isinstance(focus_trace, dict) or focus_trace.get("runId") != run_id:
            raise ExtractionFailure("composer focus trace does not identify this packaged run")
        max_attempts = focus_trace.get("maxAttempts")
        focus_attempts = focus_trace.get("attempts")
        forced_first_miss = focus_trace.get("forcedFirstPostAttachMiss")
        if not isinstance(max_attempts, int) or max_attempts < 1 or max_attempts > 2:
            raise ExtractionFailure("composer focus trace does not retain the bounded tap-attempt limit")
        if not isinstance(forced_first_miss, bool):
            raise ExtractionFailure("composer focus trace does not state whether its transient miss was forced")
        if not isinstance(focus_attempts, list):
            raise ExtractionFailure("composer focus trace does not contain tap attempts")
        post_attach_attempts = [
            attempt for attempt in focus_attempts
            if isinstance(attempt, dict)
            and attempt.get("stage") in {"uncertain-session-after-attach", "post-inline-dictation-attach"}
        ]
        if not post_attach_attempts or len(post_attach_attempts) > max_attempts:
            raise ExtractionFailure("composer focus trace is missing the bounded post-attach tap sequence")
        if not any(
            attempt.get("requestedSelector") == "[data-testid=prompt-draft]"
            and attempt.get("trustedPointerDownOnRequestedTarget") is True
            and attempt.get("draftFocusedAfter") is True
            and attempt.get("nativeImeVisibleAfterImeWait") is True
            for attempt in post_attach_attempts
        ):
            raise ExtractionFailure("post-attach focus trace has no trusted physical draft tap followed by focus and IME")
        if forced_first_miss:
            forced_misses = [attempt for attempt in post_attach_attempts
                             if attempt.get("attempt") == 1]
            if not forced_misses:
                raise ExtractionFailure("forced post-attach focus trace is missing its first transient miss")
            for miss in forced_misses:
                after = miss.get("after")
                tap = miss.get("tap")
                center_hit = tap.get("centerHit") if isinstance(tap, dict) else None
                physical_pointer = miss.get("physicalPointerDown")
                physical_target = physical_pointer.get("target") if isinstance(physical_pointer, dict) else None
                physical_center_hit = (
                    miss.get("physicalCenterHit") is True
                    and isinstance(tap, dict)
                    and isinstance(physical_pointer, dict)
                    and physical_pointer.get("type") == "pointerdown"
                    and physical_pointer.get("isTrusted") is True
                    and physical_pointer.get("targetMatchesRequested") is True
                    and isinstance(physical_target, dict)
                    and physical_target.get("testid") == "composer-status"
                    and all(isinstance(value, (int, float)) and math.isfinite(value)
                            for value in (tap.get("x"), tap.get("y"),
                                          physical_pointer.get("clientX"), physical_pointer.get("clientY")))
                    and abs(physical_pointer["clientX"] - tap["x"]) <= 1.0
                    and abs(physical_pointer["clientY"] - tap["y"]) <= 1.0
                )
                visible_inert_status_hit = (
                    miss.get("requestedSelector") == "[data-testid=prompt-composer] [data-testid=composer-status]"
                    and isinstance(tap, dict)
                    and tap.get("centerHitMatchesTarget") is True
                    and tap.get("targetVisible") is True
                    and tap.get("targetTestId") == "composer-status"
                    and isinstance(center_hit, dict)
                    and center_hit.get("testid") == "composer-status"
                    and all(isinstance(tap.get(key), (int, float)) and math.isfinite(tap[key])
                            for key in ("top", "bottom", "left", "right", "width", "height",
                                        "targetWidth", "targetHeight"))
                    and tap["targetWidth"] > 0 and tap["targetHeight"] > 0
                    and tap["top"] >= 0 and tap["bottom"] > tap["top"]
                    and tap["bottom"] <= tap["height"] + 0.5
                    and tap["left"] >= 0 and tap["right"] > tap["left"]
                    and tap["right"] <= tap["width"] + 0.5
                    and physical_center_hit
                )
                if (not visible_inert_status_hit
                        or miss.get("trustedPointerDownOnRequestedTarget") is not True
                        or miss.get("draftFocusedAfter") is not False
                        or miss.get("visibleInertStatusHit") is not True
                        or miss.get("inertMissInsideComposer") is not True
                        or miss.get("dialogStayedOpenAfterMiss") is not True
                        or miss.get("draftStayedMountedAfterMiss") is not True
                        or not isinstance(after, dict)
                        or after.get("composerModal") is not True
                        or after.get("composerTitlePresent") is not True
                        or after.get("draftPresent") is not True
                        or after.get("draftConnected") is not True):
                    raise ExtractionFailure("forced first miss closed Composer or invalidated its retry target")
                stage_attempts = [attempt for attempt in post_attach_attempts
                                  if attempt.get("stage") == miss.get("stage")]
                if not any(attempt.get("attempt") == 2
                           and attempt.get("requestedSelector") == "[data-testid=prompt-draft]"
                           and attempt.get("trustedPointerDownOnRequestedTarget") is True
                           and attempt.get("draftFocusedAfter") is True
                           and attempt.get("nativeImeVisibleAfterImeWait") is True
                           for attempt in stage_attempts):
                    raise ExtractionFailure("forced first miss has no successful physical draft retry in the same attach stage")
        for attempt in focus_attempts:
            if not isinstance(attempt, dict) or not isinstance(attempt.get("attempt"), int):
                raise ExtractionFailure("composer focus trace contains a malformed tap-attempt record")
            if attempt["attempt"] < 1 or attempt["attempt"] > max_attempts:
                raise ExtractionFailure("composer focus trace exceeds its configured bounded tap-attempt limit")
            tap = attempt.get("tap")
            before = attempt.get("before")
            after = attempt.get("after")
            if not isinstance(tap, dict) or not isinstance(before, dict) or not isinstance(after, dict):
                raise ExtractionFailure("composer focus trace is missing target bounds or active-element snapshots")
            if (not isinstance(attempt.get("nativeImeVisibleBefore"), bool)
                    or not isinstance(attempt.get("nativeImeVisibleAfter"), bool)):
                raise ExtractionFailure("composer focus trace is missing Android IME visibility around a tap")
            if "nativeImeVisibleAfterImeWait" in attempt and not isinstance(attempt["nativeImeVisibleAfterImeWait"], bool):
                raise ExtractionFailure("composer focus trace has an invalid post-IME wait observation")
        if focus_failure_screenshot is not None:
            raise ExtractionFailure("packaged composer run contains a failure-state focus screenshot")
        if "composer-recording-before-fix.png" in decoded:
            raise ExtractionFailure("accepted composer run contains the pre-fix recording screenshot")
        if "composer-mode-ime-failure.png" in decoded or "composer-mode-ime-failure.json" in decoded:
            raise ExtractionFailure("accepted composer run contains an IME-hidden state failure capture")
        for state in ("recording", "recording-insert", "cancel", "background", "transcribing", "transcribing-send", "review"):
            geometry_name = f"composer-{state}-geometry.json"
            try:
                mode_geometry = json.loads(decoded[geometry_name])
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ExtractionFailure(f"{geometry_name} is invalid JSON: {error}") from error
            if not isinstance(mode_geometry, dict) or mode_geometry.get("runId") != run_id:
                raise ExtractionFailure(f"{geometry_name} does not identify this run")
            if mode_geometry.get("state") != state:
                raise ExtractionFailure(f"{geometry_name} does not identify the {state} composer state")
            if state.startswith("recording") and "Your draft stays in the composer until you tap Insert or Send" not in str(mode_geometry.get("statusText", "")):
                raise ExtractionFailure(f"{state} screenshot has misleading dictation delivery instructions")
            expected_heading = ("Prompt dictation" if state.startswith(("recording", "transcribing"))
                                else "Review dictation" if state == "review" else "Prompt Composer")
            if mode_geometry.get("composerHeading") != expected_heading:
                raise ExtractionFailure(f"{state} screenshot does not identify the active prompt dictation mode")
            if (mode_geometry.get("composerHeadingVisible") is not True
                    or mode_geometry.get("composerHeadingDisplay") == "none"):
                raise ExtractionFailure(f"keyboard-down {state} screenshot must retain the composer heading")
            if state == "review" and "Transcript ready" not in str(mode_geometry.get("reviewText", "")):
                raise ExtractionFailure("review screenshot does not identify the transcript as ready for editing")
            if state in ("recording-insert", "transcribing-send"):
                acknowledged_writes = mode_geometry.get("acknowledgedWrites")
                writes_before_action = mode_geometry.get("acknowledgedWritesBeforeAction")
                if (type(acknowledged_writes) is not int or type(writes_before_action) is not int
                        or acknowledged_writes != writes_before_action):
                    raise ExtractionFailure(f"{state} screenshot does not prove the PTY write count stayed unchanged before the explicit action")
            if state == "background":
                lifecycle = mode_geometry.get("nativeLifecycle")
                background = lifecycle.get("background") if isinstance(lifecycle, dict) else None
                foreground = lifecycle.get("foreground") if isinstance(lifecycle, dict) else None
                if (not isinstance(lifecycle, dict) or lifecycle.get("runId") != run_id
                        or lifecycle.get("homeKeyDownInjected") is not True
                        or lifecycle.get("homeKeyUpInjected") is not True
                        or not isinstance(background, dict) or background.get("windowFocus") is not False
                        or background.get("lifecycleState") == "RESUMED"
                        or not isinstance(foreground, dict) or foreground.get("windowFocus") is not True
                        or foreground.get("lifecycleState") != "RESUMED"
                        or "Status: ok" not in str(lifecycle.get("launchOutput", ""))
                        or lifecycle.get("dictationCancelledAfterResume") is not True
                        or lifecycle.get("draftRestored") is not True
                        or lifecycle.get("lateResultIgnored") is not True):
                    raise ExtractionFailure("background composer evidence does not prove native HOME/resume cancellation and draft restoration")
            if mode_geometry.get("androidImeVisible") is not False or mode_geometry.get("keyboardVisible") is not False:
                raise ExtractionFailure(f"{state} modal screenshot does not prove the Android keyboard was dismissed")
            if mode_geometry.get("composerModal") is not True:
                raise ExtractionFailure(f"{state} screenshot does not prove the composer is an accessible modal sheet")
            if mode_geometry.get("draftReadOnly") is not False:
                raise ExtractionFailure(f"{state} screenshot changed the textarea editability")
            if mode_geometry.get("expectedDraftMatches") is not True:
                raise ExtractionFailure(f"{state} screenshot does not prove the expected target draft was retained")
            expected_locked = state.startswith("recording") or state.startswith("transcribing")
            if mode_geometry.get("draftEditingLocked") is not expected_locked:
                raise ExtractionFailure(f"{state} screenshot does not show the expected draft input lock")
            expected_presentation = "focus-anchor" if expected_locked else "editor"
            if mode_geometry.get("draftPresentation") != expected_presentation:
                raise ExtractionFailure(f"{state} screenshot does not show the expected editor presentation")
            if expected_locked:
                if (mode_geometry.get("draftOpacity") != "0" or mode_geometry.get("draftAriaHidden") is True
                        or mode_geometry.get("draftAriaLabel") != "Prompt dictation draft, read only during capture"
                        or mode_geometry.get("draftEditingLocked") is not True
                        or "composer-status" not in str(mode_geometry.get("draftDescribedBy", ""))
                        or mode_geometry.get("recordingModeVisible") is not True
                        or not str(mode_geometry.get("recordingModeLabel", "")).strip()
                        or mode_geometry.get("composerStatusAccessible") is not True
                        or mode_geometry.get("cancelAccessible") is not True
                        or mode_geometry.get("recordingControlsAccessible") is not True
                        or mode_geometry.get("recordingControlsSeparate") is not True):
                    raise ExtractionFailure(f"{state} screenshot does not retain an accessible draft anchor beside the visible recording surface")
                if state.startswith("recording"):
                    if (mode_geometry.get("cancelText") != "Discard"
                            or mode_geometry.get("cancelAriaLabel") != "Discard recording without transcribing"
                            or mode_geometry.get("previewVisible") is not True
                            or mode_geometry.get("previewLive") is not True
                            or mode_geometry.get("previewAccessible") is not True
                            or mode_geometry.get("timerAccessible") is not True
                            or mode_geometry.get("timerBesideWaveform") is not True
                            or not _timer_sits_beside_waveform(mode_geometry.get("timer"), mode_geometry.get("waveform"))
                            or mode_geometry.get("stopText") != ""
                            or mode_geometry.get("stopAccessibleName") != STOP_ACCESSIBLE_NAME
                            or mode_geometry.get("stopVisible") is not True
                            or mode_geometry.get("stopEnabled") is not True
                            or mode_geometry.get("stopInRecordingHeader") is not True
                            or mode_geometry.get("stopGlyphPresent") is not True
                            or not _has_48dp_square_bounds(mode_geometry.get("stop"))
                            or not str(mode_geometry.get("previewText", "")).strip()
                            or "composer-recording-preview" not in str(mode_geometry.get("draftDescribedBy", ""))):
                        raise ExtractionFailure("recording screenshot does not prove the icon-only, enabled 48dp Stop control and live preview")
                elif (mode_geometry.get("cancelText") != "Cancel"
                        or mode_geometry.get("cancelAriaLabel") != "Cancel dictation and restore the original draft"
                        or mode_geometry.get("transcribingStatusAccessible") is not True
                        or mode_geometry.get("insertAccessible") is not False
                        or mode_geometry.get("insertEnabled") is not False
                        or mode_geometry.get("insert") is not None
                        or mode_geometry.get("dictationSendAccessible") is not True
                        or mode_geometry.get("dictationSendEnabled") is not True
                        or mode_geometry.get("timerAccessible") is not True
                        or mode_geometry.get("timerVisible") is not True
                        or not re.fullmatch(r"\d{2}:\d{2}", str(mode_geometry.get("timerText", "")))
                        or mode_geometry.get("previewVisible") is not True
                        or mode_geometry.get("previewLive") is not True
                        or mode_geometry.get("previewAccessible") is not True
                        or not str(mode_geometry.get("previewText", "")).strip()):
                    raise ExtractionFailure("transcribing screenshot must show Cancel, timer, live preview, and Send while hiding Insert")
            elif state == "review" and (
                    mode_geometry.get("reviewVisible") is not True
                    or mode_geometry.get("reviewEditable") is not True
                    or mode_geometry.get("draftReadOnly") is not False
                    or mode_geometry.get("draftEditingLocked") is not False
                    or mode_geometry.get("draftPresentation") != "editor"
                    or mode_geometry.get("expectedDraftMatches") is not True
                    or mode_geometry.get("insertAccessible") is not True
                    or mode_geometry.get("insertEnabled") is not True):
                raise ExtractionFailure("review screenshot does not show the retained transcript in an editable draft with Insert")
            elif state in ("cancel", "background") and mode_geometry.get("recordingModeVisible") is not False:
                raise ExtractionFailure(f"{state} screenshot still shows a recording surface")
            viewport = mode_geometry.get("visualViewport")
            if not isinstance(viewport, dict):
                raise ExtractionFailure(f"{geometry_name} is missing the visible keyboard viewport")
            try:
                mode_height = float(viewport["height"])
                mode_width = float(viewport["width"])
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"{geometry_name} has invalid viewport bounds") from error
            required_rects = ["draft", "status", "actions"]
            if state == "review":
                required_rects.append("review")
                required_rects.append("insert")
                required_rects.append("send")
            elif state in ("cancel", "background"):
                required_rects.append("send")
                if mode_geometry.get("recordingMode") is not None:
                    raise ExtractionFailure(f"{state} screenshot still exposes the recording surface")
                if state == "cancel" and "original draft was restored" not in str(mode_geometry.get("statusText", "")):
                    raise ExtractionFailure("cancel screenshot does not state that the original draft was restored")
            else:
                required_rects.append("recordingMode")
            if expected_locked:
                if mode_geometry.get("cancelAccessible") is not True:
                    raise ExtractionFailure(f"{state} screenshot does not prove the accessible discard/cancel action")
                if mode_geometry.get("dictationSendAccessible") is not True or mode_geometry.get("dictationSendEnabled") is not True:
                    raise ExtractionFailure(f"{state} screenshot does not prove visible, enabled Send during dictation")
                required_rects.extend(("cancel", "dictationSend", "recordingActions"))
                if state.startswith("recording"):
                    if mode_geometry.get("insertAccessible") is not True or mode_geometry.get("insertEnabled") is not True:
                        raise ExtractionFailure(f"{state} screenshot does not prove visible, enabled Insert during recording")
                    required_rects.append("insert")
                    expected_actions = "composer-recording-cancel,composer-insert,composer-dictation-send"
                else:
                    if (mode_geometry.get("insertAccessible") is not False
                            or mode_geometry.get("insertEnabled") is not False
                            or mode_geometry.get("insert") is not None):
                        raise ExtractionFailure(f"{state} screenshot exposes Insert before editable review")
                    expected_actions = "composer-recording-cancel,composer-dictation-send"
                if mode_geometry.get("actionOrder") != expected_actions:
                    raise ExtractionFailure(f"{state} screenshot action order does not match the Kotlin composer")
            if state.startswith("recording"):
                required_rects.extend(("timer", "waveform", "preview", "recordingActions", "stop"))
            elif state.startswith("transcribing"):
                required_rects.extend(("recordingMode", "timer", "preview"))
            for rect_name in required_rects:
                rect = mode_geometry.get(rect_name)
                if not isinstance(rect, dict):
                    raise ExtractionFailure(f"{state} screenshot is missing the {rect_name} bounds")
                try:
                    top = float(rect["top"])
                    bottom = float(rect["bottom"])
                    left = float(rect["left"])
                    right = float(rect["right"])
                except (KeyError, TypeError, ValueError) as error:
                    raise ExtractionFailure(f"{geometry_name} has invalid bounds for {rect_name}") from error
                if top < 0 or bottom > mode_height + 0.5 or left < 0 or right > mode_width + 0.5:
                    raise ExtractionFailure(f"{rect_name} is clipped in the {state} modal screenshot")
            draft_bounds = mode_geometry["draft"]
            if expected_locked and (float(draft_bounds["right"]) - float(draft_bounds["left"]) > 1.0
                                    or float(draft_bounds["bottom"]) - float(draft_bounds["top"]) > 1.0):
                raise ExtractionFailure(f"{state} screenshot still renders the full textarea instead of the focus anchor")
        restart_capture_names = {
            "composer-recording-after-restart.png",
            "composer-recording-after-restart-geometry.json",
        }
        if restart_capture_names & set(decoded) and not restart_capture_names.issubset(decoded):
            raise ExtractionFailure("natural-restart recording screenshot and geometry must be captured together")
        if restart_capture_names.issubset(decoded):
            try:
                restart_geometry = json.loads(decoded["composer-recording-after-restart-geometry.json"])
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ExtractionFailure(f"composer-recording-after-restart-geometry.json is invalid: {error}") from error
            if not isinstance(restart_geometry, dict) or restart_geometry.get("runId") != run_id:
                raise ExtractionFailure("natural-restart recording geometry does not identify this run")
            if (restart_geometry.get("state") != "recording-after-restart"
                    or restart_geometry.get("dictationState") != "recording"
                    or restart_geometry.get("androidImeVisible") is not False
                    or restart_geometry.get("keyboardVisible") is not False
                    or restart_geometry.get("composerModal") is not True
                    or restart_geometry.get("expectedDraftMatches") is not True
                    or restart_geometry.get("recordingModeVisible") is not True
                    or restart_geometry.get("previewVisible") is not True
                    or restart_geometry.get("previewAccessible") is not True
                    or restart_geometry.get("timerAccessible") is not True
                    or restart_geometry.get("timerBesideWaveform") is not True
                    or not _timer_sits_beside_waveform(restart_geometry.get("timer"), restart_geometry.get("waveform"))
                    or restart_geometry.get("recordingControlsAccessible") is not True
                    or restart_geometry.get("recordingControlsSeparate") is not True
                    or restart_geometry.get("cancelText") != "Discard"
                    or restart_geometry.get("cancelAriaLabel") != "Discard recording without transcribing"
                    or restart_geometry.get("actionOrder") != "composer-recording-cancel,composer-insert,composer-dictation-send"
                    or restart_geometry.get("stopText") != ""
                    or restart_geometry.get("stopAccessibleName") != STOP_ACCESSIBLE_NAME
                    or restart_geometry.get("stopVisible") is not True
                    or restart_geometry.get("stopEnabled") is not True
                    or restart_geometry.get("stopInRecordingHeader") is not True
                    or restart_geometry.get("stopGlyphPresent") is not True
                    or not _has_48dp_square_bounds(restart_geometry.get("stop"))):
                raise ExtractionFailure("natural-restart capture does not prove active recording, preview, and Stop")
        try:
            dictation_send = json.loads(decoded["composer-dictation-send.json"])
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer-dictation-send.json is invalid JSON: {error}") from error
        if not isinstance(dictation_send, dict) or dictation_send.get("runId") != run_id:
            raise ExtractionFailure("dictated Send evidence does not identify this packaged run")
        if (dictation_send.get("stage") != "dictation-explicit-send"
                or dictation_send.get("dictationState") != "idle"
                or not isinstance(dictation_send.get("acknowledgedWrites"), int)
                or dictation_send["acknowledgedWrites"] != 2
                or dictation_send.get("draft") != ""
                or not isinstance(dictation_send.get("deliveryStatus"), str)
                or "Sent to the terminal" not in dictation_send["deliveryStatus"]):
            raise ExtractionFailure("dictated Send evidence does not prove an explicit Send from the reviewed draft")

        back_state_bytes = decoded.get("composer-back-workspace-restored.json")
        try:
            back_state = json.loads(back_state_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer-back-workspace-restored.json is invalid JSON: {error}") from error
        if (not isinstance(back_state, dict) or back_state.get("runId") != run_id
                or back_state.get("state") != "workspace-restored"
                or back_state.get("route") != "home"
                or back_state.get("homeSurface") != "live"
                or back_state.get("sshPhase") != "live"
                or back_state.get("composerLauncherVisible") is not True
                or back_state.get("composerVisible") is not False
                or back_state.get("androidImeVisible") is not False):
            raise ExtractionFailure("Android Back snapshot does not prove home + live PTY + visible prompt composer launcher")
        recorded_dictation_marker = dictation_send.get("marker")
        if (not isinstance(recorded_dictation_marker, str) or not recorded_dictation_marker
                or expected_dictation_marker is None or recorded_dictation_marker != expected_dictation_marker):
            raise ExtractionFailure("dictated Send evidence does not match this packaged journey")
    return decoded


def extract(run_id: str, logcat: Path, output: Path, *, validate_layout: bool = True,
            expected_terminal_marker: str | None = None,
            expected_dictation_marker: str | None = None) -> list[str]:
    try:
        log_text = logcat.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise ExtractionFailure(f"could not read Android logcat {logcat}: {error}") from error
    artifacts = parse_assets(log_text, run_id, validate_layout=validate_layout,
                             expected_terminal_marker=expected_terminal_marker,
                             expected_dictation_marker=expected_dictation_marker)
    output.mkdir(parents=True, exist_ok=True)
    for name, payload in artifacts.items():
        (output / name).write_bytes(payload)
    return sorted(artifacts)


def self_test() -> None:
    run_id = "js2857-self-test"
    marker = "PS2857_SENT_js2857-self-test"
    dictation_marker = "PS2857_DICTATION_EDITED_js2857-self-test"
    png = b"\x89PNG\r\n\x1a\n" + b"fixture" * 200
    post_send = json.dumps({
        "stage": "after-send",
        "captureEnabled": True,
        "terminalEvidenceSource": "xterm-active-buffer-after-render",
        "expectedMarker": marker,
        "visibleTerminalText": f"command output\n{marker}",
        "terminalDomText": f"command output\n{marker}",
        "sentMarkerAbsentFromSubmittedCommand": True,
        "sendToVisibleOutputLatencyMs": 120,
        "sendToVisibleOutputTiming": "Android uptime from Send touch-up to the first 60ms WebView poll with both executed rows rendered inside the visible xterm screen",
        "appTerminalDeliveryCount": 1,
        "appTerminalMissingRefCount": 0,
        "terminalWriteCount": 1,
        "terminalViewport": {"top": 40.0, "bottom": 120.0, "left": 10.0, "right": 390.0, "height": 80.0},
        "terminalHeading": {"text": f"{run_id}-bytes", "top": 25.0, "bottom": 35.0, "left": 20.0, "right": 200.0},
        "appBar": {"top": 0.0, "bottom": 20.0, "left": 0.0, "right": 400.0},
        "composer": {"top": 105.0, "bottom": 200.0, "left": 0.0, "right": 400.0},
        "byteOutputRow": {"top": 70.0, "bottom": 85.0, "left": 20.0, "right": 200.0},
        "markerRow": {"top": 90.0, "bottom": 100.0, "left": 20.0, "right": 200.0},
        "terminalOutputRowVisible": True,
        "capturedBeforeScroll": True,
        "screenScrollTop": 0,
        "documentScrollTop": 0,
        "visualViewport": {"height": 200.0, "width": 400.0},
        "keyboardVisible": False,
        "deliveryStatus": "Sent to the terminal.",
    }).encode()
    dictation_send = json.dumps({
        "stage": "dictation-explicit-send",
        "runId": run_id,
        "marker": dictation_marker,
        "dictationState": "idle",
        "acknowledgedWrites": 2,
        "draft": "",
        "deliveryStatus": "Sent to the terminal.",
    }).encode()
    title_state = json.dumps({
        "runId": run_id,
        "state": "idle",
        "composerVisible": True,
        "sheetFullyVisible": True,
        "keyboardVisible": True,
        "composerHeading": "Prompt Composer",
        "expectedComposerHeading": "Prompt Composer",
        "composerHeadingVisible": False,
        "composerHeadingDisplay": "none",
        "dictatePromptText": "",
        "expectedDictatePromptVisibleLabel": "",
        "dictatePromptGlyphPresent": True,
        "expectedDictatePromptAccessibleName": "Dictate prompt draft",
        "dictatePromptAccessibleName": "Dictate prompt draft",
        "dictatePromptVisible": True,
        "dictatePromptEnabled": True,
        "dictatePromptBounds": {"top": 516.0, "bottom": 564.0, "left": 348.0, "right": 396.0,
                                 "width": 48.0, "height": 48.0},
        "panelBounds": {"top": 336.0, "bottom": 572.0, "left": 0.0, "right": 412.0, "width": 412.0, "height": 236.0},
        "scrimBounds": {"top": 0.0, "bottom": 572.0, "left": 0.0, "right": 412.0, "width": 412.0, "height": 572.0},
        "viewport": {"width": 412.0, "height": 572.0},
        "draftBounds": {"top": 410.0, "bottom": 482.0, "left": 16.0, "right": 396.0, "width": 380.0, "height": 72.0},
        "actionsBounds": {"top": 507.0, "bottom": 572.0, "left": 16.0, "right": 396.0, "width": 380.0, "height": 65.0},
        "buttons": {"dictate": True, "insert": True, "send": True, "keys": True},
        "composerOpenKeysBounds": {"top": 344.0, "bottom": 392.0, "left": 340.0, "right": 388.0,
                                    "width": 48.0, "height": 48.0},
        "screenScrollTop": 0,
        "documentScrollTop": 0,
        "terminalHeading": f"{run_id}-bytes",
        "expectedSessionChrome": f"{run_id}-bytes",
    }).encode()
    route_state = json.dumps({
        "runId": run_id,
        "route": "home",
        "homeSurface": "live",
        "sshPhase": "live",
        "keyboardVisible": False,
        "viewport": {"width": 412.0, "height": 915.0},
        "composerPresent": False,
        "launcherVisible": True,
        "launcherEnabled": True,
        "promptAccessibleName": "Open prompt composer to type or dictate a prompt",
        "expectedPromptAccessibleName": "Open prompt composer to type or dictate a prompt",
        "promptTitle": "Open prompt composer to type or dictate a prompt",
        "promptVisibleText": "Prompt",
        "expectedPromptVisibleText": "Prompt",
        "promptIconVisible": True,
        "promptCenterHit": True,
        "inlineMicVisible": True,
        "inlineMicEnabled": True,
        "inlineMicLabel": "Dictate at terminal cursor",
        "expectedInlineMicLabel": "Dictate at terminal cursor",
        "inlineMicTitle": "Dictate at terminal cursor",
        "inlineMicVisibleText": "Dictate",
        "terminalDestinationLabels": [],
        "expectedTerminalDestinationLabels": [],
        "terminalDestinationVisible": False,
        "inlineMicIconVisible": True,
        "inlineMicCenterHit": True,
        "targetsSeparated": True,
        "terminalHeading": f"{run_id}-bytes",
        "expectedSession": f"{run_id}-bytes",
        "promptBounds": {"top": 700.0, "bottom": 748.0, "left": 312.0, "right": 360.0,
                         "width": 48.0, "height": 48.0},
        "inlineMicBounds": {"top": 700.0, "bottom": 748.0, "left": 360.0, "right": 408.0,
                            "width": 48.0, "height": 48.0},
    }).encode()
    confused_route_state_value = json.loads(route_state)
    confused_route_state_value["inlineMicLabel"] = "Dictate prompt"
    confused_route_state = json.dumps(confused_route_state_value).encode()
    unnamed_prompt_route_value = json.loads(route_state)
    unnamed_prompt_route_value["promptAccessibleName"] = ""
    unnamed_prompt_route = json.dumps(unnamed_prompt_route_value).encode()
    mismatched_prompt_title_value = json.loads(route_state)
    mismatched_prompt_title_value["promptTitle"] = "Open terminal"
    mismatched_prompt_title = json.dumps(mismatched_prompt_title_value).encode()
    hidden_prompt_icon_value = json.loads(route_state)
    hidden_prompt_icon_value["promptIconVisible"] = False
    hidden_prompt_icon = json.dumps(hidden_prompt_icon_value).encode()
    visible_prompt_caption_value = json.loads(route_state)
    visible_prompt_caption_value["promptVisibleText"] = "Prompt"
    visible_prompt_caption = json.dumps(visible_prompt_caption_value).encode()
    missing_prompt_caption_value = json.loads(route_state)
    missing_prompt_caption_value["promptVisibleText"] = ""
    missing_prompt_caption = json.dumps(missing_prompt_caption_value).encode()
    visible_terminal_mic_caption_value = json.loads(route_state)
    visible_terminal_mic_caption_value["inlineMicVisibleText"] = "Dictate"
    visible_terminal_mic_caption = json.dumps(visible_terminal_mic_caption_value).encode()
    missing_terminal_mic_caption_value = json.loads(route_state)
    missing_terminal_mic_caption_value["inlineMicVisibleText"] = ""
    missing_terminal_mic_caption = json.dumps(missing_terminal_mic_caption_value).encode()
    visible_terminal_destination_value = json.loads(route_state)
    visible_terminal_destination_value["terminalDestinationLabels"] = ["Dictate"]
    visible_terminal_destination_value["expectedTerminalDestinationLabels"] = ["Dictate"]
    visible_terminal_destination_value["terminalDestinationVisible"] = True
    visible_terminal_destination = json.dumps(visible_terminal_destination_value).encode()
    missed_prompt_center_value = json.loads(route_state)
    missed_prompt_center_value["promptCenterHit"] = False
    missed_prompt_center = json.dumps(missed_prompt_center_value).encode()
    mismatched_inline_mic_title_value = json.loads(route_state)
    mismatched_inline_mic_title_value["inlineMicTitle"] = "Dictate prompt"
    mismatched_inline_mic_title = json.dumps(mismatched_inline_mic_title_value).encode()
    hidden_inline_mic_icon_value = json.loads(route_state)
    hidden_inline_mic_icon_value["inlineMicIconVisible"] = False
    hidden_inline_mic_icon = json.dumps(hidden_inline_mic_icon_value).encode()
    missed_inline_mic_center_value = json.loads(route_state)
    missed_inline_mic_center_value["inlineMicCenterHit"] = False
    missed_inline_mic_center = json.dumps(missed_inline_mic_center_value).encode()
    unseparated_route_value = json.loads(route_state)
    unseparated_route_value["targetsSeparated"] = False
    unseparated_route = json.dumps(unseparated_route_value).encode()
    overlapping_route_value = json.loads(route_state)
    overlapping_route_value["inlineMicBounds"]["left"] = 340.0
    overlapping_route_value["inlineMicBounds"]["right"] = 388.0
    overlapping_route = json.dumps(overlapping_route_value).encode()
    small_route_prompt_value = json.loads(route_state)
    small_route_prompt_value["promptBounds"]["width"] = 47.0
    small_route_prompt = json.dumps(small_route_prompt_value).encode()
    clipped_route_mic_value = json.loads(route_state)
    clipped_route_mic_value["inlineMicBounds"]["right"] = 420.0
    clipped_route_mic = json.dumps(clipped_route_mic_value).encode()
    launcher_before = json.dumps({
        "runId": run_id,
        "native": {"windowHasFocus": True, "webViewHasFocus": True, "imeVisible": False,
                    "webViewWidthPx": 1080, "webViewHeightPx": 2400},
        "dom": {
            "stage": "before-tap",
            "route": "home",
            "homeSurface": "live",
            "sshPhase": "live",
            "composerPresent": False,
            "launcherPresent": True,
            "launcherVisible": True,
            "launcherDisabled": False,
            "centerHitMatchesLauncher": True,
            "documentHasFocus": True,
            "keyboardVisible": False,
            "visualViewport": {"width": 412.0, "height": 915.0},
        },
    }).encode()
    launcher_before_ime_value = json.loads(launcher_before)
    launcher_before_ime_value["native"].update({"imeVisible": True, "webViewHeightPx": 1499})
    launcher_before_ime_value["dom"].update({
        "keyboardVisible": True,
        "visualViewport": {"width": 412.0, "height": 572.0},
    })
    launcher_before_ime = json.dumps(launcher_before_ime_value).encode()
    launcher_after = json.dumps({
        "runId": run_id,
        "native": {"windowHasFocus": True, "webViewHasFocus": True, "imeVisible": True},
        "dom": {
            "stage": "after-tap",
            "route": "home",
            "homeSurface": "live",
            "sshPhase": "live",
            "composerPresent": True,
            "composerVisible": True,
            "composerRole": "dialog",
            "composerAriaModal": "true",
            "activeElement": {"testid": "prompt-draft"},
            "pointerEvents": [
                {"type": "pointerdown", "isTrusted": True, "launcher": {"testid": "prompt-composer-launcher"}},
                {"type": "pointerup", "isTrusted": True, "launcher": {"testid": "prompt-composer-launcher"}},
                {"type": "click", "isTrusted": True, "launcher": {"testid": "prompt-composer-launcher"}},
            ],
        },
        "physicalTap": {
            "selector": "[data-testid=prompt-composer-launcher]",
            "centerHitMatchesTarget": True,
            "downInjected": True,
            "upInjected": True,
        },
        "attempts": [{
            "attempt": 1,
            "physicalTap": {
                "selector": "[data-testid=prompt-composer-launcher]",
                "centerHitMatchesTarget": True,
                "downInjected": True,
                "upInjected": True,
            },
            "state": {
                "native": {"windowHasFocus": True, "webViewHasFocus": True, "imeVisible": True},
                "dom": {"composerPresent": True},
            },
            "failure": None,
        }],
        "firstAttemptFailure": None,
        "openWaitFailure": None,
    }).encode()
    back_state = json.dumps({
        "runId": run_id,
        "state": "workspace-restored",
        "route": "home",
        "homeSurface": "live",
        "sshPhase": "live",
        "composerLauncherVisible": True,
        "composerVisible": False,
        "androidImeVisible": False,
    }).encode()
    one_write_dictation_send_value = json.loads(dictation_send)
    one_write_dictation_send_value["acknowledgedWrites"] = 1
    one_write_dictation_send = json.dumps(one_write_dictation_send_value).encode()
    def mode_geometry_payload(state: str) -> bytes:
        rect = {"top": 10.0, "bottom": 60.0, "left": 5.0, "right": 395.0}
        timer_rect = {"top": 28.0, "bottom": 41.0, "left": 10.0, "right": 48.0}
        waveform_rect = {"top": 18.0, "bottom": 50.0, "left": 60.0, "right": 380.0}
        recording = state.startswith("recording")
        transcribing = state.startswith("transcribing")
        anchored = recording or transcribing
        draft_rect = {"top": 10.0, "bottom": 11.0, "left": 5.0, "right": 6.0} if anchored else rect
        payload = {
            "runId": run_id,
            "state": state,
            "composerHeading": "Prompt dictation" if anchored else "Review dictation" if state == "review" else "Prompt Composer",
            "composerHeadingVisible": True,
            "composerHeadingDisplay": "flex",
            "dictationState": "recording" if recording else "transcribing" if transcribing
            else "review" if state == "review" else "idle",
            "androidImeVisible": False,
            "keyboardVisible": False,
            "composerModal": True,
            "draftFocused": False,
            "activeElementTestId": "",
            "draftReadOnly": False,
            "draftEditingLocked": anchored,
            "expectedDraftMatches": True,
            "statusText": "Dictation cancelled. Your original draft was restored." if state == "cancel"
            else "Your draft stays in the composer until you tap Insert or Send." if recording else "",
            "reviewText": "Transcript ready. Edit the draft before choosing Insert or Send." if state == "review" else "",
            "reviewEditable": state == "review",
            "acknowledgedWrites": 1 if state == "transcribing-send" else 0,
            "acknowledgedWritesBeforeAction": 1 if state == "transcribing-send" else 0,
            "draftPresentation": "focus-anchor" if anchored else "editor",
            "draftOpacity": "0" if anchored else "1",
            "draftAriaHidden": False,
            "draftAriaLabel": "Prompt dictation draft, read only during capture" if anchored else "Prompt draft",
            "draftDescribedBy": "composer-recording-preview composer-status" if recording else "composer-status" if transcribing else "",
            "recordingModeVisible": anchored,
            "recordingModeLabel": "Prompt dictation recording" if recording else "Transcribing prompt" if transcribing else "",
            "previewVisible": anchored,
            "previewLive": anchored,
            "previewAccessible": anchored,
            "previewText": "PS2857_DICTATION_INSERT_js2857-self-test" if state == "recording-insert" else "transcript in progress" if transcribing else "discard this dictated phrase" if recording else "",
            "timerAccessible": anchored,
            "timerVisible": anchored,
            "timerText": "00:12" if anchored else "",
            "timerBesideWaveform": recording,
            "recordingControlsAccessible": anchored,
            "recordingControlsSeparate": anchored,
            "cancelAccessible": anchored,
            "cancelText": "Discard" if recording else "Cancel" if transcribing else "",
            "cancelAriaLabel": "Discard recording without transcribing" if recording
            else "Cancel dictation and restore the original draft" if transcribing else "",
            "stopText": "",
            "stopAccessibleName": STOP_ACCESSIBLE_NAME if recording else "",
            "stopVisible": recording,
            "stopEnabled": recording,
            "stopInRecordingHeader": recording,
            "stopGlyphPresent": recording,
            "insertAccessible": recording or state == "review",
            "insertEnabled": recording or state == "review",
            "dictationSendAccessible": anchored,
            "dictationSendEnabled": anchored,
            "transcribingStatusAccessible": transcribing,
            "composerStatusAccessible": True,
            "reviewVisible": state == "review",
            "visualViewport": {"height": 240.0, "width": 400.0},
            "draft": draft_rect,
            "status": rect,
            "actions": rect,
        }
        if state == "background":
            payload["nativeLifecycle"] = {
                "runId": run_id,
                "homeKeyDownInjected": True,
                "homeKeyUpInjected": True,
                "background": {"windowFocus": False, "lifecycleState": "CREATED"},
                "foreground": {"windowFocus": True, "lifecycleState": "RESUMED"},
                "launchOutput": "Status: ok",
                "dictationCancelledAfterResume": True,
                "draftRestored": True,
                "lateResultIgnored": True,
            }
        if anchored:
            payload["recordingMode"] = rect
            payload["cancel"] = rect
            payload["dictationSend"] = rect
            payload["recordingActions"] = rect
            payload["actionOrder"] = (
                "composer-recording-cancel,composer-insert,composer-dictation-send"
                if recording else "composer-recording-cancel,composer-dictation-send"
            )
            if recording:
                payload["insert"] = rect
        if state in ("cancel", "background"):
            payload["send"] = rect
        if recording:
            payload.update({"timer": timer_rect, "waveform": waveform_rect, "preview": rect,
                            "stop": {"top": 10.0, "bottom": 58.0, "left": 347.0, "right": 395.0,
                                     "width": 48.0, "height": 48.0}})
        elif transcribing:
            payload.update({"timer": timer_rect, "preview": rect})
        if state == "review":
            payload["review"] = rect
            payload["insert"] = rect
            payload["send"] = rect
        return json.dumps(payload).encode()
    focus_trace = json.dumps({
        "runId": run_id,
        "androidApi": 35,
        "maxAttempts": 2,
        "forcedFirstPostAttachMiss": False,
        "attempts": [{
            "stage": "uncertain-session-after-attach",
            "attempt": 1,
            "requestedSelector": "[data-testid=prompt-draft]",
            "before": {"activeElement": {"id": "xterm-helper-textarea"}},
            "nativeImeVisibleBefore": False,
            "tap": {"top": 100, "bottom": 150, "screenX": 200, "screenY": 400},
            "trustedPointerDownOnRequestedTarget": True,
            "draftFocusedAfter": True,
            "nativeImeVisibleAfter": True,
            "nativeImeVisibleAfterImeWait": True,
            "after": {"activeElement": {"id": "prompt-draft"}},
        }],
    }).encode()
    forced_focus_value = json.loads(focus_trace)
    forced_focus_value["forcedFirstPostAttachMiss"] = True
    forced_focus_value["attempts"] = [{
        "stage": "uncertain-session-after-attach",
        "attempt": 1,
        "requestedSelector": "[data-testid=prompt-composer] [data-testid=composer-status]",
        "before": {"activeElement": {"id": "composer-close"}},
        "nativeImeVisibleBefore": False,
        "tap": {"top": 100, "bottom": 118, "left": 50, "right": 300, "width": 412, "height": 600,
                "targetTestId": "composer-status", "targetWidth": 250, "targetHeight": 18,
                "targetVisible": True, "x": 175, "y": 109, "screenX": 200, "screenY": 400,
                "centerHitMatchesTarget": True,
                "centerHit": {"tag": "P", "testid": "composer-status", "className": "composer-status"}},
        "physicalPointerDown": {"type": "pointerdown", "isTrusted": True, "targetMatchesRequested": True,
                                 "clientX": 175, "clientY": 109,
                                 "target": {"tag": "P", "testid": "composer-status", "className": "composer-status"}},
        "physicalCenterHit": True,
        "trustedPointerDownOnRequestedTarget": True,
        "draftFocusedAfter": False,
        "nativeImeVisibleAfter": False,
        "visibleInertStatusHit": True,
        "inertMissInsideComposer": True,
        "dialogStayedOpenAfterMiss": True,
        "draftStayedMountedAfterMiss": True,
        "after": {"composerPresent": True, "composerModal": True, "composerTitlePresent": True,
                  "draftPresent": True, "draftConnected": True, "draftFocused": False},
    }, {
        "stage": "uncertain-session-after-attach",
        "attempt": 2,
        "requestedSelector": "[data-testid=prompt-draft]",
        "before": {"activeElement": {"id": "composer-close"}},
        "nativeImeVisibleBefore": False,
        "tap": {"top": 100, "bottom": 150, "screenX": 200, "screenY": 400},
        "trustedPointerDownOnRequestedTarget": True,
        "draftFocusedAfter": True,
        "nativeImeVisibleAfter": True,
        "nativeImeVisibleAfterImeWait": True,
        "after": {"composerPresent": True, "composerModal": True, "composerTitlePresent": True,
                  "draftPresent": True, "draftConnected": True, "draftFocused": True},
    }]
    forced_focus_trace = json.dumps(forced_focus_value).encode()
    broken_forced_focus_value = json.loads(forced_focus_trace)
    broken_forced_focus_value["attempts"][0]["requestedSelector"] = ".terminal-viewport"
    broken_forced_focus_value["attempts"][0]["physicalCenterHit"] = False
    broken_forced_focus_value["attempts"][0]["visibleInertStatusHit"] = False
    broken_forced_focus_value["attempts"][0]["inertMissInsideComposer"] = False
    broken_forced_focus_value["attempts"][0]["dialogStayedOpenAfterMiss"] = False
    broken_forced_focus_value["attempts"][0]["draftStayedMountedAfterMiss"] = False
    broken_forced_focus_value["attempts"][0]["after"].update({"composerPresent": False, "composerModal": False,
                                                               "composerTitlePresent": False, "draftPresent": False,
                                                               "draftConnected": False})
    broken_forced_focus_trace = json.dumps(broken_forced_focus_value).encode()
    hidden_forced_focus_value = json.loads(forced_focus_trace)
    hidden_forced_focus_value["attempts"][0]["tap"]["targetVisible"] = False
    hidden_forced_focus_trace = json.dumps(hidden_forced_focus_value).encode()
    off_center_forced_focus_value = json.loads(forced_focus_trace)
    off_center_forced_focus_value["attempts"][0]["physicalPointerDown"]["clientX"] += 8
    off_center_forced_focus_trace = json.dumps(off_center_forced_focus_value).encode()
    clipped_post_send_value = json.loads(post_send)
    clipped_post_send_value["terminalViewport"]["top"] = 220.0
    clipped_post_send_value["terminalViewport"]["bottom"] = 300.0
    clipped_post_send = json.dumps(clipped_post_send_value).encode()
    early_send_writes = json.loads(mode_geometry_payload("transcribing-send"))
    early_send_writes["acknowledgedWrites"] += 1
    early_send_writes_geometry = json.dumps(early_send_writes).encode()
    misleading_recording_copy = json.loads(mode_geometry_payload("recording-insert"))
    misleading_recording_copy["statusText"] = "Nothing is sent until you review and tap Send."
    misleading_recording_copy_geometry = json.dumps(misleading_recording_copy).encode()
    visible_stop_text_value = json.loads(mode_geometry_payload("recording"))
    visible_stop_text_value["stopText"] = "Stop"
    visible_stop_text_geometry = json.dumps(visible_stop_text_value).encode()
    wrong_stop_accessibility_value = json.loads(mode_geometry_payload("recording"))
    wrong_stop_accessibility_value["stopAccessibleName"] = "Stop dictation"
    wrong_stop_accessibility_geometry = json.dumps(wrong_stop_accessibility_value).encode()
    hidden_stop_value = json.loads(mode_geometry_payload("recording"))
    hidden_stop_value["stopVisible"] = False
    hidden_stop_geometry = json.dumps(hidden_stop_value).encode()
    disabled_stop_value = json.loads(mode_geometry_payload("recording"))
    disabled_stop_value["stopEnabled"] = False
    disabled_stop_geometry = json.dumps(disabled_stop_value).encode()
    missing_stop_glyph_value = json.loads(mode_geometry_payload("recording"))
    missing_stop_glyph_value["stopGlyphPresent"] = False
    missing_stop_glyph_geometry = json.dumps(missing_stop_glyph_value).encode()
    wrong_stop_size_value = json.loads(mode_geometry_payload("recording"))
    wrong_stop_size_value["stop"]["width"] = 47.0
    wrong_stop_size_geometry = json.dumps(wrong_stop_size_value).encode()
    misplaced_stop_value = json.loads(mode_geometry_payload("recording"))
    misplaced_stop_value["stopInRecordingHeader"] = False
    misplaced_stop_geometry = json.dumps(misplaced_stop_value).encode()
    cancel_labeled_recording_discard = json.loads(mode_geometry_payload("recording"))
    cancel_labeled_recording_discard["cancelText"] = "Cancel"
    cancel_labeled_recording_discard["cancelAriaLabel"] = "Cancel dictation and restore the original draft"
    cancel_labeled_recording_discard_geometry = json.dumps(cancel_labeled_recording_discard).encode()
    discard_labeled_transcribing_cancel = json.loads(mode_geometry_payload("transcribing"))
    discard_labeled_transcribing_cancel["cancelText"] = "Discard"
    discard_labeled_transcribing_cancel["cancelAriaLabel"] = "Discard recording without transcribing"
    discard_labeled_transcribing_cancel_geometry = json.dumps(discard_labeled_transcribing_cancel).encode()
    premature_transcribing_insert = json.loads(mode_geometry_payload("transcribing"))
    premature_transcribing_insert["insertAccessible"] = True
    premature_transcribing_insert["insertEnabled"] = True
    premature_transcribing_insert["insert"] = {"top": 10.0, "bottom": 58.0, "left": 347.0, "right": 395.0}
    premature_transcribing_insert["actionOrder"] = "composer-recording-cancel,composer-insert,composer-dictation-send"
    premature_transcribing_insert_geometry = json.dumps(premature_transcribing_insert).encode()
    timer_below_waveform = json.loads(mode_geometry_payload("recording"))
    timer_below_waveform["timerBesideWaveform"] = True
    timer_below_waveform["timer"]["left"] = 80.0
    timer_below_waveform["timer"]["right"] = 120.0
    timer_below_waveform_geometry = json.dumps(timer_below_waveform).encode()
    inaccessible_transcript = json.loads(mode_geometry_payload("recording"))
    inaccessible_transcript["previewAccessible"] = False
    inaccessible_transcript_geometry = json.dumps(inaccessible_transcript).encode()
    nested_recording_actions = json.loads(mode_geometry_payload("recording"))
    nested_recording_actions["recordingControlsSeparate"] = False
    nested_recording_actions_geometry = json.dumps(nested_recording_actions).encode()
    uneditable_review = json.loads(mode_geometry_payload("review"))
    uneditable_review["reviewEditable"] = False
    uneditable_review_geometry = json.dumps(uneditable_review).encode()
    hidden_keyboard_down_heading = json.loads(mode_geometry_payload("review"))
    hidden_keyboard_down_heading["composerHeadingVisible"] = False
    hidden_keyboard_down_heading["composerHeadingDisplay"] = "none"
    hidden_keyboard_down_heading_geometry = json.dumps(hidden_keyboard_down_heading).encode()
    keyboard_up_post_send_value = json.loads(post_send)
    keyboard_up_post_send_value["keyboardVisible"] = True
    keyboard_up_post_send = json.dumps(keyboard_up_post_send_value).encode()
    negative_latency_value = json.loads(post_send)
    negative_latency_value["sendToVisibleOutputLatencyMs"] = -1
    negative_latency_post_send = json.dumps(negative_latency_value).encode()
    background_without_resume_value = json.loads(mode_geometry_payload("background"))
    background_without_resume_value["nativeLifecycle"]["foreground"]["lifecycleState"] = "CREATED"
    background_without_resume = json.dumps(background_without_resume_value).encode()
    missing_latency_value = json.loads(post_send)
    del missing_latency_value["sendToVisibleOutputLatencyMs"]
    missing_latency_post_send = json.dumps(missing_latency_value).encode()
    occluded_marker_value = json.loads(post_send)
    occluded_marker_value["markerRow"]["bottom"] = occluded_marker_value["composer"]["top"] + 1
    occluded_marker_post_send = json.dumps(occluded_marker_value).encode()
    clipped_composer_value = json.loads(post_send)
    clipped_composer_value["composer"]["bottom"] = clipped_composer_value["visualViewport"]["height"] + 1
    clipped_composer_post_send = json.dumps(clipped_composer_value).encode()
    session_leaked_title_value = json.loads(title_state)
    session_leaked_title_value["composerHeading"] = "Compose for testuser:js2857-self-test-bytes"
    session_leaked_title_state = json.dumps(session_leaked_title_value).encode()
    ambiguous_dictate_text_value = json.loads(title_state)
    ambiguous_dictate_text_value["dictatePromptText"] = "Dictate prompt"
    ambiguous_dictate_text = json.dumps(ambiguous_dictate_text_value).encode()
    ambiguous_dictate_accessibility_value = json.loads(title_state)
    ambiguous_dictate_accessibility_value["dictatePromptAccessibleName"] = "Start dictation"
    ambiguous_dictate_accessibility = json.dumps(ambiguous_dictate_accessibility_value).encode()
    clipped_dictate_prompt_value = json.loads(title_state)
    clipped_dictate_prompt_value["dictatePromptBounds"].update({"left": 376.0, "right": 424.0})
    clipped_dictate_prompt = json.dumps(clipped_dictate_prompt_value).encode()
    wrong_size_dictate_value = json.loads(title_state)
    wrong_size_dictate_value["dictatePromptBounds"]["width"] = 47.0
    wrong_size_dictate = json.dumps(wrong_size_dictate_value).encode()
    hidden_dictate_value = json.loads(title_state)
    hidden_dictate_value["dictatePromptVisible"] = False
    hidden_dictate = json.dumps(hidden_dictate_value).encode()
    disabled_dictate_value = json.loads(title_state)
    disabled_dictate_value["dictatePromptEnabled"] = False
    disabled_dictate = json.dumps(disabled_dictate_value).encode()
    hidden_open_keys_value = json.loads(title_state)
    hidden_open_keys_value["buttons"]["keys"] = False
    hidden_open_keys = json.dumps(hidden_open_keys_value).encode()
    clipped_open_keys_value = json.loads(title_state)
    clipped_open_keys_value["composerOpenKeysBounds"].update({"left": 376.0, "right": 424.0})
    clipped_open_keys = json.dumps(clipped_open_keys_value).encode()
    offscreen_title_value = json.loads(title_state)
    offscreen_title_value["sheetFullyVisible"] = False
    offscreen_title_value["panelBounds"]["top"] = 914.0
    offscreen_title_value["panelBounds"]["bottom"] = 1329.0
    offscreen_title_state = json.dumps(offscreen_title_value).encode()
    visible_keyboard_title_value = json.loads(title_state)
    visible_keyboard_title_value["composerHeadingVisible"] = True
    visible_keyboard_title_value["composerHeadingDisplay"] = "flex"
    visible_keyboard_title_state = json.dumps(visible_keyboard_title_value).encode()
    unpadded_title_draft_value = json.loads(title_state)
    unpadded_title_draft_value["draftBounds"]["left"] = 1.0
    unpadded_title_draft_value["draftBounds"]["right"] = 411.0
    unpadded_title_draft = json.dumps(unpadded_title_draft_value).encode()
    unpadded_title_actions_value = json.loads(title_state)
    unpadded_title_actions_value["actionsBounds"]["left"] = 1.0
    unpadded_title_actions_value["actionsBounds"]["right"] = 411.0
    unpadded_title_actions = json.dumps(unpadded_title_actions_value).encode()
    untrusted_launcher_after_value = json.loads(launcher_after)
    untrusted_launcher_after_value["dom"]["pointerEvents"] = []
    untrusted_launcher_after = json.dumps(untrusted_launcher_after_value).encode()
    uncertain_launcher_failure_value = json.loads(launcher_after)
    uncertain_launcher_failure_value["openWaitFailure"] = "composer modal did not open"
    uncertain_launcher_failure = json.dumps(uncertain_launcher_failure_value).encode()
    first_attach_launcher_failure_value = json.loads(launcher_after)
    first_attach_launcher_failure_value["openWaitFailure"] = "composer modal did not open"
    first_attach_launcher_failure = json.dumps(first_attach_launcher_failure_value).encode()
    uncertain_ime_mismatch_value = json.loads(launcher_before_ime)
    uncertain_ime_mismatch_value["dom"]["keyboardVisible"] = False
    uncertain_ime_mismatch = json.dumps(uncertain_ime_mismatch_value).encode()
    hidden_ime_geometry_mismatch_value = json.loads(launcher_before)
    hidden_ime_geometry_mismatch_value["native"]["webViewHeightPx"] = 1499
    hidden_ime_geometry_mismatch = json.dumps(hidden_ime_geometry_mismatch_value).encode()
    def geometry_payload(*, ime_visible: bool = True, app_bar_top: float = 24.0,
                         send_bottom: float = 218.0, terminal_height: float = 60.0) -> bytes:
        return json.dumps({
            "androidImeVisible": ime_visible,
            "visualViewport": {"height": 240.0, "width": 400.0},
            "terminalViewport": {"top": 30.0, "bottom": 30.0 + terminal_height, "left": 1.0, "right": 399.0, "height": terminal_height},
            "composerIsSheet": True,
            "composerHeadingVisible": False,
            "composerHeadingDisplay": "none",
            "appBar": {"top": app_bar_top},
            "draft": {"top": 100.0, "bottom": 150.0, "left": 16.0, "right": 384.0},
            "status": {"top": 152.0, "bottom": 166.0, "left": 16.0, "right": 384.0},
            "actions": {"top": 168.0, "bottom": 219.0, "left": 16.0, "right": 384.0},
            "buttons": {
                "discard": {"top": 170.0, "bottom": 218.0, "left": 16.0, "right": 70.0},
                "insert": {"top": 170.0, "bottom": 218.0, "left": 250.0, "right": 310.0},
                "send": {"top": 170.0, "bottom": send_bottom, "left": 320.0, "right": 384.0},
                "keys": {"top": 20.0, "bottom": 68.0, "left": 320.0, "right": 368.0},
            },
            "safeArea": {"topCss": 24.0, "bottomCss": 0.0, "shellTopPadding": 24.0, "shellBottomPadding": 0.0, "keyboardVisible": True},
            "nativeInsets": {"statusBarTopDp": 24.0, "imeBottomDp": 300.0},
        }).encode()

    geometry = geometry_payload()
    undersized_open_keys_value = json.loads(geometry)
    undersized_open_keys_value["buttons"]["keys"]["bottom"] = 67.0
    undersized_open_keys_geometry = json.dumps(undersized_open_keys_value).encode()

    def make_lines(geometry_bytes: bytes = geometry, post_send_bytes: bytes = post_send,
                   dictation_send_bytes: bytes = dictation_send,
                   route_state_bytes: bytes = route_state,
                   focus_trace_bytes: bytes = focus_trace,
                   title_state_bytes: bytes = title_state,
                   launcher_before_bytes: bytes = launcher_before,
                   launcher_after_bytes: bytes = launcher_after,
                   launcher_first_attach_after_bytes: bytes = launcher_after,
                   launcher_before_uncertain_bytes: bytes = launcher_before_ime,
                   launcher_after_uncertain_bytes: bytes = launcher_after,
                   background_geometry_bytes: bytes | None = None,
                   recording_geometry_bytes: bytes | None = None,
                   recording_insert_geometry_bytes: bytes | None = None,
                   transcribing_geometry_bytes: bytes | None = None,
                   transcribing_send_geometry_bytes: bytes | None = None,
                   review_geometry_bytes: bytes | None = None,
                   inline_preview_bytes: bytes = png) -> list[str]:
        source = [
            ("composer-route.png", png),
            ("composer-route.json", route_state_bytes),
            ("composer-title.png", png),
            ("composer-title.json", title_state_bytes),
            ("composer-launcher-before-reopen.png", png),
            ("composer-launcher-before-reopen.json", launcher_before_bytes),
            ("composer-launcher-after-reopen.png", png),
            ("composer-launcher-after-reopen.json", launcher_after_bytes),
            ("composer-launcher-before-uncertain-first-attach.png", png),
            ("composer-launcher-before-uncertain-first-attach.json", launcher_before),
            ("composer-launcher-after-uncertain-first-attach.png", png),
            ("composer-launcher-after-uncertain-first-attach.json", launcher_first_attach_after_bytes),
            ("composer-launcher-before-uncertain-reattach.json", launcher_before_uncertain_bytes),
            ("composer-launcher-after-uncertain-reattach.json", launcher_after_uncertain_bytes),
            ("composer-keyboard.png", png),
            ("composer-keyboard-geometry.json", geometry_bytes),
            ("composer-post-send.png", png),
            ("composer-post-send-terminal.json", post_send_bytes),
            ("inline-dictation-preview.png", inline_preview_bytes),
            ("composer-focus-trace.json", focus_trace_bytes),
            ("composer-recording.png", png),
            ("composer-recording-geometry.json", recording_geometry_bytes or mode_geometry_payload("recording")),
            ("composer-recording-insert.png", png),
            ("composer-recording-insert-geometry.json", recording_insert_geometry_bytes or mode_geometry_payload("recording-insert")),
            ("composer-cancel.png", png),
            ("composer-cancel-geometry.json", mode_geometry_payload("cancel")),
            ("composer-background.png", png),
            ("composer-background-geometry.json", background_geometry_bytes or mode_geometry_payload("background")),
            ("composer-transcribing.png", png),
            ("composer-transcribing-geometry.json", transcribing_geometry_bytes or mode_geometry_payload("transcribing")),
            ("composer-transcribing-send.png", png),
            ("composer-transcribing-send-geometry.json", transcribing_send_geometry_bytes or mode_geometry_payload("transcribing-send")),
            ("composer-review.png", png),
            ("composer-review-geometry.json", review_geometry_bytes or mode_geometry_payload("review")),
            ("composer-recording-after-restart.png", png),
            ("composer-recording-after-restart-geometry.json", mode_geometry_payload("recording-after-restart")),
            ("composer-dictation-send.json", dictation_send_bytes),
            ("composer-back-workspace-restored.png", png),
            ("composer-back-workspace-restored.json", back_state),
        ]
        lines: list[str] = []
        for name, payload in source:
            encoded = base64.b64encode(payload).decode()
            chunks = [encoded[index:index + 8] for index in range(0, len(encoded), 8)]
            digest = hashlib.sha256(payload).hexdigest()
            lines.append(f"I/PS2857Asset: BEGIN|{run_id}|{name}|{len(chunks)}|{digest}")
            lines.extend(f"I/PS2857Asset: DATA|{run_id}|{name}|{index}|{chunk}" for index, chunk in enumerate(chunks))
            lines.append(f"I/PS2857Asset: END|{run_id}|{name}")
        return lines

    lines = make_lines()
    assert parse_assets("\n".join(lines), run_id, expected_terminal_marker=marker,
                        expected_dictation_marker=dictation_marker)["inline-dictation-preview.png"] == png
    print("PASS: keyboard, inline dictation, composer-state, and post-send artifacts extract with complete chunks and matching SHA-256")

    keyboard_up_reopen_assets = parse_assets(
        "\n".join(make_lines(launcher_before_bytes=launcher_before_ime)), run_id,
        expected_terminal_marker=marker, expected_dictation_marker=dictation_marker)
    assert json.loads(keyboard_up_reopen_assets["composer-launcher-before-reopen.json"])["native"]["imeVisible"] is True
    print("PASS: composer launcher reopen accepts a keyboard-up state with matching native/WebView geometry")

    forced_focus_assets = parse_assets("\n".join(make_lines(focus_trace_bytes=forced_focus_trace)), run_id,
                                       expected_terminal_marker=marker,
                                       expected_dictation_marker=dictation_marker)
    assert json.loads(forced_focus_assets["composer-focus-trace.json"])["forcedFirstPostAttachMiss"] is True
    print("PASS: forced Composer miss stays inside the modal and retains a mounted draft for the physical retry")

    for label, accepted_route_state in (
        ("visible Prompt caption", visible_prompt_caption),
        ("visible Dictate destination label", visible_terminal_mic_caption),
    ):
        accepted_assets = parse_assets(
            "\n".join(make_lines(route_state_bytes=accepted_route_state)), run_id,
            expected_terminal_marker=marker, expected_dictation_marker=dictation_marker)
        accepted_state = json.loads(accepted_assets["composer-route.json"])
        assert accepted_state["promptVisibleText"] == "Prompt"
        assert accepted_state["inlineMicVisibleText"] == "Dictate"
        print(f"PASS: {label} is present in accepted dock evidence")

    for label, altered in (
        ("missing artifact", lines[:-1]),
        ("missing focus trace", [line for line in lines if "composer-focus-trace.json" not in line]),
        ("missing idle-terminal route evidence", [line for line in lines if "composer-route.json" not in line]),
        ("route confuses prompt dictation with terminal dictation",
         make_lines(route_state_bytes=confused_route_state)),
        ("Prompt composer has no accessible name", make_lines(route_state_bytes=unnamed_prompt_route)),
        ("Prompt composer has the wrong title", make_lines(route_state_bytes=mismatched_prompt_title)),
        ("Prompt composer icon is hidden", make_lines(route_state_bytes=hidden_prompt_icon)),
        ("missing Prompt caption is rejected", make_lines(route_state_bytes=missing_prompt_caption)),
        ("missing terminal Dictate caption is rejected", make_lines(route_state_bytes=missing_terminal_mic_caption)),
        ("visible terminal destination caption is rejected", make_lines(route_state_bytes=visible_terminal_destination)),
        ("Prompt composer center misses its target", make_lines(route_state_bytes=missed_prompt_center)),
        ("terminal dictation mic has the wrong title",
         make_lines(route_state_bytes=mismatched_inline_mic_title)),
        ("terminal dictation mic icon is hidden",
         make_lines(route_state_bytes=hidden_inline_mic_icon)),
        ("terminal dictation mic center misses its target",
         make_lines(route_state_bytes=missed_inline_mic_center)),
        ("Prompt and terminal dictation are not marked as separate",
         make_lines(route_state_bytes=unseparated_route)),
        ("Prompt and terminal dictation bounds overlap",
         make_lines(route_state_bytes=overlapping_route)),
        ("idle Prompt entry is below the 48dp touch target", make_lines(route_state_bytes=small_route_prompt)),
        ("idle inline terminal mic is clipped", make_lines(route_state_bytes=clipped_route_mic)),
        ("forced first miss closes Composer and removes its retry target",
         make_lines(focus_trace_bytes=broken_forced_focus_trace)),
        ("forced first miss does not hit visibly rendered inert status copy",
         make_lines(focus_trace_bytes=hidden_forced_focus_trace)),
        ("forced first miss physical pointerdown is not at the target center",
         make_lines(focus_trace_bytes=off_center_forced_focus_trace)),
        ("missing chunk", [line for line in lines if "DATA|" not in line or "|0|" not in line]),
        ("bad digest", [line.replace(hashlib.sha256(png).hexdigest(), "0" * 64) for line in lines]),
        ("IME hidden", make_lines(geometry_payload(ime_visible=False))),
        ("keyboard-up Prompt Composer title remains visible",
         make_lines(geometry_bytes=json.dumps({**json.loads(geometry), "composerHeadingVisible": True,
                                               "composerHeadingDisplay": "flex"}).encode())),
        ("keyboard-up composer draft loses its horizontal gutter",
         make_lines(geometry_bytes=json.dumps({**json.loads(geometry),
                                               "draft": {**json.loads(geometry)["draft"], "left": 1.0, "right": 399.0}}).encode())),
        ("keyboard-up composer actions lose their horizontal gutter",
         make_lines(geometry_bytes=json.dumps({**json.loads(geometry),
                                               "actions": {**json.loads(geometry)["actions"], "left": 1.0, "right": 399.0}}).encode())),
        ("send button clipped by viewport", make_lines(geometry_payload(send_bottom=241))),
        ("send touch target below 48dp", make_lines(geometry_payload(send_bottom=214))),
        ("More terminal keys touch target below 48dp", make_lines(geometry_bytes=undersized_open_keys_geometry)),
        ("status bar overlap", make_lines(geometry_payload(app_bar_top=0))),
        ("terminal context hidden", make_lines(geometry_payload(terminal_height=30))),
        ("post-send marker missing from rendered terminal", make_lines(post_send_bytes=post_send.replace(marker.encode(), b"wrong-marker"))),
        ("dictated Send reports only the body write", make_lines(dictation_send_bytes=one_write_dictation_send)),
        ("post-send screenshot missing", [line for line in lines if "composer-post-send.png" not in line]),
        ("post-send terminal record missing", [line for line in lines if "composer-post-send-terminal.json" not in line]),
        ("post-send marker belongs to another journey", make_lines(post_send_bytes=post_send.replace(marker.encode(), b"PS2857_SENT_other"))),
        ("post-send text source is not the rendered xterm buffer", make_lines(post_send_bytes=post_send.replace(b"xterm-active-buffer-after-render", b"unverified-dom-text"))),
        ("post-send terminal scrolled below the viewport", make_lines(post_send_bytes=clipped_post_send)),
        ("post-send screenshot captured with keyboard open", make_lines(post_send_bytes=keyboard_up_post_send)),
        ("post-send output latency missing", make_lines(post_send_bytes=missing_latency_post_send)),
        ("post-send output latency negative", make_lines(post_send_bytes=negative_latency_post_send)),
        ("post-send marker row is covered by the composer sheet", make_lines(post_send_bytes=occluded_marker_post_send)),
        ("post-send composer sheet clipped by viewport", make_lines(post_send_bytes=clipped_composer_post_send)),
        ("composer launcher has no trusted physical pointer evidence",
         make_lines(launcher_after_bytes=untrusted_launcher_after)),
        ("uncertain-session first-attach launcher did not open the composer",
         make_lines(launcher_first_attach_after_bytes=first_attach_launcher_failure)),
        ("uncertain-session composer reopen did not open the modal",
         make_lines(launcher_after_uncertain_bytes=uncertain_launcher_failure)),
        ("uncertain-session launcher keyboard geometry is inconsistent",
         make_lines(launcher_before_uncertain_bytes=uncertain_ime_mismatch)),
        ("keyboard-hidden launcher state has mismatched native/WebView geometry",
         make_lines(launcher_before_bytes=hidden_ime_geometry_mismatch)),
        ("background cancellation lacks a native resume", make_lines(background_geometry_bytes=background_without_resume)),
        ("transcribing-time Send writes before the explicit action",
         make_lines(transcribing_send_geometry_bytes=early_send_writes_geometry)),
        ("recording copy omits Insert delivery", make_lines(recording_insert_geometry_bytes=misleading_recording_copy_geometry)),
        ("recording Stop exposes visible label text",
         make_lines(recording_geometry_bytes=visible_stop_text_geometry)),
        ("recording Stop has the wrong accessible name",
         make_lines(recording_geometry_bytes=wrong_stop_accessibility_geometry)),
        ("recording Stop is hidden", make_lines(recording_geometry_bytes=hidden_stop_geometry)),
        ("recording Stop is disabled", make_lines(recording_geometry_bytes=disabled_stop_geometry)),
        ("recording Stop omits the square SVG glyph",
         make_lines(recording_geometry_bytes=missing_stop_glyph_geometry)),
        ("recording Stop bounds are not 48dp square",
         make_lines(recording_geometry_bytes=wrong_stop_size_geometry)),
        ("recording Stop is outside the capture header",
         make_lines(recording_geometry_bytes=misplaced_stop_geometry)),
        ("recording action is labeled Cancel instead of Discard",
         make_lines(recording_geometry_bytes=cancel_labeled_recording_discard_geometry)),
        ("transcribing Cancel is labeled Discard",
         make_lines(transcribing_send_geometry_bytes=discard_labeled_transcribing_cancel_geometry)),
        ("transcribing offers Insert before editable review",
         make_lines(transcribing_geometry_bytes=premature_transcribing_insert_geometry)),
        ("recording timer is stacked below its waveform",
         make_lines(recording_geometry_bytes=timer_below_waveform_geometry)),
        ("recording transcript lacks live accessible text",
         make_lines(recording_geometry_bytes=inaccessible_transcript_geometry)),
        ("recording controls are nested inside the status card",
         make_lines(recording_geometry_bytes=nested_recording_actions_geometry)),
        ("post-stop review is no longer editable",
         make_lines(review_geometry_bytes=uneditable_review_geometry)),
        ("keyboard-down Composer title row is hidden",
         make_lines(review_geometry_bytes=hidden_keyboard_down_heading_geometry)),
        ("session identity leaked into composer title", make_lines(title_state_bytes=session_leaked_title_state)),
        ("composer mic omits the visible Dictate label",
         make_lines(title_state_bytes=ambiguous_dictate_text)),
        ("composer dictation entry has an ambiguous accessible name",
         make_lines(title_state_bytes=ambiguous_dictate_accessibility)),
        ("composer mic is clipped", make_lines(title_state_bytes=clipped_dictate_prompt)),
        ("composer mic does not have 48dp bounds", make_lines(title_state_bytes=wrong_size_dictate)),
        ("composer mic is hidden", make_lines(title_state_bytes=hidden_dictate)),
        ("composer mic is disabled", make_lines(title_state_bytes=disabled_dictate)),
        ("composer More terminal keys route is hidden", make_lines(title_state_bytes=hidden_open_keys)),
        ("composer More terminal keys route is clipped", make_lines(title_state_bytes=clipped_open_keys)),
        ("idle composer title screenshot captured before the sheet was painted",
         make_lines(title_state_bytes=offscreen_title_state)),
        ("keyboard-up composer title copy is visible",
         make_lines(title_state_bytes=visible_keyboard_title_state)),
        ("keyboard-up composer draft has no horizontal inset",
         make_lines(title_state_bytes=unpadded_title_draft)),
        ("keyboard-up composer actions have no horizontal inset",
         make_lines(title_state_bytes=unpadded_title_actions)),
        ("inline dictation screenshot is ASCII run-as error text",
         make_lines(inline_preview_bytes=b"run-as: unknown package: com.pocketshell.app.i2857inline\n")),
    ):
        try:
            parse_assets("\n".join(altered), run_id, expected_terminal_marker=marker,
                         expected_dictation_marker=dictation_marker)
        except ExtractionFailure:
            print(f"PASS: {label} fails closed")
        else:
            raise AssertionError(f"{label} unexpectedly passed")
    incomplete_restart_capture = [
        line for line in lines if "composer-recording-after-restart-geometry.json" not in line
    ]
    try:
        parse_assets("\n".join(incomplete_restart_capture), run_id, expected_terminal_marker=marker,
                     expected_dictation_marker=dictation_marker)
    except ExtractionFailure:
        print("PASS: natural-restart recording screenshot requires its same-run geometry")
    else:
        raise AssertionError("natural-restart recording screenshot passed without geometry")
    focus_failure_lines = list(lines)
    for name, payload in (
        ("composer-focus-failure.png", png),
        ("composer-focus-failure.json", json.dumps({"stage": "uncertain-session-after-attach"}).encode()),
        ("composer-focus-failure-logcat.txt", b"ImeTracker: hide request did not complete\n"),
    ):
        encoded = base64.b64encode(payload).decode()
        digest = hashlib.sha256(payload).hexdigest()
        focus_failure_lines.append(f"I/PS2857Asset: BEGIN|{run_id}|{name}|1|{digest}")
        focus_failure_lines.append(f"I/PS2857Asset: DATA|{run_id}|{name}|0|{encoded}")
        focus_failure_lines.append(f"I/PS2857Asset: END|{run_id}|{name}")
    try:
        parse_assets("\n".join(focus_failure_lines), run_id, expected_terminal_marker=marker,
                     expected_dictation_marker=dictation_marker)
    except ExtractionFailure:
        print("PASS: packaged acceptance rejects retained focus-failure artifacts")
    else:
        raise AssertionError("focus failure screenshot unexpectedly passed strict packaged acceptance")
    retained_failure = parse_assets("\n".join(focus_failure_lines), run_id, validate_layout=False)
    assert retained_failure["composer-focus-failure.png"] == png
    assert b"ImeTracker" in retained_failure["composer-focus-failure-logcat.txt"]
    print("PASS: failure-mode extraction retains contemporaneous focus screenshot, state, and Android logs")
    recording_failure_lines = [
        line for line in lines
        if line.startswith("I/PS2857Asset: ") and line.split("|", 4)[2] == "composer-recording.png"
    ]
    retained_recording_failure = parse_assets("\n".join(recording_failure_lines), run_id, validate_layout=False)
    assert retained_recording_failure["composer-recording.png"] == png
    print("PASS: failure-mode extraction preserves a recording screenshot when timeout occurs before other captures")
    title_failure_lines = [
        line for line in lines
        if line.startswith("I/PS2857Asset: ") and line.split("|", 4)[2] in ("composer-title.png", "composer-title.json")
    ]
    retained_title_failure = parse_assets("\n".join(title_failure_lines), run_id, validate_layout=False)
    assert retained_title_failure["composer-title.png"] == png
    print("PASS: failure-mode extraction preserves the idle composer entry screenshot and report")
    broken_layout = make_lines(geometry_payload(ime_visible=False, app_bar_top=0))
    assert parse_assets("\n".join(broken_layout), run_id, validate_layout=False)["composer-keyboard.png"] == png
    no_geometry = [line for line in lines if "composer-keyboard-geometry.json" not in line]
    assert parse_assets("\n".join(no_geometry), run_id, validate_layout=False)["composer-keyboard.png"] == png
    print("PASS: failure-mode extraction preserves a screenshot without turning invalid geometry green")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id")
    parser.add_argument("--logcat", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--expected-terminal-marker")
    parser.add_argument("--expected-dictation-marker")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--preserve-on-failure", action="store_true",
                        help="extract complete hash-checked artifacts without accepting IME bounds")
    args = parser.parse_args()
    if args.self_test:
        try:
            self_test()
        except AssertionError as error:
            print(f"FAIL: {error}", file=sys.stderr)
            return 1
        return 0
    if not args.run_id or not args.logcat or not args.output_dir:
        parser.error("--run-id, --logcat, and --output-dir are required unless --self-test is used")
    if not args.preserve_on_failure and not args.expected_terminal_marker:
        parser.error("--expected-terminal-marker is required for accepted evidence")
    if not args.preserve_on_failure and not args.expected_dictation_marker:
        parser.error("--expected-dictation-marker is required for accepted evidence")
    try:
        names = extract(args.run_id, args.logcat, args.output_dir,
                        validate_layout=not args.preserve_on_failure,
                        expected_terminal_marker=args.expected_terminal_marker,
                        expected_dictation_marker=args.expected_dictation_marker)
    except ExtractionFailure as error:
        print(f"FAIL: composer artifact extraction: {error}", file=sys.stderr)
        return 1
    print(f"PASS: extracted {len(names)} same-run composer artifacts")
    for name in names:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
