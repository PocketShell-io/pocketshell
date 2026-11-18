#!/usr/bin/env python3
"""Require the exact packaged shared-app journeys (#2936, #2952, #2953, #2954, #2955, #3039, #3161) to execute and pass once each.

Also the host half of #2952's byte oracle: the raw-mode reader on the Docker
fixture writes the bytes it received as hex, the runner copies that file next
to the results (HOST_BYTES_NAME), and it must equal EXPECTED_IME_BYTES_HEX —
independently of what the journey read back from the terminal.

#3039: the session-end and client-drop journeys' phase traces (the Android
connection journal after the event) are kept for passing runs too, so a red
run always has a green one to compare against. Each must be present, a
non-empty JSON array on one logical connection, and free of `reconnecting` and
`lost` — the same verdict the journeys assert, re-read from the kept file.

#3161: J29's trust-prompt TUI on the Docker host records, as hex, the DECRPM
answers the phone's terminal sent back to its DECRQM queries; the runner reads
that file over its own SSH connection (PS3161_HOST_NAME). It must hold one
well-formed answer per query, so the DECRQM handler that threw in the shipped
bundle provably ran — independently of what the journey read on screen.

#3191: the composer-attach journeys (J31) record what they picked; the runner
re-reads the host over its OWN SSH connection (ATTACHMENT_PROOF_NAME): the
three generated Pixel-like photos (627,672 / 667,110 / 1,700,000 bytes, all
over the editor's 512 KiB limit) must be on the host byte-exact, the
cancelled upload must have left nothing (its `.part` was seen mid-flight),
and the over-limit file must never have been written.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.J01ConnectAndTrustJourney"
METHOD_CLASSES = {'aThreeFileUploadShowsTheBarMidFlightAndLeavesNoResidueAfterCompletion': 'com.pocketshell.app.smoke.J20ComposerUploadProgressJourney',
 'pickedPhonePhotosLandByteExactOnTheHost': 'com.pocketshell.app.smoke.J31ComposerAttachPhotosJourney',
 'overLimitFileShowsAReadableChipErrorWithoutADiagnostic': 'com.pocketshell.app.smoke.J31ComposerAttachPhotosJourney',
 'cancellingMidUploadLeavesNothingOnTheHost': 'com.pocketshell.app.smoke.J31ComposerAttachPhotosJourney',
 'androidShareStreamsReachTheChosenSessionOnTheRealHost': 'com.pocketshell.app.smoke.J11ShareUploadJourney',
 'androidSingleShareReachesTheChosenSessionOnTheRealHost': 'com.pocketshell.app.smoke.J11ShareUploadJourney',
 'cdInsideOpenShellKeepsTheTerminalMounted': 'com.pocketshell.app.smoke.J28CdKeepsTerminalJourney',
 'androidBackWalksSubviewsThenHostListThenBackgrounds': 'com.pocketshell.app.smoke.J25AndroidBackJourney',
 'changedHostKeyIsRefusedWithVisibleMessage': 'com.pocketshell.app.smoke.J01ConnectAndTrustJourney',
 'firstContactHostKeyAsksAndHonoursRejectOnceAndAlways': 'com.pocketshell.app.smoke.J01ConnectAndTrustJourney',
 'hostAddedThroughFormConnectsToFixture': 'com.pocketshell.app.smoke.J21HostAddConnectJourney',
 'guestSshConnectsWithoutGoogleOrVaultAndTypesIntoRealHost': 'com.pocketshell.app.smoke.J21HostAddConnectJourney',
 'hostFormSubmitRemainsReachableWithTheIme': 'com.pocketshell.app.smoke.J19HostFormImeJourney',
 'scrollTerminalDoesNotSendInputBytes': 'com.pocketshell.app.smoke.J15TerminalScrollJourney',
 'sharedAppListsAttachesAndTypesIntoFixtureSession': 'com.pocketshell.app.smoke.J02SessionTreeListJourney',
 'sharedAppRecoversAbruptServerDropWithOneControllerReconnect': 'com.pocketshell.app.smoke.J05ReconnectAfterDropJourney',
 'sharedAppReportsARealSessionEndWithoutReconnecting': 'com.pocketshell.app.smoke.J14StopSessionJourney',
 'sharedAppSilentlyRejoinsWhenOnlyTheAttachClientDies': 'com.pocketshell.app.smoke.J05ReconnectAfterDropJourney',
 'sharedAppSwitchesSessionTabsKeepingEachTerminal': 'com.pocketshell.app.smoke.J02SessionTreeListJourney',
 'sharedTerminalDeliversImeEditsAsExactBytes': 'com.pocketshell.app.smoke.J03AttachAndTypeJourney',
 'stopSessionFromTabKillsItOnTheHost': 'com.pocketshell.app.smoke.J14StopSessionJourney',
 'tunnelFormSubmitRemainsReachableWithTheIme': 'com.pocketshell.app.smoke.J19HostFormImeJourney',
 'newSessionFolderPickerAndFilesEditWorkOnTheRealHost': 'com.pocketshell.app.smoke.J27NewSessionAndFilesJourney',
 'antigravityTrustPromptRedrawsWithoutErrorOrStall': 'com.pocketshell.app.smoke.J29TuiRedrawNoDiagJourney',
 'diagToastClearsTheStatusBarWithPlainWordsAndCopyDetails': 'com.pocketshell.app.smoke.J29TuiRedrawNoDiagJourney'}
REQUIRED_METHODS = frozenset(METHOD_CLASSES)
# #3017: selection regressions supplement, never replace, the seven real journeys.
REGRESSION_CLASS = "com.pocketshell.app.ime.ScriptedImeSelectionTest"
REGRESSION_METHODS = frozenset({
    "cleanSelectionNeedsNoRebind",
    "observedStockSelectionGetsExactlyOneRebind",
    "delayedDriftRecoversBeforeOriginalDeadline",
    "persistentDriftFailsWithinOriginalDeadline",
    "conflictingSettingsAndMethodNeverPass",
    "driftAfterDeadlineCannotExtendSelectionBudget",
    "slowRebindCommandsCannotCreateANewDeadline",
})
HOST_BYTES_NAME = "host-ime-bytes.hex"
# #3191: written by scripts/connected-js-shared-app.sh from its own SSH reads.
ATTACHMENT_PROOF_NAME = "ps3191-host-proof.json"
# The maintainer's two failing photos and the third 1.67 MB pick, in pick order.
ATTACHMENT_PHOTO_SIZES = (627_672, 667_110, 1_700_000)
EDITOR_READ_LIMIT = 512 * 1024
# #3039: kept by the journeys, pulled by scripts/connected-js-shared-app.sh.
PHASE_TRACE_NAMES = ("ps3039-journal-session-end.json", "ps3039-journal-client-drop.json")
FORBIDDEN_PHASES = frozenset({"reconnecting", "lost"})
# SharedAppDockerJourneyTest.EXPECTED_IME_BYTES, as the host must have received it.
EXPECTED_IME_BYTES = (
    "echo "
    "word\x7fk "
    "teh\x7f\x7fhe "
    "\x7fn "
    "ls -la"
    "\r"
    "a1b"
    "ok"
    "\t\r"
    "PASTE1"
)
EXPECTED_IME_BYTES_HEX = EXPECTED_IME_BYTES.encode("utf-8").hex()
# #3161: tests/docker/tui/ps3161-trust-prompt-tui.py asks these modes, in this order.
PS3161_HOST_NAME = "ps3161-host-decrpm.hex"
PS3161_QUERIED_MODES = ("?2026", "?2004", "?1049", "4")
DEFAULT_RESULTS = Path("android/app/build/outputs/androidTest-results/connected/debug")


class GateFailure(ValueError):
    pass


def _integer(node: ET.Element, name: str, report: Path) -> int:
    try:
        value = int(node.attrib.get(name, "0"))
    except ValueError as error:
        raise GateFailure(f"{report}: invalid {name} count") from error
    if value < 0:
        raise GateFailure(f"{report}: negative {name} count")
    return value


def _suite_cases(suite: ET.Element, report: Path) -> list[ET.Element]:
    cases = list(suite.findall("testcase"))
    declared = _integer(suite, "tests", report)
    if declared != len(cases):
        raise GateFailure(f"{report}: declares {declared} tests but contains {len(cases)} cases")
    for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        reported = _integer(suite, summary, report)
        actual = sum(1 for case in cases for child in case.iter(tag))
        if reported != actual:
            raise GateFailure(f"{report}: declares {reported} {summary} but contains {actual}")
    return cases


def validate_results(results: Path) -> list[Path]:
    if not results.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results}")
    reports = sorted(results.rglob("TEST-*.xml"))
    if not reports:
        raise GateFailure(f"no TEST-*.xml instrumentation results found under {results}")

    cases: list[ET.Element] = []
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            raise GateFailure(f"could not parse {report}: {error}") from error
        if root.tag == "testsuite":
            cases.extend(_suite_cases(root, report))
        elif root.tag == "testsuites":
            suites = list(root.findall("testsuite"))
            if not suites:
                raise GateFailure(f"{report}: testsuites report contains no testsuite")
            suite_cases = [case for suite in suites for case in _suite_cases(suite, report)]
            for summary, tag in (("tests", None), ("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                reported = _integer(root, summary, report)
                actual = len(suite_cases) if tag is None else sum(
                    1 for case in suite_cases for child in case.iter(tag)
                )
                if reported != actual:
                    raise GateFailure(f"{report}: root declares {reported} {summary} but suites contain {actual}")
            cases.extend(suite_cases)
        else:
            raise GateFailure(f"{report}: expected <testsuite> or <testsuites>, found <{root.tag}>")

    actual = Counter((case.attrib.get("classname", ""), case.attrib.get("name", "")) for case in cases)
    expected = Counter({(METHOD_CLASSES[method], method): 1 for method in REQUIRED_METHODS})
    expected.update({(REGRESSION_CLASS, method): 1 for method in REGRESSION_METHODS})
    if actual != expected:
        raise GateFailure(
            f"expected exactly {sorted(expected.elements())}; found {sorted(actual.elements())}"
        )

    for case in cases:
        name = f"{case.attrib.get('classname', '')}#{case.attrib.get('name', '')}"
        if list(case.iter("failure")) or list(case.iter("error")):
            raise GateFailure(f"{name} failed")
        if list(case.iter("skipped")):
            raise GateFailure(f"{name} was skipped")
    return reports


def validate_host_bytes(path: Path) -> None:
    if not path.is_file():
        raise GateFailure(f"the host reader's byte record is missing: {path}")
    recorded = path.read_text(encoding="utf-8").strip()
    if recorded != EXPECTED_IME_BYTES_HEX:
        try:
            decoded = repr(bytes.fromhex(recorded).decode("utf-8", "replace"))
        except ValueError:
            decoded = "<not hex>"
        raise GateFailure(
            f"the host received {decoded} ({recorded}); expected {EXPECTED_IME_BYTES!r} ({EXPECTED_IME_BYTES_HEX})"
        )


def validate_3161_host(path: Path) -> None:
    """#3161: the host received one DECRPM answer per DECRQM query, for the queried modes, in order."""
    if not path.is_file():
        raise GateFailure(f"the #3161 host DECRPM record is missing: {path}")
    try:
        answers = bytes.fromhex(path.read_text(encoding="utf-8").strip()).decode("ascii")
    except (OSError, ValueError) as error:
        raise GateFailure(f"the #3161 host DECRPM record is unreadable: {error}") from error
    found = re.findall(r"\x1b\[(\??\d+);([0-4])\$y", answers)
    modes = tuple(mode for mode, _value in found)
    if modes != PS3161_QUERIED_MODES:
        raise GateFailure(
            f"the terminal answered DECRQM for {modes!r} ({answers!r}); expected one answer for each of {PS3161_QUERIED_MODES!r}"
        )


