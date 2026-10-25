#!/usr/bin/env python3
"""Regression layout mirrors run 40190277357's canonical packaged reports.

Class names are synthetic manifest entries: the real run's support classes do
not implement the 24 required journeys. Never use this fixture to redefine them.
"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("journey_guard", ROOT / "scripts/check-js-journey-results.py")
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
LAYOUT = json.loads((Path(__file__).parent / "fixtures/js-journey-results/packaged-layout.json").read_text())


class PackagedResultsTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.root = Path(self.scratch.name)
        self.required = guard.load_manifest()
        for index, name in enumerate(self.required):
            guard._write_report(self.root / LAYOUT[index % len(LAYOUT)], [(name, "journey", "passed")])
        # Shared Gradle output contains only the last lane. Composer also keeps
        # a convenience copy at its run root: neither is an authoritative source.
        guard._write_report(self.root / "androidTest-results/connected/debug", [("InstalledDataMigrationJourneyTest", "lastCycle", "passed")])
        guard._write_report(self.root / LAYOUT[6].rsplit("/", 1)[0], [(self.required[6], "journey", "passed")])

    def qualify(self):
        if not hasattr(guard, "qualify_packaged_results"):
            # Exercise the old production wiring on base for red→green proof.
            return guard.qualify_results(self.root / "androidTest-results/connected/debug")
        return guard.qualify_packaged_results(self.root, "40190277357", "1")

    def test_all_lanes_are_aggregated_without_copy_duplicates(self):
        result = self.qualify()
        self.assertEqual(result["result"], "PASS", result)
        self.assertEqual(len(result["executedJourneyClasses"]), 24)
        self.assertEqual(result["executedTests"], 24)
        self.assertEqual(len(result["reportFiles"]), 24)
        self.assertEqual(guard.qualify_results(self.root / "androidTest-results/connected/debug")["result"], "BLOCK")

    def test_missing_required_class_blocks(self):
        (self.root / LAYOUT[0] / f"TEST-00-{self.required[0]}.xml").unlink()
        result = self.qualify()
        self.assertEqual(result["result"], "BLOCK")
        self.assertIn(self.required[0], result["missingJourneyClasses"])

    def test_failed_required_class_blocks(self):
        guard._write_report(self.root / LAYOUT[0], [(self.required[0], "journey", "failed")])
        self.assertEqual(self.qualify()["result"], "BLOCK")

    def test_support_classes_are_observed_and_failures_cannot_hide(self):
        directory = self.root / LAYOUT[0]
        guard._write_report(directory, [("SupportTest", "probe", "passed")])
        result = self.qualify()
        self.assertEqual(result["result"], "PASS", result)
        self.assertIn("SupportTest", result["unexpectedClasses"])
        guard._write_report(directory, [("SupportTest", "probe", "failed")])
        result = self.qualify()
        self.assertEqual(result["result"], "BLOCK")
        self.assertTrue(result["failedTests"])

    def test_duplicate_required_case_blocks(self):
        guard._write_report(self.root / LAYOUT[1], [(self.required[0], "journey", "passed")])
        self.assertIn("duplicate instrumentation", " ".join(self.qualify()["blockers"]))

    def test_missing_lane_blocks_even_with_all_required_classes_elsewhere(self):
        directory = self.root / LAYOUT[12]
        for path in directory.glob("TEST-*.xml"):
            path.unlink()
        directory.rmdir()
        guard._write_report(self.root / LAYOUT[0], [(self.required[12], "journey", "passed")])
        result = self.qualify()
        self.assertEqual(len(result["executedJourneyClasses"]), 24)
        self.assertEqual(result["result"], "BLOCK")
        self.assertIn("expected exactly one packaged lane directory", " ".join(result["blockers"]))

    def test_other_run_cannot_supply_a_missing_class(self):
        (self.root / LAYOUT[1] / f"TEST-00-{self.required[1]}.xml").unlink()
        stale = LAYOUT[1].replace("40190277357-1", "40076897402-1")
        guard._write_report(self.root / stale, [(self.required[1], "journey", "passed")])
        result = self.qualify()
        self.assertEqual(result["result"], "BLOCK")
        self.assertIn(self.required[1], result["missingJourneyClasses"])

    def test_ambiguous_composer_run_blocks(self):
        alternate = LAYOUT[6].replace("1792929863342510080", "9999999999999999999")
        guard._write_report(self.root / alternate, [("SupportTest", "probe", "passed")])
        self.assertEqual(self.qualify()["result"], "BLOCK")

    def test_repeated_support_method_in_distinct_phases_is_valid(self):
        for directory in LAYOUT[6:9]:
            guard._write_report(self.root / directory, [("SupportTest", "probe", "passed")])
        self.assertEqual(self.qualify()["result"], "PASS")

    def test_duplicate_support_case_within_phase_blocks(self):
        directory = self.root / LAYOUT[6]
        guard._write_report(directory, [("SupportTest", "probe", "passed")])
        (directory / "TEST-extra.xml").write_bytes((directory / "TEST-00-SupportTest.xml").read_bytes())
        result = self.qualify()
        self.assertEqual(result["result"], "BLOCK")
        self.assertIn("duplicate instrumentation", " ".join(result["blockers"]))

    def test_skipped_support_or_malformed_xml_blocks(self):
        directory = self.root / LAYOUT[0]
        guard._write_report(directory, [("SupportTest", "probe", "skipped")])
        self.assertEqual(self.qualify()["result"], "BLOCK")
        (directory / "TEST-00-SupportTest.xml").write_text("<invalid")
        self.assertEqual(self.qualify()["result"], "BLOCK")

    def test_workflow_qualifies_canonical_same_run_outputs(self):
        workflow = (ROOT / ".github/workflows/js-release-validation.yml").read_text()
        qualification = workflow.split("- name: Run fail-closed feature journey qualification", 1)[1].split("- name:", 1)[0]
        self.assertIn("--packaged-results-root android/app/build/outputs", qualification)
        self.assertIn('--run-id "${GITHUB_RUN_ID}" --run-attempt "${GITHUB_RUN_ATTEMPT}"', qualification)
        self.assertNotIn("--results-dir", qualification)

    def test_empty_results_block(self):
        with tempfile.TemporaryDirectory() as empty:
            result = guard.qualify_packaged_results(Path(empty), "40190277357", "1")
        self.assertEqual(result["result"], "BLOCK")
        self.assertEqual(result["executedJourneyClasses"], [])


if __name__ == "__main__":
    unittest.main()
