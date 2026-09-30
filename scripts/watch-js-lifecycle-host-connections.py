#!/usr/bin/env python3
"""Record independent Docker sshd TCP/22 state during the packaged journey."""

from __future__ import annotations

import argparse
import json
import signal
import subprocess
import sys
import time
from pathlib import Path


stopping = False


def stop(_signum: int, _frame: object) -> None:
    global stopping
    stopping = True


def connections(container: str) -> tuple[list[dict[str, str]], str | None]:
    try:
        result = subprocess.run(
            ["docker", "exec", "-u", "0", container, "cat", "/proc/net/tcp", "/proc/net/tcp6"],
            check=True,
            text=True,
            capture_output=True,
            timeout=4,
        )
    except (OSError, subprocess.SubprocessError) as error:
        return [], str(error)
    established: list[dict[str, str]] = []
    for line in result.stdout.splitlines()[1:]:
        fields = line.split()
        if len(fields) < 4 or fields[3] != "01":
            continue
        local = fields[1]
        remote = fields[2]
        if local.rsplit(":", 1)[-1].upper() != "0016":
            continue
        established.append({"family": "tcp6" if len(local.split(":", 1)[0]) == 32 else "tcp", "local": local, "remote": remote})
    return established, None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--interval", type=float, default=0.5)
    args = parser.parse_args()
    if args.interval < 0.1 or args.interval > 5:
        parser.error("--interval must be between 0.1 and 5 seconds")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    with args.output.open("w", encoding="utf-8", buffering=1) as output:
        while not stopping:
            sampled_epoch_ms = time.time_ns() // 1_000_000
            sampled_monotonic_ns = time.monotonic_ns()
            sockets, error = connections(args.container)
            output.write(json.dumps({
                "sampledEpochMs": sampled_epoch_ms,
                "sampledMonotonicNs": sampled_monotonic_ns,
                "establishedSshConnections": sockets,
                "count": len(sockets),
                "error": error,
            }, sort_keys=True) + "\n")
            time.sleep(args.interval)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
