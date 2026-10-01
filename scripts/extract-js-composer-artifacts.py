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
import struct
import sys
import zlib
from pathlib import Path


TAG = "PS2857Asset:"
REQUIRED_NAMES = {
    "composer-keyboard.png",
    "composer-keyboard-geometry.json",
    "composer-post-send.png",
    "composer-post-send-terminal.json",
    "composer-focus-trace.json",
    "snippet-keyboard-down.png",
    "snippet-keyboard-down-geometry.json",
    "snippet-selected-chip.png",
    "snippet-selected-chip-geometry.json",
    "snippet-restart-evidence.json",
}
OPTIONAL_NAMES = {
    "composer-focus-failure.png",
    "composer-focus-failure.json",
    "composer-focus-failure-logcat.txt",
}
EXPECTED_NAMES = REQUIRED_NAMES | OPTIONAL_NAMES
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


class ExtractionFailure(ValueError):
    pass


def _png_dimensions(payload: bytes, name: str) -> tuple[int, int]:
    if (len(payload) < 24 or payload[:8] != b"\x89PNG\r\n\x1a\n"
            or payload[12:16] != b"IHDR"):
        raise ExtractionFailure(f"{name} has no valid PNG header")
    width, height = struct.unpack(">II", payload[16:24])
    if width <= 0 or height <= 0:
        raise ExtractionFailure(f"{name} has invalid PNG dimensions")
    return width, height


def _validate_snippet_screenshot_evidence(decoded: dict[str, bytes], name: str,
                                          geometry: dict[str, object], *, selected: bool) -> None:
    image_name = "snippet-selected-chip.png" if selected else "snippet-keyboard-down.png"
    png = decoded.get(image_name)
    evidence = geometry.get("screenshotCapture")
    if png is None or not isinstance(evidence, dict):
        raise ExtractionFailure(f"{name} is missing same-run screenshot capture evidence")
    baseline_png = decoded.get("snippet-keyboard-down.png") if selected else None
    if selected and (baseline_png is None or baseline_png == png):
        raise ExtractionFailure("snippet selected-chip PNG is byte-identical to the keyboard-down baseline")
    if (evidence.get("captureMethod") != "UiAutomation.takeScreenshot"
            or evidence.get("captureThread") != "instrumentation"
            or evidence.get("visualStateCallbackCompleted") is not True):
        raise ExtractionFailure(f"{name} does not prove an off-UI-thread capture after the WebView visual-state callback")
    width, height = _png_dimensions(png, image_name)
    if (evidence.get("pixelWidth") != width or evidence.get("pixelHeight") != height
            or evidence.get("screenshotSha256") != hashlib.sha256(png).hexdigest()):
        raise ExtractionFailure(f"{name} screenshot dimensions or digest do not match its captured PNG")
    attempt = evidence.get("attempt")
    if isinstance(attempt, bool) or not isinstance(attempt, int) or attempt < 1 or attempt > 4:
        raise ExtractionFailure(f"{name} screenshot capture does not retain its bounded attempt number")
    frame = evidence.get("webViewFrameOnScreen")
    css_viewport = evidence.get("cssViewport")
    if not isinstance(frame, dict) or not isinstance(css_viewport, dict):
        raise ExtractionFailure(f"{name} screenshot capture is missing its DOM-to-pixel coordinate map")
    try:
        frame_values = [int(frame[key]) for key in ("left", "top", "width", "height")]
        css_values = [float(css_viewport[key]) for key in ("width", "height", "offsetLeft", "offsetTop")]
        if (frame_values[2] <= 0 or frame_values[3] <= 0
                or frame_values[0] < 0 or frame_values[1] < 0
                or frame_values[0] + frame_values[2] > width
                or frame_values[1] + frame_values[3] > height
                or not all(math.isfinite(value) for value in css_values)
                or css_values[0] <= 0 or css_values[1] <= 0):
            raise ValueError("invalid viewport mapping")
    except (KeyError, TypeError, ValueError) as error:
        raise ExtractionFailure(f"{name} screenshot capture has invalid DOM-to-pixel bounds") from error
    if selected:
        assert baseline_png is not None
        if evidence.get("baselinePngSha256") != hashlib.sha256(baseline_png).hexdigest():
            raise ExtractionFailure(f"{name} selected screenshot does not identify its exact keyboard-down baseline")
        if evidence.get("selectedVisualStateVerified") is not True:
            raise ExtractionFailure(f"{name} does not prove selected-state screenshot pixels were rendered")
        for region_name, minimum in (("draftRegion", 256), ("selectedChipRegion", 256)):
            region = evidence.get(region_name)
            if not isinstance(region, dict):
                raise ExtractionFailure(f"{name} is missing rendered pixel evidence for {region_name}")
            changed = region.get("changedPixels")
            compared = region.get("comparedPixels")
            threshold = region.get("minimumChangedPixels")
            before_rect = region.get("baselineRect")
            after_rect = region.get("selectedRect")
            if (isinstance(changed, bool) or not isinstance(changed, int)
                    or isinstance(compared, bool) or not isinstance(compared, int)
                    or isinstance(threshold, bool) or not isinstance(threshold, int)
                    or threshold < minimum or changed < threshold or compared < changed
                    or not isinstance(before_rect, dict) or not isinstance(after_rect, dict)):
                raise ExtractionFailure(f"{name} {region_name} pixels do not prove a visible selected state")
            try:
                before = [int(before_rect[key]) for key in ("left", "top", "right", "bottom")]
                after = [int(after_rect[key]) for key in ("left", "top", "right", "bottom")]
                before_width, before_height = before[2] - before[0], before[3] - before[1]
                after_width, after_height = after[2] - after[0], after[3] - after[1]
                region_pixels = min(before_width, after_width) * min(before_height, after_height)
                if (before_width <= 0 or before_height <= 0 or after_width <= 0 or after_height <= 0
                        or before[0] < 0 or before[1] < 0 or after[0] < 0 or after[1] < 0
                        or before[2] > width or after[2] > width or before[3] > height or after[3] > height
                        or any(abs(before[index] - after[index]) > 1 for index in range(4))
                        or compared != region_pixels):
                    raise ValueError("unaligned screenshot crop")
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"{name} {region_name} screenshot crop bounds are invalid") from error
    elif evidence.get("selectedVisualStateVerified") is not False:
        raise ExtractionFailure(f"{name} baseline screenshot is incorrectly marked as the selected state")


