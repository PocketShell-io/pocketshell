#!/usr/bin/env python3
"""Extract same-run fast-key screenshots and validate packaged geometry/write evidence."""

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


TAG = "PS2884Asset:"
SCREENSHOTS = {
    "fastkeys-ime-open.png",
    "fastkeys-row-closed-ime-open.png",
    "fastkeys-sheet-main-ime-open.png",
    "fastkeys-sheet-main-tail-ime-open.png",
    "fastkeys-main-composer-actions-ime-open.png",
    "fastkeys-sheet-ctrl-ime-open.png",
    "fastkeys-sheet-ctrl-tail-ime-open.png",
    "fastkeys-tray-ime-dismissed.png",
    "fastkeys-tray-closed.png",
    "fastkeys-reconnected-ime-open.png",
    "fastkeys-dictation-idle-ime-open.png",
    "fastkeys-dictation-listening-ime-open.png",
    "fastkeys-dictation-listening-ctrl-ime-open.png",
    "fastkeys-dictation-transcribing-ime-open.png",
    "fastkeys-dictation-stopped-ime-open.png",
    "fastkeys-dictation-error-ime-open.png",
    "fastkeys-dictation-attach-cancel.png",
    "fastkeys-dictation-reattached-ime-open.png",
}
VIEWPORT_SCREENSHOTS = {
    "fastkeys-row-closed-ime-open-viewport.png",
    "fastkeys-sheet-main-ime-open-viewport.png",
    "fastkeys-sheet-main-tail-ime-open-viewport.png",
    "fastkeys-sheet-ctrl-ime-open-viewport.png",
    "fastkeys-sheet-ctrl-tail-ime-open-viewport.png",
    "fastkeys-main-composer-actions-ime-open-viewport.png",
    "fastkeys-dictation-listening-ime-open-viewport.png",
    "fastkeys-dictation-stopped-ime-open-viewport.png",
    "fastkeys-dictation-error-ime-open-viewport.png",
    "fastkeys-reconnected-ime-open-viewport.png",
}
REQUIRED_ASSETS = SCREENSHOTS | VIEWPORT_SCREENSHOTS | {"fastkeys-journey.json"}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


class ExtractionFailure(ValueError):
    pass


def expected_first_writes() -> list[dict[str, object]]:
    return [
        {"key": "arrow-up", "bytes": [0x1B, 0x5B, 0x41]},
        {"key": "arrow-down", "bytes": [0x1B, 0x5B, 0x42]},
        {"key": "escape", "bytes": [0x1B]},
        {"key": "tab", "bytes": [0x09]},
        {"key": "shift-tab", "bytes": [0x1B, 0x5B, 0x5A]},
        {"key": "ctrl-q", "bytes": [0x11]},
        {"key": "ctrl-c", "bytes": [0x03]},
        {"key": "ctrl-c", "bytes": [0x03, 0x03]},
        {"key": "ctrl-d", "bytes": [0x04]},
        {"key": "ctrl-d", "bytes": [0x04, 0x04]},
        {"key": "enter", "bytes": [0x0D]},
    ]


TIMING_FIELDS = (
    "connectToPromptMs",
    "tapToVisibleOutputMs",
    "reconnectTapToVisibleOutputMs",
)

MOBILE_HOTKEYS_BASE_HEIGHT_PX = 49
INLINE_DICTATION_STATUS_ROW_HEIGHT_PX = 16
CATALOG_SHEET_HEIGHT_PX = 144
ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX = 144
API35_ACCEPTED_TERMINAL_GRID = (38, 6)
# WebView can round shared rectangle edges apart by a tiny fraction; larger overlaps remain failures.
TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX = 0.01

EXPECTED_MAIN_KEY_IDS = (
    "arrow-left", "arrow-right", "escape", "tab", "shift-tab",
    "ctrl-b", "ctrl-c", "ctrl-d", "ctrl-q", "ctrl-x",
)
EXPECTED_CTRL_KEY_IDS = tuple(f"ctrl-{letter}" for row in ("qwert", "yuiop", "asdfg", "hjkl", "zxcvb", "nm")
                              for letter in row) + ("ctrl-backslash",)


def validate_composer_actions(
    item: dict[str, object], label: str, *, require_all_enabled: bool,
    require_ime_clearance_for_targets: bool = False,
) -> None:
    row = item.get("composerActionRow")
    actions = item.get("composerActions")
    viewport = item.get("visualViewport")
    if (not isinstance(row, dict) or not isinstance(actions, list) or len(actions) != 4
            or not isinstance(viewport, dict)):
        raise ExtractionFailure(f"{label} geometry lacks the four composer actions and viewport bounds")
    ime_edge = item.get("imeEdgeCssY", viewport.get("height", 0) + viewport.get("offsetTop", 0))
    if row.get("bottom", 10**9) > ime_edge - 4:
        raise ExtractionFailure(f"{label} composer action row does not keep 4px of clearance above the IME viewport")
    expected = {"discard", "dictate", "insert", "send"}
    seen: set[str] = set()
    enabled = 0
    for action in actions:
        if not isinstance(action, dict):
            raise ExtractionFailure(f"{label} composer action geometry is malformed")
        name = action.get("action")
        if not isinstance(name, str) or name not in expected or name in seen:
            raise ExtractionFailure(f"{label} composer action set is duplicated or incomplete")
        seen.add(name)
        if (action.get("width", 0) < 47.9 or action.get("height", 0) < 47.9
                or action.get("insideViewport") is not True or action.get("insideComposerPanel") is not True
                or action.get("top", 10**9) < row.get("top", 0) - 0.5
                or action.get("bottom", 10**9) > row.get("bottom", 0) + 0.5):
            raise ExtractionFailure(f"{label} composer action {name} is clipped or below its 48dp target")
        if require_ime_clearance_for_targets and action.get("bottom", 10**9) > ime_edge - 4:
            raise ExtractionFailure(f"{label} composer action {name} hit target does not clear the IME edge by 4px")
        if action.get("disabled") is not True:
            enabled += 1
            if action.get("hitTarget") is not True:
                raise ExtractionFailure(f"{label} enabled composer action {name} does not receive its measured touch target")
        if require_all_enabled and action.get("disabled") is not False:
            raise ExtractionFailure(f"{label} staged composer action {name} is not enabled")
    if seen != expected or enabled == 0:
        raise ExtractionFailure(f"{label} composer does not expose the complete tappable action row")


def validate_compact_status_composer(item: dict[str, object], label: str) -> None:
    panel = item.get("composerPanel")
    draft = item.get("composerDraft")
    row = item.get("composerActionRow")
    viewport = item.get("visualViewport")
    if not all(isinstance(rect, dict) for rect in (panel, draft, row, viewport)):
        raise ExtractionFailure(f"{label} lacks compact composer bounds for the status row")
    assert isinstance(panel, dict) and isinstance(draft, dict) and isinstance(row, dict) and isinstance(viewport, dict)
    ime_edge = item.get("imeEdgeCssY", viewport.get("height", 0) + viewport.get("offsetTop", 0))
    if (abs(panel.get("height", 0) - 80) > 0.5
            or abs(draft.get("height", 0) - 25) > 0.5
            or abs(row.get("height", 0) - 48) > 0.5
            or draft.get("top", 10**9) < panel.get("top", 0) - 0.5
            or row.get("top", 0) - draft.get("bottom", 10**9) < 2
            or row.get("bottom", 10**9) > panel.get("bottom", 0) + 0.5
            or panel.get("bottom", 10**9) > viewport.get("height", 0) + 0.5
            or row.get("bottom", 10**9) > ime_edge - 4):
        raise ExtractionFailure(f"{label} does not fit the 25px editor and 48px action row in its 80px panel")
    page = item.get("fastKeysPage")
    if page in {"main", "ctrl"}:
        sheet = item.get("catalogSheet")
        if (not isinstance(sheet, dict) or sheet.get("bottom", 10**9) > panel.get("top", 0) + 0.5
                or item.get("catalogSheetIntersectsComposer") is not False):
            raise ExtractionFailure(f"{label} catalog sheet overlaps the compact composer")
    else:
        tray = item.get("fastKeysTray")
        dock = tray.get("bounds") if isinstance(tray, dict) else None
        if not isinstance(dock, dict) or dock.get("bottom", 10**9) > panel.get("top", 0) + 0.5:
            raise ExtractionFailure(f"{label} dock overlaps the compact composer")
    validate_composer_actions(
        item, label, require_all_enabled=False, require_ime_clearance_for_targets=True,
    )


def format_timing_summary(journey: dict[str, object]) -> str:
    return (
        f"connect_to_prompt_ms={journey['connectToPromptMs']}\n"
        f"tap_to_visible_output_ms={journey['tapToVisibleOutputMs']}\n"
        f"reconnect_tap_to_visible_output_ms={journey['reconnectTapToVisibleOutputMs']}\n"
    )


