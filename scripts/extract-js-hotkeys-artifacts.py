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
    "fastkeys-tray-main-ime-open.png",
    "fastkeys-tray-ctrl-ime-open.png",
    "fastkeys-tray-ime-dismissed.png",
    "fastkeys-tray-closed.png",
    "fastkeys-reconnected-ime-open.png",
    "fastkeys-dictation-idle-ime-open.png",
    "fastkeys-dictation-listening-ime-open.png",
    "fastkeys-dictation-listening-ctrl-ime-open.png",
    "fastkeys-dictation-stopped-ime-open.png",
    "fastkeys-dictation-attach-cancel.png",
    "fastkeys-dictation-reattached-ime-open.png",
}
REQUIRED_ASSETS = SCREENSHOTS | {"fastkeys-journey.json"}
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
    should_preserve_grid = item.get("fastKeysPage") in {"main", "ctrl"} or item.get("inlineDictationStatusVisible") is True
    if should_preserve_grid and (
        isinstance(cap, bool) or not isinstance(cap, (int, float)) or cap <= 0
        or isinstance(viewport_height, bool) or not isinstance(viewport_height, (int, float))
        or abs(viewport_height - cap) > 0.5
    ):
        raise ExtractionFailure(f"{label} did not cap the terminal viewport at its pre-dock keyboard-up height")
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
    if item.get("inlineDictationBarCount") != 1 or item.get("inlineDictationMicCount") != 1:
        raise ExtractionFailure(f"{label} must contain exactly one inline dictation bar and mic")
    if (isinstance(mic.get("width"), bool) or not isinstance(mic.get("width"), (int, float))
            or mic["width"] < 47.9 or isinstance(mic.get("height"), bool)
            or not isinstance(mic.get("height"), (int, float)) or mic["height"] < 47.9):
        raise ExtractionFailure(f"{label} mic target is smaller than 48dp")
    if mic.get("insideViewport") is not True:
        raise ExtractionFailure(f"{label} mic target is clipped")
    if mic.get("disabled") is not (True if allow_disabled_mic else False):
        raise ExtractionFailure(f"{label} mic enabled state does not match its dictation phase")
    if item.get("inlineDictationBarInsideTray") is not True or item.get("inlineDictationMicInsideBar") is not True:
        raise ExtractionFailure(f"{label} dictation action is outside the persistent dock")
    if item.get("inlineDictationStatusVisible") is True:
        status_row = item.get("inlineDictationStatusRow")
        if (item.get("inlineDictationStatusOneLine") is not True
                or item.get("inlineDictationStatusInsideBar") is not True
                or item.get("inlineDictationStatusAboveKeybar") is not True
                or not isinstance(status_row, dict)
                or abs(status_row.get("height", 0) - 32) > 1):
            raise ExtractionFailure(f"{label} status chip is not a single row above the persistent keys")
    elif (item.get("inlineDictationPhase") != "idle" or item.get("inlineDictationTone") != "quiet"
          or item.get("inlineDictationStatusText") != ""):
        raise ExtractionFailure(f"{label} hides a meaningful dictation status")
    nav = item.get("navigationTargets")
    page = item.get("fastKeysPage")
    expected_nav_count = 4 if page == "closed" else 5
    if not isinstance(nav, list) or len(nav) != expected_nav_count:
        raise ExtractionFailure(f"{label} does not keep arrows, Enter, page control, and launcher visible")
    expected_nav = ("Send Up arrow", "Send Down arrow", "Send Enter")
    for index, target in enumerate(nav):
        if not isinstance(target, dict):
            raise ExtractionFailure(f"{label} persistent terminal navigation is missing a required action")
        if index < 3:
            label_matches = target.get("label") == expected_nav[index]
        elif page != "closed" and index == 3:
            label_matches = target.get("label") == (
                "Open Ctrl plus letter keys" if page == "main" else "Back to terminal hotkeys"
            )
        else:
            label_matches = target.get("label") == (
                "Open terminal hotkeys" if page == "closed" else "Close terminal hotkeys"
            )
        if not label_matches:
            raise ExtractionFailure(f"{label} persistent terminal navigation is missing a required action")
        if (target.get("disabled") is not False or target.get("insideViewport") is not True
                or target.get("width", 0) < 47.9 or target.get("height", 0) < 47.9):
            raise ExtractionFailure(f"{label} persistent terminal key is clipped, disabled, or below 48dp")
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

    for name in SCREENSHOTS:
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
        validate_docked_dictation_geometry(
            item,
            stage_name,
            allow_disabled_mic=stage_name == "dictation-final-awaiting-stopped",
        )
        tray_geometry = item.get("fastKeysTray")
        if (not isinstance(tray_geometry, dict)
                or tray_geometry.get("insideSlot") is not True
                or tray_geometry.get("belowTerminalViewport") is not True
                or tray_geometry.get("intersectsTerminalViewport") is not False
                or tray_geometry.get("intersectsComposerPanel") is not False):
            raise ExtractionFailure(f"{stage_name} dock overlaps xterm or composer, or leaves normal flow")

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
    expected_viewport_cap = math.ceil(idle_viewport_height)
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
    keyboard = by_name["keyboard-up-compact-row"]
    if keyboard.get("keyboardVisible") is not True:
        raise ExtractionFailure("keyboard-up DOM geometry says the keyboard is hidden")
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
                or tray.get("belowTerminalViewport") is not True
                or tray.get("intersectsTerminalViewport") is not False
                or tray.get("intersectsComposerPanel") is not False):
            raise ExtractionFailure(f"{label} is not docked outside the terminal viewport")
        if (item.get("inlineDictationBar") is not None
                and item.get("inlineDictationBarInsideTray") is not True):
            raise ExtractionFailure(f"{label} has a separate dictation row outside the persistent dock")
    main_bounds = opened["fastKeysTray"].get("bounds", {})
    ctrl_bounds = ctrl["fastKeysTray"].get("bounds", {})
    closed_bounds = closed["fastKeysTray"].get("bounds", {})
    if any(not isinstance(bounds, dict) for bounds in (main_bounds, ctrl_bounds, closed_bounds)):
        raise ExtractionFailure("docked tray geometry is missing bounds")
    if (abs(main_bounds.get("height", 0) - 96) > 0.5
            or abs(closed_bounds.get("height", 0) - 48) > 0.5
            or abs(ctrl_bounds.get("height", 0) - 148) > 0.5):
        raise ExtractionFailure("fast-key tray did not keep the 48dp closed, 96dp main, and 148dp Ctrl in-flow heights")
    before_grid = before.get("runtimeGeometry")
    if not isinstance(before_grid, dict):
        raise ExtractionFailure("fast-key open comparison lacks the initial xterm dimensions")
    baseline_viewport = before.get("terminalViewport")
    if not isinstance(baseline_viewport, dict):
        raise ExtractionFailure("fast-key open comparison lacks the pre-dock terminal viewport height")
    baseline_viewport_height = baseline_viewport.get("height")
    if (isinstance(baseline_viewport_height, bool)
            or not isinstance(baseline_viewport_height, (int, float))):
        raise ExtractionFailure("fast-key open comparison lacks a measured terminal viewport height")
    expected_viewport_cap = math.ceil(baseline_viewport_height)
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
        ("composer overlap from a docked fast-key tray rejected",
         with_intersecting_composer(sample_journey()), False),
        ("separate dictation row outside the dock rejected",
         with_separate_dictation_row(sample_journey()), False),
        ("missing integrated dictation mic rejected",
         with_missing_dictation_mic(sample_journey()), False),
        ("mic separated from the persistent control cluster rejected",
         with_far_right_dictation_mic(sample_journey()), False),
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
        "runtimeGeometry": {"cols": 50, "rows": 20},
        "resizeAcks": 4,
        "resizeFitEvents": [],
        "resizeAckEvents": [],
        "keyboardVisible": True,
        "sshPhase": "live",
        "terminalSlot": {"top": 64, "bottom": 283, "left": 0, "right": 400, "width": 400, "height": 219},
        "terminalViewport": {"top": 64, "bottom": 234, "left": 0, "right": 400, "width": 400, "height": 170},
        "terminalViewportDockCapPx": 0,
        "terminalHotkeysDockHeightPx": 48,
        "mobileHotkeys": {"top": 235, "bottom": 283, "left": 0, "right": 400, "width": 400, "height": 48},
        "visualViewport": {"height": 520, "width": 400, "offsetTop": 0},
        "fastKeysTray": {
            "insideSlot": True,
            "belowTerminalViewport": True,
            "intersectsTerminalViewport": False,
            "intersectsComposerPanel": False,
            "bounds": {"height": 48},
        },
        "fastKeysPage": "closed",
        "sshAttachEpoch": 2,
        "inlineDictationBar": {"top": 235, "bottom": 283, "left": 0, "right": 400, "width": 400, "height": 48},
        "inlineDictationMic": {
            "label": "Dictate to terminal", "top": 235, "bottom": 283, "left": 224, "right": 272,
            "width": 48, "height": 48, "insideViewport": True, "disabled": False, "micState": "idle",
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
        "inlineDictationMicInsideBar": True,
        "inlineDictationBarInsideTray": True,
        "androidIme": {"visible": True, "imeBottomDp": 260},
        "navigationTargets": [
            {"label": label, "top": 235, "bottom": 283, "left": left, "right": left + 48,
             "width": 48, "height": 48, "insideViewport": True, "disabled": False}
            for label, left in zip(
                ("Send Up arrow", "Send Down arrow", "Send Enter", "Open terminal hotkeys"),
                (0, 56, 112, 168),
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
        "terminalSlot": {"top": 64, "bottom": 331, "left": 0, "right": 400, "width": 400, "height": 267},
        "mobileHotkeys": {"top": 235, "bottom": 331, "left": 0, "right": 400, "width": 400, "height": 96},
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 96}},
        "terminalViewportDockCapPx": 170,
        "terminalHotkeysDockHeightPx": 96,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 331, "height": 96},
        "inlineDictationMic": {**base["inlineDictationMic"], "left": 288, "right": 336},
        "navigationTargets": [
            *base["navigationTargets"][:3],
            {**base["navigationTargets"][3], "label": "Open Ctrl plus letter keys", "left": 168, "right": 224},
            {**base["navigationTargets"][3], "label": "Close terminal hotkeys", "left": 232, "right": 280},
        ],
        "composerPanel": {"height": 100},
    }
    dismissed = {**main_open, "keyboardVisible": False, "androidIme": {"visible": False, "imeBottomDp": 0}}
    ctrl = {
        **base,
        "fastKeysPage": "ctrl",
        "terminalSlot": {"top": 64, "bottom": 383, "left": 0, "right": 400, "width": 400, "height": 319},
        "mobileHotkeys": {"top": 235, "bottom": 383, "left": 0, "right": 400, "width": 400, "height": 148},
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 148}},
        "terminalViewportDockCapPx": 170,
        "terminalHotkeysDockHeightPx": 148,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 383, "height": 148},
        "inlineDictationMic": {**base["inlineDictationMic"], "left": 288, "right": 336},
        "navigationTargets": [
            *base["navigationTargets"][:3],
            {**base["navigationTargets"][3], "label": "Back to terminal hotkeys", "left": 168, "right": 224},
            {**base["navigationTargets"][3], "label": "Close terminal hotkeys", "left": 232, "right": 280},
        ],
        "composerPanel": {"height": 100},
    }
    reattached = {
        **base,
        "keyboardComposerMode": True,
        "runtimeGeometry": {"cols": 37, "rows": 5, "cellHeight": 23.6},
    }
    dictate_text = "echo test"
    dictate_hex = dictate_text.encode("utf-8").hex()
    listening = {
        **base,
        "inlineDictationPhase": "listening",
        "inlineDictationPreview": dictate_text,
        "inlineDictationStatusText": f"Listening · {dictate_text}",
        "inlineDictationStatusRow": {"top": 235, "bottom": 267, "left": 0, "right": 400, "width": 400, "height": 32},
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 170,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 315, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "label": "Stop terminal dictation", "micState": "listening",
            "top": 267, "bottom": 315},
    }
    final_pending = {
        **base,
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
        "terminalViewportDockCapPx": 170,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 315, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "disabled": True, "micState": "transcribing",
            "top": 267, "bottom": 315},
    }
    final_inserted = {
        **base,
        "inlineDictationTone": "success",
        "inlineDictationStatusText": "Inserted at the cursor. Press Enter to run.",
        "inlineDictationStatusRow": listening["inlineDictationStatusRow"],
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 170,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 315, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "top": 267, "bottom": 315},
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
        **base,
        "inlineDictationTone": "error",
        "inlineDictationStatusText": "Dictation failed: network",
        "inlineDictationStatusRow": listening["inlineDictationStatusRow"],
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "fastKeysTray": {**base["fastKeysTray"], "bounds": {"height": 80}},
        "terminalHotkeysDockHeightPx": 80,
        "terminalViewportDockCapPx": 170,
        "inlineDictationBar": {**base["inlineDictationBar"], "bottom": 315, "height": 80},
        "inlineDictationMic": {**base["inlineDictationMic"], "top": 267, "bottom": 315},
    }
    for status_stage in (listening, final_pending, final_inserted, post_stop, error):
        status_stage["terminalSlot"] = {
            "top": 64, "bottom": 315, "left": 0, "right": 400, "width": 400, "height": 251,
        }
        status_stage["mobileHotkeys"] = {
            "top": 235, "bottom": 315, "left": 0, "right": 400, "width": 400, "height": 80,
        }
        status_stage["navigationTargets"] = [
            {**target, "top": 267, "bottom": 315}
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
            {"stage": "fast-keys-main-catalog-reachable", **main_open},
            {"stage": "fast-keys-ctrl-open-ime-up", **ctrl},
            {"stage": "fast-keys-ctrl-catalog-reachable", **ctrl},
            {"stage": "fast-keys-closed-ime-up", **base},
            {"stage": "fast-keys-open-ime-dismissed", **dismissed},
            {"stage": "fast-keys-open-ime-up-before-back", **main_open},
            {"stage": "dictation-idle-ime-open", **base},
            {"stage": "dictation-ready-ime-open", **base},
            {"stage": "dictation-listening-ime-open", **listening},
            {"stage": "dictation-final-awaiting-stopped", **final_pending},
            {"stage": "dictation-final-inserted", **final_inserted},
            {"stage": "dictation-post-stop-keyboard-input", **post_stop},
            {"stage": "dictation-error-ime-open", **error},
            {"stage": "dictation-attach-cancel-complete", **attach_cancelled},
            {"stage": "dictation-reattached-ime-open", **dictation_reattached},
            {"stage": "dictation-background-cancel-resumed", **dictation_reattached},
            {"stage": "after-reconnect", **reattached},
            {"stage": "reconnected-keybar-ime-up", **base},
            lost,
        ],
    }
    return with_ctrl_dictation_status(journey)