def _validate_snippet_restart_evidence(payload: bytes | None) -> None:
    if payload is None:
        raise ExtractionFailure("snippet-restart-evidence.json is missing")
    try:
        evidence = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExtractionFailure(f"snippet-restart-evidence.json is invalid JSON: {error}") from error
    if not isinstance(evidence, dict):
        raise ExtractionFailure("snippet restart evidence must be a JSON object")
    package_name = evidence.get("appPackage")
    old_pid = evidence.get("oldPid")
    stopped_pid = evidence.get("stoppedPid")
    new_pid = evidence.get("newPid")
    launchable_activity = evidence.get("launchableActivity")
    host_id = evidence.get("hostId")
    if not isinstance(package_name, str) or not re.fullmatch(r"[A-Za-z0-9_.]+", package_name):
        raise ExtractionFailure("restart evidence has an invalid package name")
    if not isinstance(old_pid, str) or not old_pid.isdecimal() or not isinstance(new_pid, str) or not new_pid.isdecimal():
        raise ExtractionFailure("restart evidence is missing numeric old and new process IDs")
    if old_pid == new_pid or stopped_pid != "":
        raise ExtractionFailure("restart evidence does not prove the old app process stopped before relaunch")
    if not isinstance(launchable_activity, str) or not launchable_activity.startswith(f"{package_name}/"):
        raise ExtractionFailure("restart evidence is missing the target package's resolved launchable activity")
    if not isinstance(host_id, str) or not host_id:
        raise ExtractionFailure("restart evidence is missing the reconnected host identity")
    if evidence.get("mainSnippetExact") is not True or evidence.get("uncertainSnippetExact") is not True:
        raise ExtractionFailure("restart evidence does not prove both exact snippet records survived")
    stored_count = evidence.get("storedSnippetCount")
    if not isinstance(stored_count, int) or isinstance(stored_count, bool) or stored_count < 2:
        raise ExtractionFailure("restart evidence contains fewer than two stored host snippets")
    if not isinstance(evidence.get("mainLabel"), str) or not evidence["mainLabel"]:
        raise ExtractionFailure("restart evidence is missing the main snippet label")
    if not isinstance(evidence.get("uncertainLabel"), str) or not evidence["uncertainLabel"]:
        raise ExtractionFailure("restart evidence is missing the reconnect snippet label")
    if evidence.get("storageKey") != "pocketshell.js.host-snippets.v1":
        raise ExtractionFailure("restart evidence does not identify the durable JS snippet storage key")


