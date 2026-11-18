#!/usr/bin/env python3
"""Exact-result check for the background grace hold journey (#3160).

The journey (scripts/js-background-hold-journey.mjs) writes one JUnit file and
one evidence JSON per case. A green JUnit row is not enough on its own: this
re-checks each case's load-bearing evidence, so a journey edited into a proxy
(or a run that silently skipped a case) cannot pass.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

JUNIT = "TEST-js.BackgroundHoldJourney.xml"
CASES = (
    "withinGraceHomeKeepsSameConnection",
    "deviceIdleWithinGraceKeepsConnection",
    "rapidReturnNeverCrashes",
    "beyondGraceAutoReattachesSameSession",
    "networkReturnAfterGiveUpReattaches",
    "restoredTabReconnects",
)
HOLD_TEXT = "Keeping 1 connection alive"
APP_PACKAGE_PREFIX = "com.pocketshell.app"
CRASH_LOG = "crash-logcat.txt"


class CheckError(Exception):
    pass


def need(condition: bool, message: str) -> None:
    if not condition:
        raise CheckError(message)


def load(results: Path, name: str) -> dict:
    path = results / f"{name}.json"
    need(path.is_file(), f"{name}: missing evidence {path.name}")
    return json.loads(path.read_text(encoding="utf-8"))


def check_within(name: str, data: dict) -> None:
    before, after = data["before"], data["after"]
    held = [s for s in data["samples"] if s["atMs"] >= 4_000]
    need(len(held) >= 3, f"{name}: the background window was not sampled")
    need(all((s.get("notification") or {}).get("text") == HOLD_TEXT for s in held),
         f"{name}: the hold notification was not '{HOLD_TEXT}' throughout the background")
    need(all(s["network"]["effective"] == "NONE" for s in held), f"{name}: Android blocked the app's network while held")
    need(data["backgroundMs"] >= 29_000, f"{name}: backgrounded for only {data['backgroundMs']} ms")
    need(before["hostConnections"] and after["hostConnections"] == before["hostConnections"],
         f"{name}: the host saw a different SSH TCP connection after the return")
    need(all(s["hostConnections"] == before["hostConnections"] for s in held),
         f"{name}: the host SSH TCP connection changed while backgrounded")
    need(after["live"]["transportId"] == before["live"]["transportId"], f"{name}: the native transport changed")
    need(after["live"]["selectedId"] == before["live"]["selectedId"], f"{name}: the attached session changed")
    need(after["dials"] == before["dials"], f"{name}: the app dialled during a within-grace return")
    need(after["opens"] == before["opens"] and after["closes"] == before["closes"], f"{name}: a PTY was re-opened")
    need(not any(p in ("reconnecting", "lost") for p in after["journalPhases"]), f"{name}: the controller reconnected")
    need(after.get("notificationAfter") is None, f"{name}: the hold notification outlived the return")
    markers = data.get("markers") or []
    need(len(markers) == 2 and all(markers), f"{name}: the typed command did not echo after the return")


def app_crashes(text: str) -> list[str]:
    """Our app's crashes in an `adb logcat -b crash` dump: FATAL blocks for its
    process, and any foreground-service start that timed out."""
    found = []
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if "ForegroundServiceDidNotStartInTimeException" in line or "did not then call Service.startForeground" in line:
            found.append(line.strip())
        elif "FATAL EXCEPTION" in line and any(f"Process: {APP_PACKAGE_PREFIX}" in nxt for nxt in lines[index:index + 3]):
            found.append(" | ".join(nxt.strip() for nxt in lines[index:index + 3]))
    return found


def check_rapid(name: str, data: dict) -> None:
    iterations = data["iterations"]
    gaps = sorted({i["gapMs"] for i in iterations})
    need(len(iterations) >= 10 and {300, 600, 1500} <= set(gaps), f"{name}: too few or too slow HOME→return cycles: {gaps}")
    need(not data["crashes"], f"{name}: the app crashed: {data['crashes'][:2]}")
    pid = data["pidAfter"]
    need(pid and all(i["pid"] == pid for i in iterations), f"{name}: the app process died or restarted during the cycles")
    need(data["notificationAfter"] is None, f"{name}: a hold notification was left in the foreground")
    need(data["hostConnectionsAfter"] == data["before"]["hostConnections"], f"{name}: the host SSH TCP connection changed")
    need(data["live"]["transportId"] == data["before"]["live"]["transportId"], f"{name}: the native transport changed")
    need(all(data.get("markers") or [None]), f"{name}: no typed echo after the cycles")


def check_beyond(name: str, data: dict) -> None:
    before, after = data["before"], data["after"]
    need(after["live"]["selectedId"] == before["live"]["selectedId"], f"{name}: a different session was re-attached")
    need(after["live"]["transportId"] != before["live"]["transportId"], f"{name}: no fresh transport after grace")
    need(data["reattachMs"] <= 25_000, f"{name}: the re-attach took {data['reattachMs']} ms")
    need(after["banner"] == "" or after["banner"] is None, f"{name}: a banner was left on screen")
    markers = data.get("markers") or []
    need(len(markers) == 2 and all(markers) and markers[0] in after["terminal"],
         f"{name}: the scrollback printed before backgrounding is not on screen")
    need(data.get("bannersSinceLeaving") == [], f"{name}: a lost-connection banner showed during the return: {data.get('bannersSinceLeaving')}")
    need(markers[0] in (data.get("duringReconnect") or {}).get("terminal", ""),
         f"{name}: the pane went blank while reconnecting")
    late = [s for s in data["samples"] if s["atMs"] >= 35_000]
    need(late and all(s.get("notification") is None for s in late), f"{name}: the hold outlived the 30 s grace")
    need(all(not set(s["hostConnections"]) & set(before["hostConnections"]) for s in late),
         f"{name}: the host connection outlived the 30 s grace (setting not honoured)")


def check_network(name: str, data: dict) -> None:
    before, after = data["before"], data["after"]
    need("Could not reconnect" in (data.get("gaveUpBanner") or ""), f"{name}: the ladder never gave up, so nothing was proven")
    need(after["live"]["selectedId"] == before["live"]["selectedId"], f"{name}: a different session was re-attached")
    need(after["live"]["transportId"] != before["live"]["transportId"], f"{name}: no fresh transport")
    need(not after.get("banner"), f"{name}: the give-up banner stayed on screen")
    markers = data.get("markers") or []
    need(len(markers) == 2 and all(markers), f"{name}: no typed echo after the automatic re-attach")
    need(markers[0] in (after.get("terminal") or ""), f"{name}: the scrollback printed before backgrounding is not on screen")


def check_restored(name: str, data: dict) -> None:
    before, after = data["before"], data["after"]
    need(data.get("reconnectShown") is True, f"{name}: no visible Reconnect while the network was down")
    need(bool((data.get("afterOfflineTap") or {}).get("reconnectText")),
         f"{name}: a failed offline Reconnect did not keep the button on screen")
    need(after["live"]["selectedId"] == before["live"]["selectedId"], f"{name}: a different session was re-attached")
    need(not after.get("paneFailed") and not after.get("banner"), f"{name}: the pane or banner stayed stranded after the network returned")
    need(bool(after.get("hostSession")), f"{name}: the host no longer has the session")
    # The pre-outage socket may linger on the host (the phone vanished without
    # closing it); the re-attach must have opened a fresh one.
    need(bool(set(after["hostConnections"]) - set(before["hostConnections"])),
         f"{name}: the host saw no fresh SSH connection")
    markers = data.get("markers") or []
    need(len(markers) == 2 and all(markers) and markers[0] in after["terminal"],
         f"{name}: the recovered pane lacks its scrollback or a typed echo")


CHECKS = {
    "withinGraceHomeKeepsSameConnection": check_within,
    "deviceIdleWithinGraceKeepsConnection": check_within,
    "rapidReturnNeverCrashes": check_rapid,
    "beyondGraceAutoReattachesSameSession": check_beyond,
    "networkReturnAfterGiveUpReattaches": check_network,
    "restoredTabReconnects": check_restored,
}


def check(results: Path, cases: tuple[str, ...]) -> None:
    junit = results / JUNIT
    need(junit.is_file(), f"missing {JUNIT} in {results}")
    root = ET.parse(junit).getroot()
    rows = root.findall("testcase")
    names = [row.attrib.get("name") for row in rows]
    need(sorted(names) == sorted(cases), f"expected exactly {list(cases)}, ran {names}")
    need(int(root.attrib.get("tests", "-1")) == len(rows) == len(cases), "JUnit test count disagrees with its rows")
    for row in rows:
        failure = row.find("failure")
        need(failure is None and row.find("skipped") is None and row.find("error") is None,
             f"{row.attrib['name']}: {failure.attrib.get('message') if failure is not None else 'skipped or errored'}")
    for name in cases:
        CHECKS[name](name, load(results, name))
    crash_log = results / CRASH_LOG
    need(crash_log.is_file(), f"missing {CRASH_LOG}: the run's crash buffer was not captured")
    crashes = app_crashes(crash_log.read_text(encoding="utf-8", errors="replace"))
    need(not crashes, f"the app crashed during the run: {crashes[:2]}")


def self_test() -> None:
    within = {
        "backgroundMs": 30_100,
        "samples": [{"atMs": t, "notification": {"text": HOLD_TEXT}, "network": {"effective": "NONE"},
                     "hostConnections": ["172.21.0.1:5"]} for t in (5_000, 10_000, 20_000, 30_000)],
        "before": {"live": {"transportId": "t", "selectedId": "s"}, "dials": 1, "opens": 1, "closes": 0,
                   "hostConnections": ["172.21.0.1:5"]},
        "after": {"live": {"transportId": "t", "selectedId": "s"}, "dials": 1, "opens": 1, "closes": 0,
                  "hostConnections": ["172.21.0.1:5"], "journalPhases": ["background", "listing", "live"],
                  "notificationAfter": None},
        "markers": ["A_42", "B_42"],
    }
    beyond = {
        "reattachMs": 4_000,
        "samples": [{"atMs": 40_000, "notification": None, "hostConnections": []}],
        "before": {"live": {"transportId": "t", "selectedId": "s"}, "hostConnections": ["172.21.0.1:5"]},
        "after": {"live": {"transportId": "u", "selectedId": "s"}, "banner": "", "terminal": "$ echo\nA_42\n$"},
        "markers": ["A_42", "B_42"],
        "duringReconnect": {"phase": "reconnecting", "terminal": "$ echo\nA_42\n$"},
        "bannersSinceLeaving": [],
    }
    network = {
        "gaveUpBanner": "Could not reconnect to 10.0.2.2 after 5 attempts.",
        "before": {"live": {"transportId": "t", "selectedId": "s"}},
        "after": {"live": {"transportId": "u", "selectedId": "s"}, "banner": "", "terminal": "$ echo\nA_42\n$"},
        "markers": ["A_42", "B_42"],
    }
    rapid = {
        "iterations": [{"gapMs": gap, "pid": "42", "notification": None} for gap in (300, 600, 1500) * 4],
        "crashes": [], "pidAfter": "42", "notificationAfter": None,
        "hostConnectionsAfter": ["172.21.0.1:5"],
        "before": {"live": {"transportId": "t"}, "hostConnections": ["172.21.0.1:5"]},
        "live": {"transportId": "t"}, "markers": ["R_42"],
    }
    restored = {
        "reconnectShown": True,
        "afterOfflineTap": {"reconnectText": "Reconnect", "banner": "Could not reconnect to h after 5 attempts."},
        "before": {"live": {"transportId": "t", "selectedId": "s"}, "hostConnections": ["172.21.0.1:5"]},
        "after": {"live": {"transportId": "u", "selectedId": "s"}, "hostSession": {"tag": "x"}, "paneFailed": None, "banner": "",
                  "hostConnections": ["172.21.0.1:9"], "terminal": "$ echo\nT_42\n$"},
        "markers": ["T_42", "T2_42"],
    }
    good = {CASES[0]: within, CASES[1]: within, CASES[2]: rapid, CASES[3]: beyond, CASES[4]: network, CASES[5]: restored}

    def write(directory: Path, cases: dict, failing: str | None = None, junit_names=None, crash_log: str | None = "") -> None:
        if crash_log is not None:
            (directory / CRASH_LOG).write_text(crash_log, encoding="utf-8")
        rows = []
        for name in (junit_names or cases):
            body = f'<failure message="boom"/>' if name == failing else ""
            rows.append(f'<testcase classname="js.BackgroundHoldJourney" name="{name}">{body}</testcase>')
        (directory / JUNIT).write_text(
            f'<testsuite name="js.BackgroundHoldJourney" tests="{len(rows)}">{"".join(rows)}</testsuite>', encoding="utf-8")
        for name, data in cases.items():
            (directory / f"{name}.json").write_text(json.dumps(data), encoding="utf-8")

    def expect_fail(label: str, mutate) -> None:
        with tempfile.TemporaryDirectory() as raw:
            cases = json.loads(json.dumps(good))
            kwargs = mutate(cases) or {}
            write(Path(raw), cases, **kwargs)
            try:
                check(Path(raw), CASES)
            except CheckError:
                return
            raise SystemExit(f"self-test: {label} was not rejected")

    with tempfile.TemporaryDirectory() as raw:
        write(Path(raw), good)
        check(Path(raw), CASES)
    expect_fail("a failed case", lambda c: {"failing": CASES[0]})
    expect_fail("a missing case", lambda c: {"junit_names": CASES[:5]})
    expect_fail("a stranded pane with no Reconnect", lambda c: c[CASES[5]].update(reconnectShown=False))
    expect_fail("Reconnect to another session", lambda c: c[CASES[5]]["after"]["live"].update(selectedId="z"))
    expect_fail("a pane left stranded after the network returned",
                lambda c: c[CASES[5]]["after"].update(paneFailed="Connect to a host before attaching a session."))
    expect_fail("no fresh host connection", lambda c: c[CASES[5]]["after"].update(hostConnections=["172.21.0.1:5"]))
    expect_fail("an offline tap that removed the button", lambda c: c[CASES[5]]["afterOfflineTap"].update(reconnectText=None))
    expect_fail("a new transport within grace", lambda c: c[CASES[0]]["after"]["live"].update(transportId="x"))
    expect_fail("a changed host TCP connection", lambda c: c[CASES[1]]["after"].update(hostConnections=["172.21.0.1:9"]))
    expect_fail("no hold notification", lambda c: c[CASES[0]]["samples"][1].update(notification=None))
    expect_fail("a lost echo", lambda c: c[CASES[0]].update(markers=["A_42", None]))
    expect_fail("a different session after grace", lambda c: c[CASES[3]]["after"]["live"].update(selectedId="z"))
    expect_fail("missing scrollback", lambda c: c[CASES[3]]["after"].update(terminal="$"))
    expect_fail("a reconnect banner during the silent return",
                lambda c: c[CASES[3]].update(bannersSinceLeaving=[{"at": 1, "text": "Lost the connection to h. Reconnecting…"}]))
    expect_fail("a blank pane while reconnecting", lambda c: c[CASES[3]]["duringReconnect"].update(terminal=""))
    expect_fail("a hold past grace", lambda c: c[CASES[3]]["samples"][0].update(notification={"text": HOLD_TEXT}))
    expect_fail("a ladder that never gave up", lambda c: c[CASES[4]].update(gaveUpBanner=""))
    expect_fail("network case without its scrollback", lambda c: c[CASES[4]]["after"].update(terminal="$"))
    expect_fail("a banner left on screen", lambda c: c[CASES[4]]["after"].update(banner="Could not reconnect"))
    fgs_crash = ("11-18 12:26:00.486  8567  8567 E AndroidRuntime: FATAL EXCEPTION: main\n"
                 "11-18 12:26:00.486  8567  8567 E AndroidRuntime: Process: com.pocketshell.app.i3160grn, PID: 8567\n"
                 "11-18 12:26:00.486  8567  8567 E AndroidRuntime: android.app.RemoteServiceException$"
                 "ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()\n")
    expect_fail("an FGS start timeout in the crash buffer", lambda c: {"crash_log": fgs_crash})
    expect_fail("an uncaptured crash buffer", lambda c: {"crash_log": None})
    expect_fail("a crashing rapid case", lambda c: c[CASES[2]].update(crashes=["FATAL"]))
    expect_fail("a restarted process", lambda c: c[CASES[2]]["iterations"][3].update(pid="43"))
    expect_fail("too few fast cycles", lambda c: c[CASES[2]].update(iterations=c[CASES[2]]["iterations"][:5]))
    with tempfile.TemporaryDirectory() as raw:
        other = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: Process: com.google.android.gms, PID: 1\n"
        write(Path(raw), good, crash_log=other)
        check(Path(raw), CASES)  # another app's crash is not ours
    print("PASS: check-js-background-hold-results self-test")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--cases", default=",".join(CASES))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.results_dir is None:
        parser.error("--results-dir is required")
    cases = tuple(name for name in args.cases.split(",") if name)
    unknown = [name for name in cases if name not in CASES]
    if unknown:
        parser.error(f"unknown cases: {unknown}")
    try:
        check(args.results_dir, cases)
    except (CheckError, KeyError, TypeError, ValueError) as error:
        print(f"FAIL: background hold results: {error}", file=sys.stderr)
        return 1
    print(f"PASS: background hold journey ran {len(cases)} exact case(s) with load-bearing evidence in {args.results_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
