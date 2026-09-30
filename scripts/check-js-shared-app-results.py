#!/usr/bin/env python3
"""Require the exact packaged shared-app journey (#2936) to execute and pass once."""

from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.SharedAppDockerJourneyTest"
REQUIRED_METHOD = "sharedAppListsAttachesAndTypesIntoFixtureSession"
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
    expected = Counter({(REQUIRED_CLASS, REQUIRED_METHOD): 1})
    if actual != expected:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {sorted(actual.elements())}")

    case = cases[0]
    if list(case.iter("failure")) or list(case.iter("error")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} failed")
    if list(case.iter("skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} was skipped")
    return reports


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
    cases = [
        ("one exact passing shared-app journey passes", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")], True),
        ("zero tests block", [], False),
        ("missing shared-app method blocks", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("unexpected class blocks", [("example.OtherJourney", REQUIRED_METHOD, "passed")], False),
        ("extra test blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, "extra", "passed")], False),
        ("duplicate test blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, REQUIRED_METHOD, "passed")], False),
        ("failed journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
        ("skipped journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-shared-app-results-") as temporary:
        root = Path(temporary)
        for index, (label, identities, expected_pass) in enumerate(cases):
            results = root / str(index)
            if identities:
                _write_report(results / "TEST-shared-app.xml", identities)
            try:
                validate_results(results)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected_pass:
                failures += 1
                print(f"FAIL: {label}", file=sys.stderr)
            else:
                print(f"ok: {label}")
    print(f"Shared-app result guard self-test: {len(cases) - failures}/{len(cases)} checks passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    try:
        reports = validate_results(args.results_dir)
        if args.evidence_dir:
            args.evidence_dir.mkdir(parents=True, exist_ok=True)
            for report in reports:
                shutil.copy2(report, args.evidence_dir / report.name)
    except GateFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} executed exactly once")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
