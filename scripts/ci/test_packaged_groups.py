"""Partition and fail-closed custody checks for independently executed APK jobs."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PackagedGroupsTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("groups", ROOT / "scripts/ci/packaged_groups.py")
        self.groups = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.groups)

    def test_partition_executes_every_lane_once_and_keeps_upgrade_dependency(self):
        expected = {"smoke", "lifecycle", "usage", "files", "durable", "composer", "hotkeys",
                    "settings", "voice_settings", "account_sync", "account_picker", "gateway",
                    "shared_app", "account_vault", "forward_service", "key_vault", "signed_upgrade"}
        lanes = [lane for group in self.groups.GROUPS.values() for lane in group]
        self.assertEqual(expected, set(lanes))
        self.assertEqual(len(expected), len(lanes))
        vaults = next(group for group in self.groups.GROUPS.values() if "key_vault" in group)
        self.assertEqual(("key_vault", "signed_upgrade"), vaults[-2:])

    def test_aggregate_rejects_missing_stale_duplicate_or_incomplete_group(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for group, lanes in self.groups.GROUPS.items():
                target = root / group / "verdict.json"
                target.parent.mkdir()
                target.write_text(json.dumps({"group": group, "head": "abc", "run": "12", "attempt": "3",
                                              "lanes": list(lanes), "status": "passed"}))
            self.groups.verify(root, "abc", "12", "3")
            target = root / "baseline/verdict.json"
            original = target.read_text()
            for field, bad in (("head", "old"), ("run", "11"), ("attempt", "2"),
                               ("status", "failed"), ("lanes", [])):
                row = json.loads(original)
                row[field] = bad
                target.write_text(json.dumps(row))
                with self.assertRaises(ValueError):
                    self.groups.verify(root, "abc", "12", "3")
            target.unlink()
            with self.assertRaises(ValueError):
                self.groups.verify(root, "abc", "12", "3")

            target.write_text(original)
            (root / "duplicate").mkdir()
            (root / "duplicate/verdict.json").write_text(original)
            with self.assertRaises(ValueError):
                self.groups.verify(root, "abc", "12", "3")

    def test_real_dispatcher_runs_only_its_complete_group_and_propagates_failure(self):
        runner_names = {
            "usage": "usage-ports", "durable": "durable-storage", "composer": "composer-docker",
            "hotkeys": "hotkeys-docker", "voice_settings": "voice-settings", "account_sync": "account-sync",
            "account_picker": "account-picker", "gateway": "gateway-docker", "shared_app": "shared-app",
            "account_vault": "account-vault-docker", "forward_service": "forward-service",
            "key_vault": "key-vault-docker", "signed_upgrade": "key-vault-signed-upgrade", "files": "files-docker",
        }
        stub = """#!/usr/bin/env bash
set -eu
name="$(basename "$0")"
printf '%s\\n' "$name" >> "$RECORD"
case "$name" in
  connected-js-smoke.sh)
    mkdir -p android/app/build/outputs/androidTest-results/connected/debug
    touch android/app/build/outputs/androidTest-results/connected/debug/TEST-stub.xml ;;
  connected-js-composer-docker.sh)
    for phase in production no-speech prepare resume; do
      mkdir -p "android/app/build/outputs/js-composer/js2891-12-3-stub/phase-$phase"
      printf "<testsuite/>" > "android/app/build/outputs/js-composer/js2891-12-3-stub/phase-$phase/TEST-composer.xml"
    done ;;
  connected-js-hotkeys-docker.sh) mkdir -p android/app/build/outputs/js-hotkeys/js2884-12-3-stub ;;