def validate_guest_host(path: Path) -> None:
    if not path.is_file():
        raise GateFailure(f"the independent guest SSH host proof is missing: {path}")
    try:proof=json.loads(path.read_text(encoding="utf-8"))
    except (OSError,ValueError) as error:raise GateFailure(f"the guest SSH host proof is unreadable: {error}") from error
    if not isinstance(proof,dict):raise GateFailure("guest SSH host proof is not an object")
    run=proof.get("run")
    if not isinstance(run,str) or not run or proof.get("user")!="testuser" or proof.get("content","").strip()!="PSGUEST42_"+run:
        raise GateFailure("guest SSH file content/user does not match this runner's host proof")


def validate_j27_host(path: Path) -> None:
    """#3105: the runner's own SSH read of what the phone's New session and Files journey left on the host."""
    if not path.is_file():
        raise GateFailure(f"the independent J27 host proof is missing: {path}")
    try:
        proof = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise GateFailure(f"the J27 host proof is unreadable: {error}") from error
    run = proof.get("run") if isinstance(proof, dict) else None
    if not isinstance(run, str) or not run:
        raise GateFailure("the J27 host proof has no run id")
    if proof.get("notes", "").strip() != f"edited-on-phone-{run}":
        raise GateFailure("the file edited in the phone's Files tab does not hold the saved edit on the host")
    if proof.get("terminal", "").strip() != f"PS3105_42_{run}":
        raise GateFailure("the new session's terminal did not write its output proof on the host")
    sessions = proof.get("sessions")
    rows = [r for r in sessions if isinstance(r, dict) and str(r.get("workspace", "")).endswith(f"/{run}-j27/proj-one")] if isinstance(sessions, list) else []
    if len(rows) != 1 or rows[0].get("phase") != "running" or rows[0].get("alive") is not True:
        raise GateFailure("the host has no single running session in the folder picked in New session")


