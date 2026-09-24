#!/usr/bin/env python3
"""Check Android journey dispatch for the JS-first app and legacy app2.

The JS path is deliberately a dispatch guard, not a feature-coverage claim. It
proves that each journey-shaped androidTest class is selected by one of the
packaged smoke/lifecycle/composer lanes, and that the lane's exact method
contract agrees with both its source and result checker. The independent
24-class feature qualification gate remains separate and incomplete until
those journeys exist.

Usage:
  scripts/check-js-first-android-journeys.py [--repo-root DIR]
  scripts/check-js-first-android-journeys.py --self-test
"""

from __future__ import annotations

import argparse
import ast
import contextlib
import io
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class LaneContract:
    name: str
    class_name: str
    child_runner: str
    result_checker: str
    methods: frozenset[str]


LANES = (
    LaneContract(
        name="smoke",
        class_name="com.pocketshell.app.smoke.JsShellPackagedSmokeTest",
        child_runner="scripts/connected-js-smoke.sh",
        result_checker="scripts/check-js-smoke-results.py",
        methods=frozenset(
            {
                "launchShowsVerifiedSourcesAndAssetIdentity",
                "settingsAndAndroidBackReturnHome",
                "composerInputStaysAboveImeWithinSafeArea",
            }
        ),
    ),
    LaneContract(
        name="lifecycle",
        class_name="com.pocketshell.app.smoke.SshPtyDockerJourneyTest",
        child_runner="scripts/connected-js-lifecycle.sh",
        result_checker="scripts/check-js-lifecycle-results.py",
        methods=frozenset({"sshSessionSwitchingAndBackgroundGraceAgainstDockerFixture"}),
    ),
    LaneContract(
        name="composer-docker",
        class_name="com.pocketshell.app.smoke.JsComposerDockerJourneyTest",
        child_runner="scripts/connected-js-composer-docker.sh",
        result_checker="scripts/check-js-composer-journey-results.py",
        methods=frozenset({"composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop"}),
    ),
)

JOURNEY_SUFFIX = re.compile(r"(?:E2eTest|DockerTest|JourneyTest|SmokeTest)$")
ISSUE_JUSTIFICATION = re.compile(
    r"CI_JOURNEY_SUITE_JUSTIFIED:\s*(#[1-9][0-9]*)\s+([^\r\n]+)"
)


@dataclass
class Finding:
    kind: str
    value: str
    detail: str = ""


def _read(path: Path, label: str, findings: list[Finding]) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        findings.append(Finding("ERROR", f"cannot read {label}: {exc}"))
        return ""


def _strip_shell_comments(source: str) -> str:
    # The relevant dispatch tokens contain no shell quoting, so stripping
    # full-line and trailing comments is sufficient while avoiding a false hit
    # on documentation that names a lane but does not execute it.
    lines = []
    for line in source.splitlines():
        if line.lstrip().startswith("#"):
            continue
        lines.append(line.split(" #", 1)[0])
    return "\n".join(lines)


def _script_invokes(source: str, script: str) -> bool:
    active = _strip_shell_comments(source)
    return re.search(rf"(?m)^\s*(?:if\s+)?{re.escape(script)}(?:\s|$)", active) is not None


def _class_selector_matches(source: str, contract: LaneContract) -> bool:
    active = _strip_shell_comments(source)
    selector = "-Pandroid.testInstrumentationRunnerArguments.class="
    # The composer wrapper assigns its FQCN once and passes the variable in the
    # actual connected Gradle invocation; the other lanes pass the FQCN there.
    direct = re.search(
        r"['\"]?" + re.escape(selector) + re.escape(contract.class_name) + r"['\"]?(?:\s|$)", active
    )
    variable_match = re.search(
        rf"(?m)^\s*test_class=['\"]{re.escape(contract.class_name)}['\"]\s*$",
        active,
    ) and re.search(r"['\"]?" + re.escape(selector) + r"\$test_class['\"]?(?:\s|$)", active)
    return bool(direct or variable_match)