def _validate_snippet_geometry(decoded: dict[str, bytes], name: str, *, selected: bool) -> None:
    payload = decoded.get(name)
    if payload is None:
        raise ExtractionFailure(f"{name} is missing")
    try:
        geometry = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExtractionFailure(f"{name} is invalid JSON: {error}") from error
    if not isinstance(geometry, dict):
        raise ExtractionFailure(f"{name} must be a JSON object")
    if geometry.get("keyboardVisible") is not False or geometry.get("imeVisible") is not False:
        raise ExtractionFailure(f"{name} does not prove the keyboard was down")
    if geometry.get("rowVisible") is not True:
        raise ExtractionFailure(f"{name} does not prove the chip rail is visible")
    if geometry.get("composerPresent") is not True or geometry.get("terminalPresent") is not True:
        raise ExtractionFailure(f"{name} does not prove the composer and terminal are mounted")
    viewport = geometry.get("viewport")
    row = geometry.get("chipRow")
    composer = geometry.get("composer")
    terminal = geometry.get("terminal")
    chips = geometry.get("chips")
    target = geometry.get("target")
    if not all(isinstance(value, dict) for value in (viewport, row, composer, terminal, target)) or not isinstance(chips, list):
        raise ExtractionFailure(f"{name} is missing viewport, chip rail, terminal, composer, or chip bounds")
    try:
        width = float(viewport["width"])
        height = float(viewport["height"])
        if not math.isfinite(width) or not math.isfinite(height) or width <= 0 or height <= 0:
            raise ValueError("non-positive viewport")
        def read_rect(rect: dict[str, object], context: str) -> tuple[float, float, float, float, float, float]:
            left = float(rect["left"])
            right = float(rect["right"])
            top = float(rect["top"])
            bottom = float(rect["bottom"])
            rect_width = float(rect["width"])
            rect_height = float(rect["height"])
            if not all(math.isfinite(value) for value in (left, right, top, bottom, rect_width, rect_height)):
                raise ValueError(f"non-finite {context} bounds")
            if left < 0 or top < 0 or right > width + 0.5 or bottom > height + 0.5:
                raise ExtractionFailure(f"{name} {context} is outside the visible viewport")
            if rect_width < 47.9 or rect_height < 47.9:
                raise ExtractionFailure(f"{name} {context} is below the 48dp minimum target")
            return left, right, top, bottom, rect_width, rect_height
        def inside(inner: tuple[float, float, float, float, float, float],
                   outer: tuple[float, float, float, float, float, float]) -> bool:
            inner_left, inner_right, inner_top, inner_bottom, _, _ = inner
            outer_left, outer_right, outer_top, outer_bottom, _, _ = outer
            return (inner_left >= outer_left - 0.5 and inner_right <= outer_right + 0.5
                    and inner_top >= outer_top - 0.5 and inner_bottom <= outer_bottom + 0.5)
        composer_bounds = read_rect(composer, "composer")
        terminal_bounds = read_rect(terminal, "terminal viewport")
        row_bounds = read_rect(row, "chip rail")
        if not inside(row_bounds, composer_bounds):
            raise ExtractionFailure(f"{name} chip rail is outside the composer")
        composer_top = composer_bounds[2]
        terminal_top = terminal_bounds[2]
        terminal_bottom = terminal_bounds[3]
        visible_terminal_above_composer = min(terminal_bottom, composer_top, height) - max(terminal_top, 0.0)
        if visible_terminal_above_composer < 48:
            raise ExtractionFailure(f"{name} preserves less than 48dp of terminal content above the composer")
        if int(geometry.get("screenScrollTop", -1)) != 0 or int(geometry.get("documentScrollTop", -1)) != 0:
            raise ExtractionFailure(f"{name} requires page scrolling to see the chips")
        if not chips:
            raise ExtractionFailure(f"{name} has no visible saved command chip")
        for index, chip in enumerate(chips):
            if not isinstance(chip, dict) or chip.get("tag") != "BUTTON":
                raise ExtractionFailure(f"{name} chip {index} is not a button")
            label = chip.get("label")
            if not isinstance(label, str) or not label.startswith("Insert ") or len(label) <= len("Insert "):
                raise ExtractionFailure(f"{name} chip {index} has no accessible insert label")
            chip_bounds = read_rect(chip, f"chip {index}")
            if not inside(chip_bounds, row_bounds):
                raise ExtractionFailure(f"{name} chip {index} is outside the chip rail")
            if not inside(chip_bounds, composer_bounds):
                raise ExtractionFailure(f"{name} chip {index} is outside the composer")
        if target.get("tag") != "BUTTON" or not isinstance(target.get("label"), str) or not target["label"].startswith("Insert "):
            raise ExtractionFailure(f"{name} target is missing its accessible button label")
        target_bounds = read_rect(target, "target chip")
        if not inside(target_bounds, row_bounds):
            raise ExtractionFailure(f"{name} target chip is outside the chip rail")
        if not inside(target_bounds, composer_bounds):
            raise ExtractionFailure(f"{name} target chip is outside the composer")
        expected_label = geometry.get("expectedLabel")
        if target.get("label") != expected_label:
            raise ExtractionFailure(f"{name} target does not match the expected snippet chip")
        if selected:
            if target.get("current") != "true" or geometry.get("draftMatchesExact") is not True:
                raise ExtractionFailure(f"{name} does not prove literal selected-chip insertion")
            if int(geometry.get("composerWriteCount", -1)) != 0 or int(geometry.get("acknowledgedWrites", -1)) != 0:
                raise ExtractionFailure(f"{name} shows a PTY write before explicit Send")
        elif target.get("current") not in ("", None):
            raise ExtractionFailure(f"{name} target was selected before the selected-chip capture")
    except (KeyError, TypeError, ValueError) as error:
        if isinstance(error, ExtractionFailure):
            raise
        raise ExtractionFailure(f"{name} has invalid viewport or chip bounds") from error
    _validate_snippet_screenshot_evidence(decoded, name, geometry, selected=selected)


def _validate_terminal_grid_fidelity(fidelity: object, stage: str, *, require_full_height: bool) -> int:
    """Reject a terminal whose rendered rows do not fit the grid SSH accepted.

    A row wider than the xterm screen is clipped at the right edge and loses
    characters at every wrap while its DOM text and buffer text stay intact,
    so only the rendered extent proves the terminal is readable (#2932).
    """
    if not isinstance(fidelity, dict):
        raise ExtractionFailure(f"{stage} terminal grid fidelity record is missing")
    try:
        cols = fidelity["cols"]
        rows = fidelity["rows"]
        if isinstance(cols, bool) or not isinstance(cols, int) or cols <= 0:
            raise ValueError("cols")
        if fidelity.get("acceptedCols") != cols or fidelity.get("acceptedRows") != rows:
            raise ExtractionFailure(
                f"{stage} xterm grid {cols}x{rows} is not the grid SSH accepted: {fidelity.get('resizeStatus')!r}")
        clip = fidelity["clip"]
        screen = fidelity["screen"]
        if float(screen["left"]) < float(clip["left"]) - 0.5 or float(screen["right"]) > float(clip["right"]) + 0.5:
            raise ExtractionFailure(f"{stage} xterm screen extends past the visible terminal viewport width")
        # Keyboard-up keeps #2884's 38x6 PTY in a 144px cap with five fully
        # visible rows (Fast Keys lane); only keyboard-down must fit fully.
        if require_full_height and (float(screen["top"]) < float(clip["top"]) - 0.5
                                    or float(screen["bottom"]) > float(clip["bottom"]) + 0.5):
            raise ExtractionFailure(f"{stage} xterm screen extends past the visible terminal viewport height")
        overflowing = fidelity["overflowingRows"]
        if not isinstance(overflowing, list) or overflowing:
            raise ExtractionFailure(f"{stage} rendered terminal rows are clipped by the xterm screen: {overflowing}")
        max_cells = float(fidelity["maxRowCells"])
        max_right = fidelity["maxRowRight"]
        if max_cells > cols + 0.05 or max_right is None or float(max_right) > float(screen["right"]) + 0.5:
            raise ExtractionFailure(f"{stage} a rendered row is wider than the accepted {cols} columns")
        wrapped = fidelity["wrappedCommandRows"]
        if not isinstance(wrapped, list) or len(wrapped) < 2:
            raise ExtractionFailure(f"{stage} did not measure a wrapped long command row")
        for row in wrapped[:-1]:
            cells = float(row["cells"])
            if cells < cols - 1 - 0.05 or cells > cols + 0.05:
                raise ExtractionFailure(f"{stage} wrapped command row does not fill the accepted {cols} columns: {row}")
    except ExtractionFailure:
        raise
    except (KeyError, TypeError, ValueError) as error:
        raise ExtractionFailure(f"{stage} terminal grid fidelity record is invalid: {error}") from error
    return cols


