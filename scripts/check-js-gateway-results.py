#!/usr/bin/python3 -I
"""Require the packaged #3086 gateway journey to execute, with host proof.

Verifies that fresh instrumentation XML contains exactly the registered
GatewayDockerJourneyTest methods, none skipped or failed, and that the
runner's host-side evidence (scripts/gateway-lane-fixture.py's journal and
the Docker logs, all written by the host, never by the app) shows what the
journey claims:

  - the enrolled pin the phone paired with is the host's own key file;
  - the phone's key was authorized on the host, and sshd accepted exactly that
    key through the gateway, for the first dial and again after the reconnect;
  - the pin mismatch reached sshd and made ZERO userauth attempts;
  - the 4401, 4404 and 4503 refusals never reached sshd at all, and the
    gateway's own presence said offline before the 4503 dial;
  - terminal I/O and both resize sizes were read back from host files;
  - the tunnel was dropped once for the reconnect.

Usage:
  scripts/check-js-gateway-results.py --results-dir <dir> --host-dir <dir>
  scripts/check-js-gateway-results.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

REQUIRED_CLASS = "com.pocketshell.app.smoke.GatewayDockerJourneyTest"
REQUIRED_METHODS = frozenset(
    {
        "refusesPinMismatchUnverifiedTokenUnknownAndOfflineDevicesBeforeAnyLogin",
        "dialsTheEnrolledHostThroughTheGatewayListsAttachesTypesResizesAndReconnects",
    }
)
SELF_TEST_CASES = 10


class GateFailure(ValueError):
    """The gateway lane did not execute, or its host evidence contradicts it."""


def validate_results(results_dir: Path) -> int:
    if not results_dir.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results_dir}")
    files = sorted(results_dir.rglob("TEST-*.xml"))
    if not files:
        raise GateFailure(f"no TEST-*.xml instrumentation results under {results_dir}")
    seen: list[tuple[str, str]] = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            raise GateFailure(f"could not parse {path}: {exc}") from exc
        cases = list(root.iter("testcase"))
        if int(root.attrib.get("tests", "-1")) != len(cases):
            raise GateFailure(f"{path}: declared test count disagrees with its test cases")
        for case in cases:
            name = (case.attrib.get("classname", ""), case.attrib.get("name", ""))
            if list(case.iter("failure")) or list(case.iter("error")):
                raise GateFailure(f"{name[0]}#{name[1]} failed")
            if list(case.iter("skipped")):
                raise GateFailure(f"{name[0]}#{name[1]} was skipped")
            seen.append(name)
    counts = Counter(seen)
    if any(count != 1 for count in counts.values()):
        raise GateFailure(f"a test case ran more than once: {counts}")
    expected = {(REQUIRED_CLASS, method) for method in REQUIRED_METHODS}
    if set(seen) != expected:
        raise GateFailure(f"expected exactly {sorted(expected)}, ran {sorted(set(seen))}")
    return len(seen)


def _journal(host_dir: Path) -> list[dict]:
    path = host_dir / "controller-journal.jsonl"
    if not path.is_file():
        raise GateFailure(f"the controller journal is missing: {path}")
    entries = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not entries:
        raise GateFailure("the controller journal is empty")
    return entries


def _phase(entries: list[dict], op: str, phase: str) -> list[dict]:
    found = [e["reply"] for e in entries if e["request"].get("op") == op and e["request"].get("phase") == phase]
    for reply in found:
        if not reply.get("ok"):
            raise GateFailure(f"controller {op}/{phase} failed: {reply}")
    if not found:
        raise GateFailure(f"the journey never ran controller {op} for phase {phase!r}")
    return found


def validate_host(host_dir: Path) -> dict:
    hello = json.loads((host_dir / "hello.json").read_text(encoding="utf-8"))
    host_key = (host_dir / "host-key.pub").read_text(encoding="utf-8").strip()
    pinned = json.loads((host_dir / "pinned-key.json").read_text(encoding="utf-8"))
    if not host_key or pinned.get("hostKeyLine") != host_key or hello.get("hostKeyLine") != host_key:
        raise GateFailure("the pin the phone was given is not the host's own key file")
    if pinned.get("deviceId") != hello.get("deviceId"):
        raise GateFailure("the enrolled device is not the one the journey dialled")
    if hello.get("wrongHostKeyLine") == host_key:
        raise GateFailure("the pin-mismatch key is the host's key")
    if not str(hello.get("serverUrl", "")).startswith("wss://localhost:"):
        raise GateFailure(f"the phone did not dial wss:// to the TLS front: {hello.get('serverUrl')}")

    entries = _journal(host_dir)
    authorized = {r["fingerprint"] for r in _phase(entries, "authorize", "authorize")}
    if len(authorized) != 2:
        raise GateFailure(f"expected one fresh phone key per test, authorized {authorized}")

    def accepted(reply: dict) -> set[str]:
        return {a["fingerprint"] for a in reply["accepted"]}

    for reply in _phase(entries, "sshd-since", "pin-mismatch"):
        if reply["connections"] < 1:
            raise GateFailure("the pin-mismatch dial never reached the host's sshd")
        if reply["userauth"] or reply["accepted"]:
            raise GateFailure(f"the pin mismatch made userauth attempts: {reply['userauth']}")
    for phase in ("unauthorized-4401", "not-found-4404", "offline-4503"):
        for reply in _phase(entries, "sshd-since", phase):
            if reply["connections"] or reply["userauth"] or reply["accepted"]:
                raise GateFailure(f"the {phase} refusal reached sshd: {reply}")
    for reply in _phase(entries, "agent-stop", "offline"):
        if reply.get("online") is not False:
            raise GateFailure("the gateway did not report the device offline before the 4503 dial")
    for phase in ("connect", "reconnect"):
        replies = _phase(entries, "sshd-since", phase)
        if not any(accepted(reply) & authorized for reply in replies):
            raise GateFailure(f"sshd never accepted the phone's key for the {phase}")
        foreign = set().union(*(accepted(reply) for reply in replies)) - authorized
        if foreign:
            raise GateFailure(f"sshd accepted keys that are not the phone's during {phase}: {foreign}")
    _phase(entries, "drop", "reconnect")

    files = {
        reply["name"]: reply["content"].strip()
        for e in entries
        if e["request"].get("op") == "host-file" and (reply := e["reply"]).get("ok")
    }
    io = [content for name, content in files.items() if name.startswith("gwlane-io-")]
    if not io or not re.fullmatch(r"GWLANE_42_\S+", io[-1]):
        raise GateFailure(f"the host never recorded the typed command's output: {io}")
    back = [content for name, content in files.items() if name.startswith("gwlane-reconnected-")]
    if not back or not re.fullmatch(r"GWLANE_BACK_42_\S+", back[-1]):
        raise GateFailure(f"the host never recorded typing after the reconnect: {back}")
    sizes = {}
    for name, content in files.items():
        for key in ("size1", "size2"):
            if name.startswith(f"gwlane-{key}-") and re.fullmatch(r"\d+ \d+", content):
                sizes[key] = int(content.split()[0])
    if set(sizes) != {"size1", "size2"} or not sizes["size2"] < sizes["size1"]:
        raise GateFailure(f"the host PTY did not shrink with the pane: {sizes}")

    gateway_log = host_dir / "gateway.log"
    sshd_log = host_dir / "host.log"
    for log in (gateway_log, sshd_log):
        if not log.is_file() or log.stat().st_size == 0:
            raise GateFailure(f"missing same-run fixture log: {log}")
    # The gateway's and sshd's own words for what the journey asserted.
    gateway_text = gateway_log.read_text(encoding="utf-8", errors="replace")
    device = re.escape(str(hello["deviceId"]))
    if len(re.findall(rf"client session open device={device} ", gateway_text)) < 3:
        raise GateFailure("the gateway logged fewer client sessions than connect, pin mismatch and reconnect")
    if not re.search(rf"client token rejected device={device} ", gateway_text):
        raise GateFailure("the gateway never logged the 4401 token rejection")
    if not re.search(rf"client target device missing device={device}-missing ", gateway_text):
        raise GateFailure("the gateway never logged the 4404 unknown device")
    sshd_text = sshd_log.read_text(encoding="utf-8", errors="replace")
    if not re.search(r"Received disconnect from 127\.0\.0\.1 port \d+:\d+: Could not verify `ssh-ed25519` host key .*\[preauth\]", sshd_text):
        raise GateFailure("sshd never saw the phone refuse the host key (the pin mismatch) during KEX")
    return {"authorized": sorted(authorized), "rows": [sizes["size1"], sizes["size2"]]}


def _write_xml(directory: Path, methods: list[str], failed: str | None = None) -> None:
    cases = "".join(
        f'<testcase classname="{REQUIRED_CLASS}" name="{m}">' + ("<failure>x</failure>" if m == failed else "") + "</testcase>"
        for m in methods
    )
    failures = 1 if failed else 0
    (directory / "TEST-gw.xml").write_text(
        f'<testsuite tests="{len(methods)}" failures="{failures}" errors="0" skipped="0">{cases}</testsuite>'
    )


def _write_host(directory: Path, **overrides) -> None:
    key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINV1em8xlnD0YdhVrl9boi0uLwrfiqee7NBQdXL/8jT6"
    hello = {"deviceId": "gwlane-x", "serverUrl": "wss://localhost:3287", "hostKeyLine": key,
             "wrongHostKeyLine": "ssh-ed25519 AAAAWRONG"}
    (directory / "hello.json").write_text(json.dumps(hello))
    (directory / "host-key.pub").write_text(key + "\n")
    (directory / "pinned-key.json").write_text(json.dumps({"hostKeyLine": key, "deviceId": "gwlane-x"}))
    open_line = "INFO tunnel: client session open device=gwlane-x account=google:s\n"
    (directory / "gateway.log").write_text(
        open_line * overrides.get("sessions", 3)
        + "INFO tunnel: client token rejected device=gwlane-x err=x\n"
        + "INFO tunnel: client target device missing device=gwlane-x-missing account=google:s\n")
    (directory / "host.log").write_text(
        "Received disconnect from 127.0.0.1 port 45784:9: Could not verify `ssh-ed25519` host key with fingerprint "
        "`13:f9` for `gw-dev.gateway` on port 22 [preauth]\n")
    fp1, fp2 = "SHA256:" + "a" * 43, "SHA256:" + "b" * 43

    def entry(op, phase=None, **reply):
        request = {"op": op}
        if phase:
            request["phase"] = phase
        return {"request": request, "reply": {"ok": True, **reply}}

    entries = [
        entry("authorize", "authorize", fingerprint=fp1),
        entry("sshd-since", "unauthorized-4401", connections=0, userauth=[], accepted=[]),
        entry("sshd-since", "pin-mismatch", connections=overrides.get("mismatch_connections", 1),
              userauth=overrides.get("mismatch_userauth", []), accepted=[]),
        entry("sshd-since", "not-found-4404", connections=0, userauth=[], accepted=[]),
        entry("agent-stop", "offline", online=overrides.get("offline", False)),
        entry("sshd-since", "offline-4503", connections=0, userauth=[], accepted=[]),
        entry("authorize", "authorize", fingerprint=fp2),
        entry("sshd-since", "connect", connections=1, userauth=["Accepted"], accepted=[{"fingerprint": fp2}]),
        {"request": {"op": "host-file", "name": "gwlane-io-r.txt"}, "reply": {"ok": True, "name": "gwlane-io-r.txt", "content": "GWLANE_42_r\n"}},
        {"request": {"op": "host-file", "name": "gwlane-size1-r.txt"}, "reply": {"ok": True, "name": "gwlane-size1-r.txt", "content": "40 50\n"}},
        {"request": {"op": "host-file", "name": "gwlane-size2-r.txt"}, "reply": {"ok": True, "name": "gwlane-size2-r.txt", "content": overrides.get("size2", "22 50\n")}},
        entry("drop", "reconnect", dropped=True),
        entry("sshd-since", "reconnect", connections=1, userauth=["Accepted"], accepted=[{"fingerprint": overrides.get("reconnect_fp", fp2)}]),
        {"request": {"op": "host-file", "name": "gwlane-reconnected-r.txt"}, "reply": {"ok": True, "name": "gwlane-reconnected-r.txt", "content": "GWLANE_BACK_42_r\n"}},
    ]
    (directory / "controller-journal.jsonl").write_text("".join(json.dumps(e) + "\n" for e in entries))


def self_test() -> int:
    cases = 0

    def expect_fail(label, results=None, host=None):
        nonlocal cases
        with tempfile.TemporaryDirectory() as tmp:
            r, h = Path(tmp, "r"), Path(tmp, "h")
            r.mkdir()
            h.mkdir()
            _write_xml(r, **(results or {"methods": sorted(REQUIRED_METHODS)}))
            _write_host(h, **(host or {}))
            try:
                validate_results(r)
                validate_host(h)
            except GateFailure:
                cases += 1
                return
            raise AssertionError(f"self-test case passed but must fail: {label}")

    with tempfile.TemporaryDirectory() as tmp:
        r, h = Path(tmp, "r"), Path(tmp, "h")
        r.mkdir()
        h.mkdir()
        _write_xml(r, sorted(REQUIRED_METHODS))
        _write_host(h)
        assert validate_results(r) == 2
        validate_host(h)
        cases += 1
    expect_fail("missing method", results={"methods": sorted(REQUIRED_METHODS)[:1]})
    expect_fail("failed method", results={"methods": sorted(REQUIRED_METHODS), "failed": sorted(REQUIRED_METHODS)[0]})
    expect_fail("extra method", results={"methods": sorted(REQUIRED_METHODS) + ["other"]})
    expect_fail("mismatch made userauth", host={"mismatch_userauth": ["Postponed publickey"]})
    expect_fail("mismatch never reached sshd", host={"mismatch_connections": 0})
    expect_fail("offline not confirmed", host={"offline": True})
    expect_fail("no resize", host={"size2": "40 50\n"})
    expect_fail("foreign key on reconnect", host={"reconnect_fp": "SHA256:" + "c" * 43})
    expect_fail("gateway logged too few sessions", host={"sessions": 2})
    if cases != SELF_TEST_CASES:
        raise AssertionError(f"ran {cases} self-test cases, expected {SELF_TEST_CASES}")
    print(f"PASS: gateway result checker self-test ({cases} cases)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--host-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.results_dir is None or args.host_dir is None:
        parser.error("--results-dir and --host-dir are required")
    try:
        executed = validate_results(args.results_dir)
        summary = validate_host(args.host_dir)
    except (GateFailure, OSError, ValueError, KeyError) as error:
        print(f"FAIL: packaged gateway journey: {error}", file=sys.stderr)
        return 1
    print(f"PASS: packaged gateway journey executed {executed} tests, {executed} passed; host evidence {json.dumps(summary)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
