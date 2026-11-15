#!/usr/bin/env python3
"""Run the journey's actual receiver command in a PTY across delayed resizes."""
import argparse
import base64
import fcntl
import os
from pathlib import Path
import pty
import re
import select
import signal
import struct
import subprocess
import tempfile
import termios
import time

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--journey-source', type=Path)
args = parser.parse_args()
journey = (args.journey_source or ROOT / 'android/app/src/androidTest/java/com/pocketshell/app/smoke/J23InlineDictationJourney.java').read_text()

with tempfile.TemporaryDirectory(prefix='fastkey-pty-') as temporary:
    temporary = Path(temporary)
    helper = ROOT / 'android/app/src/sharedTest/java/com/pocketshell/app/smoke/FastKeyReceiverCommand.java'
    if 'FastKeyReceiverCommand.build(' in journey:
        body = 'String command = FastKeyReceiverCommand.build(rawPath, byteCount, readyMarker, doneMarker);'
        sources = [str(helper)]
    else:
        # Red baseline: compile the command expression directly from the old
        # real journey, rather than inventing a substitute ungated receiver.
        body = 'String geometryOraclePath = rawPath + ".geometry";\n' + re.search(
            r'String command = .*?;(?=\s*markResizeFitPhase)', journey, re.S).group()
        sources = []
    driver = temporary / 'ReceiverCommandProbe.java'
    driver.write_text('''package com.pocketshell.app.smoke;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
public final class ReceiverCommandProbe {
 public static void main(String[] args) {
  String rawPath=args[0], readyMarker="PS2884_DICTATION_READY_js2884-43402577042-1", doneMarker="PS2884_DONE";
  int byteCount=6;
''' + body + '''
  System.out.print(Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8)));
 }
}
''')
    subprocess.run(['javac', '-d', str(temporary), *sources, str(driver)], check=True)
    for early_enter in (False, True):
        raw = temporary / ('early.raw' if early_enter else 'delayed.raw')
        command = base64.b64decode(subprocess.check_output([
            'java', '-cp', str(temporary), 'com.pocketshell.app.smoke.ReceiverCommandProbe', str(raw)
        ])).decode()
        if 'FastKeyReceiverCommand.build(' in journey:
            assert 'PS2884_DICTATION_READY_js2884-43402577042-1' not in command, 'echoed command can impersonate READY'
            assert 'PS2884_DONE' not in command, 'echoed command can impersonate DONE'
        arm_gate = temporary / 'arm.fifo'
        if early_enter:
            os.mkfifo(arm_gate)
            # Hold the host before stty/read, queue Enter first, then release
            # deterministically. No scheduler-dependent sleep proves this case.
            command = f'IFS= read -r arm < {arm_gate}; ' + command
        pid, master = pty.fork()
        if pid == 0:
            os.execl('/bin/bash', 'bash', '-c', command)
        output = bytearray()
        def collect(seconds):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                if select.select([master], [], [], max(0, deadline - time.monotonic()))[0]:
                    try:
                        chunk = os.read(master, 65536)
                    except OSError:
                        break
                    if not chunk:
                        break
                    output.extend(chunk)
        try:
            for rows, cols in ((30, 38), (12, 37), (11, 38)):
                fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
                collect(.08)
                assert b'PS2884_DICTATION_READY_' not in output, 'READY emitted before the post-Send resize gate opened'
                assert not raw.exists(), 'measured capture began before the resize gate opened'
            # Exercise Enter queued before the host reaches read as well as a
            # host already waiting in read; both consume exactly one setup line.
            if not early_enter:
                collect(.15)
            os.write(master, b'\r')
            if early_enter:
                with arm_gate.open('w') as gate:
                    gate.write('armed\n')
            collect(.4)
            marker = b'PS2884_DICTATION_READY_js2884-43402577042-1'
            assert marker in output, f'exact READY not emitted after release: {output!r}'
            payload = b'a\x1b[A\rz'
            os.write(master, payload)
            collect(.5)
            assert raw.read_bytes() == payload, f'setup Enter leaked into the measured byte oracle: {raw.read_bytes()!r}'
            assert b'PS2884_DONE' in output, 'receiver did not finish the exact byte count'
        finally:
            try:
                os.killpg(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            os.close(master)
            os.waitpid(pid, 0)
    print('PASS: actual fast-key PTY receiver gates READY across 38→37→38 resizes and excludes setup Enter from exact bytes')
