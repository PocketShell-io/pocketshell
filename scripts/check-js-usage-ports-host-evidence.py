#!/usr/bin/python3 -I
"""Check same-run Android artifacts against independent Docker host state."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


SCREENSHOTS = (
    "provider-usage.png",
    "ports-auto-forward.png",
    "ports-manual-forward.png",
)
RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$")
PORT_RE = re.compile(r"(?:\]|:)(\d+)$")


class EvidenceFailure(ValueError):
    pass


def _run(command: list[str]) -> str:
    try:
        result = subprocess.run(command, check=True, text=True, capture_output=True, timeout=30)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        detail = getattr(error, "stderr", "")
        raise EvidenceFailure(f"Docker host evidence command failed: {' '.join(command)}: {detail or error}") from error
    return result.stdout


def _read_summary(artifact_directory: Path, run_id: str) -> dict[str, Any]:
    summary_path = artifact_directory / "journey-summary.json"
    try:
        summary = json.loads(summary_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceFailure(f"could not read packaged usage/ports summary: {error}") from error
    if not isinstance(summary, dict) or summary.get("schema") != 1 or summary.get("runId") != run_id:
        raise EvidenceFailure("packaged summary has the wrong schema or run ID")
    return summary


def _verify_artifacts(artifact_directory: Path, summary: dict[str, Any], run_id: str) -> None:
    if summary.get("screenshots") != list(SCREENSHOTS):
        raise EvidenceFailure(f"summary must name exactly these same-run screenshots: {list(SCREENSHOTS)}")
    for name in SCREENSHOTS:
        image_path = artifact_directory / name
        checksum_path = artifact_directory / f"{name}.sha256"
        try:
            image = image_path.read_bytes()
            recorded_hash = checksum_path.read_text(encoding="utf-8").strip()
        except OSError as error:
            raise EvidenceFailure(f"missing same-run Android screenshot artifact {name}: {error}") from error
        if len(image) < 24 or image[:8] != b"\x89PNG\r\n\x1a\n" or image[12:16] != b"IHDR":
            raise EvidenceFailure(f"{name} is not a PNG with an IHDR header")
        width, height = struct.unpack(">II", image[16:24])
        if width < 200 or height < 300:
            raise EvidenceFailure(f"{name} is too small to be a real device screenshot ({width}x{height})")
        digest = hashlib.sha256(image).hexdigest()
        if not re.fullmatch(r"[0-9a-f]{64}", recorded_hash) or digest != recorded_hash:
            raise EvidenceFailure(f"{name} SHA-256 does not match its same-run manifest")

    expected_started = marker(run_id, "HS")
    expected_stopped = marker(run_id, "HE")
    if summary.get("httpStartedMarker") != expected_started or summary.get("httpStoppedMarker") != expected_stopped:
        raise EvidenceFailure("host listener markers do not bind to this run ID")
    remote_port = summary.get("httpRemotePort")
    if not isinstance(remote_port, int) or isinstance(remote_port, bool) or not 1024 <= remote_port <= 10_000:
        raise EvidenceFailure("the auto-forwarded Docker listener is outside the shared interesting-port range")
    stop_check_path = artifact_directory / "host-http-stop-process-check.txt"
    try:
        stop_check = dict(
            line.split("=", 1)
            for line in stop_check_path.read_text(encoding="utf-8").splitlines()
            if "=" in line
        )
    except OSError as error:
        raise EvidenceFailure(f"missing normalized same-run HTTP stop process check: {error}") from error
    expected_process = f"python3 -m http.server {remote_port} --bind 127.0.0.1"
    if (not stop_check.get("pid", "").isdigit() or
            expected_process not in stop_check.get("cmdline", "") or
            stop_check.get("decision") != "matched-kill" or
            stop_check.get("exitDecision") != "process-exited"):
        raise EvidenceFailure(f"HTTP stop did not prove the expected process identity and exit: {stop_check}")
    out_of_range_port = summary.get("outOfRangeRemotePort")
    if (not isinstance(out_of_range_port, int) or isinstance(out_of_range_port, bool) or
            not 10_000 < out_of_range_port <= 65_535 or
            summary.get("outOfRangeListenerForwarded") is not False):
        raise EvidenceFailure("the discovered out-of-range listener was not proven excluded from auto-forwarding")
    if not isinstance(summary.get("httpStatus"), str) or not summary["httpStatus"].startswith(("HTTP/1.0 200", "HTTP/1.1 200")):
        raise EvidenceFailure("the Android loopback tunnel did not report the Docker HTTP 200 response")
    for key in ("autoLocalPort", "manualLocalPort", "reconnectedManualLocalPort"):
        value = summary.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= 65_535:
            raise EvidenceFailure(f"summary is missing a valid local tunnel endpoint: {key}")
    if (summary.get("firstMissingScanRetained") is not True or
            summary.get("autoListenerAfterFirstMissingScan") is not True or
            summary.get("secondMissingScanClosed") is not True or
            summary.get("autoListenerAfterSecondMissingScan") is not False):
        raise EvidenceFailure("the bounded two-successful-scan cleanup was not proven")
    manual_banner = summary.get("manualSshBanner")
    if summary.get("manualRemotePort") != 22 or not isinstance(manual_banner, str) or not manual_banner.startswith("SSH-2.0-"):
        raise EvidenceFailure("manual low-port forwarding did not reach fixture sshd")
    reconnected_banner = summary.get("reconnectedSshBanner")
    if not isinstance(reconnected_banner, str) or not reconnected_banner.startswith("SSH-2.0-"):
        raise EvidenceFailure("manual desired tunnel did not reopen after reconnect")
    if (summary.get("nativeForwardCountAfterDisconnect") != 0 or
            summary.get("nativeForwardCountAfterFinalDisconnect") != 0 or
            summary.get("manualListenerAfterDisconnect") is not False or
            summary.get("reconnectedListenerAfterDisconnect") is not False):
        raise EvidenceFailure("native bridge still reports forwards after disconnect")
    connection_ids = (summary.get("initialConnectionId"), summary.get("reconnectedConnectionId"))
    generations = (summary.get("initialGenerationId"), summary.get("reconnectedGenerationId"))
    if any(not isinstance(value, str) or not value for value in connection_ids + generations):
        raise EvidenceFailure("summary is missing transport IDs for the reconnect proof")
    if connection_ids[0] == connection_ids[1] or generations[0] == generations[1]:
        raise EvidenceFailure("manual tunnel reconnect did not use a new SSH connection and generation")
    providers = summary.get("usageProviders")
    if not isinstance(providers, list) or len(providers) != 5:
        raise EvidenceFailure("summary is missing rendered provider quota states")
    provider_states = {
        row.get("provider"): row.get("state")
        for row in providers
        if isinstance(row, dict)
    }
    expected_states = {
        "claude": "blocked",
        "codex": "ok",
        "copilot": "approaching",
        "fixture-critical": "critical",
        "fixture-error": "error",
    }
    if (len(provider_states) != 5 or
            any(provider_states.get(provider) != state for provider, state in expected_states.items())):
        raise EvidenceFailure(f"rendered quota/error state evidence is incomplete: {provider_states}")


def marker(run_id: str, suffix: str) -> str:
    token = hashlib.sha256(run_id.encode("utf-8")).hexdigest()[:10].upper()
    return f"REMOTE_OUTPUT_{token}_{suffix}"


def _host_snapshot(container: str, session_tag: str, session_id: str) -> dict[str, Any]:
    raw = _run([
        "docker", "exec", "-u", "testuser", "-e", "HOME=/home/testuser", container,
        "/usr/bin/a", "snapshot", "--json",
    ])
    try:
        rows = json.loads(raw)
    except json.JSONDecodeError as error:
        raise EvidenceFailure(f"independent a snapshot was malformed: {error}") from error
    if not isinstance(rows, list):
        raise EvidenceFailure("independent a snapshot did not return a row array")
    matches = [
        row for row in rows
        if isinstance(row, dict) and row.get("tag") == session_tag and row.get("id") == session_id
    ]
    if len(matches) != 1:
        raise EvidenceFailure(f"independent host snapshot must contain one live {session_tag} row; found {len(matches)}")
    row = matches[0]
    if row.get("state") != "running" or row.get("worker_alive") is not True:
        raise EvidenceFailure("the packaged journey host session is not independently live")
    return row


def _host_capture(container: str, session_id: str, started: str, stopped: str) -> str:
    raw = _run([
        "docker", "exec", "-u", "testuser", "-e", "HOME=/home/testuser", container,
        "/usr/bin/a", "capture", "--bytes", "65536", session_id,
    ])
    normalized = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", raw)
    lines = {" ".join(line.replace("\u00a0", " ").split()) for line in normalized.replace("\r", "\n").split("\n")}
    if started not in lines or stopped not in lines:
        raise EvidenceFailure("independent a history capture is missing the host listener start/stop markers")
    return raw


def _verify_service_stopped(container: str, artifact_directory: Path, run_id: str, port: int) -> dict[str, str]:
    pid_path = f"/tmp/pocketshell-{run_id}-usage-ports.pid"
    command = (
        'pid=$(cat "$1" 2>/dev/null || true); '
        'case "$pid" in ""|*[!0-9]*) echo no-pid; exit 0;; esac; '
        'if [ -r "/proc/$pid/cmdline" ]; then '
        'args=$(tr "\\000" " " < "/proc/$pid/cmdline" 2>/dev/null || true); '
        'case "$args" in *"python3 -m http.server $2"*) echo listener-still-running; exit 1;; esac; '
        'fi; echo process-stopped'
    )
    process_state = _run([
        "docker", "exec", "-u", "testuser", container, "/bin/sh", "-c", command,
        "usage-ports-oracle", pid_path, str(port),
    ]).strip()
    listener_output = _run(["docker", "exec", container, "/bin/sh", "-c", "ss -tlnH 2>/dev/null || true"])
    open_ports: set[int] = set()
    for line in listener_output.splitlines():
        for field in line.split():
            match = PORT_RE.search(field)
            if match:
                open_ports.add(int(match.group(1)))
    if port in open_ports:
        raise EvidenceFailure(f"Docker fixture still has a listening socket on stopped test port {port}")
    (artifact_directory / "host-usage-ports-listeners-after.txt").write_text(listener_output, encoding="utf-8")
    return {
        "pidState": process_state,
        "processCheckFile": "host-http-stop-process-check.txt",
        "listenersAfterFile": "host-usage-ports-listeners-after.txt",
    }


def validate(container: str, artifact_directory: Path, run_id: str) -> dict[str, Any]:
    if not RUN_ID_RE.fullmatch(run_id):
        raise EvidenceFailure("run ID has an invalid tag-safe format")
    summary = _read_summary(artifact_directory, run_id)
    _verify_artifacts(artifact_directory, summary, run_id)
    session_tag = summary.get("sessionTag")
    session_id = summary.get("sessionId")
    if not isinstance(session_tag, str) or session_tag != f"{run_id}-usage":
        raise EvidenceFailure("summary session tag does not match this run")
    if not isinstance(session_id, str) or not re.fullmatch(r"[a-f0-9-]{36}", session_id):
        raise EvidenceFailure("summary is missing the immutable host session UUID")

    host_row = _host_snapshot(container, session_tag, session_id)
    started = summary["httpStartedMarker"]
    stopped = summary["httpStoppedMarker"]
    capture = _host_capture(container, session_id, started, stopped)
    capture_path = artifact_directory / "host-usage-ports-session-capture.txt"
    capture_path.write_text(capture, encoding="utf-8")
    service = _verify_service_stopped(container, artifact_directory, run_id, summary["httpRemotePort"])
    if service["pidState"] != "process-stopped":
        raise EvidenceFailure(f"the same-run Docker HTTP process was not observed stopped: {service['pidState']}")
    oracle = {
        "schema": 1,
        "runId": run_id,
        "source": "independent Docker exec: a snapshot/capture and ss listener table",
        "hostSession": host_row,
        "hostCaptureFile": capture_path.name,
        "hostListenerStop": service,
        "verifiedAndroidScreenshots": list(SCREENSHOTS),
        "verifiedProviderStates": summary["usageProviders"],
        "verifiedAutomaticHttpTunnel": {
            "remotePort": summary["httpRemotePort"],
            "localPort": summary["autoLocalPort"],
            "httpStatus": summary["httpStatus"],
            "twoMissCleanup": True,
        },
        "verifiedManualReconnect": {
            "remotePort": summary["manualRemotePort"],
            "initialConnectionId": summary["initialConnectionId"],
            "reconnectedConnectionId": summary["reconnectedConnectionId"],
            "reconnectedGenerationId": summary["reconnectedGenerationId"],
            "sshBanner": summary["reconnectedSshBanner"],
        },
        "nativeForwardCountAfterDisconnect": summary["nativeForwardCountAfterDisconnect"],
        "result": "PASS",
    }
    (artifact_directory / "host-usage-ports-oracle.json").write_text(
        json.dumps(oracle, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return oracle


def _self_test() -> int:
    # One synthetic 400x800 IHDR is sufficient here; real runs validate the PNG
    # bytes and dimensions emitted by UiAutomation.takeScreenshot().
    png = b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 400, 800)
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-usage-ports-host-") as scratch:
        root = Path(scratch)
        run_id = "js2859-selftest"
        states = [
            {"provider": "claude", "state": "blocked"},
            {"provider": "codex", "state": "ok"},
            {"provider": "copilot", "state": "approaching"},
            {"provider": "fixture-critical", "state": "critical"},
            {"provider": "fixture-error", "state": "error"},
        ]
        summary = {
            "schema": 1,
            "runId": run_id,
            "screenshots": list(SCREENSHOTS),
            "httpStartedMarker": marker(run_id, "HS"),
            "httpStoppedMarker": marker(run_id, "HE"),
            "httpRemotePort": 8_888,
            "httpStatus": "HTTP/1.0 200 OK",
            "outOfRangeRemotePort": 40_961,
            "outOfRangeListenerForwarded": False,
            "autoLocalPort": 40_001,
            "autoListenerAfterFirstMissingScan": True,
            "autoListenerAfterSecondMissingScan": False,
            "firstMissingScanRetained": True,
            "secondMissingScanClosed": True,
            "manualRemotePort": 22,
            "manualLocalPort": 40_002,
            "manualSshBanner": "SSH-2.0-fixture",
            "reconnectedManualLocalPort": 40_003,
            "reconnectedSshBanner": "SSH-2.0-fixture",
            "nativeForwardCountAfterDisconnect": 0,
            "nativeForwardCountAfterFinalDisconnect": 0,
            "manualListenerAfterDisconnect": False,
            "reconnectedListenerAfterDisconnect": False,
            "initialConnectionId": "conn-1",
            "reconnectedConnectionId": "conn-2",
            "initialGenerationId": "generation-1",
            "reconnectedGenerationId": "generation-2",
            "usageProviders": states,
        }
        (root / "journey-summary.json").write_text(json.dumps(summary), encoding="utf-8")
        for name in SCREENSHOTS:
            (root / name).write_bytes(png)
            (root / f"{name}.sha256").write_text(hashlib.sha256(png).hexdigest(), encoding="utf-8")
        (root / "host-http-stop-process-check.txt").write_text(
            "pid=4119\n"
            "cmdline=python3 -m http.server 8888 --bind 127.0.0.1\n"
            "decision=matched-kill\n"
            "exitDecision=process-exited\n",
            encoding="utf-8",
        )
        _verify_artifacts(root, summary, run_id)
        print("ok [1/6] complete same-run summary, process stop proof, and hashed device screenshots pass")
        bad_summary = dict(summary, httpRemotePort=22)
        try:
            _verify_artifacts(root, bad_summary, run_id)
            raise AssertionError("low port was accepted as automatic")
        except EvidenceFailure:
            print("ok [2/6] low port cannot qualify as an automatic interesting listener")
        bad_summary = dict(summary, usageProviders=[{"provider": "claude", "state": "ok"}])
        try:
            _verify_artifacts(root, bad_summary, run_id)
            raise AssertionError("missing quota/error states were accepted")
        except EvidenceFailure:
            print("ok [3/6] missing quota/error states fail closed")
        (root / "host-http-stop-process-check.txt").write_text(
            "pid=4119\ncmdline=python3 -m http.server 8888 --bind 127.0.0.1\n"
            "decision=identity-mismatch\nexitDecision=process-exited\n",
            encoding="utf-8",
        )
        try:
            _verify_artifacts(root, summary, run_id)
            raise AssertionError("mismatched process identity was accepted")
        except EvidenceFailure:
            print("ok [4/6] mismatched HTTP process identity fails closed")
        (root / "host-http-stop-process-check.txt").write_text(
            "pid=4119\ncmdline=python3 -m http.server 8888 --bind 127.0.0.1\n"
            "decision=matched-kill\nexitDecision=process-exited\n",
            encoding="utf-8",
        )
        try:
            _verify_artifacts(root, dict(summary, outOfRangeListenerForwarded=True), run_id)
            raise AssertionError("an out-of-range listener was accepted as auto-forwarded")
        except EvidenceFailure:
            print("ok [5/6] auto-forwarding an out-of-range listener fails closed")
        try:
            _verify_artifacts(root, dict(summary, outOfRangeRemotePort=70_000), run_id)
            raise AssertionError("an invalid TCP port was accepted as an out-of-range listener")
        except EvidenceFailure:
            print("ok [6/6] invalid out-of-range TCP port fails closed")
    print("PASS: usage/ports host evidence checks (6/6)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id")
    parser.add_argument("--container")
    parser.add_argument("--artifact-directory", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return _self_test()
    if not args.run_id or not args.container or args.artifact_directory is None:
        parser.error("--run-id, --container, and --artifact-directory are required unless --self-test is used")
    try:
        oracle = validate(args.container, args.artifact_directory, args.run_id)
    except EvidenceFailure as error:
        print(f"FAIL: usage/ports host evidence: {error}", file=sys.stderr)
        return 1
    print("PASS: independent Docker oracle matched host markers, stopped listener, quota states, and Android tunnel artifacts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