def validate_docked_dictation_geometry(
    item: dict[str, object], label: str, *, allow_disabled_mic: bool = False,
) -> None:
    viewport = item.get("terminalViewport")
    slot = item.get("terminalSlot")
    cap = item.get("terminalViewportDockCapPx")
    dock = item.get("fastKeysTray")
    viewport_height = viewport.get("height") if isinstance(viewport, dict) else None
    slot_height = slot.get("height") if isinstance(slot, dict) else None
    dock_bounds = dock.get("bounds") if isinstance(dock, dict) else None
    rendered_dock_height = dock_bounds.get("height") if isinstance(dock_bounds, dict) else None
    declared_dock_height = item.get("terminalHotkeysDockHeightPx")
    keyboard_up_terminal = (item.get("keyboardVisible") is True
                            and item.get("keyboardComposerMode") is True
                            and item.get("sshPhase") == "live")
    should_preserve_grid = (item.get("fastKeysPage") in {"main", "ctrl"}
                            or item.get("inlineDictationStatusVisible") is True
                            or keyboard_up_terminal)
    expected_cap = (min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX, math.floor(viewport_height))
                    if isinstance(viewport_height, (int, float)) and not isinstance(viewport_height, bool)
                    else None)
    if should_preserve_grid and (
        isinstance(cap, bool) or not isinstance(cap, (int, float)) or cap <= 0
        or isinstance(viewport_height, bool) or not isinstance(viewport_height, (int, float))
        or cap is None or abs(cap - expected_cap) > 0.5
        or abs(viewport_height - cap) > 0.5
    ):
        raise ExtractionFailure(f"{label} did not apply the min(144px, measured viewport) keyboard-up cap")
    if should_preserve_grid and (
        isinstance(slot_height, bool) or not isinstance(slot_height, (int, float))
        or isinstance(rendered_dock_height, bool) or not isinstance(rendered_dock_height, (int, float))
        or isinstance(declared_dock_height, bool) or not isinstance(declared_dock_height, (int, float))
        or abs(rendered_dock_height - declared_dock_height) > 0.5
        or slot_height + 0.5 < cap + rendered_dock_height + 1
    ):
        raise ExtractionFailure(f"{label} terminal slot does not reserve the capped viewport, rendered dock, and 1px gap")
    bar = item.get("inlineDictationBar")
    mic = item.get("inlineDictationMic")
    mobile_hotkeys = item.get("mobileHotkeys")
    if not isinstance(bar, dict) or not isinstance(mic, dict):
        raise ExtractionFailure(f"{label} does not contain the docked dictation bar and mic")
    page = item.get("fastKeysPage")
    expected_dock_height = (MOBILE_HOTKEYS_BASE_HEIGHT_PX
                            + (CATALOG_SHEET_HEIGHT_PX if page in {"main", "ctrl"} else 0)
                            + (INLINE_DICTATION_STATUS_ROW_HEIGHT_PX
                               if item.get("inlineDictationStatusVisible") is True else 0))
    if (not isinstance(mobile_hotkeys, dict)
            or abs(mobile_hotkeys.get("height", 0) - expected_dock_height) > 0.5):
        raise ExtractionFailure(f"{label} dock height does not match the persistent row, catalog, and status placement")
    if page in {"main", "ctrl"}:
        sheet = item.get("catalogSheet")
        scroller = item.get("catalogScrollerBounds")
        if (item.get("catalogScrollerInsideSheet") is not True
                or not isinstance(sheet, dict) or not isinstance(scroller, dict)
                or scroller.get("top", -1) < sheet.get("top", 0) - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                or scroller.get("bottom", 10**9) > sheet.get("bottom", 0) + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                or scroller.get("left", -1) < sheet.get("left", 0) - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                or scroller.get("right", 10**9) > sheet.get("right", 0) + TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
                or abs(scroller.get("height", 0) - (sheet.get("height", 0) - 48)) > 1):
            raise ExtractionFailure(f"{label} visible catalog scroller is clipped by the 144px sheet")
    tray_bounds = dock.get("bounds") if isinstance(dock, dict) else None
    terminal_panel = item.get("terminalPanel")
    if (not isinstance(tray_bounds, dict) or not isinstance(terminal_panel, dict)
            or dock.get("insideTerminalPanel") is not True
            or tray_bounds.get("top", -1) < terminal_panel.get("top", 0)
            or tray_bounds.get("bottom", 10**9) > terminal_panel.get("bottom", 0)):
        raise ExtractionFailure(f"{label} dock extends outside the clipped terminal panel")
    if (not isinstance(viewport, dict)
            or tray_bounds.get("top", -1)
            < viewport.get("bottom", 0) - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX):
        raise ExtractionFailure(f"{label} dock overlaps the measured terminal viewport")
    terminal_panel = item.get("terminalPanel")
    if (not isinstance(slot, dict) or not isinstance(terminal_panel, dict)
            or item.get("terminalSlotInsideTerminalPanel") is not True
            or slot.get("top", -1) < terminal_panel.get("top", 0) - 0.5
            or slot.get("bottom", 10**9) > terminal_panel.get("bottom", 0) + 0.5):
        raise ExtractionFailure(f"{label} terminal slot extends beyond its clipped panel")
    keybar = item.get("keybarClientRect")
    if not isinstance(keybar, dict):
        raise ExtractionFailure(f"{label} does not include persistent terminal-key geometry")
    if item.get("inlineDictationBarCount") != 1 or item.get("inlineDictationMicCount") != 1:
        raise ExtractionFailure(f"{label} must contain exactly one inline dictation bar and mic")
    if (isinstance(mic.get("width"), bool) or not isinstance(mic.get("width"), (int, float))
            or mic["width"] < 47.9 or isinstance(mic.get("height"), bool)
            or not isinstance(mic.get("height"), (int, float)) or mic["height"] < 47.9):
        raise ExtractionFailure(f"{label} mic target is smaller than 48dp")
    if mic.get("visibleLabel", "") != "":
        raise ExtractionFailure(f"{label} persistent microphone adds a visible caption to the compact controls row")
    if mic.get("insideViewport") is not True:
        raise ExtractionFailure(f"{label} mic target is clipped")
    if (mic.get("visibleWidthInKeybar", 0) < 47.9
            or mic.get("visibleHeightInKeybar", 0) < 47.9):
        raise ExtractionFailure(f"{label} microphone clipped hit target is below 48dp")
    if (mic.get("left", -1) < keybar.get("left", 0) - 0.5
            or mic.get("right", 10**9) > keybar.get("right", 0) + 0.5
            or mic.get("top", -1) < keybar.get("top", 0) - 0.5
            or mic.get("bottom", 10**9) > keybar.get("bottom", 0) + 0.5
            or item.get("inlineDictationMicInsideKeybar") is not True):
        raise ExtractionFailure(f"{label} microphone is outside the persistent key row")
    if mic.get("disabled") is not (True if allow_disabled_mic else False):
        raise ExtractionFailure(f"{label} mic enabled state does not match its dictation phase")
    if item.get("inlineDictationBarInsideTray") is not True or item.get("inlineDictationMicInsideBar") is not True:
        raise ExtractionFailure(f"{label} dictation action is outside the persistent dock")
    row_metrics = item.get("persistentRowMetrics")
    if (not isinstance(row_metrics, dict)
            or isinstance(row_metrics.get("clientWidth"), bool)
            or not isinstance(row_metrics.get("clientWidth"), (int, float))
            or isinstance(row_metrics.get("scrollWidth"), bool)
            or not isinstance(row_metrics.get("scrollWidth"), (int, float))
            or row_metrics.get("scrollWidth") > row_metrics.get("clientWidth") + 0.5
            or row_metrics.get("scrollable") is not (row_metrics["scrollWidth"] > row_metrics["clientWidth"] + 1)):
        raise ExtractionFailure(f"{label} live-width persistent row is clipped or lacks consistent responsive geometry")
    viewport_width = item.get("visualViewport", {}).get("width") if isinstance(item.get("visualViewport"), dict) else None
    if isinstance(viewport_width, (int, float)) and viewport_width >= 400 and row_metrics["scrollWidth"] > row_metrics["clientWidth"] + 1:
        raise ExtractionFailure(f"{label} 412px persistent row unexpectedly requires horizontal scrolling")
    if item.get("inlineDictationStatusVisible") is True:
        validate_compact_status_composer(item, label)
        status_row = item.get("inlineDictationStatusRow")
        expected_placement = item.get("inlineDictationStatusAboveKeybar") is True
        if (item.get("inlineDictationStatusOneLine") is not True
                or item.get("inlineDictationStatusInsideBar") is not True
                or item.get("inlineDictationStatusInsideSheetHeader") is not False
                or not expected_placement
                or not isinstance(status_row, dict)
                or abs(status_row.get("height", 0) - INLINE_DICTATION_STATUS_ROW_HEIGHT_PX) > 0.5
                or status_row.get("bottom", 10**9) > keybar.get("top", 0) + 0.5):
            raise ExtractionFailure(f"{label} status is not a 16px one-line row above the persistent keys")
    elif (item.get("inlineDictationPhase") != "idle" or item.get("inlineDictationTone") != "quiet"
          or item.get("inlineDictationStatusText") != ""):
        raise ExtractionFailure(f"{label} hides a meaningful dictation status")
    nav = item.get("navigationTargets")
    page = item.get("fastKeysPage")
    expected_nav_count = 4
    if not isinstance(nav, list) or len(nav) != expected_nav_count:
        raise ExtractionFailure(f"{label} does not keep arrows, Enter, and the persistent catalog launcher visible")
    expected_nav = ("Send Up arrow", "Send Down arrow", "Send Enter")
    for index, target in enumerate(nav):
        if not isinstance(target, dict):
            raise ExtractionFailure(f"{label} persistent terminal navigation is missing a required action")
        if index < 3:
            label_matches = target.get("label") == expected_nav[index]
        else:
            label_matches = target.get("label") == (
                "More terminal keys" if page == "closed" else "Close terminal keys"
            )
        if not label_matches:
            raise ExtractionFailure(f"{label} persistent terminal navigation is missing a required action")
        if (target.get("disabled") is not False or target.get("insideViewport") is not True
                or target.get("width", 0) < 47.9 or target.get("height", 0) < 47.9
                or target.get("visibleWidthInKeybar", 0) < 47.9
                or target.get("visibleHeightInKeybar", 0) < 47.9):
            raise ExtractionFailure(f"{label} persistent terminal key is clipped, disabled, or below 48dp")
    divider = item.get("enterDivider")
    if (not isinstance(divider, dict) or len(nav) < 3
            or abs(divider.get("width", 0) - 1) > 0.5
            or abs(divider.get("height", 0) - 24) > 0.5
            or abs(divider.get("top", 10**9) - (nav[0].get("top", 0) + 12)) > 0.5
            or divider.get("left", -10**9) < nav[1].get("right", 0) - 0.5
            or divider.get("right", 10**9) > nav[2].get("left", 0) + 0.5):
        raise ExtractionFailure(f"{label} does not preserve the Kotlin 1x24px Enter divider between 48px keys")
    if (not isinstance(mobile_hotkeys, dict)
            or isinstance(nav[-1].get("right"), bool) or not isinstance(nav[-1].get("right"), (int, float))
            or isinstance(mic.get("left"), bool) or not isinstance(mic.get("left"), (int, float))
            or isinstance(mic.get("right"), bool) or not isinstance(mic.get("right"), (int, float))
            or isinstance(mobile_hotkeys.get("right"), bool)
            or not isinstance(mobile_hotkeys.get("right"), (int, float))
            or mic["left"] < nav[-1]["right"] - 0.5
            or mic["left"] > nav[-1]["right"] + 8.5
            or mic["right"] > mobile_hotkeys["right"] + 0.5):
        raise ExtractionFailure(f"{label} mic is not adjacent to the trailing Fast Keys control")
    epoch = item.get("sshAttachEpoch")
    target_key = item.get("inlineDictationTargetKey")
    if (isinstance(epoch, bool) or not isinstance(epoch, int) or epoch < 0
            or not isinstance(target_key, str) or not target_key.endswith(f"/attach-{epoch}")):
        raise ExtractionFailure(f"{label} dictation target does not identify its attach epoch")


def validate_dictation_behavior(journey: dict[str, object]) -> None:
    dictation = journey.get("dictation")
    if not isinstance(dictation, dict):
        raise ExtractionFailure("journey evidence is missing integrated terminal dictation")
    request_id = dictation.get("requestId")
    target_key = dictation.get("targetKey")
    attach_epoch = dictation.get("attachEpoch")
    if (not isinstance(request_id, str) or not request_id
            or not isinstance(target_key, str) or not isinstance(attach_epoch, int)
            or isinstance(attach_epoch, bool) or not target_key.endswith(f"/attach-{attach_epoch}")):
        raise ExtractionFailure("dictation request lacks a target identity containing the active attach epoch")
    if dictation.get("stopRequestId") != request_id or dictation.get("explicitStop") is not True:
        raise ExtractionFailure("dictation final result was not gated by explicit Stop on its recognizer request")
    if dictation.get("nativeStartCalls") != 1 or dictation.get("nativeStopCalls") != 1:
        raise ExtractionFailure("one docked dictation request started or stopped more than one native recognizer")
    if dictation.get("finalReceived") is not True or dictation.get("stoppedReceived") is not True:
        raise ExtractionFailure("dictation journey lacks its final and stopped recognizer events")
    before = dictation.get("writesBeforePartial")
    partial = dictation.get("writesAfterPartial")
    after_stop = dictation.get("writesAfterStopBeforeFinal")
    after_final = dictation.get("writesAfterFinalBeforeStopped")
    after_stopped = dictation.get("writesAfterStopped")
    after_keyboard = dictation.get("writesAfterPostStopKeyboard")
    counts = (before, partial, after_stop, after_final, after_stopped, after_keyboard)
    if any(isinstance(value, bool) or not isinstance(value, int) for value in counts):
        raise ExtractionFailure("dictation journey is missing integer PTY acknowledgement checkpoints")
    if (partial != before or after_stop != before or after_final != before
            or after_stopped != before + 1 or after_keyboard != after_stopped + 1):
        raise ExtractionFailure("partials or final results wrote before explicit Stop, or terminal input acknowledgements did not advance once for Stop and once for keyboard input")
    partial_text = dictation.get("partialText")
    final_text = dictation.get("finalText")
    expected_hex = dictation.get("expectedHostHex")
    byte_count = dictation.get("expectedByteCount")
    raw_file = dictation.get("rawFile")
    post_stop_text = dictation.get("postStopKeyboardText")
    post_stop_input_chunks = dictation.get("postStopInputChunks")
    dictated_hex = dictation.get("dictatedTextHex")
    post_stop_draft_before = dictation.get("postStopKeyboardDraftBefore")
    post_stop_draft_after = dictation.get("postStopKeyboardDraftAfter")
    expected_final_byte_count = dictation.get("expectedFinalByteCount")
    if (not isinstance(partial_text, str) or not partial_text
            or not isinstance(final_text, str) or final_text != partial_text
            or not isinstance(expected_hex, str) or not re.fullmatch(r"(?:[0-9a-f]{2})+", expected_hex)
            or not isinstance(dictated_hex, str) or dictated_hex != final_text.encode("utf-8").hex()
            or not isinstance(post_stop_text, str) or len(post_stop_text.encode("utf-8")) < 1
            or expected_hex != (final_text + post_stop_text).encode("utf-8").hex()
            or isinstance(byte_count, bool) or not isinstance(byte_count, int)
            or byte_count != len(bytes.fromhex(expected_hex))
            or isinstance(expected_final_byte_count, bool) or not isinstance(expected_final_byte_count, int)
            or expected_final_byte_count != len(final_text.encode("utf-8"))
            or post_stop_draft_before != "" or post_stop_draft_after != ""
            or dictation.get("postStopTerminalFocused") is not True
            or not isinstance(raw_file, str) or not re.fullmatch(r"/tmp/[A-Za-z0-9._-]+-keys-dictation\.raw", raw_file)):
        raise ExtractionFailure("dictation plus post-Stop keyboard input does not match the host PTY byte oracle or terminal focus proof")
    target_session_id = target_key.rsplit("/", 1)[0].rsplit("/", 1)[-1]
    if (not isinstance(post_stop_input_chunks, list) or not post_stop_input_chunks
            or "".join(chunk.get("text", "") for chunk in post_stop_input_chunks
                       if isinstance(chunk, dict)) != post_stop_text
            or any(not isinstance(chunk, dict)
                   or chunk.get("attachEpoch") != attach_epoch
                   or chunk.get("phase") != "live"
                   or chunk.get("sessionId") != target_session_id
                   for chunk in post_stop_input_chunks)):
        raise ExtractionFailure("post-Stop keyboard input was not captured by xterm in the active session and attach epoch")
    for marker_name, prefix in (("readyMarker", "PS2884_DICTATION_READY_"), ("doneMarker", "PS2884_DICTATION_DONE_")):
        marker = dictation.get(marker_name)
        if not isinstance(marker, str) or not marker.startswith(prefix):
            raise ExtractionFailure(f"dictation evidence is missing its host receiver {marker_name}")

    error = journey.get("dictationError")
    if (not isinstance(error, dict) or not error.get("requestId") or error.get("tone") != "error"
            or error.get("phaseIdle") is not True or error.get("previewCleared") is not True
            or error.get("writesBefore") != error.get("writesAfter") or error.get("nativeStartCalls") != 2):
        raise ExtractionFailure("recognizer error did not clear the preview without inserting text")

    attach = journey.get("dictationAttachCancel")
    if not isinstance(attach, dict):
        raise ExtractionFailure("journey evidence is missing dictation cancellation on session attach")
    old_epoch = attach.get("oldAttachEpoch")
    new_epoch = attach.get("newAttachEpoch")
    old_target = attach.get("oldTargetKey")
    new_target = attach.get("newTargetKey")
    if (attach.get("stopRequestId") != attach.get("requestId")
            or attach.get("lateResultEmitted") is not True or attach.get("stoppedEmitted") is not True
            or attach.get("writesBefore") != attach.get("writesAfter")
            or attach.get("nativeStartCalls") != 3 or attach.get("nativeStopCalls") != 2
            or isinstance(old_epoch, bool) or not isinstance(old_epoch, int)
            or isinstance(new_epoch, bool) or not isinstance(new_epoch, int) or new_epoch <= old_epoch
            or not isinstance(old_target, str) or not old_target.endswith(f"/attach-{old_epoch}")
            or not isinstance(new_target, str) or not new_target.endswith(f"/attach-{new_epoch}")
            or old_target == new_target):
        raise ExtractionFailure("late dictation result was not rejected across the new session attach epoch")

    background = journey.get("dictationBackgroundCancel")
    if (not isinstance(background, dict) or background.get("stopRequestId") != background.get("requestId")
            or background.get("lateResultEmitted") is not True or background.get("stoppedEmitted") is not True
            or background.get("writesBefore") != background.get("writesAfter")
            or background.get("nativeStartCalls") != 4 or background.get("nativeStopCalls") != 3):
        raise ExtractionFailure("late dictation result was not rejected after app backgrounding")

    request_ids = [request_id, error.get("requestId"), attach.get("requestId"), background.get("requestId")]
    if any(not isinstance(value, str) or not value for value in request_ids) or len(set(request_ids)) != len(request_ids):
        raise ExtractionFailure("dictation reused a recognizer request ID across distinct sessions")