def _checker_contract(source: str, contract: LaneContract) -> tuple[str | None, set[str] | None]:
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return None, None
    values: dict[str, object] = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in {
                    "REQUIRED_CLASS",
                    "REQUIRED_METHOD",
                    "REQUIRED_METHODS",
                }:
                    try:
                        if (
                            target.id == "REQUIRED_METHODS"
                            and isinstance(node.value, ast.Call)
                            and isinstance(node.value.func, ast.Name)
                            and node.value.func.id == "frozenset"
                            and len(node.value.args) == 1
                        ):
                            values[target.id] = ast.literal_eval(node.value.args[0])
                        else:
                            values[target.id] = ast.literal_eval(node.value)
                    except (ValueError, TypeError):
                        values[target.id] = None
    class_name = values.get("REQUIRED_CLASS")
    methods = values.get("REQUIRED_METHODS")
    if methods is None and values.get("REQUIRED_METHOD") is not None:
        methods = {values["REQUIRED_METHOD"]}
    if not isinstance(class_name, str) or not isinstance(methods, (set, frozenset)):
        return None, None
    if not all(isinstance(method, str) for method in methods):
        return None, None
    return class_name, set(methods)


def _strip_java_non_code(source: str) -> tuple[str, list[str]]:
    """Blank comments and literals while preserving newlines; return comments too."""
    chars = list(source)
    comments: list[str] = []

    def blank(start: int, end: int) -> None:
        for index in range(start, end):
            if chars[index] not in "\r\n":
                chars[index] = " "

    def quoted_end(start: int, delimiter: str, text_block: bool = False) -> int:
        index = start + len(delimiter)
        while index < len(source):
            if source.startswith(delimiter, index):
                slashes = 0
                probe = index - 1
                while probe >= start and source[probe] == "\\":
                    slashes += 1
                    probe -= 1
                if slashes % 2 == 0:
                    return index + len(delimiter)
                index += len(delimiter)
            elif not text_block and source[index] == "\\":
                index += 2
            else:
                index += 1
        return len(source)

    index = 0
    while index < len(source):
        if source.startswith("//", index):
            end = source.find("\n", index)
            if end < 0:
                end = len(source)
            comments.append(source[index:end])
            blank(index, end)
            index = end
        elif source.startswith("/*", index):
            close = source.find("*/", index + 2)
            end = len(source) if close < 0 else close + 2
            comments.append(source[index:end])
            blank(index, end)
            index = end
        elif source.startswith('"""', index):
            end = quoted_end(index, '"""', text_block=True)
            blank(index, end)
            index = end
        elif source[index] in {'"', "'"}:
            end = quoted_end(index, source[index])
            blank(index, end)
            index = end
        else:
            index += 1
    return "".join(chars), comments


def _source_test_methods(source: str) -> set[str]:
    # These sources are Java AndroidJUnit4 tests. Comments, regular literals,
    # char literals and Java text blocks cannot supply fake @Test declarations.
    cleaned, _comments = _strip_java_non_code(source)
    pattern = re.compile(
        r"@Test\b(?:\s*\([^)]*\))?\s+"
        r"(?:(?:public|protected|private|static|final|synchronized)\s+)*"
        r"[\w$<>.?\[\]]+\s+([A-Za-z_$][\w$]*)\s*\("
    )
    return set(pattern.findall(cleaned))


def _fqcn_for_source(path: Path, root: Path, source: str) -> tuple[str, str]:
    code, _comments = _strip_java_non_code(source)
    package = re.search(r"(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;?", code)
    class_decl = re.search(r"\b(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)", code)
    if package is None or class_decl is None:
        relative = path.relative_to(root).with_suffix("")
        return ".".join(relative.parts), ""
    return f"{package.group(1)}.{class_decl.group(1)}", class_decl.group(1)


