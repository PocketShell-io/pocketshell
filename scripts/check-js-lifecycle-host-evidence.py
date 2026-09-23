#!/usr/bin/python3 -I
"""Join packaged viewport evidence to independent aplexer host state."""

from __future__ import annotations

import argparse
import csv
import hashlib
import ipaddress
import io
import json
import re
import struct
import subprocess
import sys
import shutil
import unicodedata
from pathlib import Path
from typing import Any


RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$")
CHECKPOINTS = {
    "switch-a": "a",
    "switch-b": "b",
    "switch-c": "c",
    "switch-a-return": "a",
}
MARKER_PHASES = {
    "switch-a": "AS",
    "switch-b": "BS",
    "switch-c": "CS",
    "switch-a-return": "AR",
    "background-within-grace": "AG",
    "reconnected-after-expiry": "AE",
}
MAX_NATIVE_CLOSE_COMPLETION_LAG_MS = 2_000
MAX_HOST_SOCKET_CLOSE_LAG_AFTER_NATIVE_MS = 8_000
MIN_HOST_ZERO_SOCKET_STABILITY_MS = 1_000
MIN_SCREENSHOT_OCR_CONFIDENCE = 75.0
MIN_SCREENSHOT_MARKER_ACCENT_PIXELS = 64
ANSI_ESCAPE_RE = re.compile(
    r"\x1B(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1B\\)|[@-_])"
)
CONTROL_RE = re.compile(r"[\x00-\x08\x0B-\x1F\x7F-\x9F]")


class EvidenceFailure(ValueError):
    pass


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceFailure(f"could not read JSON evidence {path}: {error}") from error


def _run(command: list[str]) -> str:
    try:
        result = subprocess.run(command, check=True, text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.SubprocessError) as error:
        raise EvidenceFailure(f"host oracle failed: {' '.join(command[:5])}: {error}") from error
    return result.stdout


def _png_dimensions(path: Path) -> tuple[int, int]:
    try:
        data = path.read_bytes()
    except OSError as error:
        raise EvidenceFailure(f"missing viewport PNG {path}: {error}") from error
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise EvidenceFailure(f"viewport artifact is not a valid PNG: {path}")
    width, height = struct.unpack(">II", data[16:24])
    if width < 100 or height < 40:
        raise EvidenceFailure(f"viewport PNG is blank or too small ({width}x{height}): {path}")
    return width, height


def _canonical_ocr_token(text: str) -> str:
    token = re.sub(r"[^A-Z0-9_]", "", unicodedata.normalize("NFKC", text).upper())
    # Tesseract commonly reads the zero in this uppercase monospace output as O.
    return token.replace("O", "0")


def _screenshot_marker_accent_rgb(marker: str) -> str:
    digest = hashlib.sha256(marker.encode("utf-8")).digest()
    return ",".join(str(128 + (component & 0x7F)) for component in digest[:3])


def has_confident_screenshot_marker(
    words: list[dict[str, Any]], marker: str, marker_bounds: tuple[int, int, int, int]
) -> tuple[bool, dict[str, Any] | None]:
    expected = _canonical_ocr_token(marker)
    left, top, right, bottom = marker_bounds
    for word in words:
        text = word.get("text")
        confidence = word.get("confidence")
        word_left = word.get("left")
        word_top = word.get("top")
        word_right = word_left + word.get("width", 0) if isinstance(word_left, int) else None
        word_bottom = word_top + word.get("height", 0) if isinstance(word_top, int) else None
        if (
            isinstance(text, str)
            and isinstance(confidence, (int, float))
            and confidence >= MIN_SCREENSHOT_OCR_CONFIDENCE
            and _canonical_ocr_token(text) == expected
            and isinstance(word_left, int)
            and isinstance(word_top, int)
            and isinstance(word_right, int)
            and isinstance(word_bottom, int)
            and left - 3 <= word_left < right
            and top - 3 <= word_top < bottom
            and word_right <= right + 3
            and word_bottom <= bottom + 3
        ):
            return True, word
    return False, None


def _marker_pixel_bounds(viewport: dict[str, Any], marker_rect: dict[str, Any]) -> tuple[int, int, int, int]:
    dpr = viewport.get("devicePixelRatio")
    keys = ("left", "top", "right", "bottom")
    if not isinstance(dpr, (int, float)) or dpr <= 0 or any(
        not isinstance(rect.get(key), (int, float)) for rect in (viewport, marker_rect) for key in keys
    ):
        raise EvidenceFailure("screenshot marker OCR needs finite viewport and marker-row geometry")
    bounds = (
        round((marker_rect["left"] - viewport["left"]) * dpr),
        round((marker_rect["top"] - viewport["top"]) * dpr),
        round((marker_rect["right"] - viewport["left"]) * dpr),
        round((marker_rect["bottom"] - viewport["top"]) * dpr),
    )
    if bounds[0] < 0 or bounds[1] < 0 or bounds[2] <= bounds[0] or bounds[3] <= bounds[1]:
        raise EvidenceFailure("screenshot marker OCR geometry is outside the captured terminal viewport")
    return bounds


