#!/usr/bin/env python3
"""Require the Android speech policy tests to have successful JUnit evidence."""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
from datetime import datetime, timedelta, timezone
import xml.etree.ElementTree as ET
from pathlib import Path


TEST_CLASS = "com.pocketshell.app.SpeechRecognitionPluginTest"
EXPECTED_METHODS = {
    "recognizerExtrasUseConfiguredLanguageAndFourSecondDefault",
    "recognizerSilenceExtrasClampTheFloorAndMaximum",
    "malformedSilenceAndLanguageValuesUseSafeDefaults",
    "autoLanguageSettingOmitsTheLocaleHintWhileMissingSettingUsesDeviceLocale",
}


class GateFailure(Exception):
    pass


def successful_methods(results_dir: Path, started_at: Path) -> set[str]:
    if not results_dir.is_dir():
        raise GateFailure(f"JUnit result directory is missing: {results_dir}")
    if not started_at.is_file():
        raise GateFailure(f"Gradle speech-test run marker is missing: {started_at}")

    marker_stat = started_at.stat()
    run_started = datetime.fromtimestamp(marker_stat.st_mtime, tz=timezone.utc)

    reports = sorted(results_dir.glob("TEST-*.xml"))
    if not reports:
        raise GateFailure(f"no TEST-*.xml reports found under {results_dir}")

    found: dict[str, int] = {}
    failures: list[str] = []
    for report in reports:
        if report.stat().st_mtime_ns <= marker_stat.st_mtime_ns:
            failures.append(f"stale JUnit report does not postdate this Gradle run marker: {report}")
            continue
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as error:
            raise GateFailure(f"invalid JUnit XML in {report}: {error}") from error

        speech_suites = [
            suite
            for suite in root.iter("testsuite")
            if any(
                case.get("classname") == TEST_CLASS and case.get("name", "") in EXPECTED_METHODS
                for case in suite.iter("testcase")
            )
        ]
        for suite in speech_suites:
            value = suite.get("timestamp")
            if value is None:
                failures.append(f"speech JUnit suite has no timestamp in {report}")
                continue
            try:
                suite_started = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                failures.append(f"speech JUnit suite has invalid timestamp {value!r} in {report}")
                continue
            if suite_started.tzinfo is None:
                failures.append(f"speech JUnit suite timestamp has no timezone in {report}")
                continue
            if suite_started.astimezone(timezone.utc) <= run_started:
                failures.append(f"stale speech JUnit suite timestamp does not postdate this Gradle run in {report}")

        for case in root.iter("testcase"):
            if case.get("classname") != TEST_CLASS:
                continue
            method = case.get("name", "")
            if method not in EXPECTED_METHODS:
                continue
            found[method] = found.get(method, 0) + 1
            problems = [
                child.tag.rsplit("}", 1)[-1]
                for child in case
                if child.tag.rsplit("}", 1)[-1] in {"failure", "error", "skipped"}
            ]
            if problems:
                failures.append(f"{method} has {', '.join(problems)} in {report}")

    missing = sorted(EXPECTED_METHODS - found.keys())
    repeated = sorted(method for method, count in found.items() if count != 1)
    if missing:
        failures.append(f"missing required methods: {', '.join(missing)}")
    if repeated:
        failures.append(f"methods appeared more than once: {', '.join(repeated)}")
    if failures:
        raise GateFailure("; ".join(failures))
    return EXPECTED_METHODS


def write_report(path: Path, cases: list[tuple[str, str]], timestamp: str) -> None:
    suite = ET.Element(
        "testsuite",
        {
            "name": TEST_CLASS,
            "tests": str(len(cases)),
            "failures": "0",
            "errors": "0",
            "skipped": "0",
            "timestamp": timestamp,
        },
    )
    for method, outcome in cases:
        case = ET.SubElement(suite, "testcase", {"name": method, "classname": TEST_CLASS})
        if outcome != "pass":
            ET.SubElement(case, outcome)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def self_test() -> None:
    methods = sorted(EXPECTED_METHODS)
    with tempfile.TemporaryDirectory(prefix="speech-policy-junit-") as temp:
        root = Path(temp)
        report = root / "TEST-speech.xml"
        marker = root / "gradle-started"
        marker.touch()
        # Use a fixed boundary so the self-test is deterministic on filesystems
        # with coarse timestamp resolution.
        marker_ns = 1_600_000_000_000_000_000
        os.utime(marker, ns=(marker_ns, marker_ns))
        started = datetime.fromtimestamp(marker_ns / 1_000_000_000, tz=timezone.utc)
        fresh_timestamp = (
            (started + timedelta(seconds=1))
            .isoformat(timespec="milliseconds")
            .replace("+00:00", "Z")
        )

        try:
            successful_methods(root, root / "missing-marker")
        except GateFailure as error:
            if "run marker is missing" not in str(error):
                raise
        else:
            raise GateFailure("self-test accepted a missing Gradle run marker")

        write_report(report, [(method, "pass") for method in methods], fresh_timestamp)
        successful_methods(root, marker)

        # Reproduce the review finding: the XML file itself is recent, but its
        # JUnit suite timestamp proves the passing cases came from an earlier run.
        write_report(report, [(method, "pass") for method in methods], "2000-01-01T00:00:00.000Z")
        try:
            successful_methods(root, marker)
        except GateFailure as error:
            if "stale speech JUnit suite timestamp" not in str(error):
                raise
        else:
            raise GateFailure("self-test accepted a stale passing report")

        # Also reject a report that retained a current-looking XML timestamp
        # while its filesystem metadata proves it predates this run.
        write_report(report, [(method, "pass") for method in methods], fresh_timestamp)
        old_ns = marker_ns - 1_000_000_000
        os.utime(report, ns=(old_ns, old_ns))
        try:
            successful_methods(root, marker)
        except GateFailure as error:
            if "stale JUnit report" not in str(error):
                raise
        else:
            raise GateFailure("self-test accepted a report with stale file metadata")

        for outcome in ("skipped", "failure", "error"):
            write_report(
                report,
                [(methods[0], outcome), *[(method, "pass") for method in methods[1:]]],
                fresh_timestamp,
            )
            try:
                successful_methods(root, marker)
            except GateFailure:
                pass
            else:
                raise GateFailure(f"self-test accepted a {outcome} method")

        write_report(report, [(method, "pass") for method in methods[:-1]], fresh_timestamp)
        try:
            successful_methods(root, marker)
        except GateFailure:
            pass
        else:
            raise GateFailure("self-test accepted a report missing a required method")

        write_report(report, [(method, "pass") for method in methods] + [(methods[0], "pass")], fresh_timestamp)
        try:
            successful_methods(root, marker)
        except GateFailure:
            pass
        else:
            raise GateFailure("self-test accepted a duplicated required method")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, help="Gradle testDebugUnitTest JUnit result directory")
    parser.add_argument("--started-at", type=Path, help="marker touched immediately before the Gradle test run")
    parser.add_argument("--self-test", action="store_true", help="exercise pass and fail cases without Gradle")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        print("PASS: speech policy JUnit checker rejects stale, missing, skipped, failed, and duplicate cases")
        return 0
    if args.results_dir is None or args.started_at is None:
        parser.error("--results-dir and --started-at are required unless --self-test is used")

    try:
        methods = successful_methods(args.results_dir, args.started_at)
    except GateFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: all {len(methods)} Android speech policy tests passed in fresh JUnit XML")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
