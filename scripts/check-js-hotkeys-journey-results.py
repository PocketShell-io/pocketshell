#!/usr/bin/env python3
"""Require the exact packaged mobile fast-key Docker journeys to execute and pass.

Two journeys, each exactly once: J23 (the legacy phone shell's dock) and J30
(#3158: the dock the shared shell -- the shell a phone boots -- contributes to
core's terminal.dock). Neither may stand in for the other.

A skipped run is sanctioned ONLY by an unexpired, well-formed row for this
exact method in scripts/journey-quarantine.txt (policy D36, issue #2355) —
the same registry scripts/check-journey-quarantine-expiry.sh reconciles
against the @Ignore on the source. Anything else (missing, extra, failed,
unsanctioned skip, malformed or expired row) fails closed.
"""

from __future__ import annotations

import argparse
import math
import sys
import tempfile
import xml.etree.ElementTree as ET
from datetime import date
from pathlib import Path


METHOD_CLASSES = {
    "fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect": "com.pocketshell.app.smoke.J23InlineDictationJourney",
    "dockAnswersApprovalPromptWithExactBytesImeClosedAndOpen": "com.pocketshell.app.smoke.J30FastKeyDockJourney",
}
REQUIRED = tuple((class_name, method) for method, class_name in METHOD_CLASSES.items())
# J23's identity, kept for the self-test probes below.
REQUIRED_METHOD = "fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect"
REQUIRED_CLASS = METHOD_CLASSES[REQUIRED_METHOD]
REQUIRED_KEY = f"{REQUIRED_CLASS}#{REQUIRED_METHOD}"
DOCK_METHOD = "dockAnswersApprovalPromptWithExactBytesImeClosedAndOpen"
DOCK_CLASS = METHOD_CLASSES[DOCK_METHOD]
DEFAULT_RESULTS_DIR = Path("android/app/build/outputs/androidTest-results/connected/debug")
DEFAULT_QUARANTINE_FILE = Path(__file__).resolve().parent / "journey-quarantine.txt"


class GateFailure(ValueError):
    """The packaged journey result is missing, extra, skipped, or red."""


def quarantine_sanction(quarantine_file: Path, required_key: str = REQUIRED_KEY):
    """((issue, expires), None) when an unexpired well-formed row sanctions the
    skip; (None, reason) otherwise. Fail-closed in every unclear case."""
    try:
        text = quarantine_file.read_text(encoding="utf-8")
    except FileNotFoundError:
        return None, f"quarantine registry is missing: {quarantine_file}"
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] != required_key:
            continue
        if len(fields) != 5:
            return None, f"registry row for {required_key} is malformed (expected 5 TAB-separated fields, got {len(fields)})"
        _, issue, added, expires, _reason = fields
        try:
            added_date = date.fromisoformat(added)
            expires_date = date.fromisoformat(expires)
        except ValueError:
            return None, f"registry row for {required_key} carries a non-ISO date (added={added}, expires={expires})"
        if expires_date <= added_date:
            return None, f"registry row for {required_key} expires ({expires}) before it was added ({added})"
        if date.today() > expires_date:
            return None, f"registry row for {required_key} EXPIRED {expires} — resolve or re-triage it per policy D36"
        return (issue, expires_date), None
    return None, f"no registry row for {required_key}"


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


def validate_results(results_dir: Path, quarantine_file: Path = DEFAULT_QUARANTINE_FILE) -> None:
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

    actual = sorted((classname, method) for classname, method, _ in cases)
    expected = sorted(REQUIRED)
    if actual != expected:
        raise GateFailure(f"expected exactly {expected}; found {actual or '<zero tests>'}")
    executed_all = True
    for classname, method, case in cases:
        key = f"{classname}#{method}"
        if list(case.iter("failure")) or list(case.iter("error")):
            raise GateFailure(f"{key} failed")
        if list(case.iter("skipped")):
            sanction, why = quarantine_sanction(quarantine_file, key)
            if sanction is None:
                raise GateFailure(f"{key} was skipped and no unexpired quarantine row sanctions it: {why}")
            issue, expires = sanction
            print(f"QUARANTINED (policy D36): {key} skipped; tracked {issue}, expires {expires.isoformat()} ({quarantine_file})")
            executed_all = False
    return executed_all


