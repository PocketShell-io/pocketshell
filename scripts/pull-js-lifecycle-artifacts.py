#!/usr/bin/env python3
"""Pull and verify one packaged lifecycle run while instrumentation is alive."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from typing import Callable


ASSET_TAG = "PocketshellJourneyAsset:"
SAFE_RUN_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$")
SAFE_PACKAGE = re.compile(r"^com\.pocketshell\.app\.[A-Za-z0-9._]+$")
CHECKER = Path(__file__).with_name("check-js-lifecycle-results.py")
# Pre-start budget: watcher launch -> journey DIRECTORY record (Gradle + install + instrumentation start).
DEFAULT_START_TIMEOUT_SECONDS = 600
# Journey budget: DIRECTORY -> MANIFEST. The journey is ~2 min by construction and ran
# 187 s of JUnit time at load average ~70 on 12 cores (issue #2975); leave headroom.
DEFAULT_JOURNEY_TIMEOUT_SECONDS = 600


class TransferFailure(ValueError):
    pass


def _scan_logcat(logcat: Path, run_id: str, expected_package: str) -> tuple[Path | None, int]:
    """Return the validated same-run DIRECTORY (if logged) and the MANIFEST count.

    The DIRECTORY record is validated the moment it appears, so a mismatched
    package or path fails immediately instead of being discovered only after the
    whole journey has run.
    """
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

    if len(directories) > 1:
        raise TransferFailure(f"expected one same-run directory record; found {len(directories)}")
    if manifest_count > 1:
        raise TransferFailure(f"expected one same-run manifest record; found {manifest_count}")
    if not directories:
        if manifest_count:
            raise TransferFailure("same-run manifest was logged without its directory record")
        return None, 0
    package, raw_directory = directories[0]
    if package != expected_package or not SAFE_PACKAGE.fullmatch(package):
        raise TransferFailure(
            f"instrumentation target package {package!r} does not match built package {expected_package!r}"
        )
    directory = Path(raw_directory)
    expected_tail = ("Android", "data", package, "files", "pocketshell-lifecycle", run_id)
    if not directory.is_absolute() or directory.parts[-len(expected_tail):] != expected_tail:
        raise TransferFailure(f"instrumentation reported an unexpected same-run artifact path: {raw_directory!r}")
    return directory, manifest_count


def _device_directory(logcat: Path, run_id: str, expected_package: str) -> Path | None:
    """The validated same-run artifact directory once its MANIFEST is logged."""
    directory, manifest_count = _scan_logcat(logcat, run_id, expected_package)
    return directory if manifest_count == 1 else None


def _pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def wait_for_manifest(
    logcat: Path,
    run_id: str,
    expected_package: str,
    start_timeout_seconds: float,
    journey_timeout_seconds: float,
    instrumentation_alive: Callable[[], bool] | None = None,
) -> Path:
    """Wait for the journey's MANIFEST with deadlines keyed to its lifecycle.

    Phase 1 (pre-start): from watcher launch until the journey logs its same-run
    DIRECTORY record. This covers Gradle configuration, APK/test-APK install and
    instrumentation start, none of which is journey time.
    Phase 2 (journey): from the DIRECTORY record until the journey logs MANIFEST.
    The journey budget only starts counting once the journey itself is running,
    so a slow build/install on a loaded box cannot eat into it (issue #2975).

    Both phases are bounded, so a hung build or a hung journey still fails, each
    with its own message. If instrumentation exits before MANIFEST, fail at once.
    """
    phase_started = time.monotonic()
    directory_seen_at: float | None = None
    while True:
        directory, manifest_count = _scan_logcat(logcat, run_id, expected_package)
        now = time.monotonic()
        if directory is not None and directory_seen_at is None:
            directory_seen_at = now
        if manifest_count == 1 and directory is not None:
            return directory
        if directory is None:
            if now - phase_started >= start_timeout_seconds:
                raise TransferFailure(
                    f"journey never started: no same-run DIRECTORY record for {run_id} within the "
                    f"{start_timeout_seconds:g}s pre-start budget (Gradle build/install/instrumentation start) in {logcat}"
                )
        elif directory_seen_at is not None and now - directory_seen_at >= journey_timeout_seconds:
            raise TransferFailure(
                f"journey started (DIRECTORY logged) but never logged the same-run MANIFEST for {run_id} within the "
                f"{journey_timeout_seconds:g}s journey budget counted from DIRECTORY; the journey appears hung"
            )
        if instrumentation_alive is not None and not instrumentation_alive():
            # One final scan: the MANIFEST may have landed between the scan and the liveness check.
            directory, manifest_count = _scan_logcat(logcat, run_id, expected_package)
            if manifest_count == 1 and directory is not None:
                return directory
            stage = "before logging DIRECTORY" if directory is None else "after DIRECTORY but before logging MANIFEST"
            raise TransferFailure(f"instrumentation exited {stage} for {run_id}; nothing to pull")
        time.sleep(0.1)


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
    start_timeout_seconds: float = DEFAULT_START_TIMEOUT_SECONDS,
    journey_timeout_seconds: float = DEFAULT_JOURNEY_TIMEOUT_SECONDS,
    instrumentation_alive: Callable[[], bool] | None = None,
) -> Path:
    if not SAFE_RUN_ID.fullmatch(run_id):
        raise TransferFailure(f"run ID is unsafe: {run_id!r}")
    if not SAFE_PACKAGE.fullmatch(expected_package):
        raise TransferFailure(f"built application ID is unsafe: {expected_package!r}")
    if not output_directory.is_dir():
        raise TransferFailure(f"host artifact destination does not exist: {output_directory}")

    device_directory = wait_for_manifest(
        logcat, run_id, expected_package, start_timeout_seconds, journey_timeout_seconds, instrumentation_alive
    )

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


class _FakeClock:
    """Virtual monotonic clock; sleeping advances time and appends scheduled logcat lines."""

    def __init__(self, logcat: Path, schedule: list[tuple[float, str]]) -> None:
        self.now = 0.0
        self.logcat = logcat
        self.pending = sorted(schedule)
        logcat.write_text("", encoding="utf-8")
        self._flush()

    def _flush(self) -> None:
        while self.pending and self.pending[0][0] <= self.now:
            _, line = self.pending.pop(0)
            with self.logcat.open("a", encoding="utf-8") as handle:
                handle.write(line + "\n")

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds
        self._flush()


def timed_wait_scenarios() -> list[str]:
    """Lifecycle-keyed deadline scenarios (issue #2975). Returns failure messages; empty means pass.

    Each scenario runs the real wait_for_manifest/pull_and_acknowledge with its DEFAULT
    budgets against a virtual clock, so a regression back to a single launch-relative
    budget turns scenario 1 red.
    """
    global time, _run
    failures: list[str] = []
    run_id = "js2975-timed-selftest"
    package = "com.pocketshell.app.i2975"
    path = f"/storage/emulated/0/Android/data/{package}/files/pocketshell-lifecycle/{run_id}"
    directory_line = f"I {ASSET_TAG} DIRECTORY|{run_id}|{package}|{path}"
    manifest_line = f"I {ASSET_TAG} MANIFEST|{run_id}|24|{'c' * 64}"
    real_time, real_run = time, _run

    def run_scenario(schedule: list[tuple[float, str]], alive_until: float | None = None) -> tuple[Path | None, str, float]:
        global time, _run
        with tempfile.TemporaryDirectory(prefix="pocketshell-lifecycle-timed-") as scratch:
            logcat = Path(scratch) / "live-logcat.txt"
            output_directory = Path(scratch) / "host-artifacts"
            output_directory.mkdir()
            clock = _FakeClock(logcat, schedule)

            def fake_run(command: list[str], *, timeout: int = 120) -> subprocess.CompletedProcess[str]:
                if command[3:4] == ["pull"]:
                    pulled = output_directory / run_id
                    pulled.mkdir()
                    (pulled / "artifact-manifest.json").write_text("{}\n", encoding="utf-8")
                return subprocess.CompletedProcess(command, 0, "", "")

            alive = None if alive_until is None else (lambda: clock.now < alive_until)
            time, _run = clock, fake_run  # type: ignore[assignment]
            try:
                pulled = pull_and_acknowledge(
                    "adb", "emulator-5554", logcat, run_id, package, output_directory,
                    instrumentation_alive=alive,
                ) if alive is not None else pull_and_acknowledge(
                    "adb", "emulator-5554", logcat, run_id, package, output_directory,
                )
                return pulled, "", clock.now
            except TransferFailure as error:
                return None, str(error), clock.now
            finally:
                time, _run = real_time, real_run

    # 1. Loaded box: Gradle+install takes 150 s, the journey another 250 s. MANIFEST lands
    #    400 s after watcher launch (> the old 180 s launch-relative budget) and must be pulled.
    pulled, error, _ = run_scenario([(150.0, directory_line), (400.0, manifest_line)])
    if pulled is None:
        failures.append(f"slow-but-healthy journey (MANIFEST 400 s after launch, 250 s after DIRECTORY) was not pulled: {error}")

    # 2. Journey never starts: no DIRECTORY at all -> distinct pre-start failure, bounded.
    pulled, error, elapsed = run_scenario([])
    if pulled is not None or "never started" not in error or "DIRECTORY" not in error:
        failures.append(f"missing DIRECTORY must fail with a distinct 'journey never started' message; got {error!r}")
    elif elapsed > 3600:
        failures.append(f"missing DIRECTORY must fail within a bounded time; took {elapsed:g}s")

    # 3. Journey hangs: DIRECTORY logged but MANIFEST never -> distinct journey-budget failure, bounded.
    pulled, error, elapsed = run_scenario([(30.0, directory_line)])
    if pulled is not None or "never logged the same-run MANIFEST" not in error or "never started" in error:
        failures.append(f"missing MANIFEST after DIRECTORY must fail with a distinct hung-journey message; got {error!r}")
    elif elapsed > 3600:
        failures.append(f"missing MANIFEST must fail within a bounded time; took {elapsed:g}s")

    # 4. Instrumentation exits mid-journey: fail at once, not after the journey budget.
    pulled, error, elapsed = run_scenario([(30.0, directory_line)], alive_until=90.0)
    if pulled is not None or "instrumentation exited after DIRECTORY" not in error or elapsed > 91:
        failures.append(f"instrumentation exit before MANIFEST must fail immediately; got {error!r} at {elapsed:g}s")

    # 5. Mismatched package on DIRECTORY fails as soon as it is logged, not after a budget.
    wrong = directory_line.replace(f"|{package}|", "|com.pocketshell.app.other|")
    pulled, error, elapsed = run_scenario([(30.0, wrong), (60.0, manifest_line)])
    if pulled is not None or "does not match built package" not in error or elapsed > 31:
        failures.append(f"mismatched DIRECTORY package must fail immediately; got {error!r} at {elapsed:g}s")

    # 5b. The real --instrumentation-pid liveness probe tracks an actual process.
    child = subprocess.Popen([sys.executable, "-c", "pass"])
    child.wait()
    if not _pid_alive(os.getpid()) or _pid_alive(child.pid):
        failures.append("instrumentation liveness probe must report a live process alive and a reaped one dead")

    # 6. A duplicate MANIFEST still fails closed.
    pulled, error, _ = run_scenario([(30.0, directory_line), (60.0, manifest_line + "\n" + manifest_line)])
    if pulled is not None or "one same-run manifest" not in error:
        failures.append(f"duplicate MANIFEST must fail closed; got {error!r}")
    return failures


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
                "adb", "emulator-5554", logcat, run_id, package, output_directory, 1, 1
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
                "adb", "emulator-5554", logcat, failure_run_id, package, output_directory, 1, 1
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

    timed_failures = timed_wait_scenarios()
    for failure in timed_failures:
        print(f"FAIL: {failure}", file=sys.stderr)
    if timed_failures:
        return 1

    print("PASS: lifecycle artifact pull path, ordered transfer, and lifecycle-keyed deadlines self-test (12/12)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb")
    parser.add_argument("--serial")
    parser.add_argument("--logcat", type=Path)
    parser.add_argument("--run-id")
    parser.add_argument("--expected-package")
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--start-timeout-seconds", type=float, default=DEFAULT_START_TIMEOUT_SECONDS,
                        help="budget from watcher launch until the journey logs its same-run DIRECTORY record")
    parser.add_argument("--journey-timeout-seconds", type=float, default=DEFAULT_JOURNEY_TIMEOUT_SECONDS,
                        help="budget from the DIRECTORY record until the journey logs its MANIFEST")
    parser.add_argument("--instrumentation-pid", type=int,
                        help="fail as soon as this process (the instrumentation run) exits before MANIFEST")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not all((args.adb, args.serial, args.logcat, args.run_id, args.expected_package, args.output_directory)):
        parser.error("--adb, --serial, --logcat, --run-id, --expected-package, and --output-directory are required")
    if args.start_timeout_seconds <= 0 or args.journey_timeout_seconds <= 0:
        parser.error("--start-timeout-seconds and --journey-timeout-seconds must be positive")
    instrumentation_pid = args.instrumentation_pid
    alive = (lambda: _pid_alive(instrumentation_pid)) if instrumentation_pid else None
    try:
        output = pull_and_acknowledge(
            args.adb, args.serial, args.logcat, args.run_id, args.expected_package,
            args.output_directory, args.start_timeout_seconds, args.journey_timeout_seconds, alive,
        )
    except TransferFailure as error:
        print(f"FAIL: packaged lifecycle artifact pull: {error}", file=sys.stderr)
        return 1
    print(f"PASS: pulled and verified same-run artifacts before instrumentation exit: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
