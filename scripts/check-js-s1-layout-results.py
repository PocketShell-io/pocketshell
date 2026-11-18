#!/usr/bin/env python3
"""Require the packaged S1 layout journey (#3120) at both phone widths; reject vacuous reports.

--results-dir holds junit-<label>/TEST-*.xml and layout-<label>.json for each of
412x915 and 360x800. Each width must execute J26S1LayoutJourney exactly once,
pass, and leave a non-empty evidence JSON whose measured counts are > 0 (G3).
"""
import argparse
import json
import tempfile
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

REQUIRED_CLASS = "com.pocketshell.app.smoke.J26S1LayoutJourney"
REQUIRED_METHOD = "hitTargetsAndWorkspaceBottomHoldAtPhoneWidth"
LABELS = ("412x915", "360x800")
SCREENS = ("1-hosts", "2-session-tree", "3-workspace", "5-settings")
# Element kinds that must be counted > 0 on a screen (G3), beyond "some target measured".
REQUIRED_KINDS = {"1-hosts": ("host-row",), "2-session-tree": ("dir-header", "session-leaf")}
LAYOUT_SCREENS = ("2-session-tree", "3-workspace", "4-workspace-ime-up")
IME_BEHAVIOURS = ("layout-viewport-resized", "visual-viewport-resized", "no-resize-native-ime-bound")


def validate_junit(directory):
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"{directory}: no instrumentation reports")
    cases = []
    for path in reports:
        root = ET.parse(path).getroot()
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite")) if root.tag == "testsuites" else []
        if not suites:
            raise ValueError("missing testsuite")
        for suite in suites:
            rows = list(suite.findall("testcase"))
            if int(suite.attrib.get("tests", "-1")) != len(rows):
                raise ValueError("declared test count differs from testcase count")
            for attribute, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
                if int(suite.attrib.get(attribute, "0")) != sum(len(list(c.iter(tag))) for c in rows):
                    raise ValueError("declared status differs from testcase status")
            cases.extend(rows)
    if Counter((c.attrib.get("classname"), c.attrib.get("name")) for c in cases) != Counter({(REQUIRED_CLASS, REQUIRED_METHOD): 1}):
        raise ValueError(f"{directory}: expected exactly {REQUIRED_CLASS}#{REQUIRED_METHOD} once")
    if any(list(c.iter(t)) for c in cases for t in ("failure", "error", "skipped")):
        raise ValueError(f"{directory}: the S1 layout journey failed or skipped")


def validate_evidence(path, label):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"{path}: unreadable evidence: {error}")
    if data.get("label") != label:
        raise ValueError(f"{path}: label mismatch")
    for screen in SCREENS:
        hits = data.get(screen + ".hits")
        if not isinstance(hits, dict) or not hits.get("coarse"):
            raise ValueError(f"{path}: {screen} has no coarse-pointer measurement")
        counts = hits.get("counts", {})
        if sum(counts.values()) <= 0:
            raise ValueError(f"{path}: {screen} measured zero elements")
        for kind in REQUIRED_KINDS.get(screen, ()):
            if counts.get(kind, 0) <= 0:
                raise ValueError(f"{path}: {screen} measured no {kind}")
        if hits.get("small"):
            raise ValueError(f"{path}: {screen} has sub-48px targets: {hits['small']}")
    for screen in LAYOUT_SCREENS:
        layout = data.get(screen + ".layout")
        if not isinstance(layout, dict) or not layout.get("web", {}).get("workspace"):
            raise ValueError(f"{path}: {screen} has no workspace measurement")
        native, web = layout["native"], layout["web"]
        if native.get("statusTopPx", 0) <= 0:
            raise ValueError(f"{path}: {screen} ran with a zero native status-bar inset")
        if web.get("insetTop", 0) <= 0:
            raise ValueError(f"{path}: {screen} resolved --ps-inset-top is zero")
        measured = layout.get("navInsetMeasured")
        reads = layout.get("navOsReads")
        if not isinstance(measured, bool) or not isinstance(reads, dict):
            raise ValueError(f"{path}: {screen} lacks the explicit navInsetMeasured marker and navOsReads")
        if measured:
            if native.get("osNavBottomPx", native.get("navBottomPx", 0)) <= 0 and max(reads.values(), default=0) <= 0:
                raise ValueError(f"{path}: {screen} claims a measured nav inset but every OS read is zero")
            if screen != "4-workspace-ime-up" and not web.get("insetBottom", 0) > 0:
                raise ValueError(f"{path}: {screen} resolved --ps-inset-bottom is zero with a measured nav inset")
        else:
            # Only the explicit marker with all-zero OS reads and no CSS bottom inset passes.
            if any(v != 0 for v in reads.values()) or native.get("navBottomPx", 0) != 0 or web.get("insetBottom", 0) != 0:
                raise ValueError(f"{path}: {screen} navInsetMeasured=false but a nav inset is present")
        last = web.get("lastControl")
        if not isinstance(last, dict) or last.get("covered") is not False:
            raise ValueError(f"{path}: {screen} has no uncovered last control measurement")
        if screen == "4-workspace-ime-up":
            if layout.get("imeBehaviour") not in IME_BEHAVIOURS:
                raise ValueError(f"{path}: {screen} has no named IME behaviour")
            if not native.get("imeVisible") or native.get("imeBottomPx", 0) <= 0:
                raise ValueError(f"{path}: {screen} IME was not visible")


