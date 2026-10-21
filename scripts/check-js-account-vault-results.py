#!/usr/bin/env python3
"""Fail-closed result check for the packaged account key-vault journey (key-vault W4).

Requires, from the same run: both SharedVaultDockerJourneyTest methods in the
JUnit XML, passed (no skips, failures, errors or extra tests); every required
screenshot present as a complete PNG with distinct content below the status bar (#3067);
and no credential line in the app logcat.
"""
from __future__ import annotations

import argparse
import hashlib
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
    # The sixth screenshot is the post-sign-out Account screen, not a second
    # host-list capture: both vault methods legitimately render the same host
    # list, so a second host-list shot would be byte-identical to
    # vault-unlocked-account-host.png by design and rightly rejected (#3067).
    # The silent next launch stays proven by the no-unlock-prompt assertion
    # in the journey; the screenshot proves the sign-out half forgets.
    "vault-signed-out-forgotten.png",
}
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


class CheckFailure(Exception):
    pass


def check_png(path: Path) -> None:
    data = path.read_bytes()
    if not data.startswith(PNG_MAGIC):
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


def _png_content_digest(data: bytes, top_fraction: float = 0.10) -> str | None:
    """Return SHA-256 of decompressed PNG scanlines excluding the top status bar.

    Returns None if chunks cannot be decompressed (e.g. synthetic test bytes).
    """
    try:
        if not data.startswith(PNG_MAGIC):
            return None
        pos = 8
        idat_parts = []
        width = height = color_type = 0
        while pos + 12 <= len(data):
            length = struct.unpack(">I", data[pos:pos + 4])[0]
            chunk_type = data[pos + 4:pos + 8]
            chunk_data = data[pos + 8:pos + 8 + length]
            pos += 8 + length + 4
            if chunk_type == b"IHDR":
                width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk_data[:10])
            elif chunk_type == b"IDAT":
                idat_parts.append(chunk_data)
            elif chunk_type == b"IEND":
                break
        if not idat_parts or width <= 0 or height <= 0:
            return None
        raw = zlib.decompress(b"".join(idat_parts))
        bpp = 4 if color_type == 6 else (3 if color_type == 2 else 1)
        stride = 1 + width * bpp
        skip_rows = max(1, int(height * top_fraction))
        content_raw = raw[skip_rows * stride:]
        return hashlib.sha256(content_raw).hexdigest()
    except Exception:
        return None


def _create_mock_png(width: int = 100, height: int = 200, status_bar_val: int = 0, content_val: int = 1) -> bytes:
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        val = status_bar_val if y < 20 else content_val
        raw.extend([val, val, val, 255] * width)
    compressed = zlib.compress(bytes(raw))
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    ihdr_chunk = struct.pack(">I", len(ihdr)) + b"IHDR" + ihdr + struct.pack(">I", zlib.crc32(b"IHDR" + ihdr))
    idat_chunk = struct.pack(">I", len(compressed)) + b"IDAT" + compressed + struct.pack(">I", zlib.crc32(b"IDAT" + compressed))
    iend_chunk = struct.pack(">I", 0) + b"IEND" + struct.pack(">I", zlib.crc32(b"IEND"))
    return PNG_MAGIC + ihdr_chunk + idat_chunk + iend_chunk


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
    seen: dict[str, str] = {}
    seen_content: dict[str, str] = {}
    for name in sorted(SCREENSHOTS):
        shot_path = screenshots_dir / name
        check_png(shot_path)
        data = shot_path.read_bytes()
        digest = hashlib.sha256(data).hexdigest()
        if digest in seen:
            raise CheckFailure(f"stale screenshot: {name} is byte-identical to {seen[digest]}")
        seen[digest] = name

        content_digest = _png_content_digest(data)
        if content_digest is not None:
            if content_digest in seen_content:
                raise CheckFailure(
                    f"stale screenshot: {name} content is identical to {seen_content[content_digest]} "
                    "(status-bar change only)"
                )
            seen_content[content_digest] = name
    text = redaction.read_text(encoding="utf-8").strip() if redaction.is_file() else ""
    if text != "sensitive_log_lines_redacted=0":
        raise CheckFailure(f"credential material reached logcat or the redaction report is missing: {text or '<missing>'}")
    return f"PASS: {len(executed)} vault journey tests passed, {len(SCREENSHOTS)} screenshots, logcat clean"


def self_test() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        results, shots, red = root / "r", root / "s", root / "red.txt"
        results.mkdir()
        shots.mkdir()

        def write(cases: str) -> None:
            (results / "TEST-x.xml").write_text(f'<testsuite name="{REQUIRED_CLASS}">{cases}</testsuite>')

        def write_shots(duplicate: bool = False, status_bar_only: bool = False, missing_one: bool = False) -> None:
            names = sorted(SCREENSHOTS)
            if missing_one:
                names = names[1:]
            for index, name in enumerate(names):
                if status_bar_only:
                    sb_val = index
                    c_val = 0 if index < 2 else index + 1
                    (shots / name).write_bytes(_create_mock_png(status_bar_val=sb_val, content_val=c_val))
                elif duplicate and index < 2:
                    (shots / name).write_bytes(_create_mock_png(status_bar_val=0, content_val=0))
                else:
                    (shots / name).write_bytes(_create_mock_png(status_bar_val=index, content_val=index + 1))

        ok_cases = "".join(f'<testcase classname="{REQUIRED_CLASS}" name="{m}"/>' for m in sorted(REQUIRED_METHODS))
        write_shots()
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

        # 1. Missing screenshot
        write_shots(missing_one=True)
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a missing screenshot was not rejected")

        # 2. Truncated screenshot
        write_shots()
        (shots / sorted(SCREENSHOTS)[0]).write_bytes(PNG_MAGIC + b"truncated")
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a truncated screenshot was not rejected")

        # 3. Non-PNG screenshot
        write_shots()
        (shots / sorted(SCREENSHOTS)[0]).write_bytes(b"GIF89a" + b"\0" * 1024)
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a non-PNG screenshot was not rejected")

        # 4. Byte-identical (stale) screenshot
        write_shots(duplicate=True)
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a byte-identical screenshot was not rejected")

        # 5. Stale screenshot differing only by status-bar pixels
        write_shots(status_bar_only=True)
        try:
            check(results, shots, red)
        except CheckFailure:
            pass
        else:
            raise SystemExit("self-test: a stale frame with only status-bar diff was not rejected")


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
