#!/usr/bin/python3 -I
"""Require the packaged #3086 gateway journey to execute, with host proof.

Verifies that fresh instrumentation XML contains exactly the registered
GatewayDockerJourneyTest methods, none skipped or failed, and that the
runner's host-side evidence (scripts/gateway-lane-fixture.py's journal and
the Docker logs, all written by the host, never by the app) shows what the
journey claims:

  - the enrolled pin the vault authenticated is the host's own key file;
  - the vault key was authorized on the host, and sshd accepted exactly that
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
        "dialsTheGatewayHostWithTheAccountVaultKeyAfterOneUnlockAndNoKeyPicker",
        "connectsTrustedVaultServerWithoutManualPairingAndCreatesHomeTerminal",
        "recoversNativeAuthLossWithoutSignOutAndPreservesVaultTrustBeforeRealTerminal",
        "onboardingExistingVaultRequiresGoogleThenRejectsWrongPhraseBeforeOneUnlockAndRealTerminal",
        "onboardingCreatesMissingVaultAtomicallyInAppAndReopensItWithoutWeb",
    }
)
SELF_TEST_CASES = 29


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
    if len(authorized) != 1:
        raise GateFailure(f"expected the one authoritative vault credential, authorized {authorized}")

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
            raise GateFailure(f"sshd never accepted the vault key for the {phase}")
        foreign = set().union(*(accepted(reply) for reply in replies)) - authorized
        if foreign:
            raise GateFailure(f"sshd accepted keys that are not the vault's during {phase}: {foreign}")
    _phase(entries, "drop", "reconnect")
    # Every positive managed route authenticates with the authoritative vault key.
    vault_keys = {r["fingerprint"] for r in _phase(entries, "authorize", "vault-authorize")}
    if vault_keys != authorized:
        raise GateFailure(f"expected the same vault credential for each trusted server journey, authorized {vault_keys}")
    vault_replies = _phase(entries, "sshd-since", "vault-connect")
    if not any(accepted(reply) & vault_keys for reply in vault_replies):
        raise GateFailure("sshd never accepted the vault key for the gateway vault dial")
    vault_foreign = set().union(*(accepted(reply) for reply in vault_replies)) - vault_keys
    if vault_foreign:
        raise GateFailure(f"the gateway vault dial logged in with keys that are not the vault's: {vault_foreign}")

    before = _phase(entries, "home-sessions", "trusted-before")
    after = _phase(entries, "home-sessions", "trusted-after")
    if not before or any(r.get("count") != 0 or r.get("sessions") != [] for r in before):
        raise GateFailure("normal trusted-server case started with a pre-created HOME shell")
    if not after or not any(r.get("count") == 1 and len(r.get("sessions", [])) == 1 for r in after):
        raise GateFailure("normal server tap did not create its HOME shell")

    trusted_keys = {r["fingerprint"] for r in _phase(entries, "authorize", "trusted-authorize")}
    if trusted_keys != vault_keys:
        raise GateFailure("the trusted-server flow did not authorize the vault credential")
    trusted_logins = _phase(entries, "sshd-since", "trusted-connect")
    if not any(accepted(reply) == trusted_keys for reply in trusted_logins):
        raise GateFailure("sshd never accepted the trusted descriptor vault key")
    trusted_files = _phase(entries, "host-file", "trusted-io")
    def matching_trusted_file(reply):
        name = re.fullmatch(r"gwlane-trusted-io-([A-Za-z0-9_-]+)\.txt", str(reply.get("name", "")))
        return name is not None and str(reply.get("content", "")).strip() == f"GWTRUST42_{name.group(1)}"
    if not any(matching_trusted_file(reply) for reply in trusted_files):
        raise GateFailure("the Docker host never recorded the trusted server terminal command")

    auth_keys = {r["fingerprint"] for r in _phase(entries, "authorize", "authloss-authorize")}
    if auth_keys != vault_keys:
        raise GateFailure("auth-loss recovery did not retain the authenticated vault credential")
    for phase in ("authloss-connect", "authloss-recovered-connect"):
        logins = _phase(entries, "sshd-since", phase)
        if not any(accepted(reply) == auth_keys for reply in logins):
            raise GateFailure(f"no real vault-key SSH login for {phase}")
        if set().union(*(accepted(reply) for reply in logins)) - auth_keys:
            raise GateFailure(f"foreign SSH key accepted during {phase}")
    _phase(entries, "drop", "authloss-recovery")
    auth_files = _phase(entries, "host-file", "authloss-io")
    def matching_auth_file(reply):
        name = re.fullmatch(r"gwlane-authloss-io-([A-Za-z0-9_-]+)\.txt", str(reply.get("name", "")))
        return (name is not None and str(reply.get("content", "")).strip() == f"GWAUTH42_{name.group(1)}"
                and reply.get("exists") is True and reply.get("type") == "regular"
                and reply.get("readExitCode") == 0 and reply.get("sizeBytes", 0) > 0)
    if not any(matching_auth_file(reply) for reply in auth_files):
        raise GateFailure("no independent run-bound computed terminal result after auth-loss recovery")
    validate_authloss_runtime(host_dir.parent)
    onboarding_keys={r["fingerprint"] for r in _phase(entries,"authorize","onboarding-authorize")}
    if onboarding_keys!=vault_keys or not any(accepted(reply)==onboarding_keys for reply in _phase(entries,"sshd-since","onboarding-connect")):
        raise GateFailure("onboarding never authenticated at the real SSH host with the vault key")
    def matching_onboarding_file(reply):
        name=re.fullmatch(r"gwlane-onboarding-io-([A-Za-z0-9_-]+)\.txt",str(reply.get("name","")))
        return (name is not None and str(reply.get("content","")).strip()==f"GWONBOARD42_{name.group(1)}"
                and reply.get("exists") is True and reply.get("type")=="regular" and reply.get("readExitCode")==0)
    if not any(matching_onboarding_file(reply) for reply in _phase(entries,"host-file","onboarding-io")):
        raise GateFailure("the onboarding terminal never produced its run-bound independent host file")
    validate_onboarding_runtime(host_dir.parent)

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


def validate_authloss_runtime(artifacts: Path) -> None:
    path = artifacts / "logcat.txt"
    if not path.is_file():
        raise GateFailure("native auth-loss runtime log is missing")
    receipts = [json.loads(line.split("AUTH_LOSS_CUSTODY ", 1)[1])
                for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if "AUTH_LOSS_CUSTODY " in line]
    if len(receipts) != 1:
        raise GateFailure("exactly one native auth-loss custody receipt is required")
    row = receipts[0]
    expected = {"losses":3,"silentFailures":3,"interactive":4,"gatewayChallenges":1,"signOuts":0,"cloudWrites":0,"floor":1,
                "encryptedSlotsUnchanged":True,"protectedBytesSurviveLoss":True,"rememberBytesUnchanged":True,"scopeStable":True}
    if any(type(row.get(key)) is not type(value) or row.get(key) != value for key, value in expected.items()):
        raise GateFailure("native auth-loss custody or real Google recovery contract failed")
    counts = [row.get(key) for key in ("beforeDropDials", "preTapDials", "finalDials")]
    if any(type(n) is not int for n in counts) or not 0 < counts[0] < counts[1] <= counts[2]:
        raise GateFailure("the controlled stream drop never caused a new native SSH dial")
    if row.get("recoveryPhaseBeforeTap") not in ("lost", "connected", "live"):
        raise GateFailure("pre-tap automatic recovery state was not observed")
    for name in ("authloss-initial-terminal", "authloss-login-required", "authloss-unlock-cancelled", "authloss-recovered-terminal"):
        frame = artifacts / "screenshots" / f"{name}.png"
        if not frame.is_file() or frame.stat().st_size < 1024 or not frame.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise GateFailure(f"native auth-loss screenshot is missing or invalid: {name}")


def validate_onboarding_runtime(artifacts: Path) -> None:
    text=(artifacts/"logcat.txt").read_text(encoding="utf-8",errors="replace")
    for label,expected in (
        ("ONBOARDING_EXISTING",{"unlockGates":1,"createGates":0,"interactive":2,"wrongPhraseRefused":True,"accountFailureNoGate":True,"cloudWrites":0}),
        ("ONBOARDING_CREATED",{"epoch":3,"initializations":1,"initializeAttempts":2,"createGates":2,"unlockGates":1,"interactive":2,
                               "ciphertextPreserved":True,"plaintextUploaded":False,"emptyServers":True}),
    ):
        rows=[json.loads(line.split(label+" ",1)[1]) for line in text.splitlines() if label+" " in line]
        if len(rows)!=1 or any(type(rows[0].get(k)) is not type(v) or rows[0].get(k)!=v for k,v in expected.items()):
            raise GateFailure(f"the real native onboarding flow receipt is missing or contradicts {label}")
    if "ONBOARDING_VISIBLE_TERMINAL " not in text:
        raise GateFailure("the onboarding terminal's visible text was not retained")
    for name in ("onboarding-before-google","onboarding-account-unavailable","onboarding-wrong-phrase","onboarding-existing-terminal",
                 "onboarding-create-first-vault","onboarding-create-unavailable","onboarding-created-empty-servers","onboarding-created-vault-reopened",
                 "onboarding-existing-terminal-viewport"):
        frame=artifacts/"screenshots"/f"{name}.png"
        if not frame.is_file() or frame.stat().st_size<1024 or not frame.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
            raise GateFailure(f"native onboarding screenshot is missing or invalid: {name}")


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
    fp1 = fp2 = fpv = "SHA256:" + "v" * 43

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
        entry("authorize", "vault-authorize", fingerprint=fpv),
        entry("sshd-since", "vault-connect", connections=1, userauth=["Accepted"],
              accepted=[{"fingerprint": overrides.get("vault_fp", fpv)}]),
        entry("home-sessions", "trusted-before", count=overrides.get("home_before", 0), sessions=[]),
        entry("home-sessions", "trusted-after", count=overrides.get("home_after", 1), sessions=[{"workspace":"/home/testuser","alive":True}]),
        entry("authorize", "trusted-authorize", fingerprint=fpv),
        entry("sshd-since", "trusted-connect", connections=1, userauth=["Accepted"],
              accepted=[{"fingerprint": overrides.get("trusted_fp", fpv)}]),
        {"request": {"op": "host-file", "name": "gwlane-trusted-io-r.txt", "phase": "trusted-io"},
         "reply": {"ok": True, "name": "gwlane-trusted-io-r.txt", "content": overrides.get("trusted_content", "GWTRUST42_r\n")}},
    ]
    entries.extend([
        entry("authorize", "authloss-authorize", fingerprint=fpv),
        entry("sshd-since", "authloss-connect", connections=1, userauth=["Accepted"], accepted=[{"fingerprint":fpv}]),
        entry("drop", "authloss-recovery", dropped=True),
        entry("sshd-since", "authloss-recovered-connect", connections=1, userauth=["Accepted"],
              accepted=[{"fingerprint":overrides.get("authloss_fp", fpv)}]),
        {"request":{"op":"host-file","phase":"authloss-io","name":"gwlane-authloss-io-r.txt"},
         "reply":{"ok":True,"name":"gwlane-authloss-io-r.txt","content":overrides.get("authloss_content", "GWAUTH42_r\n"),
                  "exists":True,"type":"regular","readExitCode":0,"sizeBytes":11}},
    ])
    receipt = {"losses":3,"silentFailures":3,"interactive":4,"gatewayChallenges":1,"signOuts":0,"cloudWrites":0,"floor":1,
               "encryptedSlotsUnchanged":True,"protectedBytesSurviveLoss":not overrides.get("authloss_custody_bad",False),
               "rememberBytesUnchanged":True,"scopeStable":True,"beforeDropDials":1,"preTapDials":overrides.get("authloss_pre_tap_dials",2),"finalDials":3,
               "recoveryPhaseBeforeTap":"lost"}
    receipt["gatewayChallenges"] = overrides.get("authloss_gateway_challenges", 1)
    (directory.parent / "logcat.txt").write_text("AUTH_LOSS_CUSTODY " + json.dumps(receipt) + "\n")
    entries.extend([
        entry("authorize","onboarding-authorize",fingerprint=fpv),
        entry("sshd-since","onboarding-connect",connections=1,userauth=["Accepted"],accepted=[{"fingerprint":fpv}]),
        {"request":{"op":"host-file","phase":"onboarding-io","name":"gwlane-onboarding-io-r.txt"},
         "reply":{"ok":True,"name":"gwlane-onboarding-io-r.txt","content":overrides.get("onboarding_content","GWONBOARD42_r\n"),"exists":True,"type":"regular","readExitCode":0}},
    ])
    with (directory.parent/"logcat.txt").open("a") as log:
        log.write("ONBOARDING_EXISTING "+json.dumps({"unlockGates":overrides.get("onboarding_unlock_gates",1),"createGates":0,"interactive":2,
                  "wrongPhraseRefused":True,"accountFailureNoGate":True,"cloudWrites":0})+"\n")
        log.write("ONBOARDING_CREATED "+json.dumps({"epoch":3,"initializations":overrides.get("onboarding_initializations",1),"initializeAttempts":2,
                  "createGates":2,"unlockGates":1,"interactive":2,"ciphertextPreserved":True,"plaintextUploaded":False,"emptyServers":True})+"\n")
        log.write("ONBOARDING_VISIBLE_TERMINAL GWONBOARD42_r\n")
    shots = directory.parent / "screenshots"
    shots.mkdir(exist_ok=True)
    for name in ("authloss-initial-terminal", "authloss-login-required", "authloss-unlock-cancelled", "authloss-recovered-terminal"):
        (shots / f"{name}.png").write_bytes(b"\x89PNG\r\n\x1a\n" + b"x" * 1024)
    for name in ("onboarding-before-google","onboarding-account-unavailable","onboarding-wrong-phrase","onboarding-existing-terminal",
                 "onboarding-create-first-vault","onboarding-create-unavailable","onboarding-created-empty-servers","onboarding-created-vault-reopened",
                 "onboarding-existing-terminal-viewport"):
        (shots/f"{name}.png").write_bytes(b"\x89PNG\r\n\x1a\n"+b"x"*1024)
    if overrides.get("onboarding_viewport_missing"):
        (shots/"onboarding-existing-terminal-viewport.png").unlink()
    if overrides.get("authloss_shot_missing"):
        (shots / "authloss-unlock-cancelled.png").unlink()
    if overrides.get("authloss_receipt_missing"):
        (directory.parent / "logcat.txt").write_text("")
    if overrides.get("missing_trusted_file"):
        entries = [e for e in entries if e["request"].get("phase") != "trusted-io"]
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
        assert validate_results(r) == 7
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
    expect_fail("vault dial logged in with the phone key", host={"vault_fp": "SHA256:" + "b" * 43})
    expect_fail("trusted dial uses another key", host={"trusted_fp": "SHA256:" + "b" * 43})
    expect_fail("trusted terminal oracle missing", host={"missing_trusted_file": True})
    expect_fail("trusted terminal output wrong", host={"trusted_content": "GWTRUST42_foreign-run\n"})
    expect_fail("precreated HOME shell", host={"home_before": 1})
    expect_fail("no HOME shell created", host={"home_after": 0})
    expect_fail("exact new auth-loss method omitted", results={"methods":[m for m in sorted(REQUIRED_METHODS) if m != "recoversNativeAuthLossWithoutSignOutAndPreservesVaultTrustBeforeRealTerminal"]})
    expect_fail("no observed post-loss stream redial", host={"authloss_pre_tap_dials":1})
    expect_fail("auth-loss recovered with another key", host={"authloss_fp":"SHA256:" + "x" * 43})
    expect_fail("auth-loss terminal output belongs to another run", host={"authloss_content":"GWAUTH42_other-run\n"})
    expect_fail("auth-loss changed protected custody", host={"authloss_custody_bad":True})
    expect_fail("auth-loss unlock screenshot missing", host={"authloss_shot_missing":True})
    expect_fail("auth-loss receipt missing", host={"authloss_receipt_missing":True})
    expect_fail("gateway-owned loss not proven", host={"authloss_gateway_challenges":0})
    expect_fail("new onboarding method omitted",results={"methods":[m for m in sorted(REQUIRED_METHODS) if m!="onboardingCreatesMissingVaultAtomicallyInAppAndReopensItWithoutWeb"]})
    expect_fail("onboarding unlock duplicated",host={"onboarding_unlock_gates":2})
    expect_fail("onboarding vault created twice",host={"onboarding_initializations":2})
    expect_fail("onboarding terminal output from another run",host={"onboarding_content":"GWONBOARD42_other\n"})
    expect_fail("onboarding terminal viewport missing",host={"onboarding_viewport_missing":True})
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