def with_changed_tray_resize(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-main-open-ime-up":
            item["resizeAcks"] += 1
    return copied


def with_receiver_setup_resize_events(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    ready = next(item for item in copied["geometryTrace"] if item["stage"] == "dictation-ready-ime-open")
    fit_events = []
    ack_events = []
    dimensions = ((50, 19), (49, 24), (50, 20))
    for index, (cols, rows) in enumerate(dimensions):
        request_id = 101 + index
        at_ms = 10.0 + index * 2
        fit_events.append({
            "atMs": at_ms, "marker": "send-receiver-command:fixture", "reason": "resize-observer",
            "cols": cols, "rows": rows, "requestId": request_id, "hostWidth": 400, "hostHeight": 170,
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


def with_intersecting_composer(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "fast-keys-ctrl-open-ime-up":
            item["fastKeysTray"]["intersectsComposerPanel"] = True
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


def with_far_right_dictation_mic(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "dictation-idle-ime-open":
            item["inlineDictationMic"]["left"] = item["mobileHotkeys"]["right"] - 48
            item["inlineDictationMic"]["right"] = item["mobileHotkeys"]["right"]
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
            item["terminalSlot"]["height"] = 318
    return copied


def with_ctrl_dictation_status(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    ctrl = next(item for item in copied["geometryTrace"] if item["stage"] == "fast-keys-ctrl-open-ime-up")
    copied["geometryTrace"].append({
        **ctrl,
        "stage": "dictation-listening-ctrl-open-ime-open",
        "terminalSlot": {"top": 64, "bottom": 415, "left": 0, "right": 400, "width": 400, "height": 351},
        "mobileHotkeys": {"top": 235, "bottom": 415, "left": 0, "right": 400, "width": 400, "height": 180},
        "terminalHotkeysDockHeightPx": 180,
        "fastKeysTray": {**ctrl["fastKeysTray"], "bounds": {"height": 180}},
        "inlineDictationBar": {**ctrl["inlineDictationBar"], "bottom": 415, "height": 180},
        "navigationTargets": [
            {**target, "top": 267, "bottom": 315}
            for target in ctrl["navigationTargets"]
        ],
        "inlineDictationPhase": "listening",
        "inlineDictationTone": "quiet",
        "inlineDictationStatusText": "Listening · echo test",
        "inlineDictationPreview": "echo test",
        "inlineDictationStatusRow": {"top": 235, "bottom": 267, "left": 0, "right": 400, "width": 400, "height": 32},
        "inlineDictationStatusVisible": True,
        "inlineDictationStatusOneLine": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "inlineDictationMic": {
            **ctrl["inlineDictationMic"], "label": "Stop terminal dictation", "micState": "listening",
            "top": 267, "bottom": 315, "left": 288, "right": 336,
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
