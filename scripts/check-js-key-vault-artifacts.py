#!/usr/bin/env python3
"""Validate same-run Diagnostics, generated-key and connect-timing evidence."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Any


class EvidenceFailure(ValueError):
    pass


def _read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise EvidenceFailure(f"could not read valid JSON evidence from {path.name}: {error}") from error
    if not isinstance(value, dict):
        raise EvidenceFailure(f"{path.name} must contain a JSON object")
    return value


def validate(artifact_dir: Path, expected_run_id: str | None = None) -> None:
    diagnostic_path = artifact_dir / "diagnostics-export-preview.json"
    timing_path = artifact_dir / "connect-to-session-timing.json"
    fingerprint_path = artifact_dir / "generated-key-fingerprint.txt"
    auth_path = artifact_dir / "app-authenticated-fingerprint.txt"
    host_path = artifact_dir / "host-oracle.txt"
    auth_start_path = artifact_dir / "docker-log-started-at.txt"
    for path in (diagnostic_path, timing_path, fingerprint_path, auth_path, host_path, auth_start_path):
        if not path.is_file() or path.stat().st_size == 0:
            raise EvidenceFailure(f"required same-run evidence is missing or empty: {path.name}")

    diagnostic = _read_json(diagnostic_path)
    if diagnostic.get("schema") != 1 or not isinstance(diagnostic.get("events"), list):
        raise EvidenceFailure("Diagnostics export does not have the expected schema-1 event list")
    if not any(isinstance(event, dict) and event.get("kind") == "app-started" for event in diagnostic["events"]):
        raise EvidenceFailure("actual Diagnostics preview is missing the app-started event")
    if not any(isinstance(event, dict) and event.get("kind") == "build-verified" for event in diagnostic["events"]):
        raise EvidenceFailure("actual Diagnostics preview is missing the build-verified event")

    timing = _read_json(timing_path)
    if timing.get("schema") != 1 or not isinstance(timing.get("runId"), str):
        raise EvidenceFailure("connect-to-session timing artifact has an invalid schema or run ID")
    if expected_run_id is not None and timing["runId"] != expected_run_id:
        raise EvidenceFailure("connect-to-session timing run ID does not match the current artifact bundle")
    measurements = timing.get("measurements")
    if not isinstance(measurements, list) or len(measurements) != 2:
        raise EvidenceFailure("timing artifact must contain imported-key and generated-key measurements")
    by_credential = {
        item.get("credential"): item
        for item in measurements
        if isinstance(item, dict) and isinstance(item.get("credential"), str)
    }
    if set(by_credential) != {"imported-key", "generated-key"}:
        raise EvidenceFailure("timing artifact is missing a credential-specific connect-to-session measurement")
    tags: set[str] = set()
    for item in by_credential.values():
        if item.get("start") != "SSH connect button activated" or item.get("end") != "session attached with live terminal":
            raise EvidenceFailure("timing artifact does not measure connect through live session attachment")
        if not isinstance(item.get("elapsedMillis"), int) or item["elapsedMillis"] <= 0:
            raise EvidenceFailure("connect-to-session duration must be a positive integer")
        if not isinstance(item.get("sessionTag"), str) or not item["sessionTag"]:
            raise EvidenceFailure("timing artifact is missing its session tag")
        tags.add(item["sessionTag"])
    if len(tags) != 2:
        raise EvidenceFailure("imported and generated credentials must have distinct session tags")

    generated_fingerprint = fingerprint_path.read_text(encoding="ascii").strip()
    if not generated_fingerprint.startswith("SHA256:") or len(generated_fingerprint) != 50:
        raise EvidenceFailure("generated-key fingerprint evidence is malformed")
    auth_text = auth_path.read_text(encoding="utf-8")
    if "imported_key_docker_authentication=PASS" not in auth_text or "generated_key_docker_authentication=PASS" not in auth_text:
        raise EvidenceFailure("Docker authentication oracle is missing one of the two key credentials")
    if generated_fingerprint not in auth_text:
        raise EvidenceFailure("Docker authentication oracle does not contain the selected generated-key fingerprint")
    try:
        auth_start = datetime.fromisoformat(auth_start_path.read_text(encoding="ascii").strip().replace("Z", "+00:00"))
        for credential in ("imported-key", "generated-key"):
            prefix = f"{credential}_accepted_line="
            accepted_lines = [line[len(prefix):] for line in auth_text.splitlines() if line.startswith(prefix)]
            if len(accepted_lines) != 1:
                raise EvidenceFailure(f"Docker authentication oracle omitted the timestamped {credential} log line")
            accepted_at = datetime.fromisoformat(accepted_lines[0].split()[0].replace("Z", "+00:00"))
            if accepted_at < auth_start:
                raise EvidenceFailure(f"Docker authentication oracle reused historical {credential} authentication")
    except (ValueError, TypeError, IndexError) as error:
        raise EvidenceFailure(f"Docker authentication time boundary is invalid: {error}") from error
    host_text = host_path.read_text(encoding="utf-8")
    required_tags = {"keyvault-" + timing["runId"], "keyvault-generated-" + timing["runId"]}
    if not required_tags.issubset(tags) or any(f"session_tag={tag}" not in host_text for tag in required_tags):
        raise EvidenceFailure("independent Docker session lookup is missing an imported or generated-key session")
    if "independent_session_list_for_both_credentials=PASS" not in host_text:
        raise EvidenceFailure("independent Docker session lookup did not pass for both credentials")

    text = diagnostic_path.read_text(encoding="utf-8")
    for marker in (
        "-----BEGIN OPENSSH PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----",
        "pocketshell-vault-test-passphrase",
        "10.0.2.2",
        "testuser",
    ):
        if marker in text:
            raise EvidenceFailure(f"actual Diagnostics preview contains forbidden data: {marker}")


def _write_valid(root: Path, run_id: str = "keyvault-test") -> None:
    root.mkdir(parents=True, exist_ok=True)
    (root / "diagnostics-export-preview.json").write_text(json.dumps({
        "schema": 1,
        "events": [{"kind": "app-started"}, {"kind": "build-verified"}],
    }), encoding="utf-8")
    (root / "connect-to-session-timing.json").write_text(json.dumps({
        "schema": 1,
        "runId": run_id,
        "measurements": [
            {"credential": "imported-key", "start": "SSH connect button activated", "end": "session attached with live terminal", "sessionTag": f"keyvault-{run_id}", "elapsedMillis": 1234},
            {"credential": "generated-key", "start": "SSH connect button activated", "end": "session attached with live terminal", "sessionTag": f"keyvault-generated-{run_id}", "elapsedMillis": 2345},
        ],
    }), encoding="utf-8")
    (root / "generated-key-fingerprint.txt").write_text("SHA256:" + "A" * 43 + "\n", encoding="ascii")
    (root / "docker-log-started-at.txt").write_text("2026-09-30T12:00:00.000000000Z\n", encoding="ascii")
    (root / "app-authenticated-fingerprint.txt").write_text(
        "imported_key_docker_authentication=PASS\ngenerated_key_docker_authentication=PASS\nSHA256:" + "A" * 43 + "\n"
        "imported-key_accepted_line=2026-09-30T12:00:01.000000000Z Accepted publickey\n"
        "generated-key_accepted_line=2026-09-30T12:00:02.000000000Z Accepted publickey\n",
        encoding="utf-8",
    )
    (root / "host-oracle.txt").write_text(
        f"session_tag=keyvault-{run_id}\nsession_tag=keyvault-generated-{run_id}\nindependent_session_list_for_both_credentials=PASS\n",
        encoding="utf-8",
    )


def self_test() -> int:
    failures = 0
    cases = (
        ("complete same-run evidence passes", None, True),
        ("missing Diagnostics preview fails", "diagnostics-export-preview.json", False),
        ("missing generated-key authentication fails", "generated-auth", False),
        ("missing generated timing fails", "generated-timing", False),
        ("historical imported-key authentication fails", "historical-imported-auth", False),
        ("historical generated-key authentication fails", "historical-generated-auth", False),
        ("secret in the real preview fails", "secret", False),
    )
    with tempfile.TemporaryDirectory(prefix="pocketshell-key-vault-artifacts-") as temporary:
        for index, (name, mutation, expected) in enumerate(cases):
            root = Path(temporary) / str(index)
            _write_valid(root)
            if mutation == "diagnostics-export-preview.json":
                (root / mutation).unlink()
            elif mutation == "generated-auth":
                (root / "app-authenticated-fingerprint.txt").write_text("imported_key_docker_authentication=PASS\n", encoding="utf-8")
            elif mutation == "generated-timing":
                payload = _read_json(root / "connect-to-session-timing.json")
                payload["measurements"].pop()
                (root / "connect-to-session-timing.json").write_text(json.dumps(payload), encoding="utf-8")
            elif mutation in {"historical-imported-auth", "historical-generated-auth"}:
                path = root / "app-authenticated-fingerprint.txt"
                credential = "imported-key" if mutation == "historical-imported-auth" else "generated-key"
                path.write_text(path.read_text(encoding="utf-8").replace(
                    f"{credential}_accepted_line=2026-09-30T12:", f"{credential}_accepted_line=2026-09-30T11:",
                ), encoding="utf-8")
            elif mutation == "secret":
                (root / "diagnostics-export-preview.json").write_text(
                    '{"schema":1,"events":[],"secret":"pocketshell-vault-test-passphrase"}', encoding="utf-8")
            try:
                validate(root, "keyvault-test")
                actual = True
            except EvidenceFailure:
                actual = False
            if actual != expected:
                failures += 1
                print(f"FAIL: {name}", file=sys.stderr)
            else:
                print(f"ok: {name}")
    print(f"Key-vault evidence guard self-test: {len(cases) - failures}/{len(cases)} checks passed")
    return int(failures != 0)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact-dir", type=Path, default=Path("android/app/build/outputs/js-key-vault"))
    parser.add_argument("--run-id")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    try:
        validate(args.artifact_dir, args.run_id)
    except EvidenceFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: same-run Diagnostics, generated-key auth and timing evidence in {args.artifact_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