def parse_assets(log_text: str, run_id: str) -> dict[str, bytes]:
    records: dict[str, dict[str, object]] = {}
    for line in log_text.splitlines():
        if TAG not in line:
            continue
        message = line.split(TAG, 1)[1].strip()
        parts = message.split("|", 4)
        if len(parts) < 3 or parts[1] != run_id:
            continue
        kind, _, name = parts[:3]
        if not SAFE_NAME.fullmatch(name) or name not in REQUIRED_ASSETS:
            raise ExtractionFailure(f"unsafe or unexpected asset {name!r}")
        if kind == "BEGIN":
            if len(parts) != 5 or name in records:
                raise ExtractionFailure(f"duplicate or malformed BEGIN for {name}")
            try:
                count = int(parts[3])
            except ValueError as error:
                raise ExtractionFailure(f"invalid chunk count for {name}") from error
            if count < 1 or not re.fullmatch(r"[a-f0-9]{64}", parts[4]):
                raise ExtractionFailure(f"invalid count or digest for {name}")
            records[name] = {"count": count, "digest": parts[4], "chunks": {}, "ended": False}
        elif kind == "DATA":
            if len(parts) != 5 or name not in records or records[name]["ended"]:
                raise ExtractionFailure(f"DATA without active BEGIN for {name}")
            try:
                index = int(parts[3])
            except ValueError as error:
                raise ExtractionFailure(f"invalid chunk index for {name}") from error
            chunks = records[name]["chunks"]
            assert isinstance(chunks, dict)
            if index in chunks:
                raise ExtractionFailure(f"duplicate chunk {index} for {name}")
            chunks[index] = parts[4]
        elif kind == "END":
            if len(parts) != 3 or name not in records or records[name]["ended"]:
                raise ExtractionFailure(f"END without matching BEGIN for {name}")
            records[name]["ended"] = True
        else:
            raise ExtractionFailure(f"unknown asset record {kind!r}")

    if set(records) != REQUIRED_ASSETS:
        raise ExtractionFailure(f"expected {sorted(REQUIRED_ASSETS)}, found {sorted(records)}")
    decoded: dict[str, bytes] = {}
    for name, record in records.items():
        chunks = record["chunks"]
        assert isinstance(chunks, dict)
        count = record["count"]
        if not record["ended"] or len(chunks) != count or set(chunks) != set(range(count)):
            raise ExtractionFailure(f"asset {name} is incomplete")
        encoded = "".join(str(chunks[index]) for index in range(count))
        try:
            payload = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error:
            raise ExtractionFailure(f"asset {name} contains invalid base64") from error
        if hashlib.sha256(payload).hexdigest() != record["digest"]:
            raise ExtractionFailure(f"asset {name} hash does not match its manifest")
        decoded[name] = payload

    for name in SCREENSHOTS | VIEWPORT_SCREENSHOTS:
        payload = decoded[name]
        if len(payload) < 1024 or not payload.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ExtractionFailure(f"{name} is not a full non-empty PNG")
    try:
        journey = json.loads(decoded["fastkeys-journey.json"])
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExtractionFailure(f"journey JSON is invalid: {error}") from error
    validate_journey(journey)
    return decoded


