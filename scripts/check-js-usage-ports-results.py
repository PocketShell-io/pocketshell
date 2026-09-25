#!/usr/bin/python3 -I
"""Fail closed unless the packaged JS usage/ports journey ran exactly once."""

from __future__ import annotations

import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.UsagePortsDockerJourneyTest"
REQUIRED_METHOD = "usageAndPortForwardingPoliciesUseDockerAndNativePlugin"
DEFAULT_RESULTS = Path("android/app/build/outputs/androidTest-results/connected/debug")


class GateFailure(ValueError):
    pass


def validate(results: Path) -> None:
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
                discovered.append((
                    class_name,
                    method_name,
                    bool(list(case.iter("failure")) or list(case.iter("error"))),
                    bool(list(case.iter("skipped"))),
                ))

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
    ET.ElementTree(suite).write(directory / "TEST-usage-ports.xml", encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    good = [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")]
    probes: list[tuple[str, list[tuple[str, str, str]] | None, bool]] = [
        ("exact packaged journey passes", good, True),
        ("missing result XML blocks", None, False),
        ("zero tests block", [], False),
        ("missing method blocks", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("unexpected class blocks", [("other.Journey", REQUIRED_METHOD, "passed")], False),
        ("duplicate journey blocks", good + good, False),
        ("skipped journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
        ("failed journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
    ]
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-usage-ports-results-") as scratch:
        root = Path(scratch)
        for index, (label, cases, expected) in enumerate(probes):
            report_dir = root / f"case-{index}"
            if cases is not None:
                write_report(report_dir, cases)
            try:
                validate(report_dir)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: usage/ports result guard probe {index + 1}: {label}", file=sys.stderr)
                return 1
            print(f"ok [{index + 1}/{len(probes)}] {label}")
    print("PASS: packaged usage/ports result guard checks (8/8)")
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
        print(f"FAIL: packaged JS usage/ports journey: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} executed exactly once")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