def validate_attachment_host(path: Path) -> None:
    """#3191: the runner's own SSH read of what the composer-attach journeys left on the host."""
    if not path.is_file():
        raise GateFailure(f"the independent #3191 attachment host proof is missing: {path}")
    try:
        proof = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise GateFailure(f"the #3191 attachment host proof is unreadable: {error}") from error
    if not isinstance(proof, dict):
        raise GateFailure("the #3191 attachment host proof is not an object")
    photos = proof.get("photos")
    files = photos.get("files") if isinstance(photos, dict) else None
    token = photos.get("token") if isinstance(photos, dict) else None
    if not isinstance(files, list) or not isinstance(token, str) or not token:
        raise GateFailure("the #3191 proof has no picked photos")
    if tuple(f.get("size") for f in files if isinstance(f, dict)) != ATTACHMENT_PHOTO_SIZES:
        raise GateFailure(f"the picked photos must be {ATTACHMENT_PHOTO_SIZES} bytes, in order")
    if not all(size > EDITOR_READ_LIMIT for size in ATTACHMENT_PHOTO_SIZES):
        raise GateFailure("every photo must exceed the editor's 512 KiB limit to reproduce #3191")
    for item in files:
        remote = str(item.get("remotePath", ""))
        if token not in str(item.get("name", "")) or "/.pocketshell/attachments/" not in remote:
            raise GateFailure(f"photo {item.get('name')} was not staged in this run's attachment directory")
        if item.get("hostSize") != item.get("size") or item.get("hostSha256") != item.get("sha256") or not item.get("sha256"):
            raise GateFailure(f"the host does not hold {item.get('name')} byte-exact")
    cancelled = proof.get("cancelled")
    if not isinstance(cancelled, dict) or not cancelled.get("token"):
        raise GateFailure("the #3191 proof has no cancelled upload")
    seen = str(cancelled.get("partSeenMidFlight", ""))
    if cancelled["token"] not in seen or not seen.endswith(".part"):
        raise GateFailure("the cancelled upload's .part file was not seen mid-flight")
    if cancelled.get("hostMatches") != []:
        raise GateFailure(f"the cancelled upload left files on the host: {cancelled.get('hostMatches')}")
    over = proof.get("overLimit")
    if not isinstance(over, dict) or not over.get("name") or not isinstance(over.get("hostMatches"), list):
        raise GateFailure("the #3191 proof has no over-limit pick")
    if any(str(over["name"]) in str(match) for match in over["hostMatches"]):
        raise GateFailure("the over-limit file reached the host")