def validate_journey(journey: object) -> None:
    if not isinstance(journey, dict):
        raise ExtractionFailure("journey evidence must be a JSON object")
    if not isinstance(journey.get("androidApi"), int) or journey["androidApi"] < 35:
        raise ExtractionFailure("journey evidence does not prove API 35+")
    narrow_row = journey.get("narrowToolbarReachability")
    narrow_targets = narrow_row.get("targets") if isinstance(narrow_row, dict) else None
    if (not isinstance(narrow_row, dict)
            or abs(narrow_row.get("clientWidth", 0) - 330) > 1
            or narrow_row.get("scrollable") is not False
            or narrow_row.get("scrollWidth", 10**9) > narrow_row.get("clientWidth", 0) + 1
            or narrow_row.get("ptyWritesBefore") != narrow_row.get("ptyWritesAfter")
            or not isinstance(narrow_targets, list)
            or len(narrow_targets) != 5
            or any(not isinstance(target, dict)
                   or target.get("width", 0) < 47.9 or target.get("height", 0) < 47.9
                   or target.get("visibleWidth", 0) < 47.9 or target.get("visibleHeight", 0) < 47.9
                   or target.get("hitTarget") is not True
                   or target.get("insideToolbar") is not True or target.get("disabled") is True
                   for target in narrow_targets)
            or {target.get("label") for target in narrow_targets if isinstance(target, dict)}
               < {"Send Up arrow", "Send Down arrow", "Send Enter", "More terminal keys", "Dictate to terminal"}
           ):
        raise ExtractionFailure("330px dock does not prove reachable 48dp navigation, launcher, and dictation controls")
    validate_dictation_behavior(journey)
    if journey.get("firstHotkeyWrites") != expected_first_writes():
        raise ExtractionFailure("mounted app write trace does not match the expected first-session key payloads")
    final = expected_first_writes() + [{"key": "arrow-up", "bytes": [0x1B, 0x5B, 0x41]}]
    if journey.get("allHotkeyWrites") != final:
        raise ExtractionFailure("mounted app trace does not prove the reattached session key write")
    for timing_name in TIMING_FIELDS:
        timing = journey.get(timing_name)
        if (isinstance(timing, bool)
                or not isinstance(timing, (int, float))
                or not math.isfinite(timing)
                or timing <= 0):
            raise ExtractionFailure(f"journey timing {timing_name} must be a positive finite millisecond duration")
    first_done_marker = journey.get("firstDoneMarker")
    resumed_done_marker = journey.get("resumedDoneMarker")
    if not isinstance(first_done_marker, str) or not first_done_marker.startswith("PS2884_DONE_"):
        raise ExtractionFailure("journey evidence is missing the first-session rendered output marker")
    if not isinstance(resumed_done_marker, str) or not resumed_done_marker.startswith("PS2884_RESUMED_DONE_"):
        raise ExtractionFailure("journey evidence is missing the reattached-session rendered output marker")
    held = journey.get("reattachEarlyPromptTapWhileHeld")
    if not isinstance(held, dict):
        raise ExtractionFailure("journey evidence is missing the early prompt tap while attach focus is held")
    if (held.get("activeElement") != "prompt-draft"
            or held.get("keyboardVisible") is not True
            or held.get("androidImeVisible") is not True
            or held.get("attachFocusPending") is not True):
        raise ExtractionFailure("early reattach tap did not prove focused prompt with the native IME while attach work was held")
    if (isinstance(held.get("attachEpoch"), bool)
            or not isinstance(held.get("attachEpoch"), int)
            or isinstance(held.get("attachResizeAckEpoch"), bool)
            or not isinstance(held.get("attachResizeAckEpoch"), int)
            or held["attachEpoch"] == held["attachResizeAckEpoch"]):
        raise ExtractionFailure("early prompt tap did not occur before this attach's explicit resize acknowledgement")
    held_gate = held.get("gate")
    held_pending = held_gate.get("pending") if isinstance(held_gate, dict) else None
    if (not isinstance(held_gate, dict)
            or held_gate.get("released") is not False
            or isinstance(held_pending, bool)
            or not isinstance(held_pending, int)
            or held_pending < 1):
        raise ExtractionFailure("early prompt tap was not captured behind a held autofocus gate")
    entered = held_gate.get("entered")
    entered_sources = {
        event.get("source")
        for event in entered
        if isinstance(event, dict) and isinstance(event.get("source"), str)
    } if isinstance(entered, list) else set()
    if not {"terminal-enabled-watcher", "attach-resize"}.issubset(entered_sources):
        raise ExtractionFailure("early prompt tap evidence does not identify both held attach autofocus paths")
    resize_gate_at = next(
        (event.get("atMs") for event in entered
         if isinstance(event, dict) and event.get("source") == "attach-resize"),
        None,
    )
    focus_events = held.get("focusEvents")
    if (isinstance(resize_gate_at, bool)
            or not isinstance(resize_gate_at, (int, float))
            or not isinstance(focus_events, list)
            or not any(
                isinstance(event, dict)
                and event.get("type") == "focusin"
                and isinstance(event.get("atMs"), (int, float))
                and not isinstance(event.get("atMs"), bool)
                and event["atMs"] >= resize_gate_at
                and isinstance(event.get("target"), dict)
                and event["target"].get("testId") == "prompt-draft"
                for event in focus_events
            )):
        raise ExtractionFailure("early-tap focus trace does not show the prompt gaining focus after attach resize was held")
    after_attach = journey.get("reattachEarlyPromptTapAfterAttach")
    if not isinstance(after_attach, dict):
        raise ExtractionFailure("journey evidence is missing the final early-tap focus and IME state")
    if (after_attach.get("activeElement") != "prompt-draft"
            or after_attach.get("keyboardVisible") is not True
            or after_attach.get("androidImeVisible") is not True
            or after_attach.get("attachFocusPending") is not False
            or after_attach.get("attachEpoch") != after_attach.get("attachResizeAckEpoch")):
        raise ExtractionFailure("prompt focus or native IME was lost after attach resize/autofocus finished")
    final_gate = after_attach.get("gate")
    final_pending = final_gate.get("pending") if isinstance(final_gate, dict) else None
    if (not isinstance(final_gate, dict)
            or final_gate.get("released") is not True
            or isinstance(final_pending, bool)
            or not isinstance(final_pending, int)
            or final_pending != 0):
        raise ExtractionFailure("final early-tap evidence was captured before the deterministic attach gate settled")
    final_entered = final_gate.get("entered")
    final_sources = {
        event.get("source")
        for event in final_entered
        if isinstance(event, dict) and isinstance(event.get("source"), str)
    } if isinstance(final_entered, list) else set()
    if not {"terminal-enabled-watcher", "attach-resize", "attach-final-focus"}.issubset(final_sources):
        raise ExtractionFailure("final early-tap evidence does not show every attach autofocus path settling")
    stages = journey.get("geometryTrace")
    if not isinstance(stages, list):
        raise ExtractionFailure("geometryTrace is missing")
    by_name = {item.get("stage"): item for item in stages if isinstance(item, dict)}
    required_stages = {
        "keyboard-up-compact-row",
        "after-navigation-row-taps",
        "before-fast-keys",
        "fast-keys-main-open-ime-up",
        "fast-keys-main-composer-actions-ime-up",
        "fast-keys-main-catalog-reachable",
        "fast-keys-ctrl-open-ime-up",
        "fast-keys-ctrl-catalog-reachable",
        "fast-keys-closed-ime-up",
        "fast-keys-open-ime-dismissed",
        "fast-keys-open-ime-up-before-back",
        "dictation-idle-ime-open",
        "dictation-ready-ime-open",
        "dictation-listening-ime-open",
        "dictation-listening-ctrl-open-ime-open",
        "dictation-final-awaiting-stopped",
        "dictation-final-inserted",
        "dictation-post-stop-keyboard-input",
        "dictation-error-ime-open",
        "dictation-attach-cancel-complete",
        "dictation-reattached-ime-open",
        "dictation-background-cancel-resumed",
        "after-reconnect",
        "reconnected-keybar-ime-up",
        "after-reconnect-loss",
    }
    if not required_stages.issubset(by_name):
        raise ExtractionFailure(f"missing geometry stages: {sorted(required_stages - set(by_name))}")
    back_precondition = by_name["fast-keys-open-ime-up-before-back"]
    if (back_precondition.get("sshPhase") != "live"
            or back_precondition.get("homeSurface") != "live"
            or back_precondition.get("keyboardVisible") is not True
            or back_precondition.get("keyboardComposerMode") is not True
            or back_precondition.get("fastKeysPage") not in {"main", "ctrl"}
            or back_precondition.get("androidIme", {}).get("visible") is not True):
        raise ExtractionFailure("Back layering was not tested with the live IME and dock palette both open")
    for stage_name, item in by_name.items():
        if stage_name == "after-reconnect-loss":
            if (item.get("inlineDictationBar") is not None
                    or item.get("inlineDictationMic") is not None
                    or item.get("inlineDictationBarCount") != 0
                    or item.get("inlineDictationMicCount") != 0):
                raise ExtractionFailure("live-only dictation controls remain after the SSH session is lost")
            continue
        grid = item.get("runtimeGeometry")
        if not isinstance(grid, dict) or grid.get("rows", 0) < 5:
            raise ExtractionFailure(f"{stage_name} leaves fewer than five terminal rows visible")
        visible_rows = item.get("visibleTerminalRows")
        cell_height = grid.get("cellHeight")
        viewport = item.get("terminalViewport")
        viewport_height = viewport.get("height") if isinstance(viewport, dict) else None
        if (isinstance(visible_rows, bool) or not isinstance(visible_rows, int) or visible_rows < 5
                or isinstance(cell_height, bool) or not isinstance(cell_height, (int, float)) or cell_height <= 0
                or isinstance(viewport_height, bool) or not isinstance(viewport_height, (int, float))
                or visible_rows != math.floor((viewport_height - 8) / cell_height)):
            raise ExtractionFailure(f"{stage_name} does not prove five physical xterm rows in its measured viewport")
        validate_docked_dictation_geometry(
            item,
            stage_name,
            allow_disabled_mic=stage_name == "dictation-final-awaiting-stopped",
        )
        tray_geometry = item.get("fastKeysTray")
        if (not isinstance(tray_geometry, dict)
                or tray_geometry.get("insideSlot") is not True
                or tray_geometry.get("insideTerminalPanel") is not True
                or tray_geometry.get("belowTerminalViewport") is not True
                or tray_geometry.get("intersectsTerminalViewport") is not False
                or tray_geometry.get("intersectsComposerPanel") is not False):
            raise ExtractionFailure(f"{stage_name} dock overlaps its clipped terminal panel, xterm, or composer")

    dictation = journey["dictation"]
    assert isinstance(dictation, dict)
    listening = by_name["dictation-listening-ime-open"]
    if (listening.get("inlineDictationPhase") != "listening"
            or listening.get("inlineDictationPreview") != dictation.get("partialText")
            or listening.get("inlineDictationMic", {}).get("label") != "Stop terminal dictation"
            or listening.get("inlineDictationMic", {}).get("micState") != "listening"
            or listening.get("inlineDictationStatusVisible") is not True
            or listening.get("androidIme", {}).get("visible") is not True):
        raise ExtractionFailure("listening journey does not show the partial preview and reachable Stop beside the open IME")
    final_pending = by_name["dictation-final-awaiting-stopped"]
    if (final_pending.get("inlineDictationPhase") != "stopping"
            or final_pending.get("inlineDictationPreview") != dictation.get("finalText")
            or final_pending.get("inlineDictationMic", {}).get("disabled") is not True
            or final_pending.get("inlineDictationMic", {}).get("micState") != "transcribing"
            or final_pending.get("inlineDictationStatusVisible") is not True
            or dictation.get("writesAfterFinalBeforeStopped") != dictation.get("writesBeforePartial")):
        raise ExtractionFailure("final result was not held preview-only while explicit Stop was finishing")
    final_inserted = by_name["dictation-final-inserted"]
    if (final_inserted.get("inlineDictationPhase") != "idle"
            or final_inserted.get("inlineDictationTone") != "success"
            or "Inserted at the cursor" not in final_inserted.get("inlineDictationStatusText", "")
            or final_inserted.get("inlineDictationStatusVisible") is not True
            or final_inserted.get("keyboardVisible") is not True
            or final_inserted.get("keyboardComposerMode") is not True
            or final_inserted.get("androidIme", {}).get("visible") is not True
            or final_inserted.get("terminalViewportFocused") is not True
            or final_inserted.get("activeElementInsideTerminal") is not True
            or final_inserted.get("activeElementIsPromptDraft") is not False
            or final_inserted.get("composerDraftValue") != ""):
        raise ExtractionFailure("dictation did not report its single explicit final insertion")
    post_stop = by_name["dictation-post-stop-keyboard-input"]
    if (post_stop.get("keyboardVisible") is not True
            or post_stop.get("keyboardComposerMode") is not True
            or post_stop.get("androidIme", {}).get("visible") is not True
            or post_stop.get("terminalViewportFocused") is not True
            or post_stop.get("activeElementInsideTerminal") is not True
            or post_stop.get("activeElementIsPromptDraft") is not False
            or post_stop.get("composerDraftValue") != ""
            or post_stop.get("runtimeGeometry", {}).get("rows", 0) < 5
            or post_stop.get("inlineDictationTone") != "success"
            or post_stop.get("inlineDictationStatusVisible") is not True):
        raise ExtractionFailure("post-Stop keyboard input did not remain in the focused terminal with five visible rows")
    error_geometry = by_name["dictation-error-ime-open"]
    if (error_geometry.get("inlineDictationPhase") != "idle"
            or error_geometry.get("inlineDictationTone") != "error"
            or error_geometry.get("inlineDictationPreview") != ""):
        raise ExtractionFailure("recognizer error left a preview visible in the dock")
    attach_geometry = by_name["dictation-reattached-ime-open"]
    attach = journey["dictationAttachCancel"]
    assert isinstance(attach, dict)
    if (attach_geometry.get("inlineDictationTargetKey") != attach.get("newTargetKey")
            or attach_geometry.get("androidIme", {}).get("visible") is not True
            or attach_geometry.get("runtimeGeometry", {}).get("rows", 0) < 5):
        raise ExtractionFailure("reattached dock does not expose its new target with the IME and five terminal rows")
    grid_anchor = by_name["dictation-idle-ime-open"].get("runtimeGeometry")
    if not isinstance(grid_anchor, dict):
        raise ExtractionFailure("dictation journey lacks its initial live PTY grid")
    idle_viewport = by_name["dictation-idle-ime-open"].get("terminalViewport")
    if not isinstance(idle_viewport, dict):
        raise ExtractionFailure("dictation journey lacks its initial keyboard-up terminal viewport")
    idle_viewport_height = idle_viewport.get("height")
    if (isinstance(idle_viewport_height, bool)
            or not isinstance(idle_viewport_height, (int, float))):
        raise ExtractionFailure("dictation journey lacks its measured initial viewport height")
    expected_viewport_cap = min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX, math.floor(idle_viewport_height))
    ready = by_name["dictation-ready-ime-open"]
    ready_grid = ready.get("runtimeGeometry")
    ready_viewport = ready.get("terminalViewport")
    setup_resize_acks = ready.get("resizeAcks", 0) - by_name["dictation-idle-ime-open"].get("resizeAcks", 0)
    if (not isinstance(ready_grid, dict)
            or (ready_grid.get("cols"), ready_grid.get("rows")) != (grid_anchor.get("cols"), grid_anchor.get("rows"))
            or not isinstance(ready_viewport, dict)
            or abs(ready_viewport.get("height", 0) - idle_viewport.get("height", 0)) > 0.5
            or setup_resize_acks < 0
            or dictation.get("receiverSetupResizeAcks") != setup_resize_acks
            or dictation.get("resizeAcksAtStableBaseline") != ready.get("resizeAcks")):
        raise ExtractionFailure("host receiver setup did not settle back to the original PTY grid and viewport baseline")
    fit_events = ready.get("resizeFitEvents")
    ack_events = ready.get("resizeAckEvents")
    if not isinstance(fit_events, list) or not isinstance(ack_events, list) or len(ack_events) != setup_resize_acks:
        raise ExtractionFailure("receiver setup resize ACKs lack a complete timestamped fit/ACK trace")
    fit_events_by_request = {
        event.get("requestId"): event for event in fit_events
        if isinstance(event, dict) and isinstance(event.get("requestId"), int)
        and not isinstance(event.get("requestId"), bool)
    }
    seen_ack_requests: set[int] = set()
    for event in ack_events:
        request_id = event.get("requestId") if isinstance(event, dict) else None
        fit = fit_events_by_request.get(request_id)
        if (not isinstance(event, dict) or not isinstance(request_id, int) or isinstance(request_id, bool)
                or fit is None or request_id in seen_ack_requests
                or isinstance(event.get("atMs"), bool) or not isinstance(event.get("atMs"), (int, float))
                or event["atMs"] < fit.get("atMs", 0)
                or event.get("result") != "accepted"
                or event.get("attachEpoch") != by_name["dictation-idle-ime-open"].get("sshAttachEpoch")
                or (event.get("cols"), event.get("rows")) != (fit.get("cols"), fit.get("rows"))):
            raise ExtractionFailure("receiver setup trace contains an unmatched, stale, failed, or mismatched PTY resize ACK")
        seen_ack_requests.add(request_id)
    for stage_name in ("dictation-ready-ime-open", "dictation-listening-ime-open",
                       "dictation-listening-ctrl-open-ime-open",
                       "dictation-final-awaiting-stopped", "dictation-final-inserted",
                       "dictation-post-stop-keyboard-input", "dictation-error-ime-open"):
        grid = by_name[stage_name].get("runtimeGeometry")
        active_status = by_name[stage_name].get("inlineDictationStatusVisible") is True
        if (not isinstance(grid, dict)
                or (grid.get("cols"), grid.get("rows")) != (grid_anchor.get("cols"), grid_anchor.get("rows"))
                or by_name[stage_name].get("resizeAcks") != ready.get("resizeAcks")
                or (active_status and abs(by_name[stage_name].get("terminalViewportDockCapPx", 0)
                                          - expected_viewport_cap) > 0.5)):
            raise ExtractionFailure(f"dictation state {stage_name} changed the accepted PTY grid or sent a resize")
    keyboard_grid = by_name["keyboard-up-compact-row"].get("runtimeGeometry")
    if (not isinstance(grid_anchor, dict) or not isinstance(keyboard_grid, dict)
            or (grid_anchor.get("cols"), grid_anchor.get("rows"))
            != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))):
        raise ExtractionFailure("dictation baseline changed the accepted keyboard-up PTY grid")
    for stage_name in ("dictation-reattached-ime-open", "dictation-background-cancel-resumed",
                       "after-reconnect", "reconnected-keybar-ime-up"):
        grid = by_name[stage_name].get("runtimeGeometry")
        if (not isinstance(grid, dict) or not isinstance(keyboard_grid, dict)
                or (grid.get("cols"), grid.get("rows")) != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))):
            raise ExtractionFailure(f"reattached state {stage_name} changed the accepted keyboard-up PTY grid")
    keyboard = by_name["keyboard-up-compact-row"]
    if keyboard.get("keyboardVisible") is not True:
        raise ExtractionFailure("keyboard-up DOM geometry says the keyboard is hidden")
    keyboard_viewport = keyboard.get("terminalViewport")
    if not isinstance(keyboard_viewport, dict) or not isinstance(keyboard_grid, dict):
        raise ExtractionFailure("keyboard-up baseline lacks its measured viewport or xterm grid")
    accepted_keyboard_cap = min(
        ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX,
        math.floor(keyboard_viewport.get("height", 0)),
    )
    if abs(keyboard.get("terminalViewportDockCapPx", 0) - accepted_keyboard_cap) > 0.5:
        raise ExtractionFailure("keyboard-up baseline did not establish the min(144px, measured viewport) cap")
    if (journey.get("androidApi") == 35
            and accepted_keyboard_cap == ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX
            and (keyboard_grid.get("cols"), keyboard_grid.get("rows")) != API35_ACCEPTED_TERMINAL_GRID):
        raise ExtractionFailure("API 35 keyboard-up baseline must lock the accepted 144px / 38×6 terminal grid")
    for stage_name in ("after-navigation-row-taps", "before-fast-keys", "fast-keys-main-open-ime-up",
                       "fast-keys-ctrl-open-ime-up", "fast-keys-closed-ime-up"):
        grid = by_name[stage_name].get("runtimeGeometry")
        if (not isinstance(grid, dict)
                or (grid.get("cols"), grid.get("rows")) != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))):
            raise ExtractionFailure(f"keyboard-up stage {stage_name} changed the accepted PTY grid")
    ime = keyboard.get("androidIme")
    targets = keyboard.get("navigationTargets")
    if not isinstance(ime, dict) or ime.get("visible") is not True or ime.get("imeBottomDp", 0) <= 0:
        raise ExtractionFailure("keyboard-up geometry lacks a visible native IME inset")
    if not isinstance(targets, list) or len(targets) != 4:
        raise ExtractionFailure("keyboard-up geometry does not contain the three quick keys and launcher")
    for target in targets:
        if not isinstance(target, dict) or target.get("height", 0) < 47.9 or target.get("width", 0) < 47.9:
            raise ExtractionFailure("a keyboard-up action target is smaller than 48dp")
        if target.get("insideViewport") is not True or target.get("disabled") is not False:
            raise ExtractionFailure("a keyboard-up action target is offscreen or disabled")
        if not target.get("label"):
            raise ExtractionFailure("a keyboard-up action target has no accessibility label")

    after_navigation = by_name["after-navigation-row-taps"]
    if after_navigation.get("keyboardVisible") is not True or after_navigation.get("androidIme", {}).get("visible") is not True:
        raise ExtractionFailure("tapping compact navigation keys dismissed the real IME")

    catalogs = journey.get("catalogReachability")
    if not isinstance(catalogs, dict):
        raise ExtractionFailure("journey evidence is missing full catalog reachability")
    main_keys = catalogs.get("mainKeys")
    ctrl_keys = catalogs.get("ctrlKeys")
    if not isinstance(main_keys, list) or [key.get("keyId") if isinstance(key, dict) else None for key in main_keys] != list(EXPECTED_MAIN_KEY_IDS):
        raise ExtractionFailure("main fast-key catalog is incomplete or out of shared-core order")
    if not isinstance(ctrl_keys, list) or [key.get("keyId") if isinstance(key, dict) else None for key in ctrl_keys] != list(EXPECTED_CTRL_KEY_IDS):
        raise ExtractionFailure("QWERTY Ctrl catalog is incomplete or out of shared-core order")
    for label, keys in (("main", main_keys), ("Ctrl", ctrl_keys)):
        for key in keys:
            if (not isinstance(key, dict)
                    or key.get("insideContent") is not True
                    or key.get("insideViewport") is not True
                    or isinstance(key.get("width"), bool)
                    or not isinstance(key.get("width"), (int, float))
                    or key["width"] < 47.9
                    or isinstance(key.get("height"), bool)
                    or not isinstance(key.get("height"), (int, float))
                    or key["height"] < 47.9):
                key_id = key.get("keyId") if isinstance(key, dict) else "unknown"
                raise ExtractionFailure(f"{label} catalog key {key_id} lacks a visible 48dp target measurement")

    before = by_name["before-fast-keys"]
    opened = by_name["fast-keys-main-open-ime-up"]
    ctrl = by_name["fast-keys-ctrl-open-ime-up"]
    closed = by_name["fast-keys-closed-ime-up"]
    if opened.get("keyboardVisible") is not True:
        raise ExtractionFailure("main fast-key tray geometry does not show the visible IME")
    for label, item in (("main tray", opened), ("Ctrl tray", ctrl), ("closed tray", closed)):
        tray = item.get("fastKeysTray")
        if (not isinstance(tray, dict)
                or tray.get("insideSlot") is not True
                or tray.get("insideTerminalPanel") is not True
                or tray.get("belowTerminalViewport") is not True
                or tray.get("intersectsTerminalViewport") is not False
                or tray.get("intersectsComposerPanel") is not False):
            raise ExtractionFailure(f"{label} is not contained by the terminal panel and outside the terminal viewport")
        if (item.get("inlineDictationBar") is not None
                and item.get("inlineDictationBarInsideTray") is not True):
            raise ExtractionFailure(f"{label} has a separate dictation row outside the persistent dock")
    main_bounds = opened["fastKeysTray"].get("bounds", {})
    ctrl_bounds = ctrl["fastKeysTray"].get("bounds", {})
    closed_bounds = closed["fastKeysTray"].get("bounds", {})
    if any(not isinstance(bounds, dict) for bounds in (main_bounds, ctrl_bounds, closed_bounds)):
        raise ExtractionFailure("docked tray geometry is missing bounds")
    if (abs(main_bounds.get("height", 0) - (MOBILE_HOTKEYS_BASE_HEIGHT_PX + CATALOG_SHEET_HEIGHT_PX)) > 0.5
            or abs(closed_bounds.get("height", 0) - MOBILE_HOTKEYS_BASE_HEIGHT_PX) > 0.5
            or abs(ctrl_bounds.get("height", 0) - (MOBILE_HOTKEYS_BASE_HEIGHT_PX + CATALOG_SHEET_HEIGHT_PX)) > 0.5):
        raise ExtractionFailure("fast-key tray did not reserve both 48px rows and the 144px in-flow catalog")
    for label, item in (("main", opened), ("Ctrl", ctrl)):
        sheet = item.get("catalogSheet")
        page_action = item.get("catalogPageAction")
        scroll = item.get("catalogScrollMetrics")
        if (not isinstance(sheet, dict)
                or abs(sheet.get("height", 0) - 144) > 0.5
                or item.get("catalogScrollerInsideSheet") is not True
                or item.get("catalogSheetModal") != "false"
                or item.get("catalogSheetBelowTerminalViewport") is not True
                or item.get("catalogSheetIntersectsComposer") is not False):
            raise ExtractionFailure(f"{label} catalog is missing its bounded nonmodal sheet geometry")
        slot = item.get("terminalSlot")
        if (not isinstance(slot, dict)
                or sheet.get("top", -1) < slot.get("top", 0) - 0.5
                or sheet.get("bottom", 10**9) > slot.get("bottom", 0) + 0.5):
            raise ExtractionFailure(f"{label} catalog sheet escaped the terminal control lane")
        if (not isinstance(page_action, dict)
                or page_action.get("insideCatalogSheet") is not True
                or page_action.get("insideViewport") is not True
                or page_action.get("width", 0) < 47.9
                or page_action.get("height", 0) < 47.9):
            raise ExtractionFailure(f"{label} catalog page action is not a visible 48dp sheet target")
        expected_title = "Terminal keys" if label == "main" else "Ctrl keys"
        title = item.get("catalogTitle")
        if (not isinstance(title, dict) or title.get("text") != expected_title or title.get("fits") is not True
                or item.get("catalogHeaderControlsDoNotOverlap") is not True):
            raise ExtractionFailure(f"{label} catalog title, optional dictation line, and page action do not fit the header")
        expected_scroller = ".mobile-hotkeys__main-keys" if label == "main" else ".mobile-hotkeys__ctrl-grid"
        if item.get("catalogScrollerSelector") != expected_scroller:
            raise ExtractionFailure(f"{label} catalog evidence does not measure {expected_scroller}")
        if (not isinstance(scroll, dict)
                or scroll.get("scrollWidth", 0) > scroll.get("clientWidth", 0) + 1):
            raise ExtractionFailure(f"{label} key targets overflow the compact catalog width")
        if label == "Ctrl":
            if (scroll.get("axis") != "vertical"
                    or scroll.get("scrollHeight", 0) <= scroll.get("clientHeight", 0) + 1):
                raise ExtractionFailure("Ctrl catalog does not prove physical vertical reachability")
        elif (abs(scroll.get("clientHeight", 0) - 96) > 1
              or scroll.get("axis") != "grid"
              or scroll.get("scrollHeight", 0) > scroll.get("clientHeight", 0) + 1):
            raise ExtractionFailure("the main catalog does not fit exactly two visible 48dp grid rows")
    before_grid = before.get("runtimeGeometry")
    if not isinstance(before_grid, dict):
        raise ExtractionFailure("fast-key open comparison lacks the initial xterm dimensions")
    if (not isinstance(keyboard_grid, dict)
            or (before_grid.get("cols"), before_grid.get("rows"))
            != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))):
        raise ExtractionFailure("pre-fast-key geometry changed the accepted keyboard-up xterm grid")
    baseline_viewport = before.get("terminalViewport")
    if not isinstance(baseline_viewport, dict):
        raise ExtractionFailure("fast-key open comparison lacks the pre-dock terminal viewport height")
    baseline_viewport_height = baseline_viewport.get("height")
    if (isinstance(baseline_viewport_height, bool)
            or not isinstance(baseline_viewport_height, (int, float))):
        raise ExtractionFailure("fast-key open comparison lacks a measured terminal viewport height")
    expected_viewport_cap = min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX,
                                math.floor(baseline_viewport_height))
    for label, item in (("main open", opened), ("Ctrl open", ctrl), ("close", closed)):
        grid = item.get("runtimeGeometry")
        if not isinstance(grid, dict):
            raise ExtractionFailure(f"{label} geometry lacks xterm dimensions")
        if (before_grid.get("cols"), before_grid.get("rows")) != (grid.get("cols"), grid.get("rows")):
            raise ExtractionFailure(f"fast-key {label} changed terminal cell geometry")
        if before.get("resizeAcks") != item.get("resizeAcks"):
            raise ExtractionFailure(f"fast-key {label} sent an SSH PTY resize")
    for label, item in (("main", opened), ("Ctrl", ctrl)):
        if abs(item.get("terminalViewportDockCapPx", 0) - expected_viewport_cap) > 0.5:
            raise ExtractionFailure(f"fast-key {label} cap differs from the pre-dock viewport height")
    for label in ("fast-keys-main-catalog-reachable", "fast-keys-ctrl-catalog-reachable"):
        item = by_name[label]
        grid = item.get("runtimeGeometry")
        if (not isinstance(grid, dict)
                or (before_grid.get("cols"), before_grid.get("rows")) != (grid.get("cols"), grid.get("rows"))
                or before.get("resizeAcks") != item.get("resizeAcks")
                or abs(item.get("terminalViewportDockCapPx", 0) - expected_viewport_cap) > 0.5):
            raise ExtractionFailure(f"scrolling the {label.removeprefix('fast-keys-').removesuffix('-catalog-reachable')} catalog changed the accepted PTY grid or sent a resize")
    ctrl_composer = ctrl.get("composerPanel")
    if not isinstance(ctrl_composer, dict) or ctrl_composer.get("height", 999) > 104.1:
        raise ExtractionFailure("IME-up Ctrl tray did not compact the composer to 104dp or less")
    main_composer = opened.get("composerPanel")
    if not isinstance(main_composer, dict) or main_composer.get("height", 999) > 104.1:
        raise ExtractionFailure("IME-up main tray did not compact the composer to 104dp or less")
    if ctrl.get("runtimeGeometry", {}).get("rows", 0) < 5:
        raise ExtractionFailure("IME-up Ctrl tray leaves fewer than five xterm rows")
    if opened.get("runtimeGeometry", {}).get("rows", 0) < 5:
        raise ExtractionFailure("IME-up main tray leaves fewer than five xterm rows")
    validate_composer_actions(
        by_name["fast-keys-main-composer-actions-ime-up"],
        "IME-up main catalog with staged draft",
        require_all_enabled=True,
    )
    validate_composer_actions(ctrl, "IME-up Ctrl catalog", require_all_enabled=False)
    validate_composer_actions(
        by_name["dictation-listening-ctrl-open-ime-open"],
        "IME-up Ctrl catalog while inline dictation is listening",
        require_all_enabled=False,
    )

    main_reachable = by_name["fast-keys-main-catalog-reachable"]
    ctrl_reachable = by_name["fast-keys-ctrl-catalog-reachable"]
    for label, item in (("main", main_reachable), ("Ctrl", ctrl_reachable)):
        tray = item.get("fastKeysTray")
        if (not isinstance(tray, dict)
                or tray.get("intersectsTerminalViewport") is not False
                or tray.get("intersectsComposerPanel") is not False):
            raise ExtractionFailure(f"scrolled {label} catalog overlaps terminal text or the composer")
        if (item.get("inlineDictationBar") is not None
                and item.get("inlineDictationBarInsideTray") is not True):
            raise ExtractionFailure(f"scrolled {label} catalog has a separate dictation row outside the dock")

    dismissed = by_name["fast-keys-open-ime-dismissed"]
    if dismissed.get("androidIme", {}).get("visible") is not False:
        raise ExtractionFailure("first Android Back did not dismiss the real IME")
    dismissed_tray = dismissed.get("fastKeysTray")
    if (not isinstance(dismissed_tray, dict)
            or dismissed_tray.get("belowTerminalViewport") is not True
            or dismissed_tray.get("intersectsTerminalViewport") is not False
            or dismissed_tray.get("intersectsComposerPanel") is not False
            or dismissed.get("sshPhase") != "live"):
        raise ExtractionFailure("the docked tray overlapped the composer or terminal, or the live session was lost while dismissing the IME")
    if (dismissed.get("inlineDictationBar") is not None
            and dismissed.get("inlineDictationBarInsideTray") is not True):
        raise ExtractionFailure("the dictation row is separate from the persistent dock after IME dismissal")
    resumed = by_name["reconnected-keybar-ime-up"]
    if resumed.get("sshPhase") != "live" or resumed.get("keyboardVisible") is not True:
        raise ExtractionFailure("hotkeys did not return in the resumed live session")
    reattached = by_name["after-reconnect"]
    if reattached.get("sshPhase") != "live" or reattached.get("keyboardVisible") is not True:
        raise ExtractionFailure("reattached terminal geometry does not show the live keyboard-up workspace")
    if reattached.get("keyboardComposerMode") is not True:
        raise ExtractionFailure("reattach evidence does not preserve prompt focus after the early tap")
    if reattached.get("androidIme", {}).get("visible") is not True:
        raise ExtractionFailure("reattached terminal geometry lacks a visible native IME")
    reattached_grid = reattached.get("runtimeGeometry")
    if not isinstance(reattached_grid, dict) or reattached_grid.get("rows", 0) < 5:
        raise ExtractionFailure("reattached terminal retains fewer than five xterm rows with the terminal-focused IME")
    if not isinstance(reattached_grid.get("cellHeight"), (int, float)) or reattached_grid["cellHeight"] <= 0:
        raise ExtractionFailure("reattached xterm geometry lacks a positive measured cell height")
    lost = by_name["after-reconnect-loss"]
    if (lost.get("sshPhase") != "idle"
            or lost.get("mobileHotkeys") is not None
            or lost.get("navigationTargets") != []
            or lost.get("hotkeyControls") != []):
        raise ExtractionFailure("live-only hotkey controls remain after the reattached session is lost")