def parse_assets(log_text: str, run_id: str, *, validate_layout: bool = True,
                 expected_terminal_marker: str | None = None) -> dict[str, bytes]:
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

    required_names = REQUIRED_NAMES if validate_layout else {"composer-keyboard.png"}
    if not required_names.issubset(assets) or set(assets) - EXPECTED_NAMES:
        raise ExtractionFailure(f"expected at least {sorted(required_names)} without extras, found {sorted(assets)}")

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

    for name in ("composer-keyboard.png", "composer-post-send.png", "snippet-keyboard-down.png", "snippet-selected-chip.png"):
        screenshot = decoded.get(name)
        if screenshot is not None and (not screenshot.startswith(b"\x89PNG\r\n\x1a\n") or len(screenshot) < 1024):
            raise ExtractionFailure(f"{name} is not a non-empty PNG")
    screenshot = decoded["composer-keyboard.png"]
    if not screenshot.startswith(b"\x89PNG\r\n\x1a\n") or len(screenshot) < 1024:
        raise ExtractionFailure("keyboard screenshot is not a non-empty PNG")
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
        keyboard_cols = _validate_terminal_grid_fidelity(
            geometry.get("terminalGridFidelity"), "keyboard-up", require_full_height=False)

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
        send_to_visible_latency = post_send.get("sendToVisibleOutputLatencyMs")
        if (not isinstance(send_to_visible_latency, int) or isinstance(send_to_visible_latency, bool)
                or not 0 <= send_to_visible_latency <= 5_000):
            raise ExtractionFailure("Send-to-visible-output latency must be an integer from 0 through 5000 ms")
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
        if (post_send.get("composerInline") is not True
                or post_send.get("composerModal") is not False
                or post_send.get("composerScrimVisible") is not False
                or post_send.get("promptComposerOpen") is not False):
            raise ExtractionFailure("post-send composer is still modal or has a visible backdrop")
        terminal_rect = post_send.get("terminalViewport")
        post_viewport = post_send.get("visualViewport")
        if not isinstance(post_viewport, dict):
            raise ExtractionFailure("post-send terminal record is missing viewport bounds")
        try:
            post_height = float(post_viewport["height"])
            post_width = float(post_viewport["width"])
        except (KeyError, TypeError, ValueError) as error:
            raise ExtractionFailure("post-send terminal viewport dimensions are invalid") from error
        if not math.isfinite(post_width) or not math.isfinite(post_height) or post_width <= 0 or post_height <= 0:
            raise ExtractionFailure("post-send terminal viewport dimensions are invalid")

        def post_rect(name: str, *, minimum_height: float = 0.0) -> tuple[float, float, float, float]:
            value = post_send.get(name)
            if not isinstance(value, dict):
                raise ExtractionFailure(f"post-send terminal record is missing {name} bounds")
            try:
                left = float(value["left"])
                right = float(value["right"])
                top = float(value["top"])
                bottom = float(value["bottom"])
            except (KeyError, TypeError, ValueError) as error:
                raise ExtractionFailure(f"post-send {name} bounds are invalid") from error
            if not all(math.isfinite(number) for number in (left, right, top, bottom)):
                raise ExtractionFailure(f"post-send {name} bounds are invalid")
            if (left < 0 or top < 0 or right > post_width + 0.5 or bottom > post_height + 0.5
                    or right <= left or bottom <= top or bottom - top < minimum_height):
                raise ExtractionFailure(f"post-send {name} is clipped or too small")
            return left, right, top, bottom

        def contained(inner: tuple[float, float, float, float],
                      outer: tuple[float, float, float, float]) -> bool:
            return (inner[0] >= outer[0] - 0.5 and inner[1] <= outer[1] + 0.5
                    and inner[2] >= outer[2] - 0.5 and inner[3] <= outer[3] + 0.5)

        terminal_bounds = post_rect("terminalViewport", minimum_height=48)
        screen_bounds = post_rect("terminalScreen")
        app_bar_bounds = post_rect("appBar")
        composer_bounds = post_rect("composer")
        marker_row_bounds = post_rect("markerRow")
        byte_output_bounds = post_rect("byteOutputRow")
        if not contained(screen_bounds, terminal_bounds):
            raise ExtractionFailure("post-send xterm screen is outside the terminal viewport")
        if app_bar_bounds[3] > terminal_bounds[2] + 0.5:
            raise ExtractionFailure("post-send app bar overlaps the terminal viewport")
        if terminal_bounds[3] > composer_bounds[2] + 0.5:
            raise ExtractionFailure("post-send inline composer overlaps the terminal viewport")
        if not contained(marker_row_bounds, terminal_bounds) or not contained(marker_row_bounds, screen_bounds):
            raise ExtractionFailure("post-send marker row is clipped or obscured outside the visible terminal screen")
        if not contained(byte_output_bounds, terminal_bounds) or not contained(byte_output_bounds, screen_bounds):
            raise ExtractionFailure("post-send byte-output row is clipped or obscured outside the visible terminal screen")
        composer_top = composer_bounds[2]
        if marker_row_bounds[3] > composer_top + 0.5 or byte_output_bounds[3] > composer_top + 0.5:
            raise ExtractionFailure("post-send terminal output rows are obscured by the composer")
        if byte_output_bounds[3] > marker_row_bounds[2] + 0.5:
            raise ExtractionFailure("post-send byte-output row does not precede the marker row")
        if post_send.get("terminalOutputRowsAboveComposer") is not True or post_send.get("terminalOutputRowVisible") is not True:
            raise ExtractionFailure("post-send terminal output rows are not proven visible above the composer")
        if post_send.get("keyboardVisible") is not False or post_send.get("nativeImeVisible") is not False:
            raise ExtractionFailure("post-send terminal screenshot was not captured with the keyboard and Android IME hidden")
        if (post_send.get("capturedBeforeScroll") is not True
                or isinstance(post_send.get("screenScrollTop"), bool) or post_send.get("screenScrollTop") != 0
                or isinstance(post_send.get("documentScrollTop"), bool) or post_send.get("documentScrollTop") != 0):
            raise ExtractionFailure("post-send terminal evidence required page scrolling")
        post_send_cols = _validate_terminal_grid_fidelity(
            post_send.get("terminalGridFidelity"), "post-send", require_full_height=True)
        if post_send.get("keyboardUpGridCols") != keyboard_cols or post_send_cols != keyboard_cols:
            raise ExtractionFailure(
                f"Send changed the xterm column count from {keyboard_cols} to {post_send_cols}")

        _validate_snippet_geometry(decoded, "snippet-keyboard-down-geometry.json", selected=False)
        _validate_snippet_geometry(decoded, "snippet-selected-chip-geometry.json", selected=True)
        _validate_snippet_restart_evidence(decoded.get("snippet-restart-evidence.json"))
        for name in ("snippet-keyboard-down.png", "snippet-selected-chip.png"):
            screenshot = decoded.get(name)
            if screenshot is None or not screenshot.startswith(b"\x89PNG\r\n\x1a\n") or len(screenshot) < 1024:
                raise ExtractionFailure(f"{name} is not a non-empty PNG")
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
            if isinstance(attempt, dict) and attempt.get("stage") == "uncertain-session-after-attach"
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
    return decoded


