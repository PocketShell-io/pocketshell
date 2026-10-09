#!/usr/bin/python3 -I
"""Fail closed unless the packaged JS usage/ports journey ran exactly once.

A skipped run is sanctioned ONLY by an unexpired, well-formed row for this
exact method in scripts/journey-quarantine.txt (policy D36) — the registry
scripts/check-journey-quarantine-expiry.sh reconciles against the @Ignore on
the source. Missing, malformed, expired or foreign rows fail closed, exactly
like scripts/check-js-hotkeys-journey-results.py.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from datetime import date
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.smoke.UsagePortsDockerJourneyTest"
REQUIRED_METHOD = "usageAndPortForwardingPoliciesUseDockerAndNativePlugin"
REQUIRED_KEY = f"{REQUIRED_CLASS}#{REQUIRED_METHOD}"
DEFAULT_RESULTS = Path("android/app/build/outputs/androidTest-results/connected/debug")
DEFAULT_QUARANTINE_FILE = Path(__file__).resolve().parent / "journey-quarantine.txt"


class GateFailure(ValueError):
    pass


def quarantine_sanction(quarantine_file: Path):
    """((issue, expires), None) when an unexpired well-formed row sanctions the
    skip; (None, reason) otherwise. Fail-closed in every unclear case."""
    try:
        text = quarantine_file.read_text(encoding="utf-8")
    except OSError:
        return None, f"quarantine registry is missing or unreadable: {quarantine_file}"
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if fields[0] != REQUIRED_KEY:
            continue
        if len(fields) != 5:
            return None, f"registry row for {REQUIRED_KEY} is malformed (expected 5 TAB-separated fields, got {len(fields)})"
        _, issue, added, expires, reason = fields
        if not issue.strip() or not reason.strip():
            return None, f"registry row for {REQUIRED_KEY} has an empty issue or reason"
        try:
            added_date = date.fromisoformat(added)
            expires_date = date.fromisoformat(expires)
        except ValueError:
            return None, f"registry row for {REQUIRED_KEY} carries a non-ISO date (added={added}, expires={expires})"
        if expires_date <= added_date:
            return None, f"registry row for {REQUIRED_KEY} expires ({expires}) before it was added ({added})"
        if date.today() > expires_date:
            return None, f"registry row for {REQUIRED_KEY} EXPIRED {expires} — resolve or re-triage it per policy D36"
        return (issue, expires_date), None
    return None, f"no registry row for {REQUIRED_KEY}"


def validate(results: Path, quarantine_file: Path = DEFAULT_QUARANTINE_FILE) -> bool:
    """Return True when the exact journey passed, False for a D36-sanctioned skip."""
    if not results.is_dir():
        raise GateFailure(f"instrumentation results directory is missing: {results}")
    reports = sorted(results.rglob("TEST-*.xml"))
    if not reports:
        raise GateFailure(f"no instrumentation XML found under {results}")

    discovered: list[tuple[str, str, bool, bool]] = []
    declared_tests = 0
    for report in reports:
        try:
            root = ET.parse(report).getroot()
        except (ET.ParseError, OSError) as error:
            raise GateFailure(f"could not parse {report}: {error}") from error
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise GateFailure(f"{report}: expected a <testsuite> report")
        for suite in suites:
            cases = list(suite.findall("testcase"))
            try:
                declared = int(suite.attrib["tests"])
            except (KeyError, ValueError) as error:
                raise GateFailure(f"{report}: missing or invalid test count") from error
            if declared != len(cases):
                raise GateFailure(f"{report}: declares {declared} tests but contains {len(cases)} cases")
            declared_tests += declared
            for summary, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                try:
                    reported = int(suite.attrib.get(summary, "0"))
                except ValueError as error:
                    raise GateFailure(f"{report}: invalid {summary} count") from error
                actual = sum(1 for case in cases for child in case.iter(tag))
                if reported != actual:
                    raise GateFailure(f"{report}: {summary} says {reported}, testcase details show {actual}")
            for case in cases:
                class_name = case.attrib.get("classname", "")
                method_name = case.attrib.get("name", "")
                if not class_name or not method_name:
                    raise GateFailure(f"{report}: testcase is missing its class or method")
                discovered.append((
                    class_name,
                    method_name,
                    bool(list(case.iter("failure")) or list(case.iter("error"))),
                    bool(list(case.iter("skipped"))),
                ))

    counts = Counter((class_name, method_name) for class_name, method_name, _, _ in discovered)
    duplicates = [f"{class_name}#{method} x{count}" for (class_name, method), count in counts.items() if count != 1]
    if duplicates:
        raise GateFailure("duplicate test cases: " + ", ".join(sorted(duplicates)))
    identities = {(class_name, method) for class_name, method, _, _ in discovered}
    required = {(REQUIRED_CLASS, REQUIRED_METHOD)}
    if identities != required or len(discovered) != 1 or declared_tests != 1:
        raise GateFailure(f"expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD}; found {sorted(identities)}")
    failures = [f"{name}#{method}" for name, method, failed, _ in discovered if failed]
    skipped = [f"{name}#{method}" for name, method, _, was_skipped in discovered if was_skipped]
    if failures:
        raise GateFailure("failed tests: " + ", ".join(failures))
    if skipped:
        sanction, why = quarantine_sanction(quarantine_file)
        if sanction is None:
            raise GateFailure("skipped tests: " + ", ".join(skipped) + f" (no unexpired quarantine row sanctions it: {why})")
        issue, expires = sanction
        print(f"QUARANTINED (policy D36): {REQUIRED_KEY} skipped; tracked {issue}, expires {expires.isoformat()} ({quarantine_file})")
        return False
    return True


LAUNCHER_PHASES = ("HTTP_START", "HTTP_CLEANUP")


def validate_launcher_evidence(logcat: Path, run_id: str) -> None:
    """Require same-run proof that both phases found Prompt closed and opened it by a trusted physical tap.

    A scripted launcher activation would open the sheet before the journey's
    physical tap (so the phase line is missing or says sheetOpenOnEntry=true) or
    would record untrusted launcher events; either is red here.
    """
    if not run_id or not re.fullmatch(r"[A-Za-z0-9._-]+", run_id):
        raise GateFailure(f"invalid run id for launcher evidence: {run_id!r}")
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        raise GateFailure(f"launcher evidence logcat is unreadable: {logcat}: {error}") from error
    prefix = f"UsagePortsDockerJourney: RUN {run_id} "
    events: list[tuple[int, str, str]] = []
    for number, line in enumerate(lines):
        at = line.find(prefix)
        if at < 0:
            continue
        rest = line[at + len(prefix):]
        name, _, fields = rest.partition(" ")
        events.append((number, name, fields))
    if any(name.endswith("_PROMPT_ALREADY_OPEN_AFTER_FAILURE") for _, name, _ in events):
        raise GateFailure("the Prompt sheet was already open when a cleanup phase started")

    def only(name: str) -> tuple[int, str]:
        found = [(number, fields) for number, event, fields in events if event == name]
        if len(found) != 1:
            raise GateFailure(f"expected exactly one {name} line for run {run_id}; found {len(found)}")
        return found[0]

    order: list[tuple[str, int]] = []
    for phase in LAUNCHER_PHASES:
        if phase == "HTTP_CLEANUP":
            start_line, start_fields = only("HTTP_CLEANUP_START")
            if not re.search(r"(?:^| )route=ports(?: |$)", start_fields) \
               or not re.search(r"(?:^| )composerVisible=false(?: |$)", start_fields):
                raise GateFailure("HTTP cleanup must start on Ports with the Composer hidden")
            order.append(("HTTP_CLEANUP_START", start_line))
        tap_line, tap_fields = only(f"{phase}_PROMPT_LAUNCHER_TAP")
        head = tap_fields.split(" before=", 1)[0]
        fields = dict(part.split("=", 1) for part in head.split() if "=" in part)
        expected = {
            "sheetOpenOnEntry": "false",
            "trustedLauncherTapComplete": "true",
            "launcherClicks": "1",
            "zeroDetailLauncherClicks": "0",
            "keyboardLauncherEvents": "0",
            "keyEvents": "0",
            "untrustedLauncherEvents": "0",
            "promptSheetOpen": "true",
        }
        for key, value in expected.items():
            if fields.get(key) != value:
                raise GateFailure(f"{phase}_PROMPT_LAUNCHER_TAP needs {key}={value}; saw {fields.get(key)!r}")
        if fields.get("attempts") not in ("1", "2", "3"):
            raise GateFailure(f"{phase}_PROMPT_LAUNCHER_TAP needs 1-3 attempts; saw {fields.get('attempts')!r}")
        order.append((f"{phase}_PROMPT_LAUNCHER_TAP", tap_line))
        ready_line, _ = only(f"{phase}_COMPOSER_READY")
        order.append((f"{phase}_COMPOSER_READY", ready_line))
    positions = [line for _, line in order]
    if positions != sorted(positions):
        raise GateFailure("launcher evidence is out of order: " + ", ".join(name for name, _ in order))


def write_report(directory: Path, cases: list[tuple[str, str, str]]) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    suite = ET.Element("testsuite", {
        "name": REQUIRED_CLASS,
        "tests": str(len(cases)),
        "failures": str(sum(status == "failed" for _, _, status in cases)),
        "errors": "0",
        "skipped": str(sum(status == "skipped" for _, _, status in cases)),
    })
    for class_name, method_name, status in cases:
        case = ET.SubElement(suite, "testcase", {"classname": class_name, "name": method_name})
        if status == "failed":
            ET.SubElement(case, "failure", {"message": "synthetic failure"})
        elif status == "skipped":
            ET.SubElement(case, "skipped", {"message": "synthetic skip"})
    ET.ElementTree(suite).write(directory / "TEST-usage-ports.xml", encoding="utf-8", xml_declaration=True)


def self_test() -> int:
    good = [(REQUIRED_CLASS, REQUIRED_METHOD, "passed")]
    skip = [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")]
    today = date.today()
    live = f"{REQUIRED_KEY}\t#3076\t{today.isoformat()}\t{date.fromordinal(today.toordinal() + 14).isoformat()}\tselftest\n"
    expired = f"{REQUIRED_KEY}\t#3076\t2020-01-01\t2020-01-15\tselftest\n"
    malformed = f"{REQUIRED_KEY}\t#3076\t{today.isoformat()}\n"
    foreign = f"{REQUIRED_CLASS}#otherMethod\t#3076\t{today.isoformat()}\t2099-01-01\tselftest\n"
    backwards = f"{REQUIRED_KEY}\t#3076\t2099-01-02\t2099-01-01\tselftest\n"
    registry_probes: list[tuple[str, list[tuple[str, str, str]], str | None, bool]] = [
        ("skipped journey with an unexpired registry row passes", skip, live, True),
        ("skipped journey with an expired registry row blocks", skip, expired, False),
        ("skipped journey with a malformed registry row blocks", skip, malformed, False),
        ("skipped journey with only a foreign method's row blocks", skip, foreign, False),
        ("skipped journey with expires before added blocks", skip, backwards, False),
        ("skipped journey with a missing registry file blocks", skip, None, False),
        ("failed journey still blocks under a live registry row", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], live, False),
        ("duplicate skipped journey still blocks under a live registry row", skip + skip, live, False),
    ]
    probes: list[tuple[str, list[tuple[str, str, str]] | None, bool]] = [
        ("exact packaged journey passes", good, True),
        ("missing result XML blocks", None, False),
        ("zero tests block", [], False),
        ("missing method blocks", [(REQUIRED_CLASS, "otherMethod", "passed")], False),
        ("unexpected class blocks", [("other.Journey", REQUIRED_METHOD, "passed")], False),
        ("duplicate journey blocks", good + good, False),
        ("skipped journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "skipped")], False),
        ("failed journey blocks", [(REQUIRED_CLASS, REQUIRED_METHOD, "failed")], False),
    ]
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-usage-ports-results-") as scratch:
        root = Path(scratch)
        for index, (label, cases, expected) in enumerate(probes):
            report_dir = root / f"case-{index}"
            if cases is not None:
                write_report(report_dir, cases)
            empty_registry = root / f"registry-empty-{index}.txt"
            empty_registry.write_text("", encoding="utf-8")
            try:
                validate(report_dir, empty_registry)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: usage/ports result guard probe {index + 1}: {label}", file=sys.stderr)
                return 1
            print(f"ok [{index + 1}/{len(probes)}] {label}")
        for index, (label, cases, registry, expected) in enumerate(registry_probes, start=1):
            report_dir = root / f"registry-case-{index}"
            write_report(report_dir, cases)
            registry_file = root / f"registry-{index}.txt"
            if registry is not None:
                registry_file.write_text(registry, encoding="utf-8")
            try:
                validate(report_dir, registry_file)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: usage/ports quarantine probe {index}: {label}", file=sys.stderr)
                return 1
            print(f"ok [{index}/{len(registry_probes)}] quarantine: {label}")
    print(f"PASS: packaged usage/ports result guard checks ({len(probes)}/{len(probes)}) "
          f"and D36 quarantine sanction checks ({len(registry_probes)}/{len(registry_probes)})")
    return launcher_self_test()


def launcher_self_test() -> int:
    run_id = "js2908selftest"

    def line(event: str, fields: str) -> str:
        return f"09-30 23:06:29.139  3598  3613 I UsagePortsDockerJourney: RUN {run_id} {event} {fields}"

    def tap(phase: str, **overrides: str) -> str:
        fields = {"sheetOpenOnEntry": "false", "attempts": "1", "trustedLauncherTapComplete": "true",
                  "launcherClicks": "1", "zeroDetailLauncherClicks": "0", "keyboardLauncherEvents": "0",
                  "keyEvents": "0", "untrustedLauncherEvents": "0", "promptSheetOpen": "true"}
        fields.update(overrides)
        return line(f"{phase}_PROMPT_LAUNCHER_TAP", "sessionTag=t " + " ".join(f"{k}={v}" for k, v in fields.items())
                    + ' before={"promptComposerOpen":"false"} taps=[] events=[]')

    good = [
        tap("HTTP_START"),
        line("HTTP_START_COMPOSER_READY", "route=home transportState=connected"),
        line("HTTP_CLEANUP_START", "route=ports composerVisible=false sshPhase=live"),
        tap("HTTP_CLEANUP"),
        line("HTTP_CLEANUP_COMPOSER_READY", "route=home transportState=connected"),
    ]
    probes: list[tuple[str, list[str], bool]] = [
        ("both phases physically opened Prompt", good, True),
        ("cleanup launcher tap missing (sheet opened some other way)", [good[0], good[1], good[2], good[4]], False),
        ("startup launcher tap missing", good[1:], False),
        ("sheet already open when cleanup started", good[:3] + [tap("HTTP_CLEANUP", sheetOpenOnEntry="true")] + good[4:], False),
        ("untrusted launcher event recorded", good[:3] + [tap("HTTP_CLEANUP", untrustedLauncherEvents="1")] + good[4:], False),
        ("trusted launcher click not completed", good[:3] + [tap("HTTP_CLEANUP", trustedLauncherTapComplete="false")] + good[4:], False),
        ("sheet not open after the tap", [tap("HTTP_START", promptSheetOpen="false")] + good[1:], False),
        ("two clicks reached the launcher (ignored tap + keyboard click)",
         good[:3] + [tap("HTTP_CLEANUP", launcherClicks="2")] + good[4:], False),
        ("keyboard-generated (detail 0) launcher click", [tap("HTTP_START", zeroDetailLauncherClicks="1")] + good[1:], False),
        ("keyboard event on the launcher", good[:3] + [tap("HTTP_CLEANUP", keyboardLauncherEvents="2")] + good[4:], False),
        ("keyboard input during the launcher open", [tap("HTTP_START", keyEvents="1")] + good[1:], False),
        ("launcher modality fields missing (pre-hardening line)",
         good[:3] + [tap("HTTP_CLEANUP").replace(" launcherClicks=1", "")] + good[4:], False),
        ("more than three attempts", good[:3] + [tap("HTTP_CLEANUP", attempts="4")] + good[4:], False),
        ("cleanup tap before cleanup start", [good[0], good[1], good[3], good[2], good[4]], False),
        ("duplicate cleanup tap", good + [good[3]], False),
        ("cleanup started with the Composer visible",
         good[:2] + [line("HTTP_CLEANUP_START", "route=ports composerVisible=true")] + good[3:], False),
        ("after-failure fallback found the sheet open",
         good + [line("HTTP_CLEANUP_PROMPT_ALREADY_OPEN_AFTER_FAILURE", "sessionTag=t")], False),
        ("another run's evidence does not count", [entry.replace(run_id, "js-other") for entry in good], False),
    ]
    with tempfile.TemporaryDirectory(prefix="pocketshell-js-usage-ports-launcher-") as scratch:
        for index, (label, entries, expected) in enumerate(probes, start=1):
            logcat = Path(scratch) / f"case-{index}.txt"
            logcat.write_text("\n".join(entries) + "\n", encoding="utf-8")
            try:
                validate_launcher_evidence(logcat, run_id)
                passed = True
            except GateFailure:
                passed = False
            if passed != expected:
                print(f"FAIL: usage/ports launcher evidence probe {index}: {label}", file=sys.stderr)
                return 1
            print(f"ok [{index}/{len(probes)}] launcher evidence: {label}")
    print(f"PASS: packaged usage/ports launcher evidence checks ({len(probes)}/{len(probes)})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS)
    parser.add_argument("--launcher-logcat", type=Path,
                        help="same-run logcat; requires trusted physical Prompt launcher evidence for both phases")
    parser.add_argument("--run-id", help="run id whose launcher evidence --launcher-logcat must contain")
    parser.add_argument("--quarantine-file", type=Path, default=DEFAULT_QUARANTINE_FILE)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if (args.launcher_logcat is None) != (args.run_id is None):
        print("FAIL: --launcher-logcat and --run-id must be given together", file=sys.stderr)
        return 2
    try:
        executed = validate(args.results_dir, args.quarantine_file)
        if not executed:
            # A sanctioned skip produced no journey evidence; nothing else to check.
            print(f"PASS (QUARANTINED): {REQUIRED_KEY} reported exactly once as a D36-sanctioned skip")
            return 0
        if args.launcher_logcat is not None:
            validate_launcher_evidence(args.launcher_logcat, args.run_id)
    except GateFailure as error:
        print(f"FAIL: packaged JS usage/ports journey: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} executed exactly once")
    if args.launcher_logcat is not None:
        print(f"PASS: both Usage/Ports phases opened Prompt by a trusted physical launcher tap ({args.run_id})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
