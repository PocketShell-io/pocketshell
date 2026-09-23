#!/usr/bin/env python3
"""Check a built APK's package, versionCode, and versionName."""

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


class GateFailure(ValueError):
    """The built APK does not have the expected Android package identity."""


def parse_metadata(output: str) -> tuple[str, int, str]:
    package_lines = [line for line in output.splitlines() if line.startswith("package:")]
    if len(package_lines) != 1:
        raise GateFailure(
            f"aapt reported {len(package_lines)} package metadata lines; expected exactly one"
        )

    matches = PACKAGE_FIELD.findall(package_lines[0][len("package:") :])
    fields = dict(matches)
    if len(matches) != 3 or set(fields) != {"name", "versionCode", "versionName"}:
        raise GateFailure("APK metadata must contain name, versionCode, and versionName exactly once")
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
        raise GateFailure(f"APK versionCode is {code}; expected {expected_version_code}")
    if name != expected_version_name:
        raise GateFailure(f"APK versionName is {name!r}; expected {expected_version_name!r}")
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


def check_apk(
    apk: Path, variant: str, expected_code: int, expected_name: str, aapt: Path
) -> tuple[str, int, str]:
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
            "package: name='com.pocketshell.app' versionCode='102' versionName='0.5.6'\n",
            "debug",
            102,
            "0.5.6",
            True,
        ),
        (
            "release package and derived version pass",
            "package: name='com.pocketshell.app.release' versionCode='102' versionName='0.5.6'\n",
            "release",
            102,
            "0.5.6",
            True,
        ),
        (
            "wrong package is rejected",
            "package: name='com.pocketshell.app.other' versionCode='102' versionName='0.5.6'\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "wrong versionCode is rejected",
            "package: name='com.pocketshell.app' versionCode='101' versionName='0.5.6'\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "wrong versionName is rejected",
            "package: name='com.pocketshell.app' versionCode='102' versionName='0.5.5'\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "zero versionCode is rejected",
            "package: name='com.pocketshell.app' versionCode='0' versionName='0.5.6'\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "missing version field is rejected",
            "package: name='com.pocketshell.app' versionCode='102'\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "duplicate package metadata is rejected",
            "package: name='com.pocketshell.app' versionCode='102' versionName='0.5.6'\n"
            "package: name='com.pocketshell.app' versionCode='102' versionName='0.5.6'\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "empty versionName is rejected",
            "package: name='com.pocketshell.app' versionCode='102' versionName=''\n",
            "debug",
            102,
            "0.5.6",
            False,
        ),
        (
            "duplicate metadata field is rejected",
            "package: name='com.pocketshell.app' name='com.pocketshell.app' versionCode='102' versionName='0.5.6'\n",
            "debug",
            102,
            "0.5.6",
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
    if failures:
        print(f"FAIL: {failures} of {len(cases)} APK metadata self-tests failed", file=sys.stderr)
        return 1
    print(f"SELF-TEST OK: APK package/version identity checks passed ({len(cases)}/{len(cases)})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk")
    parser.add_argument("--variant", choices=PACKAGE_BY_VARIANT)
    parser.add_argument("--expected-version-code", type=int)
    parser.add_argument("--expected-version-name")
    parser.add_argument("--aapt")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not all((args.apk, args.variant, args.expected_version_code, args.expected_version_name)):
        parser.error("--apk, --variant, --expected-version-code, and --expected-version-name are required")
    if args.expected_version_code <= 0:
        parser.error("--expected-version-code must be positive")
    try:
        metadata = check_apk(
            Path(args.apk), args.variant, args.expected_version_code, args.expected_version_name,
            resolve_aapt(args.aapt),
        )
    except GateFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        f"PASS: {args.variant} APK identity and derived version: "
        f"{metadata[0]} versionCode={metadata[1]} versionName={metadata[2]}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