def _screenshot_marker_ocr(path: Path, marker: str, marker_bounds: tuple[int, int, int, int]) -> dict[str, Any]:
    if shutil.which("tesseract") is None:
        raise EvidenceFailure("Tesseract is required to verify that each packaged screenshot visibly contains its exact terminal marker")
    try:
        result = subprocess.run(
            [
                "tesseract", str(path), "stdout", "--psm", "6", "tsv",
                "-c", "tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_",
            ],
            check=True,
            text=True,
            capture_output=True,
            timeout=20,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise EvidenceFailure(f"could not OCR screenshot {path}: {error}") from error
    words: list[dict[str, Any]] = []
    try:
        for row in csv.DictReader(io.StringIO(result.stdout), delimiter="\t"):
            if row.get("level") != "5" or not row.get("text"):
                continue
            words.append({
                "text": row["text"],
                "confidence": float(row.get("conf", "-1")),
                "left": int(row["left"]),
                "top": int(row["top"]),
                "width": int(row["width"]),
                "height": int(row["height"]),
            })
    except (ValueError, csv.Error) as error:
        raise EvidenceFailure(f"Tesseract returned malformed TSV for {path}: {error}") from error
    found, match = has_confident_screenshot_marker(words, marker, marker_bounds)
    if not found or match is None:
        recognized = [
            {"text": word["text"], "confidence": round(word["confidence"], 1),
             "left": word["left"], "top": word["top"], "width": word["width"], "height": word["height"]}
            for word in words if word["confidence"] >= 0
        ]
        raise EvidenceFailure(
            f"screenshot {path.name} does not OCR the standalone marker {marker!r} "
            f"inside its measured terminal row at confidence >= {MIN_SCREENSHOT_OCR_CONFIDENCE:g}; "
            f"recognized={recognized[:20]}"
        )
    return {
        "marker": marker,
        "recognized": match["text"],
        "confidence": round(match["confidence"], 1),
        "pixelBounds": list(marker_bounds),
        "recognizedBounds": [match["left"], match["top"], match["left"] + match["width"], match["top"] + match["height"]],
    }


def normalized_terminal_lines(text: str) -> list[str]:
    text = ANSI_ESCAPE_RE.sub("", text.replace("\r\n", "\n").replace("\r", "\n"))
    text = CONTROL_RE.sub("", text)
    lines: list[str] = []
    for line in text.split("\n"):
        normalized = unicodedata.normalize("NFKC", line.replace("\u00a0", " "))
        normalized = " ".join(normalized.split())
        if normalized:
            lines.append(normalized)
    return lines


def has_exact_terminal_line(text: str, marker: str) -> bool:
    return marker in normalized_terminal_lines(text)


def validate_independent_session_captures(
    history: str,
    screen: str,
    markers: set[str],
    latest_marker: str,
    tag: str,
) -> None:
    if not markers or any(not has_exact_terminal_line(history, marker) for marker in markers):
        raise EvidenceFailure(f"independent a history capture for {tag} lacks a standalone remote output line for every packaged checkpoint")
    if not latest_marker or not has_exact_terminal_line(screen, latest_marker):
        raise EvidenceFailure(f"independent a current-screen capture for {tag} lacks its latest packaged output marker")


def _validate_resize_checkpoint(checkpoint: dict[str, Any], name: str) -> int:
    if checkpoint.get("terminalResizePending") != 0 or checkpoint.get("terminalResizeFailureCount") != 0:
        raise EvidenceFailure(f"{name}: native PTY resize was pending or failed at the screenshot checkpoint")
    resize_acks = checkpoint.get("terminalResizeAckCount")
    if not isinstance(resize_acks, int) or isinstance(resize_acks, bool) or resize_acks < 1:
        raise EvidenceFailure(f"{name}: native PTY resize acknowledgement is missing")
    resize_status = checkpoint.get("terminalResizeStatus")
    if not isinstance(resize_status, str) or not resize_status.endswith("accepted by SSH"):
        raise EvidenceFailure(f"{name}: screenshot was captured before SSH accepted the terminal dimensions")
    return resize_acks


def _validate_fresh_resize_ack_progress(resize_ack_counts: list[int]) -> None:
    if len(resize_ack_counts) < 4:
        raise EvidenceFailure("A→B→C→A resize evidence is incomplete")
    if any(after <= before for before, after in zip(resize_ack_counts[:4], resize_ack_counts[1:4])):
        raise EvidenceFailure("each A→B→C→A attach must receive a fresh native PTY resize acknowledgement")
    if any(after < before for before, after in zip(resize_ack_counts, resize_ack_counts[1:])):
        raise EvidenceFailure("native PTY resize acknowledgement counts must not move backwards")


def _validate_reconnected_resize_ack(checkpoint: dict[str, Any], before_reconnect: int) -> None:
    resize_acks = checkpoint.get("terminalResizeAckCount")
    if not isinstance(resize_acks, int) or isinstance(resize_acks, bool) or resize_acks <= before_reconnect:
        raise EvidenceFailure("reconnected A viewport lacks a fresh native PTY resize acknowledgement")


def _validate_native_expiry_event_timing(timing: dict[str, Any], event: dict[str, Any]) -> None:
    timestamp_keys = (
        "nativeGraceDeadlineEpochMs", "nativeGraceDeadlineElapsedRealtimeMs", "nativeClosedAtElapsedRealtimeMs",
        "nativeGraceExpiryDispatchedAtEpochMs", "nativeGraceExpiryDispatchedAtElapsedRealtimeMs",
        "nativeClosedAtEpochMs", "nativeTransportCloseCompletedAtEpochMs",
        "nativeTransportCloseCompletedAtElapsedRealtimeMs", "atEpochMs",
    )
    timing_keys = (
        "beyondGraceForegroundRequestedElapsedMs", "beyondGraceForegroundRequestedEpochMs",
        "beyondGraceExpiryEventObservedAfterForegroundElapsedMs",
        "beyondGraceExpiryEventObservedAfterForegroundEpochMs",
        "nativeGraceDeadlineEpochMs", "nativeGraceDeadlineElapsedRealtimeMs",
        "nativeGraceExpiryDispatchedAtEpochMs", "nativeGraceExpiryDispatchedAtElapsedRealtimeMs",
        "nativeTransportCloseCompletedAtEpochMs", "nativeTransportCloseCompletedAtElapsedRealtimeMs",
    )
    for key in timestamp_keys:
        if not isinstance(event.get(key), int) or isinstance(event.get(key), bool):
            raise EvidenceFailure(f"native grace event is missing integer timestamp {key}")
    for key in timing_keys:
        if not isinstance(timing.get(key), int) or isinstance(timing.get(key), bool):
            raise EvidenceFailure(f"journey is missing integer timestamp {key}")
    if event.get("nativeSocketClosedAfterClose") is not True:
        raise EvidenceFailure("native SSH socket is not closed after transport close completion")
    if not isinstance(event.get("nativeClientReportedConnectedAfterClose"), bool):
        raise EvidenceFailure("native grace event is missing the diagnostic SSHJ connected state")
    if not isinstance(event.get("nativeRawSocketCloseFallbackUsed"), bool):
        raise EvidenceFailure("native grace event is missing raw socket close fallback evidence")
    for key in (
        "nativeSshjDisconnectErrorClass", "nativeSshjClientCloseErrorClass", "nativeRawSocketCloseErrorClass",
    ):
        if not isinstance(event.get(key), str):
            raise EvidenceFailure(f"native grace event is missing exception class diagnostic {key}")
    if event["nativeSshjDisconnectErrorClass"]:
        raise EvidenceFailure("native grace expiry reported an SSHJ disconnect exception")
    if event["nativeSshjClientCloseErrorClass"]:
        raise EvidenceFailure("native grace expiry reported an SSHJ close exception")
    if event["nativeRawSocketCloseErrorClass"]:
        raise EvidenceFailure("native raw socket close fallback reported an exception")
    if event.get("nativeCleanupExecutorRejectErrorClass") != "":
        raise EvidenceFailure("native grace cleanup executor rejected the close task")
    if event["nativeClosedAtElapsedRealtimeMs"] < event["nativeGraceDeadlineElapsedRealtimeMs"]:
        raise EvidenceFailure("native close handler started before its monotonic grace deadline")
    if event["nativeGraceExpiryDispatchedAtElapsedRealtimeMs"] < event["nativeGraceDeadlineElapsedRealtimeMs"]:
        raise EvidenceFailure("native grace close was dispatched before its monotonic deadline")
    if event["nativeClosedAtElapsedRealtimeMs"] < event["nativeGraceExpiryDispatchedAtElapsedRealtimeMs"]:
        raise EvidenceFailure("native cleanup worker started before grace expiry was dispatched")
    if event["nativeTransportCloseCompletedAtElapsedRealtimeMs"] < event["nativeClosedAtElapsedRealtimeMs"]:
        raise EvidenceFailure("native transport close completion predates the close handler")
    if event["nativeTransportCloseCompletedAtElapsedRealtimeMs"] > timing["beyondGraceForegroundRequestedElapsedMs"]:
        raise EvidenceFailure("native transport close did not complete before foreground was requested")
    if event["nativeTransportCloseCompletedAtElapsedRealtimeMs"] - event["nativeGraceDeadlineElapsedRealtimeMs"] > MAX_NATIVE_CLOSE_COMPLETION_LAG_MS:
        raise EvidenceFailure("native transport close completed more than two seconds after its grace deadline")
    if (
        timing["nativeGraceDeadlineEpochMs"] != event["nativeGraceDeadlineEpochMs"]
        or timing["nativeGraceDeadlineElapsedRealtimeMs"] != event["nativeGraceDeadlineElapsedRealtimeMs"]
        or timing["nativeTransportCloseCompletedAtEpochMs"] != event["nativeTransportCloseCompletedAtEpochMs"]
        or timing["nativeTransportCloseCompletedAtElapsedRealtimeMs"] != event["nativeTransportCloseCompletedAtElapsedRealtimeMs"]
    ):
        raise EvidenceFailure("journey timing did not preserve native deadline and transport close completion values")
    if timing["beyondGraceExpiryEventObservedAfterForegroundElapsedMs"] < timing["beyondGraceForegroundRequestedElapsedMs"]:
        raise EvidenceFailure("the test did not observe the native expiry event after foreground was requested")
    if timing["beyondGraceExpiryEventObservedAfterForegroundEpochMs"] < timing["beyondGraceForegroundRequestedEpochMs"]:
        raise EvidenceFailure("the test did not observe the native expiry event after foreground was requested")
    if not (
        event["nativeClosedAtEpochMs"] <= event["nativeTransportCloseCompletedAtEpochMs"]
        <= event["atEpochMs"]
        <= timing["beyondGraceExpiryEventObservedAfterForegroundEpochMs"]
    ):
        raise EvidenceFailure("native expiry bridge delivery timestamp is outside close completion and post-foreground observation")


def _self_test() -> int:
    marker = "REMOTE_OUTPUT_JS2861FIX0923_A_SWITCH"
    probes = [
        ("standalone output accepted", f"prompt$ printf '\\n%s\\n' '{marker}'\r\n{marker}\r\nprompt$", True),
        ("ANSI-wrapped standalone output accepted", f"\x1b[32m{marker}\x1b[0m\r\n", True),
        ("command echo alone rejected", f"prompt$ printf '\\n%s\\n' '{marker}'\r\n", False),
        ("unfinished continuation rejected", f"prompt> {marker}\r\n", False),
        ("marker substring rejected", f"prefix-{marker}-suffix\r\n", False),
        ("wrap-split marker rejected", f"{marker[:17]}\r\n{marker[17:]}\r\n", False),
    ]
    for index, (label, text, expected) in enumerate(probes, 1):
        passed = has_exact_terminal_line(text, marker) == expected
        if not passed:
            print(f"FAIL: host marker oracle mutation probe {index}: {label}", file=sys.stderr)
            return 1
        print(f"ok [{index}/{len(probes)}] {label}")

    screenshot_marker = "REMOTE_OUTPUT_0CA60FF4FA_AS"
    marker_bounds = (10, 20, 900, 80)
    screenshot_probes = [
        ("exact standalone screenshot marker accepted", [
            {"text": "REMOTE_OUTPUT_OCA60FF4FA_AS", "confidence": 91.0,
             "left": 26, "top": 31, "width": 675, "height": 37},
        ], True),
        ("stale session-list screenshot rejected", [
            {"text": "testuser:js2861-review-0923-a", "confidence": 91.0,
             "left": 31, "top": 31, "width": 600, "height": 37},
        ], False),
        ("command echo outside marker row rejected", [
            {"text": screenshot_marker, "confidence": 91.0,
             "left": 26, "top": 120, "width": 675, "height": 37},
        ], False),
        ("command echo in marker row rejected when not standalone", [
            {"text": "PRINTF_REMOTE_OUTPUT_0CA60FF4FA_AS", "confidence": 91.0,
             "left": 26, "top": 31, "width": 675, "height": 37},
        ], False),
        ("marker substring rejected", [
            {"text": "REMOTE_OUTPUT_0CA60FF4FA_AS_EXTRA", "confidence": 91.0,
             "left": 26, "top": 31, "width": 675, "height": 37},
        ], False),
        ("wrap-split marker rejected", [
            {"text": "REMOTE_OUTPUT_0CA", "confidence": 91.0,
             "left": 26, "top": 31, "width": 300, "height": 37},
            {"text": "60FF4FA_AS", "confidence": 91.0,
             "left": 26, "top": 61, "width": 300, "height": 37},
        ], False),
        ("low-confidence OCR rejected", [
            {"text": screenshot_marker, "confidence": 42.0,
             "left": 26, "top": 31, "width": 675, "height": 37},
        ], False),
    ]
    for index, (label, words, expected) in enumerate(screenshot_probes, 1):
        actual, _match = has_confident_screenshot_marker(words, screenshot_marker, marker_bounds)
        if actual != expected:
            print(f"FAIL: screenshot OCR mutation probe {index}: {label}", file=sys.stderr)
            return 1
        print(f"ok [screenshot {index}/{len(screenshot_probes)}] {label}")

    history_markers = {"REMOTE_OUTPUT_JS2861FIX0923_AS", "REMOTE_OUTPUT_JS2861FIX0923_AR"}
    try:
        validate_independent_session_captures(
            "REMOTE_OUTPUT_JS2861FIX0923_AS\nREMOTE_OUTPUT_JS2861FIX0923_AR\n",
            "REMOTE_OUTPUT_JS2861FIX0923_AR\n",
            history_markers,
            "REMOTE_OUTPUT_JS2861FIX0923_AR",
            "self-test-session",
        )
        print("ok [capture 1/2] full history passes while older marker is absent from current screen")
    except EvidenceFailure as error:
        print(f"FAIL: full-history capture positive probe: {error}", file=sys.stderr)
        return 1
    try:
        validate_independent_session_captures(
            "REMOTE_OUTPUT_JS2861FIX0923_AR\n",
            "REMOTE_OUTPUT_JS2861FIX0923_AR\n",
            history_markers,
            "REMOTE_OUTPUT_JS2861FIX0923_AR",
            "self-test-session",
        )
    except EvidenceFailure:
        print("ok [capture 2/2] current-screen-only capture rejected when older output scrolled off")
    else:
        print("FAIL: current-screen-only output satisfied full-history marker gate", file=sys.stderr)
        return 1

    epoch_offset = 0
    timing = {
        "selectedGraceMs": 30_000,
        "sshConnectRequestedEpochMs": 1_000,
        "sshConnectedEpochMs": 2_000,
        "withinGraceBackgroundEpochMs": 10_000,
        "withinGraceForegroundRequestedEpochMs": 11_500,
        "beyondGraceBackgroundEpochMs": 20_000,
        "beyondGraceDeadlineEpochMs": 50_000,
        "nativeGraceDeadlineEpochMs": 50_000,
        "nativeTransportCloseCompletedAtEpochMs": 50_050,
        "beyondGraceForegroundRequestedEpochMs": 62_000,
        "beyondGraceLiveEpochMs": 62_500,
    }
    def sample(at: int, count: int, remote: str = "01001EAC:AAC6") -> dict[str, Any]:
        return {
            "sampledEpochMs": at,
            "count": count,
            "establishedSshConnections": [
                {"family": "tcp", "local": "02001EAC:0016", "remote": remote}
                for _ in range(count)
            ],
            "error": None,
        }

    good_samples = [
        sample(500, 0), sample(900, 0), sample(2_100, 1), sample(5_000, 1), sample(9_900, 1),
        sample(10_500, 1), sample(11_000, 1), sample(21_000, 1), sample(49_000, 1),
        sample(50_500, 1), sample(51_000, 1), sample(54_000, 1), sample(54_500, 1),
        sample(55_000, 0), sample(55_500, 0), sample(56_000, 0), sample(60_000, 0), sample(61_000, 0),
        sample(62_800, 1, "01001EAC:ADF0"), sample(63_200, 1, "01001EAC:ADF0"),
    ]
    validate_host_transport_timeline(timing, good_samples, epoch_offset, 0, 64_000)
    print("ok [7/15] independent socket timeline measures native close, host disconnect, and post-foreground reconnect")

    nonzero_device_to_host_offset_ms = 2_762
    shifted_host_samples = [
        {**row, "sampledEpochMs": row["sampledEpochMs"] + nonzero_device_to_host_offset_ms}
        for row in good_samples
    ]
    adjusted_timeline = validate_host_transport_timeline(
        timing, shifted_host_samples, nonzero_device_to_host_offset_ms, 0, 64_000
    )
    if (
        adjusted_timeline["nativeGraceDeadlineHostEpochMs"] != 52_762
        or adjusted_timeline["nativeTransportCloseCompletedHostEpochMs"] != 52_812
        or adjusted_timeline["hostServerSocketDisappearedAtEpochMs"] != 57_762
        or adjusted_timeline["nativeDeadlineToHostSocketDisappearedMs"] != 5_000
    ):
        print("FAIL: nonzero device-to-host offset was mixed into lifecycle elapsed time", file=sys.stderr)
        return 1
    print("ok [clock] nonzero device-to-host offset keeps deadline and host disappearance in one clock domain")

    def mutate_count(sampled_at: int | tuple[int, ...], count: int) -> list[dict[str, Any]]:
        targets = {sampled_at} if isinstance(sampled_at, int) else set(sampled_at)
        return [
            {
                **row,
                "count": count,
                "establishedSshConnections": [
                    {"family": "tcp", "local": "02001EAC:0016", "remote": "01001EAC:AAC6"}
                    for _ in range(count)
                ],
            } if row["sampledEpochMs"] in targets else row
            for row in good_samples
        ]

    def replace_sample(at: int, sockets: list[dict[str, str]]) -> list[dict[str, Any]]:
        return [
            {**row, "count": len(sockets), "establishedSshConnections": sockets}
            if row["sampledEpochMs"] == at else row
            for row in good_samples
        ]

    original_socket = {"family": "tcp", "local": "02001EAC:0016", "remote": "01001EAC:AAC6"}
    reopened_socket = {"family": "tcp", "local": "02001EAC:0016", "remote": "01001EAC:ADF0"}
    no_close_samples = [
        {**row, "count": 1, "establishedSshConnections": [original_socket]}
        if 50_500 <= row["sampledEpochMs"] <= 61_000 else row
        for row in good_samples
    ]
    late_close_samples = [
        {**row, "count": 1, "establishedSshConnections": [original_socket]}
        if 50_500 <= row["sampledEpochMs"] < 60_000 else row
        for row in good_samples
    ]
    late_close_samples = [
        ({**row, "count": 0, "establishedSshConnections": []}
         if row["sampledEpochMs"] in (60_000, 61_000) else row)
        for row in late_close_samples
    ]
    reopened_after_close = replace_sample(56_000, [reopened_socket])
    reconnect_before_foreground = replace_sample(54_500, [original_socket, reopened_socket])
    mutations = [
        ("within-grace socket drop rejected", mutate_count(10_500, 0)),
        ("socket still open until foreground rejected", no_close_samples),
        ("server-side close beyond eight-second bound rejected", late_close_samples),
        ("new socket before foreground rejected", reconnect_before_foreground),
        ("socket reopening in background after close rejected", reopened_after_close),
        ("missing post-expiry reconnect rejected", mutate_count((62_800, 63_200), 0)),
        ("loopback-only stream rejected", [
            dict(row, establishedSshConnections=[
                {"family": "tcp", "local": "02001EAC:0016", "remote": "0100007F:0016"}
                for _ in row["establishedSshConnections"]
            ]) if row["count"] else row
            for row in good_samples
        ]),
        ("early selected deadline rejected", [dict(timing, nativeGraceDeadlineEpochMs=47_000)]),
    ]
    for index, (label, altered) in enumerate(mutations, 8):
        try:
            if label == "early selected deadline rejected":
                validate_host_transport_timeline(altered[0], good_samples, epoch_offset, 0, 64_000)
            else:
                validate_host_transport_timeline(timing, altered, epoch_offset, 0, 64_000)
        except EvidenceFailure:
            print(f"ok [{index}/15] {label}")
        else:
            print(f"FAIL: host socket timeline mutation probe {index}: {label}", file=sys.stderr)
            return 1
    resize_checkpoint = {
        "terminalResizePending": 0,
        "terminalResizeAckCount": 5,
        "terminalResizeFailureCount": 0,
        "terminalResizeStatus": "35 × 13 accepted by SSH",
    }
    try:
        _validate_resize_checkpoint(resize_checkpoint, "switch-a")
        _validate_fresh_resize_ack_progress([5, 6, 7, 8, 8, 8])
    except EvidenceFailure as error:
        print(f"FAIL: fresh native resize acknowledgement accepted case: {error}", file=sys.stderr)
        return 1
    print("ok [16/23] accepted native resize and fresh attach/reconnect acknowledgements pass")
    resize_mutations = [
        ("missing resize acknowledgement rejected", {**resize_checkpoint, "terminalResizeAckCount": 0}),
        ("pending resize rejected", {**resize_checkpoint, "terminalResizePending": 1}),
        ("failed resize rejected", {**resize_checkpoint, "terminalResizeFailureCount": 1}),
        ("local fit without SSH acknowledgement rejected", {**resize_checkpoint, "terminalResizeStatus": "35 × 13 (local fit)"}),
    ]
    for index, (label, altered) in enumerate(resize_mutations, 17):
        try:
            _validate_resize_checkpoint(altered, "switch-a")
        except EvidenceFailure:
            print(f"ok [{index}/23] {label}")
        else:
            print(f"FAIL: host resize evidence mutation probe {index}: {label}", file=sys.stderr)
            return 1
    try:
        _validate_fresh_resize_ack_progress([5, 6, 6, 8])
    except EvidenceFailure:
        print("ok [21/23] reused resize acknowledgement across A→B→C→A rejected")
    else:
        print("FAIL: host resize evidence accepted a reused A→B→C→A acknowledgement", file=sys.stderr)
        return 1
    _validate_reconnected_resize_ack({"terminalResizeAckCount": 9}, 8)
    print("ok [22/23] fresh native resize acknowledgement after reconnect passes")
    try:
        _validate_reconnected_resize_ack({"terminalResizeAckCount": 8}, 8)
    except EvidenceFailure:
        print("ok [23/23] stale pre-expiry resize acknowledgement rejected after reconnect")
    else:
        print("FAIL: host resize evidence accepted a pre-expiry acknowledgement after reconnect", file=sys.stderr)
        return 1
    synthetic_timing = {
        "beyondGraceForegroundRequestedElapsedMs": 4_000,
        "beyondGraceForegroundRequestedEpochMs": 4_000,
        "beyondGraceExpiryEventObservedAfterForegroundElapsedMs": 4_100,
        "beyondGraceExpiryEventObservedAfterForegroundEpochMs": 4_100,
        "nativeGraceDeadlineEpochMs": 3_000,
        "nativeGraceDeadlineElapsedRealtimeMs": 3_000,
        "nativeGraceExpiryDispatchedAtEpochMs": 3_000,
        "nativeGraceExpiryDispatchedAtElapsedRealtimeMs": 3_000,
        "nativeTransportCloseCompletedAtEpochMs": 3_002,
        "nativeTransportCloseCompletedAtElapsedRealtimeMs": 3_002,
    }
    synthetic_event = {
        "nativeGraceDeadlineEpochMs": 3_000,
        "nativeGraceDeadlineElapsedRealtimeMs": 3_000,
        "nativeGraceExpiryDispatchedAtEpochMs": 3_000,
        "nativeGraceExpiryDispatchedAtElapsedRealtimeMs": 3_000,
        "nativeClosedAtEpochMs": 3_001,
        "nativeClosedAtElapsedRealtimeMs": 3_001,
        "nativeTransportCloseCompletedAtEpochMs": 3_002,
        "nativeTransportCloseCompletedAtElapsedRealtimeMs": 3_002,
        "nativeSocketClosedAfterClose": True,
        "nativeClientReportedConnectedAfterClose": True,
        "nativeRawSocketCloseFallbackUsed": True,
        "nativeSshjDisconnectErrorClass": "",
        "nativeSshjClientCloseErrorClass": "",
        "nativeRawSocketCloseErrorClass": "",
        "nativeCleanupExecutorRejectErrorClass": "",
        "atEpochMs": 3_003,
    }
    _validate_native_expiry_event_timing(synthetic_timing, synthetic_event)
    print("ok [24/32] background event timing and physical socket closure pass despite SSHJ's connected-state diagnostic")
    late_close_event = {
        **synthetic_event,
        "nativeTransportCloseCompletedAtEpochMs": 5_001,
        "nativeTransportCloseCompletedAtElapsedRealtimeMs": 5_001,
        "atEpochMs": 5_002,
    }
    late_close_timing = {
        **synthetic_timing,
        "beyondGraceForegroundRequestedElapsedMs": 6_000,
        "beyondGraceForegroundRequestedEpochMs": 6_000,
        "beyondGraceExpiryEventObservedAfterForegroundElapsedMs": 6_100,
        "beyondGraceExpiryEventObservedAfterForegroundEpochMs": 6_100,
        "nativeTransportCloseCompletedAtEpochMs": 5_001,
        "nativeTransportCloseCompletedAtElapsedRealtimeMs": 5_001,
    }
    for index, (label, altered_timing, altered_event) in enumerate((
        ("bridge delivery before transport close completion rejected", synthetic_timing, {**synthetic_event, "atEpochMs": 3_001}),
        ("grace expiry dispatch before deadline rejected", synthetic_timing, {
            **synthetic_event,
            "nativeGraceExpiryDispatchedAtElapsedRealtimeMs": 2_999,
            "nativeGraceExpiryDispatchedAtEpochMs": 2_999,
        }),
        ("cleanup worker started before dispatch rejected", synthetic_timing, {
            **synthetic_event,
            "nativeClosedAtElapsedRealtimeMs": 2_999,
        }),
        ("observation before foreground rejected", {
            **synthetic_timing,
            "beyondGraceExpiryEventObservedAfterForegroundElapsedMs": 3_999,
        }, synthetic_event),
        ("native close completion over two seconds late rejected", late_close_timing, late_close_event),
        ("native socket still open after close rejected", synthetic_timing, {**synthetic_event, "nativeSocketClosedAfterClose": False}),
        ("SSHJ main-thread network exception rejected", synthetic_timing, {**synthetic_event, "nativeSshjDisconnectErrorClass": "NetworkOnMainThreadException"}),
        ("cleanup executor rejection rejected", synthetic_timing, {**synthetic_event, "nativeCleanupExecutorRejectErrorClass": "RejectedExecutionException"}),
    ), 25):
        try:
            _validate_native_expiry_event_timing(altered_timing, altered_event)
        except EvidenceFailure:
            print(f"ok [{index}/32] {label}")
        else:
            print(f"FAIL: native grace timestamp mutation probe {index}: {label}", file=sys.stderr)
            return 1
    print("PASS: exact output/history, native resize, physical socket close timing, and host transport timeline guards (32/32)")
    return 0


def _remote_address(socket: dict[str, Any]) -> str:
    family = socket.get("family")
    remote = socket.get("remote")
    if family not in ("tcp", "tcp6") or not isinstance(remote, str) or ":" not in remote:
        raise EvidenceFailure("host socket sample contains an invalid remote address")
    encoded = remote.rsplit(":", 1)[0]
    try:
        if family == "tcp":
            if len(encoded) != 8:
                raise ValueError("IPv4 address must be eight hex digits")
            address = ipaddress.IPv4Address(bytes.fromhex(encoded)[::-1])
        else:
            if len(encoded) != 32:
                raise ValueError("IPv6 address must be 32 hex digits")
            packed = b"".join(bytes.fromhex(encoded[index:index + 8])[::-1] for index in range(0, 32, 8))
            address = ipaddress.IPv6Address(packed)
    except (ValueError, ipaddress.AddressValueError) as error:
        raise EvidenceFailure(f"host socket sample has a malformed remote address: {remote}") from error
    return str(address)


def _is_loopback_socket(socket: dict[str, Any]) -> bool:
    address = ipaddress.ip_address(_remote_address(socket))
    return address.is_loopback or (isinstance(address, ipaddress.IPv6Address)
                                   and address.ipv4_mapped is not None
                                   and address.ipv4_mapped.is_loopback)


def _socket_identity(socket: dict[str, Any]) -> tuple[str, str, str]:
    family = socket.get("family")
    local = socket.get("local")
    remote = socket.get("remote")
    if any(not isinstance(value, str) or not value for value in (family, local, remote)):
        raise EvidenceFailure("host socket sample contains an incomplete established connection")
    return family, local, remote


def validate_host_transport_timeline(
    timing: dict[str, Any],
    samples: list[dict[str, Any]],
    device_to_host_offset_ms: int,
    selected_grace_ms: int,
    reconnected_checkpoint_epoch_ms: int,
) -> dict[str, Any]:
    def number(key: str) -> int:
        value = timing.get(key)
        if not isinstance(value, int) or isinstance(value, bool):
            raise EvidenceFailure(f"lifecycle timeline is missing integer {key}")
        return value

    grace = number("selectedGraceMs")
    if grace != 30_000 or selected_grace_ms not in (0, grace):
        raise EvidenceFailure("host timeline must prove the persisted selected 30-second grace setting")
    connect_requested = number("sshConnectRequestedEpochMs") + device_to_host_offset_ms
    connected = number("sshConnectedEpochMs") + device_to_host_offset_ms
    within_background = number("withinGraceBackgroundEpochMs") + device_to_host_offset_ms
    within_foreground = number("withinGraceForegroundRequestedEpochMs") + device_to_host_offset_ms
    beyond_background = number("beyondGraceBackgroundEpochMs") + device_to_host_offset_ms
    beyond_deadline = number("nativeGraceDeadlineEpochMs") + device_to_host_offset_ms
    native_close_completed = number("nativeTransportCloseCompletedAtEpochMs") + device_to_host_offset_ms
    beyond_foreground = number("beyondGraceForegroundRequestedEpochMs") + device_to_host_offset_ms
    beyond_live = number("beyondGraceLiveEpochMs") + device_to_host_offset_ms

    if not 0 < connected - connect_requested < 120_000:
        raise EvidenceFailure("the initial SSH socket must be opened after the packaged connect request")
    if not 0 < within_foreground - within_background < grace:
        raise EvidenceFailure("the within-grace return must happen before the selected grace deadline")
    if not grace - 1_000 <= beyond_deadline - beyond_background <= grace + 1_000:
        raise EvidenceFailure("the beyond-grace deadline does not match the selected grace window")
    if abs(number("nativeGraceDeadlineEpochMs") - number("beyondGraceDeadlineEpochMs")) > 2_000:
        raise EvidenceFailure("native transport deadline does not match the selected grace window")
    if beyond_foreground < beyond_deadline + 1_000:
        raise EvidenceFailure("the beyond-grace foreground request did not cross the selected deadline")
    if not 0 <= native_close_completed - beyond_deadline <= MAX_NATIVE_CLOSE_COMPLETION_LAG_MS:
        raise EvidenceFailure("native transport close completion was outside the bounded grace-deadline window")
    if beyond_foreground < native_close_completed + MAX_HOST_SOCKET_CLOSE_LAG_AFTER_NATIVE_MS + MIN_HOST_ZERO_SOCKET_STABILITY_MS:
        raise EvidenceFailure("foreground began before the bounded host close-observation window had enough time")
    if beyond_live <= beyond_foreground:
        raise EvidenceFailure("the app reported a live reconnect before foreground was requested")

    ordered = sorted(samples, key=lambda row: row.get("sampledEpochMs", -1))
    if len(ordered) < 8:
        raise EvidenceFailure("the independent Docker socket watcher recorded too few samples")
    previous_epoch = -1
    normalized: list[dict[str, Any]] = []
    for raw_sample in ordered:
        sample = dict(raw_sample)
        sampled_at = sample.get("sampledEpochMs")
        count = sample.get("count")
        if not isinstance(sampled_at, int) or sampled_at <= previous_epoch:
            raise EvidenceFailure("host socket samples have missing or non-increasing timestamps")
        previous_epoch = sampled_at
        if sample.get("error"):
            raise EvidenceFailure(f"host socket watcher had an error: {sample['error']}")
        sockets = sample.get("establishedSshConnections")
        if not isinstance(count, int) or count < 0 or not isinstance(sockets, list) or count != len(sockets):
            raise EvidenceFailure("host socket watcher sample has an invalid established SSH count")
        if any(not isinstance(sock, dict) for sock in sockets):
            raise EvidenceFailure("host socket watcher sample contains a malformed socket")
        nonloopback = [sock for sock in sockets if not _is_loopback_socket(sock)]
        sample["lifecycleSockets"] = nonloopback
        sample["lifecycleSocketIds"] = {_socket_identity(sock) for sock in nonloopback}
        sample["lifecycleCount"] = len(nonloopback)
        sample["ignoredLoopbackCount"] = len(sockets) - len(nonloopback)
        normalized.append(sample)
    ordered = normalized

    baseline_samples = [row for row in ordered if row["sampledEpochMs"] < connect_requested]
    if len(baseline_samples) < 2:
        raise EvidenceFailure("socket watcher must record a stable baseline before the app SSH connect")
    baseline = baseline_samples[-1]["lifecycleCount"]
    baseline_set = baseline_samples[-1]["lifecycleSocketIds"]
    if baseline_samples[-2]["lifecycleCount"] != baseline or baseline_samples[-2]["lifecycleSocketIds"] != baseline_set:
        raise EvidenceFailure("Docker SSH baseline changed before the packaged app connected")
    if baseline != 0:
        raise EvidenceFailure("a non-loopback SSH client was already connected to the dedicated fixture before this run")

    def interval(start: int, end: int) -> list[dict[str, Any]]:
        return [row for row in ordered if start <= row["sampledEpochMs"] <= end]

    def require_count(label: str, start: int, end: int, expected: int, minimum: int) -> list[dict[str, Any]]:
        observed = interval(start, end)
        if len(observed) < minimum:
            raise EvidenceFailure(f"host socket watcher has too few samples for {label}")
        wrong = [row for row in observed if row["lifecycleCount"] != expected]
        if wrong:
            raise EvidenceFailure(
                f"host SSH connection count changed during {label}: expected {expected}, observed "
                f"{[(row['sampledEpochMs'], row['lifecycleCount']) for row in wrong[:3]]}"
            )
        return observed

    def require_original_socket(label: str, start: int, end: int, minimum: int, expected: set[tuple[str, str, str]] | None = None) -> set[tuple[str, str, str]]:
        observed = interval(start, end)
        if len(observed) < minimum:
            raise EvidenceFailure(f"host socket watcher has too few samples for {label}")
        identity = expected
        for row in observed:
            socket_ids = row["lifecycleSocketIds"]
            added = socket_ids - baseline_set
            if len(added) != 1 or len(socket_ids) != baseline + 1:
                raise EvidenceFailure(f"{label} did not have exactly one new non-loopback SSH socket")
            if identity is None:
                identity = added
            if added != identity:
                raise EvidenceFailure(f"{label} changed the original SSH socket identity")
        assert identity is not None
        return identity

    original_socket = require_original_socket("initial live SSH session", connected, within_background - 250, 2)
    require_original_socket("within-grace background", within_background - 250, within_foreground + 250, 2, original_socket)
    require_original_socket("beyond-grace session before deadline", beyond_background + 1_000, beyond_deadline - 500, 2, original_socket)
    expiry_observation_start = beyond_deadline + 500
    expiry_observation_end = beyond_foreground - 250
    expiry_samples = interval(expiry_observation_start, expiry_observation_end)
    if len(expiry_samples) < 4:
        raise EvidenceFailure("host socket watcher has too few samples to observe expiry and pre-foreground disconnect")
    original_identity = next(iter(original_socket))
    socket_gone_at: int | None = None
    zero_socket_samples: list[dict[str, Any]] = []
    for row in expiry_samples:
        identities = row["lifecycleSocketIds"] - baseline_set
        if row["lifecycleCount"] == baseline:
            socket_gone_at = row["sampledEpochMs"] if socket_gone_at is None else socket_gone_at
            zero_socket_samples.append(row)
            continue
        if socket_gone_at is not None:
            raise EvidenceFailure("an SSH socket reopened while the app was still backgrounded after the expired socket closed")
        if row["lifecycleCount"] != baseline + 1 or identities != {original_identity}:
            raise EvidenceFailure("background expiry opened a new or ambiguous SSH socket before foreground")
    if socket_gone_at is None:
        raise EvidenceFailure("the original Docker SSH socket remained ESTABLISHED until foreground was requested")
    host_close_lag = socket_gone_at - native_close_completed
    if host_close_lag < 0:
        raise EvidenceFailure("host socket disappeared before native transport close completed")
    if host_close_lag > MAX_HOST_SOCKET_CLOSE_LAG_AFTER_NATIVE_MS:
        raise EvidenceFailure(
            f"host SSH socket remained ESTABLISHED for {host_close_lag}ms after native close completion "
            f"(limit {MAX_HOST_SOCKET_CLOSE_LAG_AFTER_NATIVE_MS}ms)"
        )
    if len(zero_socket_samples) < 3 or zero_socket_samples[-1]["sampledEpochMs"] - zero_socket_samples[0]["sampledEpochMs"] < MIN_HOST_ZERO_SOCKET_STABILITY_MS:
        raise EvidenceFailure("the fixture had fewer than one second of stable zero-socket samples before foreground")
    reconnected_checkpoint = reconnected_checkpoint_epoch_ms + device_to_host_offset_ms
    if reconnected_checkpoint <= beyond_live:
        raise EvidenceFailure("reconnected terminal checkpoint predates the foreground reconnect")
    reconnected_samples = require_count("reconnected SSH session", beyond_live - 500, reconnected_checkpoint, baseline + 1, 1)
    reconnected_sockets = {
        tuple(sorted(row["lifecycleSocketIds"] - baseline_set))
        for row in reconnected_samples
    }
    if len(reconnected_sockets) != 1 or len(next(iter(reconnected_sockets))) != 1:
        raise EvidenceFailure("post-expiry SSH socket identity changed or was ambiguous")
    reconnected_identity = next(iter(reconnected_sockets))[0]
    if reconnected_identity == original_identity:
        raise EvidenceFailure("post-expiry reconnect reused the expired TCP socket identity")
    original_peer = _remote_address({"family": original_identity[0], "remote": original_identity[2]})
    reconnected_peer = _remote_address({"family": reconnected_identity[0], "remote": reconnected_identity[2]})
    if original_peer != reconnected_peer:
        raise EvidenceFailure("post-expiry SSH reconnect used a different Docker gateway peer")
    clock_adjusted_timeline = {
        "deviceToHostEpochOffsetMs": device_to_host_offset_ms,
        "nativeGraceDeadlineHostEpochMs": beyond_deadline,
        "nativeTransportCloseCompletedHostEpochMs": native_close_completed,
        "hostServerSocketDisappearedAtEpochMs": socket_gone_at,
        "nativeDeadlineToNativeCloseCompleteMs": native_close_completed - beyond_deadline,
        "nativeDeadlineToHostSocketDisappearedMs": socket_gone_at - beyond_deadline,
        "nativeCloseCompleteToHostSocketDisappearedMs": socket_gone_at - native_close_completed,
    }
    clock_adjusted_timeline["summary"] = (
        "Applied device-to-host epoch offset of "
        f"{device_to_host_offset_ms} ms: native deadline {beyond_deadline}, "
        f"transport close completion {native_close_completed}, server ESTABLISHED disappearance {socket_gone_at}; "
        f"deadline-to-disappearance {socket_gone_at - beyond_deadline} ms "
        f"({socket_gone_at - native_close_completed} ms after transport close completion)."
    )
    return {
        "source": "Docker container /proc/net/tcp and /proc/net/tcp6, ESTABLISHED local port 22",
        "deviceToHostEpochOffsetMs": device_to_host_offset_ms,
        "baselineEstablishedConnections": baseline,
        "liveEstablishedConnections": baseline + 1,
        "ignoredLoopbackHealthChecks": sum(row["ignoredLoopbackCount"] for row in ordered),
        "expiredSocketIdentity": list(original_identity),
        "reconnectedSocketIdentity": list(reconnected_identity),
        "dockerGatewayPeer": original_peer,
        "withinGraceSamples": len(interval(within_background - 250, within_foreground + 250)),
        "expiredBeforeForegroundSamples": len(expiry_samples),
        "nativeGraceDeadlineHostEpochMs": beyond_deadline,
        "nativeTransportCloseCompletedHostEpochMs": native_close_completed,
        "hostServerSocketDisappearedAtEpochMs": socket_gone_at,
        "nativeDeadlineToNativeCloseCompleteMs": number("nativeTransportCloseCompletedAtEpochMs") - number("nativeGraceDeadlineEpochMs"),
        "nativeCloseCompleteToHostSocketDisappearedMs": host_close_lag,
        "nativeDeadlineToHostSocketDisappearedMs": socket_gone_at - beyond_deadline,
        "clockAdjustedTimeline": clock_adjusted_timeline,
        "zeroSocketBeforeForegroundStableMs": zero_socket_samples[-1]["sampledEpochMs"] - zero_socket_samples[0]["sampledEpochMs"],
        "hostSocketCloseLagLimitMs": MAX_HOST_SOCKET_CLOSE_LAG_AFTER_NATIVE_MS,
        "reconnectedSamples": len(reconnected_samples),
        "sampleCount": len(ordered),
        "samples": [
            {
                "sampledHostEpochMs": row["sampledEpochMs"],
                "rawEstablishedCount": row["count"],
                "ignoredLoopbackCount": row["ignoredLoopbackCount"],
                "nonLoopbackEstablishedCount": row["lifecycleCount"],
                "nonLoopbackSockets": row["lifecycleSockets"],
            }
            for row in ordered
        ],
        "result": "PASS",
    }


def _read_socket_samples(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise EvidenceFailure(f"could not read independent Docker socket samples {path}: {error}") from error
    samples: list[dict[str, Any]] = []
    for index, line in enumerate(lines, 1):
        try:
            value = json.loads(line)
        except json.JSONDecodeError as error:
            raise EvidenceFailure(f"Docker socket sample {index} is malformed: {error}") from error
        if not isinstance(value, dict):
            raise EvidenceFailure(f"Docker socket sample {index} is not an object")
        samples.append(value)
    return samples


def _device_to_host_epoch_offset(path: Path) -> int:
    timebase = _read_json(path)
    if timebase.get("schema") != 1:
        raise EvidenceFailure("host/device clock evidence has an unsupported schema")
    before = timebase.get("before")
    after = timebase.get("after")
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise EvidenceFailure("host/device clock evidence needs before and after samples")
    offsets = [before.get("offsetMs"), after.get("offsetMs")]
    if any(not isinstance(offset, int) or isinstance(offset, bool) for offset in offsets):
        raise EvidenceFailure("host/device clock offset is missing from the timebase evidence")
    if abs(offsets[1] - offsets[0]) > 1_000:
        raise EvidenceFailure("host/device clock offset changed by more than one second during the packaged journey")
    return round((offsets[0] + offsets[1]) / 2)


def validate(
    run_id: str, container: str, artifact_directory: Path, host_connections_path: Path, timebase_path: Path
) -> dict[str, Any]:
    if not RUN_ID_RE.fullmatch(run_id):
        raise EvidenceFailure("run ID has an invalid tag-safe format")
    summary_path = artifact_directory / "journey-summary.json"
    summary = _read_json(summary_path)
    if summary.get("schema") != 1 or summary.get("runId") != run_id:
        raise EvidenceFailure("packaged summary has the wrong schema or run ID")
    if summary.get("graceMs") != 30_000:
        raise EvidenceFailure("journey did not exercise the selected 30-second grace setting")
    if summary.get("terminalInputPending") != 0 or summary.get("terminalInputFailures") != 0:
        raise EvidenceFailure("journey ended with pending terminal input or a terminal input failure")

    sessions = summary.get("sessions")
    if not isinstance(sessions, list) or len(sessions) != 3:
        raise EvidenceFailure("packaged journey must report exactly three live host session rows")
    expected_tags = {f"{run_id}-{letter}" for letter in "abc"}
    rows = {row.get("tag"): row for row in sessions if isinstance(row, dict)}
    if set(rows) != expected_tags:
        raise EvidenceFailure(f"packaged host rows do not match the A/B/C run tags: {sorted(rows)}")
    identities = [row.get("id") for row in sessions]
    if any(not isinstance(identity, str) or not re.fullmatch(r"[a-f0-9-]{36}", identity) for identity in identities):
        raise EvidenceFailure("packaged host rows are missing immutable aplexer UUIDs")
    if len(set(identities)) != 3:
        raise EvidenceFailure("A, B, and C must have distinct host session identities")

    checkpoints = summary.get("checkpoints")
    if not isinstance(checkpoints, list):
        raise EvidenceFailure("packaged journey is missing checkpoint evidence")
    by_checkpoint = {entry.get("checkpoint"): entry for entry in checkpoints if isinstance(entry, dict)}
    expected_names = set(CHECKPOINTS) | {"background-within-grace", "reconnected-after-expiry"}
    if set(by_checkpoint) != expected_names:
        raise EvidenceFailure(f"missing or unexpected checkpoints: expected {sorted(expected_names)}, found {sorted(by_checkpoint)}")
    checkpoint_order = [entry.get("checkpoint") for entry in checkpoints if isinstance(entry, dict)]
    expected_order = ["switch-a", "switch-b", "switch-c", "switch-a-return", "background-within-grace", "reconnected-after-expiry"]
    if checkpoint_order != expected_order:
        raise EvidenceFailure(f"packaged checkpoints were not captured in the required A→B→C→A→grace order: {checkpoint_order}")
    run_token = hashlib.sha256(run_id.encode("utf-8")).hexdigest()[:10].upper()
    expected_marker_prefix = f"REMOTE_OUTPUT_{run_token}_"
    markers = [by_checkpoint[name].get("marker") for name in expected_order]
    if any(not isinstance(marker, str) for marker in markers) or len(set(markers)) != len(expected_order):
        raise EvidenceFailure("each lifecycle checkpoint must have its own unique remote output marker")
    for name, marker in zip(expected_order, markers, strict=True):
        if marker != expected_marker_prefix + MARKER_PHASES[name]:
            raise EvidenceFailure(f"{name}: remote marker does not identify its unique lifecycle phase")
    ack_counts: list[int] = []
    resize_ack_counts: list[int] = []
    for name in expected_order:
        checkpoint = by_checkpoint[name]
        if checkpoint.get("inputPending") != 0 or checkpoint.get("inputFailureCount") != 0:
            raise EvidenceFailure(f"{name}: terminal input was pending or failed at the checkpoint")
        ack = checkpoint.get("inputAckCount")
        if not isinstance(ack, int) or ack < 1:
            raise EvidenceFailure(f"{name}: native terminal input acknowledgement is missing")
        ack_counts.append(ack)
        columns = checkpoint.get("terminalColumns")
        marker = checkpoint.get("marker")
        if not isinstance(columns, int) or columns < 1 or not isinstance(marker, str) or len(marker) > columns:
            raise EvidenceFailure(f"{name}: marker must fit within the recorded PTY column count")
        resize_ack_counts.append(_validate_resize_checkpoint(checkpoint, name))
    if any(after <= before for before, after in zip(ack_counts, ack_counts[1:])):
        raise EvidenceFailure("each checkpoint must follow a newly acknowledged native terminal input")
    _validate_fresh_resize_ack_progress(resize_ack_counts)

    initial_connection = summary.get("initialConnectionId")
    if not isinstance(initial_connection, str) or not initial_connection:
        raise EvidenceFailure("initial live SSH connection ID is missing")
    screenshot_marker_evidence: dict[str, dict[str, Any]] = {}
    for checkpoint_name, session_letter in CHECKPOINTS.items():
        checkpoint = by_checkpoint[checkpoint_name]
        session = rows[f"{run_id}-{session_letter}"]
        screenshot_marker_evidence[checkpoint_name] = _validate_checkpoint(
            artifact_directory, checkpoint, session, initial_connection
        )
    within = by_checkpoint["background-within-grace"]
    screenshot_marker_evidence["background-within-grace"] = _validate_checkpoint(
        artifact_directory, within, rows[f"{run_id}-a"], initial_connection
    )
    if summary.get("connectionAfterWithinGrace") != initial_connection:
        raise EvidenceFailure("within-grace foreground return changed the SSH connection ID")
    if summary.get("expiredConnectionId") != initial_connection or summary.get("expiryBridgeEventObserved") is not True:
        raise EvidenceFailure("journey did not observe the native grace-expired bridge event for the original connection")
    timing = summary.get("graceTiming")
    if not isinstance(timing, dict):
        raise EvidenceFailure("journey is missing its monotonic lifecycle timing record")
    expected_timing_fields = (
        "selectedGraceMs", "sshConnectRequestedEpochMs", "sshConnectedEpochMs",
        "withinGraceBackgroundEpochMs", "withinGraceForegroundRequestedEpochMs",
        "beyondGraceBackgroundEpochMs", "beyondGraceDeadlineEpochMs", "beyondGraceDeadlineElapsedMs",
        "beyondGraceWaitCompleteElapsedMs", "beyondGraceForegroundRequestedEpochMs",
        "beyondGraceForegroundRequestedElapsedMs", "beyondGraceLiveEpochMs",
        "nativeGraceDeadlineEpochMs", "nativeGraceDeadlineElapsedRealtimeMs",
        "nativeTransportCloseCompletedAtEpochMs", "nativeTransportCloseCompletedAtElapsedRealtimeMs",
        "beyondGraceResizeAckBefore",
        "beyondGraceExpiryEventObservedAfterForegroundElapsedMs",
        "beyondGraceExpiryEventObservedAfterForegroundEpochMs",
    )
    if any(not isinstance(timing.get(key), int) for key in expected_timing_fields):
        raise EvidenceFailure("journey lifecycle timing is incomplete")
    if timing["beyondGraceWaitCompleteElapsedMs"] < timing["beyondGraceDeadlineElapsedMs"] + 10_000:
        raise EvidenceFailure("journey did not wait ten seconds past the monotonic grace deadline for host-side teardown")
    bridge_events = summary.get("bridgeEvents")
    if not isinstance(bridge_events, list):
        raise EvidenceFailure("raw native SSH bridge events are missing")
    expiry_events = [
        event for event in bridge_events
        if isinstance(event, dict) and event.get("state") == "closed"
        and event.get("reason") == "grace-expired" and event.get("connectionId") == initial_connection
    ]
    if len(expiry_events) != 1:
        raise EvidenceFailure("exactly one native grace-expired event for the original connection must be recorded")
    native_expiry = expiry_events[0]
    native_timestamp_keys = (
        "nativeGraceScheduledAtEpochMs", "nativeGraceScheduledAtElapsedRealtimeMs",
        "nativeGraceDeadlineEpochMs", "nativeGraceDeadlineElapsedRealtimeMs",
        "nativeClosedAtEpochMs", "nativeClosedAtElapsedRealtimeMs",
        "nativeTransportCloseCompletedAtEpochMs", "nativeTransportCloseCompletedAtElapsedRealtimeMs", "atEpochMs",
    )
    if any(not isinstance(native_expiry.get(key), int) for key in native_timestamp_keys):
        raise EvidenceFailure("native grace event is missing schedule, deadline, close, or bridge-delivery timestamps")
    if native_expiry.get("generationId") != by_checkpoint["switch-a-return"].get("generationId"):
        raise EvidenceFailure("native expiry event belongs to a different SSH transport generation")
    _validate_native_expiry_event_timing(timing, native_expiry)
    if abs(native_expiry["nativeGraceDeadlineEpochMs"] - (
        timing["beyondGraceDeadlineEpochMs"]
    )) > 2_000:
        raise EvidenceFailure("native epoch deadline does not match the selected grace period")
    socket_timeline = validate_host_transport_timeline(
        timing,
        _read_socket_samples(host_connections_path),
        _device_to_host_epoch_offset(timebase_path),
        summary.get("graceMs", 0),
        by_checkpoint["reconnected-after-expiry"].get("capturedAtEpochMs", 0),
    )
    socket_samples = socket_timeline.pop("samples")
    socket_timeline_path = artifact_directory / "host-socket-timeline.json"
    socket_timeline_path.write_text(json.dumps({
        "schema": 1,
        "runId": run_id,
        **socket_timeline,
        "samples": socket_samples,
    }, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    copied_socket_samples = artifact_directory / "host-ssh-connections.jsonl"
    shutil.copy2(host_connections_path, copied_socket_samples)
    copied_timebase = artifact_directory / "host-time-offset.json"
    shutil.copy2(timebase_path, copied_timebase)

    after = by_checkpoint["reconnected-after-expiry"]
    screenshot_marker_evidence["reconnected-after-expiry"] = _validate_checkpoint(
        artifact_directory, after, rows[f"{run_id}-a"], None
    )
    _validate_reconnected_resize_ack(after, timing["beyondGraceResizeAckBefore"])
    expired_connection = summary.get("connectionAfterExpiry")
    if not isinstance(expired_connection, str) or not expired_connection or expired_connection == initial_connection:
        raise EvidenceFailure("beyond-grace foreground return did not create a new SSH connection")
    if after.get("connectionId") != expired_connection or after.get("generationId") == by_checkpoint["switch-a-return"].get("generationId"):
        raise EvidenceFailure("post-expiry viewport is not bound to the reconnected transport generation")

    phase_events = summary.get("phaseEvents")
    if not isinstance(phase_events, list):
        raise EvidenceFailure("phase history is missing")
    recorded_events = [event for event in phase_events if isinstance(event, dict)]
    if sum(event.get("phase") == "background" for event in recorded_events) < 2:
        raise EvidenceFailure("phase history must show both requested app backgrounds")
    original_generation = by_checkpoint["switch-a-return"].get("generationId")
    if not any(
        event.get("phase") == "background"
        and not event.get("connectionId")
        and event.get("generationId") == original_generation
        for event in recorded_events
    ):
        raise EvidenceFailure("phase history is missing the native bridge event that cleared the expired transport")
    if not any(
        event.get("phase") == "live"
        and event.get("connectionId") == expired_connection
        and event.get("tag") == f"{run_id}-a"
        and event.get("selectedId") == rows[f"{run_id}-a"].get("id")
        for event in recorded_events
    ):
        raise EvidenceFailure("phase history is missing the reconnected live A session")
    diagnostic_events = summary.get("diagnosticEvents")
    if not isinstance(diagnostic_events, list):
        raise EvidenceFailure("lifecycle diagnostics are missing")
    kinds = [event.get("kind") for event in diagnostic_events if isinstance(event, dict)]
    if kinds.count("app-backgrounded") < 2 or kinds.count("app-foregrounded") < 2:
        raise EvidenceFailure("diagnostics must record both backgrounds and both foreground returns")

    raw_snapshot = _run([
        "docker", "exec", "-u", "testuser", "-e", "HOME=/home/testuser", container,
        "/usr/bin/a", "snapshot", "--json",
    ])
    snapshot_path = artifact_directory / "host-aplexer-snapshot.json"
    snapshot_path.write_text(raw_snapshot, encoding="utf-8")
    try:
        host_rows = json.loads(raw_snapshot)
    except json.JSONDecodeError as error:
        raise EvidenceFailure(f"independent a snapshot was malformed: {error}") from error
    if not isinstance(host_rows, list):
        raise EvidenceFailure("independent a snapshot did not return a row array")

    joined_rows: list[dict[str, Any]] = []
    captures: dict[str, str] = {}
    for tag in sorted(expected_tags):
        expected = rows[tag]
        matches = [
            row for row in host_rows
            if isinstance(row, dict)
            and row.get("workspace") == expected.get("workspace")
            and row.get("tag") == tag
            and row.get("id") == expected.get("id")
        ]
        if len(matches) != 1:
            raise EvidenceFailure(f"independent a snapshot must contain exactly one matching host row for {tag}; found {len(matches)}")
        host_row = matches[0]
        if host_row.get("state") != "running" or host_row.get("worker_alive") is not True:
            raise EvidenceFailure(f"host session {tag} is not running with a live aplexer worker")
        joined_rows.append(host_row)

        raw_history = _run([
            "docker", "exec", "-u", "testuser", "-e", "HOME=/home/testuser", container,
            "/usr/bin/a", "capture", "--bytes", "65536", str(expected["id"]),
        ])
        history_capture_path = artifact_directory / f"host-aplexer-history-capture-{tag}.txt"
        history_capture_path.write_text(raw_history, encoding="utf-8")
        raw_screen = _run([
            "docker", "exec", "-u", "testuser", "-e", "HOME=/home/testuser", container,
            "/usr/bin/a", "capture", str(expected["id"]), "--screen", "--plain",
        ])
        screen_capture_path = artifact_directory / f"host-aplexer-screen-capture-{tag}.txt"
        screen_capture_path.write_text(raw_screen, encoding="utf-8")
        markers = {
            checkpoint.get("marker") for checkpoint in checkpoints
            if isinstance(checkpoint, dict) and checkpoint.get("tag") == tag
            and isinstance(checkpoint.get("marker"), str)
        }
        latest_checkpoint = max(
            (checkpoint for checkpoint in checkpoints if isinstance(checkpoint, dict) and checkpoint.get("tag") == tag),
            key=lambda checkpoint: checkpoint.get("capturedAtEpochMs", 0),
        )
        latest_marker = latest_checkpoint.get("marker") if isinstance(latest_checkpoint.get("marker"), str) else ""
        validate_independent_session_captures(raw_history, raw_screen, markers, latest_marker, tag)
        captures[tag] = {
            "history": history_capture_path.name,
            "currentScreen": screen_capture_path.name,
        }

    oracle = {
        "schema": 1,
        "runId": run_id,
        "source": "independent Docker exec: /usr/bin/a snapshot --json, a capture --bytes 65536 by session UUID, and a capture --screen --plain by session UUID",
        "matchedRows": joined_rows,
        "captures": captures,
        "packagedCheckpoints": sorted(expected_names),
        "screenshotMarkerOcr": screenshot_marker_evidence,
        "nativeGraceExpiry": native_expiry,
        "hostSocketTimeline": socket_timeline,
        "hostSocketTimelineFile": socket_timeline_path.name,
        "rawHostSocketSamplesFile": copied_socket_samples.name,
        "hostDeviceTimebaseFile": copied_timebase.name,
        "result": "PASS",
    }
    oracle_path = artifact_directory / "host-oracle-summary.json"
    oracle_path.write_text(json.dumps(oracle, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return oracle


def _validate_checkpoint(
    directory: Path, checkpoint: dict[str, Any], session: dict[str, Any], connection_id: str | None
) -> dict[str, Any]:
    if checkpoint.get("phase") != "live":
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: app phase is not live")
    if not isinstance(checkpoint.get("capturedAtEpochMs"), int):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot capture timestamp is missing")
    for key, expected_key in (("tag", "tag"), ("selectedId", "id"), ("workspace", "workspace")):
        if checkpoint.get(key) != session.get(expected_key):
            raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: selected {key} does not match host row")
    if checkpoint.get("selectedName") != session.get("name"):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: terminal title does not match selected host session")
    if connection_id is not None and checkpoint.get("connectionId") != connection_id:
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: live switch changed SSH connection ID")
    text_name = checkpoint.get("visibleTerminalFile")
    marker = checkpoint.get("marker")
    if not isinstance(text_name, str) or not isinstance(marker, str):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: viewport text metadata is missing")
    text_path = directory / text_name
    try:
        visible_text = text_path.read_text(encoding="utf-8")
    except OSError as error:
        raise EvidenceFailure(f"missing visible terminal text {text_path}: {error}") from error
    if not has_exact_terminal_line(visible_text, marker):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: visible terminal text lacks its standalone remote output line")
    png_name = checkpoint.get("viewportPng")
    if not isinstance(png_name, str):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: viewport PNG name is missing")
    png_path = directory / png_name
    image_width, image_height = _png_dimensions(png_path)
    viewport = checkpoint.get("viewportRect")
    marker_rect = checkpoint.get("markerRect")
    pixels = checkpoint.get("screenshotPixels")
    if not isinstance(viewport, dict) or not isinstance(marker_rect, dict) or not isinstance(pixels, dict):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot geometry and pixel evidence are missing")
    dpr = viewport.get("devicePixelRatio")
    if not isinstance(dpr, (int, float)) or dpr <= 0 or pixels.get("devicePixelRatio") != dpr:
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot DPR is missing or inconsistent")
    expected_width = round(float(viewport.get("width", 0)) * dpr)
    expected_height = round(float(viewport.get("height", 0)) * dpr)
    if abs(image_width - expected_width) > 1 or abs(image_height - expected_height) > 1:
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: PNG dimensions do not match the selected terminal viewport at DPR")
    if pixels.get("cropWidth") != image_width or pixels.get("cropHeight") != image_height:
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot crop metadata disagrees with the PNG")
    if not isinstance(pixels.get("markerBrightPixels"), int) or pixels["markerBrightPixels"] < 8:
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot does not prove rendered marker text")
    if not (
        marker_rect.get("left", float("inf")) >= viewport.get("left", float("-inf"))
        and marker_rect.get("top", float("inf")) >= viewport.get("top", float("-inf"))
        and marker_rect.get("right", float("-inf")) <= viewport.get("right", float("inf"))
        and marker_rect.get("bottom", float("-inf")) <= viewport.get("bottom", float("inf"))
    ):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: marker row lies outside the captured terminal viewport")
    marker_bounds = _marker_pixel_bounds(viewport, marker_rect)
    ocr_evidence = _screenshot_marker_ocr(png_path, marker, marker_bounds)
    if pixels.get("markerAccentColor") != _screenshot_marker_accent_rgb(marker):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot lacks the expected terminal-output accent color")
    if (
        not isinstance(pixels.get("markerAccentPixels"), int)
        or pixels["markerAccentPixels"] < MIN_SCREENSHOT_MARKER_ACCENT_PIXELS
    ):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: screenshot lacks the current marker-row accent pixels")
    return ocr_evidence


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id")
    parser.add_argument("--container")
    parser.add_argument("--artifact-directory", type=Path)
    parser.add_argument("--host-connections", type=Path)
    parser.add_argument("--timebase", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return _self_test()
    if not args.run_id or not args.container or args.artifact_directory is None or args.host_connections is None or args.timebase is None:
        parser.error("--run-id, --container, --artifact-directory, --host-connections, and --timebase are required unless --self-test is used")
    try:
        oracle = validate(args.run_id, args.container, args.artifact_directory, args.host_connections, args.timebase)
    except EvidenceFailure as error:
        print(f"FAIL: lifecycle host evidence: {error}", file=sys.stderr)
        return 1
    print(f"PASS: independent aplexer oracle matched {len(oracle['matchedRows'])} live host rows and all terminal markers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