def _valid_justification(source: str) -> str | None:
    # Accept only source comments, so a marker embedded in a string does not
    # become an exemption. Support line comments and Javadocs, which are used
    # by both the Java test source and the Kotlin legacy suite.
    _code, comments = _strip_java_non_code(source)
    for comment in comments:
        for match in ISSUE_JUSTIFICATION.finditer(comment):
            reason = match.group(2).strip()
            # Issue reference plus a short concrete reason; reject marker-only
            # or generic "later" escape hatches.
            if len(reason) >= 16 and len(reason.split()) >= 3:
                return f"{match.group(1)} {reason}"
    return None


def check_js(repo_root: Path) -> list[Finding]:
    findings: list[Finding] = []
    android_test_root = repo_root / "android/app/src/androidTest"
    dispatcher_path = repo_root / "scripts/ci-js-first-packaged-lanes.sh"
    dispatcher = _read(dispatcher_path, "JS-first packaged lane dispatcher", findings)
    if not android_test_root.is_dir():
        findings.append(Finding("ERROR", "JS androidTest root is missing; refusing a zero-class pass"))
        return findings
    if not dispatcher:
        return findings

    # A fixed three-lane inventory makes an empty/partially deleted registry a
    # hard error. This is the current packaged suite only, not the 24-class
    # feature-journey inventory in scripts/js-journey-class-manifest.json.
    if {lane.name for lane in LANES} != {"smoke", "lifecycle", "composer-docker"}:
        findings.append(Finding("ERROR", "required smoke/lifecycle/composer lane inventory changed"))

    for lane in LANES:
        if not _script_invokes(dispatcher, lane.child_runner):
            findings.append(Finding("ERROR", f"dispatcher does not execute {lane.child_runner}"))
            continue
        child = _read(repo_root / lane.child_runner, f"{lane.name} lane selector", findings)
        if not child:
            continue
        if not _class_selector_matches(child, lane):
            findings.append(
                Finding("ERROR", f"{lane.name} selector does not dispatch exact class {lane.class_name}")
            )
        if not _script_invokes(child, f"$ROOT_DIR/{lane.result_checker}") and not re.search(
            rf"(?m)^\s*\"\$ROOT_DIR/{re.escape(lane.result_checker)}\"\s+--results-dir\s+\"\$RESULTS_DIR\"",
            _strip_shell_comments(child),
        ):
            findings.append(
                Finding("ERROR", f"{lane.name} lane does not execute result contract {lane.result_checker}")
            )
        checker_source = _read(repo_root / lane.result_checker, f"{lane.name} result checker", findings)
        checker_class, checker_methods = _checker_contract(checker_source, lane)
        if checker_class != lane.class_name or checker_methods != set(lane.methods):
            findings.append(
                Finding(
                    "ERROR",
                    f"{lane.name} result checker contract drift: expected {lane.class_name}"
                    f" methods={','.join(sorted(lane.methods))}; found {checker_class or '<missing>'}"
                    f" methods={','.join(sorted(checker_methods or set()))}",
                )
            )

    files = sorted(
        path
        for path in android_test_root.rglob("*")
        if path.is_file() and path.suffix in {".java", ".kt"}
    )
    if not files:
        findings.append(Finding("ERROR", "JS androidTest root contains zero source classes; refusing a vacuous J1 pass"))
        return findings

    actual_journey_classes: dict[str, tuple[Path, str, str]] = {}
    known_classes = {lane.class_name for lane in LANES}
    for path in files:
        source = _read(path, f"androidTest source {path.relative_to(repo_root)}", findings)
        code, _comments = _strip_java_non_code(source)
        package = re.search(r"(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;?", code)
        package_name = package.group(1) if package else ""
        declarations = re.findall(r"\b(?:class|interface|enum)\s+([A-Za-z_$][\w$]*)", code)
        candidates = [
            simple_name
            for simple_name in declarations
            if JOURNEY_SUFFIX.search(simple_name)
            or f"{package_name}.{simple_name}" in known_classes
        ]
        if not candidates and not declarations and JOURNEY_SUFFIX.search(path.stem):
            candidates = [path.stem]
        if len(candidates) > 1:
            findings.append(
                Finding(
                    "ERROR",
                    f"multiple journey-shaped class declarations in {path.relative_to(repo_root)}; split them so J1 can prove each selector",
                )
            )
        for simple_name in candidates:
            fqcn = f"{package_name}.{simple_name}" if package_name else simple_name
            actual_journey_classes[fqcn] = (path, source, simple_name)

    if not actual_journey_classes:
        findings.append(Finding("ERROR", "JS androidTest root contains zero journey-shaped classes; refusing a vacuous J1 pass"))
        return findings

    by_class = {lane.class_name: lane for lane in LANES}
    wired_classes: set[str] = set()
    for class_name, lane in by_class.items():
        source_record = actual_journey_classes.get(class_name)
        if source_record is None:
            findings.append(Finding("ERROR", f"required dispatched journey class is missing: {class_name}"))
            continue
        path, source, _ = source_record
        methods = _source_test_methods(source)
        if methods != set(lane.methods):
            findings.append(
                Finding(
                    "ERROR",
                    f"{lane.name} source methods drift for {class_name}: expected "
                    f"{','.join(sorted(lane.methods))}; found {','.join(sorted(methods)) or '<none>'}",
                )
            )
        wired_classes.add(class_name)
        findings.append(Finding("WIRED", class_name, f"{lane.name}: {','.join(sorted(lane.methods))}"))

    for class_name, (path, source, _simple_name) in sorted(actual_journey_classes.items()):
        if class_name in by_class:
            continue
        justification = _valid_justification(source)
        if justification:
            findings.append(Finding("JUSTIFIED", class_name, justification))
        else:
            findings.append(
                Finding(
                    "NEW",
                    class_name,
                    f"{path.relative_to(repo_root)} is not dispatched; add a packaged lane or an issue-backed CI_JOURNEY_SUITE_JUSTIFIED reason",
                )
            )

    if not wired_classes:
        findings.append(Finding("ERROR", "zero registered JS journey classes were dispatched"))
    return findings


