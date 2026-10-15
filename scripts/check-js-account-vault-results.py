#!/usr/bin/env python3
"""Fail-closed result check for the packaged account key-vault journey (key-vault W4).

Requires, from the same run: both SharedVaultDockerJourneyTest methods in the
JUnit XML, passed (no skips, failures, errors or extra tests); every required
screenshot present as a complete PNG; and no credential line in the app logcat.
"""
from __future__ import annotations

import argparse
import struct
import sys
import tempfile
import xml.etree.ElementTree as ET
import zlib
from pathlib import Path

REQUIRED_CLASS = "com.pocketshell.app.smoke.SharedVaultDockerJourneyTest"
REQUIRED_METHODS = frozenset({
    "typeEveryTimeUnlockDialsTheWebSavedKeyWithNoKeyPicker",
    "rememberOnThisDeviceUnlocksSilentlyNextLaunchAndSignOutForgets",
})
SCREENSHOTS = {
    "vault-unlock-on-launch.png",
    "vault-unlock-wrong-passphrase.png",
    "vault-unlocked-account-host.png",
    "vault-connected-with-vault-key.png",
    "vault-unlock-remember-chosen.png",
    "vault-remembered-next-launch.png",
}


class CheckFailure(Exception):
    pass


def check_png(path: Path) -> None:
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise CheckFailure(f"{path.name} is not a PNG")
    offset, seen = 8, set()
    while offset < len(data):
        if offset + 12 > len(data):
            raise CheckFailure(f"{path.name}: truncated chunk")
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        kind = data[offset + 4:offset + 8]
        end = offset + 12 + length
        if end > len(data) or zlib.crc32(kind + data[offset + 8:offset + 8 + length]) & 0xFFFFFFFF != struct.unpack(">I", data[end - 4:end])[0]:
            raise CheckFailure(f"{path.name}: corrupt chunk")
        seen.add(kind)
        offset = end
        if kind == b"IEND":
            break
    if not {b"IHDR", b"IDAT", b"IEND"} <= seen or offset != len(data):
        raise CheckFailure(f"{path.name}: incomplete PNG")


def check(results_dir: Path, screenshots_dir: Path, redaction: Path) -> str:
    executed: dict[str, str] = {}
    for report in sorted(results_dir.glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        for suite in [root] if root.tag == "testsuite" else root.findall("testsuite"):
            for case in suite.findall("testcase"):
                name = case.attrib.get("name", "")
                if case.attrib.get("classname") != REQUIRED_CLASS:
                    raise CheckFailure(f"unexpected test class executed: {case.attrib.get('classname')}#{name}")
                status = "passed"
                for tag in ("skipped", "failure", "error"):
                    if case.find(tag) is not None:
                        status = tag
                if name in executed:
                    raise CheckFailure(f"{name} executed twice")
                executed[name] = status
    if set(executed) != REQUIRED_METHODS:
        raise CheckFailure(f"executed {sorted(executed)}; required exactly {sorted(REQUIRED_METHODS)}")
    bad = {name: status for name, status in executed.items() if status != "passed"}
    if bad:
        raise CheckFailure(f"non-passing tests: {bad}")
    missing = sorted(name for name in SCREENSHOTS if not (screenshots_dir / name).is_file())
    if missing:
        raise CheckFailure(f"missing screenshots: {missing}")
    for name in SCREENSHOTS:
        check_png(screenshots_dir / name)
    text = redaction.read_text(encoding="utf-8").strip() if redaction.is_file() else ""
    if text != "sensitive_log_lines_redacted=0":
        raise CheckFailure(f"credential material reached logcat or the redaction report is missing: {text or '<missing>'}")
    return f"PASS: {len(executed)} vault journey tests passed, {len(SCREENSHOTS)} screenshots, logcat clean"


def self_test() -> None:
    png = b"\x89PNG\r\n\x1a\n"
    for kind, payload in ((b"IHDR", b"\0" * 13), (b"IDAT", b"x"), (b"IEND", b"")):
        png += struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        results, shots, red = root / "r", root / "s", root / "red.txt"
        results.mkdir()
        shots.mkdir()

        def write(cases: str) -> None:
            (results / "TEST-x.xml").write_text(f'<testsuite name="{REQUIRED_CLASS}">{cases}</testsuite>')

        ok_cases = "".join(f'<testcase classname="{REQUIRED_CLASS}" name="{m}"/>' for m in sorted(REQUIRED_METHODS))
        for name in SCREENSHOTS:
            (shots / name).write_bytes(png)
        red.write_text("sensitive_log_lines_redacted=0\n")
        write(ok_cases)
        check(results, shots, red)
        cases = [
            ("", "zero tests"),
            (ok_cases.replace("/>", "><skipped/></testcase>", 1), "skip"),
            (f'<testcase classname="{REQUIRED_CLASS}" name="{sorted(REQUIRED_METHODS)[0]}"/>', "one missing"),
            (ok_cases + f'<testcase classname="{REQUIRED_CLASS}" name="extra"/>', "extra test"),
        ]
        for body, label in cases:
            write(body)
            try:
                check(results, shots, red)
            except CheckFailure:
                continue
            raise SystemExit(f"self-test: {label} was not rejected")
        write(ok_cases)
        red.write_text("sensitive_log_lines_redacted=1\n")
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a credential log line was not rejected")
        red.write_text("sensitive_log_lines_redacted=0\n")
        (shots / sorted(SCREENSHOTS)[0]).write_bytes(png[:-4])
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a truncated screenshot was not rejected")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--screenshots-dir", type=Path)
    parser.add_argument("--logcat-redaction", type=Path)
    args = parser.parse_args()
    if args.self_test:
        self_test()
        print("PASS: account key-vault result checker self-test")
        return 0
    if not (args.results_dir and args.screenshots_dir and args.logcat_redaction):
        parser.error("--results-dir, --screenshots-dir and --logcat-redaction are required")
    try:
        print(check(args.results_dir, args.screenshots_dir, args.logcat_redaction))
    except CheckFailure as failure:
        print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
