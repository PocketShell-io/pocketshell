#!/usr/bin/env python3
"""Require the exact packaged shared-app journeys (#2936, #2952) to execute and pass once.

Also the host half of #2952's byte oracle: the raw-mode reader on the Docker
fixture writes the bytes it received as hex, the runner copies that file next
to the results (HOST_BYTES_NAME), and it must equal EXPECTED_IME_BYTES_HEX —
independently of what the journey read back from the terminal.
"""

from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.SharedAppDockerJourneyTest"
REQUIRED_METHODS = frozenset(
    {
        "sharedAppListsAttachesAndTypesIntoFixtureSession",
        "sharedTerminalDeliversImeEditsAsExactBytes",
    }
)
HOST_BYTES_NAME = "host-ime-bytes.hex"
# SharedAppDockerJourneyTest.EXPECTED_IME_BYTES, as the host must have received it.
EXPECTED_IME_BYTES = (
    "echo "
    "word\x7fk "
    "teh\x7f\x7fhe "
    "\x7fn "
    "ls -la"
    "\r"
    "a1b"
    "ok"
    "\t\r"
    "PASTE1"
)
EXPECTED_IME_BYTES_HEX = EXPECTED_IME_BYTES.encode("utf-8").hex()
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
    expected = Counter({(REQUIRED_CLASS, method): 1 for method in REQUIRED_METHODS})
    if actual != expected:
        raise GateFailure(
            f"expected exactly {REQUIRED_CLASS}#{{{', '.join(sorted(REQUIRED_METHODS))}}}; found {sorted(actual.elements())}"
        )

    for case in cases:
        name = f"{REQUIRED_CLASS}#{case.attrib.get('name', '')}"
        if list(case.iter("failure")) or list(case.iter("error")):
            raise GateFailure(f"{name} failed")
        if list(case.iter("skipped")):
            raise GateFailure(f"{name} was skipped")
    return reports


def validate_host_bytes(path: Path) -> None:
    if not path.is_file():
        raise GateFailure(f"the host reader's byte record is missing: {path}")
    recorded = path.read_text(encoding="utf-8").strip()
    if recorded != EXPECTED_IME_BYTES_HEX:
        try:
            decoded = repr(bytes.fromhex(recorded).decode("utf-8", "replace"))
        except ValueError:
            decoded = "<not hex>"
        raise GateFailure(
            f"the host received {decoded} ({recorded}); expected {EXPECTED_IME_BYTES!r} ({EXPECTED_IME_BYTES_HEX})"
        )


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
    first, second = sorted(REQUIRED_METHODS)
    both = [(REQUIRED_CLASS, first, "passed"), (REQUIRED_CLASS, second, "passed")]
    good_bytes = EXPECTED_IME_BYTES_HEX
    duplicated = ("echo PS2 PS936".encode() + EXPECTED_IME_BYTES.encode()).hex()
    cases = [
        ("both exact passing shared-app journeys pass", both, good_bytes, True),
        ("zero tests block", [], good_bytes, False),
        ("a missing IME journey blocks", [(REQUIRED_CLASS, first, "passed")], good_bytes, False),
        ("a missing attach journey blocks", [(REQUIRED_CLASS, second, "passed")], good_bytes, False),
        ("unexpected class blocks", [("example.OtherJourney", first, "passed"), (REQUIRED_CLASS, second, "passed")], good_bytes, False),
        ("extra test blocks", both + [(REQUIRED_CLASS, "extra", "passed")], good_bytes, False),
        ("duplicate test blocks", both + [(REQUIRED_CLASS, first, "passed")], good_bytes, False),
        ("failed journey blocks", [(REQUIRED_CLASS, first, "passed"), (REQUIRED_CLASS, second, "failed")], good_bytes, False),
        ("skipped journey blocks", [(REQUIRED_CLASS, first, "skipped"), (REQUIRED_CLASS, second, "passed")], good_bytes, False),
        ("missing host byte record blocks", both, None, False),
        ("duplicated host bytes block", both, duplicated, False),
        ("empty host byte record blocks", both, "", False),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-shared-app-results-") as temporary:
        root = Path(temporary)
        for index, (label, identities, host_bytes, expected_pass) in enumerate(cases):
            results = root / str(index)
            results.mkdir(parents=True)
            if identities:
                _write_report(results / "TEST-shared-app.xml", identities)
            if host_bytes is not None:
                (results / HOST_BYTES_NAME).write_text(host_bytes + "\n", encoding="utf-8")
            try:
                validate_results(results)
                validate_host_bytes(results / HOST_BYTES_NAME)
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
    parser.add_argument(
        "--host-bytes",
        type=Path,
        help=f"the host reader's hex record (default: <results-dir>/{HOST_BYTES_NAME})",
    )
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    host_bytes = args.host_bytes or args.results_dir / HOST_BYTES_NAME
    try:
        reports = validate_results(args.results_dir)
        validate_host_bytes(host_bytes)
        if args.evidence_dir:
            args.evidence_dir.mkdir(parents=True, exist_ok=True)
            for report in reports:
                shutil.copy2(report, args.evidence_dir / report.name)
            if host_bytes.resolve() != (args.evidence_dir / HOST_BYTES_NAME).resolve():
                shutil.copy2(host_bytes, args.evidence_dir / HOST_BYTES_NAME)
    except GateFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {REQUIRED_CLASS}#{{{', '.join(sorted(REQUIRED_METHODS))}}} each executed exactly once")
    print(f"PASS: the host received exactly the scripted IME bytes ({EXPECTED_IME_BYTES_HEX})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
