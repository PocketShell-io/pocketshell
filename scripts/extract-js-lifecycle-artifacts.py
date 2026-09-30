#!/usr/bin/python3 -I
"""Rebuild same-run packaged screenshots and text from Android logcat chunks."""

from __future__ import annotations

import argparse
import binascii
import base64
import hashlib
import json
import re
import sys
from pathlib import Path


SAFE_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")


class ExtractionFailure(ValueError):
    pass


def extract(run_id: str, logcat: Path, output: Path) -> list[str]:
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        raise ExtractionFailure(f"could not read Android logcat {logcat}: {error}") from error

    assets: dict[str, dict[str, object]] = {}
    for line in lines:
        if "PocketshellJourneyAsset:" not in line:
            continue
        message = line.split("PocketshellJourneyAsset:", 1)[1].strip()
        parts = message.split("|", 4)
        if len(parts) < 3 or parts[1] != run_id:
            continue
        kind, _, name = parts[:3]
        if not SAFE_NAME.fullmatch(name):
            raise ExtractionFailure(f"unsafe logged artifact name {name!r}")
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
            if name not in assets or len(parts) != 3 or assets[name]["ended"]:
                raise ExtractionFailure(f"END record has no matching BEGIN for {name}")
            assets[name]["ended"] = True
        else:
            raise ExtractionFailure(f"unknown artifact record kind {kind!r} for {name}")

    output.mkdir(parents=True, exist_ok=True)
    extracted: list[str] = []
    for name, asset in sorted(assets.items()):
        parts = asset["parts"]
        assert isinstance(parts, dict)
        count = asset["count"]
        if not asset["ended"] or len(parts) != count or set(parts) != set(range(count)):
            raise ExtractionFailure(f"artifact {name} is incomplete: {len(parts)}/{count} chunks, ended={asset['ended']}")
        encoded = "".join(str(parts[index]) for index in range(count))
        try:
            payload = base64.b64decode(encoded, validate=True)
        except (ValueError, binascii.Error) as error:
            raise ExtractionFailure(f"artifact {name} contains invalid base64: {error}") from error
        digest = hashlib.sha256(payload).hexdigest()
        if digest != asset["sha256"]:
            raise ExtractionFailure(f"artifact {name} SHA-256 does not match its logcat manifest")
        (output / name).write_bytes(payload)
        extracted.append(name)

    if "journey-summary.json" not in assets:
        raise ExtractionFailure(f"no completed journey summary was logged for {run_id}")
    try:
        summary = json.loads((output / "journey-summary.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ExtractionFailure(f"journey summary is invalid: {error}") from error
    if summary.get("schema") != 1 or summary.get("runId") != run_id:
        raise ExtractionFailure("journey summary has the wrong schema or run ID")
    required = {"journey-summary.json"}
    for checkpoint in summary.get("checkpoints", []):
        if not isinstance(checkpoint, dict):
            raise ExtractionFailure("journey summary has an invalid checkpoint")
        required.update((checkpoint.get("visibleTerminalFile", ""), checkpoint.get("viewportPng", "")))
    required.discard("")
    missing = sorted(required - set(extracted))
    if missing:
        raise ExtractionFailure("missing packaged evidence artifacts: " + ", ".join(missing))
    return extracted


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--logcat", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        names = extract(args.run_id, args.logcat, args.output_dir)
    except ExtractionFailure as error:
        print(f"FAIL: lifecycle artifact extraction: {error}", file=sys.stderr)
        return 1
    print(f"PASS: extracted {len(names)} same-run packaged lifecycle artifacts")
    for name in names:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