def _legacy_files(repo_root: Path) -> list[Path]:
    roots = [repo_root / "app2/src/androidTest"]
    shared = repo_root / "shared"
    if shared.is_dir():
        roots.extend(path for path in shared.rglob("src/androidTest") if path.is_dir())
    return sorted(
        path
        for root in roots
        if root.is_dir()
        for path in root.rglob("*")
        if path.is_file() and path.suffix in {".kt", ".java"}
    )


def check_legacy(repo_root: Path) -> list[Finding]:
    """Mirror the stable/main app2 whole-module J1 rule without changing it."""
    findings: list[Finding] = []
    suite_path = repo_root / "scripts/ci-app2-journey-suite.sh"
    suite = _read(suite_path, "legacy app2 journey runner", findings)
    task_match = re.search(r'(?m)^JOURNEY_TASK="([^"]+)"', suite)
    if task_match is None:
        findings.append(Finding("ERROR", "legacy app2 runner has no JOURNEY_TASK"))
        return findings
    args = re.search(r"(?ms)^gradle_args\(\)\s*\{(.*?)^\}", suite)
    if args is None:
        findings.append(Finding("ERROR", "legacy app2 runner has no gradle_args()"))
        return findings
    if re.search(r"testInstrumentationRunnerArguments\.(?:class|package|annotation)=", args.group(1)):
        findings.append(Finding("ERROR", "legacy app2 runner now filters its wholesale class set"))
        return findings
    module = task_match.group(1).removeprefix(":")
    module = module[: module.rfind(":")]
    journey_root = repo_root / module.replace(":", "/") / "src/androidTest"
    if not journey_root.is_dir():
        findings.append(Finding("ERROR", f"legacy whole-module journey root is missing: {journey_root.relative_to(repo_root)}"))
        return findings
    wired: set[str] = set()
    for path in journey_root.rglob("*"):
        if path.is_file() and path.suffix in {".kt", ".java"}:
            source = _read(path, f"legacy androidTest source {path.relative_to(repo_root)}", findings)
            fqcn, _simple = _fqcn_for_source(path, journey_root, source)
            wired.add(fqcn)
    if not wired:
        findings.append(Finding("ERROR", "legacy app2 journey root has zero classes; refusing a vacuous J1 pass"))
        return findings
    for path in _legacy_files(repo_root):
        source = _read(path, f"legacy androidTest source {path.relative_to(repo_root)}", findings)
        fqcn, simple_name = _fqcn_for_source(path, path.parent, source)
        if not re.search(r"(?:E2eTest|DockerTest)$", simple_name or path.stem):
            continue
        if fqcn in wired:
            findings.append(Finding("WIRED", fqcn, "legacy app2 wholesale connected suite"))
        elif re.search(r"CI_JOURNEY_SUITE_JUSTIFIED:\s*[^\s]", source):
            findings.append(Finding("JUSTIFIED", fqcn, "legacy source justification"))
        else:
            findings.append(Finding("NEW", fqcn, "outside app2 wholesale connected suite and unjustified"))
    return findings