def validate_results(directory):
    for label in LABELS:
        validate_junit(directory / f"junit-{label}")
        validate_evidence(directory / f"layout-{label}.json", label)
        if not list(directory.glob(f"s1-{label}-*.png")):
            raise ValueError(f"no screenshot saved for {label}")


def _junit(directory, tests="1", extra=None, name=REQUIRED_METHOD):
    directory.mkdir(parents=True)
    suite = ET.Element("testsuite", tests=tests)
    case = ET.SubElement(suite, "testcase", classname=REQUIRED_CLASS, name=name)
    if extra:
        ET.SubElement(case, extra)
        suite.set({"failure": "failures", "error": "errors", "skipped": "skipped"}[extra], "1")
    ET.ElementTree(suite).write(directory / "TEST-s1.xml")


def _evidence(path, label, small=False, zero=False, nonav=False, noleaf=False, nosettings=False, noime=False,
              zeroinset=False, covered=False, nolast=False, marker=None, notop=False):
    counts = {"icon-btn": 3, "host-row": 1, "dir-header": 1, "session-leaf": 0 if noleaf else 1}
    if zero:
        counts = {k: 0 for k in counts}
    hit = {"coarse": True, "counts": counts, "small": [{"w": 30}] if small else []}
    last = None if nolast else {"covered": covered, "bottom": 690}
    web = {"workspace": {"bottom": 700}, "insetBottom": 0 if (zeroinset or nonav) else 21, "insetTop": 0 if notop else 24, "lastControl": last}
    native = {"navBottomPx": 0 if nonav else 63, "statusTopPx": 63, "imeVisible": True, "imeBottomPx": 800}
    layout = {"web": web, "native": native, "navInsetMeasured": not nonav, "navOsReads": {"webViewPx": 0 if nonav else 63, "decorPx": 0 if nonav else 63}}
    if marker == "missing":
        del layout["navInsetMeasured"]
    elif marker == "false-with-nav":
        layout["navInsetMeasured"] = False
    elif marker == "true-no-nav":
        layout["navInsetMeasured"] = True
    data = {"label": label}
    for s in SCREENS:
        if s == "5-settings" and nosettings:
            continue
        data[s + ".hits"] = hit
    for s in LAYOUT_SCREENS:
        entry = dict(layout)
        if s == "4-workspace-ime-up" and not noime:
            entry["imeBehaviour"] = "no-resize-native-ime-bound"
        data[s + ".layout"] = entry
    path.write_text(json.dumps(data))


def self_test():
    probes = ["pass", "failure", "skipped", "wrong-method", "count-mismatch", "no-evidence", "small", "zero", "no-shot", "missing-width",
              "no-leaf", "no-settings", "no-ime-behaviour", "zero-inset", "covered", "no-last-control", "nav-absent-explicit", "nav-marker-missing", "nav-false-but-present", "nav-true-but-zero", "zero-top-inset"]
    with tempfile.TemporaryDirectory(prefix="s1-layout-guard-") as temp:
        for index, probe in enumerate(probes):
            d = Path(temp) / str(index)
            d.mkdir()
            for label in LABELS:
                if probe == "missing-width" and label == "360x800":
                    continue
                _junit(d / f"junit-{label}", tests="2" if probe == "count-mismatch" else "1",
                       extra=probe if probe in ("failure", "skipped") else None,
                       name="other" if probe == "wrong-method" else REQUIRED_METHOD)
                if probe != "no-evidence":
                    _evidence(d / f"layout-{label}.json", label, small=probe == "small", zero=probe == "zero", nonav=probe in ("nav-absent-explicit", "nav-true-but-zero"),
                              noleaf=probe == "no-leaf", nosettings=probe == "no-settings", noime=probe == "no-ime-behaviour",
                              zeroinset=probe == "zero-inset", covered=probe == "covered", nolast=probe == "no-last-control",
                              marker={"nav-marker-missing": "missing", "nav-false-but-present": "false-with-nav", "nav-true-but-zero": "true-no-nav"}.get(probe),
                              notop=probe == "zero-top-inset")
                if probe != "no-shot":
                    (d / f"s1-{label}-3-workspace.png").write_bytes(b"png")
            try:
                validate_results(d)
                ok = True
            except (ValueError, OSError, ET.ParseError):
                ok = False
            if ok != (probe in ("pass", "nav-absent-explicit")):
                raise ValueError("self-test failed: " + probe)
    print(f"PASS: {len(probes)}/{len(probes)} S1 layout result guard checks")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.results_dir is None:
        parser.error("--results-dir is required")
    validate_results(args.results_dir)
    print(f"PASS: {REQUIRED_CLASS}#{REQUIRED_METHOD} executed once and passed at {', '.join(LABELS)}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError) as error:
        raise SystemExit("FAIL: " + str(error))
