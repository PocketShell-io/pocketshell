#!/usr/bin/env python3
"""Extract same-run fast-key screenshots and validate packaged geometry/write evidence."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
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
    if reattached.get("keyboardComposerMode") is not False:
        raise ExtractionFailure("reattached terminal evidence was captured after focus moved to the composer")
    if reattached.get("androidIme", {}).get("visible") is not True:
        raise ExtractionFailure("reattached terminal geometry lacks a visible native IME")
    reattached_grid = reattached.get("runtimeGeometry")
    if not isinstance(reattached_grid, dict) or reattached_grid.get("rows", 0) < 5:
        raise ExtractionFailure("reattached terminal retains fewer than five xterm rows with the terminal-focused IME")
    if not isinstance(reattached_grid.get("cellHeight"), (int, float)) or reattached_grid["cellHeight"] <= 0:
        raise ExtractionFailure("reattached xterm geometry lacks a positive measured cell height")


def extract(log_path: Path, output_dir: Path, run_id: str) -> None:
    assets = parse_assets(log_path.read_text(encoding="utf-8", errors="replace"), run_id)
    output_dir.mkdir(parents=True, exist_ok=True)
    for name, payload in assets.items():
        (output_dir / name).write_bytes(payload)
        print(f"PASS: extracted {name} ({len(payload)} bytes)")
    print(f"PASS: same-run API 35 IME, Back, palette, write trace evidence ({run_id})")


def self_test() -> int:
    samples = [
        ("complete key action stream accepted", {**sample_journey(), "androidApi": 35}, True),
        ("missing IME proof rejected", {**sample_journey(), "geometryTrace": []}, False),
        ("single Ctrl hold split into extra write rejected",
         {**sample_journey(), "firstHotkeyWrites": expected_first_writes()[:7] + [
             {"key": "ctrl-c", "bytes": [0x03]}, {"key": "ctrl-c", "bytes": [0x03]},
         ] + expected_first_writes()[8:]}, False),
        ("resized PTY on open rejected",
         with_changed_palette_resize(sample_journey()), False),
    ]
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
    }
    dismissed = {**base, "keyboardVisible": False, "androidIme": {"visible": False, "imeBottomDp": 0}}
    reattached = {
        **base,
        "keyboardComposerMode": False,
        "runtimeGeometry": {"cols": 37, "rows": 5, "cellHeight": 23.6},
    }
    return {
        "androidApi": 35,
        "firstHotkeyWrites": expected_first_writes(),
        "allHotkeyWrites": expected_first_writes() + [{"key": "arrow-up", "bytes": [0x1B, 0x5B, 0x41]}],
        "geometryTrace": [
            {"stage": "keyboard-up-compact-row", **base},
            {"stage": "after-navigation-row-taps", **base},
            {"stage": "before-palette", **base},
            {"stage": "palette-open-ime-up", **base},
            {"stage": "palette-dragged-ime-up", **base},
            {"stage": "palette-open-ime-dismissed", **dismissed},
            {"stage": "after-reconnect", **reattached},
            {"stage": "reconnected-keybar-ime-up", **base},
        ],
    }


def with_changed_palette_resize(journey: dict[str, object]) -> dict[str, object]:
    copied = json.loads(json.dumps(journey))
    for item in copied["geometryTrace"]:
        if item["stage"] == "palette-open-ime-up":
            item["resizeAcks"] += 1
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
