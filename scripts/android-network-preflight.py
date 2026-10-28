#!/usr/bin/env python3
"""Restore emulator WiFi and prove the guest can reach the CI SSH fixture."""
import argparse
import fcntl
import os
from pathlib import Path
import shutil
import subprocess
import time


def run(adb, serial, command, timeout=5):
    try:
        result = subprocess.run([adb, '-s', serial, 'shell', command],
                                input='', text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
        return result.returncode, result.stdout
    except subprocess.TimeoutExpired:
        return 124, 'adb command timed out\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=2222, help='host SSH fixture port (1-65535)')
    parser.add_argument('--timeout', type=float, default=90)
    parser.add_argument('--evidence-dir', default='android/app/build/outputs/js-network-preflight')
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error('--port must be between 1 and 65535')
    adb = os.environ.get('ADB') or shutil.which('adb')
    if not adb:
        sdk = os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Android/Sdk')))
        adb = str(Path(sdk) / 'platform-tools/adb')
    serial = os.environ.get('ANDROID_SERIAL')
    if not serial:
        devices = subprocess.check_output([adb, 'devices'], text=True, timeout=10)
        serials = [line.split()[0] for line in devices.splitlines()
                   if line.startswith('emulator-') and line.split()[1:] == ['device']]
        if len(serials) != 1:
            parser.error(f'expected exactly one online emulator; found {serials}')
        serial = serials[0]
    if not serial.startswith('emulator-'):
        parser.error('network preflight only mutates emulators')
    evidence = Path(args.evidence_dir)
    evidence.mkdir(parents=True, exist_ok=True)
    lock_dir = Path(os.environ.get('POCKETSHELL_AVD_LOCK_DIR', str(Path.home() / '.cache/pocketshell/avd-locks')))
    lock_dir.mkdir(parents=True, exist_ok=True)
    lock_file = Path(os.environ.get('POCKETSHELL_AVD_LOCK_FILE', str(lock_dir / f'avd-lock-{serial}')))
    with lock_file.open('a') as lock, (evidence / 'preflight.txt').open('w') as log:
        # Same per-serial lock used by the connected runners. Fail rather than
        # altering an emulator owned by a different lane/session.
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        def capture(command, timeout=5):
            status, output = run(adb, serial, command, timeout)
            log.write(f'$ {command}\nexit={status}\n{output}\n')
            log.flush()
            return status, output
        # Preserve the guest state before recovery for CI root-cause evidence.
        capture('ip route get 10.0.2.2')
        capture('cmd wifi status')
        capture('svc wifi enable')
        deadline = time.monotonic() + args.timeout
        ready = False
        while time.monotonic() < deadline:
            remaining = max(0.1, min(5, deadline - time.monotonic()))
            route_status, route = capture('ip route get 10.0.2.2', remaining)
            if route_status == 0 and ' dev ' in route and 'unreachable' not in route:
                # -q keeps the socket open after stdin EOF long enough to read
                # the server banner. A TCP connect alone cannot prove SSH ready.
                _, banner = capture(f'toybox nc -w 2 -W 2 -q 2 10.0.2.2 {args.port}',
                                    max(0.1, min(5, deadline - time.monotonic())))
                if any(line.startswith('SSH-2.0-') for line in banner.splitlines()):
                    ready = True
                    break
            time.sleep(min(1, max(0, deadline - time.monotonic())))
        for command in ('ip address', 'ip route show table all', 'cmd wifi status', 'dumpsys connectivity'):
            capture(command)
        if not ready:
            print(f'FAIL: {serial} has no route and SSH banner from 10.0.2.2:{args.port}; see {evidence}')
            return 1
        print(f'PASS: {serial} route and SSH banner verified at 10.0.2.2:{args.port}; see {evidence}')
        return 0


if __name__ == '__main__':
    raise SystemExit(main())
