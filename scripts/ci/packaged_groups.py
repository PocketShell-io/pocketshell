#!/usr/bin/env python3
"""Disjoint APK jobs and same-run, complete-group aggregation. No skipped lanes."""
import argparse
import json
import os
from pathlib import Path

GROUPS = {
    "baseline": ("smoke", "lifecycle", "usage", "files", "durable", "settings", "voice_settings", "account_sync", "account_picker"),
    "composer": ("composer",),
    "mobile": ("hotkeys", "shared_app", "forward_service"),
    "vaults": ("gateway", "account_vault", "key_vault", "signed_upgrade"),
}


def lanes(group):
    if group == "all":
        return tuple(lane for rows in GROUPS.values() for lane in rows)
    if group not in GROUPS:
        raise ValueError(f"unknown packaged group: {group}")
    return GROUPS[group]


def verify(root, head, run, attempt):
    seen = set()
    for path in Path(root).rglob("verdict.json"):
        row = json.loads(path.read_text())
        group = row.get("group")
        if group not in GROUPS or group in seen:
            raise ValueError(f"unknown or duplicate group: {group}")
        expected = {"group": group, "head": head, "run": run, "attempt": attempt,
                    "status": "passed", "lanes": list(GROUPS[group])}
        if row != expected:
            raise ValueError(f"incomplete or stale group verdict: {path}")
        seen.add(group)
    if seen != set(GROUPS):
        raise ValueError(f"missing packaged groups: {sorted(set(GROUPS) - seen)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("list", "write", "verify"))
    parser.add_argument("group_or_root")
    args = parser.parse_args()
    if args.command == "list":
        print(" ".join(lanes(args.group_or_root)))
        return
    head, run, attempt = (os.environ[key] for key in ("GITHUB_SHA", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT"))
    if args.command == "verify":
        verify(Path(args.group_or_root), head, run, attempt)
        print("PASS: every packaged group completed on this exact head/run/attempt")
    else:
        group = args.group_or_root
        row = {"group": group, "head": head, "run": run, "attempt": attempt,
               "status": "passed", "lanes": list(lanes(group))}
        target = Path("build/packaged-group/verdict.json")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(row) + "\n")


if __name__ == "__main__":
    main()
