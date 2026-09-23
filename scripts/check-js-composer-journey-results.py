#!/usr/bin/env python3
"""Require the exact packaged Docker composer journey to execute and pass."""

from __future__ import annotations

import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.JsComposerDockerJourneyTest"
REQUIRED_METHOD = "composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop"
DEFAULT_RESULTS_DIR = Path("android/app/build/outputs/androidTest-results/connected/debug")


class GateFailure(ValueError):
    """The packaged composer journey result is missing, extra, skipped, or red."""


def validate_results(results_dir: Path) -> None:
    if not results_dir.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results_dir}")
    xml_files = sorted(results_dir.rglob("TEST-*.xml"))
    if not xml_files:
        raise GateFailure(f"no TEST-*.xml reports found under {results_dir}")

    testcases: list[tuple[str, str, ET.Element]] = []
    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as exc:
            raise GateFailure(f"could not parse instrumentation report {path}: {exc}") from exc
        if root.tag not in {"testsuite", "testsuites"}:
            raise GateFailure(f"{path}: expected <testsuite> or <testsuites>, found <{root.tag}>")
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        if not suites:
            raise GateFailure(f"{path}: report contains no test suites")
        for suite in suites:
            cases = list(suite.findall("testcase"))
            try:
                declared = int(suite.attrib["tests"])
            except (KeyError, ValueError) as exc:
                raise GateFailure(f"{path}: suite is missing a valid tests count") from exc
            if declared != len(cases):
                raise GateFailure(f"{path}: suite declares {declared} tests but contains {len(cases)} cases")
            for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                try:
                    declared_count = int(suite.attrib.get(summary, "0"))
                except ValueError as exc:
                    raise GateFailure(f"{path}: invalid {summary} count") from exc
                actual_count = sum(1 for case in cases for _ in case.iter(tag))
                if declared_count != actual_count:
                    raise GateFailure(f"{path}: declares {declared_count} {summary} but contains {actual_count}")
            for case in cases:
                classname = case.attrib.get("classname", "")
                method = case.attrib.get("name", "")
                if not classname or not method:
                    raise GateFailure(f"{path}: test case is missing classname or name")
                testcases.append((classname, method, case))

    actual = [(classname, method) for classname, method, _ in testcases]
    expected = [(REQUIRED_CLASS, REQUIRED_METHOD)]
    if actual != expected:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {actual or '<zero tests>'}")
    case = testcases[0][2]
    if list(case.iter("failure")) or list(case.iter("error")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} failed")
    if list(case.iter("skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} was skipped")


def self_test() -> int:
    probes = [
        ("one exact passing packaged journey passes", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")], True),
        ("zero reports fail closed", None, False),
        ("zero tests fail closed", [], False),
        ("missing required method fails", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("extra method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, "extra", "passed")], False),
        ("extra class fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), ("other.Journey", "run", "passed")], False),
        ("duplicate method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, REQUIRED_METHOD, "passed")], False),
        ("failed test fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
        ("skipped test fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-composer-results-") as temporary:
        root = Path(temporary)
        for index, (label, cases, should_pass) in enumerate(probes):
            directory = root / f"probe-{index}"
            if cases is not None:
                directory.mkdir()
                suite = ET.Element("testsuite", {
                    "tests": str(len(cases)),
                    "failures": str(sum(status == "failed" for _, _, status in cases)),
                    "errors": "0",
                    "skipped": str(sum(status == "skipped" for _, _, status in cases)),
                })
                for classname, method, status in cases:
                    case = ET.SubElement(suite, "testcase", {"classname": classname, "name": method})
                    if status == "failed":
                        ET.SubElement(case, "failure", {"message": "synthetic failure"})
                    elif status == "skipped":
                        ET.SubElement(case, "skipped", {"message": "synthetic skip"})
                ET.ElementTree(suite).write(directory / "TEST-composer.xml", encoding="utf-8", xml_declaration=True)
            try:
                validate_results(directory)
                passed = True
            except GateFailure:
                passed = False
            if passed != should_pass:
                print(f"FAIL: self-test {label}", file=sys.stderr)
                failures += 1
            else:
                print(f"PASS: self-test {label}")
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
    except GateFailure as exc:
        print(f"BLOCK: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: exactly one packaged composer journey passed ({REQUIRED_CLASS}#{REQUIRED_METHOD})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