esac
[[ "${FAIL_LANE:-}" != "$name" ]]
"""
        for group, expected in self.groups.GROUPS.items():
            for fail in (False, True):
                with self.subTest(group=group, fail=fail), tempfile.TemporaryDirectory() as directory:
                    root = Path(directory)
                    for relative in ("scripts/ci-js-first-packaged-lanes.sh", "scripts/lib/packaged-group.sh", "scripts/ci/packaged_groups.py"):
                        target = root / relative
                        target.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(ROOT / relative, target)
                    (root / "scripts/android-network-preflight.py").write_text("# No device use in dispatcher contract test.\n")
                    for lane in (item for rows in self.groups.GROUPS.values() for item in rows):
                        target = root / f"scripts/connected-js-{runner_names.get(lane, lane)}.sh"
                        target.write_text(stub)
                        target.chmod(0o755)
                    for name in ("composer-journey", "hotkeys-journey"):
                        target = root / f"scripts/check-js-{name}-results.py"
                        target.write_text("#!/usr/bin/env bash\nexit 0\n")
                        target.chmod(0o755)
                    names = {f"connected-js-{runner_names.get(lane, lane)}.sh" for lane in expected}
                    env = dict(os.environ, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="3",
                               POCKETSHELL_PACKAGED_GROUP=group, RECORD=str(root / "calls"),
                               FAIL_LANE=sorted(names)[0] if fail else "")
                    result = subprocess.run(["bash", "scripts/ci-js-first-packaged-lanes.sh"], cwd=root,
                                            env=env, text=True, capture_output=True, timeout=20)
                    self.assertEqual(1 if fail else 0, result.returncode, result.stdout + result.stderr)
                    calls = (root / "calls").read_text().splitlines()
                    self.assertEqual(names, set(calls))
                    self.assertEqual(len(names), len(calls))

    def test_required_aggregate_covers_all_isolated_jobs(self):
        workflow = (ROOT / ".github/workflows/js-first-rewrite.yml").read_text()
        self.assertIn("group: [baseline, composer, mobile, vaults]", workflow)
        self.assertIn("fail-fast: false", workflow)
        aggregate = workflow.split("  web-and-android:\n", 1)[1].split("  docker-agents-fixture:", 1)[0]
        self.assertIn("needs: [source-and-android, packaged]", aggregate)
        self.assertIn("if: always()", aggregate)
        self.assertIn('test "$SOURCE_RESULT" = success && test "$PACKAGED_RESULT" = success', aggregate)
        self.assertIn("packaged_groups.py verify build/packaged-verdicts", aggregate)

    def test_exact_report_guard_checks_every_assigned_lane_and_no_other_group(self):
        workflow = (ROOT / ".github/workflows/js-first-rewrite.yml").read_text()
        block = workflow.split("      - name: Require exact reports for every packaged JS lane\n", 1)[1]
        block = block.split("\n      - name:", 1)[0].split("        run: |\n", 1)[1]
        script = "\n".join(line[10:] if line.startswith(" " * 10) else line for line in block.splitlines())
        checker_names = {
            "usage": "usage-ports", "durable": "durable-storage", "composer": "composer-journey",
            "voice_settings": "voice-settings", "account_sync": "account-sync", "account_picker": "account-picker",
            "shared_app": "shared-app", "account_vault": "account-vault", "forward_service": "forward-service",
            "key_vault": "key-vault", "signed_upgrade": "signed-upgrade",
        }
        for group, lanes in self.groups.GROUPS.items():
            with self.subTest(group=group), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                for relative in ("scripts/lib/packaged-group.sh", "scripts/ci/packaged_groups.py"):
                    target = root / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT / relative, target)
                for lane in (item for rows in self.groups.GROUPS.values() for item in rows if item != "hotkeys"):
                    target = root / f"scripts/check-js-{checker_names.get(lane, lane)}-results.py"
                    target.write_text("#!/usr/bin/env bash\nprintf '%s\\n' \"$(basename \"$0\")\" >> \"$RECORD\"\n")
                    target.chmod(0o755)
                for phase in ("production", "no-speech", "prepare", "resume"):
                    (root / f"android/app/build/outputs/js-composer/js2891-12-3-fixture/phase-{phase}").mkdir(parents=True)
                env = dict(os.environ, GITHUB_RUN_ID="12", GITHUB_RUN_ATTEMPT="3",
                           POCKETSHELL_PACKAGED_GROUP=group, RECORD=str(root / "checks"))
                result = subprocess.run(["bash", "-e", "-u", "-o", "pipefail"], cwd=root, env=env,
                                        input=script, text=True, capture_output=True, timeout=20)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                expected = [f"check-js-{checker_names.get(lane, lane)}-results.py"
                            for lane in lanes if lane != "hotkeys" for _ in range(4 if lane == "composer" else 1)]
                self.assertCountEqual(expected, (root / "checks").read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
