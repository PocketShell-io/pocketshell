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
PROMPT_DICTATION_SCREENSHOTS = {
    "fastkeys-prompt-dictation-recording.png",
    "fastkeys-prompt-dictation-transcribing.png",
    "fastkeys-prompt-dictation-review.png",
}
SCREENSHOTS = {
    "fastkeys-ime-open.png",
    "fastkeys-row-closed-ime-open.png",
    "fastkeys-sheet-main-ime-open.png",
    "fastkeys-sheet-main-tail-ime-open.png",
    "fastkeys-sheet-ctrl-ime-open.png",
    "fastkeys-sheet-ctrl-tail-ime-open.png",
    "fastkeys-tray-ime-dismissed.png",
    "fastkeys-tray-closed.png",
    "fastkeys-reconnected-ime-open.png",
    "fastkeys-composer-keys-ime-open.png",
    "fastkeys-composer-returned.png",
    "fastkeys-dictation-idle-ime-open.png",
    "fastkeys-dictation-listening-ime-open.png",
    "fastkeys-dictation-listening-ctrl-ime-open.png",
    "fastkeys-dictation-transcribing-ime-open.png",
    "fastkeys-dictation-stopped-ime-open.png",
    "fastkeys-dictation-error-ime-open.png",
    "fastkeys-dictation-attach-cancel.png",
    "fastkeys-dictation-reattached-ime-open.png",
    "fastkeys-dictation-background-cancel-resumed.png",
    "fastkeys-dictation-post-resume-ime-open.png",
    "fastkeys-prompt-dictation-recording.png",
    "fastkeys-prompt-dictation-transcribing.png",
    "fastkeys-prompt-dictation-review.png",
}
VIEWPORT_SCREENSHOTS = {
    "fastkeys-row-closed-ime-open-viewport.png",
    "fastkeys-sheet-main-ime-open-viewport.png",
    "fastkeys-sheet-main-tail-ime-open-viewport.png",
    "fastkeys-sheet-ctrl-ime-open-viewport.png",
    "fastkeys-sheet-ctrl-tail-ime-open-viewport.png",
    "fastkeys-dictation-listening-ime-open-viewport.png",
    "fastkeys-dictation-stopped-ime-open-viewport.png",
    "fastkeys-dictation-error-ime-open-viewport.png",
    "fastkeys-dictation-background-cancel-resumed-viewport.png",
    "fastkeys-dictation-post-resume-ime-open-viewport.png",
    "fastkeys-reconnected-ime-open-viewport.png",
}
REQUIRED_ASSETS = SCREENSHOTS | VIEWPORT_SCREENSHOTS | {"fastkeys-journey.json"}
FAILURE_ASSETS = {
    "fastkeys-dictation-post-stop-marker-failure.png",
    "fastkeys-dictation-post-stop-marker-failure.json",
}
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
INLINE_DICTATION_STATUS_ROW_HEIGHT_PX = 32
CATALOG_SHEET_HEIGHT_PX = 96
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


def format_timing_summary(journey: dict[str, object]) -> str:
    return (
        f"connect_to_prompt_ms={journey['connectToPromptMs']}\n"
        f"tap_to_visible_output_ms={journey['tapToVisibleOutputMs']}\n"
        f"reconnect_tap_to_visible_output_ms={journey['reconnectTapToVisibleOutputMs']}\n"
    )


def expected_terminal_dictation_accessible_name(phase: object, tone: object, disabled: object) -> str:
    phase_names = {
        "listening": "Stop dictating at terminal cursor",
        "starting": "Cancel terminal cursor dictation request",
        "cancelling": "Cancelling terminal dictation",
        "stopping": "Transcribing speech for terminal cursor",
        "inserting": "Inserting speech at terminal cursor",
    }
    if isinstance(phase, str) and phase in phase_names:
        return phase_names[phase]
    if tone == "error":
        return "Retry terminal cursor dictation"
    return "Terminal cursor dictation unavailable" if disabled is True else "Dictate at terminal cursor"


def expected_terminal_dictation_caption(phase: object, tone: object) -> str:
    if phase == "listening":
        return "Stop"
    if phase == "starting":
        return "Cancel"
    if phase in {"stopping", "cancelling", "inserting"}:
        return "Wait"
    if tone == "error":
        return "Retry"
    return "Dictate"


def expected_terminal_dictation_mic_state(phase: object, tone: object, disabled: object) -> str:
    if phase == "listening":
        return "listening"
    if phase == "starting":
        return "starting"
    if phase in ("stopping", "cancelling", "inserting"):
        return "transcribing"
    if tone == "error":
        return "error"
    return "disabled" if disabled is True else "idle"


def icon_is_inside_button(button: object, icon: object) -> bool:
    if not isinstance(button, dict) or not isinstance(icon, dict):
        return False
    numeric_fields = ("left", "top", "right", "bottom", "width", "height")
    if any(isinstance(icon.get(field), bool) or not isinstance(icon.get(field), (int, float))
           for field in numeric_fields):
        return False
    if icon["width"] <= 0 or icon["height"] <= 0:
        return False
    return (
        icon["left"] >= button.get("left", 0) - 0.5
        and icon["right"] <= button.get("right", 0) + 0.5
        and icon["top"] >= button.get("top", 0) - 0.5
        and icon["bottom"] <= button.get("bottom", 0) + 0.5
    )


def prompt_icon_matches_computed_size(launcher: object, icon: object) -> bool:
    if not isinstance(launcher, dict) or not isinstance(icon, dict):
        return False
    try:
        computed_width = float(str(launcher.get("iconComputedWidth", "")).removesuffix("px"))
        computed_height = float(str(launcher.get("iconComputedHeight", "")).removesuffix("px"))
    except ValueError:
        return False
    return (
        abs(computed_width - 20) <= 0.5
        and abs(computed_height - 20) <= 0.5
        and abs(icon.get("width", 0) - computed_width) <= 0.5
        and abs(icon.get("height", 0) - computed_height) <= 0.5
    )


def validate_composer_alternate_surface(
    item: dict[str, object], label: str, expected_draft: object,
) -> None:
    panel = item.get("composerPanel")
    dock = item.get("fastKeysTray")
    dock_bounds = dock.get("bounds") if isinstance(dock, dict) else None
    panel_covers_dock = (
        isinstance(panel, dict) and isinstance(dock_bounds, dict)
        and panel.get("left", 10**9) <= dock_bounds.get("left", 0) + 0.5
        and panel.get("right", -1) >= dock_bounds.get("right", 10**9) - 0.5
        and panel.get("top", 10**9) <= dock_bounds.get("top", 0) + 0.5
        and panel.get("bottom", -1) >= dock_bounds.get("bottom", 10**9) - 0.5
    )
    ime = item.get("androidIme")
    if (not isinstance(expected_draft, str) or not expected_draft
            or not isinstance(panel, dict) or not panel_covers_dock
            or not isinstance(dock, dict) or dock.get("intersectsComposerPanel") is not True
            or item.get("fastKeysPage") != "closed" or item.get("catalogSheet") is not None
            or item.get("keyboardVisible") is not True or item.get("keyboardComposerMode") is not True
            or not isinstance(ime, dict) or ime.get("visible") is not True
            or item.get("activeElementIsPromptDraft") is not True
            or item.get("composerDraftValue") != expected_draft):
        raise ExtractionFailure(
            f"{label} must show the modal composer alone, covering the dock while preserving its draft and IME"
        )


def validate_docked_dictation_geometry(
    item: dict[str, object], label: str, *, allow_disabled_mic: bool = False,
) -> None:
    viewport = item.get("terminalViewport")
    grid_viewport = item.get("terminalGridViewport")
    xterm_surface = item.get("terminalXtermSurface")
    slot = item.get("terminalSlot")
    cap = item.get("terminalViewportDockCapPx")
    dock = item.get("fastKeysTray")
    viewport_height = grid_viewport.get("height") if isinstance(grid_viewport, dict) else None
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
            raise ExtractionFailure(f"{label} visible catalog scroller is clipped by the 96px rail")
    tray_bounds = dock.get("bounds") if isinstance(dock, dict) else None
    terminal_panel = item.get("terminalPanel")
    if (not isinstance(tray_bounds, dict) or not isinstance(terminal_panel, dict)
            or dock.get("insideTerminalPanel") is not True
            or tray_bounds.get("top", -1) < terminal_panel.get("top", 0)
            or tray_bounds.get("bottom", 10**9) > terminal_panel.get("bottom", 0)):
        raise ExtractionFailure(f"{label} dock extends outside the clipped terminal panel")
    canvas = item.get("terminalCanvas")
    if (not isinstance(viewport, dict) or not isinstance(canvas, dict)
            or tray_bounds.get("top", -1)
            < viewport.get("bottom", 0) - TERMINAL_VIEWPORT_ROUNDING_EPSILON_CSS_PX
            or canvas.get("top", 10**9) > viewport.get("top", 0) + 0.5
            or canvas.get("bottom", -1) < tray_bounds.get("bottom", 10**9) - 1
            or canvas.get("height", 0) < viewport.get("height", 0) + rendered_dock_height
            or item.get("terminalCanvasEndsAtDock") is not True):
        raise ExtractionFailure(f"{label} capped terminal viewport and input rail do not share one full-height canvas")
    if (not isinstance(grid_viewport, dict) or not isinstance(viewport, dict)
            or not isinstance(xterm_surface, dict)
            or grid_viewport.get("top", -1) < viewport.get("top", 0) - 0.5
            or grid_viewport.get("bottom", 10**9) > viewport.get("bottom", 0) + 0.5
            or (should_preserve_grid and abs(viewport.get("height", 0) - cap) > 0.5)
            or (should_preserve_grid and abs(grid_viewport.get("height", 0) - cap) > 0.5)
            or xterm_surface.get("top", -1) < viewport.get("top", 0) - 0.5
            or xterm_surface.get("bottom", 10**9) > viewport.get("bottom", 0) + 0.5
            or viewport.get("bottom", 10**9) > canvas.get("bottom", -1) + 0.5):
        raise ExtractionFailure(f"{label} capped xterm grid escapes its measured terminal viewport")
    canvas_style = item.get("terminalCanvasStyle")
    if (not isinstance(canvas_style, dict)
            or canvas_style.get("borderRadius") != "0px"
            or canvas_style.get("backgroundColor") != canvas_style.get("panelBackgroundColor")):
        raise ExtractionFailure(f"{label} terminal surface adds a nested panel boundary")
    if item.get("keyboardVisible") is True:
        ime_edge = item.get("imeEdgeCssY", viewport.get("height", 0) + viewport.get("offsetTop", 0))
        if tray_bounds.get("bottom", 10**9) > ime_edge + 0.5:
            raise ExtractionFailure(f"{label} dock does not clear the measured keyboard viewport edge")
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
    phase = item.get("inlineDictationPhase")
    expected_accessible_name = expected_terminal_dictation_accessible_name(
        phase, item.get("inlineDictationTone"), mic.get("disabled"),
    )
    if mic.get("label") != expected_accessible_name or mic.get("title") != expected_accessible_name:
        raise ExtractionFailure(f"{label} dictation button lacks its phase-specific accessible name")
    expected_caption = expected_terminal_dictation_caption(phase, item.get("inlineDictationTone"))
    if (mic.get("visibleText") != expected_caption or mic.get("destinationLabels") != []
            or mic.get("destinationLabelBounds") is not None):
        raise ExtractionFailure(f"{label} terminal mic caption must match its active phase and remain inside its target")
    if mic.get("iconVisible") is not True or not icon_is_inside_button(mic, mic.get("iconBounds")):
        raise ExtractionFailure(f"{label} dictation icon is missing or outside its 48dp button")
    expected_mic_state = expected_terminal_dictation_mic_state(
        phase, item.get("inlineDictationTone"), mic.get("disabled"),
    )
    if mic.get("micState") != expected_mic_state:
        raise ExtractionFailure(f"{label} mic icon state does not match its dictation phase")
    if mic.get("pressed") is not (phase == "listening"):
        raise ExtractionFailure(f"{label} mic pressed state does not match the visible Stop state")
    if mic.get("insideViewport") is not True:
        raise ExtractionFailure(f"{label} mic target is clipped")
    if (mic.get("visibleWidthInKeybar", 0) < 47.9
            or mic.get("visibleHeightInKeybar", 0) < 47.9):
        raise ExtractionFailure(f"{label} microphone clipped hit target is below 48dp")
    if (mic.get("hitTarget") is not True
            or mic.get("left", -1) < keybar.get("left", 0) - 0.5
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
        status_row = item.get("inlineDictationStatusRow")
        expected_placement = item.get("inlineDictationStatusAboveKeybar") is True
        if (item.get("inlineDictationStatusOneLine") is not True
                or item.get("inlineDictationStatusInsideBar") is not True
                or item.get("inlineDictationStatusInsideSheetHeader") is not False
                or not expected_placement
                or not isinstance(status_row, dict)
                or status_row.get("height", 0) < INLINE_DICTATION_STATUS_ROW_HEIGHT_PX - 0.5
                or status_row.get("bottom", 10**9) > keybar.get("top", 0) + 0.5):
            raise ExtractionFailure(f"{label} status is not a readable one-line row above the persistent keys")
        status_metrics = item.get("inlineDictationStatusMetrics")
        if (not isinstance(status_metrics, dict)
                or status_metrics.get("fontSize", 0) < 11
                or status_metrics.get("lineHeight", 0) < 16
                or status_metrics.get("paddingTop", 0) < 6
                or status_metrics.get("paddingBottom", 0) < 6
                or status_metrics.get("height", 0) < 29.5):
            raise ExtractionFailure(f"{label} status chip lacks readable text and vertical padding")
        status_text = item.get("inlineDictationStatusText", "")
        if item.get("inlineDictationTone") == "error" and not status_text.startswith("Terminal · Error ·"):
            raise ExtractionFailure(f"{label} terminal dictation error is not named in the status chip")
        if phase == "listening" and (not status_text.startswith("Terminal · Listening ·")
                                      or not item.get("inlineDictationPreview")):
            raise ExtractionFailure(f"{label} listening state lacks a visible partial preview")
    elif (item.get("inlineDictationPhase") != "idle"
          or item.get("inlineDictationTone") not in {"quiet", "success"}
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
    geometry_oracle_file = dictation.get("geometryOracleFile")
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
            or not isinstance(raw_file, str) or not re.fullmatch(r"/tmp/[A-Za-z0-9._-]+-keys-dictation\.raw", raw_file)
            or geometry_oracle_file != raw_file + ".geometry"):
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
            or error.get("writesBefore") != error.get("writesAfter") or error.get("nativeStartCalls") != 3):
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
            or attach.get("nativeStartCalls") != 4 or attach.get("nativeStopCalls") != 3
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
            or background.get("nativeStartCalls") != 2 or background.get("nativeStopCalls") != 2
            or not isinstance(background.get("resizeAcksBeforeResume"), int)
            or isinstance(background.get("resizeAcksBeforeResume"), bool)
            or not isinstance(background.get("resizeAcksAfterResume"), int)
            or isinstance(background.get("resizeAcksAfterResume"), bool)
            or background["resizeAcksAfterResume"] <= background["resizeAcksBeforeResume"]):
        raise ExtractionFailure("late dictation result was not rejected after app backgrounding")

    request_ids = [request_id, error.get("requestId"), attach.get("requestId"), background.get("requestId")]
    if any(not isinstance(value, str) or not value for value in request_ids) or len(set(request_ids)) != len(request_ids):
        raise ExtractionFailure("dictation reused a recognizer request ID across distinct sessions")


