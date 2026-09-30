#!/usr/bin/env python3
"""Pull and verify one packaged lifecycle run while instrumentation is alive."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path


ASSET_TAG = "PocketshellJourneyAsset:"
SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$")
SAFE_PACKAGE = re.compile(r"^com\.pocketshell\.app\.[A-Za-z0-9._]+$")
CHECKER = Path(__file__).with_name("check-js-lifecycle-results.py")


class TransferFailure(ValueError):
    pass


def _device_directory(logcat: Path, run_id: str, expected_package: str) -> Path | None:
    try:
        lines = logcat.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as error:
        raise TransferFailure(f"could not read live lifecycle logcat {logcat}: {error}") from error

    directories: list[tuple[str, str]] = []
    manifest_count = 0
    for line in lines:
        if ASSET_TAG not in line:
            continue
        fields = line.split(ASSET_TAG, 1)[1].strip().split("|")
        if len(fields) < 2 or fields[1] != run_id:
            continue
        if fields[0] == "DIRECTORY":
            if len(fields) != 4:
                raise TransferFailure("same-run device artifact directory record is malformed")
            directories.append((fields[2], fields[3]))
        elif fields[0] == "MANIFEST":
            manifest_count += 1

    if manifest_count == 0:
        return None
    if manifest_count != 1 or len(directories) != 1:
        raise TransferFailure(
            f"expected one same-run manifest and directory record; found {manifest_count} and {len(directories)}"
        )
    package, raw_directory = directories[0]
    if package != expected_package or not SAFE_PACKAGE.fullmatch(package):
        raise TransferFailure(
            f"instrumentation target package {package!r} does not match built package {expected_package!r}"
        )
    directory = Path(raw_directory)
    expected_tail = ("Android", "data", package, "files", "pocketshell-lifecycle", run_id)
    if not directory.is_absolute() or directory.parts[-len(expected_tail):] != expected_tail:
        raise TransferFailure(f"instrumentation reported an unexpected same-run artifact path: {raw_directory!r}")
    return directory


def _run(args: list[str], *, timeout: int = 120) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(args, check=True, text=True, capture_output=True, timeout=timeout)
    except subprocess.CalledProcessError as error:
        details = (error.stderr or error.stdout or "").strip()
        raise TransferFailure(f"command failed ({' '.join(args)}): {details}") from error
    except (OSError, subprocess.SubprocessError) as error:
        raise TransferFailure(f"could not run {' '.join(args)}: {error}") from error


def _acknowledge(adb: str, serial: str, device_directory: Path, run_id: str, suffix: str) -> None:
    signal = device_directory.parent / f".host-pull-{suffix}-{run_id}"
    _run([adb, "-s", serial, "shell", "touch", str(signal)])


def pull_and_acknowledge(
    adb: str,
    serial: str,
    logcat: Path,
    run_id: str,
    expected_package: str,
    output_directory: Path,
    timeout_seconds: int = 180,
) -> Path:
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise TransferFailure(f"run ID is unsafe: {run_id!r}")
    if not SAFE_PACKAGE.fullmatch(expected_package):
        raise TransferFailure(f"built application ID is unsafe: {expected_package!r}")
    if not output_directory.is_dir():
        raise TransferFailure(f"host artifact destination does not exist: {output_directory}")

    deadline = time.monotonic() + timeout_seconds
    device_directory: Path | None = None
    while time.monotonic() < deadline:
        device_directory = _device_directory(logcat, run_id, expected_package)
        if device_directory is not None:
            break
        time.sleep(0.1)
    if device_directory is None:
        raise TransferFailure(f"timed out waiting for the same-run manifest in {logcat}")

    host_run_directory = output_directory / run_id
    if host_run_directory.exists():
        raise TransferFailure(f"refusing to overwrite same-run device artifacts: {host_run_directory}")

    try:
        _run([adb, "-s", serial, "pull", str(device_directory), str(output_directory)], timeout=120)
        if not (host_run_directory / "artifact-manifest.json").is_file():
            raise TransferFailure(f"adb pull did not create the same-run manifest: {host_run_directory}")
        _run([
            sys.executable,
            str(CHECKER),
            "--verify-assets-before-ack",
            "--run-directory",
            str(output_directory),
            "--run-id",
            run_id,
        ])
        _acknowledge(adb, serial, device_directory, run_id, "complete")
    except TransferFailure:
        try:
            _acknowledge(adb, serial, device_directory, run_id, "failed")
        except TransferFailure:
            pass
        raise

    return host_run_directory


def self_test() -> int:
    global _run
    run_id = "js2856-transfer-selftest"
    package = "com.pocketshell.app.i2856"
    path = f"/storage/emulated/0/Android/data/{package}/files/pocketshell-lifecycle/{run_id}"
    with tempfile.TemporaryDirectory(prefix="pocketshell-lifecycle-pull-") as scratch:
        logcat = Path(scratch) / "live-logcat.txt"
        output_directory = Path(scratch) / "host-artifacts"
        output_directory.mkdir()
        valid = (
            f"I {ASSET_TAG} DIRECTORY|{run_id}|{package}|{path}\n"
            f"I {ASSET_TAG} MANIFEST|{run_id}|24|{'a' * 64}\n"
        )
        logcat.write_text(valid, encoding="utf-8")
        if _device_directory(logcat, run_id, package) != Path(path):
            print("FAIL: valid same-run app external-files path was not recognized", file=sys.stderr)
            return 1
        logcat.write_text(valid.replace("/files/pocketshell-lifecycle/", "/files/other/"), encoding="utf-8")
        try:
            _device_directory(logcat, run_id, package)
        except TransferFailure:
            pass
        else:
            print("FAIL: unexpected same-run artifact path was accepted", file=sys.stderr)
            return 1
        logcat.write_text(valid, encoding="utf-8")

        events: list[tuple[str, list[str]]] = []
        original_run = _run

        def fake_run(command: list[str], *, timeout: int = 120) -> subprocess.CompletedProcess[str]:
            if command[:3] == ["adb", "-s", "emulator-5554"] and command[3] == "pull":
                events.append(("pull", command))
                pulled_directory = output_directory / run_id
                pulled_directory.mkdir()
                (pulled_directory / "artifact-manifest.json").write_text("{}\n", encoding="utf-8")
            elif command[:3] == ["adb", "-s", "emulator-5554"] and command[3:5] == ["shell", "touch"]:
                events.append(("ack", command))
            elif command[:2] == [sys.executable, str(CHECKER)]:
                events.append(("verify", command))
            else:
                raise AssertionError(f"unexpected command in lifecycle artifact transfer: {command}")
            return subprocess.CompletedProcess(command, 0, "", "")

        try:
            _run = fake_run
            pulled = pull_and_acknowledge(
                "adb", "emulator-5554", logcat, run_id, package, output_directory, timeout_seconds=1
            )
        except (AssertionError, TransferFailure) as error:
            print(f"FAIL: same-run artifact transfer orchestration failed: {error}", file=sys.stderr)
            return 1
        finally:
            _run = original_run
        if pulled != output_directory / run_id or [kind for kind, _ in events] != ["pull", "verify", "ack"]:
            print("FAIL: host must pull and verify same-run files before acknowledging instrumentation", file=sys.stderr)
            return 1
        pull_command = events[0][1]
        verify_command = events[1][1]
        ack_command = events[2][1]
        if pull_command[4:] != [path, str(output_directory)]:
            print("FAIL: artifact transfer did not pull the instrumentation-reported directory", file=sys.stderr)
            return 1
        if "--verify-assets-before-ack" not in verify_command or f".host-pull-complete-{run_id}" not in ack_command[-1]:
            print("FAIL: artifact transfer omitted exact pre-ack verification or its same-run signal", file=sys.stderr)
            return 1

        failure_run_id = "js2856-transfer-failure"
        failure_path = f"/storage/emulated/0/Android/data/{package}/files/pocketshell-lifecycle/{failure_run_id}"
        logcat.write_text(
            f"I {ASSET_TAG} DIRECTORY|{failure_run_id}|{package}|{failure_path}\n"
            f"I {ASSET_TAG} MANIFEST|{failure_run_id}|24|{'b' * 64}\n",
            encoding="utf-8",
        )
        events.clear()

        def reject_verification(command: list[str], *, timeout: int = 120) -> subprocess.CompletedProcess[str]:
            if command[:3] == ["adb", "-s", "emulator-5554"] and command[3] == "pull":
                events.append(("pull", command))
                pulled_directory = output_directory / failure_run_id
                pulled_directory.mkdir()
                (pulled_directory / "artifact-manifest.json").write_text("{}\n", encoding="utf-8")
            elif command[:2] == [sys.executable, str(CHECKER)]:
                events.append(("verify", command))
                raise TransferFailure("synthetic strict-check rejection")
            elif command[:3] == ["adb", "-s", "emulator-5554"] and command[3:5] == ["shell", "touch"]:
                events.append(("ack", command))
            else:
                raise AssertionError(f"unexpected command in rejected lifecycle artifact transfer: {command}")
            return subprocess.CompletedProcess(command, 0, "", "")

        rejected = False
        try:
            _run = reject_verification
            pull_and_acknowledge(
                "adb", "emulator-5554", logcat, failure_run_id, package, output_directory, timeout_seconds=1
            )
        except TransferFailure:
            rejected = True
        except AssertionError as error:
            print(f"FAIL: rejected artifact transfer orchestration failed: {error}", file=sys.stderr)
            return 1
        finally:
            _run = original_run
        if not rejected or [kind for kind, _ in events] != ["pull", "verify", "ack"]:
            print("FAIL: rejected transfer must signal test failure after strict verification rejects it", file=sys.stderr)
            return 1
        if f".host-pull-failed-{failure_run_id}" not in events[2][1][-1]:
            print("FAIL: rejected transfer did not send the same-run failure signal", file=sys.stderr)
            return 1

    print("PASS: lifecycle artifact pull path and ordered transfer self-test (5/5)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--logcat", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--expected-package")
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--timeout-seconds", type=int, default=180)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not all((args.adb, args.serial, args.logcat, args.run_id, args.expected_package, args.output_directory)):
        parser.error("--adb, --serial, --logcat, --run-id, --expected-package, and --output-directory are required")
    try:
        output = pull_and_acknowledge(
            args.adb, args.serial, args.logcat, args.run_id, args.expected_package,
            args.output_directory, args.timeout_seconds,
        )
    except TransferFailure as error:
        print(f"FAIL: packaged lifecycle artifact pull: {error}", file=sys.stderr)
        return 1
    print(f"PASS: pulled and verified same-run artifacts before instrumentation exit: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
