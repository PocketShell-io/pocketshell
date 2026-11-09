#!/usr/bin/env python3
"""Write failing JUnit testcases (name + reason) to $GITHUB_STEP_SUMMARY and stdout.

Scans every TEST-*.xml under the given roots so a red full-suite job names the
failing journey and its assertion on the run page, not only in a 7000-line log.
Always exits 0: it reports, the result checkers remain the gate.
"""
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def failures(roots):
    for root in roots:
        for path in sorted(Path(root).rglob("TEST-*.xml")):
            try:
                tree = ET.parse(path)
            except ET.ParseError as error:
                yield str(path), "<unparseable report>", str(error)
                continue
            for case in tree.iter("testcase"):
                for bad in list(case.findall("failure")) + list(case.findall("error")):
                    reason = (bad.get("message") or bad.text or "").strip().splitlines()
                    yield case.get("classname", "?") + "." + case.get("name", "?"), str(path), (reason[0] if reason else "")[:600]


def render(items):
    if not items:
        return "### Packaged journeys: no failing testcase found in JUnit reports\n"
    lines = ["### Failing packaged journeys", ""]
    for name, path, reason in items:
        lines.append("- `%s`: %s (`%s`)" % (name, reason or "no message", path))
    return "\n".join(lines) + "\n"


def main(argv):
    text = render(list(failures(argv[1:] or ["android/app/build/outputs"])))
    sys.stdout.write(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
