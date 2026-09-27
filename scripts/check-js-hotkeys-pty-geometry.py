#!/usr/bin/env python3
"""Compare packaged xterm geometry checkpoints with independent remote stty samples."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


class OracleFailure(ValueError):
    pass


STEADY_STAGES = (
    "dictation-ready-ime-open",
    "dictation-listening-ime-open",
    "dictation-listening-ctrl-open-ime-open",
    "dictation-final-awaiting-stopped",
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


def self_test() -> int:
    stages = [
        {"stage": name, "runtimeGeometry": {"cols": cols, "rows": rows},
         "resizePending": 0, "resizeFailures": 0,
         "resizeStatus": f"{cols} × {rows} accepted by SSH"}
        for name, cols, rows in (
            ("dictation-ready-ime-open", 38, 6),
            ("dictation-listening-ime-open", 38, 6),
            ("dictation-listening-ctrl-open-ime-open", 38, 6),
            ("dictation-final-awaiting-stopped", 38, 6),
            ("dictation-final-inserted", 38, 6),
            ("dictation-post-stop-keyboard-input", 38, 6),
            ("dictation-error-ime-open", 38, 6),
            ("dictation-reattached-ime-open", 38, 6),
            ("dictation-background-cancel-resumed", 37, 24),
            ("dictation-post-resume-ime-open", 38, 6),
        )
    ]
    journey = {"geometryTrace": stages}
    cases = [
        ("keyboard and IME-hidden local grids match independent host samples", "6 38\n24 37\n" * 10, True),
        ("host PTY mismatch at IME-hidden resume is rejected", "6 38\n" * 10, False),
        ("unacknowledged local resume geometry is rejected", "6 38\n24 37\n" * 10, False),
    ]
    failed = False
    for index, (label, samples, should_pass) in enumerate(cases):
        case = json.loads(json.dumps(journey))
        if index == 2:
            next(stage for stage in case["geometryTrace"]
                 if stage["stage"] == "dictation-background-cancel-resumed")["resizePending"] = 1
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
