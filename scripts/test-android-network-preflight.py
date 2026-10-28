#!/usr/bin/env python3
"""Exercise the real preflight process with a stateful fake adb guest."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

TARGET = Path(__file__).with_name('android-network-preflight.py')
FAKE = '''#!/usr/bin/env python3
import os
from pathlib import Path
import sys
state = Path(os.environ['FAKE_STATE'])
command = sys.argv[-1]
with Path(os.environ['FAKE_CALLS']).open('a') as log:
    log.write(command + '\\n')
case = os.environ['FAKE_CASE']
if command == 'devices':
    print('List of devices attached\\nemulator-5554\\tdevice')
elif command == 'svc wifi enable':
    state.write_text('enabled')
elif command == 'ip route get 10.0.2.2':
    if case == 'no-route' or not state.exists():
        print('RTNETLINK answers: Network is unreachable')
        sys.exit(1)
    print('10.0.2.2 via 10.0.2.2 dev wlan0 src 10.0.2.16')
elif command.startswith('toybox nc '):
    if case == 'non-ssh':
        print('HTTP/1.1 200 OK')
    elif case == 'delayed' and not state.with_suffix('.banner').exists():
        state.with_suffix('.banner').touch()
        print('Connection refused')
        sys.exit(1)
    else:
        print('SSH-2.0-OpenSSH_9.6')
else:
    print('diagnostic ' + command)
'''


class PreflightTests(unittest.TestCase):
    def exercise(self, case, expected, port=2222):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / 'adb'
            adb.write_text(FAKE)
            adb.chmod(0o755)
            env = {**os.environ, 'ADB': str(adb), 'ANDROID_SERIAL': 'emulator-5554',
                   'POCKETSHELL_AVD_LOCK_DIR': str(root / 'locks'),
                   'FAKE_STATE': str(root / 'state'), 'FAKE_CALLS': str(root / 'calls'),
                   'FAKE_CASE': case}
            env.pop('POCKETSHELL_AVD_LOCK_FILE', None)
            result = subprocess.run(['python3', str(TARGET), '--timeout', ('8' if case == 'delayed' else '2'),
                                     '--port', str(port), '--evidence-dir', str(root / 'evidence')],
                                    env=env, text=True, capture_output=True, timeout=20)
            self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
            calls = (root / 'calls').read_text().splitlines()
            enable_index = calls.index('svc wifi enable')
            self.assertLess(calls.index('ip route get 10.0.2.2'), enable_index)
            self.assertLess(calls.index('cmd wifi status'), enable_index)
            self.assertIn('ip route get 10.0.2.2', calls[enable_index + 1:])
            if expected == 0:
                self.assertIn(f'toybox nc -w 2 -W 2 -q 2 10.0.2.2 {port}', calls)
                self.assertIn(f'10.0.2.2:{port}', result.stdout)
            for diagnostic in ('ip address', 'ip route show table all', 'cmd wifi status', 'dumpsys connectivity'):
                self.assertIn(diagnostic, calls)
            evidence = (root / 'evidence/preflight.txt').read_text()
            self.assertIn('exit=', evidence)
            if expected == 0:
                self.assertIn('SSH-2.0-', evidence)
            if case == 'no-route':
                self.assertFalse(any(call.startswith('toybox nc ') for call in calls))
            if case == 'delayed':
                self.assertGreaterEqual(sum(call.startswith('toybox nc ') for call in calls), 2)

    def test_disabled_wifi_restored_before_probe(self):
        self.exercise('disabled', 0)

    def test_route_without_ssh_is_failure(self):
        self.exercise('non-ssh', 1)

    def test_missing_route_is_failure_with_diagnostics(self):
        self.exercise('no-route', 1)

    def test_waits_for_banner_readiness(self):
        self.exercise('delayed', 0)

    def test_nondefault_local_fixture_port(self):
        self.exercise('disabled', 0, port=23243)

    def test_invalid_ports_rejected_before_adb(self):
        for port in ('0', '-1', '65536', 'not-a-port'):
            with self.subTest(port=port):
                result = subprocess.run(['python3', str(TARGET), '--port', port],
                                        env={**os.environ, 'ADB': '/nonexistent-adb'},
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn('--port', result.stderr)
                self.assertNotIn('Traceback', result.stderr)

    def test_ci_dispatches_before_any_journey(self):
        dispatcher = TARGET.with_name('ci-js-first-packaged-lanes.sh').read_text()
        self.assertLess(dispatcher.index('python3 scripts/android-network-preflight.py'),
                        dispatcher.index('if scripts/connected-js-smoke.sh'))


if __name__ == '__main__':
    unittest.main()
