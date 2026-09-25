#!/usr/bin/env python3
"""Rebuild and validate same-run composer screenshots and terminal evidence from Android logcat."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
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
}
OPTIONAL_NAMES = {
    "composer-recording-before-fix.png",
    "composer-mode-ime-failure.png",
    "composer-mode-ime-failure.json",
    "composer-focus-failure.png",
    "composer-focus-failure.json",
    "composer-focus-failure-logcat.txt",
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
    "composer-recording-before-fix.png",
    "composer-focus-failure.png",
    "composer-mode-ime-failure.png",
}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


class ExtractionFailure(ValueError):
    pass


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
        if abs(safe_bottom) > 0.5 or abs(shell_bottom) > 0.5:
            raise ExtractionFailure(f"keyboard layout retained bottom safe-area padding: CSS={safe_bottom}, shell={shell_bottom}")
        if status_inset <= 0 or safe_top < status_inset - 1 or shell_top < status_inset - 1 or app_bar_top < status_inset - 1:
            raise ExtractionFailure(
                f"app header overlaps the status bar: status inset={status_inset}, safe CSS={safe_top}, "
                f"shell padding={shell_top}, app bar top={app_bar_top}"
            )
        visible_rects = {"draft": draft, "status": status, "actions": actions, "terminal context": terminal}
        for name in ("discard", "insert", "send"):
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
        focus_trace_bytes = decoded.get("composer-focus-trace.json")
        try:
            focus_trace = json.loads(focus_trace_bytes)
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"composer focus trace JSON is invalid: {error}") from error
        if not isinstance(focus_trace, dict) or focus_trace.get("runId") != run_id:
            raise ExtractionFailure("composer focus trace does not identify this packaged run")
        max_attempts = focus_trace.get("maxAttempts")
        focus_attempts = focus_trace.get("attempts")
        if not isinstance(max_attempts, int) or max_attempts < 1 or max_attempts > 2:
            raise ExtractionFailure("composer focus trace does not retain the bounded tap-attempt limit")
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
        for state in ("recording", "cancel", "background", "transcribing", "review"):
            geometry_name = f"composer-{state}-geometry.json"
            try:
                mode_geometry = json.loads(decoded[geometry_name])
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ExtractionFailure(f"{geometry_name} is invalid JSON: {error}") from error
            if not isinstance(mode_geometry, dict) or mode_geometry.get("runId") != run_id:
                raise ExtractionFailure(f"{geometry_name} does not identify this run")
            if mode_geometry.get("state") != state:
                raise ExtractionFailure(f"{geometry_name} does not identify the {state} composer state")
            if mode_geometry.get("androidImeVisible") is not True or mode_geometry.get("keyboardVisible") is not True:
                raise ExtractionFailure(f"{state} screenshot does not prove the Android keyboard was visible")
            if mode_geometry.get("draftFocused") is not True:
                raise ExtractionFailure(f"{state} screenshot does not prove the composer draft retained focus")
            if mode_geometry.get("activeElementTestId") != "prompt-draft":
                raise ExtractionFailure(f"{state} screenshot does not identify the composer draft as document.activeElement")
            if mode_geometry.get("draftReadOnly") is not False:
                raise ExtractionFailure(f"{state} screenshot changed the textarea editability and could hide the IME")
            if mode_geometry.get("expectedDraftMatches") is not True:
                raise ExtractionFailure(f"{state} screenshot does not prove the expected target draft was retained")
            expected_locked = state in ("recording", "transcribing")
            if mode_geometry.get("draftEditingLocked") is not expected_locked:
                raise ExtractionFailure(f"{state} screenshot does not show the expected draft input lock")
            expected_presentation = "focus-anchor" if expected_locked else "editor"
            if mode_geometry.get("draftPresentation") != expected_presentation:
                raise ExtractionFailure(f"{state} screenshot does not show the expected visible editor presentation")
            if expected_locked:
                if (mode_geometry.get("draftOpacity") != "0" or mode_geometry.get("draftAriaHidden") is True
                        or "read only while dictating" not in str(mode_geometry.get("draftAriaLabel", ""))
                        or mode_geometry.get("draftEditingLocked") is not True
                        or "composer-status" not in str(mode_geometry.get("draftDescribedBy", ""))
                        or mode_geometry.get("recordingModeVisible") is not True
                        or not str(mode_geometry.get("recordingModeLabel", "")).strip()
                        or mode_geometry.get("composerStatusAccessible") is not True
                        or mode_geometry.get("cancelAccessible") is not True):
                    raise ExtractionFailure(f"{state} screenshot does not retain an accessible focus anchor beside the visible recording surface")
                if state == "recording":
                    if (mode_geometry.get("previewVisible") is not True
                            or mode_geometry.get("previewLive") is not True
                            or mode_geometry.get("stopAccessible") is not True
                            or "discard this dictated phrase" not in str(mode_geometry.get("previewText", ""))
                            or "composer-recording-preview" not in str(mode_geometry.get("draftDescribedBy", ""))):
                        raise ExtractionFailure("recording screenshot does not expose the live preview and accessible Stop control")
                elif mode_geometry.get("transcribingStatusAccessible") is not True:
                    raise ExtractionFailure("transcribing screenshot does not expose its accessible live status")
            elif state == "review" and mode_geometry.get("reviewVisible") is not True:
                raise ExtractionFailure("review screenshot does not show the editable review surface")
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
                required_rects.append("send")
            elif state in ("cancel", "background"):
                required_rects.append("send")
                if mode_geometry.get("recordingMode") is not None:
                    raise ExtractionFailure(f"{state} screenshot still exposes the recording surface")
                if state == "cancel" and "original draft was restored" not in str(mode_geometry.get("statusText", "")):
                    raise ExtractionFailure("cancel screenshot does not state that the original draft was restored")
            else:
                required_rects.append("recordingMode")
            if state in ("recording", "transcribing") and mode_geometry.get("send") is not None:
                raise ExtractionFailure(f"{state} screenshot exposes Send before the draft is ready for review")
            if state == "recording":
                required_rects.extend(("timer", "preview", "cancel", "stop"))
            elif state == "transcribing":
                required_rects.append("cancel")
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
                    raise ExtractionFailure(f"{rect_name} is clipped in the keyboard-up {state} screenshot")
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
                    or restart_geometry.get("androidImeVisible") is not True
                    or restart_geometry.get("keyboardVisible") is not True
                    or restart_geometry.get("draftFocused") is not True
                    or restart_geometry.get("activeElementTestId") != "prompt-draft"
                    or restart_geometry.get("expectedDraftMatches") is not True
                    or restart_geometry.get("recordingModeVisible") is not True
                    or restart_geometry.get("previewVisible") is not True
                    or restart_geometry.get("stopAccessible") is not True):
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
        "terminalViewport": {"top": 20.0, "bottom": 100.0, "left": 10.0, "right": 390.0, "height": 80.0},
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
    one_write_dictation_send_value = json.loads(dictation_send)
    one_write_dictation_send_value["acknowledgedWrites"] = 1
    one_write_dictation_send = json.dumps(one_write_dictation_send_value).encode()
    def mode_geometry_payload(state: str) -> bytes:
        rect = {"top": 10.0, "bottom": 60.0, "left": 5.0, "right": 395.0}
        recording = state.startswith("recording")
        anchored = recording or state == "transcribing"
        draft_rect = {"top": 10.0, "bottom": 11.0, "left": 5.0, "right": 6.0} if anchored else rect
        payload = {
            "runId": run_id,
            "state": state,
            "dictationState": "recording" if recording else "transcribing" if state == "transcribing"
            else "review" if state == "review" else "idle",
            "androidImeVisible": True,
            "keyboardVisible": True,
            "draftFocused": True,
            "activeElementTestId": "prompt-draft",
            "draftReadOnly": False,
            "draftEditingLocked": anchored,
            "expectedDraftMatches": True,
            "statusText": "Dictation cancelled. Your original draft was restored." if state == "cancel" else "",
            "draftPresentation": "focus-anchor" if anchored else "editor",
            "draftOpacity": "0" if anchored else "1",
            "draftAriaHidden": False,
            "draftAriaLabel": "Dictation draft, read only while dictating" if anchored else "Prompt draft",
            "draftDescribedBy": "composer-recording-preview composer-status" if recording else "composer-status" if state == "transcribing" else "",
            "recordingModeVisible": anchored,
            "recordingModeLabel": "Recording prompt" if anchored else "",
            "previewVisible": recording,
            "previewLive": recording,
            "previewText": "discard this dictated phrase" if recording else "",
            "cancelAccessible": anchored,
            "stopAccessible": recording,
            "transcribingStatusAccessible": state == "transcribing",
            "composerStatusAccessible": True,
            "reviewVisible": state == "review",
            "visualViewport": {"height": 240.0, "width": 400.0},
            "draft": draft_rect,
            "status": rect,
            "actions": rect,
        }
        if anchored:
            payload["recordingMode"] = rect
            payload["cancel"] = rect
        if state in ("cancel", "background"):
            payload["send"] = rect
        if recording:
            payload.update({"timer": rect, "preview": rect, "stop": rect})
        if state == "review":
            payload["review"] = rect
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
    clipped_post_send_value = json.loads(post_send)
    clipped_post_send_value["terminalViewport"]["top"] = 220.0
    clipped_post_send_value["terminalViewport"]["bottom"] = 300.0
    clipped_post_send = json.dumps(clipped_post_send_value).encode()
    keyboard_up_post_send_value = json.loads(post_send)
    keyboard_up_post_send_value["keyboardVisible"] = True
    keyboard_up_post_send = json.dumps(keyboard_up_post_send_value).encode()
    negative_latency_value = json.loads(post_send)
    negative_latency_value["sendToVisibleOutputLatencyMs"] = -1
    negative_latency_post_send = json.dumps(negative_latency_value).encode()
    missing_latency_value = json.loads(post_send)
    del missing_latency_value["sendToVisibleOutputLatencyMs"]
    missing_latency_post_send = json.dumps(missing_latency_value).encode()
    def geometry_payload(*, ime_visible: bool = True, app_bar_top: float = 24.0,
                         send_bottom: float = 218.0, terminal_height: float = 60.0) -> bytes:
        return json.dumps({
            "androidImeVisible": ime_visible,
            "visualViewport": {"height": 240.0, "width": 400.0},
            "terminalViewport": {"top": 30.0, "bottom": 30.0 + terminal_height, "left": 1.0, "right": 399.0, "height": terminal_height},
            "appBar": {"top": app_bar_top},
            "draft": {"top": 100.0, "bottom": 150.0, "left": 1.0, "right": 399.0},
            "status": {"top": 152.0, "bottom": 166.0, "left": 1.0, "right": 399.0},
            "actions": {"top": 168.0, "bottom": 219.0, "left": 1.0, "right": 399.0},
            "buttons": {
                "discard": {"top": 170.0, "bottom": 218.0, "left": 1.0, "right": 70.0},
                "insert": {"top": 170.0, "bottom": 218.0, "left": 250.0, "right": 310.0},
                "send": {"top": 170.0, "bottom": send_bottom, "left": 320.0, "right": 398.0},
            },
            "safeArea": {"topCss": 24.0, "bottomCss": 0.0, "shellTopPadding": 24.0, "shellBottomPadding": 0.0, "keyboardVisible": True},
            "nativeInsets": {"statusBarTopDp": 24.0, "imeBottomDp": 300.0},
        }).encode()

    geometry = geometry_payload()

    def make_lines(geometry_bytes: bytes = geometry, post_send_bytes: bytes = post_send,
                   dictation_send_bytes: bytes = dictation_send,
                   inline_preview_bytes: bytes = png) -> list[str]:
        source = [
            ("composer-keyboard.png", png),
            ("composer-keyboard-geometry.json", geometry_bytes),
            ("composer-post-send.png", png),
            ("composer-post-send-terminal.json", post_send_bytes),
            ("inline-dictation-preview.png", inline_preview_bytes),
            ("composer-focus-trace.json", focus_trace),
            ("composer-recording.png", png),
            ("composer-recording-geometry.json", mode_geometry_payload("recording")),
            ("composer-cancel.png", png),
            ("composer-cancel-geometry.json", mode_geometry_payload("cancel")),
            ("composer-background.png", png),
            ("composer-background-geometry.json", mode_geometry_payload("background")),
            ("composer-transcribing.png", png),
            ("composer-transcribing-geometry.json", mode_geometry_payload("transcribing")),
            ("composer-review.png", png),
            ("composer-review-geometry.json", mode_geometry_payload("review")),
            ("composer-recording-after-restart.png", png),
            ("composer-recording-after-restart-geometry.json", mode_geometry_payload("recording-after-restart")),
            ("composer-dictation-send.json", dictation_send_bytes),
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

    for label, altered in (
        ("missing artifact", lines[:-1]),
        ("missing focus trace", [line for line in lines if "composer-focus-trace.json" not in line]),
        ("missing chunk", [line for line in lines if "DATA|" not in line or "|0|" not in line]),
        ("bad digest", [line.replace(hashlib.sha256(png).hexdigest(), "0" * 64) for line in lines]),
        ("IME hidden", make_lines(geometry_payload(ime_visible=False))),
        ("send button clipped by viewport", make_lines(geometry_payload(send_bottom=241))),
        ("send touch target below 48dp", make_lines(geometry_payload(send_bottom=214))),
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