def extract(log_path: Path, output_dir: Path, run_id: str) -> None:
    assets = parse_assets(log_path.read_text(encoding="utf-8", errors="replace"), run_id)
    output_dir.mkdir(parents=True, exist_ok=True)
    for name, payload in assets.items():
        (output_dir / name).write_bytes(payload)
        print(f"PASS: extracted {name} ({len(payload)} bytes)")
    journey = json.loads(assets["fastkeys-journey.json"])
    timing_summary = format_timing_summary(journey)
    (output_dir / "fastkeys-timing-summary.txt").write_text(timing_summary, encoding="utf-8")
    print(f"PASS: {timing_summary.strip().replace(chr(10), '; ')}")
    print(f"PASS: same-run API 35 IME, Back, docked tray, non-overlap and write trace evidence ({run_id})")


def self_test() -> int:
    samples = [
        ("complete key action stream accepted", {**sample_journey(), "androidApi": 35}, True),
        ("API 35 keyboard-up baseline locks 144px and the 38x6 PTY grid", sample_journey(), True),
        ("API 35 144px viewport with a 38x7 PTY grid rejected",
         with_api35_extra_keyboard_row(sample_journey()), False),
        ("API 35 172px keyboard-up viewport cap rejected",
         with_api35_expanded_keyboard_viewport(sample_journey()), False),
        ("missing IME proof rejected", {**sample_journey(), "geometryTrace": []}, False),
        ("reattach focus race without an early tap rejected",
         {**sample_journey(), "reattachEarlyPromptTapWhileHeld": {"activeElement": "BODY"}}, False),
        ("prompt focus before attach resize gate rejected",
         {**sample_journey(), "reattachEarlyPromptTapWhileHeld": {
             **sample_journey()["reattachEarlyPromptTapWhileHeld"],
             "focusEvents": [{"type": "focusin", "atMs": 110, "target": {"testId": "prompt-draft"}}],
         }}, False),
        ("single Ctrl hold split into extra write rejected",
         {**sample_journey(), "firstHotkeyWrites": expected_first_writes()[:7] + [
             {"key": "ctrl-c", "bytes": [0x03]}, {"key": "ctrl-c", "bytes": [0x03]},
         ] + expected_first_writes()[8:]}, False),
        ("terminal overlap from a docked fast-key tray rejected",
         with_intersecting_tray(sample_journey()), False),
        ("terminal viewport contact within the 0.01px CSS rounding epsilon accepted",
         with_terminal_viewport_overlap(sample_journey(), 0.005), True),
        ("terminal viewport overlap greater than 0.01px CSS rejected",
         with_terminal_viewport_overlap(sample_journey(), 0.0101), False),
        ("catalog sheet composer overlap rejected",
         with_intersecting_catalog_sheet(sample_journey()), False),
        ("Ctrl catalog without vertical physical scroll range rejected",
         with_non_scrollable_catalog_sheet(sample_journey()), False),
        ("clipped catalog title rejected",
         with_clipped_catalog_title(sample_journey()), False),
        ("overlapping catalog header content rejected",
         with_overlapping_catalog_header(sample_journey()), False),
        ("composer overlap from a docked fast-key tray rejected",
         with_intersecting_composer(sample_journey()), False),
        ("separate dictation row outside the dock rejected",
         with_separate_dictation_row(sample_journey()), False),
        ("missing integrated dictation mic rejected",
         with_missing_dictation_mic(sample_journey()), False),
        ("visible inline mic caption rejected",
         with_visible_dictation_caption(sample_journey()), False),
        ("mic separated from the persistent control cluster rejected",
         with_far_right_dictation_mic(sample_journey()), False),
        ("missing Kotlin Enter divider rejected",
         with_missing_enter_divider(sample_journey()), False),
        ("live-width toolbar overflow rejected",
         with_live_row_overflow(sample_journey()), False),
        ("Back layering without the native IME rejected",
         with_invalid_back_layer_precondition(sample_journey(), keyboard_visible=False), False),
        ("Back layering with the dock closed rejected",
         with_invalid_back_layer_precondition(sample_journey(), palette_page="closed"), False),
        ("persistent navigation row missing while the catalog is open rejected",
         with_missing_palette_navigation(sample_journey()), False),
        ("unfrozen terminal viewport while catalog is open rejected",
         with_uncapped_catalog_viewport(sample_journey()), False),
        ("terminal slot without full dock capacity rejected",
         with_under_reserved_terminal_slot(sample_journey()), False),
        ("fractional dock overflow past the terminal panel clip rejected",
         with_fractional_terminal_panel_overflow(sample_journey()), False),
        ("Ctrl-listening terminal slot extending 10.333px past its panel rejected",
         with_ctrl_listening_terminal_slot_outside_panel(sample_journey()), False),
        ("catalog scroller contact within the 0.01px CSS rounding epsilon accepted",
         with_fractional_catalog_scroller_overflow(sample_journey(), 0.005), True),
        ("catalog scroller overflow above the 0.01px CSS rounding epsilon rejected",
         with_fractional_catalog_scroller_overflow(sample_journey(), 0.0101), False),
        ("fractional catalog scroller overflow past the sheet rejected",
         with_fractional_catalog_scroller_overflow(sample_journey()), False),
        ("composer action row without the 4px IME viewport gap rejected",
         with_composer_action_gap(sample_journey()), False),
        ("Ctrl catalog during dictation without the 4px composer action gap rejected",
         with_ctrl_dictation_action_gap(sample_journey()), False),
        ("status composer action row without its 2px editor gap rejected",
         with_short_status_action_gap(sample_journey()), False),
        ("status Send target without 4px IME clearance rejected",
         with_status_send_target_ime_gap(sample_journey()), False),
        ("status composer with less than a 25px editor rejected",
         with_short_status_composer_draft(sample_journey()), False),
        ("dictation status shrinking the pre-dock viewport cap rejected",
         with_status_cap_reclaim(sample_journey()), False),
        ("Ctrl catalog and listening status reserve the full dock height",
         sample_journey(), True),
        ("traced receiver setup resizes settle before the stable dictation baseline",
         with_receiver_setup_resize_events(sample_journey()), True),
        ("unmatched receiver setup resize ACK rejected",
         with_unmatched_receiver_setup_ack(sample_journey()), False),
        ("PTY resize ACK after the stable dictation baseline rejected",
         with_dictation_resize_after_baseline(sample_journey()), False),
        ("undersized integrated mic target rejected",
         with_undersized_dictation_mic(sample_journey()), False),
        ("active dictation status row missing rejected",
         with_missing_active_status_row(sample_journey()), False),
        ("partial dictation write rejected",
         with_partial_dictation_write(sample_journey()), False),
        ("duplicate native recognizer rejected",
         with_duplicate_dictation_recognizer(sample_journey()), False),
        ("dictation target without attach epoch rejected",
         with_missing_dictation_attach_epoch(sample_journey()), False),
        ("post-Stop keyboard text entering the prompt draft rejected",
         with_post_stop_draft_input(sample_journey()), False),
        ("post-Stop keyboard text missing from the xterm input path rejected",
         {**sample_journey(), "dictation": {
             **sample_journey()["dictation"],
             "postStopInputChunks": [],
         }}, False),
        ("post-Stop keyboard focus leaving the terminal rejected",
         with_post_stop_terminal_focus_lost(sample_journey()), False),
        ("post-Stop terminal grid change rejected",
         with_changed_post_stop_grid(sample_journey()), False),
        ("late attach result write rejected",
         with_late_attach_dictation_write(sample_journey()), False),
        ("recognizer error insertion rejected",
         with_dictation_error_write(sample_journey()), False),
        ("incomplete main catalog reachability rejected",
         with_missing_main_key(sample_journey()), False),
        ("undersized Ctrl catalog target rejected",
         with_undersized_ctrl_key(sample_journey()), False),
        ("resized PTY on fast-key open rejected",
         with_changed_tray_resize(sample_journey()), False),
        ("fractional terminal viewport cap rounds down",
         with_fractional_viewport_baselines(sample_journey()), True),
        ("ceil-rounded fast-key cap rejected for fractional viewport",
         with_ceil_cap_for_fractional_viewport(sample_journey(), "fast-keys-main-open-ime-up"), False),
        ("ceil-rounded dictation cap rejected for fractional viewport",
         with_ceil_cap_for_fractional_viewport(sample_journey(), "dictation-listening-ime-open"), False),
        ("stale live controls after reattached-session loss rejected",
         with_live_hotkeys_after_reconnect_loss(sample_journey()), False),
    ]
    for timing_name in TIMING_FIELDS:
        missing_timing = sample_journey()
        missing_timing.pop(timing_name)
        samples.append((f"missing {timing_name} rejected", missing_timing, False))
        zero_timing = sample_journey()
        zero_timing[timing_name] = 0
        samples.append((f"zero {timing_name} rejected", zero_timing, False))
    failures = 0
    for label, journey, expected in samples:
        try:
            validate_journey(journey)
            passed = True
        except ExtractionFailure:
            passed = False
        if passed != expected:
            print(f"FAIL: self-test {label}", file=sys.stderr)
            failures += 1
        else:
            print(f"PASS: self-test {label}")
    expected_summary = (
        "connect_to_prompt_ms=1100\n"
        "tap_to_visible_output_ms=80\n"
        "reconnect_tap_to_visible_output_ms=45\n"
    )
    if format_timing_summary(sample_journey()) != expected_summary:
        print("FAIL: self-test timing summary lost a measured millisecond field", file=sys.stderr)
        failures += 1
    else:
        print("PASS: self-test timing summary retains all three same-run millisecond fields")
    return 1 if failures else 0


