#!/usr/bin/python3 -I
"""Require complete Android input-focus diagnostics from a failed packaged lane.

Issue #2946: a lost Android-injected key used to leave two logcat lines and no
record of who owned input focus. On a packaged-lane failure the runners call
``pocketshell_android_capture_input_diagnostics`` (scripts/lib/
android-input-preflight.sh); this guard proves that bundle contains every dump
needed to attribute the loss (focused window, system error dialogs, IME target,
resumed activity, unfiltered logcat, screenshot) rather than trusting that the
capture commands ran.

Usage:
  scripts/check-android-input-diagnostics.py --dir <failure-diagnostics dir>
  scripts/check-android-input-diagnostics.py --self-test
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path


# file name -> text that proves the file is the real dump and not an adb error
REQUIRED_TEXT_DUMPS = {
    "dumpsys-input.txt": "Input Dispatcher State",
    "dumpsys-window-windows.txt": "WINDOW MANAGER WINDOWS",
    "dumpsys-window-displays.txt": "mCurrentFocus=",
    "dumpsys-window-lastanr.txt": "WINDOW MANAGER LAST ANR",
    "dumpsys-input-method.txt": "mCurTokenDisplayId",
    "dumpsys-activity-activities.txt": "ACTIVITY MANAGER ACTIVITIES",
    "logcat-all.txt": "--------- beginning of",
}
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
SCREENSHOT = "device-screen.png"
SELF_TEST_CASES = 5


class DiagnosticsFailure(ValueError):
    """The failure-diagnostics bundle is missing a required dump."""


def validate(directory: Path) -> int:
    if not directory.is_dir():
        raise DiagnosticsFailure(f"Android input diagnostics directory is missing: {directory}")
    for name, marker in REQUIRED_TEXT_DUMPS.items():
        path = directory / name
        if not path.is_file() or path.stat().st_size == 0:
            raise DiagnosticsFailure(f"missing or empty Android input diagnostic: {path}")
        text = path.read_text(encoding="utf-8", errors="replace")
        if marker not in text:
            raise DiagnosticsFailure(f"{path} does not contain {marker!r}; the capture did not run")
    screenshot = directory / SCREENSHOT
    if not screenshot.is_file() or not screenshot.read_bytes().startswith(PNG_MAGIC):
        raise DiagnosticsFailure(f"missing or invalid device screenshot: {screenshot}")
    return len(REQUIRED_TEXT_DUMPS) + 1


def _write_complete(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name, marker in REQUIRED_TEXT_DUMPS.items():
        (directory / name).write_text(f"header\n{marker} sample\n", encoding="utf-8")
    (directory / SCREENSHOT).write_bytes(PNG_MAGIC + b"payload")


def self_test() -> int:
    passed = 0

    def expect_failure(label: str, directory: Path, needle: str) -> None:
        nonlocal passed
        try:
            validate(directory)
        except DiagnosticsFailure as error:
            if needle not in str(error):
                raise SystemExit(f"FAIL self-test {label}: wrong error {error}")
            passed += 1
            print(f"ok   [{passed}/{SELF_TEST_CASES}] {label}")
            return
        raise SystemExit(f"FAIL self-test {label}: incomplete diagnostics were accepted")

    with tempfile.TemporaryDirectory() as raw:
        root = Path(raw)
        complete = root / "complete"
        _write_complete(complete)
        if validate(complete) != len(REQUIRED_TEXT_DUMPS) + 1:
            raise SystemExit("FAIL self-test: complete bundle count mismatch")
        passed += 1
        print(f"ok   [{passed}/{SELF_TEST_CASES}] complete bundle passes")

        expect_failure("missing directory is rejected", root / "absent", "directory is missing")

        no_input = root / "no-input"
        _write_complete(no_input)
        (no_input / "dumpsys-input.txt").unlink()
        expect_failure("missing dumpsys input is rejected", no_input, "dumpsys-input.txt")

        adb_error = root / "adb-error"
        _write_complete(adb_error)
        (adb_error / "dumpsys-window-windows.txt").write_text(
            "error: device offline\n", encoding="utf-8")
        expect_failure("an adb error in place of a dump is rejected", adb_error, "WINDOW MANAGER WINDOWS")

        bad_png = root / "bad-png"
        _write_complete(bad_png)
        (bad_png / SCREENSHOT).write_bytes(b"error: closed")
        expect_failure("an invalid screenshot is rejected", bad_png, "screenshot")

    if passed != SELF_TEST_CASES:
        raise SystemExit(f"FAIL self-test ran {passed}/{SELF_TEST_CASES} cases")
    print(f"PASS: {passed}/{SELF_TEST_CASES} Android input diagnostics guard checks")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--dir", type=Path)
    group.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    try:
        count = validate(args.dir)
    except DiagnosticsFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: {count} Android input diagnostics present in {args.dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
