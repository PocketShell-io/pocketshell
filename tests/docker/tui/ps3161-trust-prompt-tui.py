#!/usr/bin/env python3
"""An Antigravity/Claude-like trust prompt with heavy redraws (pocketshell#3161).

The maintainer's Pixel 7a showed `[error] i is not defined` and then
`[terminal-stall]` while Antigravity CLI drew its "Do you trust the contents
of this project?" prompt. That TUI, like many (Claude Code, Gemini CLI), asks
the terminal which private modes it supports with DECRQM (`CSI ? Ps $ p`)
before it paints with synchronized output. In the shipped bundle the DECRQM
handler of xterm.js threw a ReferenceError (an esbuild minifier bug), which
killed xterm's write loop.

This script reproduces that host behaviour inside a Docker fixture session:
  * the alternate screen, hidden cursor, bracketed paste;
  * DECRQM queries for synchronized output (2026), bracketed paste (2004),
    the alternate screen (1049) and the ANSI insert mode (4) — the private and
    the ANSI branch of the handler;
  * FRAMES fast repaints inside synchronized-output brackets, each with
    absolute cursor moves, a rounded box of box-drawing characters, 256-colour
    and truecolour SGR, and a toggled highlighted option.

The PTY is in raw mode, so the terminal's DECRPM answers (`CSI ? Ps ; Pm $ y`)
come back as input instead of being echoed. They are written, as hex, to the
file named by argv[1]: the runner reads that file over its own SSH connection,
an oracle independent of anything the app shows. A terminal whose DECRQM
handler throws never answers.

Finally the screen is restored and one line is printed:
    PS3161_DONE_<n>   where <n> is the number of DECRPM answers received.
"""
import os
import re
import select
import sys
import termios
import time
import tty

FRAMES = 60
QUERIES = (b"\x1b[?2026$p", b"\x1b[?2004$p", b"\x1b[?1049$p", b"\x1b[4$p")
ANSWER = re.compile(rb"\x1b\[\??\d+;\d+\$y")
WIDTH = 34

out_path = os.path.expanduser(sys.argv[1])
fd = sys.stdin.fileno()
saved = termios.tcgetattr(fd)
tty.setraw(fd)


def write(data: bytes) -> None:
    os.write(1, data)


def frame(n: int) -> bytes:
    yes_selected = n % 2 == 0
    rows = [
        (b"\x1b[38;5;214m", "Accessing workspace:"),
        (b"\x1b[1;37m", "~/git/bots-management"),
        (b"\x1b[0m", ""),
        (b"\x1b[0m", "Do you trust the contents of"),
        (b"\x1b[0m", "this project?"),
        (b"\x1b[38;2;66;133;244m", "Antigravity CLI requires"),
        (b"\x1b[0m", "permission to read, edit, and"),
        (b"\x1b[0m", "execute files here."),
        (b"\x1b[38;2;66;133;244m" if yes_selected else b"\x1b[2m",
         ("> " if yes_selected else "  ") + "Yes, I trust this folder"),
        (b"\x1b[38;2;66;133;244m" if not yes_selected else b"\x1b[2m",
         ("> " if not yes_selected else "  ") + "No, exit"),
        (b"\x1b[38;5;178m", f"auto - frame {n:02d}/{FRAMES}"),
    ]
    parts = [b"\x1b[?2026h", b"\x1b[H\x1b[2J"]
    parts.append(b"\x1b[1;1H\x1b[38;5;244m\xe2\x95\xad" + "─".encode() * WIDTH + b"\xe2\x95\xae")
    for index, (sgr, text) in enumerate(rows, start=2):
        cell = text[:WIDTH].ljust(WIDTH).encode()
        parts.append(f"\x1b[{index};1H".encode() + b"\x1b[38;5;244m\xe2\x94\x82\x1b[0m" + sgr + cell
                     + b"\x1b[0m\x1b[38;5;244m\xe2\x94\x82")
    parts.append(f"\x1b[{len(rows) + 2};1H".encode() + b"\xe2\x95\xb0" + "─".encode() * WIDTH
                 + b"\xe2\x95\xaf\x1b[0m")
    parts.append(b"\x1b[?2026l")
    return b"".join(parts)


answers = bytearray()
try:
    write(b"\x1b[?1049h\x1b[?25l\x1b[?2004h")
    write(b"".join(QUERIES))
    for n in range(FRAMES):
        write(frame(n))
        time.sleep(0.02)
        ready, _, _ = select.select([fd], [], [], 0)
        if ready:
            answers += os.read(fd, 4096)
    deadline = time.monotonic() + 5
    while len(ANSWER.findall(bytes(answers))) < len(QUERIES) and time.monotonic() < deadline:
        ready, _, _ = select.select([fd], [], [], 0.2)
        if ready:
            answers += os.read(fd, 4096)
finally:
    write(b"\x1b[?2004l\x1b[?25h\x1b[?1049l")
    termios.tcsetattr(fd, termios.TCSADRAIN, saved)

with open(out_path, "w") as record:
    record.write(bytes(answers).hex() + "\n")
# Own line: a multiplexer that ignores the alternate screen leaves the cursor
# on the last row the TUI drew.
print(f"\nPS3161_DONE_{len(ANSWER.findall(bytes(answers)))}")