def sample_journey() -> dict[str, object]:
    base = {
        "runtimeGeometry": {"cols": 38, "rows": 6, "cellHeight": 22.6},
        "visibleTerminalRows": 6,
        "terminalInputAcks": 3,
        "hotkeyWrites": [],
        "resizeAcks": 4,
        "resizeFitEvents": [],
        "resizeAckEvents": [],
        "keyboardVisible": True,
        "sshPhase": "live",
        "terminalSlot": {"top": 64, "bottom": 258, "left": 0, "right": 400, "width": 400, "height": 194},
        "terminalViewport": {"top": 64, "bottom": 208, "left": 0, "right": 400, "width": 400, "height": 144},
        "terminalPanel": {"top": 48, "bottom": 252, "left": 0, "right": 400, "width": 400, "height": 204},
        "terminalSlotInsideTerminalPanel": True,
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 49,
        "mobileHotkeys": {"top": 208, "bottom": 257, "left": 0, "right": 400, "width": 400, "height": 49},
        "catalogSheet": None,
        "catalogSheetModal": None,
        "catalogSheetBelowTerminalViewport": False,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": None,
        "catalogScrollMetrics": None,
        "catalogScrollerBounds": None,
        "catalogScrollerInsideSheet": False,
        "catalogScrollerSelector": None,
        "catalogTitle": None,
        "catalogHeader": None,
        "catalogHeaderControlsDoNotOverlap": False,
        "dictationSheetHeader": None,
        "inlineDictationStatusInsideSheetHeader": False,
        "visualViewport": {"height": 520, "width": 400, "offsetTop": 0},
        "fastKeysTray": {
            "insideSlot": True,
            "insideTerminalPanel": True,
            "belowTerminalViewport": True,
            "intersectsTerminalViewport": False,
            "intersectsComposerPanel": False,
            "bounds": {"height": 49},
        },
        "fastKeysPage": "closed",
        "keyboardComposerMode": True,
        "sshAttachEpoch": 2,
        "inlineDictationBar": {"top": 208, "bottom": 257, "left": 0, "right": 400, "width": 400, "height": 49},
        "inlineDictationMic": {
            "label": "Dictate to terminal", "top": 208, "bottom": 256, "left": 235, "right": 283,
            "width": 48, "height": 48, "insideViewport": True, "disabled": False, "micState": "idle",
            "visibleLabel": "",
        },
        "persistentRowMetrics": {"clientWidth": 400, "scrollWidth": 384, "scrollLeft": 0, "scrollable": False},
        "inlineDictationBarCount": 1,
        "inlineDictationMicCount": 1,
        "inlineDictationTargetKey": "testuser@fixture:22/first/attach-2",
        "inlineDictationPhase": "idle",
        "inlineDictationTone": "quiet",
        "inlineDictationStatusText": "",
        "inlineDictationPreview": "",
        "inlineDictationStatusRow": None,
        "inlineDictationStatusVisible": False,
        "inlineDictationStatusOneLine": False,
        "inlineDictationStatusInsideBar": False,
        "inlineDictationStatusAboveKeybar": False,
        "enterDivider": {"top": 220, "bottom": 244, "left": 120, "right": 121, "width": 1, "height": 24},
        "inlineDictationMicInsideBar": True,
        "inlineDictationBarInsideTray": True,
        "androidIme": {"visible": True, "imeBottomDp": 260},
        "navigationTargets": [
            {"label": label, "top": 208, "bottom": 256, "left": left, "right": left + 48,
             "width": 48, "height": 48, "insideViewport": True, "disabled": False}
            for label, left in zip(
                ("Send Up arrow", "Send Down arrow", "Send Enter", "More terminal keys"),
                (8, 64, 129, 185),
            )
        ],
        "hotkeyControls": [
            {"testId": "mobile-hotkeys"},
            {"keyId": "arrow-up", "disabled": False},
        ],
    }
    dismissed = {**base, "keyboardVisible": False, "androidIme": {"visible": False, "imeBottomDp": 0}}
    action_panel = {"top": 427, "bottom": 516, "left": 0, "right": 400, "width": 400, "height": 89}
    action_row = {"top": 468, "bottom": 516, "left": 0, "right": 400, "width": 400, "height": 48}
    composer_actions = [
        {"action": name, "label": label, "testId": test_id, "text": label, "top": 468, "bottom": 516,
         "left": left, "right": left + 48, "width": 48, "height": 48, "disabled": disabled,
         "insideViewport": True, "insideComposerPanel": True, "hitTarget": not disabled}
        for name, label, test_id, left, disabled in (
            ("discard", "Discard", "composer-discard", 0, True),
            ("dictate", "Dictate", "composer-dictate", 88, False),
            ("insert", "Insert", "composer-insert", 176, True),
            ("send", "Send", "", 264, True),
        )
    ]
    main_open = {
        **base,
        "homeSurface": "live",
        "keyboardComposerMode": True,
        "fastKeysPage": "main",
        "terminalSlot": {"top": 64, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 331},
        "mobileHotkeys": {"top": 203, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 192},
        "catalogSheet": {"top": 251, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 144},
        "catalogSheetModal": "false",
        "catalogSheetBelowTerminalViewport": True,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": {"label": "Open Ctrl plus letter keys", "width": 48, "height": 48,
            "insideViewport": True, "insideCatalogSheet": True},
        "catalogTitle": {"text": "Terminal keys", "fits": True, "width": 80, "height": 18},
        "catalogHeader": {"top": 251, "bottom": 299, "left": 0, "right": 400, "width": 400, "height": 48},
        "catalogHeaderControlsDoNotOverlap": True,
        "catalogScrollerSelector": ".mobile-hotkeys__main-keys",
        "catalogScrollMetrics": {"clientWidth": 400, "scrollWidth": 400, "scrollLeft": 0,
            "clientHeight": 96, "scrollHeight": 96, "scrollTop": 0, "axis": "grid"},
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 192}},
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 192,
        "inlineDictationBar": {**base["inlineDictationBar"], "top": 203, "bottom": 395, "height": 192},
        "inlineDictationMic": {**base["inlineDictationMic"], "left": 235, "right": 283},
        "navigationTargets": [
            *base["navigationTargets"][:3],
            {**base["navigationTargets"][3], "label": "Close terminal keys", "left": 185, "right": 233},
        ],
        "composerPanel": action_panel,
        "composerActionRow": action_row,
        "composerActions": composer_actions,
    }
    dictation_idle = {
        **base,
        "visibleTerminalRows": 6,
        "terminalViewport": {"top": 64, "bottom": 208, "left": 0, "right": 400, "width": 400, "height": 144},
    }
    dismissed = {**main_open, "keyboardVisible": False, "androidIme": {"visible": False, "imeBottomDp": 0}}
    ctrl = {
        **base,
        "fastKeysPage": "ctrl",
        "terminalSlot": {"top": 64, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 331},
        "mobileHotkeys": {"top": 203, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 192},
        "catalogSheet": {"top": 251, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 144},
        "catalogSheetModal": "false",
        "catalogSheetBelowTerminalViewport": True,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": {"label": "Back to terminal hotkeys", "width": 48, "height": 48,
            "insideViewport": True, "insideCatalogSheet": True},
        "catalogTitle": {"text": "Ctrl keys", "fits": True, "width": 60, "height": 18},
        "catalogHeader": {"top": 251, "bottom": 299, "left": 0, "right": 400, "width": 400, "height": 48},
        "catalogHeaderControlsDoNotOverlap": True,
        "catalogScrollerSelector": ".mobile-hotkeys__ctrl-grid",
        "catalogScrollMetrics": {"clientWidth": 400, "scrollWidth": 400, "scrollLeft": 0,
            "clientHeight": 96, "scrollHeight": 312, "scrollTop": 0, "axis": "vertical"},
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 192}},
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 192,
        "inlineDictationBar": {**base["inlineDictationBar"], "top": 203, "bottom": 395, "height": 192},
        "inlineDictationMic": {**base["inlineDictationMic"], "left": 235, "right": 283},
        "navigationTargets": [
            *base["navigationTargets"][:3],
            {**base["navigationTargets"][3], "label": "Close terminal keys", "left": 185, "right": 233},
        ],
        "composerPanel": action_panel,
        "composerActionRow": action_row,
        "composerActions": composer_actions,
    }
    reattached = {
        **dictation_idle,
        "keyboardComposerMode": True,
        "runtimeGeometry": {"cols": 38, "rows": 6, "cellHeight": 22.6},
    }
    dictate_text = "echo test"
    dictate_hex = dictate_text.encode("utf-8").hex()
    listening = {
        **dictation_idle,
        "inlineDictationPhase": "listening",
        "inlineDictationPreview": dictate_text,
        "inlineDictationStatusText": f"Listening · {dictate_text}",
        "inlineDictationStatusRow": {"top": 203, "bottom": 235, "left": 0, "right": 400, "width": 400, "height": 32},
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "inlineDictationStatusInsideSheetHeader": False,
        "dictationSheetHeader": None,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "label": "Stop terminal dictation", "micState": "listening",
            "top": 235, "bottom": 283},
    }
    final_pending = {
        **dictation_idle,
        "inlineDictationPhase": "stopping",
        "inlineDictationPreview": dictate_text,
        "inlineDictationStatusText": f"Transcribing · {dictate_text}",
        "inlineDictationStatusRow": listening["inlineDictationStatusRow"],
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "disabled": True, "micState": "transcribing",
            "top": 235, "bottom": 283},
    }
    final_inserted = {
        **dictation_idle,
        "inlineDictationTone": "success",
        "inlineDictationStatusText": "Inserted at the cursor. Press Enter to run.",
        "inlineDictationStatusRow": listening["inlineDictationStatusRow"],
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "top": 235, "bottom": 283},
        "terminalViewportFocused": True,
        "activeElementInsideTerminal": True,
        "activeElementIsPromptDraft": False,
        "composerDraftValue": "",
        "keyboardComposerMode": True,
    }
    post_stop = {
        **final_inserted,
        "stage": "dictation-post-stop-keyboard-input",
    }
    error = {
        **dictation_idle,
        "inlineDictationTone": "error",
        "inlineDictationStatusText": "Dictation failed: network",
        "inlineDictationStatusRow": listening["inlineDictationStatusRow"],
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "top": 235, "bottom": 283},
    }
    for status_stage in (listening, final_pending, final_inserted, post_stop, error):
        status_stage["terminalSlot"] = {
            "top": 64, "bottom": 283, "left": 0, "right": 400, "width": 400, "height": 219,
        }
        status_stage["mobileHotkeys"] = {
            "top": 203, "bottom": 283, "left": 0, "right": 400, "width": 400, "height": 80,
        }
        status_stage["navigationTargets"] = [
            {**target, "top": 235, "bottom": 283}
            for target in base["navigationTargets"]
        ]
    changed_target = "testuser@fixture:22/second/attach-3"
    attach_cancelled = {
        **base,
        "sshAttachEpoch": 3,
        "inlineDictationTargetKey": changed_target,
    }
    dictation_reattached = {
        **reattached,
        "sshAttachEpoch": 3,
        "inlineDictationTargetKey": changed_target,
    }
    dictation = {
        "requestId": "req-dictation",
        "stopRequestId": "req-dictation",
        "targetKey": base["inlineDictationTargetKey"],
        "attachEpoch": 2,
        "receiverSetupResizeAcks": 0,
        "resizeAcksAtStableBaseline": 4,
        "partialText": dictate_text,
        "finalText": dictate_text,
        "writesBeforePartial": 3,
        "writesAfterPartial": 3,
        "writesAfterStopBeforeFinal": 3,
        "writesAfterFinalBeforeStopped": 3,
        "writesAfterStopped": 4,
        "writesAfterPostStopKeyboard": 5,
        "explicitStop": True,
        "finalReceived": True,
        "stoppedReceived": True,
        "nativeStartCalls": 1,
        "nativeStopCalls": 1,
        "rawFile": "/tmp/js2884-fixture-keys-dictation.raw",
        "dictatedTextHex": dictate_hex,
        "postStopKeyboardText": "z",
        "postStopInputChunks": [{
            "text": "z", "sessionName": "first", "sessionId": "first", "sessionTag": "first",
            "attachEpoch": 2, "phase": "live",
        }],
        "postStopKeyboardDraftBefore": "",
        "postStopKeyboardDraftAfter": "",
        "postStopTerminalFocused": True,
        "expectedHostHex": (dictate_text + "z").encode("utf-8").hex(),
        "expectedByteCount": len((dictate_text + "z").encode("utf-8")),
        "expectedFinalByteCount": len(dictate_text.encode("utf-8")),
        "readyMarker": "PS2884_DICTATION_READY_fixture",
        "doneMarker": "PS2884_DICTATION_DONE_fixture",
    }
    attach_cancel = {
        "requestId": "req-attach",
        "stopRequestId": "req-attach",
        "oldTargetKey": base["inlineDictationTargetKey"],
        "newTargetKey": changed_target,
        "oldAttachEpoch": 2,
        "newAttachEpoch": 3,
        "lateResultEmitted": True,
        "stoppedEmitted": True,
        "writesBefore": 4,
        "writesAfter": 4,
        "nativeStartCalls": 3,
        "nativeStopCalls": 2,
    }
    background_cancel = {
        "requestId": "req-background",
        "stopRequestId": "req-background",
        "lateResultEmitted": True,
        "stoppedEmitted": True,
        "writesBefore": 4,
        "writesAfter": 4,
        "nativeStartCalls": 4,
        "nativeStopCalls": 3,
    }
    lost = {
        **base,
        "stage": "after-reconnect-loss",
        "keyboardVisible": False,
        "sshPhase": "idle",
        "mobileHotkeys": None,
        "navigationTargets": [],
        "hotkeyControls": [],
        "inlineDictationBar": None,
        "inlineDictationMic": None,
        "inlineDictationBarCount": 0,
        "inlineDictationMicCount": 0,
        "inlineDictationTargetKey": "",
    }
    journey = {
        "androidApi": 35,
        "narrowToolbarReachability": {
            "clientWidth": 330,
            "scrollWidth": 330,
            "maxScrollLeft": 0,
            "scrollable": False,
            "ptyWritesBefore": 2,
            "ptyWritesAfter": 2,
            "targets": [
                {"label": label, "width": 48, "height": 48, "visibleWidth": 48, "visibleHeight": 48,
                 "hitTarget": True, "insideToolbar": True, "disabled": False}
                for label in (
                    "Send Up arrow", "Send Down arrow", "Send Enter", "More terminal keys",
                    "Dictate to terminal",
                )
            ],
        },
        "connectToPromptMs": 1100,
        "tapToVisibleOutputMs": 80,
        "reconnectTapToVisibleOutputMs": 45,
        "firstDoneMarker": "PS2884_DONE_fixture",
        "resumedDoneMarker": "PS2884_RESUMED_DONE_fixture",
        "dictation": dictation,
        "dictationError": {
            "requestId": "req-error", "tone": "error", "phaseIdle": True,
            "previewCleared": True, "writesBefore": 4, "writesAfter": 4, "nativeStartCalls": 2,
        },
        "dictationAttachCancel": attach_cancel,
        "dictationBackgroundCancel": background_cancel,
        "firstHotkeyWrites": expected_first_writes(),
        "allHotkeyWrites": expected_first_writes() + [{"key": "arrow-up", "bytes": [0x1B, 0x5B, 0x41]}],
        "catalogReachability": {
            "mainKeys": [
                {"keyId": key_id, "width": 48, "height": 48, "insideContent": True, "insideViewport": True}
                for key_id in EXPECTED_MAIN_KEY_IDS
            ],
            "ctrlKeys": [
                {"keyId": key_id, "width": 48, "height": 48, "insideContent": True, "insideViewport": True}
                for key_id in EXPECTED_CTRL_KEY_IDS
            ],
        },
        "reattachEarlyPromptTapWhileHeld": {
            "activeElement": "prompt-draft",
            "keyboardVisible": True,
            "androidImeVisible": True,
            "attachFocusPending": True,
            "attachEpoch": 2,
            "attachResizeAckEpoch": 1,
            "gate": {
                "entered": [
                    {"source": "terminal-enabled-watcher", "atMs": 115},
                    {"source": "attach-resize", "atMs": 120},
                ],
                "pending": 2,
                "released": False,
            },
            "focusEvents": [{"type": "focusin", "atMs": 130, "target": {"testId": "prompt-draft"}}],
        },
        "reattachEarlyPromptTapAfterAttach": {
            "activeElement": "prompt-draft",
            "keyboardVisible": True,
            "androidImeVisible": True,
            "attachFocusPending": False,
            "attachEpoch": 2,
            "attachResizeAckEpoch": 2,
            "gate": {
                "entered": [
                    {"source": "terminal-enabled-watcher", "atMs": 115},
                    {"source": "attach-resize", "atMs": 120},
                    {"source": "attach-final-focus", "atMs": 140},
                ],
                "pending": 0,
                "released": True,
            },
        },
        "geometryTrace": [
            {"stage": "keyboard-up-compact-row", **base},
            {"stage": "after-navigation-row-taps", **base},
            {"stage": "before-fast-keys", **base},
            {"stage": "fast-keys-main-open-ime-up", **main_open},
            {"stage": "fast-keys-main-composer-actions-ime-up", **main_open,
             "composerActions": [
                 {**action, "disabled": False, "hitTarget": True}
                 for action in composer_actions
             ]},
            {"stage": "fast-keys-main-catalog-reachable", **main_open},
            {"stage": "fast-keys-ctrl-open-ime-up", **ctrl},
            {"stage": "fast-keys-ctrl-catalog-reachable", **ctrl},
            {"stage": "fast-keys-closed-ime-up", **base},
            {"stage": "fast-keys-open-ime-dismissed", **dismissed},
            {"stage": "fast-keys-open-ime-up-before-back", **main_open},
            {"stage": "dictation-idle-ime-open", **dictation_idle},
            {"stage": "dictation-ready-ime-open", **dictation_idle},
            {"stage": "dictation-listening-ime-open", **listening},
            {"stage": "dictation-final-awaiting-stopped", **final_pending},
            {"stage": "dictation-final-inserted", **final_inserted},
            {"stage": "dictation-post-stop-keyboard-input", **post_stop},
            {"stage": "dictation-error-ime-open", **error},
            {"stage": "dictation-attach-cancel-complete", **attach_cancelled},
            {"stage": "dictation-reattached-ime-open", **dictation_reattached},
            {"stage": "dictation-background-cancel-resumed", **dictation_reattached},
            {"stage": "after-reconnect", **reattached},
            {"stage": "reconnected-keybar-ime-up", **dictation_idle},
            lost,
        ],
    }
    return with_android_dock_containment(with_ctrl_dictation_status(journey))


