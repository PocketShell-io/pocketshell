#!/usr/bin/python3 -I
"""Verify J20's uploaded payloads from the host, then delete and verify cleanup."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import sys
from pathlib import Path
from typing import Any


REMOTE_ATTACHMENTS = "/home/testuser/.pocketshell/attachments"
PAYLOAD_BYTES = 512 * 1024
POOL_PORTS = {2243, 2244, 2245}
HOST_PAYLOAD = re.compile(
    r"HOST_PAYLOAD\|(?P<run_id>[A-Za-z0-9_-]+)\|(?P<path>[^|]+)"
    r"\|bytes=(?P<size>[0-9]+)\|sha256=(?P<sha>[a-f0-9]{64})"
)


class OracleFailure(ValueError):
    """The run log, host payloads, or cleanup did not match the J20 contract."""


def expected_payload_sha256(file_index: int) -> str:
    payload = bytes((offset + file_index * 37) % 251 for offset in range(PAYLOAD_BYTES))
    return hashlib.sha256(payload).hexdigest()


def parse_host_payloads(log_text: str, run_id: str) -> list[dict[str, Any]]:
    records = [match.groupdict() for match in HOST_PAYLOAD.finditer(log_text) if match.group("run_id") == run_id]
    if len(records) != 3:
        raise OracleFailure(f"expected three run-scoped HOST_PAYLOAD records, found {len(records)}")

    payload_stem = f"j20p-{run_id}"
    expected_names = {f"{payload_stem}-{index}.bin": index - 1 for index in range(1, 4)}
    paths: set[str] = set()
    names: set[str] = set()
    normalized: list[dict[str, Any]] = []
    for record in records:
        path = record["path"]
        if not path.startswith(REMOTE_ATTACHMENTS + "/") or not re.fullmatch(r"[A-Za-z0-9._/-]+", path):
            raise OracleFailure(f"host payload path is outside the production attachment directory: {path!r}")
        if path in paths:
            raise OracleFailure(f"duplicate staged payload path: {path}")
        paths.add(path)
        name = Path(path).name
        matches = [candidate for candidate in expected_names if name.endswith(candidate)]
        if len(matches) != 1:
            raise OracleFailure(f"staged payload path does not identify one source file: {path}")
        source_name = matches[0]
        if source_name in names:
            raise OracleFailure(f"source payload appears more than once in the staged set: {source_name}")
        names.add(source_name)
        file_index = expected_names[source_name]
        if int(record["size"]) != PAYLOAD_BYTES:
            raise OracleFailure(f"instrumentation host check reports {record['size']} bytes for {source_name}")
        expected_sha = expected_payload_sha256(file_index)
        if record["sha"] != expected_sha:
            raise OracleFailure(f"instrumentation host check reports the wrong source hash for {source_name}")
        normalized.append({
            "path": path,
            "sourceName": source_name,
            "bytes": PAYLOAD_BYTES,
            "expectedSha256": expected_sha,
            "instrumentationObservedSha256": record["sha"],
        })
    if names != set(expected_names):
        raise OracleFailure(f"host payload set is incomplete: {sorted(names)}")
    return sorted(normalized, key=lambda record: record["sourceName"])


def ssh_command(port: int, key: Path, remote_command: str) -> str:
    completed = subprocess.run(
        [
            "ssh", "-q", "-i", str(key), "-p", str(port),
            "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
            "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
            "testuser@127.0.0.1", remote_command,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise OracleFailure(
            f"host SSH command failed with {completed.returncode}: {completed.stderr.strip()}"
        )
    return completed.stdout


def read_payloads(port: int, key: Path, records: list[dict[str, Any]]) -> list[dict[str, Any]]:
    commands = []
    for record in records:
        quoted = shlex.quote(record["path"])
        commands.append(
            f"printf '%s\\n' {quoted}; stat -c '%s' -- {quoted}; "
            f"sha256sum -- {quoted} | awk '{{print $1}}'"
        )
    lines = ssh_command(port, key, "set -eu; " + "; ".join(commands)).splitlines()
    if len(lines) != 9:
        raise OracleFailure(f"host SSH oracle returned {len(lines)} fields instead of 9")
    verified: list[dict[str, Any]] = []
    for index, record in enumerate(records):
        path, size, sha = lines[index * 3:index * 3 + 3]
        if path != record["path"]:
            raise OracleFailure(f"host SSH oracle returned the wrong path: {path!r}")
        if int(size) != PAYLOAD_BYTES:
            raise OracleFailure(f"host SSH oracle found {size} bytes at {path}")
        if sha != record["expectedSha256"]:
            raise OracleFailure(f"host SSH oracle found the wrong payload hash at {path}")
        verified.append({**record, "hostObservedBytes": int(size), "hostObservedSha256": sha})
    return verified


def remove_payloads_and_verify(port: int, key: Path, run_id: str) -> list[str]:
    pattern = shlex.quote(f"*j20p-{run_id}*")
    directory = shlex.quote(REMOTE_ATTACHMENTS)
    ssh_command(port, key, f"if [ -d {directory} ]; then find {directory} -type f -name {pattern} -delete; fi")
    remaining = ssh_command(
        port,
        key,
        f"if [ -d {directory} ]; then find {directory} -type f -name {pattern} -print; fi",
    )
    return [path for path in remaining.splitlines() if path]


def verify(port: int, key: Path, run_id: str, logcat: Path) -> dict[str, Any]:
    if port not in POOL_PORTS:
        raise OracleFailure(f"direct agents port must be one of {sorted(POOL_PORTS)}, got {port}")
    if not re.fullmatch(r"[A-Za-z0-9_-]{4,48}", run_id):
        raise OracleFailure("artifact run id must be 4-48 safe characters")
    if not key.is_file():
        raise OracleFailure(f"fixture key is missing: {key}")

    records: list[dict[str, Any]] = []
    verified: list[dict[str, Any]] = []
    blockers: list[str] = []
    try:
        records = parse_host_payloads(logcat.read_text(encoding="utf-8", errors="replace"), run_id)
        verified = read_payloads(port, key, records)
    except (OSError, OracleFailure, ValueError) as error:
        blockers.append(str(error))

    try:
        remaining = remove_payloads_and_verify(port, key, run_id)
        if remaining:
            blockers.append("run-scoped files remain after host cleanup: " + ", ".join(remaining))
    except OracleFailure as error:
        remaining = []
        blockers.append(f"host cleanup or postcondition failed: {error}")

    return {
        "schema": 1,
        "runId": run_id,
        "directAgentsPort": port,
        "expectedFileCount": 3,
        "verifiedFileCount": len(verified),
        "payloads": verified,
        "cleanupVerified": not remaining and not any("cleanup" in blocker or "remain" in blocker for blocker in blockers),
        "remainingRunScopedPaths": remaining,
        "result": "PASS" if not blockers and len(verified) == 3 else "BLOCK",
        "blockers": blockers,
    }


def self_test() -> int:
    run_id = "i2929-test-123"
    stem = f"j20p-{run_id}"
    lines = []
    for file_index in range(3):
        name = f"{stem}-{file_index + 1}.bin"
        path = f"{REMOTE_ATTACHMENTS}/20260930-{file_index}-{name}"
        digest = expected_payload_sha256(file_index)
        lines.append(f"I J20ComposerUploadProgress: HOST_PAYLOAD|{run_id}|{path}|bytes={PAYLOAD_BYTES}|sha256={digest}")
    good_log = "\n".join(lines)
    probes = [
        ("three exact source paths and payload hashes parse", good_log, True),
        ("missing source blocks", "\n".join(lines[:2]), False),
        ("wrong run id is ignored", good_log.replace(run_id, "another-run"), False),
        ("wrong payload size blocks", good_log.replace(f"bytes={PAYLOAD_BYTES}", "bytes=12", 1), False),
        ("wrong payload hash blocks", good_log.replace(expected_payload_sha256(0), "0" * 64, 1), False),
        ("duplicate file blocks", "\n".join(lines[:2] + [lines[0]]), False),
    ]
    failures = 0
    for label, text, expected in probes:
        try:
            parse_host_payloads(text, run_id)
            passed = True
        except OracleFailure:
            passed = False
        if passed != expected:
            failures += 1
            print(f"FAIL: host oracle self-test: {label}", file=sys.stderr)
        else:
            print(f"PASS: host oracle self-test: {label}")
    print(f"{'FAIL' if failures else 'PASS'}: host oracle parser self-test ({len(probes) - failures}/{len(probes)})")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=2245, help="direct agents pool host port")
    parser.add_argument("--key", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--logcat", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    missing = [name for name in ("key", "run_id", "logcat", "output") if getattr(args, name) is None]
    if missing:
        parser.error("these arguments are required outside --self-test: " + ", ".join("--" + name.replace("_", "-") for name in missing))
    report = verify(args.port, args.key, args.run_id, args.logcat)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{report['result']}: independent host payload/cleanup oracle ({report['verifiedFileCount']}/3 payloads)")
    for blocker in report["blockers"]:
        print(f"  BLOCK: {blocker}", file=sys.stderr)
    return 0 if report["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
