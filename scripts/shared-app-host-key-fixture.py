#!/usr/bin/env python3
"""Host-key oracle and rotation controller for the shared-app trust journey (#2953).

The shared-app journey checks the host key the app SHOWS against the key the
SSH fixture really presents, computed here on the Docker host with
ssh-keyscan/ssh-keygen (never by the app under test), and it needs a server
whose key really changes mid-journey. The `sshd-rekeyed` fixture mints new host
keys on every container start, so a `docker restart` is a real rotation.

  keys  --port P            print "keyType SHA256:fp;..." for 127.0.0.1:P
  serve --ssh-port P --container NAME --port-file FILE
                            listen on 127.0.0.1 (the emulator's 10.0.2.2); each
                            "rotate" line restarts NAME, waits for new keys on
                            P, and answers "ok keyType SHA256:fp;..." (or
                            "error <why>"). Writes the chosen port to FILE.
  --self-test               check the ssh-keyscan/ssh-keygen parsing offline
"""

from __future__ import annotations

import argparse
import socket
import subprocess
import sys
import time
from pathlib import Path

KEYSCAN_TIMEOUT_S = 5
ROTATION_DEADLINE_S = 90


def parse_keyscan(lines: str) -> list[tuple[str, str]]:
    """(keyType, "host keyType base64") for each ssh-keyscan key line."""
    keys = []
    for line in lines.splitlines():
        parts = line.split()
        if len(parts) >= 3 and not line.startswith("#"):
            keys.append((parts[1], line.strip()))
    return keys


def parse_fingerprint(ssh_keygen_output: str) -> str:
    """The SHA256:... field of `ssh-keygen -lf -` ("256 SHA256:x host (ED25519)")."""
    for field in ssh_keygen_output.split():
        if field.startswith("SHA256:"):
            return field
    raise ValueError(f"no SHA256 fingerprint in: {ssh_keygen_output!r}")


def host_keys(port: int, host: str = "127.0.0.1") -> list[str]:
    scan = subprocess.run(
        ["ssh-keyscan", "-T", str(KEYSCAN_TIMEOUT_S), "-p", str(port), host],
        capture_output=True, text=True, check=False,
    )
    entries = []
    for key_type, line in parse_keyscan(scan.stdout):
        fingerprint = subprocess.run(
            ["ssh-keygen", "-E", "sha256", "-lf", "-"], input=line,
            capture_output=True, text=True, check=True,
        ).stdout
        entries.append(f"{key_type} {parse_fingerprint(fingerprint)}")
    return sorted(set(entries))


def rotate(container: str, ssh_port: int) -> str:
    before = set(host_keys(ssh_port))
    restarted = subprocess.run(["docker", "restart", container], capture_output=True, text=True, check=False)
    if restarted.returncode != 0:
        return f"error docker restart {container} failed: {restarted.stderr.strip()}"
    deadline = time.monotonic() + ROTATION_DEADLINE_S
    while time.monotonic() < deadline:
        after = host_keys(ssh_port)
        if after and not (set(after) & before):
            return "ok " + ";".join(after)
        time.sleep(1)
    return f"error {container} did not present new host keys within {ROTATION_DEADLINE_S}s"


def serve(ssh_port: int, container: str, port_file: Path) -> int:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 0))
    server.listen(4)
    port_file.write_text(f"{server.getsockname()[1]}\n", encoding="utf-8")
    print(f"rotation controller for {container} (ssh {ssh_port}) on 127.0.0.1:{server.getsockname()[1]}", flush=True)
    while True:
        connection, _address = server.accept()
        with connection:
            connection.settimeout(30)
            try:
                request = connection.makefile("r", encoding="utf-8").readline().strip()
            except OSError:
                continue
            answer = rotate(container, ssh_port) if request == "rotate" else f"error unknown request {request!r}"
            print(f"{request} -> {answer}", flush=True)
            try:
                connection.sendall((answer + "\n").encode("utf-8"))
            except OSError:
                pass


def self_test() -> int:
    scan = (
        "# 127.0.0.1:2246 SSH-2.0-OpenSSH_9.9\n"
        "[127.0.0.1]:2246 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample\n"
        "[127.0.0.1]:2246 ecdsa-sha2-nistp256 AAAAE2VjZHNhExample\n"
    )
    checks = [
        ("keyscan comments are ignored", [k for k, _ in parse_keyscan(scan)] == ["ssh-ed25519", "ecdsa-sha2-nistp256"]),
        ("fingerprint field is extracted",
         parse_fingerprint("256 SHA256:q0Yx1S9hN1Yy4oX1fqmE0Q [127.0.0.1]:2246 (ED25519)\n") == "SHA256:q0Yx1S9hN1Yy4oX1fqmE0Q"),
    ]
    try:
        parse_fingerprint("no fingerprint here")
        checks.append(("output without a fingerprint is refused", False))
    except ValueError:
        checks.append(("output without a fingerprint is refused", True))
    failures = [label for label, ok in checks if not ok]
    for label, ok in checks:
        print(f"{'ok' if ok else 'FAIL'}: {label}")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="command")
    keys = sub.add_parser("keys")
    keys.add_argument("--port", type=int, required=True)
    srv = sub.add_parser("serve")
    srv.add_argument("--ssh-port", type=int, required=True)
    srv.add_argument("--container", required=True)
    srv.add_argument("--port-file", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.command == "keys":
        entries = host_keys(args.port)
        if not entries:
            print(f"FAIL: no SSH host keys answered on 127.0.0.1:{args.port}", file=sys.stderr)
            return 1
        print(";".join(entries))
        return 0
    if args.command == "serve":
        return serve(args.ssh_port, args.container, args.port_file)
    parser.print_help()
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
