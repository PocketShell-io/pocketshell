#!/usr/bin/env python3
"""Require the exact packaged mobile fast-key Docker journey to execute and pass."""

from __future__ import annotations

import argparse
import math
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.JsFastKeysDockerJourneyTest"
REQUIRED_METHOD = "fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect"
DEFAULT_RESULTS_DIR = Path("android/app/build/outputs/androidTest-results/connected/debug")


class GateFailure(ValueError):
    """The packaged journey result is missing, extra, skipped, or red."""


def require_successful_run_metadata(results_dir: Path, junit_path: Path) -> None:
    metadata_path = junit_path.parent / "hotkeys-run-metadata.txt"
    if not metadata_path.is_file():
        raise GateFailure(f"{junit_path}: run metadata is missing: {metadata_path}")
    values: dict[str, str] = {}
    for line_number, raw_line in enumerate(metadata_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not raw_line.strip():
            continue
        key, separator, value = raw_line.partition("=")
        if not separator or not key.strip() or key.strip() in values:
            raise GateFailure(f"{metadata_path}:{line_number}: malformed or duplicate run metadata")
        values[key.strip()] = value.strip()
    try:
        exit_code = int(values["exit_code"])
    except (KeyError, ValueError) as exc:
        raise GateFailure(f"{metadata_path}: missing or invalid exit_code") from exc
    if exit_code != 0:
        raise GateFailure(f"{metadata_path}: run exit_code is {exit_code}, expected 0")


def require_valid_duration(path: Path, element: ET.Element, label: str) -> None:
    value = element.attrib.get("time")
    try:
        duration = float(value) if value is not None else math.nan
    except ValueError:
        duration = math.nan
    if not math.isfinite(duration) or duration < 0:
        raise GateFailure(f"{path}: {label} has an invalid or negative JUnit duration: {value!r}")


def validate_results(results_dir: Path) -> None:
    if not results_dir.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results_dir}")
    xml_files = sorted(results_dir.rglob("TEST-*.xml"))
    if not xml_files:
        raise GateFailure(f"no TEST-*.xml reports found under {results_dir}")

    cases: list[tuple[str, str, ET.Element]] = []
    for path in xml_files:
        require_successful_run_metadata(results_dir, path)
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as exc:
            raise GateFailure(f"could not parse {path}: {exc}") from exc
        if root.tag not in {"testsuite", "testsuites"}:
            raise GateFailure(f"{path}: expected a JUnit testsuite report")
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        if not suites:
            raise GateFailure(f"{path}: report contains no suites")
        for suite in suites:
            require_valid_duration(path, suite, "testsuite")
            suite_cases = list(suite.findall("testcase"))
            try:
                declared = int(suite.attrib["tests"])
            except (KeyError, ValueError) as exc:
                raise GateFailure(f"{path}: invalid declared tests count") from exc
            if declared != len(suite_cases):
                raise GateFailure(f"{path}: declares {declared} tests but contains {len(suite_cases)}")
            for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                try:
                    declared_count = int(suite.attrib.get(summary, "0"))
                except ValueError as exc:
                    raise GateFailure(f"{path}: invalid {summary} count") from exc
                actual_count = sum(1 for case in suite_cases for _ in case.iter(tag))
                if declared_count != actual_count:
                    raise GateFailure(f"{path}: declares {declared_count} {summary} but contains {actual_count}")
            for case in suite_cases:
                require_valid_duration(path, case, "testcase")
                classname, method = case.attrib.get("classname", ""), case.attrib.get("name", "")
                if not classname or not method:
                    raise GateFailure(f"{path}: testcase is missing its class or method")
                cases.append((classname, method, case))

    actual = [(classname, method) for classname, method, _ in cases]
    expected = [(REQUIRED_CLASS, REQUIRED_METHOD)]
    if actual != expected:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {actual or '<zero tests>'}")
    case = cases[0][2]
    if list(case.iter("failure")) or list(case.iter("error")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} failed")
    if list(case.iter("skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} was skipped")


def self_test() -> int:
    probes = [
        ("one exact passing packaged journey passes", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")], True),
        ("zero reports fail closed", None, False),
        ("zero tests fail closed", [], False),
        ("missing method fails", [(REQUIRED_CLASS, "other", "passed")], False),
        ("extra method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, "extra", "passed")], False),
        ("extra class fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), ("other.Journey", "run", "passed")], False),
        ("failed method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
        ("skipped method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-hotkeys-results-") as temporary:
        root = Path(temporary)
        for index, (label, probe, expected) in enumerate(probes):
            directory = root / str(index)
            if probe is not None:
                directory.mkdir()
                suite = ET.Element("testsuite", {
                    "tests": str(len(probe)),
                    "failures": str(sum(result == "failed" for _, _, result in probe)),
                    "errors": "0",
                    "skipped": str(sum(result == "skipped" for _, _, result in probe)),
                    "time": "0.01",
                })
                for classname, method, result in probe:
                    case = ET.SubElement(suite, "testcase", {"classname": classname, "name": method, "time": "0.01"})
                    if result == "failed":
                        ET.SubElement(case, "failure", {"message": "synthetic failure"})
                    elif result == "skipped":
                        ET.SubElement(case, "skipped", {"message": "synthetic skip"})
                ET.ElementTree(suite).write(directory / "TEST-fastkeys.xml", encoding="utf-8", xml_declaration=True)
                (directory / "hotkeys-run-metadata.txt").write_text("run_id=self-test\nexit_code=0\n", encoding="utf-8")
            try:
                validate_results(directory)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: self-test {label}", file=sys.stderr)
                failures += 1
            else:
                print(f"PASS: self-test {label}")

        def write_report(directory: Path, *, exit_code: str = "0", duration: str = "0.01",
                         include_metadata: bool = True) -> None:
            directory.mkdir()
            suite = ET.Element("testsuite", {
                "tests": "1", "failures": "0", "errors": "0", "skipped": "0", "time": "0.01",
            })
            ET.SubElement(suite, "testcase", {
                "classname": REQUIRED_CLASS, "name": REQUIRED_METHOD, "time": duration,
            })
            ET.ElementTree(suite).write(directory / "TEST-fastkeys.xml", encoding="utf-8", xml_declaration=True)
            if include_metadata:
                (directory / "hotkeys-run-metadata.txt").write_text(
                    f"run_id=self-test\nexit_code={exit_code}\n", encoding="utf-8")

        strict_probes = [
            ("exit 143 with a 0/1 JUnit report fails closed", {"exit_code": "143"}, "0.01", True, False),
            ("negative testcase duration fails closed", {"exit_code": "0"}, "-1.790577107471E9", True, False),
            ("invalid testcase duration fails closed", {"exit_code": "0"}, "NaN", True, False),
            ("missing run metadata fails closed", {}, "0.01", False, False),
        ]
        for index, (label, metadata, duration, include_metadata, expected) in enumerate(strict_probes, start=len(probes)):
            directory = root / f"strict-{index}"
            write_report(directory, exit_code=metadata.get("exit_code", "0"), duration=duration,
                         include_metadata=include_metadata)
            try:
                validate_results(directory)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
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
    print(f"PASS: exactly one packaged fast-key Docker journey passed ({REQUIRED_CLASS}#{REQUIRED_METHOD})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