def self_test() -> int:
    dock = (DOCK_CLASS, DOCK_METHOD, "passed")
    probes = [
        ("both exact passing packaged journeys pass", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), dock], True, ""),
        ("the legacy J23 journey alone no longer passes (#3158)", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")], False, ""),
        ("the shared-shell J30 dock journey alone does not pass", [dock], False, ""),
        ("a failed J30 dock journey fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (DOCK_CLASS, DOCK_METHOD, "failed")], False, ""),
        ("a duplicated J30 dock journey fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), dock, dock], False, ""),
        ("a skipped J30 without its own quarantine row fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (DOCK_CLASS, DOCK_METHOD, "skipped")], False,
         f"{REQUIRED_KEY}\t#3056\t2026-01-01\t2099-01-01\tsynthetic row for the other journey"),
        ("zero reports fail closed", None, False, ""),
        ("zero tests fail closed", [], False, ""),
        ("missing method fails", [(REQUIRED_CLASS, "other", "passed"), dock], False, ""),
        ("extra method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, "extra", "passed"), dock], False, ""),
        ("extra class fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), ("other.Journey", "run", "passed"), dock], False, ""),
        ("failed method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed"), dock], False, ""),
        ("skipped method without a quarantine row fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), dock], False, ""),
        ("skipped with an unexpired registered quarantine passes", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), dock], True,
         f"{REQUIRED_KEY}\t#3056\t2026-01-01\t2099-01-01\tsynthetic unexpired row"),
        # #3201: J23 quarantined must never make J30 optional (G3: the lane
        # still has to prove the shared-shell dock journey executed and passed).
        ("quarantined J23 with a failed J30 fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), (DOCK_CLASS, DOCK_METHOD, "failed")], False,
         f"{REQUIRED_KEY}\t#3201\t2026-01-01\t2099-01-01\tsynthetic unexpired row"),
        ("quarantined J23 with a skipped, unregistered J30 fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), (DOCK_CLASS, DOCK_METHOD, "skipped")], False,
         f"{REQUIRED_KEY}\t#3201\t2026-01-01\t2099-01-01\tsynthetic unexpired row"),
        ("quarantined J23 with J30 missing fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False,
         f"{REQUIRED_KEY}\t#3201\t2026-01-01\t2099-01-01\tsynthetic unexpired row"),
        ("skipped with an expired registered quarantine fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), dock], False,
         f"{REQUIRED_KEY}\t#3056\t2026-01-01\t2026-01-15\tsynthetic expired row"),
        ("skipped with a malformed registry row fails closed", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), dock], False,
         "this-row-has-no-tabs-at-all"),
        ("skipped with a row for a different method fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped"), dock], False,
         f"{REQUIRED_CLASS}#other\t#3056\t2026-01-01\t2099-01-01\tsynthetic other-method row"),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-hotkeys-results-") as temporary:
        root = Path(temporary)
        for index, (label, probe, expected, registry) in enumerate(probes):
            directory = root / str(index)
            registry_file = root / f"registry-{index}.txt"
            registry_file.write_text(registry, encoding="utf-8")
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
                validate_results(directory, registry_file)
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
                "tests": "2", "failures": "0", "errors": "0", "skipped": "0", "time": "0.01",
            })
            ET.SubElement(suite, "testcase", {
                "classname": REQUIRED_CLASS, "name": REQUIRED_METHOD, "time": duration,
            })
            ET.SubElement(suite, "testcase", {
                "classname": DOCK_CLASS, "name": DOCK_METHOD, "time": "0.01",
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
            registry_file = root / f"registry-strict-{index}.txt"
            registry_file.write_text("", encoding="utf-8")
            write_report(directory, exit_code=metadata.get("exit_code", "0"), duration=duration,
                         include_metadata=include_metadata)
            try:
                validate_results(directory, registry_file)
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
    parser.add_argument("--quarantine-file", type=Path, default=DEFAULT_QUARANTINE_FILE)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        executed = validate_results(args.results_dir, args.quarantine_file)
    except GateFailure as exc:
        print(f"BLOCK: {exc}", file=sys.stderr)
        return 1
    if executed:
        print(f"PASS: each packaged fast-key Docker journey passed exactly once ({', '.join(f'{c}#{m}' for c, m in REQUIRED)})")
    else:
        print("PASS (QUARANTINED): every fast-key journey reported exactly once; a skip was D36-sanctioned")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