def validate_guest_viewport(directory: Path) -> None:
    frame=directory/"guest-terminal-viewport.png"
    if not frame.is_file() or frame.stat().st_size<1024 or not frame.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"):
        raise GateFailure("the real guest terminal viewport screenshot is missing or invalid")


def validate_phase_traces(results: Path) -> list[Path]:
    traces = []
    for name in PHASE_TRACE_NAMES:
        path = results / name
        if not path.is_file():
            raise GateFailure(f"the #3039 phase trace is missing: {path}")
        try:
            entries = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise GateFailure(f"{path}: not readable JSON: {error}") from error
        if not isinstance(entries, list) or not entries or not all(isinstance(e, dict) for e in entries):
            raise GateFailure(f"{path}: expected a non-empty JSON array of journal entries")
        connections = {entry.get("connectionId") for entry in entries}
        if len(connections) != 1 or None in connections:
            raise GateFailure(f"{path}: expected one logical connection, found {sorted(map(str, connections))}")
        bad = [entry for entry in entries if entry.get("phase") in FORBIDDEN_PHASES]
        if bad:
            raise GateFailure(f"{path}: the trace reconnects: {bad[0]}")
        traces.append(path)
    return traces


def _write_report(path: Path, identities: list[tuple[str, str, str]]) -> None:
    failures = sum(status == "failed" for _class_name, _method, status in identities)
    skipped = sum(status == "skipped" for _class_name, _method, status in identities)
    suite = ET.Element(
        "testsuite",
        tests=str(len(identities)),
        failures=str(failures),
        errors="0",
        skipped=str(skipped),
    )
    for class_name, method, status in identities:
        case = ET.SubElement(suite, "testcase", classname=class_name, name=method)
        if status == "failed":
            ET.SubElement(case, "failure")
        elif status == "skipped":
            ET.SubElement(case, "skipped")
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    every = [(METHOD_CLASSES[method], method, "passed") for method in sorted(REQUIRED_METHODS)]
    regressions = [(REGRESSION_CLASS, method, "passed") for method in sorted(REGRESSION_METHODS)]
    every += regressions
    good_bytes = EXPECTED_IME_BYTES_HEX
    duplicated = ("echo PS2 PS936".encode() + EXPECTED_IME_BYTES.encode()).hex()
    good_trace = [{"connectionId": "android-1", "phase": "connected", "transportId": "t-1"}]
    reconnecting_trace = good_trace + [{"connectionId": "android-1", "phase": "reconnecting", "transportId": None}]
    two_connections = good_trace + [{"connectionId": "android-2", "phase": "connected", "transportId": "t-2"}]
    trace_cases = [
        ("every phase trace present and clean passes", {}, True),
        *(
            (f"a missing {name} blocks", {name: None}, False)
            for name in PHASE_TRACE_NAMES
        ),
        *(
            (f"a reconnecting {name} blocks", {name: reconnecting_trace}, False)
            for name in PHASE_TRACE_NAMES
        ),
        ("an empty phase trace blocks", {PHASE_TRACE_NAMES[0]: []}, False),
        ("a trace across two logical connections blocks", {PHASE_TRACE_NAMES[1]: two_connections}, False),
        ("an unreadable phase trace blocks", {PHASE_TRACE_NAMES[0]: "{not json"}, False),
    ]
    cases = [
        ("every exact passing shared-app journey passes", every, good_bytes, True),
        ("zero tests block", [], good_bytes, False),
        *(
            (f"a missing {method} blocks", [row for row in every if row[1] != method], good_bytes, False)
            for method in sorted(REQUIRED_METHODS)
        ),
        *(
            (f"a failed {method} blocks", [(c, m, "failed" if m == method else s) for c, m, s in every], good_bytes, False)
            for method in sorted(REQUIRED_METHODS)
        ),
        *(
            (f"a missing selection regression {method} blocks",
             [row for row in every if row[1] != method], good_bytes, False)
            for method in sorted(REGRESSION_METHODS)
        ),
        *(
            (f"a failed selection regression {method} blocks",
             [(c, m, "failed" if m == method else s) for c, m, s in every], good_bytes, False)
            for method in sorted(REGRESSION_METHODS)
        ),
        ("selection regressions alone cannot replace real journeys", regressions, good_bytes, False),
        ("real journeys alone cannot replace selection regressions",
         [row for row in every if row[0] != REGRESSION_CLASS], good_bytes, False),
        ("skipped selection regression blocks",
         [(c, m, "skipped" if m == regressions[0][1] else s) for c, m, s in every], good_bytes, False),
        ("unexpected class blocks", [("example.OtherJourney", every[0][1], "passed"), *every[1:]], good_bytes, False),
        ("extra test blocks", every + [(REQUIRED_CLASS, "extra", "passed")], good_bytes, False),
        ("duplicate test blocks", every + [every[0]], good_bytes, False),
        ("skipped journey blocks", [(every[0][0], every[0][1], "skipped"), *every[1:]], good_bytes, False),
        ("missing host byte record blocks", every, None, False),
        ("duplicated host bytes block", every, duplicated, False),
        ("empty host byte record blocks", every, "", False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-shared-app-results-") as temporary:
        root = Path(temporary)
        for index, (label, identities, host_bytes, expected_pass) in enumerate(cases):
            results = root / str(index)
            results.mkdir(parents=True)
            if identities:
                _write_report(results / "TEST-shared-app.xml", identities)
            if host_bytes is not None:
                (results / HOST_BYTES_NAME).write_text(host_bytes + "\n", encoding="utf-8")
            try:
                validate_results(results)
                validate_host_bytes(results / HOST_BYTES_NAME)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected_pass:
                failures += 1
                print(f"FAIL: {label}", file=sys.stderr)
            else:
                print(f"ok: {label}")
        for index, (label, overrides, expected_pass) in enumerate(trace_cases):
            results = root / f"trace-{index}"
            results.mkdir(parents=True)
            for name in PHASE_TRACE_NAMES:
                content = overrides.get(name, good_trace)
                if content is None:
                    continue
                text = content if isinstance(content, str) else json.dumps(content)
                (results / name).write_text(text, encoding="utf-8")
            try:
                validate_phase_traces(results)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected_pass:
                failures += 1
                print(f"FAIL: {label}", file=sys.stderr)
            else:
                print(f"ok: {label}")
        guest_cases=[("guest host content matches this run",{"run":"fixture-run","user":"testuser","content":"PSGUEST42_fixture-run\n"},True),
                     ("guest file from another run fails",{"run":"fixture-run","user":"testuser","content":"PSGUEST42_foreign\n"},False),
                     ("guest proof missing fails",None,False)]
        for index,(label,proof,expected_pass) in enumerate(guest_cases):
            path=root/f"guest-{index}.json"
            if proof is not None:path.write_text(json.dumps(proof),encoding="utf-8")
            try:validate_guest_host(path);passed=True
            except GateFailure:passed=False
            if passed!=expected_pass:failures+=1;print(f"FAIL: {label}",file=sys.stderr)
            else:print(f"ok: {label}")
        good_sessions = [{"workspace": "/home/testuser/r-j27/proj-one", "phase": "running", "alive": True}]
        j27_good = {"run": "r", "notes": "edited-on-phone-r\n", "terminal": "PS3105_42_r\n", "sessions": good_sessions}
        j27_cases = [
            ("J27 host proof passes when every oracle matches", j27_good, True),
            ("J27 unsaved file fails", {**j27_good, "notes": "before-edit\n"}, False),
            ("J27 missing terminal output fails", {**j27_good, "terminal": ""}, False),
            ("J27 session in another folder fails", {**j27_good, "sessions": [{**good_sessions[0], "workspace": "/home/testuser/r-j27/proj-two"}]}, False),
            ("J27 dead session fails", {**j27_good, "sessions": [{**good_sessions[0], "alive": False}]}, False),
            ("J27 proof missing fails", None, False),
        ]
        for index, (label, proof, expected_pass) in enumerate(j27_cases):
            path = root / f"j27-{index}.json"
            if proof is not None:
                path.write_text(json.dumps(proof), encoding="utf-8")
            try:
                validate_j27_host(path)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected_pass:
                failures += 1
                print(f"FAIL: {label}", file=sys.stderr)
            else:
                print(f"ok: {label}")
        good_3161 = "\x1b[?2026;2$y\x1b[?2004;1$y\x1b[?1049;1$y\x1b[4;2$y"
        cases_3161 = [
            ("#3161 one answer per DECRQM query passes", good_3161.encode().hex(), True),
            ("#3161 no answer (the handler threw) fails", "", False),
            ("#3161 a missing answer fails", good_3161[: good_3161.rindex("\x1b")].encode().hex(), False),
            ("#3161 answers for other modes fail", good_3161.replace("2026", "2027").encode().hex(), False),
            ("#3161 record missing fails", None, False),
        ]
        for index, (label, record, expected_pass) in enumerate(cases_3161):
            path = root / f"ps3161-{index}.hex"
            if record is not None:
                path.write_text(record + "\n", encoding="utf-8")
            try:
                validate_3161_host(path)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected_pass:
                failures += 1
                print(f"FAIL: {label}", file=sys.stderr)
            else:
                print(f"ok: {label}")
        photo_files = [
            {"name": f"PXL_t1_{index + 1}.jpg", "size": size, "sha256": f"{index}" * 64,
             "remotePath": f"/home/testuser/.pocketshell/attachments/s/20261118-101500-0{index + 1}-PXL_t1_{index + 1}.jpg",
             "hostSize": size, "hostSha256": f"{index}" * 64}
            for index, size in enumerate(ATTACHMENT_PHOTO_SIZES)
        ]
        attach_good = {
            "photos": {"token": "t1", "files": photo_files},
            "cancelled": {"token": "c1", "partSeenMidFlight": "/home/testuser/.pocketshell/attachments/s/x-VID_c1.mp4.part", "hostMatches": []},
            "overLimit": {"token": "o1", "name": "VID_o1.jpg", "hostMatches": ["/home/testuser/.pocketshell/attachments/s/x-PXL_o1_ok.jpg"]},
        }
        def altered(section: str, **changes: object) -> dict:
            return {**attach_good, section: {**attach_good[section], **changes}}
        attach_cases = [
            ("#3191 proof passes when every host read matches", attach_good, True),
            ("#3191 a truncated photo on the host fails",
             altered("photos", files=[{**photo_files[0], "hostSize": 524_288}, *photo_files[1:]]), False),
            ("#3191 a re-encoded photo on the host fails",
             altered("photos", files=[*photo_files[:2], {**photo_files[2], "hostSha256": "f" * 64}]), False),
            ("#3191 photos under the editor limit cannot stand in",
             altered("photos", files=[{**f, "size": 1000, "hostSize": 1000} for f in photo_files]), False),
            ("#3191 a missing photo fails", altered("photos", files=photo_files[:2]), False),
            ("#3191 a cancelled upload left on the host fails",
             altered("cancelled", hostMatches=["/home/testuser/.pocketshell/attachments/s/x-VID_c1.mp4.part"]), False),
            ("#3191 a cancel with no .part seen mid-flight fails", altered("cancelled", partSeenMidFlight=""), False),
            ("#3191 an over-limit file on the host fails",
             altered("overLimit", hostMatches=["/home/testuser/.pocketshell/attachments/s/x-VID_o1.jpg"]), False),
            ("#3191 proof missing fails", None, False),
        ]
        for index, (label, proof, expected_pass) in enumerate(attach_cases):
            path = root / f"attach-{index}.json"
            if proof is not None:
                path.write_text(json.dumps(proof), encoding="utf-8")
            try:
                validate_attachment_host(path)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected_pass:
                failures += 1
                print(f"FAIL: {label}", file=sys.stderr)
            else:
                print(f"ok: {label}")
    total = len(cases) + len(trace_cases)+len(guest_cases)+len(j27_cases)+len(cases_3161)+len(attach_cases)
    print(f"Shared-app result guard self-test: {total - failures}/{total} checks passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument(
        "--host-bytes",
        type=Path,
        help=f"the host reader's hex record (default: <results-dir>/{HOST_BYTES_NAME})",
    )
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--guest-host-proof", type=Path)
    parser.add_argument("--j27-host-proof", type=Path)
    parser.add_argument("--attachment-host-proof", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    host_bytes = args.host_bytes or args.results_dir / HOST_BYTES_NAME
    try:
        reports = validate_results(args.results_dir)
        validate_host_bytes(host_bytes)
        validate_guest_host(args.guest_host_proof or args.results_dir / "guest-host-proof.json")
        j27_proof = args.j27_host_proof or args.results_dir / "j27-host-proof.json"
        validate_j27_host(j27_proof)
        host_3161 = args.results_dir / PS3161_HOST_NAME
        validate_3161_host(host_3161)
        attachment_proof = args.attachment_host_proof or args.results_dir / ATTACHMENT_PROOF_NAME
        validate_attachment_host(attachment_proof)
        validate_guest_viewport(args.evidence_dir or args.results_dir)
        traces = validate_phase_traces(args.results_dir)
        if args.evidence_dir:
            args.evidence_dir.mkdir(parents=True, exist_ok=True)
            for report in reports:
                shutil.copy2(report, args.evidence_dir / report.name)
            for trace in traces:
                if trace.resolve() != (args.evidence_dir / trace.name).resolve():
                    shutil.copy2(trace, args.evidence_dir / trace.name)
            guest_proof=args.guest_host_proof or args.results_dir / "guest-host-proof.json"
            if guest_proof.resolve() != (args.evidence_dir / guest_proof.name).resolve():
                shutil.copy2(guest_proof,args.evidence_dir / guest_proof.name)
            if j27_proof.resolve() != (args.evidence_dir / j27_proof.name).resolve():
                shutil.copy2(j27_proof, args.evidence_dir / j27_proof.name)
            if host_3161.resolve() != (args.evidence_dir / PS3161_HOST_NAME).resolve():
                shutil.copy2(host_3161, args.evidence_dir / PS3161_HOST_NAME)
            if attachment_proof.resolve() != (args.evidence_dir / attachment_proof.name).resolve():
                shutil.copy2(attachment_proof, args.evidence_dir / attachment_proof.name)
            if host_bytes.resolve() != (args.evidence_dir / HOST_BYTES_NAME).resolve():
                shutil.copy2(host_bytes, args.evidence_dir / HOST_BYTES_NAME)
    except GateFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {len(METHOD_CLASSES)} registered feature methods and {len(REGRESSION_METHODS)} IME regressions each executed exactly once")
    print(f"PASS: {REGRESSION_CLASS}#{{{', '.join(sorted(REGRESSION_METHODS))}}} each executed exactly once")
    print(f"PASS: the host received exactly the scripted IME bytes ({EXPECTED_IME_BYTES_HEX})")
    print(f"PASS: #3039 phase traces kept and free of reconnects: {', '.join(PHASE_TRACE_NAMES)}")
    print(f"PASS: #3161 the host received a DECRPM answer for each of {', '.join(PS3161_QUERIED_MODES)}")
    print("PASS: #3191 picked photos are byte-exact on the host; the cancelled and over-limit files left nothing")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
