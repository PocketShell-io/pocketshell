#!/usr/bin/env python3
"""Compare packaged xterm geometry checkpoints with independent remote stty samples."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path


class OracleFailure(ValueError):
    pass


STEADY_STAGES = (
    "dictation-ready-ime-open",
    "dictation-listening-ime-open",
    "dictation-listening-ctrl-open-ime-open",
    "dictation-stop-awaiting-final",
    "dictation-final-inserted",
    "dictation-post-stop-keyboard-input",
    "dictation-error-ime-open",
    "dictation-reattached-ime-open",
    "dictation-background-cancel-resumed",
    "dictation-post-resume-ime-open",
)


def validate(journey: object, samples_text: str) -> dict[tuple[int, int], int]:
    if not isinstance(journey, dict):
        raise OracleFailure("journey manifest is not a JSON object")
    trace = journey.get("geometryTrace")
    if not isinstance(trace, list):
        raise OracleFailure("journey has no geometry trace")
    by_stage = {stage.get("stage"): stage for stage in trace if isinstance(stage, dict)}
    missing = [stage for stage in STEADY_STAGES if stage not in by_stage]
    if missing:
        raise OracleFailure(f"journey is missing local steady-state checkpoints: {', '.join(missing)}")

    validate_stop_waiting_final(by_stage["dictation-stop-awaiting-final"])
    validate_error_status_dock(by_stage["dictation-error-ime-open"])

    host_counts: dict[tuple[int, int], int] = {}
    for line_number, line in enumerate(samples_text.splitlines(), start=1):
        if not line.strip():
            continue
        match = re.fullmatch(r"\s*(\d+)\s+(\d+)\s*", line)
        if not match:
            raise OracleFailure(f"remote stty sample line {line_number} is malformed: {line!r}")
        rows, cols = map(int, match.groups())
        if cols < 1 or rows < 1:
            raise OracleFailure(f"remote stty sample line {line_number} has invalid PTY size: {line!r}")
        host_counts[(cols, rows)] = host_counts.get((cols, rows), 0) + 1
    if not host_counts:
        raise OracleFailure("independent remote stty oracle returned no PTY dimensions")

    expected_by_stage: dict[str, tuple[int, int]] = {}
    stages_by_size: dict[tuple[int, int], list[str]] = {}
    for stage_name in STEADY_STAGES:
        stage = by_stage[stage_name]
        local = stage.get("runtimeGeometry")
        if not isinstance(local, dict):
            raise OracleFailure(f"{stage_name} has no mounted xterm dimensions")
        cols, rows = local.get("cols"), local.get("rows")
        if (isinstance(cols, bool) or not isinstance(cols, int) or cols < 1
                or isinstance(rows, bool) or not isinstance(rows, int) or rows < 1):
            raise OracleFailure(f"{stage_name} has invalid local xterm dimensions")
        if stage.get("resizePending") != 0 or stage.get("resizeFailures") != 0:
            raise OracleFailure(f"{stage_name} local geometry was not settled without resize errors")
        if stage.get("resizeStatus") != f"{cols} × {rows} accepted by SSH":
            raise OracleFailure(f"{stage_name} local geometry lacks a matching accepted SSH resize status")
        size = (cols, rows)
        expected_by_stage[stage_name] = size
        stages_by_size.setdefault(size, []).append(stage_name)

    # The host sampler runs every 100ms while the real fixture PTY is held open.
    # Require enough independent observations to cover every stable checkpoint
    # sharing a grid, while allowing transient fit sizes during IME transitions.
    for size, stage_names in stages_by_size.items():
        observations = host_counts.get(size, 0)
        if observations < len(stage_names):
            labels = ", ".join(stage_names)
            raise OracleFailure(
                f"host PTY size {size[0]}×{size[1]} has {observations} samples for "
                f"{len(stage_names)} local steady checkpoints: {labels}"
            )

    keyboard_size = expected_by_stage["dictation-ready-ime-open"]
    resumed_size = expected_by_stage["dictation-background-cancel-resumed"]
    if keyboard_size == resumed_size:
        raise OracleFailure("IME-hidden resume did not record the independently measured keyboard visibility geometry change")
    return host_counts


def validate_stop_waiting_final(stage: object) -> None:
    """Require the explicit-Stop checkpoint to show transcription before final insertion."""
    if not isinstance(stage, dict):
        raise OracleFailure("dictation-stop-awaiting-final geometry is not an object")
    mic = stage.get("inlineDictationMic")
    if (stage.get("stage") != "dictation-stop-awaiting-final"
            or stage.get("inlineDictationPhase") != "stopping"
            or not isinstance(stage.get("inlineDictationPreview"), str)
            or not stage.get("inlineDictationPreview", "").strip()
            or not isinstance(mic, dict)
            or mic.get("disabled") is not True
            or mic.get("micState") != "transcribing"
            or stage.get("inlineDictationStatusVisible") is not True):
        raise OracleFailure("explicit Stop checkpoint does not show the retained preview and transcribing dock")


def _number(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise OracleFailure(f"error-state {label} is missing or non-numeric")
    if not math.isfinite(value):
        raise OracleFailure(f"error-state {label} is not finite")
    return float(value)


def _expect_near(value: object, expected: float, label: str) -> None:
    actual = _number(value, label)
    if abs(actual - expected) > 0.5:
        raise OracleFailure(f"error-state {label} is {actual:g}px; expected the measured {expected:g}px contract")


# Issue #3060: a recognizer error keeps the words it had shown, so the error
# checkpoint is the dock's warning recovery strip (64px row, 113px dock),
# measured on API 35 (run js3060-hk03), not the old 32px one-line error chip.
ERROR_RECOVERY_STATUS_ROW_HEIGHT_PX = 64
ERROR_RECOVERY_DOCK_HEIGHT_PX = 113


def validate_error_status_dock(stage: object) -> None:
    """Pin the visible error recovery dock geometry while preserving the terminal viewport."""
    if not isinstance(stage, dict):
        raise OracleFailure("dictation-error-ime-open geometry is not an object")
    if (stage.get("stage") != "dictation-error-ime-open"
            or stage.get("inlineDictationPhase") != "idle"
            or stage.get("inlineDictationTone") != "warning"
            or stage.get("inlineDictationStatusVisible") is not True
            or stage.get("inlineDictationRecoveryVisible") is not True
            or not isinstance(stage.get("inlineDictationRecoveryPreview"), str)
            or not stage.get("inlineDictationRecoveryPreview", "").strip()
            or stage.get("inlineDictationRecoveryCopyHitTarget") is not True
            or stage.get("inlineDictationStatusInsideBar") is not True
            or stage.get("inlineDictationStatusAboveKeybar") is not True):
        raise OracleFailure("recognizer error did not keep its words in the visible terminal recovery strip")

    _expect_near(stage.get("terminalHotkeysDockHeightPx"), ERROR_RECOVERY_DOCK_HEIGHT_PX, "slot reservation")
    _expect_near(stage.get("inlineDictationStatusRow", {}).get("height")
                 if isinstance(stage.get("inlineDictationStatusRow"), dict) else None,
                 ERROR_RECOVERY_STATUS_ROW_HEIGHT_PX, "settled recovery row height")
    _expect_near(stage.get("keybarRect", {}).get("height")
                 if isinstance(stage.get("keybarRect"), dict) else None,
                 48, "fast-key row height")
    status = stage["inlineDictationStatusRow"]
    keybar = stage["keybarRect"]
    _expect_near(status.get("bottom"), _number(keybar.get("top"), "fast-key row top"),
                 "status-to-keybar gap")

    dock = stage.get("mobileHotkeys")
    tray = stage.get("fastKeysTray")
    if not isinstance(dock, dict) or not isinstance(tray, dict) or not isinstance(tray.get("bounds"), dict):
        raise OracleFailure("error-state rendered dock bounds are missing")
    bounds = tray["bounds"]
    _expect_near(dock.get("height"), ERROR_RECOVERY_DOCK_HEIGHT_PX, "settled recovery-visible dock height")
    _expect_near(bounds.get("height"), ERROR_RECOVERY_DOCK_HEIGHT_PX, "tray height")
    if (_number(bounds.get("top"), "tray top") > _number(status.get("top"), "status row top") + 0.5
            or _number(bounds.get("bottom"), "tray bottom") < _number(keybar.get("bottom"), "fast-key row bottom") - 0.5):
        raise OracleFailure("rendered recovery or fast-key row extends outside the settled 113px error dock")
    if (tray.get("insideSlot") is not True
            or tray.get("insideTerminalPanel") is not True
            or tray.get("belowTerminalViewport") is not True
            or tray.get("intersectsTerminalViewport") is not False
            or tray.get("intersectsComposerPanel") is not False
            or stage.get("terminalCanvasEndsAtDock") is not True):
        raise OracleFailure("error dock is outside the terminal flow or overlaps the viewport/composer")

    _expect_near(stage.get("terminalViewportDockCapPx"), 144, "terminal viewport cap")
    _expect_near(stage.get("terminalGridViewport", {}).get("height")
                 if isinstance(stage.get("terminalGridViewport"), dict) else None,
                 144, "xterm viewport height")
    visible_rows = stage.get("visibleTerminalRows")
    if isinstance(visible_rows, bool) or not isinstance(visible_rows, int) or visible_rows < 5:
        raise OracleFailure("error dock leaves fewer than five terminal rows visible")
    runtime = stage.get("runtimeGeometry")
    if not isinstance(runtime, dict) or runtime.get("cols") != 38 or runtime.get("rows") != 6:
        raise OracleFailure("error dock changed the accepted API 35 38×6 PTY grid")
    if (stage.get("resizePending") != 0 or stage.get("resizeFailures") != 0
            or stage.get("resizeStatus") != "38 × 6 accepted by SSH"):
        raise OracleFailure("error dock geometry is not settled at the accepted 38×6 SSH size")


def self_test() -> int:
    stages = [
        {"stage": name, "runtimeGeometry": {"cols": cols, "rows": rows},
         "resizePending": 0, "resizeFailures": 0,
         "resizeStatus": f"{cols} × {rows} accepted by SSH"}
        for name, cols, rows in (
            ("dictation-ready-ime-open", 38, 6),
            ("dictation-listening-ime-open", 38, 6),
            ("dictation-listening-ctrl-open-ime-open", 38, 6),
            ("dictation-stop-awaiting-final", 38, 6),
            ("dictation-final-inserted", 38, 6),
            ("dictation-post-stop-keyboard-input", 38, 6),
            ("dictation-error-ime-open", 38, 6),
            ("dictation-reattached-ime-open", 38, 6),
            ("dictation-background-cancel-resumed", 37, 24),
            ("dictation-post-resume-ime-open", 38, 6),
        )
    ]
    stop_waiting_stage = next(stage for stage in stages if stage["stage"] == "dictation-stop-awaiting-final")
    stop_waiting_stage.update({
        "inlineDictationPhase": "stopping",
        "inlineDictationPreview": "retained partial transcript",
        "inlineDictationMic": {"disabled": True, "micState": "transcribing"},
        "inlineDictationStatusVisible": True,
    })
    journey = {"geometryTrace": stages}
    cases = [
        ("keyboard and IME-hidden local grids match independent host samples", "6 38\n24 37\n" * 10, True),
        ("host PTY mismatch at IME-hidden resume is rejected", "6 38\n" * 10, False),
        ("unacknowledged local resume geometry is rejected", "6 38\n24 37\n" * 10, False),
        ("journey missing the pre-final Stop checkpoint is rejected", "6 38\n24 37\n" * 10, False),
        ("Stop checkpoint without a transcribing dock is rejected", "6 38\n24 37\n" * 10, False),
    ]
    failed = False

    error_stage = {
        "stage": "dictation-error-ime-open",
        "inlineDictationPhase": "idle",
        "inlineDictationTone": "warning",
        "inlineDictationStatusVisible": True,
        "inlineDictationRecoveryVisible": True,
        "inlineDictationRecoveryPreview": "kept after a recognizer error",
        "inlineDictationRecoveryCopyHitTarget": True,
        "inlineDictationStatusInsideBar": True,
        "inlineDictationStatusAboveKeybar": True,
        "terminalHotkeysDockHeightPx": 113,
        "inlineDictationStatusRow": {"top": 201, "bottom": 265, "height": 64},
        "keybarRect": {"top": 265, "bottom": 313, "height": 48},
        "mobileHotkeys": {"top": 200, "bottom": 313, "height": 113},
        "fastKeysTray": {
            "bounds": {"top": 200, "bottom": 313, "height": 113},
            "insideSlot": True, "insideTerminalPanel": True, "belowTerminalViewport": True,
            "intersectsTerminalViewport": False, "intersectsComposerPanel": False,
        },
        "terminalCanvasEndsAtDock": True,
        "terminalViewportDockCapPx": 144,
        "terminalGridViewport": {"top": 0, "bottom": 144, "height": 144},
        "visibleTerminalRows": 5,
        "runtimeGeometry": {"cols": 38, "rows": 6},
        "resizePending": 0,
        "resizeFailures": 0,
        "resizeStatus": "38 × 6 accepted by SSH",
    }
    next(stage for stage in journey["geometryTrace"]
         if stage["stage"] == "dictation-error-ime-open").update(error_stage)
    error_dock_cases = [
        ("settled 113px recovery-visible error dock passes", error_stage, True),
        ("old 81px one-line error dock that dropped the words fails", {
            **error_stage,
            "inlineDictationTone": "error",
            "inlineDictationRecoveryVisible": False,
            "inlineDictationRecoveryPreview": "",
            "terminalHotkeysDockHeightPx": 81,
            "inlineDictationStatusRow": {**error_stage["inlineDictationStatusRow"], "bottom": 233, "height": 32},
            "keybarRect": {**error_stage["keybarRect"], "top": 233, "bottom": 281},
            "mobileHotkeys": {**error_stage["mobileHotkeys"], "bottom": 281, "height": 81},
            "fastKeysTray": {**error_stage["fastKeysTray"], "bounds": {"top": 200, "bottom": 281, "height": 81}},
        }, False),
        ("recovery strip without a reachable Copy action fails", {
            **error_stage, "inlineDictationRecoveryCopyHitTarget": False,
        }, False),
        ("hidden recognizer error status fails", {**error_stage, "inlineDictationStatusVisible": False}, False),
        ("error dock overlapping xterm fails", {
            **error_stage,
            "fastKeysTray": {**error_stage["fastKeysTray"], "intersectsTerminalViewport": True},
        }, False),
        ("error dock with fewer than five rows fails", {**error_stage, "visibleTerminalRows": 4}, False),
        ("error dock with a changed PTY grid fails", {
            **error_stage, "runtimeGeometry": {"cols": 38, "rows": 5},
        }, False),
    ]
    for label, stage, should_pass in error_dock_cases:
        try:
            validate_error_status_dock(stage)
        except OracleFailure:
            accepted = False
        else:
            accepted = True
        if accepted != should_pass:
            print(f"FAIL: {label}", file=sys.stderr)
            failed = True
        else:
            print(f"PASS: {label}")

    for index, (label, samples, should_pass) in enumerate(cases):
        case = json.loads(json.dumps(journey))
        if index == 2:
            next(stage for stage in case["geometryTrace"]
                 if stage["stage"] == "dictation-background-cancel-resumed")["resizePending"] = 1
        elif index == 3:
            case["geometryTrace"] = [stage for stage in case["geometryTrace"]
                                     if stage["stage"] != "dictation-stop-awaiting-final"]
        elif index == 4:
            next(stage for stage in case["geometryTrace"]
                 if stage["stage"] == "dictation-stop-awaiting-final")["inlineDictationMic"]["micState"] = "listening"
        try:
            validate(case, samples)
        except OracleFailure:
            accepted = False
        else:
            accepted = True
        if accepted != should_pass:
            print(f"FAIL: {label}", file=sys.stderr)
            failed = True
        else:
            print(f"PASS: {label}")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--journey", type=Path)
    parser.add_argument("--samples", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.journey is None or args.samples is None:
        parser.error("--journey and --samples are required unless --self-test is used")
    try:
        journey = json.loads(args.journey.read_text(encoding="utf-8"))
        host_counts = validate(journey, args.samples.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, OracleFailure) as error:
        print(f"BLOCK: {error}", file=sys.stderr)
        return 1
    sizes = ", ".join(f"{cols}×{rows} ({count} samples)" for (cols, rows), count in sorted(host_counts.items()))
    print(f"PASS: local dictation geometry matches independent remote PTY samples: {sizes}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
