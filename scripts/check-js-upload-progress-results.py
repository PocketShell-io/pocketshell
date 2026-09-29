#!/usr/bin/python3 -I
"""Require the exact packaged J20 upload-progress journey to pass."""

from __future__ import annotations

import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.J20ComposerUploadProgressJourney"
REQUIRED_METHOD = "aThreeFileUploadShowsTheBarMidFlightAndLeavesNoResidueAfterCompletion"
DEFAULT_RESULTS_DIR = Path("android/app/build/outputs/js-upload-progress/junit")


class GateFailure(ValueError):
    """The required packaged progress journey is absent or did not pass."""


def validate_results(results_dir: Path) -> None:
    if not results_dir.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results_dir}")
    reports = sorted(results_dir.rglob("TEST-*.xml"))
    if not reports:
        raise GateFailure(f"no TEST-*.xml instrumentation reports found under {results_dir}")

    discovered: list[tuple[str, str, ET.Element]] = []
    declared_total = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except (OSError, ET.ParseError) as error:
            raise GateFailure(f"could not parse instrumentation report {report}: {error}") from error
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise GateFailure(f"{report}: expected a <testsuite> report")
        file_tests = 0
        for suite in suites:
            cases = list(suite.findall("testcase"))
            try:
                declared = int(suite.attrib["tests"])
            except (KeyError, ValueError) as error:
                raise GateFailure(f"{report}: suite is missing a valid tests count") from error
            if declared != len(cases):
                raise GateFailure(f"{report}: suite declares {declared} tests but contains {len(cases)} cases")
            file_tests += declared
            declared_total += declared
            for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                try:
                    reported = int(suite.attrib.get(summary, "0"))
                except ValueError as error:
                    raise GateFailure(f"{report}: invalid {summary} count") from error
                actual = sum(1 for case in cases for _ in case.iter(tag))
                if reported != actual:
                    raise GateFailure(f"{report}: {summary} says {reported}, testcase details show {actual}")
            for case in cases:
                class_name = case.attrib.get("classname", "")
                method_name = case.attrib.get("name", "")
                if not class_name or not method_name:
                    raise GateFailure(f"{report}: testcase is missing classname or name")
                discovered.append((class_name, method_name, case))
        if root.tag == "testsuites" and "tests" in root.attrib:
            try:
                aggregate = int(root.attrib["tests"])
            except ValueError as error:
                raise GateFailure(f"{report}: invalid <testsuites> test count") from error
            if aggregate != file_tests:
                raise GateFailure(f"{report}: <testsuites> declares {aggregate} tests but contains {file_tests}")

    actual = [(class_name, method_name) for class_name, method_name, _ in discovered]
    expected = [(REQUIRED_CLASS, REQUIRED_METHOD)]
    if actual != expected or declared_total != 1:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {actual or '<zero tests>'}")
    case = discovered[0][2]
    if list(case.iter("failure")) or list(case.iter("error")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} failed")
    if list(case.iter("skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} was skipped")


def _write_report(directory: Path, cases: list[tuple[str, str, str]]) -> None:
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
    ET.ElementTree(suite).write(directory / "TEST-j20-upload-progress.xml", encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    good = [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")]
    probes: list[tuple[str, list[tuple[str, str, str]] | None, bool]] = [
        ("exact named journey passes", good, True),
        ("missing reports block", None, False),
        ("zero tests block", [], False),
        ("missing required method blocks", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("wrong class blocks", [("com.pocketshell.app.smoke.OtherJourney", REQUIRED_METHOD, "passed")], False),
        ("extra method blocks", good + [(REQUIRED_CLASS, "extraMethod", "passed")], False),
        ("duplicate method blocks", good + good, False),
        ("failed journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
        ("skipped journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-upload-progress-results-") as scratch:
        root = Path(scratch)
        for index, (label, cases, expected) in enumerate(probes):
            report_dir = root / f"case-{index}"
            if cases is not None:
                _write_report(report_dir, cases)
            try:
                validate_results(report_dir)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                failures += 1
                print(f"FAIL: J20 result guard probe {index + 1}: {label}", file=sys.stderr)
            else:
                print(f"PASS: J20 result guard probe {index + 1}: {label}")
    print(f"{'FAIL' if failures else 'PASS'}: J20 result guard self-test ({len(probes) - failures}/{len(probes)})")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        validate_results(args.results_dir)
    except GateFailure as error:
        print(f"BLOCK: {error}", file=sys.stderr)
        return 1
    print(f"PASS: exactly one packaged J20 upload-progress journey passed ({REQUIRED_CLASS}#{REQUIRED_METHOD})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
