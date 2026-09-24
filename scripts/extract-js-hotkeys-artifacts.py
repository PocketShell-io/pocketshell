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
    "fastkeys-palette-ime-open.png",
    "fastkeys-palette-ime-dismissed.png",
    "fastkeys-palette-closed.png",
    "fastkeys-reconnected-ime-open.png",
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


def format_timing_summary(journey: dict[str, object]) -> str:
    return (
        f"connect_to_prompt_ms={journey['connectToPromptMs']}\n"
        f"tap_to_visible_output_ms={journey['tapToVisibleOutputMs']}\n"
        f"reconnect_tap_to_visible_output_ms={journey['reconnectTapToVisibleOutputMs']}\n"
    )


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
        "before-palette",
        "palette-open-ime-up",
        "palette-dragged-ime-up",
        "palette-open-ime-dismissed",
        "after-reconnect",
        "reconnected-keybar-ime-up",
        "after-reconnect-loss",
    }
    if not required_stages.issubset(by_name):
        raise ExtractionFailure(f"missing geometry stages: {sorted(required_stages - set(by_name))}")
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

    before = by_name["before-palette"]
    opened = by_name["palette-open-ime-up"]
    if opened.get("keyboardVisible") is not True or opened.get("palette", {}).get("insideSlot") is not True:
        raise ExtractionFailure("floating palette did not open inside the terminal slot with IME visible")
    before_grid, open_grid = before.get("runtimeGeometry"), opened.get("runtimeGeometry")
    if not isinstance(before_grid, dict) or not isinstance(open_grid, dict):
        raise ExtractionFailure("palette open comparison lacks xterm dimensions")
    if (before_grid.get("cols"), before_grid.get("rows")) != (open_grid.get("cols"), open_grid.get("rows")):
        raise ExtractionFailure("opening the palette changed terminal cell geometry")
    if before.get("resizeAcks") != opened.get("resizeAcks"):
        raise ExtractionFailure("opening the palette sent an SSH PTY resize")
    dragged = by_name["palette-dragged-ime-up"]
    if dragged.get("palette", {}).get("insideSlot") is not True:
        raise ExtractionFailure("dragged palette escaped the terminal slot")
    dismissed = by_name["palette-open-ime-dismissed"]
    if dismissed.get("androidIme", {}).get("visible") is not False:
        raise ExtractionFailure("first Android Back did not dismiss the real IME")
    if dismissed.get("palette", {}).get("insideSlot") is not True or dismissed.get("sshPhase") != "live":
        raise ExtractionFailure("the palette or live session was lost while dismissing the IME")
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
    print(f"PASS: same-run API 35 IME, Back, palette, write trace evidence ({run_id})")


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
        ("resized PTY on open rejected",
         with_changed_palette_resize(sample_journey()), False),
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
        "keyboardVisible": True,
        "sshPhase": "live",
        "palette": {"insideSlot": True},
        "androidIme": {"visible": True, "imeBottomDp": 260},
        "navigationTargets": [
            {"label": label, "width": 48, "height": 48, "insideViewport": True, "disabled": False}
            for label in ("Send Up arrow", "Send Down arrow", "Send Enter", "Open terminal hotkeys")
        ],
        "hotkeyControls": [
            {"testId": "mobile-hotkeys"},
            {"keyId": "arrow-up", "disabled": False},
        ],
    }
    dismissed = {**base, "keyboardVisible": False, "androidIme": {"visible": False, "imeBottomDp": 0}}
    reattached = {
        **base,
        "keyboardComposerMode": True,
        "runtimeGeometry": {"cols": 37, "rows": 5, "cellHeight": 23.6},
    }
    lost = {
        **base,
        "stage": "after-reconnect-loss",
        "keyboardVisible": False,
        "sshPhase": "idle",
        "mobileHotkeys": None,
        "navigationTargets": [],
        "hotkeyControls": [],
    }
    return {
        "androidApi": 35,
        "connectToPromptMs": 1100,
        "tapToVisibleOutputMs": 80,
        "reconnectTapToVisibleOutputMs": 45,
        "firstDoneMarker": "PS2884_DONE_fixture",
        "resumedDoneMarker": "PS2884_RESUMED_DONE_fixture",
        "firstHotkeyWrites": expected_first_writes(),
        "allHotkeyWrites": expected_first_writes() + [{"key": "arrow-up", "bytes": [0x1B, 0x5B, 0x41]}],
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
            {"stage": "before-palette", **base},
            {"stage": "palette-open-ime-up", **base},
            {"stage": "palette-dragged-ime-up", **base},
            {"stage": "palette-open-ime-dismissed", **dismissed},
            {"stage": "after-reconnect", **reattached},
            {"stage": "reconnected-keybar-ime-up", **base},
            lost,
        ],
    }


def with_changed_palette_resize(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "palette-open-ime-up":
            item["resizeAcks"] += 1
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
