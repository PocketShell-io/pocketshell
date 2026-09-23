#!/usr/bin/python3 -I
"""Join packaged viewport evidence to independent aplexer host state."""

from __future__ import annotations

import argparse
import json
import re
import struct
import subprocess
import sys
from pathlib import Path
from typing import Any


RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$")
CHECKPOINTS = {
    "switch-a": "a",
    "switch-b": "b",
    "switch-c": "c",
    "switch-a-return": "a",
}


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


def validate(run_id: str, container: str, artifact_directory: Path) -> dict[str, Any]:
    if not RUN_ID_RE.fullmatch(run_id):
        raise EvidenceFailure("run ID has an invalid tag-safe format")
    summary_path = artifact_directory / "journey-summary.json"
    summary = _read_json(summary_path)
    if summary.get("schema") != 1 or summary.get("runId") != run_id:
        raise EvidenceFailure("packaged summary has the wrong schema or run ID")
    if summary.get("graceMs") != 30_000:
        raise EvidenceFailure("journey did not exercise the selected 30-second grace setting")

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

    initial_connection = summary.get("initialConnectionId")
    if not isinstance(initial_connection, str) or not initial_connection:
        raise EvidenceFailure("initial live SSH connection ID is missing")
    for checkpoint_name, session_letter in CHECKPOINTS.items():
        checkpoint = by_checkpoint[checkpoint_name]
        session = rows[f"{run_id}-{session_letter}"]
        _validate_checkpoint(artifact_directory, checkpoint, session, initial_connection)
    within = by_checkpoint["background-within-grace"]
    _validate_checkpoint(artifact_directory, within, rows[f"{run_id}-a"], initial_connection)
    if summary.get("connectionAfterWithinGrace") != initial_connection:
        raise EvidenceFailure("within-grace foreground return changed the SSH connection ID")
    if summary.get("expiredConnectionId") != initial_connection or summary.get("expiryBridgeEventObserved") is not True:
        raise EvidenceFailure("journey did not observe the native grace-expired bridge event for the original connection")

    after = by_checkpoint["reconnected-after-expiry"]
    _validate_checkpoint(artifact_directory, after, rows[f"{run_id}-a"], None)
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

        raw_capture = _run([
            "docker", "exec", "-u", "testuser", "-e", "HOME=/home/testuser", container,
            "/usr/bin/a", "capture", "--workspace", str(expected["workspace"]), "--tag", tag,
            "--screen", "--plain",
        ])
        capture_path = artifact_directory / f"host-aplexer-capture-{tag}.txt"
        capture_path.write_text(raw_capture, encoding="utf-8")
        markers = {
            checkpoint.get("marker") for checkpoint in checkpoints
            if isinstance(checkpoint, dict) and checkpoint.get("tag") == tag
            and isinstance(checkpoint.get("marker"), str)
        }
        if not markers or any(marker not in raw_capture for marker in markers):
            raise EvidenceFailure(f"independent a capture for {tag} does not contain every packaged terminal marker")
        captures[tag] = capture_path.name

    oracle = {
        "schema": 1,
        "runId": run_id,
        "source": "independent Docker exec: /usr/bin/a snapshot --json and a capture --screen --plain",
        "matchedRows": joined_rows,
        "captures": captures,
        "packagedCheckpoints": sorted(expected_names),
        "result": "PASS",
    }
    oracle_path = artifact_directory / "host-oracle-summary.json"
    oracle_path.write_text(json.dumps(oracle, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return oracle


def _validate_checkpoint(directory: Path, checkpoint: dict[str, Any], session: dict[str, Any], connection_id: str | None) -> None:
    if checkpoint.get("phase") != "live":
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: app phase is not live")
    for key, expected_key in (("tag", "tag"), ("selectedId", "id"), ("workspace", "workspace")):
        if checkpoint.get(key) != session.get(expected_key):
            raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: selected {key} does not match host row")
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
    if marker not in visible_text:
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: visible terminal text lacks its remote marker")
    png_name = checkpoint.get("viewportPng")
    if not isinstance(png_name, str):
        raise EvidenceFailure(f"{checkpoint.get('checkpoint')}: viewport PNG name is missing")
    _png_dimensions(directory / png_name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--artifact-directory", type=Path, required=True)
    args = parser.parse_args()
    try:
        oracle = validate(args.run_id, args.container, args.artifact_directory)
    except EvidenceFailure as error:
        print(f"FAIL: lifecycle host evidence: {error}", file=sys.stderr)
        return 1
    print(f"PASS: independent aplexer oracle matched {len(oracle['matchedRows'])} live host rows and all terminal markers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