def extract(run_id: str, logcat: Path, output: Path, *, validate_layout: bool = True,
            expected_terminal_marker: str | None = None) -> list[str]:
    try:
        log_text = logcat.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise ExtractionFailure(f"could not read Android logcat {logcat}: {error}") from error
    artifacts = parse_assets(log_text, run_id, validate_layout=validate_layout,
                             expected_terminal_marker=expected_terminal_marker)
    output.mkdir(parents=True, exist_ok=True)
    for name, payload in artifacts.items():
        (output / name).write_bytes(payload)
    return sorted(artifacts)


def self_test() -> None:
    run_id = "js2857-self-test"
    marker = "PS2857_SENT_js2857-self-test"

    def png_fixture(color: tuple[int, int, int]) -> bytes:
        width, height = 1080, 2400
        row = b"\x00" + bytes(color) * width
        raw = row * height

        def chunk(kind: bytes, payload: bytes) -> bytes:
            return (struct.pack(">I", len(payload)) + kind + payload
                    + struct.pack(">I", binascii.crc32(kind + payload) & 0xffffffff))

        header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
        return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))

    def grid_fidelity(*, cols: int = 38, accepted_cols: int = 38, row_cells: float = 38.0,
                      screen_right: float = 383.6, clip_right: float = 391.2) -> dict[str, object]:
        cell = 9.62
        left = 18.0
        rows = [
            {"index": 0, "text": "$ printf '%s' 'cafe' | od", "cells": row_cells, "right": left + row_cells * cell},
            {"index": 1, "text": "| tr -d '[:space:]'", "cells": cols, "right": left + cols * cell},
            {"index": 2, "text": "s-sent-output.marker", "cells": 20.0, "right": left + 20 * cell},
        ]
        overflowing = [row for row in rows if row["right"] > screen_right + 0.5]
        return {
            "cols": cols, "rows": 9, "bufferType": "alternate", "cellWidth": cell,
            "viewport": {"top": 170.0, "bottom": 404.0, "left": 13.0, "right": 397.2},
            "clip": {"top": 170.0, "bottom": 404.0, "left": 14.0, "right": clip_right},
            "screen": {"top": 178.0, "bottom": 390.0, "left": left, "right": screen_right},
            "domRowCount": 9,
            "maxRowRight": max(row["right"] for row in rows),
            "maxRowCells": max(row["cells"] for row in rows),
            "overflowingRows": overflowing,
            "wrappedCommandRows": rows,
            "resizeStatus": f"{accepted_cols} \u00d7 9 accepted by SSH",
            "acceptedCols": accepted_cols, "acceptedRows": 9,
        }

    png = png_fixture((24, 32, 40))
    selected_png = png_fixture((90, 48, 24))
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
        "terminalViewport": {"top": 172.21, "bottom": 464.63, "left": 0.0, "right": 412.19, "height": 292.42},
        "terminalScreen": {"top": 181.0, "bottom": 462.74, "left": 0.0, "right": 412.19},
        "appBar": {"top": 51.0, "bottom": 103.76, "left": 0.0, "right": 412.19},
        "composer": {"top": 507.33, "bottom": 859.05, "left": 10.0, "right": 402.19},
        "composerInline": True,
        "composerModal": False,
        "composerScrimVisible": False,
        "promptComposerOpen": False,
        "markerRow": {"top": 370.0, "bottom": 393.0, "left": 12.0, "right": 390.0},
        "byteOutputRow": {"top": 346.0, "bottom": 369.0, "left": 12.0, "right": 390.0},
        "terminalScroller": {"scrollTop": 0, "scrollHeight": 604, "clientHeight": 604},
        "terminalOutputRowsAboveComposer": True,
        "terminalOutputRowVisible": True,
        "visualViewport": {"height": 915.05, "width": 412.19},
        "capturedBeforeScroll": True,
        "screenScrollTop": 0,
        "documentScrollTop": 0,
        "keyboardVisible": False,
        "nativeImeVisible": False,
        "deliveryStatus": "Sent to the terminal.",
        "keyboardUpGridCols": 38,
        "terminalGridFidelity": grid_fidelity(),
    }).encode()
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
    clipped_post_send_value["terminalViewport"]["bottom"] = 1000.0
    clipped_post_send = json.dumps(clipped_post_send_value).encode()
    keyboard_up_post_send_value = json.loads(post_send)
    keyboard_up_post_send_value["keyboardVisible"] = True
    keyboard_up_post_send = json.dumps(keyboard_up_post_send_value).encode()
    native_ime_up_post_send_value = json.loads(post_send)
    native_ime_up_post_send_value["nativeImeVisible"] = True
    native_ime_up_post_send = json.dumps(native_ime_up_post_send_value).encode()
    scrolled_post_send_value = json.loads(post_send)
    scrolled_post_send_value["capturedBeforeScroll"] = False
    scrolled_post_send_value["screenScrollTop"] = 1
    scrolled_post_send = json.dumps(scrolled_post_send_value).encode()
    obscured_output_post_send_value = json.loads(post_send)
    obscured_output_post_send_value["composer"]["top"] = 350.0
    obscured_output_post_send_value["terminalOutputRowsAboveComposer"] = False
    obscured_output_post_send = json.dumps(obscured_output_post_send_value).encode()
    modal_post_send_value = json.loads(post_send)
    modal_post_send_value["composerInline"] = False
    modal_post_send_value["composerModal"] = True
    modal_post_send_value["composerScrimVisible"] = True
    modal_post_send_value["promptComposerOpen"] = True
    modal_post_send = json.dumps(modal_post_send_value).encode()
    backdrop_post_send_value = json.loads(post_send)
    backdrop_post_send_value["composerScrimVisible"] = True
    backdrop_post_send = json.dumps(backdrop_post_send_value).encode()
    clipped_composer_post_send_value = json.loads(post_send)
    clipped_composer_post_send_value["composer"]["bottom"] = 930.0
    clipped_composer_post_send = json.dumps(clipped_composer_post_send_value).encode()
    clipped_output_post_send_value = json.loads(post_send)
    clipped_output_post_send_value["byteOutputRow"]["bottom"] = 780.0
    clipped_output_post_send_value["byteOutputRow"]["top"] = 770.0
    clipped_output_post_send = json.dumps(clipped_output_post_send_value).encode()
    negative_latency_value = json.loads(post_send)
    negative_latency_value["sendToVisibleOutputLatencyMs"] = -1
    negative_latency_post_send = json.dumps(negative_latency_value).encode()
    invalid_latency_value = json.loads(post_send)
    invalid_latency_value["sendToVisibleOutputLatencyMs"] = 5001
    invalid_latency_post_send = json.dumps(invalid_latency_value).encode()
    missing_latency_value = json.loads(post_send)
    del missing_latency_value["sendToVisibleOutputLatencyMs"]
    missing_latency_post_send = json.dumps(missing_latency_value).encode()

    def post_send_with(**changes: object) -> bytes:
        value = json.loads(post_send)
        value.update(changes)
        return json.dumps(value).encode()

    # #2932: the reviewer's clipped run rendered 37-cell rows in a 35-column
    # screen after Send resized the grid; each case must fail closed.
    clipped_rows_post_send = post_send_with(
        keyboardUpGridCols=37, terminalGridFidelity=grid_fidelity(cols=35, accepted_cols=35, row_cells=37.0, screen_right=354.7))
    resized_by_send_post_send = post_send_with(
        keyboardUpGridCols=38, terminalGridFidelity=grid_fidelity(cols=35, accepted_cols=35, row_cells=35.0, screen_right=354.7))
    unaccepted_grid_post_send = post_send_with(terminalGridFidelity=grid_fidelity(accepted_cols=37))
    screen_past_viewport_post_send = post_send_with(terminalGridFidelity=grid_fidelity(clip_right=380.0))
    short_viewport_value = grid_fidelity()
    short_viewport_value["clip"]["bottom"] = 386.0
    screen_below_viewport_post_send = post_send_with(terminalGridFidelity=short_viewport_value)
    short_rows_only_value = grid_fidelity()
    short_rows_only_value["wrappedCommandRows"] = short_rows_only_value["wrappedCommandRows"][2:]
    short_rows_only_post_send = post_send_with(terminalGridFidelity=short_rows_only_value)
    missing_fidelity_value = json.loads(post_send)
    del missing_fidelity_value["terminalGridFidelity"]
    missing_fidelity_post_send = json.dumps(missing_fidelity_value).encode()
    def geometry_payload(*, ime_visible: bool = True, app_bar_top: float = 24.0,
                         send_bottom: float = 218.0, terminal_height: float = 60.0,
                         keyboard_fidelity: dict[str, object] | None = None) -> bytes:
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
            "terminalGridFidelity": keyboard_fidelity if keyboard_fidelity is not None else grid_fidelity(),
        }).encode()

    geometry = geometry_payload()

    def snippet_geometry(*, selected: bool, keyboard_visible: bool = False,
                         write_count: int = 0, ack_count: int = 0,
                         chip_height: float = 48.0, terminal_top: float = 172.21,
                         terminal_bottom: float = 776.1, composer_top: float = 434.72,
                         composer_bottom: float = 867.05,
                         page_scroll: int = 0, include_target_bounds: bool = True,
                         draft_changed_pixels: int = 920, chip_changed_pixels: int = 12000) -> bytes:
        chip = {"tag": "BUTTON", "label": "Insert Multiline", "width": 92.0, "height": chip_height,
                "top": 596.29 - chip_height, "bottom": 596.29, "left": 12.0, "right": 104.0,
                "current": "true" if selected else ""}
        geometry = {
            "keyboardVisible": keyboard_visible,
            "imeVisible": keyboard_visible,
            "rowVisible": True,
            "composerPresent": True,
            "terminalPresent": True,
            "viewport": {"width": 412.19, "height": 915.05},
            "chipRow": {"top": 544.29, "bottom": 596.29, "left": 0.0, "right": 412.19, "width": 412.19, "height": 52.0},
            "composer": {"top": composer_top, "bottom": composer_bottom, "left": 0.0, "right": 412.19,
                         "width": 412.19, "height": composer_bottom - composer_top},
            "terminal": {"top": terminal_top, "bottom": terminal_bottom, "left": 0.0, "right": 412.19,
                         "width": 412.19, "height": terminal_bottom - terminal_top},
            "target": dict(chip),
            "chips": [chip],
            "expectedLabel": "Insert Multiline",
            "draftMatchesExact": selected,
            "composerWriteCount": write_count,
            "acknowledgedWrites": ack_count,
            "screenScrollTop": page_scroll,
            "documentScrollTop": page_scroll,
        }
        capture = {
            "captureMethod": "UiAutomation.takeScreenshot",
            "captureThread": "instrumentation",
            "visualStateCallbackCompleted": True,
            "attempt": 1,
            "pixelWidth": 1080,
            "pixelHeight": 2400,
            "webViewFrameOnScreen": {"left": 0, "top": 0, "width": 1080, "height": 2400},
            "cssViewport": {"width": 412.19, "height": 915.05, "offsetLeft": 0, "offsetTop": 0},
            "screenshotSha256": hashlib.sha256(selected_png if selected else png).hexdigest(),
            "selectedVisualStateVerified": selected,
        }
        if selected:
            capture.update({
                "baselinePngSha256": hashlib.sha256(png).hexdigest(),
                "draftRegion": {"changedPixels": draft_changed_pixels, "comparedPixels": 20000,
                                "minimumChangedPixels": 256,
                                "baselineRect": {"left": 100, "top": 100, "right": 300, "bottom": 200},
                                "selectedRect": {"left": 100, "top": 100, "right": 300, "bottom": 200}},
                "selectedChipRegion": {"changedPixels": chip_changed_pixels, "comparedPixels": 30000,
                                       "minimumChangedPixels": 256,
                                       "baselineRect": {"left": 400, "top": 300, "right": 600, "bottom": 450},
                                       "selectedRect": {"left": 400, "top": 300, "right": 600, "bottom": 450}},
            })
        geometry["screenshotCapture"] = capture
        if not include_target_bounds:
            for key in ("top", "bottom", "left", "right"):
                del geometry["target"][key]
        return json.dumps(geometry).encode()

    chip_down = snippet_geometry(selected=False)
    chip_selected = snippet_geometry(selected=True)
    chip_selected_write = snippet_geometry(selected=True, write_count=1)
    chip_selected_no_draft_pixels = snippet_geometry(selected=True, draft_changed_pixels=0)
    chip_selected_no_chip_pixels = snippet_geometry(selected=True, chip_changed_pixels=64)
    chip_selected_outside_pixel_crop = json.loads(chip_selected)
    chip_selected_outside_pixel_crop["screenshotCapture"]["draftRegion"]["selectedRect"]["left"] = 1080
    chip_selected_misaligned_pixel_crop = json.loads(chip_selected)
    chip_selected_misaligned_pixel_crop["screenshotCapture"]["draftRegion"]["selectedRect"]["left"] += 2
    chip_selected_misaligned_pixel_crop["screenshotCapture"]["draftRegion"]["selectedRect"]["right"] += 2
    chip_selected_small = snippet_geometry(selected=True, chip_height=44.0)
    snippet_insufficient_terminal = snippet_geometry(selected=False, terminal_top=400.0)
    snippet_composer_clipped = snippet_geometry(selected=False, composer_bottom=930.0)
    snippet_rail_outside_composer = snippet_geometry(selected=False, composer_top=560.0)
    snippet_terminal_clipped = snippet_geometry(selected=False, terminal_bottom=930.0)
    restart_evidence = json.dumps({
        "appPackage": "com.pocketshell.app.i2885",
        "oldPid": "401",
        "stoppedPid": "",
        "newPid": "455",
        "launchableActivity": "com.pocketshell.app.i2885/com.pocketshell.app.MainActivity",
        "hostId": "testuser@10.0.2.2:2243",
        "storedSnippetCount": 2,
        "mainSnippetExact": True,
        "uncertainSnippetExact": True,
        "mainLabel": "Multiline",
        "uncertainLabel": "Uncertain",
        "storageKey": "pocketshell.js.host-snippets.v1",
    }).encode()
    bad_restart_evidence = json.dumps({
        "appPackage": "com.pocketshell.app.i2885",
        "oldPid": "401",
        "stoppedPid": "401",
        "newPid": "455",
        "launchableActivity": "com.pocketshell.app.i2885/com.pocketshell.app.MainActivity",
        "hostId": "testuser@10.0.2.2:2243",
        "storedSnippetCount": 2,
        "mainSnippetExact": True,
        "uncertainSnippetExact": True,
        "mainLabel": "Multiline",
        "uncertainLabel": "Uncertain",
        "storageKey": "pocketshell.js.host-snippets.v1",
    }).encode()

    def make_lines(geometry_bytes: bytes = geometry, post_send_bytes: bytes = post_send,
                   chip_down_bytes: bytes = chip_down, chip_selected_bytes: bytes = chip_selected,
                   restart_bytes: bytes = restart_evidence,
                   chip_down_image: bytes = png, chip_selected_image: bytes = selected_png) -> list[str]:
        source = [
            ("composer-keyboard.png", png),
            ("composer-keyboard-geometry.json", geometry_bytes),
            ("composer-post-send.png", png),
            ("composer-post-send-terminal.json", post_send_bytes),
            ("composer-focus-trace.json", focus_trace),
            ("snippet-keyboard-down.png", chip_down_image),
            ("snippet-keyboard-down-geometry.json", chip_down_bytes),
            ("snippet-selected-chip.png", chip_selected_image),
            ("snippet-selected-chip-geometry.json", chip_selected_bytes),
            ("snippet-restart-evidence.json", restart_bytes),
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
    assert parse_assets("\n".join(lines), run_id, expected_terminal_marker=marker)["composer-keyboard.png"] == png
    print("PASS: keyboard and post-send inline artifacts extract with complete chunks and matching SHA-256")

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
        ("post-send screenshot missing", [line for line in lines if "composer-post-send.png" not in line]),
        ("post-send terminal record missing", [line for line in lines if "composer-post-send-terminal.json" not in line]),
        ("post-send marker belongs to another journey", make_lines(post_send_bytes=post_send.replace(marker.encode(), b"PS2857_SENT_other"))),
        ("post-send text source is not the rendered xterm buffer", make_lines(post_send_bytes=post_send.replace(b"xterm-active-buffer-after-render", b"unverified-dom-text"))),
        ("post-send terminal scrolled below the viewport", make_lines(post_send_bytes=clipped_post_send)),
        ("post-send screenshot captured with keyboard open", make_lines(post_send_bytes=keyboard_up_post_send)),
        ("post-send screenshot captured with native IME open", make_lines(post_send_bytes=native_ime_up_post_send)),
        ("post-send page scrolled to expose output", make_lines(post_send_bytes=scrolled_post_send)),
        ("post-send output obscured by composer", make_lines(post_send_bytes=obscured_output_post_send)),
        ("post-send modal composer and backdrop", make_lines(post_send_bytes=modal_post_send)),
        ("post-send visible backdrop", make_lines(post_send_bytes=backdrop_post_send)),
        ("post-send composer clipped by viewport", make_lines(post_send_bytes=clipped_composer_post_send)),
        ("post-send output row clipped by xterm screen", make_lines(post_send_bytes=clipped_output_post_send)),
        ("post-send output latency missing", make_lines(post_send_bytes=missing_latency_post_send)),
        ("post-send rendered rows wider than the xterm screen", make_lines(post_send_bytes=clipped_rows_post_send)),
        ("post-send Send changed the column count", make_lines(post_send_bytes=resized_by_send_post_send)),
        ("post-send xterm grid differs from the SSH-accepted grid", make_lines(post_send_bytes=unaccepted_grid_post_send)),
        ("post-send xterm screen extends past the terminal viewport", make_lines(post_send_bytes=screen_past_viewport_post_send)),
        ("post-send grid check measured only short rows", make_lines(post_send_bytes=short_rows_only_post_send)),
        ("post-send xterm screen extends below the terminal viewport", make_lines(post_send_bytes=screen_below_viewport_post_send)),
        ("post-send grid fidelity record missing", make_lines(post_send_bytes=missing_fidelity_post_send)),
        ("keyboard-up rendered rows wider than the xterm screen", make_lines(geometry_payload(
            keyboard_fidelity=grid_fidelity(row_cells=39.0)))),
        ("post-send output latency negative", make_lines(post_send_bytes=negative_latency_post_send)),
        ("post-send output latency exceeds the observation timeout", make_lines(post_send_bytes=invalid_latency_post_send)),
        ("snippet chips captured with keyboard open", make_lines(chip_down_bytes=snippet_geometry(selected=False, keyboard_visible=True))),
        ("snippet selected capture lacks a PTY write-before-send check", make_lines(chip_selected_bytes=chip_selected_write)),
        ("snippet selected capture duplicates the keyboard-down PNG", make_lines(chip_selected_image=png)),
        ("snippet selected capture has no changed draft pixels", make_lines(chip_selected_bytes=chip_selected_no_draft_pixels)),
        ("snippet selected capture has no selected-chip style pixels", make_lines(chip_selected_bytes=chip_selected_no_chip_pixels)),
        ("snippet selected capture points outside its PNG for draft pixels", make_lines(
            chip_selected_bytes=json.dumps(chip_selected_outside_pixel_crop).encode())),
        ("snippet selected capture compares a shifted draft crop", make_lines(
            chip_selected_bytes=json.dumps(chip_selected_misaligned_pixel_crop).encode())),
        ("snippet touch target below 48dp", make_lines(chip_selected_bytes=chip_selected_small)),
        ("snippet target omits its DOM rect bounds", make_lines(chip_down_bytes=snippet_geometry(selected=False, include_target_bounds=False))),
        ("snippet restart evidence missing", [line for line in lines if "snippet-restart-evidence.json" not in line]),
        ("snippet restart did not prove a terminated process", make_lines(restart_bytes=bad_restart_evidence)),
        ("snippet rail requires page scrolling", make_lines(chip_down_bytes=snippet_geometry(selected=False, page_scroll=1))),
        ("snippet composer clipped by viewport", make_lines(chip_down_bytes=snippet_composer_clipped)),
        ("snippet rail outside composer", make_lines(chip_down_bytes=snippet_rail_outside_composer)),
        ("snippet terminal viewport clipped", make_lines(chip_down_bytes=snippet_terminal_clipped)),
        ("snippet leaves less than 48dp of visible terminal above composer", make_lines(chip_down_bytes=snippet_insufficient_terminal)),
    ):
        try:
            parse_assets("\n".join(altered), run_id, expected_terminal_marker=marker)
        except ExtractionFailure as error:
            print(f"PASS: {label} fails closed ({error})")
        else:
            raise AssertionError(f"{label} unexpectedly passed")
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
        parse_assets("\n".join(focus_failure_lines), run_id, expected_terminal_marker=marker)
    except ExtractionFailure:
        print("PASS: packaged acceptance rejects retained focus-failure artifacts")
    else:
        raise AssertionError("focus failure screenshot unexpectedly passed strict packaged acceptance")
    retained_failure = parse_assets("\n".join(focus_failure_lines), run_id, validate_layout=False)
    assert retained_failure["composer-focus-failure.png"] == png
    assert b"ImeTracker" in retained_failure["composer-focus-failure-logcat.txt"]
    print("PASS: failure-mode extraction retains contemporaneous focus screenshot, state, and Android logs")
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
    try:
        names = extract(args.run_id, args.logcat, args.output_dir,
                        validate_layout=not args.preserve_on_failure,
                        expected_terminal_marker=args.expected_terminal_marker)
    except ExtractionFailure as error:
        print(f"FAIL: composer artifact extraction: {error}", file=sys.stderr)
        return 1
    print(f"PASS: extracted {len(names)} same-run composer artifacts")
    for name in names:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
