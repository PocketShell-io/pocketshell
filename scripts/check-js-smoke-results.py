#!/usr/bin/python3 -I
"""Require the complete packaged-JS Android smoke class to execute.

This is a deliberately narrow foundation gate, not a replacement for the
session/SSH/reconnect parity journeys. It verifies that Gradle produced fresh
instrumentation XML containing exactly the registered packaged-shell smoke
methods, and that none were skipped or failed.

Usage:
  scripts/check-js-smoke-results.py
  scripts/check-js-smoke-results.py --results-dir <gradle-results-dir>
  scripts/check-js-smoke-results.py --self-test
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


DEFAULT_RESULTS_DIR = Path(
    "android/app/build/outputs/androidTest-results/connected/debug"
)
REQUIRED_CLASS = "com.pocketshell.app.smoke.JsShellPackagedSmokeTest"
REQUIRED_METHODS = frozenset(
    {
        "launchShowsVerifiedSourcesAndAssetIdentity",
        "singleOpenDocumentDataUriIsIncludedAndDeduplicated",
        "packagedAndroidAdaptersDeliverSharedTextAndExactFileBytes",
        "packagedMultipleShareReadsStandardStreamListWithoutClipData",
        "settingsAndAndroidBackReturnHome",
        "composerInputStaysAboveImeWithinSafeArea",
    }
)
SELF_TEST_CASES = 8


class GateFailure(ValueError):
    """The connected smoke run did not execute the exact required tests."""


def _integer_attribute(element: ET.Element, name: str, path: Path) -> int:
    raw = element.attrib.get(name)
    if raw is None:
        raise GateFailure(f"{path}: XML root is missing required {name!r} count")
    try:
        value = int(raw)
    except ValueError as exc:
        raise GateFailure(
            f"{path}: XML root has invalid {name!r} count {raw!r}"
        ) from exc
    if value < 0:
        raise GateFailure(f"{path}: XML root has negative {name!r} count {value}")
    return value


def validate_results(results_dir: Path) -> tuple[int, int]:
    """Validate and return (executed, passed) counts for the required class."""
    if not results_dir.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results_dir}")

    xml_files = sorted(results_dir.rglob("TEST-*.xml"))
    if not xml_files:
        raise GateFailure(f"no TEST-*.xml instrumentation results found under {results_dir}")

    discovered: list[tuple[str, str, bool, bool]] = []
    declared_total = 0

    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            raise GateFailure(f"could not parse instrumentation result {path}: {exc}") from exc

        if root.tag not in {"testsuite", "testsuites"}:
            raise GateFailure(
                f"{path}: expected <testsuite> or <testsuites>, found <{root.tag}>"
            )

        cases = list(root.iter("testcase"))
        declared = _integer_attribute(root, "tests", path)
        if declared != len(cases):
            raise GateFailure(
                f"{path}: XML declares {declared} tests but contains {len(cases)} test cases"
            )
        declared_total += declared

        # Compare the top-level summary to the actual test case elements. This
        # rejects partial or internally inconsistent XML instead of trusting
        # a summary count that can conceal skipped work.
        for summary_name, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
            reported = int(root.attrib.get(summary_name, "0"))
            actual = sum(1 for case in cases for child in case.iter(tag))
            if reported != actual:
                raise GateFailure(
                    f"{path}: XML declares {reported} {summary_name} but test cases contain {actual}"
                )

        for case in cases:
            class_name = case.attrib.get("classname", "")
            method_name = case.attrib.get("name", "")
            if not class_name or not method_name:
                raise GateFailure(
                    f"{path}: test case is missing classname/name: {case.attrib!r}"
                )
            failed = bool(list(case.iter("failure")) or list(case.iter("error")))
            skipped = bool(list(case.iter("skipped")))
            discovered.append((class_name, method_name, failed, skipped))

    if declared_total != len(discovered):
        # The per-file equality above should make this unreachable; keep the
        # combined assertion explicit so future multi-report changes fail shut.
        raise GateFailure(
            f"instrumentation XML declares {declared_total} tests but contains "
            f"{len(discovered)} test cases"
        )

    counts = Counter((class_name, method_name) for class_name, method_name, _, _ in discovered)
    if any(count != 1 for count in counts.values()):
        duplicates = sorted(f"{class_name}#{method_name} x{count}" for (class_name, method_name), count in counts.items() if count != 1)
        raise GateFailure(f"duplicate instrumentation test cases: {', '.join(duplicates)}")

    actual_class_names = {class_name for class_name, _, _, _ in discovered}
    if actual_class_names != {REQUIRED_CLASS}:
        raise GateFailure(
            "unexpected instrumentation classes; expected exactly "
            f"{REQUIRED_CLASS}, found {', '.join(sorted(actual_class_names)) or '<none>'}"
        )

    actual_methods = {method_name for _, method_name, _, _ in discovered}
    missing = sorted(REQUIRED_METHODS - actual_methods)
    extra = sorted(actual_methods - REQUIRED_METHODS)
    if missing or extra:
        details = []
        if missing:
            details.append(f"missing {', '.join(missing)}")
        if extra:
            details.append(f"unexpected {', '.join(extra)}")
        raise GateFailure(
            "instrumentation method set does not match the required smoke suite: "
            + "; ".join(details)
        )

    if len(discovered) != len(REQUIRED_METHODS) or declared_total != len(REQUIRED_METHODS):
        raise GateFailure(
            f"expected exactly {len(REQUIRED_METHODS)} executed smoke tests, "
            f"found {len(discovered)} test cases ({declared_total} declared)"
        )

    failures = sorted(
        f"{class_name}#{method_name}"
        for class_name, method_name, failed, _ in discovered
        if failed
    )
    if failures:
        raise GateFailure(f"instrumentation tests failed: {', '.join(failures)}")

    skipped = sorted(
        f"{class_name}#{method_name}"
        for class_name, method_name, _, was_skipped in discovered
        if was_skipped
    )
    if skipped:
        raise GateFailure(f"instrumentation tests were skipped: {', '.join(skipped)}")

    return declared_total, declared_total


def _write_xml(results_dir: Path, cases: list[tuple[str, str, str]]) -> None:
    results_dir.mkdir(parents=True, exist_ok=True)
    root = ET.Element(
        "testsuite",
        {
            "name": REQUIRED_CLASS,
            "tests": str(len(cases)),
            "failures": str(sum(1 for _, _, status in cases if status == "failed")),
            "errors": "0",
            "skipped": str(sum(1 for _, _, status in cases if status == "skipped")),
        },
    )
    for class_name, method_name, status in cases:
        case = ET.SubElement(root, "testcase", {"classname": class_name, "name": method_name})
        if status == "failed":
            ET.SubElement(case, "failure", {"message": "synthetic failure"})
        elif status == "skipped":
            ET.SubElement(case, "skipped", {"message": "synthetic skip"})
    ET.ElementTree(root).write(results_dir / "TEST-smoke.xml", encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    good_cases = [(REQUIRED_CLASS, method, "passed") for method in sorted(REQUIRED_METHODS)]
    duplicate_method = REQUIRED_METHODS.__iter__().__next__()
    probes: list[tuple[str, list[tuple[str, str, str]] | None, bool]] = [
        ("complete exact smoke suite passes", good_cases, True),
        ("missing result XML is rejected", None, False),
        (
            "missing required method is rejected",
            [(REQUIRED_CLASS, method, "passed") for method in sorted(REQUIRED_METHODS)[:-1]],
            False,
        ),
        (
            "extra method is rejected",
            good_cases + [(REQUIRED_CLASS, "unregisteredMethod", "passed")],
            False,
        ),
        (
            "duplicate method is rejected",
            good_cases + [(REQUIRED_CLASS, duplicate_method, "passed")],
            False,
        ),
        (
            "skipped method is rejected",
            [(REQUIRED_CLASS, method, "skipped" if method == duplicate_method else "passed") for method in sorted(REQUIRED_METHODS)],
            False,
        ),
        (
            "failed method is rejected",
            [(REQUIRED_CLASS, method, "failed" if method == duplicate_method else "passed") for method in sorted(REQUIRED_METHODS)],
            False,
        ),
        (
            "unexpected test class is rejected",
            [("example.OtherSmokeTest", method, "passed") for method in sorted(REQUIRED_METHODS)],
            False,
        ),
    ]

    completed = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-smoke-guard-") as scratch:
        root = Path(scratch)
        for index, (label, cases, should_pass) in enumerate(probes):
            case_dir = root / f"case-{index}"
            if cases is not None:
                _write_xml(case_dir, cases)
            try:
                validate_results(case_dir)
                passed = True
                detail = "accepted"
            except GateFailure as exc:
                passed = False
                detail = str(exc)

            if passed != should_pass:
                print(f"FAIL: self-test #{index + 1}: {label}: {detail}", file=sys.stderr)
                return 1
            completed += 1
            print(f"ok   [{index + 1}/{SELF_TEST_CASES}] {label}")

    if completed != SELF_TEST_CASES:
        print(
            f"FAIL: self-test executed {completed} probes, expected {SELF_TEST_CASES}",
            file=sys.stderr,
        )
        return 1
    print(f"PASS: {completed}/{SELF_TEST_CASES} JS packaged smoke result guard checks")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--results-dir",
        type=Path,
        default=DEFAULT_RESULTS_DIR,
        help=f"Gradle connected instrumentation XML directory (default: {DEFAULT_RESULTS_DIR})",
    )
    parser.add_argument("--self-test", action="store_true", help="run synthetic red/green guard probes")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    try:
        executed, passed = validate_results(args.results_dir)
    except GateFailure as exc:
        print(f"FAIL: packaged JS smoke result check: {exc}", file=sys.stderr)
        return 1

    print(
        f"PASS: packaged JS smoke results contain {executed} executed tests, "
        f"{passed} passed; required class {REQUIRED_CLASS}"
    )
    for method in sorted(REQUIRED_METHODS):
        print(f"  passed {REQUIRED_CLASS}#{method}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
