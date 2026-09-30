#!/usr/bin/python3 -I
"""Qualify JS-first Android feature journeys without accepting a vacuous run.

The manifest names the 24 user-visible journeys mapped from the removed
app2 suite. This checker is deliberately NOT wired into the foundation PR
workflow while those feature journeys are absent; it emits a machine-readable
BLOCK result until every registered journey has actually executed.

Usage:
  scripts/check-js-journey-results.py --results-dir <connected-XML-dir>
  scripts/check-js-journey-results.py --results-dir <dir> --json
  scripts/check-js-journey-results.py --self-test
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts/js-journey-class-manifest.json"
DEFAULT_RESULTS_DIR = ROOT / "android/app/build/outputs/androidTest-results/connected/debug"
SELF_TEST_CASES = 9
REQUIRED_CLASS_COUNT = 24


def _new_result(required: list[str]) -> dict[str, Any]:
    return {
        "schema": 1,
        "suite": "js-first-feature-journeys",
        "result": "BLOCK",
        "requiredJourneyClasses": len(required),
        "executedJourneyClasses": [],
        "executedTests": 0,
        "missingJourneyClasses": list(required),
        "unexpectedClasses": [],
        "failedTests": [],
        "skippedTests": [],
        "blockers": [],
    }


def load_manifest(path: Path = MANIFEST) -> list[str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("schema") != 1:
        raise ValueError(f"{path}: expected manifest schema 1")
    classes = data.get("requiredJourneyClasses")
    if (
        not isinstance(classes, list)
        or not classes
        or any(not isinstance(name, str) or not name for name in classes)
        or len(classes) != len(set(classes))
    ):
        raise ValueError(f"{path}: requiredJourneyClasses must be a non-empty list of unique class names")
    if len(classes) != REQUIRED_CLASS_COUNT:
        raise ValueError(
            f"{path}: expected exactly {REQUIRED_CLASS_COUNT} mapped feature journey classes, found {len(classes)}"
        )
    return classes


def _read_nonnegative_count(element: ET.Element, name: str, path: Path, blockers: list[str]) -> int | None:
    raw = element.attrib.get(name)
    if raw is None:
        blockers.append(f"{path}: XML <{element.tag}> is missing the {name!r} count")
        return None
    try:
        count = int(raw)
    except ValueError:
        blockers.append(f"{path}: XML <{element.tag}> has invalid {name!r} count {raw!r}")
        return None
    if count < 0:
        blockers.append(f"{path}: XML <{element.tag}> has negative {name!r} count {count}")
        return None
    return count


def _class_name(raw: str) -> str:
    # Package layouts may differ while feature issues add the replacement
    # tests; the simple class name remains the contract from the inventory.
    return raw.rsplit(".", 1)[-1]


def qualify_results(results_dir: Path, manifest_path: Path = MANIFEST) -> dict[str, Any]:
    try:
        required = load_manifest(manifest_path)
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        result = _new_result([])
        result["blockers"] = [f"could not load required journey manifest: {exc}"]
        return result

    result = _new_result(required)
    blockers: list[str] = result["blockers"]
    if not results_dir.is_dir():
        blockers.append(f"instrumentation result directory is missing: {results_dir}")
        return result

    xml_files = sorted(results_dir.rglob("TEST-*.xml"))
    if not xml_files:
        blockers.append(f"no TEST-*.xml instrumentation reports found under {results_dir}")
        return result

    test_cases: list[tuple[str, str, bool, bool]] = []
    declared_total = 0
    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as exc:
            blockers.append(f"could not parse instrumentation report {path}: {exc}")
            continue
        if root.tag not in {"testsuite", "testsuites"}:
            blockers.append(f"{path}: expected <testsuite> or <testsuites>, found <{root.tag}>")
            continue

        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        if not suites:
            blockers.append(f"{path}: <testsuites> contains no <testsuite>")
            continue

        file_cases = 0
        file_declared_total = 0
        for suite in suites:
            cases = list(suite.findall("testcase"))
            declared = _read_nonnegative_count(suite, "tests", path, blockers)
            if declared is not None:
                file_declared_total += declared
            if declared is not None and declared != len(cases):
                blockers.append(f"{path}: XML declares {declared} tests but contains {len(cases)} in <testsuite>")
            if not cases:
                blockers.append(f"{path}: <testsuite> executed zero tests")
            file_cases += len(cases)

            for summary_name, tag_name in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                declared_status = suite.attrib.get(summary_name, "0")
                try:
                    status_count = int(declared_status)
                except ValueError:
                    blockers.append(f"{path}: invalid <testsuite> {summary_name} count {declared_status!r}")
                    continue
                actual_status = sum(1 for case in cases for child in case.iter(tag_name))
                if status_count != actual_status:
                    blockers.append(
                        f"{path}: XML declares {status_count} {summary_name} but test cases contain {actual_status}"
                    )

            for case in cases:
                raw_class = case.attrib.get("classname", "")
                method = case.attrib.get("name", "")
                if not raw_class or not method:
                    blockers.append(f"{path}: testcase is missing classname or name: {case.attrib!r}")
                    continue
                simple_class = _class_name(raw_class)
                failed = bool(list(case.iter("failure")) or list(case.iter("error")))
                skipped = bool(list(case.iter("skipped")))
                test_cases.append((simple_class, f"{raw_class}#{method}", failed, skipped))

        if root.tag == "testsuites" and "tests" in root.attrib:
            aggregate = _read_nonnegative_count(root, "tests", path, blockers)
            if aggregate is not None and aggregate != file_cases:
                blockers.append(f"{path}: <testsuites> declares {aggregate} tests but contains {file_cases}")
        declared_total += file_declared_total

    identities = Counter((class_name, test_id.rsplit("#", 1)[-1]) for class_name, test_id, _, _ in test_cases)
    duplicates = sorted(f"{name}#{method} x{count}" for (name, method), count in identities.items() if count != 1)
    if duplicates:
        blockers.append("duplicate instrumentation test cases: " + ", ".join(duplicates))

    expected = set(required)
    executed_counts = Counter(class_name for class_name, _, _, _ in test_cases)
    actual = set(executed_counts)
    result["executedJourneyClasses"] = sorted(actual & expected)
    result["executedTests"] = len(test_cases)
    result["missingJourneyClasses"] = sorted(expected - actual)
    result["unexpectedClasses"] = sorted(actual - expected)
    result["failedTests"] = sorted(test_id for _, test_id, failed, _ in test_cases if failed)
    result["skippedTests"] = sorted(test_id for _, test_id, _, skipped in test_cases if skipped)

    if declared_total != len(test_cases):
        blockers.append(f"reports declare {declared_total} tests but contain {len(test_cases)} test cases")
    if not test_cases:
        blockers.append("zero feature journey tests executed")
    if result["missingJourneyClasses"]:
        blockers.append(
            "missing required journey classes: " + ", ".join(result["missingJourneyClasses"])
        )
    if result["unexpectedClasses"]:
        blockers.append("unexpected journey classes: " + ", ".join(result["unexpectedClasses"]))
    if result["failedTests"]:
        blockers.append("failed journey tests: " + ", ".join(result["failedTests"]))
    if result["skippedTests"]:
        blockers.append("skipped journey tests: " + ", ".join(result["skippedTests"]))

    if not blockers and len(result["executedJourneyClasses"]) == len(required) and result["executedTests"] > 0:
        result["result"] = "PASS"
    return result


def _write_report(directory: Path, entries: list[tuple[str, str, str]]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    suites: dict[str, list[tuple[str, str]]] = {}
    for class_name, method_name, status in entries:
        suites.setdefault(class_name, []).append((method_name, status))
    for index, (class_name, cases) in enumerate(sorted(suites.items())):
        failures = sum(status == "failed" for _, status in cases)
        skipped = sum(status == "skipped" for _, status in cases)
        suite = ET.Element(
            "testsuite",
            {"name": class_name, "tests": str(len(cases)), "failures": str(failures), "errors": "0", "skipped": str(skipped)},
        )
        for method_name, status in cases:
            case = ET.SubElement(suite, "testcase", {"classname": class_name, "name": method_name})
            if status == "failed":
                ET.SubElement(case, "failure", {"message": "synthetic failure"})
            elif status == "skipped":
                ET.SubElement(case, "skipped", {"message": "synthetic skip"})
        ET.ElementTree(suite).write(directory / f"TEST-{index:02d}-{class_name}.xml", encoding="utf-8", xml_declaration=True)


def _write_zero_report(directory: Path, required: list[str]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for index, class_name in enumerate(required):
        suite = ET.Element(
            "testsuite",
            {"name": class_name, "tests": "0", "failures": "0", "errors": "0", "skipped": "0"},
        )
        ET.ElementTree(suite).write(directory / f"TEST-{index:02d}-{class_name}.xml", encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    try:
        required = load_manifest()
    except (OSError, json.JSONDecodeError, ValueError) as exc:
        print(f"FAIL: journey manifest cannot be loaded: {exc}", file=sys.stderr)
        return 1

    green = [(name, "runsJourney", "passed") for name in required]
    first, last = required[0], required[-1]
    probes: list[tuple[str, list[tuple[str, str, str]] | None, bool]] = [
        ("exact non-empty 24-class journey report passes", green, True),
        ("missing reports block", None, False),
        ("missing journey class blocks", [case for case in green if case[0] != first], False),
        ("zero tests block", [(name, "", "zero") for name in required], False),
        ("skipped journey blocks", [(name, "runsJourney", "skipped" if name == first else "passed") for name in required], False),
        ("failed journey blocks", [(name, "runsJourney", "failed" if name == first else "passed") for name in required], False),
        ("extra class blocks", green + [("UnregisteredJourney", "runsJourney", "passed")], False),
        ("duplicate test case blocks", green + [(last, "runsJourney", "passed")], False),
        ("empty manifest blocks", green, False),
    ]

    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-journey-guard-") as scratch:
        root = Path(scratch)
        for index, (label, entries, should_pass) in enumerate(probes):
            report_dir = root / f"case-{index}"
            if entries is not None:
                if label == "zero tests block":
                    _write_zero_report(report_dir, required)
                else:
                    _write_report(report_dir, entries)
            manifest = MANIFEST
            if label == "empty manifest blocks":
                manifest = report_dir / "empty-manifest.json"
                manifest.write_text('{"schema":1,"requiredJourneyClasses":[]}', encoding="utf-8")
            result = qualify_results(report_dir, manifest)
            passed = result["result"] == "PASS"
            if passed != should_pass:
                failures += 1
                print(f"FAIL: self-test #{index + 1}: {label}: {json.dumps(result, sort_keys=True)}", file=sys.stderr)
            else:
                print(f"  ok  [{index + 1}/{SELF_TEST_CASES}] {label}")
    if len(probes) != SELF_TEST_CASES:
        failures += 1
        print(f"FAIL: self-test defined {len(probes)} probes, expected {SELF_TEST_CASES}", file=sys.stderr)
    if failures:
        print(f"FAIL: {failures} journey result guard self-tests failed", file=sys.stderr)
        return 1
    print(f"SELF-TEST OK: missing, zero, skipped, failed, extra and duplicate journey cases block ({SELF_TEST_CASES}/{SELF_TEST_CASES})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    parser.add_argument("--json", action="store_true", help="emit only the machine-readable qualification object")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()

    result = qualify_results(args.results_dir, args.manifest)
    if args.json:
        print(json.dumps(result, sort_keys=True))
    else:
        print(f"{result['result']}: JS feature journey qualification")
        print(
            f"  required classes: {result['requiredJourneyClasses']}; "
            f"executed classes: {len(result['executedJourneyClasses'])}; tests: {result['executedTests']}"
        )
        for key, label in (
            ("missingJourneyClasses", "missing journeys"),
            ("unexpectedClasses", "unexpected classes"),
            ("failedTests", "failed tests"),
            ("skippedTests", "skipped tests"),
            ("blockers", "blockers"),
        ):
            if result[key]:
                print(f"  {label}: {'; '.join(result[key])}")
    return 0 if result["result"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
