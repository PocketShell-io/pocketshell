#!/usr/bin/env python3
"""Rebuild and validate keyboard-up composer artifacts from Android logcat."""

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
EXPECTED_NAMES = {"composer-keyboard.png", "composer-keyboard-geometry.json"}
SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


class ExtractionFailure(ValueError):
    pass


def parse_assets(log_text: str, run_id: str, *, validate_layout: bool = True) -> dict[str, bytes]:
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

    required_names = EXPECTED_NAMES if validate_layout else {"composer-keyboard.png"}
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

    screenshot = decoded["composer-keyboard.png"]
    if not screenshot.startswith(b"\x89PNG\r\n\x1a\n") or len(screenshot) < 1024:
        raise ExtractionFailure("keyboard screenshot is not a non-empty PNG")
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
    return decoded


def extract(run_id: str, logcat: Path, output: Path, *, validate_layout: bool = True) -> list[str]:
    try:
        log_text = logcat.read_text(encoding="utf-8", errors="replace")
    except OSError as error:
        raise ExtractionFailure(f"could not read Android logcat {logcat}: {error}") from error
    artifacts = parse_assets(log_text, run_id, validate_layout=validate_layout)
    output.mkdir(parents=True, exist_ok=True)
    for name, payload in artifacts.items():
        (output / name).write_bytes(payload)
    return sorted(artifacts)


def self_test() -> None:
    run_id = "js2857-self-test"
    png = b"\x89PNG\r\n\x1a\n" + b"fixture" * 200
    def geometry_payload(*, ime_visible: bool = True, app_bar_top: float = 24.0,
                         send_bottom: float = 198.0, terminal_height: float = 60.0) -> bytes:
        return json.dumps({
            "androidImeVisible": ime_visible,
            "visualViewport": {"height": 200.0, "width": 400.0},
            "terminalViewport": {"top": 30.0, "bottom": 30.0 + terminal_height, "left": 1.0, "right": 399.0, "height": terminal_height},
            "appBar": {"top": app_bar_top},
            "draft": {"top": 100.0, "bottom": 150.0, "left": 1.0, "right": 399.0},
            "status": {"top": 152.0, "bottom": 166.0, "left": 1.0, "right": 399.0},
            "actions": {"top": 168.0, "bottom": 199.0, "left": 1.0, "right": 399.0},
            "buttons": {
                "discard": {"top": 170.0, "bottom": 198.0, "left": 1.0, "right": 70.0},
                "insert": {"top": 170.0, "bottom": 198.0, "left": 250.0, "right": 310.0},
                "send": {"top": 170.0, "bottom": send_bottom, "left": 320.0, "right": 398.0},
            },
            "safeArea": {"topCss": 24.0, "bottomCss": 0.0, "shellTopPadding": 24.0, "shellBottomPadding": 0.0, "keyboardVisible": True},
            "nativeInsets": {"statusBarTopDp": 24.0, "imeBottomDp": 300.0},
        }).encode()

    geometry = geometry_payload()

    def make_lines(geometry_bytes: bytes = geometry) -> list[str]:
        source = [("composer-keyboard.png", png), ("composer-keyboard-geometry.json", geometry_bytes)]
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
    assert parse_assets("\n".join(lines), run_id)["composer-keyboard.png"] == png
    print("PASS: screenshot and geometry artifacts extract with complete chunks and matching SHA-256")

    for label, altered in (
        ("missing artifact", lines[:-1]),
        ("missing chunk", [line for line in lines if "DATA|" not in line or "|0|" not in line]),
        ("bad digest", [line.replace(hashlib.sha256(png).hexdigest(), "0" * 64) for line in lines]),
        ("IME hidden", make_lines(geometry_payload(ime_visible=False))),
        ("send button clipped by viewport", make_lines(geometry_payload(send_bottom=201))),
        ("status bar overlap", make_lines(geometry_payload(app_bar_top=0))),
        ("terminal context hidden", make_lines(geometry_payload(terminal_height=30))),
    ):
        try:
            parse_assets("\n".join(altered), run_id)
        except ExtractionFailure:
            print(f"PASS: {label} fails closed")
        else:
            raise AssertionError(f"{label} unexpectedly passed")
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
    try:
        names = extract(args.run_id, args.logcat, args.output_dir,
                        validate_layout=not args.preserve_on_failure)
    except ExtractionFailure as error:
        print(f"FAIL: composer artifact extraction: {error}", file=sys.stderr)
        return 1
    print(f"PASS: extracted {len(names)} same-run composer artifacts")
    for name in names:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
