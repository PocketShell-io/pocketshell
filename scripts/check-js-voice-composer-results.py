#!/usr/bin/env python3
"""Require the packaged one-tap voice composer journey (#3128) and its host oracle.

--results-dir holds the run's JUnit XML (TEST-*.xml), the host's own record of
every byte the session's PTY received (host-bytes.hex, read by the runner over
its own SSH connection, not the app's), and the journey's screenshots
(j32-*.png). It passes only when:

  * J32VoiceComposerJourney's four tests each ran exactly once and passed (G3:
    no other count, no failure, error or skip);
  * the host received exactly INSERT_TEXT (no Enter), then SEND_TEXT plus one
    CR, and nothing else (the HOME-while-listening phase wrote nothing);
  * the reader's PTY took zero SIGWINCH over the whole lane (host-winch.txt):
    no remote resize from opening, dictating, closing or switching;
  * the one-tap, streaming, review, Insert, Send and after-HOME screenshots
    exist (the phone-layout cursor, Insert, Send, HOME and session-switch phases).
"""
import argparse
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

REQUIRED_CLASS = "com.pocketshell.app.smoke.J32VoiceComposerJourney"
REQUIRED_METHODS = frozenset(
    {
        "a1_oneTapListensStreamsPartialsUntilExplicitStopThenInsertsWithoutEnter",
        "a2_stopThenSendDeliversExactBytesWithEnter",
        "a3_homeWhileListeningStopsWithoutWriting",
        "a4_sessionSwitchWhileListeningStopsWithoutWriting",
    }
)
# Mirrors J32VoiceComposerJourney.INSERT_TEXT / SEND_TEXT.
INSERT_TEXT = "PS3128 insert check the deploy log"
SEND_TEXT = "PS3128 send the summary."
EXPECTED_HOST_BYTES = (INSERT_TEXT + SEND_TEXT + "\r").encode("utf-8")
SCREENSHOTS = (
    "j32-launcher",
    "j32-one-tap-listening",
    "j32-partials-streaming",
    "j32-review",
    "j32-inserted",
    "j32-closed",
    "j32-sent",
    "j32-after-home",
    "j32-after-switch",
)


def validate_junit(directory):
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"{directory}: no instrumentation reports")
    cases = []
    for path in reports:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise ValueError(f"{path}: missing testsuite")
        for suite in suites:
            rows = list(suite.findall("testcase"))
            if int(suite.attrib.get("tests", "-1")) != len(rows):
                raise ValueError(f"{path}: declared test count differs from testcase count")
            for attribute, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                if int(suite.attrib.get(attribute, "0")) != sum(len(list(c.iter(tag))) for c in rows):
                    raise ValueError(f"{path}: declared {attribute} differs from testcase status")
            cases.extend(rows)
    ran = Counter((c.attrib.get("classname"), c.attrib.get("name")) for c in cases)
    expected = Counter({(REQUIRED_CLASS, method): 1 for method in REQUIRED_METHODS})
    if ran != expected:
        raise ValueError(f"{directory}: expected exactly {sorted(m for _, m in expected)} once each, ran {dict(ran)}")
    bad = [c.attrib.get("name") for c in cases if any(list(c.iter(t)) for t in ("failure", "error", "skipped"))]
    if bad:
        raise ValueError(f"{directory}: failed or skipped: {bad}")


def validate_host_bytes(path):
    try:
        text = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise ValueError(f"{path}: the host byte record is missing: {error}")
    try:
        received = bytes.fromhex(text)
    except ValueError:
        raise ValueError(f"{path}: not hex: {text[:80]!r}")
    if received != EXPECTED_HOST_BYTES:
        raise ValueError(f"host PTY received {received!r}, expected exactly {EXPECTED_HOST_BYTES!r}")


def validate_host_winch(path):
    try:
        text = path.read_text(encoding="utf-8").strip()
    except OSError as error:
        raise ValueError(f"{path}: the host SIGWINCH count is missing: {error}")
    if text != "0":
        raise ValueError(f"the reader's PTY was resized {text!r} times during the lane; the voice composer must never resize it")


def validate_results(directory):
    validate_junit(directory)
    validate_host_bytes(directory / "host-bytes.hex")
    validate_host_winch(directory / "host-winch.txt")
    missing = [name for name in SCREENSHOTS if not (directory / f"{name}.png").is_file()]
    if missing:
        raise ValueError(f"{directory}: missing screenshots {missing}")


def _write_case(directory, methods, extra=None, declared=None):
    directory.mkdir(parents=True, exist_ok=True)
    suite = ET.Element("testsuite", tests=str(declared if declared is not None else len(methods)))
    for method in methods:
        case = ET.SubElement(suite, "testcase", classname=REQUIRED_CLASS, name=method)
        if extra and method == methods[0]:
            ET.SubElement(case, extra)
            suite.set({"failure": "failures", "error": "errors", "skipped": "skipped"}[extra], "1")
    ET.ElementTree(suite).write(directory / "TEST-j32.xml")


def self_test():
    methods = tuple(sorted(REQUIRED_METHODS))
    good = EXPECTED_HOST_BYTES.hex()
    probes = {
        "pass": dict(),
        "failure": dict(extra="failure"),
        "skipped": dict(extra="skipped"),
        "missing-test": dict(methods=methods[:3]),
        "duplicate-test": dict(methods=methods + methods[:1]),
        "count-mismatch": dict(declared=5),
        "no-junit": dict(nojunit=True),
        "enter-after-insert": dict(hexdata=(INSERT_TEXT + "\r" + SEND_TEXT + "\r").encode().hex()),
        "no-enter-on-send": dict(hexdata=(INSERT_TEXT + SEND_TEXT).encode().hex()),
        "home-wrote": dict(hexdata=good + "PS3128 never written".encode().hex()),
        "empty-host": dict(hexdata=""),
        "no-host-record": dict(nohost=True),
        "no-screenshot": dict(noshot=True),
        "resized": dict(winch="2"),
        "no-winch-record": dict(nowinch=True),
    }
    with tempfile.TemporaryDirectory(prefix="voice-composer-guard-") as temp:
        for name, probe in probes.items():
            d = Path(temp) / name
            d.mkdir()
            if not probe.get("nojunit"):
                _write_case(d, probe.get("methods", methods), probe.get("extra"), probe.get("declared"))
            if not probe.get("nowinch"):
                (d / "host-winch.txt").write_text(probe.get("winch", "0") + "\n", encoding="utf-8")
            if not probe.get("nohost"):
                (d / "host-bytes.hex").write_text(probe.get("hexdata", good) + "\n", encoding="utf-8")
            for shot in SCREENSHOTS:
                if probe.get("noshot") and shot == "j32-after-home":
                    continue
                (d / f"{shot}.png").write_bytes(b"png")
            try:
                validate_results(d)
                ok = True
            except (ValueError, OSError, ET.ParseError):
                ok = False
            if ok != (name == "pass"):
                raise SystemExit(f"FAIL: self-test probe {name} was {'accepted' if ok else 'rejected'}")
    print(f"PASS: {len(probes)}/{len(probes)} voice composer result guard checks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.results_dir is None:
        parser.error("--results-dir is required")
    validate_results(args.results_dir)
    print(f"PASS: {REQUIRED_CLASS} ran {len(REQUIRED_METHODS)}/{len(REQUIRED_METHODS)} tests and the host PTY "
          f"received exactly {EXPECTED_HOST_BYTES!r}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit("FAIL: " + str(error))
