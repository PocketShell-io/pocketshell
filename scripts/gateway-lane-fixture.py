#!/usr/bin/env python3
"""Host-side controller and oracle for the Android gateway emulator lane.

pocketshell#3086 slice 3 (scripts/connected-js-gateway-docker.sh). The
packaged journey (GatewayDockerJourneyTest) reaches this controller from the
emulator at 10.0.2.2:<port> (the host's loopback; the controller binds
127.0.0.1 only) with one JSON request line per TCP connection and
gets one JSON reply line. Every operation acts on the run's own Docker
compose project and every answer comes from Docker or the real gateway, never
from the app, so the journey's host-side assertions have an independent
oracle. Each request and reply is appended to a JSON-lines journal the lane's
result checker re-reads.

Operations:
  hello          the run's device id, gateway origin, the pinned host-key
                 line (from the enrolled agent's own `show`), a wrong pin, an
                 unenrolled device id and the SSH user
  authorize      append the phone's public key line to the host user's
                 authorized_keys (what the user does on the host); returns its
                 SHA256 fingerprint
  mark           a position in the host sshd log
  sshd-since     what sshd logged after a mark: bridged connections, every
                 userauth line, and the fingerprints it accepted
  agent-stop     stop the host agent and wait until the gateway reports the
                 device offline (its device list's presence)
  agent-start    start it and wait until the gateway reports it online
  drop           restart the TLS front: every phone tunnel drops at once
  host-file      read ~/gwlane-* from the host user's home
  presence       the gateway's device list (the run's account)

Usage:
  scripts/gateway-lane-fixture.py serve --project P --compose-file F \
      --state DIR --journal FILE --port-file FILE --hello JSON
  scripts/gateway-lane-fixture.py pinned-key --show-output FILE
  scripts/gateway-lane-fixture.py --self-test
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import socket
import socketserver
import ssl
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

PUBLIC_KEY_LINE = re.compile(r"^(ssh-ed25519|ecdsa-sha2-nistp256|ssh-rsa) ([A-Za-z0-9+/]+={0,2})(?: [ -~]{0,128})?$")
HOST_FILE = re.compile(r"^gwlane-[A-Za-z0-9_-]{1,80}\.txt$")
CONNECTION = re.compile(r"^Connection from 127\.0\.0\.1 port \d+ on 127\.0\.0\.1 port 22\b")
USERAUTH = re.compile(
    r"Accepted |Failed |Postponed |authenticating user|Invalid user|userauth|Authentication refused|"
    r"Disconnected from user|maximum authentication attempts"
)
ACCEPTED = re.compile(r"^Accepted publickey for (\S+) from 127\.0\.0\.1 port \d+ ssh2: (\S+) (SHA256:[A-Za-z0-9+/]{43})$")


def fingerprint(line: str) -> str:
    match = PUBLIC_KEY_LINE.match(line.strip())
    if match is None:
        raise ValueError("not one OpenSSH public key line")
    blob = base64.b64decode(match.group(2), validate=True)
    return "SHA256:" + base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")


def pinned_key(show_output: str) -> tuple[str, str]:
    """The `pinned ssh host key:` line and device id, as `pocketshell gateway show --host-key` reads them."""
    fields = {}
    for line in show_output.splitlines():
        name, sep, value = line.partition(":")
        if sep:
            fields[name.strip()] = value.strip()
    key = fields.get("pinned ssh host key", "")
    parts = key.split()
    if len(parts) != 2 or PUBLIC_KEY_LINE.match(key) is None:
        raise ValueError("the enrolled state has no usable pinned host key")
    return key, fields.get("device id", "")


def summarize_sshd(lines: list[str]) -> dict:
    accepted = []
    for line in lines:
        match = ACCEPTED.match(line.strip())
        if match:
            accepted.append({"user": match.group(1), "fingerprint": match.group(3)})
    return {
        "connections": sum(1 for line in lines if CONNECTION.match(line.strip())),
        "userauth": [line.strip() for line in lines if USERAUTH.search(line)],
        "accepted": accepted,
    }


class Fixture:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.hello = json.loads(args.hello)
        self.lock = threading.Lock()
        self.journal = Path(args.journal)

    def compose(self, *command: str, timeout: float = 120) -> str:
        result = subprocess.run(
            ["docker", "compose", "-f", self.args.compose_file, "-p", self.args.project, *command],
            check=False, capture_output=True, text=True, timeout=timeout,
        )
        if result.returncode != 0:
            raise RuntimeError(f"docker compose {' '.join(command)} exited {result.returncode}: {result.stderr.strip()[-400:]}")
        return result.stdout

    def host_exec(self, script: str, stdin: str | None = None) -> str:
        command = ["exec", "-T", "host", "su", "testuser", "-s", "/bin/sh", "-c", "HOME=/home/testuser; cd; " + script]
        result = subprocess.run(
            ["docker", "compose", "-f", self.args.compose_file, "-p", self.args.project, *command],
            check=False, capture_output=True, text=True, timeout=60, input=stdin,
        )
        if result.returncode != 0:
            raise RuntimeError(f"host command exited {result.returncode}: {result.stderr.strip()[-400:]}")
        return result.stdout

    def sshd_lines(self) -> list[str]:
        return self.compose("logs", "--no-color", "--no-log-prefix", "host").splitlines()

    def devices(self) -> list[dict]:
        token = subprocess.run(
            [str(Path(self.args.state) / "bin" / "gwfixture"), "mint", "-key", str(Path(self.args.state) / "broker" / "broker-key.pem"),
             "-iss", self.hello["issuer"], "-sub", self.hello["sub"], "-email", self.hello["email"], "-ttl", "2m"],
            check=True, capture_output=True, text=True, timeout=20,
        ).stdout.strip()
        context = ssl.create_default_context(cafile=str(Path(self.args.state) / "tls" / "ca.pem"))
        request = urllib.request.Request(
            f"https://localhost:{self.hello['tlsPort']}/identity/v1/devices",
            headers={"Authorization": f"Bearer {token}"},
        )
        with urllib.request.urlopen(request, context=context, timeout=10) as response:
            return json.loads(response.read())["devices"]

    def online(self) -> bool | None:
        for device in self.devices():
            if device.get("id") == self.hello["deviceId"]:
                return bool((device.get("presence") or {}).get("online"))
        return None

    def await_online(self, wanted: bool, timeout: float = 90) -> bool:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.online() is wanted:
                return True
            time.sleep(0.5)
        raise RuntimeError(f"the gateway never reported the device {'online' if wanted else 'offline'}")

    def handle(self, request: dict) -> dict:
        op = request.get("op")
        if op == "hello":
            return dict(self.hello)
        if op == "authorize":
            line = str(request.get("publicKey", "")).strip()
            fp = fingerprint(line)
            self.host_exec("umask 077; mkdir -p .ssh; cat >> .ssh/authorized_keys", stdin=line + "\n")
            installed = self.host_exec("cat .ssh/authorized_keys").splitlines().count(line)
            return {"fingerprint": fp, "installedCopies": installed}
        if op == "mark":
            return {"mark": len(self.sshd_lines())}
        if op == "sshd-since":
            mark = int(request["mark"])
            return summarize_sshd(self.sshd_lines()[mark:])
        if op == "agent-stop":
            self.compose("stop", "agent")
            self.await_online(False)
            return {"online": False}
        if op == "agent-start":
            self.compose("start", "agent")
            self.await_online(True)
            return {"online": True}
        if op == "drop":
            self.compose("restart", "tlsfront")
            return {"dropped": True}
        if op == "host-file":
            name = str(request.get("name", ""))
            if HOST_FILE.match(name) is None:
                raise ValueError("host-file reads ~/gwlane-*.txt only")
            return {"name": name, "content": self.host_exec(f"cat {name} 2>/dev/null || true")}
        if op == "presence":
            return {"online": self.online()}
        raise ValueError(f"unknown op {op!r}")

    def record(self, request: dict, reply: dict) -> None:
        with self.lock, self.journal.open("a", encoding="utf-8") as journal:
            journal.write(json.dumps({"at": time.time(), "request": request, "reply": reply}) + "\n")


def serve(args: argparse.Namespace) -> int:
    fixture = Fixture(args)

    class Handler(socketserver.StreamRequestHandler):
        def handle(self) -> None:
            raw = self.rfile.readline(65536)
            try:
                request = json.loads(raw)
                if not isinstance(request, dict):
                    raise ValueError("one JSON object per request")
                reply = {"ok": True, **fixture.handle(request)}
            except Exception as error:  # noqa: BLE001 - every failure goes back to the journey
                request = {"raw": raw.decode("utf-8", "replace")[:200]} if "request" not in locals() else request
                reply = {"ok": False, "error": str(error)[:400]}
            fixture.record(request, reply)
            self.wfile.write((json.dumps(reply) + "\n").encode())

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    # Loopback only: the emulator reaches the host's loopback as 10.0.2.2.
    with Server(("127.0.0.1", 0), Handler) as server:
        Path(args.port_file).write_text(str(server.server_address[1]))
        server.serve_forever()
    return 0


def self_test() -> int:
    line = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINV1em8xlnD0YdhVrl9boi0uLwrfiqee7NBQdXL/8jT6"
    assert fingerprint(line) == "SHA256:181Z5KJWXLKQbUssp7QcJ6WgVTR6pXkk0cD96lz7pF4", fingerprint(line)
    assert fingerprint(line + " phone key") == fingerprint(line)
    for bad in ("", "ssh-ed25519", "ssh-dss AAAA", line + "\nssh-ed25519 AAAA", "ssh-ed25519 !!!"):
        try:
            fingerprint(bad)
        except (ValueError, Exception):
            continue
        raise AssertionError(f"accepted {bad!r}")
    show = "server: ws://gateway:8080\ndevice id:       dev-1\npinned ssh host key: " + line + "\n"
    assert pinned_key(show) == (line, "dev-1")
    try:
        pinned_key("device id: dev-1\n")
        raise AssertionError("a show without a pin must refuse")
    except ValueError:
        pass
    log = [
        'Connection from 127.0.0.1 port 42039 on 127.0.0.1 port 22 rdomain ""',
        "Connection closed by 127.0.0.1 port 42039 [preauth]",
        'Connection from 127.0.0.1 port 42040 on 127.0.0.1 port 22 rdomain ""',
        "Postponed publickey for testuser from 127.0.0.1 port 42040 ssh2 [preauth]",
        "Accepted publickey for testuser from 127.0.0.1 port 42040 ssh2: ED25519 SHA256:181Z5KJWXLKQbUssp7QcJ6WgVTR6pXkk0cD96lz7pF4",
    ]
    mismatch = summarize_sshd(log[:2])
    assert mismatch == {"connections": 1, "userauth": [], "accepted": []}, mismatch
    accepted = summarize_sshd(log)
    assert accepted["connections"] == 2 and len(accepted["userauth"]) == 2, accepted
    assert accepted["accepted"] == [{"user": "testuser", "fingerprint": "SHA256:181Z5KJWXLKQbUssp7QcJ6WgVTR6pXkk0cD96lz7pF4"}]
    for name, ok in (("gwlane-io-abc.txt", True), ("../etc/passwd", False), ("gwlane-x.txt;id", False), (".ssh/authorized_keys", False)):
        assert (HOST_FILE.match(name) is not None) == ok, name
    print("PASS: gateway lane fixture self-test")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true")
    sub = parser.add_subparsers(dest="command")
    run = sub.add_parser("serve")
    run.add_argument("--project", required=True)
    run.add_argument("--compose-file", required=True)
    run.add_argument("--state", required=True)
    run.add_argument("--journal", required=True)
    run.add_argument("--port-file", required=True)
    run.add_argument("--hello", required=True)
    pin = sub.add_parser("pinned-key")
    pin.add_argument("--show-output", required=True)
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.command == "serve":
        return serve(args)
    if args.command == "pinned-key":
        key, device = pinned_key(Path(args.show_output).read_text())
        print(json.dumps({"hostKeyLine": key, "deviceId": device, "fingerprint": fingerprint(key)}))
        return 0
    parser.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
