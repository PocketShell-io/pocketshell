#!/usr/bin/env python3
"""Require the packaged durable-storage restart journey (#2993) to execute and pass.

Two modes:

  --results-dir DIR   one phase directory: exactly one passing
                      DurableStorageRestartJourneyTest#<method> testcase.
  --run-dir DIR       a whole runner directory: phase-seed and phase-verify
                      each pass exactly once, and kill-gap.txt proves the
                      mutate phase's process was SIGKILLed less than
                      MAX_KILL_GAP_MS after it acknowledged its writes. That
                      phase ends in its own kill, so it has no JUnit pass. A
                      slower kill would let a lazily-flushing store pass
                      vacuously, so it fails closed instead of counting as
                      green.
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.DurableStorageRestartJourneyTest"
REQUIRED_METHOD = "userDataWritesSurviveForceStopShortlyAfterAcknowledgement"
PHASES = ("seed", "verify")
MAX_KILL_GAP_MS = 1000
DEFAULT_RESULTS_DIR = Path("android/app/build/outputs/androidTest-results/connected/debug")


class GateFailure(ValueError):
    """The durable-storage journey result is missing, extra, skipped, red, or not a fast kill."""


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
    if actual != [(REQUIRED_CLASS, REQUIRED_METHOD)]:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {actual or '<zero tests>'}")
    case = testcases[0][2]
    if list(case.iter("failure")) or list(case.iter("error")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} failed")
    if list(case.iter("skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} was skipped")


def read_kill_gap(path: Path) -> int:
    if not path.is_file():
        raise GateFailure(f"kill-gap evidence is missing: {path}")
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    try:
        ack = int(values["ack_epoch_ms"])
        killed = int(values["killed_epoch_ms"])
    except (KeyError, ValueError) as exc:
        raise GateFailure(f"{path}: ack_epoch_ms and killed_epoch_ms must be integers") from exc
    if values.get("process_after_kill") != "absent":
        raise GateFailure(f"{path}: the app process was not proven absent after the kill")
    gap = killed - ack
    if gap < 0:
        raise GateFailure(f"{path}: kill time precedes the acknowledgement ({gap} ms)")
    if gap >= MAX_KILL_GAP_MS:
        raise GateFailure(
            f"{path}: process died {gap} ms after the acknowledgement; the journey only proves durability "
            f"for a kill under {MAX_KILL_GAP_MS} ms"
        )
    return gap


def validate_run(run_dir: Path) -> int:
    for phase in PHASES:
        validate_results(run_dir / f"phase-{phase}")
    return read_kill_gap(run_dir / "kill-gap.txt")


def _write_report(directory: Path, cases: list[tuple[str, str, str]]) -> None:
    directory.mkdir(parents=True)
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
    ET.ElementTree(suite).write(directory / "TEST-durable.xml", encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    ok = [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")]
    phase_probes = [
        ("one exact passing phase passes", ok, True),
        ("zero reports fail closed", None, False),
        ("zero tests fail closed", [], False),
        ("missing required method fails", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("extra method fails", ok + [(REQUIRED_CLASS, "extra", "passed")], False),
        ("extra class fails", ok + [("other.Journey", "run", "passed")], False),
        ("duplicate method fails", ok + ok, False),
        ("failed test fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
        ("skipped test fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
    ]
    gap_ok = "ack_epoch_ms=1000\nkilled_epoch_ms=1650\nprocess_after_kill=absent\n"
    run_probes = [
        ("three passing phases and a fast kill pass", PHASES, gap_ok, True),
        ("a missing verify phase fails", ("seed",), gap_ok, False),
        ("a missing seed phase fails", ("verify",), gap_ok, False),
        ("missing kill-gap evidence fails", PHASES, None, False),
        ("a kill one second after the acknowledgement fails", PHASES,
         "ack_epoch_ms=1000\nkilled_epoch_ms=2000\nprocess_after_kill=absent\n", False),
        ("a live process after the kill fails", PHASES,
         "ack_epoch_ms=1000\nkilled_epoch_ms=1200\nprocess_after_kill=present\n", False),
        ("a kill before the acknowledgement fails", PHASES,
         "ack_epoch_ms=1000\nkilled_epoch_ms=900\nprocess_after_kill=absent\n", False),
        ("malformed kill-gap evidence fails", PHASES, "ack_epoch_ms=soon\n", False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-durable-results-") as temporary:
        root = Path(temporary)
        for index, (label, cases, should_pass) in enumerate(phase_probes):
            directory = root / f"phase-probe-{index}"
            if cases is not None:
                _write_report(directory, cases)
            try:
                validate_results(directory)
                passed = True
            except GateFailure:
                passed = False
            failures += _report(label, passed, should_pass)
        for index, (label, phases, gap, should_pass) in enumerate(run_probes):
            run_dir = root / f"run-probe-{index}"
            run_dir.mkdir()
            for phase in phases:
                _write_report(run_dir / f"phase-{phase}", ok)
            if gap is not None:
                (run_dir / "kill-gap.txt").write_text(gap, encoding="utf-8")
            try:
                validate_run(run_dir)
                passed = True
            except GateFailure:
                passed = False
            failures += _report(label, passed, should_pass)
    return 1 if failures else 0


def _report(label: str, passed: bool, should_pass: bool) -> int:
    if passed != should_pass:
        print(f"FAIL: self-test {label}", file=sys.stderr)
        return 1
    print(f"PASS: self-test {label}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--results-dir", type=Path)
    mode.add_argument("--run-dir", type=Path)
    mode.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    try:
        if args.run_dir is not None:
            gap = validate_run(args.run_dir)
            print(f"PASS: seed and verify phases passed and the mutate-phase app was killed {gap} ms after the "
                  f"acknowledgement ({REQUIRED_CLASS}#{REQUIRED_METHOD})")
            return 0
        validate_results(args.results_dir or DEFAULT_RESULTS_DIR)
    except GateFailure as exc:
        print(f"BLOCK: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: exactly one packaged durable-storage phase passed ({REQUIRED_CLASS}#{REQUIRED_METHOD})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
