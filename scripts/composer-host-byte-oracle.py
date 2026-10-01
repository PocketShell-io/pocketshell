#!/usr/bin/env python3
"""Capture Docker PTY bytes at a packaged composer journey checkpoint."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import socketserver
import subprocess
import sys
from pathlib import Path


SAFE_STAGE = re.compile(r"^(recording-insert|transcribing-send|stop-review)$")
SAFE_SESSION = re.compile(r"^[A-Za-z0-9-]{8,32}-bytes$")


def serve(args: argparse.Namespace) -> None:
    key = Path(args.ssh_key).resolve(strict=True)
    evidence_dir = Path(args.evidence_dir).resolve()
    evidence_dir.mkdir(parents=True, exist_ok=True)

    class Handler(socketserver.StreamRequestHandler):
        def handle(self) -> None:
            raw = self.rfile.readline(16_385)
            try:
                request = json.loads(raw)
                stage = request.get("stage")
                run_id = request.get("runId")
                session = request.get("session")
                forbidden = request.get("forbiddenMarker")
                if len(raw) > 16_384:
                    raise ValueError("request exceeds 16 KiB")
                if run_id != args.run_id or not isinstance(stage, str) or not SAFE_STAGE.fullmatch(stage):
                    raise ValueError("run identity or checkpoint is invalid")
                if session != args.session or not isinstance(session, str) or not SAFE_SESSION.fullmatch(session):
                    raise ValueError("session identity is invalid")
                if not isinstance(forbidden, str) or not forbidden or len(forbidden) > 512:
                    raise ValueError("forbidden marker is invalid")
                if any(character in forbidden for character in "\r\n\x00"):
                    raise ValueError("forbidden marker contains a control character")
                remote_command = f"a capture --workspace /home/testuser --tag '{session}' --bytes 4096"
                result = subprocess.run(
                    [
                        "ssh", "-q", "-i", str(key), "-p", str(args.ssh_port),
                        "-o", "BatchMode=yes", "-o", "ConnectTimeout=5",
                        "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                        "testuser@127.0.0.1", remote_command,
                    ],
                    check=False,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    timeout=10,
                )
                capture = result.stdout
                marker_present = forbidden.encode("utf-8") in capture
                report = {
                    "runId": args.run_id,
                    "stage": stage,
                    "session": session,
                    "source": "docker-host-a-capture",
                    "exitCode": result.returncode,
                    "hostBytesBase64": base64.b64encode(capture).decode("ascii"),
                    "hostBytesLength": len(capture),
                    "hostBytesSha256": hashlib.sha256(capture).hexdigest(),
                    "forbiddenMarker": forbidden,
                    "ptyWriteObserved": marker_present,
                    "noPtyWriteBeforeExplicitAction": result.returncode == 0 and not marker_present,
                }
                if result.returncode != 0:
                    report["error"] = result.stderr.decode("utf-8", errors="replace")[-2_000:]
                report_path = evidence_dir / f"composer-host-oracle-pre-{stage}.json"
                report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
                self.wfile.write((json.dumps(report, ensure_ascii=False) + "\n").encode("utf-8"))
            except (ValueError, TypeError, json.JSONDecodeError, subprocess.TimeoutExpired, OSError) as error:
                failure = {"runId": args.run_id, "ok": False, "error": str(error)}
                self.wfile.write((json.dumps(failure) + "\n").encode("utf-8"))

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    with Server((args.listen, args.port), Handler) as server:
        args.port_file.write_text(f"{server.server_address[1]}\n", encoding="ascii")
        server.serve_forever(poll_interval=0.2)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listen", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--port-file", type=Path, required=True)
    parser.add_argument("--ssh-key", required=True)
    parser.add_argument("--ssh-port", type=int, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--session", required=True)
    parser.add_argument("--evidence-dir", required=True)
    args = parser.parse_args()
    if not 1 <= args.ssh_port <= 65_535 or not 0 <= args.port <= 65_535:
        parser.error("port is out of range")
    if not SAFE_SESSION.fullmatch(args.session) or not SAFE_STAGE.fullmatch("recording-insert"):
        parser.error("session identity is malformed")
    try:
        serve(args)
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
