#!/usr/bin/python3 -I
"""Fail closed unless the packaged JS lifecycle journey and its abrupt-drop oracle ran."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from typing import Any


REQUIRED_CLASS = "com.pocketshell.app.smoke.SshPtyDockerJourneyTest"
REQUIRED_METHOD = "sshSessionSwitchingGraceAndAbruptServerDropReconnectAgainstDockerFixture"
DEFAULT_RESULTS = Path("android/app/build/outputs/androidTest-results/connected/debug")
REQUIRED_ASSERTIONS = {
    "server-side-sshd-transport-killed",
    "native-transport-loss-observed-once",
    "single-js-reconnect-and-session-reattach",
    "fresh-terminal-viewport-captured",
    "post-reconnect-pty-bytes-present-on-host-exactly-once",
}
REQUIRED_ARTIFACTS = {
    "journey-summary.json",
    "abrupt-drop-server-proof.txt",
    "abrupt-drop-recovered-visible-terminal.txt",
    "abrupt-drop-recovered-viewport.png",
    "abrupt-drop-recovered-full-screen.png",
}
SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$")
SAFE_ASSET_NAME = re.compile(r"^[A-Za-z0-9._-]{1,100}$")
ANSI_CSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
ASSET_TAG = "PocketshellJourneyAsset:"


class GateFailure(ValueError):
    pass


def _validate_results(results: Path) -> None:
    if not results.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results}")
    reports = sorted(results.rglob("TEST-*.xml"))
    if not reports:
        raise GateFailure(f"no instrumentation XML found under {results}")

    discovered: list[tuple[str, str, bool, bool]] = []
    declared_tests = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            raise GateFailure(f"could not parse {report}: {error}") from error
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise GateFailure(f"{report}: expected a <testsuite> report")
        for suite in suites:
            cases = list(suite.findall("testcase"))
            try:
                declared = int(suite.attrib["tests"])
            except (KeyError, ValueError) as error:
                raise GateFailure(f"{report}: missing or invalid test count") from error
            if declared != len(cases):
                raise GateFailure(f"{report}: declares {declared} tests but contains {len(cases)} cases")
            declared_tests += declared
            for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                try:
                    reported = int(suite.attrib.get(summary, "0"))
                except ValueError as error:
                    raise GateFailure(f"{report}: invalid {summary} count") from error
                actual = sum(1 for case in cases for child in case.iter(tag))
                if reported != actual:
                    raise GateFailure(f"{report}: {summary} says {reported}, testcase details show {actual}")
            for case in cases:
                class_name = case.attrib.get("classname", "")
                method_name = case.attrib.get("name", "")
                if not class_name or not method_name:
                    raise GateFailure(f"{report}: testcase is missing its class or method")
                discovered.append((class_name, method_name, bool(list(case.iter("failure")) or list(case.iter("error"))), bool(list(case.iter("skipped")))))

    counts = Counter((class_name, method_name) for class_name, method_name, _, _ in discovered)
    duplicates = [f"{class_name}#{method} x{count}" for (class_name, method), count in counts.items() if count != 1]
    if duplicates:
        raise GateFailure("duplicate test cases: " + ", ".join(sorted(duplicates)))
    identities = {(class_name, method) for class_name, method, _, _ in discovered}
    required = {(REQUIRED_CLASS, REQUIRED_METHOD)}
    if identities != required or len(discovered) != 1 or declared_tests != 1:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {sorted(identities)}")
    failures = [f"{name}#{method}" for name, method, failed, _ in discovered if failed]
    skipped = [f"{name}#{method}" for name, method, _, was_skipped in discovered if was_skipped]
    if failures:
        raise GateFailure("failed tests: " + ", ".join(failures))
    if skipped:
        raise GateFailure("skipped tests: " + ", ".join(skipped))


def _find_same_run_directory(results: Path) -> Path:
    reports = sorted(results.rglob("TEST-*.xml"))
    if not reports:
        raise GateFailure(f"no instrumentation XML found under {results}")
    latest_report_time = max(report.stat().st_mtime for report in reports)
    resolved_results = results.resolve()
    if len(resolved_results.parents) < 3:
        raise GateFailure(f"cannot locate packaged lifecycle artifacts beside {results}")
    lifecycle_root = resolved_results.parents[2] / "js-lifecycle"
    if not lifecycle_root.is_dir():
        raise GateFailure(f"same-run JS lifecycle artifact directory is missing: {lifecycle_root}")
    candidates: list[Path] = []
    for run_directory in lifecycle_root.iterdir():
        logcat = run_directory / "lifecycle-assets-live-logcat.txt"
        if not run_directory.is_dir() or not logcat.is_file() or not SAFE_RUN_ID.fullmatch(run_directory.name):
            continue
        age = latest_report_time - logcat.stat().st_mtime
        if -30 <= age <= 30 * 60:
            candidates.append(run_directory)
    if not candidates:
        raise GateFailure("no same-run live lifecycle artifact log is close to the instrumentation result time")
    candidates.sort(key=lambda directory: (directory / "lifecycle-assets-live-logcat.txt").stat().st_mtime_ns)
    return candidates[-1]


def _read_same_run_assets(logcat: Path, run_id: str) -> dict[str, bytes]:
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        raise GateFailure(f"could not read same-run lifecycle artifact log {logcat}: {error}") from error

    assets: dict[str, dict[str, Any]] = {}
    for line in lines:
        if ASSET_TAG not in line:
            continue
        message = line.split(ASSET_TAG, 1)[1].strip()
        parts = message.split("|", 4)
        if len(parts) < 3 or parts[1] != run_id:
            continue
        kind, _, name = parts[:3]
        if not SAFE_ASSET_NAME.fullmatch(name):
            raise GateFailure(f"same-run artifact has an unsafe name: {name!r}")
        if kind == "BEGIN":
            if len(parts) != 5 or name in assets:
                raise GateFailure(f"same-run artifact has a duplicate or malformed BEGIN: {name}")
            try:
                chunks = int(parts[3])
            except ValueError as error:
                raise GateFailure(f"same-run artifact has an invalid chunk count: {name}") from error
            if chunks < 1 or not re.fullmatch(r"[a-f0-9]{64}", parts[4]):
                raise GateFailure(f"same-run artifact has an invalid chunk count or digest: {name}")
            assets[name] = {"count": chunks, "sha256": parts[4], "parts": {}, "ended": False}
        elif kind == "DATA":
            if len(parts) != 5 or name not in assets or assets[name]["ended"]:
                raise GateFailure(f"same-run artifact DATA has no active BEGIN: {name}")
            try:
                index = int(parts[3])
            except ValueError as error:
                raise GateFailure(f"same-run artifact has an invalid chunk index: {name}") from error
            chunks = assets[name]["parts"]
            if index in chunks:
                raise GateFailure(f"same-run artifact repeats chunk {index}: {name}")
            chunks[index] = parts[4]
        elif kind == "END":
            if len(parts) != 3 or name not in assets or assets[name]["ended"]:
                raise GateFailure(f"same-run artifact END has no matching BEGIN: {name}")
            assets[name]["ended"] = True
        else:
            raise GateFailure(f"same-run artifact has an unknown record kind: {kind!r}")

    decoded: dict[str, bytes] = {}
    for name, asset in assets.items():
        parts = asset["parts"]
        count = asset["count"]
        if not asset["ended"] or len(parts) != count or set(parts) != set(range(count)):
            raise GateFailure(f"same-run artifact is incomplete: {name} ({len(parts)}/{count} chunks)")
        encoded = "".join(parts[index] for index in range(count))
        try:
            payload = base64.b64decode(encoded, validate=True)
        except (ValueError, base64.binascii.Error) as error:
            raise GateFailure(f"same-run artifact is invalid base64: {name}") from error
        if hashlib.sha256(payload).hexdigest() != asset["sha256"]:
            raise GateFailure(f"same-run artifact SHA-256 does not match its manifest: {name}")
        decoded[name] = payload
    return decoded


def _parse_summary(assets: dict[str, bytes], run_id: str) -> dict[str, Any]:
    missing = sorted(REQUIRED_ARTIFACTS - set(assets))
    if missing:
        raise GateFailure("same-run abrupt-drop evidence artifacts are missing: " + ", ".join(missing))
    try:
        summary = json.loads(assets["journey-summary.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GateFailure(f"same-run lifecycle summary is invalid: {error}") from error
    if not isinstance(summary, dict) or summary.get("schema") != 1 or summary.get("runId") != run_id:
        raise GateFailure("same-run lifecycle summary has the wrong schema or run ID")
    return summary


def _parse_proof(text: str) -> dict[str, str]:
    proof: dict[str, str] = {}
    for line in text.splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            proof[key] = value
    return proof


def _count_marker_lines(raw: str, marker: str) -> int:
    plain = ANSI_CSI.sub("", raw).replace("\r\n", "\n").replace("\r", "\n")
    return sum(line.strip() == marker for line in plain.split("\n"))


def _validate_summary(summary: dict[str, Any], assets: dict[str, bytes], run_id: str) -> tuple[int, str, str]:
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise GateFailure(f"run ID is unsafe: {run_id!r}")
    port = summary.get("sshPort")
    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65535:
        raise GateFailure("same-run summary is missing the Docker SSH port")
    sessions = summary.get("sessions")
    if not isinstance(sessions, list):
        raise GateFailure("same-run summary is missing the independent session identities")
    session = next((row for row in sessions if isinstance(row, dict) and row.get("tag") == f"{run_id}-a"), None)
    if not isinstance(session, dict) or not isinstance(session.get("id"), str) or not session["id"]:
        raise GateFailure("same-run summary is missing the selected A session identity")

    drop = summary.get("abruptTransportDrop")
    if not isinstance(drop, dict):
        raise GateFailure("same-run summary is missing abrupt transport-drop evidence")
    assertions = drop.get("assertions")
    if not isinstance(assertions, list) or not REQUIRED_ASSERTIONS.issubset(set(assertions)):
        missing = sorted(REQUIRED_ASSERTIONS - set(assertions if isinstance(assertions, list) else []))
        raise GateFailure("named abrupt-drop assertions are missing: " + ", ".join(missing))
    if drop.get("trigger") != "server-side-sshd-session-sigkill" or drop.get("triggerSignal") != "SIGKILL":
        raise GateFailure("journey did not prove a Docker-host SSH server-process SIGKILL")
    proof_name = drop.get("triggerProofFile")
    if proof_name != "abrupt-drop-server-proof.txt":
        raise GateFailure("abrupt transport-drop proof artifact is not named in the summary")
    try:
        proof_text = assets[proof_name].decode("utf-8")
    except (KeyError, UnicodeDecodeError) as error:
        raise GateFailure("abrupt transport-drop proof artifact is missing or invalid") from error
    proof = _parse_proof(proof_text)
    if proof.get("run_id") != run_id or proof.get("signal") != "SIGKILL":
        raise GateFailure("server-side proof file does not identify this run and SIGKILL")
    try:
        proof_pid = int(proof["server_pid"])
        proof_uid = int(proof["server_uid"])
        summary_pid = drop["serverPid"]
        summary_uid = drop["serverUid"]
    except (KeyError, TypeError, ValueError) as error:
        raise GateFailure("server-side proof file or summary has invalid sshd PID/UID values") from error
    if proof_pid <= 1 or proof_uid <= 0 or summary_pid != proof_pid or summary_uid != proof_uid:
        raise GateFailure("server-side proof must name the non-root sshd session process killed by this run")
    if not all(token in proof.get("server_args", "") for token in ("sshd", "testuser@pts/")):
        raise GateFailure("server-side proof did not identify the active testuser PTY sshd process")
    if drop.get("serverArgs") != proof.get("server_args"):
        raise GateFailure("summary sshd process metadata does not match the server-side proof file")

    old_connection = drop.get("oldConnectionId")
    new_connection = drop.get("newConnectionId")
    old_generation = drop.get("oldGenerationId")
    new_generation = drop.get("newGenerationId")
    if not all(isinstance(value, str) and value for value in (old_connection, new_connection, old_generation, new_generation)):
        raise GateFailure("abrupt-drop evidence is missing old/new native connection and generation IDs")
    if old_connection == new_connection or old_generation == new_generation:
        raise GateFailure("abrupt-drop recovery reused the old SSH transport or PTY generation")
    if drop.get("nativeLostEventCount") != 1:
        raise GateFailure("abrupt-drop evidence must contain exactly one native lost event")
    if drop.get("reconnectingPhaseCount") != 1 or drop.get("recoveredLivePhaseCount") != 1:
        raise GateFailure("abrupt-drop evidence must prove one JS reconnect and one live same-session attach")
    if drop.get("selectedTag") != session["tag"] or drop.get("selectedId") != session["id"]:
        raise GateFailure("abrupt-drop recovery did not preserve the same selected aplexer session")
    times = [drop.get(key) for key in ("dropRequestedAtEpochMs", "lossObservedAtEpochMs", "reconnectLiveAtEpochMs")]
    if any(not isinstance(value, int) or isinstance(value, bool) for value in times) or not times[0] <= times[1] <= times[2]:
        raise GateFailure("abrupt-drop request, transport loss, and recovery timestamps are invalid")

    marker = drop.get("hostInputMarker")
    expected_marker = f"REMOTE_OUTPUT_{hashlib.sha256(run_id.encode('utf-8')).hexdigest()[:10].upper()}_AD"
    if marker != expected_marker or drop.get("hostCaptureExactMarkerLineCount") != 1:
        raise GateFailure("post-reconnect host capture does not prove the unique run marker exactly once")
    if drop.get("hostCaptureSource") != "SshCapability.exec /usr/bin/a capture --bytes 65536":
        raise GateFailure("post-reconnect host capture source is missing")
    ack_before = drop.get("inputAckCountBefore")
    ack_after = drop.get("inputAckCountAfter")
    if not isinstance(ack_before, int) or not isinstance(ack_after, int) or ack_after <= ack_before:
        raise GateFailure("post-reconnect PTY input has no native acknowledgement progress")
    if drop.get("inputFailureCountAfter") != 0 or drop.get("inputPendingAfter") != 0:
        raise GateFailure("post-reconnect PTY input was pending or failed")

    checkpoint = drop.get("recoveryCheckpoint")
    if not isinstance(checkpoint, dict):
        raise GateFailure("abrupt-drop recovery viewport checkpoint is missing")
    expected_text = "abrupt-drop-recovered-visible-terminal.txt"
    expected_viewport = "abrupt-drop-recovered-viewport.png"
    if checkpoint.get("checkpoint") != "abrupt-drop-recovered" or checkpoint.get("phase") != "live":
        raise GateFailure("abrupt-drop recovery checkpoint is not a live terminal capture")
    if checkpoint.get("connectionId") != new_connection or checkpoint.get("generationId") != new_generation:
        raise GateFailure("abrupt-drop recovery viewport is bound to a stale SSH transport")
    if checkpoint.get("selectedId") != session["id"] or checkpoint.get("tag") != session["tag"]:
        raise GateFailure("abrupt-drop recovery viewport does not show the selected host session")
    if checkpoint.get("marker") != marker or checkpoint.get("visibleTerminalFile") != expected_text:
        raise GateFailure("abrupt-drop recovery checkpoint is missing its named terminal marker artifact")
    if checkpoint.get("viewportPng") != expected_viewport or drop.get("fullScreenPng") != "abrupt-drop-recovered-full-screen.png":
        raise GateFailure("abrupt-drop recovery full-screen or viewport capture is not named in the summary")
    visible_text = assets[expected_text].decode("utf-8", errors="replace")
    if _count_marker_lines(visible_text, marker) != 1:
        raise GateFailure("post-reconnect visible terminal capture does not contain one exact marker line")
    for image_name in (expected_viewport, "abrupt-drop-recovered-full-screen.png"):
        image = assets[image_name]
        if len(image) <= 100 or not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise GateFailure(f"same-run Android screenshot is missing or not a non-empty PNG: {image_name}")
    return port, session["id"], marker


def _validate_lifecycle_logs(logcat: Path, run_id: str) -> None:
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        raise GateFailure(f"could not read same-run lifecycle logs {logcat}: {error}") from error
    steps = (
        "ABRUPT_DROP_SERVER_KILL_REQUESTED",
        "ABRUPT_DROP_NATIVE_LOST",
        "ABRUPT_DROP_RECOVERED",
    )
    for step in steps:
        marker = f"RUN {run_id} {step} "
        count = sum(marker in line for line in lines)
        if count != 1:
            raise GateFailure(f"same-run lifecycle logs must contain exactly one {step} record; found {count}")


def _container_for_ssh_port(port: int) -> str:
    try:
        result = subprocess.run(
            ["docker", "ps", "--format", "{{.Names}}\t{{.Ports}}"],
            check=True,
            text=True,
            capture_output=True,
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise GateFailure(f"could not locate the live Docker SSH fixture for port {port}: {error}") from error
    matches = []
    port_token = f":{port}->22/tcp"
    for line in result.stdout.splitlines():
        name, separator, published = line.partition("\t")
        if separator and port_token in published:
            matches.append(name)
    if len(matches) != 1:
        raise GateFailure(f"Docker SSH port {port} must map to exactly one running fixture; found {matches}")
    return matches[0]


def _docker_exec(container: str, command: list[str], *, user: str | None = None) -> str:
    args = ["docker", "exec"]
    if user is not None:
        args.extend(("-u", user, "-e", "HOME=/home/testuser"))
    args.extend((container, *command))
    try:
        result = subprocess.run(args, check=True, text=True, capture_output=True, timeout=30)
    except subprocess.CalledProcessError as error:
        message = error.stderr.strip() or error.stdout.strip()
        raise GateFailure(f"Docker host oracle failed ({' '.join(args)}): {message}") from error
    except (OSError, subprocess.SubprocessError) as error:
        raise GateFailure(f"Docker host oracle could not run {' '.join(args)}: {error}") from error
    return result.stdout


def _validate_host_oracle(
    container: str, run_id: str, session_id: str, marker: str, assets: dict[str, bytes],
    injected: dict[str, str] | None = None,
) -> None:
    if injected is None:
        proof_path = f"/tmp/pocketshell-server-transport-drop-{run_id}.txt"
        proof = _docker_exec(container, ["cat", proof_path])
        snapshot_raw = _docker_exec(container, ["/usr/bin/a", "snapshot", "--json"], user="testuser")
        history = _docker_exec(
            container, ["/usr/bin/a", "capture", "--bytes", "65536", session_id], user="testuser"
        )
    else:
        proof = injected["proof"]
        snapshot_raw = injected["snapshot"]
        history = injected["history"]

    try:
        artifact_proof = assets["abrupt-drop-server-proof.txt"].decode("utf-8")
    except (KeyError, UnicodeDecodeError) as error:
        raise GateFailure("server-side proof artifact is not valid UTF-8") from error
    if proof != artifact_proof:
        raise GateFailure("independent Docker host proof file does not match the same-run Android artifact")
    proof_fields = _parse_proof(proof)
    if proof_fields.get("run_id") != run_id or proof_fields.get("signal") != "SIGKILL":
        raise GateFailure("independent Docker host does not retain this run's sshd SIGKILL proof")
    if not all(token in proof_fields.get("server_args", "") for token in ("sshd", "testuser@pts/")):
        raise GateFailure("independent Docker host proof does not name the active testuser PTY sshd transport process")

    try:
        rows = json.loads(snapshot_raw)
    except json.JSONDecodeError as error:
        raise GateFailure(f"independent Docker aplexer snapshot is invalid JSON: {error}") from error
    matching = [
        row for row in rows
        if isinstance(row, dict) and row.get("id") == session_id and row.get("tag") == f"{run_id}-a"
    ] if isinstance(rows, list) else []
    if len(matching) != 1 or matching[0].get("state") != "running" or matching[0].get("worker_alive") is not True:
        raise GateFailure("independent Docker host no longer has exactly one live selected aplexer session")
    marker_count = _count_marker_lines(history, marker)
    if marker_count != 1:
        raise GateFailure(f"independent Docker a capture must contain exactly one post-reconnect marker line; found {marker_count}")


def validate(
    results: Path,
    evidence_directory: Path | None = None,
    host_oracle: dict[str, str] | None = None,
) -> None:
    _validate_results(results)
    run_directory = evidence_directory or _find_same_run_directory(results)
    if not run_directory.is_dir():
        raise GateFailure(f"same-run lifecycle evidence directory is missing: {run_directory}")
    run_id = run_directory.name
    logcat = run_directory / "lifecycle-assets-live-logcat.txt"
    if not logcat.is_file():
        raise GateFailure(f"same-run lifecycle artifact log is missing: {logcat}")
    assets = _read_same_run_assets(logcat, run_id)
    summary = _parse_summary(assets, run_id)
    port, session_id, marker = _validate_summary(summary, assets, run_id)
    _validate_lifecycle_logs(logcat, run_id)
    container = _container_for_ssh_port(port) if host_oracle is None else "synthetic-docker-host"
    _validate_host_oracle(container, run_id, session_id, marker, assets, host_oracle)


def write_report(directory: Path, cases: list[tuple[str, str, str]]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    suite = ET.Element("testsuite", {
        "name": REQUIRED_CLASS,
        "tests": str(len(cases)),
        "failures": str(sum(status == "failed" for _, _, status in cases)),
        "errors": "0",
        "skipped": str(sum(status == "skipped" for _, _, status in cases)),
    })
    for class_name, method_name, status in cases:
        case = ET.SubElement(suite, "testcase", {"classname": class_name, "name": method_name})
        if status == "failed":
            ET.SubElement(case, "failure", {"message": "synthetic failure"})
        elif status == "skipped":
            ET.SubElement(case, "skipped", {"message": "synthetic skip"})
    ET.ElementTree(suite).write(directory / "TEST-lifecycle.xml", encoding="utf-8", xml_declaration=True)


def write_synthetic_evidence(directory: Path, run_id: str, mutation: str = "") -> dict[str, str]:
    directory.mkdir(parents=True, exist_ok=True)
    session_id = "12345678-1234-1234-1234-123456789abc"
    marker = f"REMOTE_OUTPUT_{hashlib.sha256(run_id.encode('utf-8')).hexdigest()[:10].upper()}_AD"
    proof = (
        f"run_id={run_id}\nserver_pid=4321\nserver_uid=1000\n"
        "server_args=sshd-session: testuser@pts/7\nsignal=SIGKILL\n"
    )
    drop = {
        "assertions": sorted(REQUIRED_ASSERTIONS),
        "trigger": "server-side-sshd-session-sigkill",
        "triggerSignal": "SIGKILL",
        "triggerProofFile": "abrupt-drop-server-proof.txt",
        "oldConnectionId": "old-connection",
        "oldGenerationId": "old-generation",
        "newConnectionId": "new-connection",
        "newGenerationId": "new-generation",
        "nativeLostEventCount": 1,
        "reconnectingPhaseCount": 1,
        "recoveredLivePhaseCount": 1,
        "dropRequestedAtEpochMs": 100,
        "lossObservedAtEpochMs": 110,
        "reconnectLiveAtEpochMs": 120,
        "selectedTag": f"{run_id}-a",
        "selectedId": session_id,
        "serverPid": 4321,
        "serverUid": 1000,
        "serverArgs": "sshd-session: testuser@pts/7",
        "hostInputMarker": marker,
        "hostCaptureSource": "SshCapability.exec /usr/bin/a capture --bytes 65536",
        "hostCaptureExactMarkerLineCount": 1,
        "inputAckCountBefore": 10,
        "inputAckCountAfter": 12,
        "inputFailureCountAfter": 0,
        "inputPendingAfter": 0,
        "recoveryCheckpoint": {
            "checkpoint": "abrupt-drop-recovered",
            "phase": "live",
            "connectionId": "new-connection",
            "generationId": "new-generation",
            "selectedId": session_id,
            "tag": f"{run_id}-a",
            "marker": marker,
            "visibleTerminalFile": "abrupt-drop-recovered-visible-terminal.txt",
            "viewportPng": "abrupt-drop-recovered-viewport.png",
        },
        "fullScreenPng": "abrupt-drop-recovered-full-screen.png",
    }
    if mutation == "missing-assertion":
        drop["assertions"].remove("single-js-reconnect-and-session-reattach")
    if mutation == "wrong-host-count":
        drop["hostCaptureExactMarkerLineCount"] = 2
    if mutation == "wrong-transport":
        drop["newConnectionId"] = drop["oldConnectionId"]
    if mutation == "wrong-proof":
        proof = proof.replace("signal=SIGKILL", "signal=TERM")

    summary = {
        "schema": 1,
        "runId": run_id,
        "sshPort": 2222,
        "sessions": [{"tag": f"{run_id}-a", "id": session_id}],
        "abruptTransportDrop": drop,
    }
    visible = f"command echo\n{marker}\n"
    png = b"\x89PNG\r\n\x1a\n" + b"synthetic-image" * 20
    assets = {
        "journey-summary.json": json.dumps(summary, sort_keys=True).encode("utf-8"),
        "abrupt-drop-server-proof.txt": proof.encode("utf-8"),
        "abrupt-drop-recovered-visible-terminal.txt": visible.encode("utf-8"),
        "abrupt-drop-recovered-viewport.png": png,
        "abrupt-drop-recovered-full-screen.png": png,
    }
    if mutation == "missing-artifact":
        del assets["abrupt-drop-recovered-full-screen.png"]
    lines = [
        f"09-28 12:00:00.001 I SshPtyDockerJourney: RUN {run_id} ABRUPT_DROP_SERVER_KILL_REQUESTED {{}}",
        f"09-28 12:00:00.002 I SshPtyDockerJourney: RUN {run_id} ABRUPT_DROP_NATIVE_LOST {{}}",
        f"09-28 12:00:00.003 I SshPtyDockerJourney: RUN {run_id} ABRUPT_DROP_RECOVERED {{}}",
    ]
    for name, payload in assets.items():
        encoded = base64.b64encode(payload).decode("ascii")
        chunks = [encoded[index:index + 1800] for index in range(0, len(encoded), 1800)]
        digest = hashlib.sha256(payload).hexdigest()
        lines.append(f"09-28 12:00:01.001 I {ASSET_TAG} BEGIN|{run_id}|{name}|{len(chunks)}|{digest}")
        for index, chunk in enumerate(chunks):
            lines.append(f"09-28 12:00:01.002 I {ASSET_TAG} DATA|{run_id}|{name}|{index}|{chunk}")
        lines.append(f"09-28 12:00:01.003 I {ASSET_TAG} END|{run_id}|{name}")
    if mutation == "missing-log":
        lines = [line for line in lines if "ABRUPT_DROP_NATIVE_LOST" not in line]
    (directory / "lifecycle-assets-live-logcat.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {
        "proof": proof,
        "snapshot": json.dumps([{
            "tag": f"{run_id}-a", "id": session_id, "state": "running", "worker_alive": True,
        }]),
        "history": f"command echo\n{marker}\n",
    }


def self_test() -> int:
    good = [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")]
    result_probes: list[tuple[str, list[tuple[str, str, str]] | None, bool]] = [
        ("exact packaged journey passes", good, True),
        ("missing result XML blocks", None, False),
        ("zero tests block", [], False),
        ("missing journey blocks", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("unexpected class blocks", [("other.Journey", REQUIRED_METHOD, "passed")], False),
        ("duplicate journey blocks", good + good, False),
        ("skipped journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
        ("failed journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
    ]
    evidence_probes = [
        ("missing abrupt artifact blocks", "missing-artifact", False),
        ("missing named assertion blocks", "missing-assertion", False),
        ("stale transport identity blocks", "wrong-transport", False),
        ("mismatched Docker proof blocks", "wrong-proof", False),
        ("duplicate post-reconnect host marker blocks", "wrong-host-count", False),
        ("missing same-run native loss log blocks", "missing-log", False),
    ]
    total = len(result_probes) + len(evidence_probes)
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-lifecycle-results-") as scratch:
        root = Path(scratch)
        index = 0
        for label, cases, expected in result_probes:
            report_dir = root / f"case-{index}" / "results"
            evidence_dir = root / f"case-{index}" / "run-selftest"
            if cases is not None:
                write_report(report_dir, cases)
                host_oracle = write_synthetic_evidence(evidence_dir, evidence_dir.name)
            else:
                host_oracle = None
            try:
                validate(report_dir, evidence_dir, host_oracle)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: lifecycle result guard probe {index + 1}: {label}", file=sys.stderr)
                return 1
            print(f"ok [{index + 1}/{total}] {label}")
            index += 1
        for label, mutation, expected in evidence_probes:
            report_dir = root / f"case-{index}" / "results"
            evidence_dir = root / f"case-{index}" / f"run-selftest-{index}"
            write_report(report_dir, good)
            host_oracle = write_synthetic_evidence(evidence_dir, evidence_dir.name, mutation)
            try:
                validate(report_dir, evidence_dir, host_oracle)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: lifecycle result guard probe {index + 1}: {label}", file=sys.stderr)
                return 1
            print(f"ok [{index + 1}/{total}] {label}")
            index += 1
    print(f"PASS: lifecycle result guard checks ({total}/{total})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        validate(args.results_dir)
    except GateFailure as error:
        print(f"FAIL: packaged JS lifecycle journey: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} and same-run abrupt-drop Docker evidence")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