def parse_assets(log_text: str, run_id: str, *, preserve_on_failure: bool = False,
                 preserve_test_failure: bool = False) -> dict[str, bytes]:
    if preserve_on_failure and preserve_test_failure:
        raise ExtractionFailure("marker-only and packaged-test failure extraction modes cannot be combined")
    records: dict[str, dict[str, object]] = {}
    for line in log_text.splitlines():
        if TAG not in line:
            continue
        message = line.split(TAG, 1)[1].strip()
        parts = message.split("|", 4)
        if len(parts) < 3 or parts[1] != run_id:
            continue
        kind, _, name = parts[:3]
        if not SAFE_NAME.fullmatch(name) or name not in REQUIRED_ASSETS | FAILURE_ASSETS:
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

    if preserve_on_failure:
        if not FAILURE_ASSETS.issubset(records):
            raise ExtractionFailure(f"failure-mode capture is missing marker diagnostics: {sorted(FAILURE_ASSETS - set(records))}")
    elif preserve_test_failure:
        initial_screenshots = {
            "fastkeys-ime-open.png",
            "fastkeys-row-closed-ime-open.png",
            "fastkeys-row-closed-ime-open-viewport.png",
        }
        if "fastkeys-journey.json" in records:
            raise ExtractionFailure("an incomplete packaged journey cannot include its green completion manifest")
        if not initial_screenshots.issubset(records):
            raise ExtractionFailure(
                f"test-failure capture is missing pre-assert keyboard screenshots: {sorted(initial_screenshots - set(records))}"
            )
        if bool(FAILURE_ASSETS & set(records)) and not FAILURE_ASSETS.issubset(records):
            raise ExtractionFailure("post-Stop marker diagnostics must include their same-run screenshot and state JSON")
    elif FAILURE_ASSETS & set(records):
        raise ExtractionFailure("failure-mode marker diagnostics cannot be accepted as a green journey")
    elif set(records) != REQUIRED_ASSETS:
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

    for name in (SCREENSHOTS | VIEWPORT_SCREENSHOTS | {"fastkeys-dictation-post-stop-marker-failure.png"}) & set(decoded):
        payload = decoded[name]
        if len(payload) < 1024 or not payload.startswith(b"\x89PNG\r\n\x1a\n"):
            raise ExtractionFailure(f"{name} is not a full non-empty PNG")
    if preserve_on_failure:
        try:
            marker_failure = json.loads(decoded["fastkeys-dictation-post-stop-marker-failure.json"])
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ExtractionFailure(f"post-Stop marker failure evidence is invalid JSON: {error}") from error
        if (not isinstance(marker_failure, dict) or marker_failure.get("runId") != run_id
                or marker_failure.get("stage") != "post-stop-done-marker-timeout"
                or not isinstance(marker_failure.get("terminalEvidence"), dict)
                or not isinstance(marker_failure.get("geometry"), dict)
                or not isinstance(marker_failure.get("terminalVisibleText"), str)
                or not isinstance(marker_failure.get("terminalInputChunks"), list)):
            raise ExtractionFailure("post-Stop marker failure evidence does not contain same-run terminal state")
        return decoded
    if preserve_test_failure:
        if FAILURE_ASSETS.issubset(decoded):
            try:
                marker_failure = json.loads(decoded["fastkeys-dictation-post-stop-marker-failure.json"])
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ExtractionFailure(f"post-Stop marker failure evidence is invalid JSON: {error}") from error
            if (not isinstance(marker_failure, dict) or marker_failure.get("runId") != run_id
                    or marker_failure.get("stage") != "post-stop-done-marker-timeout"
                    or not isinstance(marker_failure.get("terminalEvidence"), dict)
                    or not isinstance(marker_failure.get("geometry"), dict)
                    or not isinstance(marker_failure.get("terminalVisibleText"), str)
                    or not isinstance(marker_failure.get("terminalInputChunks"), list)):
                raise ExtractionFailure("post-Stop marker failure evidence does not contain same-run terminal state")
        return decoded
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
    geometry_trace = journey.get("geometryTrace")
    has_prompt_dictation_dock_control = (
        journey.get("promptDictationLauncher") is not None
        or journey.get("promptDictationFromDock") is not None
        or (isinstance(geometry_trace, list)
            and any(isinstance(item, dict) and item.get("promptDictationLauncher") is not None
                    for item in geometry_trace))
    )
    if has_prompt_dictation_dock_control:
        raise ExtractionFailure("prompt dictation must start inside PromptComposer, not from a separate dock shortcut")
    composer_entry = journey.get("promptComposerEntry")
    if (not isinstance(composer_entry, dict)
            or composer_entry.get("role") != "dialog"
            or composer_entry.get("modal") != "true"
            or composer_entry.get("micLabel") != "Dictate prompt draft"
            or composer_entry.get("micVisible") is not True
            or composer_entry.get("micWidth", 0) < 47.9
            or composer_entry.get("micHeight", 0) < 47.9):
        raise ExtractionFailure("prompt composer entry does not prove a reachable dictation action in the modal composer")
    if (composer_entry.get("keysLabel") != "More terminal keys"
            or composer_entry.get("keysVisible") is not True
            or composer_entry.get("keysWidth", 0) < 47.9
            or composer_entry.get("keysHeight", 0) < 47.9):
        raise ExtractionFailure("mobile composer does not expose its measured 48dp More terminal keys handoff")
    transition = journey.get("composerKeysTransition")
    controls = transition.get("stableDockControls") if isinstance(transition, dict) else None
    expected_dock_labels = (
        "Open prompt composer to type or dictate a prompt", "Send Up arrow", "Send Down arrow",
        "Send Enter", "Close terminal keys", "Dictate at terminal cursor",
    )
    before_grid = transition.get("terminalGridBefore") if isinstance(transition, dict) else None
    keys_grid = transition.get("terminalGridDuringKeys") if isinstance(transition, dict) else None
    if (not isinstance(transition, dict)
            or not isinstance(transition.get("draftBefore"), str)
            or not transition["draftBefore"]
            or transition.get("draftAfterReturn") != transition.get("draftBefore")
            or transition.get("composerVisibleDuringKeys") is not False
            or transition.get("paletteOpenDuringKeys") is not True
            or transition.get("imeVisibleDuringKeys") is not True
            or transition.get("keyboardVisibleDuringKeys") is not True
            or transition.get("imeVisibleAfterReturn") is not True
            or transition.get("keyboardVisibleAfterReturn") is not True
            or not isinstance(before_grid, dict) or before_grid != keys_grid
            or not isinstance(controls, list) or len(controls) != 6
            or tuple(control.get("label") for control in controls if isinstance(control, dict)) != expected_dock_labels
            or any(not isinstance(control, dict)
                   or abs(control.get("width", 0) - 48) >= 0.5
                   or abs(control.get("height", 0) - 48) >= 0.5
                   or control.get("insideViewport") is not True
                   or control.get("hitTarget") is not True
                   for control in controls)
            or any(controls[index].get("visibleText") != expected_text
                   for index, expected_text in ((0, "Prompt"), (1, "↑"), (2, "↓"),
                                                (3, "Enter"), (4, ""), (5, "Dictate")))
            or any(controls[index].get("iconCount") != 1 for index in (0, 4, 5))):
        raise ExtractionFailure("composer-to-keys journey does not prove a draft-preserving exclusive 48dp dock handoff with stable PTY grid")
    mic = transition.get("inlineDictationMic")
    label_bounds = mic.get("destinationLabelBounds") if isinstance(mic, dict) else None
    if (not isinstance(mic, dict) or mic.get("visibleText") != "Dictate"
            or mic.get("destinationLabels") != [] or label_bounds is not None):
        raise ExtractionFailure("terminal mic must show one Dictate caption without a duplicate destination label")
    native_dictation = journey.get("terminalNativeDictation")
    if not isinstance(native_dictation, dict):
        raise ExtractionFailure("journey is missing the terminal bar's actual Android speech bridge proof")
    native_start = native_dictation.get("startOptions")
    native_start_result = native_dictation.get("startResult")
    native_stop_result = native_dictation.get("stopResult")
    native_events = [native_dictation.get(name) for name in ("partialInjection", "finalInjection", "finishInjection")]
    request_id = native_dictation.get("requestId")
    write_counts = [native_dictation.get(name) for name in (
        "writesBeforePartial", "writesAfterPartial", "writesAfterFinalBeforeStopped", "writesAfterStopped")]
    if (native_dictation.get("bridge") != "Capacitor SpeechRecognition plugin"
            or native_dictation.get("debugTestMode") is not True
            or not isinstance(request_id, str) or not request_id
            or not isinstance(native_start, dict) or native_start.get("requestId") != request_id
            or native_start.get("testMode") is not True
            or not isinstance(native_start_result, dict)
            or native_start_result.get("requestId") != request_id or native_start_result.get("started") is not True
            or not isinstance(native_stop_result, dict)
            or native_stop_result.get("requestId") != request_id or native_stop_result.get("stopped") is not True
            or native_dictation.get("explicitStopRequestId") != request_id
            or native_dictation.get("startCalls") != 1 or native_dictation.get("stopCalls") != 1
            or any(not isinstance(event, dict) or event.get("emitted") is not True or event.get("requestId") != request_id
                   for event in native_events)
            or any(isinstance(value, bool) or not isinstance(value, int) for value in write_counts)
            or write_counts[0] != write_counts[1] or write_counts[0] != write_counts[2]
            or write_counts[3] != write_counts[0] + 1):
        raise ExtractionFailure("terminal dictation did not traverse the native Android speech plugin with partial/final gating through explicit Stop")
    narrow_row = journey.get("narrowToolbarReachability")
    narrow_targets = narrow_row.get("targets") if isinstance(narrow_row, dict) else None
    narrow_mic = narrow_row.get("finalMic") if isinstance(narrow_row, dict) else None
    expected_narrow_labels = (
        "Open prompt composer to type or dictate a prompt", "Send Up arrow", "Send Down arrow",
        "Send Enter", "More terminal keys", "Dictate at terminal cursor",
    )
    narrow_client_width = narrow_row.get("clientWidth") if isinstance(narrow_row, dict) else None
    narrow_scroll_width = narrow_row.get("scrollWidth") if isinstance(narrow_row, dict) else None
    narrow_max_scroll_left = narrow_row.get("maxScrollLeft") if isinstance(narrow_row, dict) else None
    narrow_mic_left = narrow_mic.get("left") if isinstance(narrow_mic, dict) else None
    narrow_mic_right = narrow_mic.get("right") if isinstance(narrow_mic, dict) else None
    narrow_mic_width = narrow_mic.get("width") if isinstance(narrow_mic, dict) else None
    if (not isinstance(narrow_row, dict)
            or isinstance(narrow_client_width, bool) or not isinstance(narrow_client_width, (int, float))
            or isinstance(narrow_scroll_width, bool) or not isinstance(narrow_scroll_width, (int, float))
            or isinstance(narrow_max_scroll_left, bool) or not isinstance(narrow_max_scroll_left, (int, float))
            or abs(narrow_client_width - 330) > 1
            or narrow_scroll_width > narrow_client_width + 0.5
            or narrow_row.get("scrollable") is not False
            or abs(narrow_max_scroll_left) > 0.5
            or narrow_row.get("ptyWritesBefore") != narrow_row.get("ptyWritesAfter")
            or not isinstance(narrow_targets, list)
            or len(narrow_targets) != 6
            or not isinstance(narrow_mic, dict)
            or isinstance(narrow_mic_left, bool) or not isinstance(narrow_mic_left, (int, float))
            or isinstance(narrow_mic_right, bool) or not isinstance(narrow_mic_right, (int, float))
            or isinstance(narrow_mic_width, bool) or not isinstance(narrow_mic_width, (int, float))
            or narrow_mic_left < 0 or narrow_mic_right > narrow_client_width + 0.5
            or abs((narrow_mic_right - narrow_mic_left) - narrow_mic_width) > 0.5
            or narrow_mic.get("label") != "Dictate at terminal cursor"
            or narrow_mic.get("title") != "Dictate at terminal cursor"
            or narrow_mic.get("visibleText") != "Dictate"
            or narrow_mic.get("iconVisible") is not True
            or narrow_mic.get("width", 0) < 47.9 or narrow_mic.get("height", 0) < 47.9
            or narrow_mic.get("insideToolbar") is not True or narrow_mic.get("hitTarget") is not True
            or tuple(target.get("label") for target in narrow_targets if isinstance(target, dict)) != expected_narrow_labels
            or any(not isinstance(narrow_targets[index], dict)
                   or narrow_targets[index].get("visibleText") != expected_text
                   for index, expected_text in ((0, "Prompt"), (1, "↑"), (2, "↓"),
                                                (3, "Enter"), (4, ""), (5, "Dictate")))
            or any(not isinstance(target, dict)
                   or target.get("width", 0) < 47.9 or target.get("height", 0) < 47.9
                   or target.get("visibleWidth", 0) < 47.9 or target.get("visibleHeight", 0) < 47.9
                   or target.get("hitTarget") is not True
                   or target.get("insideToolbar") is not True or target.get("disabled") is True
                   for target in narrow_targets)
            or {target.get("label") for target in narrow_targets if isinstance(target, dict)}
               < {"Open prompt composer to type or dictate a prompt", "Send Up arrow", "Send Down arrow", "Send Enter", "More terminal keys", "Dictate at terminal cursor"}
           ):
        raise ExtractionFailure("330px dock does not prove reachable 48dp Prompt, navigation, More keys, and dictation controls")
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
        "composer-keys-before-ime-open",
        "composer-to-keys-ime-open",
        "keys-to-composer-return",
        "after-navigation-row-taps",
        "before-fast-keys",
        "fast-keys-main-open-ime-up",
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
        "dictation-post-resume-ime-open",
        "after-reconnect",
        "reconnected-keybar-ime-up",
        "after-reconnect-loss",
    }
    if not required_stages.issubset(by_name):
        raise ExtractionFailure(f"missing geometry stages: {sorted(required_stages - set(by_name))}")
    transition = journey["composerKeysTransition"]
    assert isinstance(transition, dict)
    validate_composer_alternate_surface(
        by_name["composer-keys-before-ime-open"],
        "composer entry before More keys",
        transition.get("draftBefore"),
    )
    validate_composer_alternate_surface(
        by_name["keys-to-composer-return"],
        "composer return after More keys",
        transition.get("draftAfterReturn"),
    )
    composer_to_keys = by_name["composer-to-keys-ime-open"]
    if (composer_to_keys.get("composerPanel") is not None
            or composer_to_keys.get("fastKeysPage") != "main"
            or composer_to_keys.get("keyboardVisible") is not True
            or composer_to_keys.get("androidIme", {}).get("visible") is not True
            or composer_to_keys.get("activeElementInsideTerminal") is not True
            or composer_to_keys.get("activeElementIsPromptDraft") is not False):
        raise ExtractionFailure("More keys must transfer focus to the terminal palette without leaving the composer open")
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
        viewport = item.get("terminalXtermSurface")
        viewport_height = viewport.get("height") if isinstance(viewport, dict) else None
        if (isinstance(visible_rows, bool) or not isinstance(visible_rows, int) or visible_rows < 5
                or isinstance(cell_height, bool) or not isinstance(cell_height, (int, float)) or cell_height <= 0
                or isinstance(viewport_height, bool) or not isinstance(viewport_height, (int, float))
                or visible_rows != math.floor((viewport_height - 8) / cell_height)):
            raise ExtractionFailure(f"{stage_name} does not prove five physical xterm rows in its measured viewport")
        if stage_name in {"composer-keys-before-ime-open", "keys-to-composer-return"}:
            continue
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
            or listening.get("inlineDictationMic", {}).get("label") != "Stop dictating at terminal cursor"
            or listening.get("inlineDictationMic", {}).get("micState") != "listening"
            or listening.get("inlineDictationMic", {}).get("pressed") is not True
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
            or final_inserted.get("inlineDictationStatusText") != ""
            or final_inserted.get("inlineDictationStatusVisible") is not False
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
            or post_stop.get("inlineDictationPhase") != "idle"
            or post_stop.get("inlineDictationPreview") != ""
            or post_stop.get("inlineDictationMic", {}).get("disabled") is not False):
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
    idle_viewport = by_name["dictation-idle-ime-open"].get("terminalGridViewport")
    if not isinstance(idle_viewport, dict):
        raise ExtractionFailure("dictation journey lacks its initial keyboard-up terminal viewport")
    idle_viewport_height = idle_viewport.get("height")
    if (isinstance(idle_viewport_height, bool)
            or not isinstance(idle_viewport_height, (int, float))):
        raise ExtractionFailure("dictation journey lacks its measured initial viewport height")
    expected_viewport_cap = min(ACCEPTED_ANDROID_TERMINAL_VIEWPORT_CAP_PX, math.floor(idle_viewport_height))
    ready = by_name["dictation-ready-ime-open"]
    ready_grid = ready.get("runtimeGeometry")
    ready_viewport = ready.get("terminalGridViewport")
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
                       "dictation-final-awaiting-stopped", "dictation-final-inserted"):
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
        if stage_name == "dictation-background-cancel-resumed":
            continue
        grid = by_name[stage_name].get("runtimeGeometry")
        if (not isinstance(grid, dict) or not isinstance(keyboard_grid, dict)
                or (grid.get("cols"), grid.get("rows")) != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))):
            raise ExtractionFailure(f"reattached state {stage_name} changed the accepted keyboard-up PTY grid")
    resumed = by_name["dictation-background-cancel-resumed"]
    resumed_grid = resumed.get("runtimeGeometry")
    background_resize = journey.get("dictationBackgroundCancel")
    if (not isinstance(resumed_grid, dict)
            or resumed.get("keyboardVisible") is not False
            or resumed.get("androidIme", {}).get("visible") is not False
            or resumed.get("sshPhase") != "live"
            or resumed.get("resizePending") != 0
            or resumed.get("resizeFailures") != 0
            or resumed.get("resizeAcks") != background_resize.get("resizeAcksAfterResume")
            or resumed.get("resizeAcks", 0) <= background_resize.get("resizeAcksBeforeResume", 0)
            or resumed.get("resizeStatus") != f"{resumed_grid.get('cols')} × {resumed_grid.get('rows')} accepted by SSH"):
        raise ExtractionFailure("IME-hidden resume lacks a settled accepted PTY resize checkpoint")
    resume_fit_events = resumed.get("resizeFitEvents")
    if not isinstance(resume_fit_events, list):
        resume_fit_events = []
    resume_fit = next((event for event in reversed(resume_fit_events)
                       if isinstance(event, dict)
                       and isinstance(event.get("reason"), str) and event["reason"].strip()
                       and event.get("cols") == resumed_grid.get("cols")
                       and event.get("rows") == resumed_grid.get("rows")
                       and isinstance(event.get("requestId"), int)
                       and not isinstance(event.get("requestId"), bool)), None)
    resume_ack = next((event for event in resumed.get("resizeAckEvents", [])
                       if isinstance(event, dict) and isinstance(resume_fit, dict)
                       and event.get("requestId") == resume_fit.get("requestId")), None)
    if (not isinstance(resume_fit, dict) or not isinstance(resume_ack, dict)
            or resume_ack.get("result") != "accepted"
            or (resume_ack.get("cols"), resume_ack.get("rows"))
            != (resumed_grid.get("cols"), resumed_grid.get("rows"))
            or resume_ack.get("attachEpoch") != resumed.get("sshAttachEpoch")
            or not isinstance(resume_fit.get("atMs"), (int, float))
            or not isinstance(resume_ack.get("atMs"), (int, float))
            or resume_ack.get("atMs") < resume_fit.get("atMs")):
        raise ExtractionFailure("IME-hidden local fit is missing its matching accepted native PTY resize ACK")
    post_resume = by_name["dictation-post-resume-ime-open"]
    post_resume_grid = post_resume.get("runtimeGeometry")
    if (not isinstance(post_resume_grid, dict)
            or post_resume.get("keyboardVisible") is not True
            or post_resume.get("androidIme", {}).get("visible") is not True
            or post_resume.get("resizePending") != 0
            or post_resume.get("resizeFailures") != 0
            or (post_resume_grid.get("cols"), post_resume_grid.get("rows"))
            != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))
            or post_resume.get("resizeAcks", 0) <= resumed.get("resizeAcks", 0)
            or post_resume.get("resizeStatus") != f"{post_resume_grid.get('cols')} × {post_resume_grid.get('rows')} accepted by SSH"):
        raise ExtractionFailure("reopening the IME after resume lacks a settled accepted keyboard-up PTY grid")
    post_resume_fit = next((event for event in post_resume.get("resizeFitEvents", [])
                            if isinstance(event, dict)
                            and (event.get("cols"), event.get("rows"))
                            == (post_resume_grid.get("cols"), post_resume_grid.get("rows"))
                            and isinstance(event.get("requestId"), int)
                            and not isinstance(event.get("requestId"), bool)), None)
    post_resume_ack = next((event for event in post_resume.get("resizeAckEvents", [])
                            if isinstance(event, dict) and isinstance(post_resume_fit, dict)
                            and event.get("requestId") == post_resume_fit.get("requestId")), None)
    if (not isinstance(post_resume_fit, dict) or not isinstance(post_resume_ack, dict)
            or post_resume_ack.get("result") != "accepted"
            or (post_resume_ack.get("cols"), post_resume_ack.get("rows"))
            != (post_resume_grid.get("cols"), post_resume_grid.get("rows"))
            or post_resume_ack.get("attachEpoch") != post_resume.get("sshAttachEpoch")
            or not isinstance(post_resume_fit.get("atMs"), (int, float))
            or not isinstance(post_resume_ack.get("atMs"), (int, float))
            or post_resume_ack.get("atMs") < post_resume_fit.get("atMs")):
        raise ExtractionFailure("reopened keyboard-up local fit is missing its matching accepted PTY resize ACK")
    post_stop = by_name["dictation-post-stop-keyboard-input"]
    post_stop_grid = post_stop.get("runtimeGeometry")
    if (not isinstance(post_stop_grid, dict)
            or (post_stop_grid.get("cols"), post_stop_grid.get("rows"))
            != (post_resume_grid.get("cols"), post_resume_grid.get("rows"))
            or post_stop.get("resizeAcks") != post_resume.get("resizeAcks")):
        raise ExtractionFailure("post-Stop keyboard input changed the accepted resumed keyboard-up PTY grid")
    error_state = by_name["dictation-error-ime-open"]
    error_grid = error_state.get("runtimeGeometry")
    if (not isinstance(error_grid, dict)
            or (error_grid.get("cols"), error_grid.get("rows"))
            != (post_resume_grid.get("cols"), post_resume_grid.get("rows"))
            or error_state.get("resizeAcks") != post_resume.get("resizeAcks")
            or error_state.get("resizeStatus") != post_resume.get("resizeStatus")):
        raise ExtractionFailure("post-resume recognizer error did not keep the acknowledged keyboard-up grid stable")
    keyboard = by_name["keyboard-up-compact-row"]
    if keyboard.get("keyboardVisible") is not True:
        raise ExtractionFailure("keyboard-up DOM geometry says the keyboard is hidden")
    keyboard_viewport = keyboard.get("terminalGridViewport")
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
            expected_axis = "horizontal" if label == "main" else "vertical"
            expected_snap = "x mandatory" if label == "main" else "y mandatory"
            if key.get("axis") != expected_axis or expected_snap not in key.get("snapType", ""):
                key_id = key.get("keyId", "unknown")
                raise ExtractionFailure(f"{label} catalog key {key_id} was not reached in its snapped scroll direction")
            if label == "main":
                endpoint = key.get("endpointBounds")
                endpoint_content = key.get("endpointContentBounds")
                endpoint_intersects = key.get("endpointIntersectsContent")
                if (not isinstance(endpoint, dict) or not isinstance(endpoint_content, dict)
                        or not isinstance(endpoint_intersects, bool)):
                    raise ExtractionFailure("Main catalog is missing endpoint measurements for each snapped target")
                left, right = endpoint.get("left"), endpoint.get("right")
                top, bottom = endpoint.get("top"), endpoint.get("bottom")
                content_left, content_right = endpoint_content.get("left"), endpoint_content.get("right")
                content_top, content_bottom = endpoint_content.get("top"), endpoint_content.get("bottom")
                geometry_values = (left, right, top, bottom, content_left, content_right, content_top, content_bottom)
                if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in geometry_values):
                    raise ExtractionFailure("Main catalog endpoint evidence is missing target or rail bounds")
                intersects = (right > content_left and left < content_right
                              and bottom > content_top and top < content_bottom)
                contained = (left >= content_left and right <= content_right
                             and top >= content_top and bottom <= content_bottom)
                if intersects != endpoint_intersects:
                    raise ExtractionFailure("Main catalog endpoint intersection flag does not match measured bounds")
                if intersects and (not contained or key.get("endpointInsideContent") is not True
                                   or key.get("endpointInsideViewport") is not True):
                    key_id = key.get("keyId", "unknown")
                    raise ExtractionFailure(f"Main endpoint key {key_id} is partially clipped at the rail boundary")
                scroll_left = key.get("scrollLeft")
                endpoint_scroll_left = key.get("endpointScrollLeft")
                if (isinstance(scroll_left, bool) or not isinstance(scroll_left, (int, float))
                        or isinstance(endpoint_scroll_left, bool) or not isinstance(endpoint_scroll_left, (int, float))
                        or abs(scroll_left - round(scroll_left / 56) * 56) > 0.75
                        or abs(endpoint_scroll_left - round(endpoint_scroll_left / 56) * 56) > 0.75):
                    key_id = key.get("keyId", "unknown")
                    raise ExtractionFailure(f"Main catalog key {key_id} stops between 56dp slot boundaries")
            else:
                scroll_top = key.get("scrollTop")
                if (isinstance(scroll_top, bool) or not isinstance(scroll_top, (int, float))
                        or abs(scroll_top - round(scroll_top / 48) * 48) > 0.75):
                    key_id = key.get("keyId", "unknown")
                    raise ExtractionFailure(f"Ctrl catalog key {key_id} was reached between 48dp row boundaries")

    before = by_name["before-fast-keys"]
    opened = by_name["fast-keys-main-open-ime-up"]
    ctrl = by_name["fast-keys-ctrl-open-ime-up"]
    closed = by_name["fast-keys-closed-ime-up"]
    if opened.get("keyboardVisible") is not True or ctrl.get("keyboardVisible") is not True:
        raise ExtractionFailure("main fast-key tray geometry does not show the visible IME")
    for label, item in (("main", opened), ("Ctrl", ctrl)):
        if (item.get("androidApi") != 35 or item.get("androidIme", {}).get("visible") is not True
                or item.get("keyboardVisible") is not True):
            raise ExtractionFailure(f"{label} catalog geometry lacks same-run API 35 native IME evidence")
        launcher = item.get("promptComposerLauncher")
        icon = launcher.get("iconBounds") if isinstance(launcher, dict) else None
        if (not isinstance(launcher, dict) or launcher.get("label") != "Open prompt composer to type or dictate a prompt"
                or launcher.get("title") != "Open prompt composer to type or dictate a prompt"
                or launcher.get("visibleText") != "Prompt"
                or launcher.get("iconVisible") is not True
                or launcher.get("iconInside") is not True
                or launcher.get("width", 0) < 47.9 or launcher.get("height", 0) < 47.9
                or launcher.get("width", 0) > 48.5
                or not icon_is_inside_button(launcher, icon)
                or not prompt_icon_matches_computed_size(launcher, icon)
                or launcher.get("visibleWidthInKeybar", 0) < 47.9
                or launcher.get("visibleHeightInKeybar", 0) < 47.9
                or launcher.get("insideViewport") is not True or launcher.get("hitTarget") is not True
                or launcher.get("disabled") is not False):
            raise ExtractionFailure(f"{label} catalog geometry does not prove the reachable prompt composer launcher")
        terminal_panel_heading = item.get("layout", {}).get(".panel-heading--terminal")
        terminal_viewport = item.get("terminalViewport")
        if (not isinstance(terminal_panel_heading, dict) or terminal_panel_heading.get("display") == "none"
                or terminal_panel_heading.get("height", 0) < 24
                or not isinstance(terminal_viewport, dict)
                or terminal_panel_heading.get("bottom", 10**9) > terminal_viewport.get("top", 0) + 0.5
                or item.get("visibleTerminalRows", 0) < 5
                or item.get("runtimeGeometry", {}).get("rows", 0) < 5):
            raise ExtractionFailure(f"{label} catalog geometry obscures the terminal heading or leaves fewer than five terminal rows")
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
        raise ExtractionFailure("fast-key tray did not reserve the persistent 48px row and compact 96px catalog")
    for label, item in (("main", opened), ("Ctrl", ctrl)):
        sheet = item.get("catalogSheet")
        page_action = item.get("catalogPageAction")
        scroll = item.get("catalogScrollMetrics")
        if (not isinstance(sheet, dict)
                or abs(sheet.get("height", 0) - CATALOG_SHEET_HEIGHT_PX) > 0.5
                or item.get("catalogScrollerInsideSheet") is not True
                or item.get("catalogSheetRole") != "region"
                or item.get("catalogSheetModal") is not None
                or item.get("catalogSheetBelowTerminalViewport") is not True
                or item.get("catalogSheetIntersectsComposer") is not False):
            raise ExtractionFailure(f"{label} catalog is missing its in-flow terminal region geometry")
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
        expected_page_action = "Select Ctrl keys" if label == "main" else "Select Main keys"
        if page_action.get("label") != expected_page_action:
            raise ExtractionFailure(f"{label} catalog page action does not lead to the other visible page")
        tabs = item.get("catalogTabs")
        expected_selected = {"Select Main keys": label == "main", "Select Ctrl keys": label == "Ctrl"}
        if (not isinstance(tabs, list) or len(tabs) != 2
                or {tab.get("label"): tab.get("selected") for tab in tabs if isinstance(tab, dict)} != expected_selected
                or any(not isinstance(tab, dict) or tab.get("insideCatalogSheet") is not True
                       or tab.get("insideViewport") is not True or tab.get("hitTarget") is not True
                       or tab.get("width", 0) < 47.9 or tab.get("height", 0) < 47.9 for tab in tabs)):
            raise ExtractionFailure(f"{label} catalog does not expose both reachable 48px Main/Ctrl tabs")
        expected_title = "Keys"
        title = item.get("catalogTitle")
        if (not isinstance(title, dict) or title.get("text") != expected_title or title.get("fits") is not True
                or item.get("catalogHeaderControlsDoNotOverlap") is not True):
            raise ExtractionFailure(f"{label} catalog caption and Main/Ctrl navigation do not fit the header")
        surface = item.get("catalogSurfaceStyle")
        if (not isinstance(surface, dict) or surface.get("backgroundColor") != "rgba(0, 0, 0, 0)"
                or surface.get("borderRadius") != "0px"
                or any(surface.get(edge) != "0px" for edge in
                       ("borderRightWidth", "borderBottomWidth", "borderLeftWidth"))):
            raise ExtractionFailure(f"{label} catalog still uses the rounded, filled card surface")
        key_style = item.get("catalogKeyStyle")
        scaled_font_size = math.nan
        if isinstance(key_style, dict) and isinstance(key_style.get("fontSize"), str):
            try:
                scaled_font_size = float(key_style["fontSize"].removesuffix("px"))
            except ValueError:
                pass
        if (not isinstance(key_style, dict) or key_style.get("backgroundColor") != "rgba(0, 0, 0, 0)"
                or key_style.get("fontToken") != "13px" or not 13 <= scaled_font_size <= 17):
            raise ExtractionFailure(f"{label} key slots do not use the quiet, compact dense treatment")
        expected_scroller = ".mobile-hotkeys__main-keys" if label == "main" else ".mobile-hotkeys__ctrl-grid"
        if item.get("catalogScrollerSelector") != expected_scroller:
            raise ExtractionFailure(f"{label} catalog evidence does not measure {expected_scroller}")
        if (not isinstance(scroll, dict)
                or abs(scroll.get("clientHeight", 0) - 48) > 1):
            raise ExtractionFailure(f"{label} catalog does not reserve exactly one visible 48px key row")
        expected_snap = "x mandatory" if label == "main" else "y mandatory"
        if (not isinstance(scroll, dict) or expected_snap not in scroll.get("scrollSnapType", "")):
            raise ExtractionFailure(f"{label} catalog does not report mandatory snapping along its swipe axis")
        if label == "main":
            button_rects = scroll.get("buttonRects")
            if (scroll.get("axis") != "horizontal" or scroll.get("scrollWidth", 0) <= scroll.get("clientWidth", 0) + 1
                    or not isinstance(button_rects, list) or len(button_rects) != len(EXPECTED_MAIN_KEY_IDS)):
                raise ExtractionFailure("Main keys must fit one horizontally scrollable row of ten 48px targets")
            row_counts: dict[int, int] = {}
            for rect in button_rects:
                top = rect.get("top") if isinstance(rect, dict) else None
                if isinstance(top, bool) or not isinstance(top, (int, float)):
                    raise ExtractionFailure("main common-key catalog is missing measured key row positions")
                row = round(top)
                row_counts[row] = row_counts.get(row, 0) + 1
            if sorted(row_counts.values()) != [10]:
                raise ExtractionFailure("main common-key catalog must keep all ten targets in one row")
            key_layout = item.get("mainKeyLayout")
            if (not isinstance(key_layout, dict) or key_layout.get("rowCount") != 1
                    or key_layout.get("columnGap") != "8px"
                    or not 500 < key_layout.get("maxRowWidth", 0) <= 600):
                raise ExtractionFailure("Main keys must use one compactly spaced horizontal touch row")
            if scroll.get("scrollHeight", 0) > scroll.get("clientHeight", 0) + 1:
                raise ExtractionFailure("Main keys must not add a second vertical catalog row")
        elif (scroll.get("axis") != "vertical"
              or scroll.get("scrollWidth", 0) > scroll.get("clientWidth", 0) + 1
              or scroll.get("scrollHeight", 0) <= scroll.get("clientHeight", 0) + 1):
            raise ExtractionFailure("Ctrl letters must remain in a vertically scrollable QWERTY catalog")
    before_grid = before.get("runtimeGeometry")
    if not isinstance(before_grid, dict):
        raise ExtractionFailure("fast-key open comparison lacks the initial xterm dimensions")
    if (not isinstance(keyboard_grid, dict)
            or (before_grid.get("cols"), before_grid.get("rows"))
            != (keyboard_grid.get("cols"), keyboard_grid.get("rows"))):
        raise ExtractionFailure("pre-fast-key geometry changed the accepted keyboard-up xterm grid")
    baseline_viewport = before.get("terminalGridViewport")
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
    if ctrl.get("runtimeGeometry", {}).get("rows", 0) < 5:
        raise ExtractionFailure("IME-up Ctrl tray leaves fewer than five xterm rows")
    if opened.get("runtimeGeometry", {}).get("rows", 0) < 5:
        raise ExtractionFailure("IME-up main tray leaves fewer than five xterm rows")

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


