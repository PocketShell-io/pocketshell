#!/usr/bin/env python3
"""Require the one real packaged Voice settings journey; reject vacuous reports."""
import argparse
from collections import Counter
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

REQUIRED_CLASS = "com.pocketshell.app.smoke.J24SettingsReachJourney"
REQUIRED_METHOD = "voiceLanguageAndSilenceControlsPersistAcrossNavigationAndActivityRelaunch"


def validate_results(directory):
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError("no instrumentation reports")
    cases = []
    for path in reports:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise ValueError("missing testsuite")
        for suite in suites:
            rows = list(suite.findall("testcase"))
            if int(suite.attrib.get("tests", "-1")) != len(rows):
                raise ValueError("declared test count differs from testcase count")
            for attribute, tag in [("failures", "failure"), ("errors", "error"), ("skipped", "skipped")]:
                if int(suite.attrib.get(attribute, "0")) != sum(len(list(case.iter(tag))) for case in rows):
                    raise ValueError("declared status differs from testcase status")
            cases.extend(rows)
    identities = Counter((case.attrib.get("classname"), case.attrib.get("name")) for case in cases)
    if identities != Counter({(REQUIRED_CLASS, REQUIRED_METHOD): 1}):
        raise ValueError("expected exactly the registered Voice settings journey")
    if any(list(case.iter(tag)) for case in cases for tag in ("failure", "error", "skipped")):
        raise ValueError("required Voice settings journey failed or skipped")
    return reports


def self_test():
    probes = ["pass", "missing", "zero", "wrong-class", "duplicate", "failure", "error", "skipped", "count-mismatch", "malformed"]
    with tempfile.TemporaryDirectory(prefix="voice-settings-guard-") as temp:
        for index, probe in enumerate(probes):
            directory = Path(temp) / str(index)
            directory.mkdir()
            if probe != "missing":
                suite = ET.Element("testsuite", tests="0" if probe == "zero" else "2" if probe in {"duplicate", "count-mismatch"} else "1")
                if probe != "zero":
                    for _ in range(2 if probe == "duplicate" else 1):
                        case = ET.SubElement(suite, "testcase", classname="Other" if probe == "wrong-class" else REQUIRED_CLASS, name=REQUIRED_METHOD)
                        if probe in {"failure", "error", "skipped"}:
                            ET.SubElement(case, probe)
                            suite.set({"failure": "failures", "error": "errors", "skipped": "skipped"}[probe], "1")
                ET.ElementTree(suite).write(directory / "TEST-voice.xml")
                if probe == "malformed":
                    (directory / "TEST-voice.xml").write_text("<broken")
            try:
                validate_results(directory)
                passed = True
            except (ValueError, OSError, ET.ParseError):
                passed = False
            if passed != (probe == "pass"):
                raise ValueError("self-test failed: " + probe)
    print(f"PASS: {len(probes)}/{len(probes)} Voice settings result guard checks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
    else:
        if args.results_dir is None:
            parser.error("--results-dir is required")
        validate_results(args.results_dir)
        print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} executed exactly once")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit("FAIL: " + str(error))
