#!/usr/bin/env python3
"""Validate independent Docker PTY snapshots taken before prompt actions."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
from pathlib import Path


STAGES = ("recording-insert", "transcribing-insert", "transcribing-send", "stop-review")


class OracleFailure(ValueError):
    pass


def validate(evidence_dir: Path, run_id: str, session: str) -> list[Path]:
    reports: list[Path] = []
    for stage in STAGES:
        path = evidence_dir / f"composer-host-oracle-pre-{stage}.json"
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise OracleFailure(f"{path.name} is missing or invalid: {error}") from error
        if (not isinstance(report, dict) or report.get("runId") != run_id
                or report.get("stage") != stage or report.get("session") != session
                or report.get("source") != "docker-host-a-capture" or report.get("exitCode") != 0):
            raise OracleFailure(f"{path.name} does not identify a successful same-run Docker PTY capture")
        try:
            capture = base64.b64decode(report["hostBytesBase64"], validate=True)
        except (KeyError, ValueError) as error:
            raise OracleFailure(f"{path.name} does not contain valid independent host bytes") from error
        marker = report.get("forbiddenMarker")
        if not isinstance(marker, str) or not marker:
            raise OracleFailure(f"{path.name} is missing the dictated text checked at this checkpoint")
        if (report.get("hostBytesLength") != len(capture)
                or report.get("hostBytesSha256") != hashlib.sha256(capture).hexdigest()
                or report.get("ptyWriteObserved") is not False
                or report.get("noPtyWriteBeforeExplicitAction") is not True
                or marker.encode("utf-8") in capture):
            raise OracleFailure(f"{path.name} shows dictated text in the host PTY before the explicit action")
        reports.append(path)
    return reports


def self_test() -> int:
    import tempfile

    run_id = "js2857-oracle-self-test"
    session = f"{run_id}-bytes"
    capture = b"$ "
    def write_report(root: Path, stage: str, payload: bytes = capture, marker: str = "dictated-marker") -> None:
        report = {
            "runId": run_id,
            "stage": stage,
            "session": session,
            "source": "docker-host-a-capture",
            "exitCode": 0,
            "hostBytesBase64": base64.b64encode(payload).decode("ascii"),
            "hostBytesLength": len(payload),
            "hostBytesSha256": hashlib.sha256(payload).hexdigest(),
            "forbiddenMarker": marker,
            "ptyWriteObserved": False,
            "noPtyWriteBeforeExplicitAction": True,
        }
        (root / f"composer-host-oracle-pre-{stage}.json").write_text(json.dumps(report), encoding="utf-8")

    with tempfile.TemporaryDirectory(prefix="pocketshell-js-composer-oracle-") as temporary:
        root = Path(temporary)
        for stage in STAGES:
            write_report(root, stage)
        assert len(validate(root, run_id, session)) == len(STAGES)
        bad = json.loads((root / f"composer-host-oracle-pre-{STAGES[0]}.json").read_text(encoding="utf-8"))
        bad_capture = b"dictated-marker"
        bad.update({
            "hostBytesBase64": base64.b64encode(bad_capture).decode("ascii"),
            "hostBytesLength": len(bad_capture),
            "hostBytesSha256": hashlib.sha256(bad_capture).hexdigest(),
            "ptyWriteObserved": True,
            "noPtyWriteBeforeExplicitAction": False,
        })
        (root / f"composer-host-oracle-pre-{STAGES[0]}.json").write_text(json.dumps(bad), encoding="utf-8")
        try:
            validate(root, run_id, session)
        except OracleFailure:
            print("PASS: host-byte oracle rejects a dictated marker in the pre-action PTY capture")
        else:
            raise AssertionError("host-byte oracle accepted a dictated marker before user action")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--session")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.evidence_dir is None or not args.run_id or not args.session:
        parser.error("--evidence-dir, --run-id, and --session are required")
    try:
        reports = validate(args.evidence_dir, args.run_id, args.session)
    except OracleFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    for report in reports:
        print(f"PASS: independent pre-action Docker PTY bytes contain no transcript: {report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