def _mode(repo_root: Path, requested: str) -> str:
    if requested != "auto":
        return requested
    if (any((repo_root / f"app2/build.gradle{suffix}").is_file() for suffix in ("", ".kts"))):
        return "legacy"
    if (repo_root / "android/app/src/androidTest").is_dir():
        return "js"
    return "legacy"


def _render(findings: Iterable[Finding]) -> None:
    labels = {"WIRED": "WIRED", "JUSTIFIED": "JUSTIFIED", "NEW": "NEW", "ERROR": "ERROR"}
    for finding in findings:
        suffix = f" — {finding.detail}" if finding.detail else ""
        print(f"J1 {labels[finding.kind]}: {finding.value}{suffix}")


def run_check(repo_root: Path, mode: str = "auto", machine: bool = False) -> int:
    selected = _mode(repo_root, mode)
    findings = check_js(repo_root) if selected == "js" else check_legacy(repo_root)
    if machine:
        for finding in findings:
            print(f"{finding.kind}\t{finding.value}\t{finding.detail}")
    else:
        print(f"J1 mode: {'JS packaged lane dispatch' if selected == 'js' else 'legacy app2 wholesale suite'}")
        _render(findings)
    return 1 if any(item.kind in {"ERROR", "NEW"} for item in findings) else 0


def _write_fixture(root: Path, lane: LaneContract, *, omit_selector: bool = False, omit_checker_method: bool = False) -> None:
    android_root = root / "android/app/src/androidTest/java"
    source_path = android_root / Path(*lane.class_name.split(".")).with_suffix(".java")
    source_path.parent.mkdir(parents=True, exist_ok=True)
    methods = sorted(lane.methods)
    source_methods = methods[:-1] if omit_checker_method and methods else methods
    source = "package " + lane.class_name.rsplit(".", 1)[0] + ";\npublic class " + lane.class_name.rsplit(".", 1)[1] + " {\n"
    source += "\n".join(f"@Test public void {method}() {{}}" for method in source_methods)
    source += "\n}\n"
    source_path.write_text(source, encoding="utf-8")

    dispatcher = root / "scripts/ci-js-first-packaged-lanes.sh"
    dispatcher.parent.mkdir(parents=True, exist_ok=True)
    dispatcher.write_text("#!/bin/sh\n" + "\n".join(lane.child_runner for lane in LANES) + "\n", encoding="utf-8")
    child = root / lane.child_runner
    child.parent.mkdir(parents=True, exist_ok=True)
    if lane.name == "composer-docker":
        selector = f"test_class='{lane.class_name}'\n"
        selector += "./gradlew -Pandroid.testInstrumentationRunnerArguments.class=$test_class\n"
    else:
        target = "wrong.Class" if omit_selector else lane.class_name
        selector = f"./gradlew -Pandroid.testInstrumentationRunnerArguments.class={target}\n"
    selector += f'"$ROOT_DIR/{lane.result_checker}" --results-dir "$RESULTS_DIR"\n'
    child.write_text(selector, encoding="utf-8")
    checker = root / lane.result_checker
    checker.parent.mkdir(parents=True, exist_ok=True)
    checker_methods = methods[:-1] if omit_checker_method and methods else methods
    checker_source = "REQUIRED_CLASS = " + repr(lane.class_name) + "\n"
    if len(checker_methods) == 1:
        checker_source += "REQUIRED_METHOD = " + repr(checker_methods[0]) + "\n"
    else:
        checker_source += "REQUIRED_METHODS = frozenset(" + repr(set(checker_methods)) + ")\n"
    checker.write_text(checker_source, encoding="utf-8")


