#!/usr/bin/python3 -I
"""Require the complete Vitest suite to execute exactly as registered.

The manifest names each test file, describe block, and test title. This catches
empty discovery, accidental suite narrowing, skips, and added tests that have
not been deliberately included in the branch gate.

Usage:
  scripts/check-js-unit-results.py --report <vitest-json-report>
  scripts/check-js-unit-results.py --self-test
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "scripts/js-unit-test-manifest.json"
SELF_TESTS = 8


class GateFailure(ValueError):
    """Vitest did not execute the registered JS unit suite."""


def load_manifest() -> dict[str, set[tuple[tuple[str, ...], str]]]:
    try:
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GateFailure(f"could not read {MANIFEST}: {exc}") from exc
    if not isinstance(data, dict) or data.get("schema") != 1:
        raise GateFailure(f"{MANIFEST}: expected manifest schema 1")
    files = data.get("testFiles")
    if not isinstance(files, dict) or not files:
        raise GateFailure(f"{MANIFEST}: testFiles must be a non-empty object")

    expected: dict[str, set[tuple[tuple[str, ...], str]]] = {}
    for raw_path, details in files.items():
        rel = PurePosixPath(raw_path)
        if rel.is_absolute() or ".." in rel.parts or not raw_path.startswith("tests/unit/"):
            raise GateFailure(f"{MANIFEST}: invalid test path {raw_path!r}")
        if not raw_path.endswith(".test.ts") or not (ROOT / raw_path).is_file():
            raise GateFailure(f"{MANIFEST}: test file is missing or has an invalid name: {raw_path}")
        if not isinstance(details, dict):
            raise GateFailure(f"{MANIFEST}: {raw_path} entry must be an object")
        suite = details.get("suite")
        titles = details.get("tests")
        if not isinstance(suite, str) or not suite.strip():
            raise GateFailure(f"{MANIFEST}: {raw_path} must name one describe block")
        if not isinstance(titles, list) or not titles or any(not isinstance(t, str) or not t.strip() for t in titles):
            raise GateFailure(f"{MANIFEST}: {raw_path} must register non-empty test titles")
        if len(titles) != len(set(titles)):
            raise GateFailure(f"{MANIFEST}: {raw_path} has duplicate test titles")
        expected[raw_path] = {((suite,), title) for title in titles}

    if sum(map(len, expected.values())) <= 0:
        raise GateFailure(f"{MANIFEST}: registered test count must be positive")
    return expected


def report_file_name(raw_name: Any, report: Path) -> str:
    if not isinstance(raw_name, str) or not raw_name:
        raise GateFailure(f"{report}: a test result has no source file name")
    path = Path(raw_name)
    if path.is_absolute():
        try:
            return path.resolve().relative_to(ROOT.resolve()).as_posix()
        except ValueError as exc:
            raise GateFailure(f"{report}: test source is outside the checkout: {raw_name}") from exc
    rel = PurePosixPath(raw_name)
    if ".." in rel.parts:
        raise GateFailure(f"{report}: test source path escapes the checkout: {raw_name}")
    return rel.as_posix().removeprefix("./")


def required_count(report: dict[str, Any], key: str, path: Path, *, default: int | None = None) -> int:
    value = report.get(key, default)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise GateFailure(f"{path}: missing or invalid Vitest count {key!r}")
    return value


def validate_report(path: Path) -> tuple[int, int]:
    if not path.is_file():
        raise GateFailure(f"Vitest JSON report is missing: {path}")
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise GateFailure(f"could not parse Vitest report {path}: {exc}") from exc
    if not isinstance(report, dict):
        raise GateFailure(f"{path}: expected a JSON object")

    expected = load_manifest()
    raw_suites = report.get("testResults")
    if not isinstance(raw_suites, list) or not raw_suites:
        raise GateFailure(f"{path}: Vitest discovered no test files")

    actual: dict[str, list[tuple[tuple[str, ...], str, str]]] = {}
    for suite in raw_suites:
        if not isinstance(suite, dict):
            raise GateFailure(f"{path}: malformed testResults entry")
        file_name = report_file_name(suite.get("name"), path)
        if file_name in actual:
            raise GateFailure(f"{path}: duplicate result entry for {file_name}")
        assertions = suite.get("assertionResults")
        if not isinstance(assertions, list):
            raise GateFailure(f"{path}: {file_name} has no assertionResults list")
        actual[file_name] = []
        for assertion in assertions:
            if not isinstance(assertion, dict):
                raise GateFailure(f"{path}: malformed assertion in {file_name}")
            ancestors, title, status = (
                assertion.get("ancestorTitles"), assertion.get("title"), assertion.get("status")
            )
            if (
                not isinstance(ancestors, list)
                or not ancestors
                or any(not isinstance(item, str) for item in ancestors)
                or not isinstance(title, str)
                or not title
                or not isinstance(status, str)
            ):
                raise GateFailure(f"{path}: incomplete assertion in {file_name}")
            actual[file_name].append((tuple(ancestors), title, status))

    if set(actual) != set(expected):
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        details = []
        if missing:
            details.append(f"missing files: {', '.join(missing)}")
        if extra:
            details.append(f"unexpected files: {', '.join(extra)}")
        raise GateFailure("Vitest file set differs from the manifest: " + "; ".join(details))

    expected_total = 0
    passed_total = 0
    for file_name, expected_tests in expected.items():
        actual_tests = actual[file_name]
        actual_keys = [(ancestors, title) for ancestors, title, _ in actual_tests]
        if len(actual_keys) != len(set(actual_keys)):
            raise GateFailure(f"{path}: {file_name} has duplicate test results")
        if set(actual_keys) != expected_tests or len(actual_keys) != len(expected_tests):
            missing = sorted(
                f"{' > '.join(ancestors)} > {title}"
                for ancestors, title in expected_tests - set(actual_keys)
            )
            extra = sorted(
                f"{' > '.join(ancestors)} > {title}"
                for ancestors, title in set(actual_keys) - expected_tests
            )
            details = []
            if missing:
                details.append(f"missing tests: {', '.join(missing)}")
            if extra:
                details.append(f"unexpected tests: {', '.join(extra)}")
            raise GateFailure(f"{path}: {file_name} test set differs from the manifest: " + "; ".join(details))
        non_passing = [
            f"{' > '.join(ancestors)} > {title} ({status})"
            for ancestors, title, status in actual_tests
            if status != "passed"
        ]
        if non_passing:
            raise GateFailure(f"{path}: non-passing or skipped tests: {', '.join(non_passing)}")
        expected_total += len(expected_tests)
        passed_total += len(actual_tests)

    total = required_count(report, "numTotalTests", path)
    passed = required_count(report, "numPassedTests", path)
    failed = required_count(report, "numFailedTests", path)
    pending = required_count(report, "numPendingTests", path, default=0)
    todo = required_count(report, "numTodoTests", path, default=0)
    if expected_total <= 0 or passed_total <= 0:
        raise GateFailure(f"{path}: zero tests executed is not a green result")
    if total != expected_total or passed != passed_total:
        raise GateFailure(
            f"Vitest summary reports {passed}/{total}; manifest and result files require "
            f"{passed_total}/{expected_total}"
        )
    if failed or pending or todo:
        raise GateFailure(f"Vitest summary contains failed/pending/todo tests: {failed}/{pending}/{todo}")
    if "success" in report and report["success"] is not True:
        raise GateFailure(f"{path}: Vitest success flag is not true")
    return passed_total, len(actual)


def synthetic_report(
    manifest: dict[str, set[tuple[tuple[str, ...], str]]],
    *, status: str = "passed", extra_file: bool = False, duplicate: bool = False, lie: bool = False
) -> dict[str, Any]:
    suites: list[dict[str, Any]] = []
    for file_name, tests in manifest.items():
        assertions = [
            {"ancestorTitles": list(ancestors), "title": title, "status": status}
            for ancestors, title in sorted(tests)
        ]
        if duplicate:
            assertions.append(dict(assertions[0]))
        suites.append({"name": str(ROOT / file_name), "assertionResults": assertions})
    if extra_file:
        suites.append(
            {
                "name": str(ROOT / "tests/unit/extra.test.ts"),
                "assertionResults": [{"ancestorTitles": ["extra"], "title": "case", "status": "passed"}],
            }
        )
    total = sum(len(suite["assertionResults"]) for suite in suites) + (1 if lie else 0)
    return {
        "success": status == "passed" and not extra_file and not duplicate and not lie,
        "numTotalTests": total,
        "numPassedTests": total if status == "passed" else 0,
        "numFailedTests": total if status == "failed" else 0,
        "numPendingTests": total if status == "skipped" else 0,
        "numTodoTests": 0,
        "testResults": suites,
    }


def self_test() -> int:
    expected = load_manifest()
    probes: list[tuple[str, dict[str, Any] | None, bool]] = [
        ("exact non-empty manifest report passes", synthetic_report(expected), True),
        ("missing report is rejected", None, False),
        ("zero tests are rejected", {"testResults": []}, False),
        ("extra test file is rejected", synthetic_report(expected, extra_file=True), False),
        ("skipped tests are rejected", synthetic_report(expected, status="skipped"), False),
        ("failed tests are rejected", synthetic_report(expected, status="failed"), False),
        ("summary mismatch is rejected", synthetic_report(expected, lie=True), False),
        ("duplicate test result is rejected", synthetic_report(expected, duplicate=True), False),
    ]

    completed = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-unit-guard-") as temp:
        temp_dir = Path(temp)
        for index, (label, data, should_pass) in enumerate(probes):
            path = temp_dir / f"case-{index}.json"
            if data is not None:
                path.write_text(json.dumps(data), encoding="utf-8")
            try:
                validate_report(path)
                passed, detail = True, "accepted"
            except GateFailure as exc:
                passed, detail = False, str(exc)
            if passed != should_pass:
                print(f"FAIL: self-test #{index + 1}: {label}: {detail}", file=sys.stderr)
                return 1
            completed += 1
            print(f"ok   [{completed}/{SELF_TESTS}] {label}")
    if completed != SELF_TESTS:
        print(f"FAIL: self-test ran {completed} probes; expected {SELF_TESTS}", file=sys.stderr)
        return 1
    print(f"PASS: {completed}/{SELF_TESTS} JS unit result guard checks")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", type=Path, help="Vitest JSON report path")
    parser.add_argument("--self-test", action="store_true", help="run synthetic red/green probes")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.report is None:
        parser.error("--report is required unless --self-test is used")
    try:
        count, files = validate_report(args.report)
    except GateFailure as exc:
        print(f"FAIL: JS unit result check: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: Vitest executed {count} registered tests across {files} registered file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
