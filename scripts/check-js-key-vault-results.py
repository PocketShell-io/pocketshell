#!/usr/bin/env python3
"""Require the exact packaged opaque-key Docker journey to execute and pass once."""

from __future__ import annotations

import argparse
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.SshKeyVaultDockerJourneyTest"
REQUIRED_METHOD = "importsEncryptedDocumentConnectsAndKeepsSecretsOutOfWebViewState"


class GateFailure(ValueError):
    pass


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
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise GateFailure(f"{report}: expected JUnit <testsuite> or non-empty <testsuites>")
        for suite in suites:
            suite_cases = list(suite.findall("testcase"))
            if int(suite.attrib.get("tests", "-1")) != len(suite_cases):
                raise GateFailure(f"{report}: declared test count does not match testcase rows")
            for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                actual = sum(1 for case in suite_cases for _ in case.iter(tag))
                if int(suite.attrib.get(summary, "-1")) != actual:
                    raise GateFailure(f"{report}: declared {summary} count does not match testcase rows")
            cases.extend(suite_cases)
    actual = Counter((case.attrib.get("classname", ""), case.attrib.get("name", "")) for case in cases)
    if actual != Counter({(REQUIRED_CLASS, REQUIRED_METHOD): 1}):
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {sorted(actual.elements())}")
    case = cases[0]
    if any(list(case.iter(tag)) for tag in ("failure", "error", "skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{REQUIRED_METHOD} failed or was skipped")
    return reports


def _write(path: Path, identity: tuple[str, str], status: str = "passed") -> None:
    suite = ET.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
    case = ET.SubElement(suite, "testcase", classname=identity[0], name=identity[1])
    if status != "passed":
        ET.SubElement(case, status)
        suite.set("failures" if status == "failure" else "skipped", "1")
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    cases = [
        ("exact passing test", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")], True),
        ("zero tests fail closed", [], False),
        ("wrong method fails", [(REQUIRED_CLASS, "other", "passed")], False),
        ("extra test fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, "extra", "passed")], False),
        ("duplicate exact test fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "passed"), (REQUIRED_CLASS, REQUIRED_METHOD, "passed")], False),
        ("failure fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "failure")], False),
        ("skip fails", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
    ]
    failed = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-key-vault-results-") as temporary:
        for index, (label, identities, expected) in enumerate(cases):
            results = Path(temporary) / str(index)
            for row_index, (class_name, method, status) in enumerate(identities):
                _write(results / f"TEST-{row_index}.xml", (class_name, method), status)
            try:
                validate_results(results)
                actual = True
            except (GateFailure, ValueError):
                actual = False
            if actual != expected:
                print(f"FAIL: {label}", file=sys.stderr)
                failed += 1
            else:
                print(f"ok: {label}")
    print(f"Key-vault result guard self-test: {len(cases) - failed}/{len(cases)} checks passed")
    return int(failed != 0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=Path("android/app/build/outputs/androidTest-results/connected/debug"))
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
                (args.evidence_dir / report.name).write_bytes(report.read_bytes())
    except (GateFailure, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} executed exactly once")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