def extract(log_path: Path, output_dir: Path, run_id: str, *, preserve_on_failure: bool = False,
            preserve_test_failure: bool = False) -> None:
    assets = parse_assets(log_path.read_text(encoding="utf-8", errors="replace"), run_id,
                           preserve_on_failure=preserve_on_failure,
                           preserve_test_failure=preserve_test_failure)
    output_dir.mkdir(parents=True, exist_ok=True)
    for name, payload in assets.items():
        (output_dir / name).write_bytes(payload)
        print(f"PASS: extracted {name} ({len(payload)} bytes)")
    if preserve_on_failure:
        print(f"PASS: preserved contemporaneous marker-failure diagnostics ({run_id})")
        return
    if preserve_test_failure:
        print(f"PASS: preserved hash-checked same-run packaged-test failure artifacts ({run_id})")
        return
    journey = json.loads(assets["fastkeys-journey.json"])
    timing_summary = format_timing_summary(journey)
    (output_dir / "fastkeys-timing-summary.txt").write_text(timing_summary, encoding="utf-8")
    print(f"PASS: {timing_summary.strip().replace(chr(10), '; ')}")
    print(f"PASS: same-run API 35 IME, Back, docked tray, non-overlap and write trace evidence ({run_id})")


def self_test() -> int:
    samples = [
        ("complete key action stream accepted", {**sample_journey(), "androidApi": 35}, True),
        ("API 35 keyboard-up baseline locks 144px and the 38x6 PTY grid", sample_journey(), True),
        ("six narrow dock controls fit 330px without horizontal scrolling", sample_journey(), True),
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
        ("nested rounded terminal panel rejected",
         with_nested_terminal_panel(sample_journey()), False),
        ("terminal viewport contact within the 0.01px CSS rounding epsilon accepted",
         with_terminal_viewport_overlap(sample_journey(), 0.005), True),
        ("terminal viewport overlap greater than 0.01px CSS rejected",
         with_terminal_viewport_overlap(sample_journey(), 0.0101), False),
        ("catalog sheet composer overlap rejected",
         with_intersecting_catalog_sheet(sample_journey()), False),
        ("Ctrl catalog horizontal overflow rejected",
         with_horizontal_catalog_overflow(sample_journey()), False),
        ("main catalog with an uneven 7+3 row split rejected",
         with_unbalanced_main_catalog_rows(sample_journey(), 7), False),
        ("main catalog with an uneven 6+4 row split rejected",
         with_unbalanced_main_catalog_rows(sample_journey(), 6), False),
        ("main catalog with an uneven 4+6 row split rejected",
         with_unbalanced_main_catalog_rows(sample_journey(), 4), False),
        ("main catalog with an extra vertical row rejected",
         with_second_catalog_row(sample_journey()), False),
        ("rejected 144px filled two-row catalog hierarchy fails the visual contract",
         with_rejected_catalog_hierarchy(sample_journey()), False),
        ("catalog page without the prompt composer launcher rejected",
         with_missing_prompt_composer_launcher(sample_journey()), False),
        ("separate prompt-dictation dock shortcut rejected",
         with_separate_prompt_dictation_shortcut(sample_journey()), False),
        ("Prompt launcher lacks its accessible title rejected",
         with_wrong_prompt_composer_launcher_title(sample_journey()), False),
        ("visible Prompt caption inside the dock launcher accepted",
         with_visible_prompt_composer_caption(sample_journey()), True),
        ("missing Prompt button caption rejected",
         with_missing_prompt_composer_caption(sample_journey()), False),
        ("duplicate Stop destination label on the terminal mic rejected",
         with_visible_terminal_mic_caption(sample_journey()), False),
        ("old Cursor caption on idle terminal dictation target rejected",
         with_old_terminal_idle_mic_caption(sample_journey()), False),
        ("Prompt launcher icon is hidden rejected",
         with_hidden_prompt_composer_icon(sample_journey()), False),
        ("Prompt launcher icon size is inconsistent with its bounds rejected",
         with_mismatched_prompt_composer_icon_size(sample_journey()), False),
        ("oversized Prompt control rejected",
         with_oversized_prompt_composer_launcher(sample_journey()), False),
        ("catalog page hiding the terminal heading rejected",
         with_hidden_terminal_heading(sample_journey()), False),
        ("catalog page with fewer than five measured terminal rows rejected",
         with_too_few_terminal_rows(sample_journey()), False),
        ("catalog geometry without same-run API 35 IME evidence rejected",
         with_missing_catalog_ime_evidence(sample_journey()), False),
        ("prompt composer entry without its modal dictation target rejected",
         with_missing_prompt_composer_entry(sample_journey()), False),
        ("clipped catalog title rejected",
         with_clipped_catalog_title(sample_journey()), False),
        ("overlapping catalog header content rejected",
         with_overlapping_catalog_header(sample_journey()), False),
        ("composer overlap from a docked fast-key tray rejected",
         with_intersecting_composer(sample_journey()), False),
        ("composer and key catalog competing for input rejected",
         with_composer_and_keys_competing(sample_journey()), False),
        ("composer return without a visible Android keyboard rejected",
         with_hidden_returned_composer_ime(sample_journey()), False),
        ("separate dictation row outside the dock rejected",
         with_separate_dictation_row(sample_journey()), False),
        ("missing integrated dictation mic rejected",
         with_missing_dictation_mic(sample_journey()), False),
        ("listening mic without its phase-specific accessible name rejected",
         with_wrong_dictation_accessible_name(sample_journey()), False),
        ("listening mic without its phase-specific title rejected",
         with_wrong_dictation_title(sample_journey()), False),
        ("dictation icon missing rejected",
         with_hidden_dictation_icon(sample_journey()), False),
        ("dictation icon outside its button rejected",
         with_misplaced_dictation_icon(sample_journey()), False),
        ("listening mic without its pressed Stop state rejected",
         with_unpressed_listening_dictation(sample_journey()), False),
        ("dictation button center miss rejected",
         with_missed_dictation_mic_center(sample_journey()), False),
        ("compressed listening chip without Kotlin-sized type and padding rejected",
         with_compressed_dictation_status_chip(sample_journey()), False),
        ("mic separated from the persistent control cluster rejected",
         with_far_right_dictation_mic(sample_journey()), False),
        ("missing Kotlin Enter divider rejected",
         with_missing_enter_divider(sample_journey()), False),
        ("live-width toolbar overflow rejected",
         with_live_row_overflow(sample_journey()), False),
        ("narrow toolbar controls are out of their required order rejected",
         with_reordered_narrow_toolbar_targets(sample_journey()), False),
        ("unnecessary horizontal scroll at 330px rejected",
         with_unnecessary_narrow_toolbar_scroll(sample_journey()), False),
        ("clipped final Dictate target at 330px rejected",
         with_clipped_narrow_toolbar_dictation_target(sample_journey()), False),
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
        ("dictation status shrinking the pre-dock viewport cap rejected",
         with_status_cap_reclaim(sample_journey()), False),
        ("Ctrl catalog and listening status reserve the full dock height",
         sample_journey(), True),
        ("traced receiver setup resizes settle before the stable dictation baseline",
         with_receiver_setup_resize_events(sample_journey()), True),
        ("unmatched receiver setup resize ACK rejected",
         with_unmatched_receiver_setup_ack(sample_journey()), False),
        ("IME-hidden ResizeObserver fit with matching accepted native PTY resize ACK accepted",
         with_background_resume_resize_observer_fit(sample_journey()), True),
        ("IME-hidden resume with an unmatched native PTY resize ACK rejected",
         with_unmatched_background_resume_resize_ack(sample_journey()), False),
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
        ("Main endpoint clamp clipping Tab at x=6.38 rejected",
         with_main_endpoint_tab_clipped(sample_journey()), False),
        ("Ctrl catalog reachability between 48dp row boundaries rejected",
         with_partial_ctrl_row_offset(sample_journey()), False),
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

    failure_run_id = "js2884-marker-failure-self-test"
    failure_assets = {
        "fastkeys-dictation-post-stop-marker-failure.png": b"\x89PNG\r\n\x1a\n" + b"fixture" * 200,
        "fastkeys-dictation-post-stop-marker-failure.json": json.dumps({
            "runId": failure_run_id,
            "stage": "post-stop-done-marker-timeout",
            "terminalEvidence": {},
            "geometry": {},
            "terminalVisibleText": "",
            "terminalInputChunks": [],
        }).encode(),
    }

    def make_asset_log(assets: dict[str, bytes], run_id: str) -> str:
        lines = []
        for name, payload in assets.items():
            encoded = base64.b64encode(payload).decode()
            chunks = [encoded[index:index + 512] for index in range(0, len(encoded), 512)]
            lines.append(f"I/{TAG} BEGIN|{run_id}|{name}|{len(chunks)}|{hashlib.sha256(payload).hexdigest()}")
            lines.extend(f"I/{TAG} DATA|{run_id}|{name}|{index}|{chunk}"
                         for index, chunk in enumerate(chunks))
            lines.append(f"I/{TAG} END|{run_id}|{name}")
        return "\n".join(lines)

    def make_failure_log(assets: dict[str, bytes]) -> str:
        return make_asset_log(assets, failure_run_id)

    try:
        preserved = parse_assets(make_failure_log(failure_assets), failure_run_id, preserve_on_failure=True)
        preservation_ok = preserved == failure_assets
    except ExtractionFailure:
        preservation_ok = False
    if not preservation_ok:
        print("FAIL: marker-failure capture does not preserve hash-checked same-run evidence", file=sys.stderr)
        failures += 1
    else:
        print("PASS: marker-failure capture preserves hash-checked same-run screenshot and terminal state")
    diagnostic_png = b"\x89PNG\r\n\x1a\n" + b"diagnostic-pixels" * 100
    prompt_phase_assets = {
        name: diagnostic_png for name in REQUIRED_ASSETS
        if name.endswith(".png")
    }
    prompt_phase_assets["fastkeys-journey.json"] = json.dumps(sample_journey()).encode()
    prompt_phase_run_id = "js2897-prompt-dictation-self-test"
    try:
        accepted = parse_assets(make_asset_log(prompt_phase_assets, prompt_phase_run_id), prompt_phase_run_id)
        prompt_phases_accepted = PROMPT_DICTATION_SCREENSHOTS.issubset(accepted)
    except ExtractionFailure:
        prompt_phases_accepted = False
    if not prompt_phases_accepted:
        print("FAIL: complete prompt dictation recording/transcribing/review artifacts are not accepted", file=sys.stderr)
        failures += 1
    else:
        print("PASS: complete prompt dictation recording/transcribing/review artifacts are accepted")
    for missing_phase in PROMPT_DICTATION_SCREENSHOTS:
        incomplete_phases = dict(prompt_phase_assets)
        incomplete_phases.pop(missing_phase)
        try:
            parse_assets(make_asset_log(incomplete_phases, prompt_phase_run_id), prompt_phase_run_id)
            missing_phase_rejected = False
        except ExtractionFailure:
            missing_phase_rejected = True
        if not missing_phase_rejected:
            print(f"FAIL: completed journey missing {missing_phase} was accepted", file=sys.stderr)
            failures += 1
        else:
            print(f"PASS: completed journey requires {missing_phase}")
    keyboard_failure_assets = {
        "fastkeys-ime-open.png": diagnostic_png,
        "fastkeys-row-closed-ime-open.png": diagnostic_png,
        "fastkeys-row-closed-ime-open-viewport.png": diagnostic_png,
    }
    try:
        preserved = parse_assets(make_failure_log(keyboard_failure_assets), failure_run_id,
                                 preserve_test_failure=True)
        keyboard_preservation_ok = preserved == keyboard_failure_assets
    except ExtractionFailure:
        keyboard_preservation_ok = False
    if not keyboard_preservation_ok:
        print("FAIL: pre-assert keyboard screenshots are not preserved as failure-only evidence", file=sys.stderr)
        failures += 1
    else:
        print("PASS: pre-assert keyboard screenshots are preserved as failure-only evidence")
    incomplete_keyboard_assets = dict(keyboard_failure_assets)
    incomplete_keyboard_assets.pop("fastkeys-row-closed-ime-open-viewport.png")
    try:
        parse_assets(make_failure_log(incomplete_keyboard_assets), failure_run_id,
                     preserve_test_failure=True)
        incomplete_keyboard_rejected = False
    except ExtractionFailure:
        incomplete_keyboard_rejected = True
    if not incomplete_keyboard_rejected:
        print("FAIL: failure preservation accepted missing pre-assert terminal screenshot", file=sys.stderr)
        failures += 1
    else:
        print("PASS: failure preservation requires the pre-assert terminal screenshot")
    try:
        parse_assets(make_failure_log({"fastkeys-dictation-post-stop-marker-failure.json": failure_assets[
            "fastkeys-dictation-post-stop-marker-failure.json"]}), failure_run_id, preserve_on_failure=True)
        missing_failure_screenshot_rejected = False
    except ExtractionFailure:
        missing_failure_screenshot_rejected = True
    if not missing_failure_screenshot_rejected:
        print("FAIL: marker-failure preservation accepted missing screenshot evidence", file=sys.stderr)
        failures += 1
    else:
        print("PASS: marker-failure preservation requires its same-run screenshot")
    try:
        parse_assets(make_failure_log(failure_assets), failure_run_id)
        failure_capture_accepted = True
    except ExtractionFailure:
        failure_capture_accepted = False
    if failure_capture_accepted:
        print("FAIL: marker-failure diagnostics can be accepted as a completed green journey", file=sys.stderr)
        failures += 1
    else:
        print("PASS: marker-failure diagnostics cannot pass as a completed green journey")

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
        "androidApi": 35,
        "sshPhase": "live",
        "terminalSlot": {"top": 64, "bottom": 258, "left": 0, "right": 400, "width": 400, "height": 194},
        "terminalViewport": {"top": 82, "bottom": 226, "left": 0, "right": 400, "width": 400, "height": 144},
        "terminalCanvas": {"top": 82, "bottom": 302, "left": 0, "right": 400, "width": 400, "height": 220},
        "terminalGridViewport": {"top": 82, "bottom": 226, "left": 0, "right": 400, "width": 400, "height": 144},
        "terminalXtermSurface": {"top": 86.76, "bottom": 225.76, "left": 4.76, "right": 395.24,
                                 "width": 390.48, "height": 139},
        "terminalPanel": {"top": 48, "bottom": 252, "left": 0, "right": 400, "width": 400, "height": 204},
        "layout": {".panel-heading--terminal": {"display": "flex", "top": 48, "bottom": 82, "height": 34}},
        "terminalSlotInsideTerminalPanel": True,
        "terminalViewportDockCapPx": 144,
        "terminalCanvasEndsAtDock": True,
        "terminalCanvasStyle": {"borderRadius": "0px", "backgroundColor": "rgb(0, 0, 0)",
                                "panelBackgroundColor": "rgb(0, 0, 0)"},
        "terminalHotkeysDockHeightPx": 49,
        "mobileHotkeys": {"top": 208, "bottom": 257, "left": 0, "right": 400, "width": 400, "height": 49},
        "catalogSheet": None,
        "catalogSheetRole": "",
        "catalogSheetModal": None,
        "catalogSheetBelowTerminalViewport": False,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": None,
        "catalogTabs": None,
        "catalogTabList": None,
        "catalogSurfaceStyle": None,
        "catalogKeyStyle": None,
        "mainKeyLayout": None,
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
            "label": "Dictate at terminal cursor", "top": 208, "bottom": 256, "left": 235, "right": 283,
            "width": 48, "height": 48, "insideViewport": True, "disabled": False, "micState": "idle",
            "title": "Dictate at terminal cursor", "visibleText": "Dictate", "destinationLabels": [],
            "destinationLabelBounds": None, "iconVisible": True,
            "iconBounds": {"left": 249, "top": 222, "right": 269, "bottom": 242, "width": 20, "height": 20},
            "pressed": False, "hitTarget": True,
        },
        "persistentRowMetrics": {"clientWidth": 400, "scrollWidth": 384, "scrollLeft": 0, "scrollable": False},
        "promptComposerLauncher": {
            "label": "Open prompt composer to type or dictate a prompt",
            "title": "Open prompt composer to type or dictate a prompt", "iconVisible": True,
            "visibleText": "Prompt",
            "iconInside": True, "iconComputedWidth": "20px", "iconComputedHeight": "20px",
            "iconBounds": {"left": 22, "top": 222, "right": 42, "bottom": 242, "width": 20, "height": 20},
            "top": 208, "bottom": 256, "left": 8, "right": 56,
            "width": 48, "height": 48, "visibleWidthInKeybar": 48, "visibleHeightInKeybar": 48,
            "insideViewport": True, "hitTarget": True, "disabled": False,
        },
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
    main_open = {
        **base,
        "homeSurface": "live",
        "keyboardComposerMode": True,
        "fastKeysPage": "main",
        "terminalSlot": {"top": 64, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 331},
        "mobileHotkeys": {"top": 250, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 145},
        "catalogSheet": {"top": 299, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 96},
        "catalogSheetRole": "region",
        "catalogSheetModal": None,
        "catalogSheetBelowTerminalViewport": True,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": {"label": "Select Ctrl keys", "width": 56, "height": 48,
            "insideViewport": True, "insideCatalogSheet": True},
        "catalogTabs": [
            {"label": "Select Main keys", "selected": True, "width": 56, "height": 48,
             "insideViewport": True, "insideCatalogSheet": True, "hitTarget": True},
            {"label": "Select Ctrl keys", "selected": False, "width": 56, "height": 48,
             "insideViewport": True, "insideCatalogSheet": True, "hitTarget": True},
        ],
        "catalogTabList": {"top": 299, "bottom": 347, "left": 300, "right": 420, "width": 120, "height": 48},
        "catalogSurfaceStyle": {"backgroundColor": "rgba(0, 0, 0, 0)", "borderRadius": "0px",
            "borderTopWidth": "0px", "borderRightWidth": "0px", "borderBottomWidth": "0px", "borderLeftWidth": "0px",
            "boxShadow": "rgb(33, 38, 45) 0px 1px 0px 0px inset"},
        "catalogKeyStyle": {"backgroundColor": "rgba(0, 0, 0, 0)", "borderRadius": "6px",
            "borderColor": "rgb(33, 38, 45)", "fontSize": "15px", "fontToken": "13px", "fontFamily": "monospace"},
        "mainKeyLayout": {"rowCount": 1, "columnGap": "8px", "maxRowWidth": 552},
        "catalogTitle": {"text": "Keys", "fits": True, "width": 24, "height": 16},
        "catalogHeader": {"top": 299, "bottom": 347, "left": 0, "right": 400, "width": 400, "height": 48},
        "catalogHeaderControlsDoNotOverlap": True,
        "catalogScrollerSelector": ".mobile-hotkeys__main-keys",
        "catalogScrollMetrics": {"clientWidth": 392, "scrollWidth": 568, "scrollLeft": 0,
            "clientHeight": 48, "scrollHeight": 48, "scrollTop": 0, "axis": "horizontal",
            "scrollSnapType": "x mandatory",
            "buttonRects": [{"keyId": key_id, "left": 12 + index * 56, "right": 60 + index * 56,
                "top": 347, "bottom": 395, "width": 48, "height": 48}
                for index, key_id in enumerate(EXPECTED_MAIN_KEY_IDS)]},
        "catalogScrollerBounds": {"top": 347, "bottom": 395, "left": 4, "right": 396, "width": 392, "height": 48},
        "catalogScrollerInsideSheet": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 145}},
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 145,
        "inlineDictationBar": {**base["inlineDictationBar"], "top": 250, "bottom": 395, "height": 145},
        "inlineDictationMic": {**base["inlineDictationMic"], "left": 235, "right": 283},
        "navigationTargets": [
            *base["navigationTargets"][:3],
            {**base["navigationTargets"][3], "label": "Close terminal keys", "left": 185, "right": 233},
        ],
    }
    dictation_idle = {
        **base,
        "visibleTerminalRows": 6,
        "terminalViewport": {"top": 82, "bottom": 226, "left": 0, "right": 400, "width": 400, "height": 144},
        "terminalCanvas": {"top": 82, "bottom": 302, "left": 0, "right": 400, "width": 400, "height": 220},
        "terminalGridViewport": {"top": 82, "bottom": 226, "left": 0, "right": 400, "width": 400, "height": 144},
    }
    dismissed = {**main_open, "keyboardVisible": False, "androidIme": {"visible": False, "imeBottomDp": 0}}
    ctrl = {
        **base,
        "fastKeysPage": "ctrl",
        "terminalSlot": {"top": 64, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 331},
        "mobileHotkeys": {"top": 250, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 145},
        "catalogSheet": {"top": 299, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 96},
        "catalogSheetRole": "region",
        "catalogSheetModal": None,
        "catalogSheetBelowTerminalViewport": True,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": {"label": "Select Main keys", "width": 56, "height": 48,
            "insideViewport": True, "insideCatalogSheet": True},
        "catalogTabs": [
            {"label": "Select Main keys", "selected": False, "width": 56, "height": 48,
             "insideViewport": True, "insideCatalogSheet": True, "hitTarget": True},
            {"label": "Select Ctrl keys", "selected": True, "width": 56, "height": 48,
             "insideViewport": True, "insideCatalogSheet": True, "hitTarget": True},
        ],
        "catalogTabList": {"top": 299, "bottom": 347, "left": 300, "right": 420, "width": 120, "height": 48},
        "catalogSurfaceStyle": {"backgroundColor": "rgba(0, 0, 0, 0)", "borderRadius": "0px",
            "borderTopWidth": "0px", "borderRightWidth": "0px", "borderBottomWidth": "0px", "borderLeftWidth": "0px",
            "boxShadow": "rgb(33, 38, 45) 0px 1px 0px 0px inset"},
        "catalogKeyStyle": {"backgroundColor": "rgba(0, 0, 0, 0)", "borderRadius": "6px",
            "borderColor": "rgb(33, 38, 45)", "fontSize": "15px", "fontToken": "13px", "fontFamily": "monospace"},
        "mainKeyLayout": {"rowCount": 0, "columnGap": "", "maxRowWidth": 0},
        "catalogTitle": {"text": "Keys", "fits": True, "width": 24, "height": 16},
        "catalogHeader": {"top": 299, "bottom": 347, "left": 0, "right": 400, "width": 400, "height": 48},
        "catalogHeaderControlsDoNotOverlap": True,
        "catalogScrollerSelector": ".mobile-hotkeys__ctrl-grid",
        "catalogScrollMetrics": {"clientWidth": 392, "scrollWidth": 392, "scrollLeft": 0,
            "clientHeight": 48, "scrollHeight": 288, "scrollTop": 0, "axis": "vertical",
            "scrollSnapType": "y mandatory",
            "buttonRects": [{"keyId": key_id, "left": 12 + (index % 5) * 56, "right": 60 + (index % 5) * 56,
                "top": 347 + (index // 5) * 48, "bottom": 395 + (index // 5) * 48, "width": 48, "height": 48}
                for index, key_id in enumerate(EXPECTED_CTRL_KEY_IDS)]},
        "catalogScrollerBounds": {"top": 347, "bottom": 395, "left": 4, "right": 396, "width": 392, "height": 48},
        "catalogScrollerInsideSheet": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 145}},
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 145,
        "inlineDictationBar": {**base["inlineDictationBar"], "top": 250, "bottom": 395, "height": 145},
        "inlineDictationMic": {**base["inlineDictationMic"], "left": 235, "right": 283},
        "navigationTargets": [
            *base["navigationTargets"][:3],
            {**base["navigationTargets"][3], "label": "Close terminal keys", "left": 185, "right": 233},
        ],
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
        "inlineDictationStatusText": f"Terminal · Listening · {dictate_text}",
        "inlineDictationStatusRow": {"top": 203, "bottom": 235, "left": 0, "right": 400, "width": 400, "height": 32},
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "inlineDictationStatusInsideSheetHeader": False,
        "dictationSheetHeader": None,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 81}},
        "terminalHotkeysDockHeightPx": 81,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 81},
        "inlineDictationMic": {**base["inlineDictationMic"], "label": "Stop dictating at terminal cursor",
            "title": "Stop dictating at terminal cursor", "micState": "listening",
            "pressed": True, "visibleText": "Stop",
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
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 81}},
        "terminalHotkeysDockHeightPx": 81,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 81},
        "inlineDictationMic": {**base["inlineDictationMic"], "label": "Transcribing speech for terminal cursor",
            "title": "Transcribing speech for terminal cursor", "disabled": True, "micState": "transcribing",
            "visibleText": "Wait", "top": 235, "bottom": 283},
    }
    final_inserted = {
        **dictation_idle,
        "inlineDictationTone": "success",
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 49}},
        "terminalHotkeysDockHeightPx": 49,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 257, "height": 49},
        "inlineDictationMic": {**base["inlineDictationMic"], "top": 208, "bottom": 256},
        "terminalViewportFocused": True,
        "activeElementInsideTerminal": True,
        "activeElementIsPromptDraft": False,
        "composerDraftValue": "",
        "keyboardComposerMode": True,
    }
    post_stop = {
        **final_inserted,
        "stage": "dictation-post-stop-keyboard-input",
        "sshAttachEpoch": 2,
        "resizeAcks": 6,
        "inlineDictationTone": "quiet",
        "inlineDictationStatusText": "",
        "inlineDictationPreview": "",
        "inlineDictationStatusVisible": False,
        "inlineDictationStatusRow": None,
    }
    error = {
        **dictation_idle,
        "resizeAcks": 6,
        "resizeStatus": "38 × 6 accepted by SSH",
        "inlineDictationTone": "error",
        "inlineDictationStatusText": "Terminal · Error · Dictation failed: network",
        "inlineDictationStatusRow": listening["inlineDictationStatusRow"],
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 81}},
        "terminalHotkeysDockHeightPx": 81,
        "terminalViewportDockCapPx": 144,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 283, "height": 81},
        "inlineDictationMic": {**base["inlineDictationMic"], "label": "Retry terminal cursor dictation",
            "title": "Retry terminal cursor dictation", "visibleText": "Retry", "micState": "error",
            "top": 235, "bottom": 283},
    }
    for status_stage in (listening, final_pending, final_inserted, post_stop, error):
        status_stage["inlineDictationStatusMetrics"] = {
            "height": 30, "fontSize": 11, "lineHeight": 16,
            "paddingTop": 6, "paddingBottom": 6,
        }
        status_stage["terminalSlot"] = {
            "top": 64, "bottom": 283, "left": 0, "right": 400, "width": 400, "height": 219,
        }
        status_stage["mobileHotkeys"] = {
            "top": 203, "bottom": 284, "left": 0, "right": 400, "width": 400, "height": 81,
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
    background_resumed = {
        **dictation_idle,
        "sshAttachEpoch": 2,
        "keyboardVisible": False,
        "androidIme": {"visible": False, "imeBottomDp": 0},
        "runtimeGeometry": {"cols": 37, "rows": 24, "cellHeight": 23.625},
        "visibleTerminalRows": 24,
        "terminalViewport": {"top": 139.5, "bottom": 721.36, "left": 14.8, "right": 397.4,
                             "width": 382.6, "height": 581.86},
        "terminalViewportDockCapPx": 0,
        "resizeAcks": 5,
        "resizePending": 0,
        "resizeFailures": 0,
        "resizeStatus": "37 × 24 accepted by SSH",
        "resizeFitEvents": [{"reason": "enabled-state-change", "cols": 37, "rows": 24,
                              "requestId": 5, "atMs": 120}],
        "resizeAckEvents": [{"requestId": 5, "cols": 37, "rows": 24, "attachEpoch": 2,
                              "result": "accepted", "atMs": 121}],
    }
    post_resume = {
        **dictation_idle,
        "sshAttachEpoch": 2,
        "resizeAcks": 6,
        "resizePending": 0,
        "resizeFailures": 0,
        "resizeStatus": "38 × 6 accepted by SSH",
        "resizeFitEvents": [{"reason": "window-resize", "cols": 38, "rows": 6,
                              "requestId": 6, "atMs": 130}],
        "resizeAckEvents": [{"requestId": 6, "cols": 38, "rows": 6, "attachEpoch": 2,
                              "result": "accepted", "atMs": 131}],
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
        "geometryOracleFile": "/tmp/js2884-fixture-keys-dictation.raw.geometry",
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
        "nativeStartCalls": 4,
        "nativeStopCalls": 3,
    }
    background_cancel = {
        "requestId": "req-background",
        "stopRequestId": "req-background",
        "lateResultEmitted": True,
        "stoppedEmitted": True,
        "writesBefore": 4,
        "writesAfter": 4,
        "nativeStartCalls": 2,
        "nativeStopCalls": 2,
        "resizeAcksBeforeResume": 4,
        "resizeAcksAfterResume": 5,
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
    composer_surface = {
        **base,
        "composerPanel": {"top": 200, "bottom": 303, "left": 0, "right": 400,
                          "width": 400, "height": 103},
        "fastKeysTray": {**base["fastKeysTray"], "intersectsComposerPanel": True},
        "fastKeysPage": "closed",
        "keyboardVisible": True,
        "keyboardComposerMode": True,
        "activeElementIsPromptDraft": True,
        "composerDraftValue": "keep this prompt",
        "androidIme": {"visible": True, "imeBottomDp": 260},
    }
    composer_to_keys = {
        **main_open,
        "composerPanel": None,
        "activeElementInsideTerminal": True,
        "activeElementIsPromptDraft": False,
        "keyboardVisible": True,
        "keyboardComposerMode": True,
        "androidIme": {"visible": True, "imeBottomDp": 260},
    }
    journey = {
        "androidApi": 35,
        "promptComposerEntry": {
            "role": "dialog", "modal": "true", "micLabel": "Dictate prompt draft",
            "micVisible": True, "micWidth": 48, "micHeight": 48,
            "keysLabel": "More terminal keys", "keysVisible": True, "keysWidth": 48, "keysHeight": 48,
        },
        "composerKeysTransition": {
            "draftBefore": "keep this prompt",
            "draftAfterReturn": "keep this prompt",
            "composerVisibleDuringKeys": False,
            "paletteOpenDuringKeys": True,
            "imeVisibleDuringKeys": True,
            "keyboardVisibleDuringKeys": True,
            "imeVisibleAfterReturn": True,
            "keyboardVisibleAfterReturn": True,
            "terminalGridBefore": {"cols": 38, "rows": 6, "cellHeight": 22.6},
            "terminalGridDuringKeys": {"cols": 38, "rows": 6, "cellHeight": 22.6},
            "stableDockControls": [
                {"label": label, "width": 48, "height": 48, "insideViewport": True,
                 "hitTarget": True,
                 "visibleText": {0: "Prompt", 1: "↑", 2: "↓", 3: "Enter", 4: "", 5: "Dictate"}.get(index, label),
                 "iconCount": 1 if index in (0, 4, 5) else 0}
                for index, label in enumerate((
                    "Open prompt composer to type or dictate a prompt", "Send Up arrow", "Send Down arrow",
                    "Send Enter", "Close terminal keys", "Dictate at terminal cursor",
                ))
            ],
            "inlineDictationMic": {
                "label": "Dictate at terminal cursor", "left": 360.0, "right": 408.0,
                "top": 700.0, "bottom": 748.0, "width": 48.0, "height": 48.0,
                "visibleText": "Dictate", "destinationLabels": [], "destinationLabelBounds": None,
            },
        },
        "terminalNativeDictation": {
            "bridge": "Capacitor SpeechRecognition plugin",
            "debugTestMode": True,
            "requestId": "native-terminal-request",
            "startOptions": {"requestId": "native-terminal-request", "testMode": True, "silenceWindowMs": 4000},
            "startResult": {"requestId": "native-terminal-request", "started": True},
            "stopResult": {"requestId": "native-terminal-request", "stopped": True},
            "partialInjection": {"requestId": "native-terminal-request", "emitted": True},
            "finalInjection": {"requestId": "native-terminal-request", "emitted": True},
            "finishInjection": {"requestId": "native-terminal-request", "emitted": True},
            "explicitStopRequestId": "native-terminal-request",
            "startCalls": 1,
            "stopCalls": 1,
            "writesBeforePartial": 4,
            "writesAfterPartial": 4,
            "writesAfterFinalBeforeStopped": 4,
            "writesAfterStopped": 5,
        },
        "narrowToolbarReachability": {
            "clientWidth": 330,
            "scrollWidth": 330,
            "maxScrollLeft": 0,
            "scrollable": False,
            "ptyWritesBefore": 2,
            "ptyWritesAfter": 2,
            "finalMic": {"left": 281, "right": 329,
                "label": "Dictate at terminal cursor", "title": "Dictate at terminal cursor", "visibleText": "Dictate",
                "iconVisible": True, "width": 48, "height": 48,
                "insideToolbar": True, "hitTarget": True},
            "targets": [
                {"label": label, "visibleText": visible_text,
                 "width": 48, "height": 48, "visibleWidth": 48, "visibleHeight": 48,
                 "hitTarget": True, "insideToolbar": True, "disabled": False}
                for label, visible_text in (
                    ("Open prompt composer to type or dictate a prompt", "Prompt"),
                    ("Send Up arrow", "↑"), ("Send Down arrow", "↓"),
                    ("Send Enter", "Enter"), ("More terminal keys", ""),
                    ("Dictate at terminal cursor", "Dictate"),
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
            "previewCleared": True, "writesBefore": 4, "writesAfter": 4, "nativeStartCalls": 3,
        },
        "dictationAttachCancel": attach_cancel,
        "dictationBackgroundCancel": background_cancel,
        "firstHotkeyWrites": expected_first_writes(),
        "allHotkeyWrites": expected_first_writes() + [{"key": "arrow-up", "bytes": [0x1B, 0x5B, 0x41]}],
        "catalogReachability": {
            "mainKeys": [
                {"keyId": key_id, "width": 48, "height": 48, "insideContent": True, "insideViewport": True,
                 "axis": "horizontal", "snapType": "x mandatory", "scrollLeft": 0, "scrollTop": 0,
                 "endpointBounds": {
                     "left": 14 + index * 56 - 168, "right": 62 + index * 56 - 168,
                     "top": 347, "bottom": 395, "width": 48, "height": 48,
                 },
                 "endpointContentBounds": {"left": 14, "right": 398, "top": 347, "bottom": 395,
                     "width": 384, "height": 48},
                 "endpointIntersectsContent": 14 + index * 56 - 168 < 398 and 62 + index * 56 - 168 > 14,
                 "endpointInsideContent": 14 + index * 56 - 168 >= 14 and 62 + index * 56 - 168 <= 398,
                 "endpointInsideViewport": 14 + index * 56 - 168 >= 0 and 62 + index * 56 - 168 <= 400,
                 "endpointScrollLeft": 168}
                for index, key_id in enumerate(EXPECTED_MAIN_KEY_IDS)
            ],
            "ctrlKeys": [
                {"keyId": key_id, "width": 48, "height": 48, "insideContent": True, "insideViewport": True,
                 "axis": "vertical", "snapType": "y mandatory", "scrollLeft": 0,
                 "scrollTop": (index // 5) * 48}
                for index, key_id in enumerate(EXPECTED_CTRL_KEY_IDS)
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
            {"stage": "composer-keys-before-ime-open", **composer_surface},
            {"stage": "composer-to-keys-ime-open", **composer_to_keys},
            {"stage": "keys-to-composer-return", **composer_surface},
            {"stage": "after-navigation-row-taps", **base},
            {"stage": "before-fast-keys", **base},
            {"stage": "fast-keys-main-open-ime-up", **main_open},
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
            {"stage": "dictation-background-cancel-resumed", **background_resumed},
            {"stage": "dictation-post-resume-ime-open", **post_resume},
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
        canvas = item.get("terminalCanvas")
        if not isinstance(canvas, dict):
            canvas = dict(viewport)
            item["terminalCanvas"] = canvas
        grid_viewport = item.get("terminalGridViewport")
        if not isinstance(grid_viewport, dict):
            grid_viewport = dict(viewport)
            item["terminalGridViewport"] = grid_viewport
        xterm_surface = item.get("terminalXtermSurface")
        if not isinstance(xterm_surface, dict):
            xterm_surface = dict(grid_viewport)
            item["terminalXtermSurface"] = xterm_surface
        cap = item.get("terminalViewportDockCapPx")
        grid_height = cap if isinstance(cap, (int, float)) and cap > 0 else viewport.get("height", 138)
        viewport_top = viewport.get("top", 64)
        viewport["height"] = grid_height
        viewport["bottom"] = viewport_top + grid_height
        grid_viewport.update({
            "top": viewport_top,
            "bottom": viewport_top + grid_height,
            "height": grid_height,
            "left": viewport.get("left", 0),
            "right": viewport.get("right", 400),
            "width": viewport.get("width", 400),
        })
        xterm_surface.update({
            "top": viewport_top + 4.76,
            "bottom": viewport_top + grid_height - 0.24,
            "height": max(0, grid_height - 5),
            "left": viewport.get("left", 0) + 4.76,
            "right": viewport.get("right", 400) - 4.76,
            "width": max(0, viewport.get("width", 400) - 9.52),
        })
        if isinstance(item.get("runtimeGeometry"), dict):
            cell_height = item["runtimeGeometry"].get("cellHeight", 23.6)
            item["visibleTerminalRows"] = math.floor((xterm_surface["height"] - 8) / cell_height)
        dock_height = (MOBILE_HOTKEYS_BASE_HEIGHT_PX
                       + (CATALOG_SHEET_HEIGHT_PX if page in {"main", "ctrl"} else 0)
                       + (INLINE_DICTATION_STATUS_ROW_HEIGHT_PX if status_visible else 0))
        canvas_height = max(canvas.get("height", 0), grid_height + dock_height + 1)
        canvas.update({"top": viewport_top, "height": canvas_height,
                       "bottom": viewport_top + canvas_height,
                       "left": viewport.get("left", 0), "right": viewport.get("right", 400),
                       "width": viewport.get("width", 400)})
        top = canvas["bottom"] - dock_height
        status_top = top + 1
        row_top = status_top + (INLINE_DICTATION_STATUS_ROW_HEIGHT_PX if status_visible else 0)
        left = 4
        right = 396
        width = right - left
        bottom = top + dock_height
        item["terminalCanvasEndsAtDock"] = abs(canvas.get("bottom", 0) - bottom) <= 0.5
        item["terminalCanvasStyle"] = {
            "borderRadius": "0px",
            "backgroundColor": "rgb(0, 0, 0)",
            "panelBackgroundColor": "rgb(0, 0, 0)",
        }
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
            slot_bottom = bottom
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
                "width": width, "height": CATALOG_SHEET_HEIGHT_PX,
            }
            item["catalogSheetRole"] = "region"
            item["catalogSheetModal"] = None
            item["catalogHeader"] = {
                "top": sheet_top, "bottom": sheet_top + 48,
                "left": left, "right": right, "width": width, "height": 48,
            }
            measured_scroller = item.get("catalogScrollerBounds")
            scroller_left = measured_scroller.get("left", left) if isinstance(measured_scroller, dict) else left
            scroller_right = measured_scroller.get("right", right) if isinstance(measured_scroller, dict) else right
            scroller_width = measured_scroller.get("width", scroller_right - scroller_left) if isinstance(measured_scroller, dict) else width
            item["catalogScrollerBounds"] = {
                "top": sheet_top + 48, "bottom": bottom,
                "left": scroller_left, "right": scroller_right, "width": scroller_width,
                "height": bottom - sheet_top - 48,
            }
            item["catalogScrollerInsideSheet"] = True
            scroll = item.get("catalogScrollMetrics")
            if not isinstance(scroll, dict):
                raise ExtractionFailure(f"{page} key catalog lacks measured scroll geometry")
        if status_visible:
            item["inlineDictationStatusRow"] = {
                "top": status_top, "bottom": status_top + INLINE_DICTATION_STATUS_ROW_HEIGHT_PX,
                "left": left, "right": right, "width": width,
                "height": INLINE_DICTATION_STATUS_ROW_HEIGHT_PX,
            }
            item["inlineDictationStatusAboveKeybar"] = True
            item["inlineDictationStatusInsideSheetHeader"] = False
        visual_viewport = item.get("visualViewport")
        if isinstance(visual_viewport, dict):
            item["imeEdgeCssY"] = visual_viewport.get("offsetTop", 0) + visual_viewport.get("height", 0)
        item["keybarRect"] = {
            "top": row_top, "bottom": row_top + 48,
            "left": left, "right": right, "width": width, "height": 48,
        }
        item["keybarClientRect"] = item["keybarRect"].copy()
        prompt_launcher = item.get("promptComposerLauncher")
        if isinstance(prompt_launcher, dict):
            item["promptComposerLauncher"] = {
                **prompt_launcher,
                "left": left + 4,
                "right": left + 52,
                "top": row_top,
                "bottom": row_top + 48,
                "width": 48,
                "height": 48,
                "visibleWidthInKeybar": 48,
                "visibleHeightInKeybar": 48,
                "insideViewport": True,
                "hitTarget": True,
                "disabled": False,
                "iconBounds": {
                    "left": left + 18, "right": left + 38,
                    "top": row_top + 14, "bottom": row_top + 34,
                    "width": 20, "height": 20,
                },
            }
        item["navigationTargets"] = [
            {**target, "left": control_left, "right": control_left + 48,
             "top": row_top, "bottom": row_top + 48,
             "visibleWidthInKeybar": 48, "visibleHeightInKeybar": 48}
            for target, control_left in zip(item.get("navigationTargets", []),
                                            (left + 52, left + 104, left + 161, left + 213))
        ]
        item["enterDivider"] = {
            "left": left + 156, "right": left + 157,
            "top": row_top + 12, "bottom": row_top + 36,
            "width": 1, "height": 24,
        }
        item["inlineDictationMic"] = {
            **item["inlineDictationMic"], "left": left + 265, "right": left + 313,
            "top": row_top, "bottom": row_top + 48,
            "visibleWidthInKeybar": 48, "visibleHeightInKeybar": 48,
            "iconBounds": {
                "left": left + 279, "right": left + 299,
                "top": row_top + 14, "bottom": row_top + 34,
                "width": 20, "height": 20,
            },
        }
        item["persistentRowMetrics"] = {
            "clientWidth": width, "scrollWidth": width, "scrollLeft": 0, "scrollable": False,
        }
        item["inlineDictationMicInsideBar"] = True
        item["inlineDictationMicInsideKeybar"] = True
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
    viewport = keyboard["terminalGridViewport"]
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
        viewport = item["terminalGridViewport"]
        old_height = viewport["height"]
        delta = viewport_height - old_height
        viewport["height"] = viewport_height
        viewport["bottom"] = viewport["top"] + viewport_height
        surface = item["terminalViewport"]
        surface["height"] += delta
        surface["bottom"] += delta
        cell_height = item["runtimeGeometry"]["cellHeight"]
        xterm_surface = item.get("terminalXtermSurface")
        xterm_surface_height = xterm_surface.get("height") if isinstance(xterm_surface, dict) else None
        item["visibleTerminalRows"] = math.floor((xterm_surface_height - 8) / cell_height)
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
    for stage_name, resize_acks in (
        ("dictation-background-cancel-resumed", 8),
        ("dictation-post-resume-ime-open", 9),
        ("dictation-post-stop-keyboard-input", 9),
        ("dictation-error-ime-open", 9),
    ):
        stage = next(item for item in copied["geometryTrace"] if item["stage"] == stage_name)
        stage["resizeAcks"] = resize_acks
        if stage_name == "dictation-error-ime-open":
            stage["resizeStatus"] = "38 × 6 accepted by SSH"
    copied["dictation"]["receiverSetupResizeAcks"] = 3
    copied["dictation"]["resizeAcksAtStableBaseline"] = 7
    copied["dictationBackgroundCancel"]["resizeAcksBeforeResume"] = 7
    copied["dictationBackgroundCancel"]["resizeAcksAfterResume"] = 8
    return copied


def with_unmatched_receiver_setup_ack(journey: dict[str, object]) -> dict[str, object]:
    copied = with_receiver_setup_resize_events(journey)
    ready = next(item for item in copied["geometryTrace"] if item["stage"] == "dictation-ready-ime-open")
    ready["resizeAckEvents"][0]["requestId"] = 999
    return copied


def with_background_resume_resize_observer_fit(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    resumed = next(item for item in copied["geometryTrace"]
                   if item["stage"] == "dictation-background-cancel-resumed")
    resumed["resizeFitEvents"] = [
        {"reason": "resize-observer", "cols": 37, "rows": 24, "requestId": 5, "atMs": 120},
        {"reason": "enabled-state-change", "cols": 37, "rows": 24, "requestId": None, "atMs": 122},
        {"reason": "resize-observer", "cols": 37, "rows": 24, "requestId": None, "atMs": 123},
    ]
    return copied


def with_unmatched_background_resume_resize_ack(journey: dict[str, object]) -> dict[str, object]:
    copied = with_background_resume_resize_observer_fit(journey)
    resumed = next(item for item in copied["geometryTrace"]
                   if item["stage"] == "dictation-background-cancel-resumed")
    resumed["resizeAckEvents"][0]["requestId"] = 999
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


def with_nested_terminal_panel(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["terminalCanvasStyle"]["borderRadius"] = "12px"
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


def with_composer_and_keys_competing(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "keys-to-composer-return":
            item["fastKeysPage"] = "main"
            item["catalogSheet"] = {"top": 100, "bottom": 250, "left": 0, "right": 400}
            item["fastKeysTray"]["intersectsComposerPanel"] = True
    return copied


def with_hidden_returned_composer_ime(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["composerKeysTransition"]["imeVisibleAfterReturn"] = False
    return copied


def with_intersecting_catalog_sheet(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["catalogSheetIntersectsComposer"] = True
    return copied


def with_horizontal_catalog_overflow(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["catalogScrollMetrics"]["scrollWidth"] = item["catalogScrollMetrics"]["clientWidth"] + 48
    return copied


def with_unbalanced_main_catalog_rows(journey: dict[str, object], first_row_count: int) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    button_rects = item["catalogScrollMetrics"]["buttonRects"]
    first_row_top = button_rects[0]["top"]
    for index, rect in enumerate(button_rects):
        rect["top"] = first_row_top + (0 if index < first_row_count else 48)
        rect["bottom"] = rect["top"] + 48
    return copied


def with_second_catalog_row(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["catalogScrollMetrics"]["scrollHeight"] = item["catalogScrollMetrics"]["clientHeight"] + 48
    return copied


def with_partial_ctrl_row_offset(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["catalogReachability"]["ctrlKeys"][5]["scrollTop"] = 56
    return copied


def with_main_endpoint_tab_clipped(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    tab = copied["catalogReachability"]["mainKeys"][3]
    tab["endpointBounds"]["left"] = 6.38
    tab["endpointBounds"]["right"] = 54.38
    tab["endpointIntersectsContent"] = True
    tab["endpointInsideContent"] = False
    tab["endpointScrollLeft"] = 183.619
    return copied


def with_rejected_catalog_hierarchy(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("fastKeysPage") not in {"main", "ctrl"}:
            continue
        old_sheet = item["catalogSheet"]
        old_sheet["height"] = 144
        old_sheet["bottom"] = old_sheet["top"] + 144
        old_dock_height = MOBILE_HOTKEYS_BASE_HEIGHT_PX + 144 + (
            INLINE_DICTATION_STATUS_ROW_HEIGHT_PX if item.get("inlineDictationStatusVisible") is True else 0
        )
        dock = item["mobileHotkeys"]
        dock["height"] = old_dock_height
        dock["bottom"] = dock["top"] + old_dock_height
        item["terminalHotkeysDockHeightPx"] = old_dock_height
        tray = item.get("fastKeysTray")
        if isinstance(tray, dict) and isinstance(tray.get("bounds"), dict):
            tray["bounds"]["height"] = old_dock_height
            tray["bounds"]["bottom"] = tray["bounds"]["top"] + old_dock_height
        scroller = item["catalogScrollerBounds"]
        scroller["height"] = 96
        scroller["bottom"] = scroller["top"] + 96
        scroll = item["catalogScrollMetrics"]
        scroll["clientHeight"] = 96
        scroll["scrollHeight"] = 96
        scroll["axis"] = "vertical"
        item["catalogSurfaceStyle"] = {
            "backgroundColor": "rgb(22, 27, 34)", "borderRadius": "10px",
            "borderTopWidth": "0px", "borderRightWidth": "0px", "borderBottomWidth": "0px",
            "borderLeftWidth": "0px", "boxShadow": "rgb(33, 38, 45) 0px 1px 0px 0px inset",
        }
        item["catalogKeyStyle"] = {
            "backgroundColor": "rgb(28, 33, 41)", "borderRadius": "6px", "fontSize": "18px",
        }
        if item.get("fastKeysPage") == "main":
            button_rects = scroll["buttonRects"]
            first_top = button_rects[0]["top"]
            for index, rect in enumerate(button_rects):
                rect["top"] = first_top + (48 if index >= 5 else 0)
                rect["bottom"] = rect["top"] + 48
            scroll["scrollWidth"] = scroll["clientWidth"]
            item["mainKeyLayout"] = {"rowCount": 2, "columnGap": "4px", "maxRowWidth": 352}
    return copied


def with_missing_prompt_composer_launcher(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item.pop("promptComposerLauncher", None)
    return copied


def with_separate_prompt_dictation_shortcut(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptDictationLauncher"] = {
        "label": "Dictate a prompt and review it before Insert or Send",
        "visibleText": "Dictate", "width": 48, "height": 48,
    }
    return copied


def with_oversized_prompt_composer_launcher(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptComposerLauncher"]["width"] = 60
    return copied


def with_wrong_prompt_composer_launcher_title(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptComposerLauncher"]["title"] = "Prompt"
    return copied


def with_visible_prompt_composer_caption(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptComposerLauncher"]["visibleText"] = "Prompt"
    return copied


def with_missing_prompt_composer_caption(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptComposerLauncher"]["visibleText"] = ""
    return copied


def with_visible_terminal_mic_caption(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "dictation-listening-ime-open")
    item["inlineDictationMic"]["visibleText"] = "Stop"
    item["inlineDictationMic"]["destinationLabels"] = ["Stop"]
    item["inlineDictationMic"]["destinationLabelBounds"] = {
        "top": 233, "bottom": 246, "left": 239, "right": 279,
    }
    return copied


def with_old_terminal_idle_mic_caption(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "dictation-idle-ime-open")
    item["inlineDictationMic"]["visibleText"] = "Cursor"
    return copied


def with_hidden_prompt_composer_icon(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptComposerLauncher"]["iconVisible"] = False
    return copied


def with_mismatched_prompt_composer_icon_size(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["promptComposerLauncher"]["iconComputedWidth"] = "0px"
    return copied


def with_reordered_narrow_toolbar_targets(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    targets = copied["narrowToolbarReachability"]["targets"]
    targets[0], targets[1] = targets[1], targets[0]
    return copied


def with_unnecessary_narrow_toolbar_scroll(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    row = copied["narrowToolbarReachability"]
    row["scrollWidth"] = row["clientWidth"] + 8
    row["maxScrollLeft"] = 8
    row["scrollable"] = True
    return copied


def with_clipped_narrow_toolbar_dictation_target(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["narrowToolbarReachability"]["targets"][-1]["visibleWidth"] = 47
    return copied


def with_hidden_terminal_heading(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-main-open-ime-up")
    item["layout"][".panel-heading--terminal"]["display"] = "none"
    return copied


def with_too_few_terminal_rows(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-ctrl-open-ime-up")
    item["visibleTerminalRows"] = 4
    return copied


def with_missing_catalog_ime_evidence(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-ctrl-open-ime-up")
    item["androidApi"] = 34
    item["androidIme"]["visible"] = False
    return copied


def with_missing_prompt_composer_entry(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    copied["promptComposerEntry"]["micVisible"] = False
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


def with_wrong_dictation_accessible_name(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationMic"]["label"] = "Dictate at terminal cursor"
    return copied


def with_wrong_dictation_title(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationMic"]["title"] = "Dictate at terminal cursor"
    return copied


def with_hidden_dictation_icon(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationMic"]["iconVisible"] = False
    return copied


def with_misplaced_dictation_icon(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            icon = item["inlineDictationMic"]["iconBounds"]
            icon["left"] = item["inlineDictationMic"]["right"] + 1
            icon["right"] = icon["left"] + icon["width"]
    return copied
    return copied


def with_unpressed_listening_dictation(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationMic"]["pressed"] = False
    return copied


def with_missed_dictation_mic_center(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationMic"]["hitTarget"] = False
    return copied


def with_compressed_dictation_status_chip(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item.get("stage") == "dictation-listening-ime-open":
            item["inlineDictationStatusRow"]["height"] = 16
            item["inlineDictationStatusMetrics"] = {
                "height": 16, "fontSize": 11, "lineHeight": 12,
                "paddingTop": 0, "paddingBottom": 0,
            }
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


def with_status_cap_reclaim(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    item = next(item for item in copied["geometryTrace"]
                if item["stage"] == "dictation-listening-ime-open")
    item["terminalViewportDockCapPx"] = 138
    item["terminalGridViewport"]["height"] = 138
    item["terminalGridViewport"]["bottom"] = item["terminalGridViewport"]["top"] + 138
    item["visibleTerminalRows"] = math.floor((138 - 8) / item["runtimeGeometry"]["cellHeight"])
    return copied


def with_ctrl_dictation_status(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    ctrl = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-ctrl-open-ime-up")
    copied["geometryTrace"].append({
        **ctrl,
        "stage": "dictation-listening-ctrl-open-ime-open",
        "mobileHotkeys": {"top": 234, "bottom": 411, "left": 0, "right": 400, "width": 400, "height": 177},
        "catalogSheet": {"top": 299, "bottom": 395, "left": 0, "right": 400, "width": 400, "height": 96},
        "catalogSheetModal": "false",
        "catalogSheetBelowTerminalViewport": True,
        "catalogSheetIntersectsComposer": False,
        "catalogPageAction": {"label": "Select Main keys", "width": 56, "height": 48,
            "insideViewport": True, "insideCatalogSheet": True},
        "catalogScrollMetrics": {"clientWidth": 384, "scrollWidth": 384, "scrollLeft": 0,
            "clientHeight": 48, "scrollHeight": 312, "scrollTop": 0, "axis": "vertical"},
        "terminalViewportDockCapPx": 144,
        "terminalHotkeysDockHeightPx": 177,
        "fastKeysTray": {**ctrl["fastKeysTray"], "bounds": {"height": 177}},
        "terminalSlot": {"top": 64, "bottom": 411, "left": 0, "right": 400, "width": 400, "height": 347},
        "inlineDictationBar": {**ctrl["inlineDictationBar"], "top": 234, "bottom": 411, "height": 177},
        "dictationSheetHeader": {"top": 299, "bottom": 347, "left": 0, "right": 400, "width": 400, "height": 48},
        "inlineDictationPhase": "listening",
        "inlineDictationTone": "quiet",
        "inlineDictationStatusText": "Terminal · Listening · echo test",
        "inlineDictationPreview": "echo test",
        "inlineDictationStatusRow": {"top": 234, "bottom": 266, "left": 89, "right": 336, "width": 247, "height": 32},
        "inlineDictationStatusMetrics": {"height": 30, "fontSize": 11, "lineHeight": 16,
            "paddingTop": 6, "paddingBottom": 6},
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "inlineDictationStatusInsideSheetHeader": False,
        "inlineDictationMic": {
            **ctrl["inlineDictationMic"], "label": "Stop dictating at terminal cursor", "title": "Stop dictating at terminal cursor",
            "visibleText": "Stop", "micState": "listening", "pressed": True,
            "top": 250, "bottom": 298, "left": 235, "right": 283,
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
    parser.add_argument("--preserve-on-failure", action="store_true",
                        help="extract hash-checked post-Stop marker failure evidence without accepting journey completion")
    parser.add_argument("--preserve-test-failure", action="store_true",
                        help="extract the pre-assert keyboard screenshots from an incomplete packaged journey")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.run_id or not args.logcat or not args.output_dir:
        parser.error("--run-id, --logcat, and --output-dir are required")
    try:
        extract(args.logcat, args.output_dir, args.run_id, preserve_on_failure=args.preserve_on_failure,
                preserve_test_failure=args.preserve_test_failure)
    except (OSError, ExtractionFailure) as error:
        print(f"BLOCK: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
