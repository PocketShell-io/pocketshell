#!/usr/bin/env python3
"""Require the signed 0.5.6-to-candidate migration lane to have really run.

Reads one run directory written by scripts/connected-js-key-vault-signed-upgrade.sh
and fails closed unless all three exact-method cycles executed once and passed,
every same-run source-hash report proves the legacy files were left unchanged,
the migrated host authenticated against Docker with the migrated key, and the
APK provenance records a genuine 0.5.6 install updated by a same-certificate
candidate. A zero-test, skipped, partial, or missing-evidence run is red.

Usage:
  scripts/check-js-signed-upgrade-results.py --artifacts-dir DIR
  scripts/check-js-signed-upgrade-results.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


REQUIRED_CLASS = "com.pocketshell.app.migration.InstalledDataMigrationJourneyTest"
REQUIRED_METHODS = frozenset(
    {
        "startupStagesLegacyDataAndLeavesOriginalFilesUntouched",
        "malformedEncryptedPreferencesAppearInPackagedWebView",
        "malformedPrivateKeyAppearsInPackagedWebViewAndLeavesSourceUntouched",
    }
)
# cycle directory -> (exact method, same-run hash report, extra required source)
CYCLES = {
    "migrated-host": (
        "startupStagesLegacyDataAndLeavesOriginalFilesUntouched",
        "migration-source-hashes.json",
        None,
    ),
    "malformed-source": (
        "malformedEncryptedPreferencesAppearInPackagedWebView",
        "malformed-encrypted-source-hashes.json",
        "shared_prefs/pocketshell-voice-secrets.xml",
    ),
    "malformed-key": (
        "malformedPrivateKeyAppearsInPackagedWebViewAndLeavesSourceUntouched",
        "malformed-key-source-hashes.json",
        None,
    ),
}
LEGACY_SOURCES = frozenset(
    {
        "databases/pocketshell.db",
        "shared_prefs/next_settings.xml",
        "shared_prefs/composer_drafts.xml",
        "shared_prefs/next_sync_selection.xml",
        "shared_prefs/workspace_order.xml",
        "files/ssh-keys/fixture.pem",
    }
)
FINGERPRINT = re.compile(r"^SHA256:[A-Za-z0-9+/]{43}$")


class GateFailure(ValueError):
    pass


def _junit_case(junit_dir: Path, method: str) -> None:
    reports = sorted(junit_dir.glob("TEST-*.xml")) if junit_dir.is_dir() else []
    if len(reports) != 1:
        raise GateFailure(f"{junit_dir}: expected exactly one TEST-*.xml, found {len(reports)}")
    try:
        root = ET.parse(reports[0]).getroot()
    except (ET.ParseError, OSError) as error:
        raise GateFailure(f"could not parse {reports[0]}: {error}") from error
    suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
    cases = [case for suite in suites for case in suite.findall("testcase")]
    for suite in suites:
        if int(suite.attrib.get("tests", "-1")) != len(suite.findall("testcase")):
            raise GateFailure(f"{reports[0]}: declared test count does not match testcase rows")
        if any(suite.attrib.get(key) != "0" for key in ("failures", "errors", "skipped")):
            raise GateFailure(f"{reports[0]}: suite reports failures, errors, or skips")
    identities = Counter((case.attrib.get("classname", ""), case.attrib.get("name", "")) for case in cases)
    if identities != Counter({(REQUIRED_CLASS, method): 1}):
        raise GateFailure(f"{reports[0]}: expected exactly {REQUIRED_CLASS}#{method}; found {sorted(identities.elements())}")
    if any(list(cases[0].iter(tag)) for tag in ("failure", "error", "skipped")):
        raise GateFailure(f"{REQUIRED_CLASS}#{method} failed or was skipped")


def _hash_report(path: Path, extra_source: str | None) -> int:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise GateFailure(f"source hash report is missing or malformed: {path}: {error}") from error
    before, after = report.get("sourceHashesBefore"), report.get("sourceHashesAfter")
    if report.get("schema") != 1 or report.get("unchanged") is not True:
        raise GateFailure(f"{path}: report does not declare schema 1 unchanged sources")
    if not isinstance(before, dict) or before != after or not before:
        raise GateFailure(f"{path}: legacy sources changed during migration")
    required = set(LEGACY_SOURCES) | ({extra_source} if extra_source else set())
    missing = sorted(required - set(before))
    if missing:
        raise GateFailure(f"{path}: hash report omitted legacy sources {missing}")
    return len(before)


def _authentication(path: Path, expected: str) -> None:
    try:
        lines = dict(
            line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines() if "=" in line
        )
    except OSError as error:
        raise GateFailure(f"migrated-host Docker authentication evidence is missing: {path}") from error
    if lines.get("accepted_publickey_fingerprint") != expected or not FINGERPRINT.match(expected):
        raise GateFailure(f"{path}: Docker did not accept the migrated key fingerprint {expected}")
    count = lines.get("accepted_external_log_line_count", "0")
    if not count.isdigit() or int(count) < 1:
        raise GateFailure(f"{path}: no same-run external authentication was recorded")


def validate(artifacts: Path) -> str:
    if not artifacts.is_dir():
        raise GateFailure(f"signed-upgrade artifacts directory is missing: {artifacts}")
    try:
        provenance = json.loads((artifacts / "apk-provenance.json").read_text(encoding="utf-8"))
        run = (artifacts / "provenance.txt").read_text(encoding="utf-8").strip().split("|")
    except (OSError, ValueError) as error:
        raise GateFailure(f"APK provenance is missing or malformed: {error}") from error
    legacy, candidate = provenance.get("legacy", {}), provenance.get("candidate", {})
    if "versionName='0.5.6'" not in legacy.get("metadata", "") or "name='com.pocketshell.app'" not in legacy.get("metadata", ""):
        raise GateFailure("legacy APK provenance is not com.pocketshell.app 0.5.6")
    if "name='com.pocketshell.app'" not in candidate.get("metadata", ""):
        raise GateFailure("candidate APK provenance is not com.pocketshell.app")
    if not legacy.get("certificateSha256") or legacy.get("certificateSha256") != candidate.get("certificateSha256"):
        raise GateFailure("legacy and candidate APK signing certificates differ")
    if legacy.get("sha256") == candidate.get("sha256"):
        raise GateFailure("candidate APK is byte-identical to the legacy APK; no upgrade happened")
    if len(run) != 6:
        raise GateFailure("provenance.txt must record run|api|certs|key fingerprint|host fingerprint")
    key_fingerprint = run[4]

    summary: list[str] = []
    for cycle, (method, report, extra_source) in CYCLES.items():
        cycle_dir = artifacts / cycle
        _junit_case(cycle_dir / "junit", method)
        sources = _hash_report(cycle_dir / report, extra_source)
        if cycle == "migrated-host":
            _authentication(cycle_dir / "migrated-host-authentication.txt", key_fingerprint)
        summary.append(f"{cycle}={method} 1/1 sources-unchanged={sources}/{sources}")
    if {method for method, _report, _extra in CYCLES.values()} != set(REQUIRED_METHODS):
        raise GateFailure("cycle table drifted from REQUIRED_METHODS")
    return "; ".join(summary)


def _write_run(root: Path, *, drop: str | None = None, mutate: str | None = None) -> None:
    root.mkdir(parents=True)
    fingerprint = "SHA256:" + "A" * 43
    (root / "apk-provenance.json").write_text(json.dumps({
        "schema": 1,
        "legacy": {"metadata": "package: name='com.pocketshell.app' versionCode='102' versionName='0.5.6'", "sha256": "a", "certificateSha256": "c"},
        "candidate": {"metadata": "package: name='com.pocketshell.app' versionCode='120' versionName='0.6.0-dev'", "sha256": "b", "certificateSha256": "d" if mutate == "cert" else "c"},
    }), encoding="utf-8")
    (root / "provenance.txt").write_text(f"run|35|c|c|{fingerprint}|{fingerprint}\n", encoding="utf-8")
    for cycle, (method, report, extra) in CYCLES.items():
        if drop == cycle:
            continue
        cycle_dir = root / cycle
        (cycle_dir / "junit").mkdir(parents=True)
        if not (mutate == "zero-tests" and cycle == "migrated-host"):
            suite = ET.Element("testsuite", tests="1", failures="0", errors="0", skipped="0")
            case = ET.SubElement(suite, "testcase", classname=REQUIRED_CLASS, name=method)
            if mutate == "skipped" and cycle == "malformed-key":
                ET.SubElement(case, "skipped")
                suite.set("skipped", "1")
            ET.ElementTree(suite).write(cycle_dir / "junit/TEST-migration.xml")
        hashes = {source: "0" * 64 for source in LEGACY_SOURCES | ({extra} if extra else set())}
        after = dict(hashes)
        if mutate == "changed" and cycle == "malformed-source":
            after["databases/pocketshell.db"] = "1" * 64
        if not (mutate == "no-hash" and cycle == "malformed-key"):
            (cycle_dir / report).write_text(json.dumps({
                "schema": 1, "unchanged": True, "sourceHashesBefore": hashes, "sourceHashesAfter": after,
            }), encoding="utf-8")
        if cycle == "migrated-host" and mutate != "no-auth":
            (cycle_dir / "migrated-host-authentication.txt").write_text(
                f"accepted_publickey_fingerprint={fingerprint}\naccepted_external_log_line_count=1\n", encoding="utf-8")


def self_test() -> int:
    cases = [
        ("complete three-cycle signed upgrade passes", {}, True),
        ("missing cycle fails", {"drop": "malformed-source"}, False),
        ("zero executed tests fail", {"mutate": "zero-tests"}, False),
        ("skipped cycle fails", {"mutate": "skipped"}, False),
        ("changed legacy source fails", {"mutate": "changed"}, False),
        ("missing hash report fails", {"mutate": "no-hash"}, False),
        ("missing migrated-key Docker authentication fails", {"mutate": "no-auth"}, False),
        ("mismatched signing certificate fails", {"mutate": "cert"}, False),
    ]
    failed = 0
    with tempfile.TemporaryDirectory(prefix="pocketshell-signed-upgrade-results-") as temporary:
        for index, (label, options, expected) in enumerate(cases):
            root = Path(temporary) / str(index)
            _write_run(root, **options)
            try:
                validate(root)
                actual = True
            except GateFailure:
                actual = False
            if actual != expected:
                print(f"FAIL: {label}", file=sys.stderr)
                failed += 1
            else:
                print(f"ok: {label}")
        try:
            validate(Path(temporary) / "absent")
            print("FAIL: absent run directory fails closed", file=sys.stderr)
            failed += 1
        except GateFailure:
            print("ok: absent run directory fails closed")
    total = len(cases) + 1
    print(f"Signed-upgrade result guard self-test: {total - failed}/{total} checks passed")
    return int(failed != 0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--artifacts-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.artifacts_dir is None:
        parser.error("--artifacts-dir is required")
    try:
        summary = validate(args.artifacts_dir)
    except GateFailure as error:
        print(f"FAIL: signed-upgrade migration lane: {error}", file=sys.stderr)
        return 1
    print(f"PASS: signed-upgrade migration lane executed 3 tests ({REQUIRED_CLASS}): {summary}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