def self_test() -> int:
    checks = 0
    failures = 0

    def probe(label: str, passed: bool, expected: bool) -> None:
        nonlocal checks, failures
        checks += 1
        if passed != expected:
            failures += 1
            print(f"FAIL [{checks}] {label}", file=sys.stderr)
        else:
            print(f"ok [{checks}] {label}")

    def check(root: Path, mode: str) -> int:
        with contextlib.redirect_stdout(io.StringIO()):
            return run_check(root, mode, machine=True)

    with tempfile.TemporaryDirectory(prefix="pocketshell-j1-js-") as temporary:
        root = Path(temporary)
        for lane in LANES:
            _write_fixture(root, lane)
        result = check(root, "auto")
        probe("all three exact packaged lane classes and methods dispatch", result == 0, True)

        dispatcher = root / "scripts/ci-js-first-packaged-lanes.sh"
        full_dispatcher = dispatcher.read_text(encoding="utf-8")
        lifecycle = next(lane for lane in LANES if lane.name == "lifecycle")
        dispatcher.write_text(
            "\n".join(line for line in full_dispatcher.splitlines() if line.strip() != lifecycle.child_runner) + "\n",
            encoding="utf-8",
        )
        probe("missing parent dispatcher lane fails", check(root, "js") != 0, True)
        dispatcher.write_text(full_dispatcher, encoding="utf-8")

        smoke = next(lane for lane in LANES if lane.name == "smoke")
        smoke_source = root / "android/app/src/androidTest/java" / Path(*smoke.class_name.split(".")).with_suffix(".java")
        original_smoke_source = smoke_source.read_text(encoding="utf-8")
        smoke_source.write_text(
            original_smoke_source.replace(
                "\n}\n",
                '\n    private static final String DOC = """\n'
                '@Test public void fakeTextBlockMethod() {}\n""";\n}\n',
            ),
            encoding="utf-8",
        )
        probe("Java text-block @Test example cannot satisfy or break method contract", check(root, "auto") == 0, True)
        smoke_source.write_text(original_smoke_source, encoding="utf-8")

        # Added journey-shaped source without dispatch/justification must turn
        # the passing registry red.
        extra = root / "android/app/src/androidTest/java/com/example/ExtraDockerTest.java"
        extra.parent.mkdir(parents=True, exist_ok=True)
        extra.write_text(
            "package com.example;\npublic class ExtraDockerTest { @Test public void run() {} }\n",
            encoding="utf-8",
        )
        probe("unjustified undispatched Docker journey fails", check(root, "js") != 0, True)
        extra.write_text(
            "package com.example;\n// CI_JOURNEY_SUITE_JUSTIFIED: #2860 opt-in signed-upgrade fixture requires a prior signed install\n"
            "public class ExtraDockerTest { @Test public void run() {} }\n",
            encoding="utf-8",
        )
        probe("issue-backed opt-in journey justification passes", check(root, "js") == 0, True)
        extra.write_text(
            "package com.example;\n// CI_JOURNEY_SUITE_JUSTIFIED: later\npublic class ExtraDockerTest { @Test public void run() {} }\n",
            encoding="utf-8",
        )
        probe("marker without issue-backed reason fails", check(root, "js") != 0, True)
        extra.write_text(
            'package com.example;\npublic class ExtraDockerTest {\n'
            '  private static final String DECOY = "CI_JOURNEY_SUITE_JUSTIFIED: #2860 signed install is isolated";\n'
            '  @Test public void run() {}\n}\n',
            encoding="utf-8",
        )
        probe("issue-backed marker embedded in a string is not an exemption", check(root, "js") != 0, True)
        extra.unlink()

        # Removing the exact smoke class selector or altering a result guard's
        # method list must fail before JUnit can report a vacuous green.
        _write_fixture(root, smoke, omit_selector=True)
        probe("missing packaged class selector fails", check(root, "js") != 0, True)
        _write_fixture(root, smoke)
        _write_fixture(root, smoke, omit_checker_method=True)
        probe("missing method in the exact result guard fails", check(root, "js") != 0, True)
        _write_fixture(root, smoke)

        smoke_source.unlink()
        probe("missing required lane class fails instead of scanning zero", check(root, "js") != 0, True)
        for lane in LANES:
            source = root / "android/app/src/androidTest/java" / Path(*lane.class_name.split(".")).with_suffix(".java")
            source.unlink(missing_ok=True)
        probe("empty JS androidTest tree fails closed", check(root, "js") != 0, True)

    # Preserve and exercise the legacy main/stable behavior with a synthetic
    # app2 whole-suite root, then promote the external class by explicit reason.
    with tempfile.TemporaryDirectory(prefix="pocketshell-j1-app2-") as temporary:
        root = Path(temporary)
        (root / "app2").mkdir(parents=True, exist_ok=True)
        (root / "app2/build.gradle.kts").write_text("plugins {}\n", encoding="utf-8")
        suite = root / "scripts/ci-app2-journey-suite.sh"
        suite.parent.mkdir(parents=True, exist_ok=True)
        suite.write_text(
            'JOURNEY_TASK=":app2:connectedDebugAndroidTest"\n'
            'gradle_args() {\n  echo "$JOURNEY_TASK"\n}\n',
            encoding="utf-8",
        )
        inside = root / "app2/src/androidTest/java/com/example/LegacyDockerTest.kt"
        inside.parent.mkdir(parents=True, exist_ok=True)
        inside.write_text("package com.example\nclass LegacyDockerTest {}\n", encoding="utf-8")
        outside = root / "shared/core/src/androidTest/java/com/example/ExternalE2eTest.kt"
        outside.parent.mkdir(parents=True, exist_ok=True)
        outside.write_text("package com.example\nclass ExternalE2eTest {}\n", encoding="utf-8")
        probe("auto mode preserves app2 whole-suite behavior when app2 exists", check(root, "auto") != 0, True)
        outside.write_text(
            "package com.example\n// CI_JOURNEY_SUITE_JUSTIFIED: #2860 opt-in migration needs a signed previous install\n"
            "class ExternalE2eTest {}\n",
            encoding="utf-8",
        )
        probe("legacy app2 external journey passes with source justification", check(root, "auto") == 0, True)
        inside.unlink()
        probe("legacy app2 empty wholesale root fails closed", check(root, "auto") != 0, True)

    print(f"J1 dispatch self-test: {checks - failures}/{checks} checks passed")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--mode", choices=("auto", "js", "legacy"), default="auto")
    parser.add_argument("--machine", action="store_true", help="emit tab-separated findings for check-test-validity.sh")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run_check(args.repo_root.resolve(), args.mode, args.machine)


if __name__ == "__main__":
    raise SystemExit(main())
