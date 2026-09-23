#!/usr/bin/python3 -I
"""Check the package and version embedded in a built JS-first Android APK.

This complements check-apk-signing.sh: signing proves the artifact's signer,
while this gate proves that the packaged Android identity and version match
the checkout's single version derivation.

Usage:
  scripts/check-js-apk-metadata.py --apk <path> --variant debug \\
      --expected-version-code 95 --expected-version-name 0.6.0-3-gabc1234
  scripts/check-js-apk-metadata.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
PACKAGE_BY_VARIANT = {
    "debug": "com.pocketshell.app",
    "release": "com.pocketshell.app.release",
}
PACKAGE_FIELD = re.compile(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'")
SELF_TESTS = 10


class GateFailure(ValueError):
    """The built APK does not have the expected package metadata."""


def parse_metadata(output: str) -> tuple[str, int, str]:
    package_lines = [line for line in output.splitlines() if line.startswith("package:")]
    if len(package_lines) != 1:
        raise GateFailure(f"aapt reported {len(package_lines)} package metadata lines; expected exactly one")

    matches = PACKAGE_FIELD.findall(package_lines[0][len("package:") :])
    fields = dict(matches)
    if len(matches) != 3 or set(fields) != {"name", "versionCode", "versionName"}:
        raise GateFailure("aapt package metadata must include name, versionCode, and versionName exactly once")
    if not fields["name"]:
        raise GateFailure("APK package name is empty")
    if not re.fullmatch(r"[1-9][0-9]*", fields["versionCode"]):
        raise GateFailure(f"APK versionCode must be a positive integer, got {fields['versionCode']!r}")
    if not fields["versionName"]:
        raise GateFailure("APK versionName is empty")
    return fields["name"], int(fields["versionCode"]), fields["versionName"]


def validate_metadata(
    output: str, variant: str, expected_version_code: int, expected_version_name: str
) -> tuple[str, int, str]:
    package, code, name = parse_metadata(output)
    expected_package = PACKAGE_BY_VARIANT[variant]
    if package != expected_package:
        raise GateFailure(f"{variant} APK package is {package!r}; expected {expected_package!r}")
    if code != expected_version_code:
        raise GateFailure(f"APK versionCode is {code}; checkout derives {expected_version_code}")
    if name != expected_version_name:
        raise GateFailure(f"APK versionName is {name!r}; checkout derives {expected_version_name!r}")
    return package, code, name


def resolve_aapt(explicit: str | None) -> Path:
    if explicit:
        candidate = Path(explicit)
        if not candidate.is_file() or not os.access(candidate, os.X_OK):
            raise GateFailure(f"aapt is not an executable file: {candidate}")
        return candidate

    sdk = next(
        (os.environ[key] for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME", "ANDROID_SDK") if os.environ.get(key)),
        None,
    )
    if not sdk:
        local_properties = ROOT / "local.properties"
        if local_properties.is_file():
            for line in local_properties.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    sdk = line.partition("=")[2].strip()
                    break
    if not sdk:
        sdk = "/home/alexey/Android/Sdk"

    build_tools = Path(sdk) / "build-tools"
    if not build_tools.is_dir():
        raise GateFailure(f"Android build-tools directory not found: {build_tools}")

    def version_key(path: Path) -> tuple[int, ...]:
        match = re.fullmatch(r"(\d+(?:\.\d+)*)", path.name)
        return tuple(int(part) for part in match.group(1).split(".")) if match else ()

    candidates = sorted((entry / "aapt" for entry in build_tools.iterdir()), key=version_key, reverse=True)
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate
    raise GateFailure(f"no executable aapt found under {build_tools}")


def check_apk(apk: Path, variant: str, expected_code: int, expected_name: str, aapt: Path) -> tuple[str, int, str]:
    if not apk.is_file():
        raise GateFailure(f"APK does not exist: {apk}")
    result = subprocess.run(
        [str(aapt), "dump", "badging", str(apk)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or f"exit status {result.returncode}"
        raise GateFailure(f"aapt could not inspect {apk}: {detail}")
    return validate_metadata(result.stdout, variant, expected_code, expected_name)


def self_test() -> int:
    cases = [
        (
            "debug package and derived version pass",
            "package: name='com.pocketshell.app' versionCode='95' versionName='0.6.0-3-gabc1234'\n",
            "debug",
            95,
            "0.6.0-3-gabc1234",
            True,
        ),
        (
            "release package and derived version pass",
            "package: name='com.pocketshell.app.release' versionCode='95' versionName='0.6.0'\n",
            "release",
            95,
            "0.6.0",
            True,
        ),
        (
            "wrong package is rejected",
            "package: name='com.pocketshell.app.other' versionCode='95' versionName='0.6.0'\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
        (
            "wrong versionCode is rejected",
            "package: name='com.pocketshell.app' versionCode='94' versionName='0.6.0'\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
        (
            "wrong versionName is rejected",
            "package: name='com.pocketshell.app' versionCode='95' versionName='0.5.9'\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
        (
            "zero versionCode is rejected",
            "package: name='com.pocketshell.app' versionCode='0' versionName='0.6.0'\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
        ("missing version field is rejected", "package: name='com.pocketshell.app' versionCode='95'\n", "debug", 95, "0.6.0", False),
        (
            "duplicate package metadata is rejected",
            "package: name='com.pocketshell.app' versionCode='95' versionName='0.6.0'\n"
            "package: name='com.pocketshell.app' versionCode='95' versionName='0.6.0'\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
        (
            "empty versionName is rejected",
            "package: name='com.pocketshell.app' versionCode='95' versionName=''\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
        (
            "duplicate metadata field is rejected",
            "package: name='com.pocketshell.app' name='com.pocketshell.app' versionCode='95' versionName='0.6.0'\n",
            "debug",
            95,
            "0.6.0",
            False,
        ),
    ]
    failures = 0
    for label, output, variant, code, name, expected in cases:
        try:
            validate_metadata(output, variant, code, name)
            observed = True
        except GateFailure:
            observed = False
        if observed != expected:
            failures += 1
            print(f"FAIL: self-test: {label}", file=sys.stderr)
        else:
            print(f"  ok  {label}")
    if len(cases) != SELF_TESTS:
        failures += 1
        print(f"FAIL: self-test defined {len(cases)} cases, expected {SELF_TESTS}", file=sys.stderr)
    if failures:
        print(f"FAIL: {failures} of {SELF_TESTS} APK metadata self-tests failed", file=sys.stderr)
        return 1
    print(f"SELF-TEST OK: APK package/version identity checks passed ({SELF_TESTS}/{SELF_TESTS})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--variant", choices=sorted(PACKAGE_BY_VARIANT))
    parser.add_argument("--expected-version-code", type=int)
    parser.add_argument("--expected-version-name")
    parser.add_argument("--aapt", help="aapt executable; defaults to the newest SDK build-tools copy")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.apk is None or args.variant is None or args.expected_version_code is None or args.expected_version_name is None:
        parser.error("--apk, --variant, --expected-version-code, and --expected-version-name are required")
    if args.expected_version_code <= 0 or not args.expected_version_name:
        parser.error("expected version code must be positive and version name must be non-empty")
    try:
        aapt = resolve_aapt(args.aapt)
        apk = args.apk if args.apk.is_absolute() else ROOT / args.apk
        package, code, name = check_apk(
            apk, args.variant, args.expected_version_code, args.expected_version_name, aapt
        )
    except GateFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: {args.variant} APK identity and derived version: {package} versionCode={code} versionName={name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
