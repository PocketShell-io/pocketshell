#!/usr/bin/python3 -I
"""Prove the browser dev-mode shims (#3022) never ship in a production build.

`pnpm dev:mock` / `pnpm dev:live` inject a fake Android bridge, a mock host
and a live-SSH bridge client into the page (src/dev/browser/). They exist
for the Vite dev server only. This check scans the built web assets and the
web assets packaged inside the APK for any trace of that code and fails if
it finds one, or if it finds nothing to scan (a vacuous pass).

Usage:
  scripts/check-no-dev-shims.py --dist dist --apk android/app/build/outputs/apk/debug/app-debug.apk
  scripts/check-no-dev-shims.py --self-test
"""
from __future__ import annotations

import argparse
import sys
import tempfile
import zipfile
from pathlib import Path

# Strings that only the dev-mode code contains. Keep in sync with
# src/dev/browser/nativeBridge.ts DEV_BROWSER_SHIM_MARKER and friends.
MARKERS = (
    "pocketshell-dev-browser-shim",
    "pocketshell-dev-config",
    "__pocketshell-dev-bridge",
    "__pocketshellDev",
    "src/dev/browser",
    "browser dev mock",
    "pocketshell-dev-ssh-bridge",
)
SCANNED_SUFFIXES = (".js", ".mjs", ".html", ".css", ".json", ".map")
APK_WEB_ROOT = "assets/public/"


class ShimLeak(ValueError):
    """Dev-mode code reached a production artifact."""


def scan_bytes(name: str, data: bytes) -> list[str]:
    text = data.decode("utf-8", errors="replace")
    return [f"{name}: contains {marker!r}" for marker in MARKERS if marker in text]


def scan_dist(dist: Path) -> tuple[int, int, list[str]]:
    if not dist.is_dir():
        raise ShimLeak(f"{dist} is not a directory; build the web assets first")
    files = scripts = 0
    leaks: list[str] = []
    for path in sorted(dist.rglob("*")):
        if not path.is_file() or not path.name.endswith(SCANNED_SUFFIXES):
            continue
        files += 1
        scripts += path.suffix in (".js", ".mjs")
        leaks += scan_bytes(str(path), path.read_bytes())
    return files, scripts, leaks


def scan_apk(apk: Path) -> tuple[int, int, list[str]]:
    if not apk.is_file():
        raise ShimLeak(f"{apk} does not exist; build the APK first")
    files = scripts = 0
    leaks: list[str] = []
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not entry.startswith(APK_WEB_ROOT) or not entry.endswith(SCANNED_SUFFIXES):
                continue
            files += 1
            scripts += entry.endswith((".js", ".mjs"))
            leaks += scan_bytes(f"{apk}!{entry}", archive.read(entry))
    return files, scripts, leaks


def check(dist: Path | None, apk: Path | None) -> list[str]:
    if dist is None and apk is None:
        raise ShimLeak("pass --dist and/or --apk")
    report: list[str] = []
    for label, target, scanner in (("dist", dist, scan_dist), ("apk", apk, scan_apk)):
        if target is None:
            continue
        files, scripts, leaks = scanner(target)
        if scripts == 0:
            raise ShimLeak(f"{label} {target}: no JavaScript assets were scanned (vacuous pass refused)")
        if leaks:
            raise ShimLeak("browser dev-mode code is in a production artifact:\n  " + "\n  ".join(leaks))
        report.append(f"{label} {target}: scanned {files} web assets ({scripts} scripts), no dev-mode shim code")
    return report


def self_test() -> None:
    passed = 0
    with tempfile.TemporaryDirectory() as raw:
        root = Path(raw)
        clean = root / "clean"
        (clean / "assets").mkdir(parents=True)
        (clean / "index.html").write_text('<script type="module" src="./assets/index.js"></script>')
        (clean / "assets/index.js").write_text("registerPlugin('SshCapability');")
        assert check(clean, None)[0].endswith("no dev-mode shim code")
        passed += 1

        for marker in MARKERS:
            leaky = root / f"leak-{passed}"
            (leaky / "assets").mkdir(parents=True)
            (leaky / "assets/index.js").write_text(f"const x = '{marker}';")
            try:
                check(leaky, None)
            except ShimLeak as error:
                assert marker in str(error)
            else:
                raise AssertionError(f"marker {marker!r} was not detected")
            passed += 1

        empty = root / "empty"
        empty.mkdir()
        (empty / "index.html").write_text("<html></html>")
        try:
            check(empty, None)
        except ShimLeak as error:
            assert "vacuous" in str(error)
        else:
            raise AssertionError("an asset tree without scripts must fail")
        passed += 1

        apk = root / "app.apk"
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("assets/public/assets/index.js", "registerPlugin('SshCapability');")
            archive.writestr("classes.dex", "pocketshell-dev-browser-shim outside the web root is not web code")
        assert "no dev-mode shim code" in check(None, apk)[0]
        passed += 1

        leaky_apk = root / "leaky.apk"
        with zipfile.ZipFile(leaky_apk, "w") as archive:
            archive.writestr("assets/public/assets/install.js", "window.__pocketshellDev = {}")
        try:
            check(None, leaky_apk)
        except ShimLeak as error:
            assert "__pocketshellDev" in str(error)
        else:
            raise AssertionError("a leaky APK must fail")
        passed += 1
    print(f"check-no-dev-shims self-test: {passed} cases passed")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dist", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    try:
        for line in check(args.dist, args.apk):
            print(line)
    except ShimLeak as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