def with_android_dock_containment(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied.get("geometryTrace", []):
        if not isinstance(item, dict) or not isinstance(item.get("inlineDictationBar"), dict):
            continue
        page = item.get("fastKeysPage")
        status_visible = item.get("inlineDictationStatusVisible") is True
        viewport = item.get("terminalViewport")
        if not isinstance(viewport, dict):
            continue
        cap = item.get("terminalViewportDockCapPx")
        viewport_height = cap if isinstance(cap, (int, float)) and cap > 0 else viewport.get("height", 138)
        viewport_top = viewport.get("top", 64)
        viewport["height"] = viewport_height
        viewport["bottom"] = viewport_top + viewport_height
        if isinstance(item.get("runtimeGeometry"), dict):
            cell_height = item["runtimeGeometry"].get("cellHeight", 23.6)
            item["visibleTerminalRows"] = math.floor((viewport_height - 8) / cell_height)
        top = viewport["bottom"]
        dock_height = (MOBILE_HOTKEYS_BASE_HEIGHT_PX
                       + (CATALOG_SHEET_HEIGHT_PX if page in {"main", "ctrl"} else 0)
                       + (INLINE_DICTATION_STATUS_ROW_HEIGHT_PX if status_visible else 0))
        status_top = top + 1
        row_top = status_top + (INLINE_DICTATION_STATUS_ROW_HEIGHT_PX if status_visible else 0)
        right = 391
        left = 20
        width = right - left
        bottom = top + dock_height
        item["mobileHotkeys"] = {
            "top": top, "bottom": bottom, "left": left, "right": right,
            "width": width, "height": dock_height,
        }
        item["terminalHotkeysDockHeightPx"] = dock_height
        item["inlineDictationBar"] = {
            "top": top, "bottom": bottom, "left": left, "right": right,
            "width": width, "height": dock_height,
        }
        tray = item.get("fastKeysTray")
        if isinstance(tray, dict):
            tray["bounds"] = {
                "top": top, "bottom": bottom, "left": left, "right": right,
                "width": width, "height": dock_height,
            }
        slot = item.get("terminalSlot")
        if isinstance(slot, dict):
            slot_bottom = bottom + 1
            slot["bottom"] = slot_bottom
            slot["height"] = slot_bottom - slot.get("top", 64)
        panel = item.get("terminalPanel")
        if isinstance(panel, dict):
            panel["bottom"] = bottom + 1
            panel["height"] = panel["bottom"] - panel.get("top", 48)
        item["terminalSlotInsideTerminalPanel"] = bool(
            isinstance(slot, dict) and isinstance(panel, dict)
            and slot.get("top", -1) >= panel.get("top", 0) - 0.5
            and slot.get("bottom", 10**9) <= panel.get("bottom", 0) + 0.5
        )
        if isinstance(tray, dict):
            tray["insideTerminalPanel"] = bool(
                isinstance(panel, dict)
                and top >= panel.get("top", 0) - 0.5
                and bottom <= panel.get("bottom", 0) + 0.5
            )
        if page in {"main", "ctrl"}:
            sheet_top = row_top + 48
            item["catalogSheet"] = {
                "top": sheet_top, "bottom": bottom, "left": left, "right": right,
                "width": width, "height": 144,
            }
            item["catalogHeader"] = {
                "top": sheet_top, "bottom": sheet_top + 48,
                "left": left, "right": right, "width": width, "height": 48,
            }
            item["catalogScrollerBounds"] = {
                "top": sheet_top + 48, "bottom": bottom,
                "left": left, "right": right, "width": width, "height": bottom - sheet_top - 48,
            }
            item["catalogScrollerInsideSheet"] = True
            item["catalogScrollMetrics"]["clientWidth"] = width
            item["catalogScrollMetrics"]["scrollWidth"] = width
        if status_visible:
            item["inlineDictationStatusRow"] = {
                "top": status_top, "bottom": status_top + INLINE_DICTATION_STATUS_ROW_HEIGHT_PX,
                "left": left, "right": right, "width": width,
                "height": INLINE_DICTATION_STATUS_ROW_HEIGHT_PX,
            }
            item["inlineDictationStatusAboveKeybar"] = True
            item["inlineDictationStatusInsideSheetHeader"] = False
        item["keybarRect"] = {
            "top": row_top, "bottom": row_top + 48,
            "left": left, "right": right, "width": width, "height": 48,
        }
        item["keybarClientRect"] = item["keybarRect"].copy()
        item["navigationTargets"] = [
            {**target, "left": control_left, "right": control_left + 48,
             "top": row_top, "bottom": row_top + 48,
             "visibleWidthInKeybar": 48, "visibleHeightInKeybar": 48}
            for target, control_left in zip(item.get("navigationTargets", []),
                                            (left + 8, left + 64, left + 129, left + 185))
        ]
        item["enterDivider"] = {
            "left": left + 120, "right": left + 121,
            "top": row_top + 12, "bottom": row_top + 36,
            "width": 1, "height": 24,
        }
        item["inlineDictationMic"] = {
            **item["inlineDictationMic"], "left": left + 235, "right": left + 283,
            "top": row_top, "bottom": row_top + 48,
            "visibleWidthInKeybar": 48, "visibleHeightInKeybar": 48,
        }
        item["persistentRowMetrics"] = {
            "clientWidth": width, "scrollWidth": width, "scrollLeft": 0, "scrollable": False,
        }
        item["inlineDictationMicInsideBar"] = True
        item["inlineDictationMicInsideKeybar"] = True
        if isinstance(item.get("catalogHeader"), dict):
            item["catalogHeaderControlsDoNotOverlap"] = True
        if status_visible:
            composer_top = bottom + 1
            composer_bottom = composer_top + 80
            visual_viewport = item.get("visualViewport")
            if isinstance(visual_viewport, dict):
                visual_viewport["height"] = max(visual_viewport.get("height", 0), composer_top + 80)
            item["composerPanel"] = {
                "top": composer_top, "bottom": composer_bottom, "left": 0, "right": 400,
                "width": 400, "height": 80,
            }
            item["composerDraft"] = {
                "top": composer_top, "bottom": composer_top + 25, "left": 1, "right": 399,
                "width": 398, "height": 25,
            }
            action_row_top = composer_top + 27.5
            action_row_bottom = action_row_top + 48
            item["composerActionRow"] = {
                "top": action_row_top, "bottom": action_row_bottom, "left": 0, "right": 400,
                "width": 400, "height": 48,
            }
            item["composerActions"] = [
                {"action": action, "label": label, "testId": test_id, "text": label,
                 "top": action_row_top + (0.381 if action == "send" else 0),
                 "bottom": action_row_bottom + (0.381 if action == "send" else 0),
                 "left": action_left, "right": action_left + 48, "width": 48, "height": 48,
                 "disabled": disabled, "insideViewport": True, "insideComposerPanel": True,
                 "hitTarget": not disabled}
                for action, label, test_id, action_left, disabled in (
                    ("discard", "Discard", "composer-discard", 0, True),
                    ("dictate", "Dictate", "composer-dictate", 88, False),
                    ("insert", "Insert", "composer-insert", 176, True),
                    ("send", "Send", "", 264, True),
                )
            ]
        visual_viewport = item.get("visualViewport")
        if isinstance(visual_viewport, dict):
            item["imeEdgeCssY"] = visual_viewport.get("offsetTop", 0) + visual_viewport.get("height", 0)
    return copied


def with_changed_tray_resize(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["resizeAcks"] += 1
    return copied


def with_api35_extra_keyboard_row(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    keyboard = next(item for item in copied["geometryTrace"] if item["stage"] == "keyboard-up-compact-row")
    keyboard["runtimeGeometry"]["rows"] = 7
    return copied


def with_api35_expanded_keyboard_viewport(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    keyboard = next(item for item in copied["geometryTrace"] if item["stage"] == "keyboard-up-compact-row")
    viewport = keyboard["terminalViewport"]
    viewport["height"] = 172
    viewport["bottom"] = viewport["top"] + 172
    keyboard["terminalViewportDockCapPx"] = 172
    keyboard["runtimeGeometry"]["rows"] = 7
    keyboard["visibleTerminalRows"] = math.floor((172 - 8) / keyboard["runtimeGeometry"]["cellHeight"])
    return with_android_dock_containment(copied)


def with_fractional_viewport_baselines(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for stage_name, viewport_height in (
        ("before-fast-keys", 144.4),
        ("dictation-idle-ime-open", 144.4),
        ("dictation-ready-ime-open", 144.4),
    ):
        item = next(item for item in copied["geometryTrace"] if item["stage"] == stage_name)
        viewport = item["terminalViewport"]
        old_height = viewport["height"]
        delta = viewport_height - old_height
        viewport["height"] = viewport_height
        viewport["bottom"] = viewport["top"] + viewport_height
        cell_height = item["runtimeGeometry"]["cellHeight"]
        item["visibleTerminalRows"] = math.floor((viewport_height - 8) / cell_height)
        if abs(delta) > 0.001:
            for field in ("terminalSlot", "terminalPanel", "mobileHotkeys", "catalogSheet", "catalogHeader",
                          "catalogScrollerBounds", "inlineDictationBar", "enterDivider",
                          "inlineDictationStatusRow", "keybarRect", "keybarClientRect", "inlineDictationMic"):
                rect = item.get(field)
                if isinstance(rect, dict):
                    for edge in ("top", "bottom"):
                        if isinstance(rect.get(edge), (int, float)):
                            rect[edge] += delta
            tray_bounds = item.get("fastKeysTray", {}).get("bounds")
            if isinstance(tray_bounds, dict):
                for edge in ("top", "bottom"):
                    tray_bounds[edge] += delta
            for field in ("navigationTargets",):
                for rect in item.get(field, []):
                    if isinstance(rect, dict):
                        for edge in ("top", "bottom"):
                            if isinstance(rect.get(edge), (int, float)):
                                rect[edge] += delta
    return copied


def with_ceil_cap_for_fractional_viewport(journey: dict[str, object], stage_name: str) -> dict[str, object]:
    copied = with_fractional_viewport_baselines(journey)
    item = next(item for item in copied["geometryTrace"] if item["stage"] == stage_name)
    item["terminalViewportDockCapPx"] = 139
    return copied


def with_fractional_terminal_panel_overflow(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["terminalPanel"]["bottom"] = item["fastKeysTray"]["bounds"]["bottom"] - 0.619
    return copied


def with_ctrl_listening_terminal_slot_outside_panel(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ctrl-open-ime-open")
    panel = item["terminalPanel"]
    slot = item["terminalSlot"]
    slot["bottom"] = panel["bottom"] + 10.333
    slot["height"] = slot["bottom"] - slot["top"]
    item["terminalSlotInsideTerminalPanel"] = False
    return copied


def with_fractional_catalog_scroller_overflow(
    journey: dict[str, object], overflow_px: float = 0.619,
) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["catalogScrollerBounds"]["bottom"] = item["catalogSheet"]["bottom"] + overflow_px
    return copied


def with_receiver_setup_resize_events(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    ready = next(item for item in copied["geometryTrace"] if item["stage"] == "dictation-ready-ime-open")
    fit_events = []
    ack_events = []
    dimensions = ((38, 5), (39, 7), (38, 6))
    for index, (cols, rows) in enumerate(dimensions):
        request_id = 101 + index
        at_ms = 10.0 + index * 2
        fit_events.append({
            "atMs": at_ms, "marker": "send-receiver-command:fixture", "reason": "resize-observer",
            "cols": cols, "rows": rows, "requestId": request_id, "hostWidth": 400, "hostHeight": 138,
        })
        ack_events.append({
            "atMs": at_ms + 1, "marker": "send-receiver-command:fixture", "requestId": request_id,
            "cols": cols, "rows": rows, "attachEpoch": 2, "result": "accepted",
        })
    ready["resizeFitEvents"] = fit_events
    ready["resizeAckEvents"] = ack_events
    ready["resizeAcks"] = 7
    for item in copied["geometryTrace"]:
        if item["stage"] in {
            "dictation-ready-ime-open", "dictation-listening-ime-open", "dictation-listening-ctrl-open-ime-open",
            "dictation-final-awaiting-stopped", "dictation-final-inserted", "dictation-post-stop-keyboard-input",
            "dictation-error-ime-open",
        }:
            item["resizeAcks"] = 7
    copied["dictation"]["receiverSetupResizeAcks"] = 3
    copied["dictation"]["resizeAcksAtStableBaseline"] = 7
    return copied


def with_unmatched_receiver_setup_ack(journey: dict[str, object]) -> dict[str, object]:
    copied = with_receiver_setup_resize_events(journey)
    ready = next(item for item in copied["geometryTrace"] if item["stage"] == "dictation-ready-ime-open")
    ready["resizeAckEvents"][0]["requestId"] = 999
    return copied


def with_dictation_resize_after_baseline(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-listening-ime-open":
            item["resizeAcks"] += 1
    return copied


def with_intersecting_tray(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["fastKeysTray"]["intersectsTerminalViewport"] = True
    return copied


def with_terminal_viewport_overlap(journey: dict[str, object], overlap_px: float) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "fast-keys-main-open-ime-up")
    bounds = item["fastKeysTray"]["bounds"]
    bounds["top"] = item["terminalViewport"]["bottom"] - overlap_px
    bounds["bottom"] -= overlap_px
    return copied


def with_intersecting_composer(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["fastKeysTray"]["intersectsComposerPanel"] = True
    return copied


def with_intersecting_catalog_sheet(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["catalogSheetIntersectsComposer"] = True
    return copied


def with_non_scrollable_catalog_sheet(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["catalogScrollMetrics"]["scrollHeight"] = item["catalogScrollMetrics"]["clientHeight"]
    return copied


def with_clipped_catalog_title(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["catalogTitle"]["fits"] = False
    return copied


def with_overlapping_catalog_header(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["catalogHeaderControlsDoNotOverlap"] = False
    return copied


def with_separate_dictation_row(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["inlineDictationBar"] = {"top": 120, "bottom": 176, "left": 0, "right": 400, "width": 400, "height": 56}
            item["inlineDictationBarInsideTray"] = False
    return copied


def with_missing_dictation_mic(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["inlineDictationMic"] = None
            item["inlineDictationMicCount"] = 0
    return copied


def with_visible_dictation_caption(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationMic"]["visibleLabel"] = "Stop"
    return copied


def with_far_right_dictation_mic(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-idle-ime-open":
            item["inlineDictationMic"]["left"] = item["mobileHotkeys"]["right"] - 48
            item["inlineDictationMic"]["right"] = item["mobileHotkeys"]["right"]
    return copied


def with_missing_enter_divider(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "fast-keys-main-open-ime-up":
            item["enterDivider"] = None
    return copied


def with_live_row_overflow(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-idle-ime-open":
            item["persistentRowMetrics"]["scrollWidth"] = item["persistentRowMetrics"]["clientWidth"] + 6
            item["persistentRowMetrics"]["scrollable"] = True
    return copied


def with_invalid_back_layer_precondition(
    journey: dict[str, object], *, keyboard_visible: bool | None = None, palette_page: str | None = None,
) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] != "fast-keys-open-ime-up-before-back":
            continue
        if keyboard_visible is not None:
            item["keyboardVisible"] = keyboard_visible
            item["androidIme"]["visible"] = keyboard_visible
        if palette_page is not None:
            item["fastKeysPage"] = palette_page
    return copied


def with_missing_palette_navigation(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["navigationTargets"].pop()
    return copied


def with_missing_active_status_row(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-listening-ime-open":
            item["inlineDictationStatusRow"] = None
    return copied


def with_uncapped_catalog_viewport(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["terminalViewportDockCapPx"] = 0
    return copied


def with_under_reserved_terminal_slot(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["terminalSlot"]["height"] = 260
    return copied


def with_composer_action_gap(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-composer-actions-ime-up":
            item["composerActionRow"]["bottom"] = item["visualViewport"]["height"] - 3
    return copied


def with_ctrl_dictation_action_gap(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ctrl-open-ime-open")
    item["composerActionRow"]["bottom"] = item["visualViewport"]["height"] - 3
    return copied


def with_short_status_composer_draft(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ime-open")
    item["composerDraft"]["height"] = 24
    return copied


def with_short_status_action_gap(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ime-open")
    draft_bottom = item["composerDraft"]["bottom"]
    row_top = draft_bottom + 1.5
    row_bottom = row_top + 48
    item["composerActionRow"]["top"] = row_top
    item["composerActionRow"]["bottom"] = row_bottom
    for action in item["composerActions"]:
        action["top"] = row_top
        action["bottom"] = row_bottom
    return copied


def with_status_send_target_ime_gap(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ime-open")
    row = item["composerActionRow"]
    edge = item["imeEdgeCssY"]
    row["bottom"] = edge - 4
    row["height"] = row["bottom"] - row["top"]
    for action in item["composerActions"]:
        action["bottom"] = row["bottom"]
        action["top"] = action["bottom"] - 48
    send = next(action for action in item["composerActions"] if action["action"] == "send")
    send["bottom"] = edge - 3.619
    send["top"] = send["bottom"] - 48
    return copied


def with_status_cap_reclaim(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ime-open")
    item["terminalViewportDockCapPx"] = 138
    item["terminalViewport"]["height"] = 138
    item["terminalViewport"]["bottom"] = item["terminalViewport"]["top"] + 138
    item["visibleTerminalRows"] = math.floor((138 - 8) / item["runtimeGeometry"]["cellHeight"])
    return copied


def with_ctrl_dictation_status(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    ctrl = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-ctrl-open-ime-up")
    copied["geometryTrace"].append({
        **ctrl,
        "stage": "dictation-listening-ctrl-open-ime-open",
        "mobileHotkeys": {"top": 203, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 192},
        "catalogSheet": {"top": 251, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 144},
        "catalogSheetModal": "false",
        "catalogSheetBelowTerminalViewport": True,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": {"label": "Back to terminal hotkeys", "width": 48, "height": 48,
            "insideViewport": True, "insideCatalogSheet": True},
        "catalogScrollMetrics": {"clientWidth": 400, "scrollWidth": 400, "scrollLeft": 0,
            "clientHeight": 96, "scrollHeight": 312, "scrollTop": 0, "axis": "vertical"},
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 192,
        "fastKeysTray": {**ctrl["fastKeysTray"], "bounds": {"height": 192}},
        "terminalSlot": {"top": 64, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 331},
        "inlineDictationBar": {**ctrl["inlineDictationBar"], "top": 203, "bottom": 395, "height": 192},
        "dictationSheetHeader": {"top": 251, "bottom": 299, "left": 0, "right": 400, "width": 400, "height": 48},
        "inlineDictationPhase": "listening",
        "inlineDictationTone": "quiet",
        "inlineDictationStatusText": "Listening · echo test",
        "inlineDictationPreview": "echo test",
        "inlineDictationStatusRow": {"top": 262, "bottom": 288, "left": 89, "right": 336, "width": 247, "height": 26},
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": False,
        "inlineDictationStatusInsideSheetHeader": True,
        "inlineDictationMic": {
            **ctrl["inlineDictationMic"], "label": "Stop terminal dictation", "micState": "listening",
            "top": 203, "bottom": 251, "left": 235, "right": 283,
        },
    })
    return copied


def with_undersized_dictation_mic(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-listening-ime-open":
            item["inlineDictationMic"]["width"] = 47
    return copied


def with_post_stop_draft_input(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-post-stop-keyboard-input":
            item["composerDraftValue"] = "z"
    copied["dictation"]["postStopKeyboardDraftAfter"] = "z"
    return copied


def with_post_stop_terminal_focus_lost(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-post-stop-keyboard-input":
            item["activeElementInsideTerminal"] = False
    copied["dictation"]["postStopTerminalFocused"] = False
    return copied


def with_changed_post_stop_grid(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-post-stop-keyboard-input":
            item["runtimeGeometry"]["rows"] -= 1
    return copied


def with_partial_dictation_write(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["dictation"]["writesAfterPartial"] += 1
    return copied


def with_duplicate_dictation_recognizer(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["dictation"]["nativeStartCalls"] += 1
    return copied


def with_missing_dictation_attach_epoch(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["dictation"]["targetKey"] = "testuser@fixture:22/first"
    return copied


def with_late_attach_dictation_write(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["dictationAttachCancel"]["writesAfter"] += 1
    return copied


def with_dictation_error_write(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["dictationError"]["writesAfter"] += 1
    return copied


def with_missing_main_key(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["catalogReachability"]["mainKeys"].pop()
    return copied


def with_undersized_ctrl_key(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["catalogReachability"]["ctrlKeys"][0]["width"] = 47
    return copied


def with_live_hotkeys_after_reconnect_loss(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "after-reconnect-loss":
            item["sshPhase"] = "live"
            item["mobileHotkeys"] = {"top": 10, "bottom": 58, "left": 10, "right": 250, "width": 240, "height": 48}
            item["navigationTargets"] = [{"label": "Send Up arrow", "disabled": False}]
            item["hotkeyControls"] = [{"keyId": "arrow-up", "disabled": False}]
    return copied


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=False)
    parser.add_argument("--logcat", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.run_id or not args.logcat or not args.output_dir:
        parser.error("--run-id, --logcat, and --output-dir are required")
    try:
        extract(args.logcat, args.output_dir, args.run_id)
    except (OSError, ExtractionFailure) as error:
        print(f"BLOCK: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
