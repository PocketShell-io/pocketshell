"""Accounting boundary tests using saved GitHub REST shapes (no network)."""

import copy
import unittest

from actions_minutes import markdown, summarize


class ActionsMinutesTests(unittest.TestCase):
    def setUp(self):
        self.repos = [{"name": "active", "private": True},
                      {"name": "idle", "private": False, "archived": True}]
        self.runs = [{"repo": "active", "id": 100, "name": "CI", "event": "push",
                      "path": ".github/workflows/ci.yml", "head_branch": "main"},
                     {"repo": "active", "id": 101, "name": "Empty", "event": "schedule"}]
        self.groups = [{"repo": "active", "run_id": run_id, "jobs": []} for run_id in (100, 101)]

    def job(self, job_id, seconds, labels=None):
        # Different clock minutes make rounding each duration (rather than
        # rounding absolute timestamps or their aggregate) observable.
        from datetime import datetime, timedelta, timezone
        start = datetime(2026, 10, 1, 12, 0, 59, tzinfo=timezone.utc)
        return {"id": job_id, "name": f"job {job_id}", "labels": labels or ["ubuntu-24.04"],
                "started_at": start.isoformat(), "completed_at": (start + timedelta(seconds=seconds)).isoformat(),
                "conclusion": "success"}

    def summary(self):
        return summarize(self.repos, self.runs, self.groups)

    def test_individual_job_rounding_and_os_multipliers(self):
        self.groups[0]["jobs"] = [self.job(1, 1), self.job(2, 59), self.job(3, 61, ["windows-2025"]),
                                    self.job(4, 60, ["macos-15"]), self.job(5, 60, ["ubuntu-24.04-arm"])]
        summary = self.summary()
        self.assertEqual(summary["total"]["raw_seconds"], 241)
        self.assertEqual(summary["total"]["rounded_minutes"], 6)
        self.assertEqual(summary["total"]["weighted_minutes"], 17)
        self.assertEqual(summary["total"]["os"]["linux"]["weighted_minutes"], 3)
        self.assertIsNone(summary["actual_billed_minutes"])
        self.assertIn("not billed usage", summary["billing_note"])

    def test_all_rerun_attempts_count_but_duplicate_job_ids_do_not(self):
        first = self.job(1, 61)
        rerun = self.job(2, 61)
        self.groups[0]["jobs"] = [first, rerun]
        self.groups.append({"repo": "active", "run_id": 100, "jobs": [copy.deepcopy(first)]})
        summary = self.summary()
        self.assertEqual(summary["unique_jobs"], 2)
        self.assertEqual(summary["total"]["weighted_minutes"], 4)
        self.assertEqual(summary["runs"], 2)

    def test_conflicting_duplicate_fails_instead_of_guessing(self):
        self.groups[0]["jobs"] = [self.job(1, 61), self.job(1, 62)]
        with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
            self.summary()

    def test_skipped_unstarted_and_zero_duration_never_infer_runner(self):
        skipped = self.job(1, 60)
        skipped.update(labels=[], conclusion="skipped")
        unstarted = {"id": 2, "name": "queued", "labels": [], "started_at": None, "completed_at": None}
        zero = self.job(3, 0)
        zero["labels"] = []
        self.groups[0]["jobs"] = [skipped, unstarted, zero]
        summary = self.summary()
        self.assertEqual(summary["total"]["weighted_minutes"], 0)
        self.assertEqual(summary["total"]["excluded_jobs"], 3)
        self.assertEqual(summary["excluded_reasons"]["skipped"], 1)

    def test_missing_or_unknown_os_is_an_error_for_a_timed_job(self):
        for labels in ([], ["custom-pool"], ["ubuntu-latest", "windows-latest"], ["self-hosted", "linux"]):
            with self.subTest(labels=labels):
                job = self.job(1, 1)
                job["labels"] = labels
                self.groups[0]["jobs"] = [job]
                with self.assertRaisesRegex(ValueError, "unsupported or ambiguous"):
                    self.summary()

    def test_cancelled_queued_job_does_not_count_waiting_time(self):
        queued = self.job(1, 3600)
        queued.update(conclusion="cancelled", runner_id=0, runner_name="", steps=[])
        ran = self.job(2, 60)
        ran.update(runner_id=0, runner_name="", steps=[{"name": "Run tests"}])
        self.groups[0]["jobs"] = [queued, ran]
        summary = self.summary()
        self.assertEqual(summary["total"]["weighted_minutes"], 1)
        self.assertEqual(summary["total"]["excluded_jobs"], 1)

    def test_live_skipped_job_with_backward_timestamps_is_excluded(self):
        # Jobs API run 36632322818, job 109624359245: no runner executed.
        self.groups[0]["jobs"] = [{"id": 109624359245,
            "name": "File/update red release-validation tracking issue",
            "conclusion": "skipped", "status": "completed",
            "started_at": "2026-09-29T21:17:58Z",
            "completed_at": "2026-09-29T21:17:57Z",
            "runner_id": None, "runner_name": None,
            "labels": ["ubuntu-latest"], "steps": []}]
        summary = self.summary()
        self.assertEqual(summary["total"]["weighted_minutes"], 0)
        self.assertEqual(summary["excluded_reasons"], {"skipped": 1})

    def test_unallocated_cancelled_jobs_ignore_positive_and_negative_waiting_time(self):
        # GitHub's unallocated cancellation timestamps describe scheduling, not
        # execution. The negative case matches desktop run 37824652789.
        for seconds in (-1, 3600):
            for runner_id, runner_name in ((None, None), (0, "")):
                with self.subTest(seconds=seconds, runner_id=runner_id):
                    queued = self.job(113476698974, seconds)
                    queued.update(conclusion="cancelled", status="completed",
                                  runner_id=runner_id, runner_name=runner_name, steps=[])
                    self.groups[0]["jobs"] = [queued]
                    summary = self.summary()
                    self.assertEqual(summary["total"]["weighted_minutes"], 0)
                    self.assertEqual(summary["total"]["excluded_jobs"], 1)

    def test_allocated_or_executed_cancelled_jobs_still_reject_backward_timestamps(self):
        for metadata in ({"runner_id": 123, "runner_name": "GitHub Actions 123", "steps": []},
                         {"runner_id": None, "runner_name": None, "steps": [{"name": "Run tests"}]},
                         {"runner_id": None, "steps": []}):
            with self.subTest(metadata=metadata):
                job = self.job(1, -1)
                job.update(conclusion="cancelled", **metadata)
                self.groups[0]["jobs"] = [job]
                with self.assertRaisesRegex(ValueError, "completed before it started"):
                    self.summary()

    def test_invalid_or_backward_timestamps_fail(self):
        for value in ("garbage", "2026-10-01T12:00:00", "2026-10-01T11:00:00Z"):
            with self.subTest(value=value):
                job = self.job(1, 60)
                job["completed_at"] = value
                self.groups[0]["jobs"] = [job]
                with self.assertRaises(ValueError):
                    self.summary()

    def test_empty_repo_and_workflow_remain_explicit(self):
        summary = self.summary()
        self.assertEqual({row["repo"] for row in summary["repos"]}, {"active", "idle"})
        self.assertEqual({row["workflow"] for row in summary["workflows"]}, {"CI", "Empty"})
        self.assertTrue(next(row for row in summary["repos"] if row["repo"] == "idle")["archived"])
        self.assertIn("idle | 0 | 0.00", markdown(summary))
        self.assertIn("active / Empty | 1 | 0.00", markdown(summary))

    def test_top_ten_job_links_and_workflow_totals(self):
        self.groups[0]["jobs"] = [self.job(n, n * 60) for n in range(1, 13)]
        summary = self.summary()
        self.assertEqual(len(summary["top_jobs"]), 10)
        self.assertEqual(summary["top_jobs"][0]["job_id"], 12)
        self.assertTrue(summary["top_jobs"][0]["url"].endswith("/runs/100/job/12"))
        self.assertEqual(next(row for row in summary["workflows"] if row["workflow"] == "CI")["weighted_minutes"], 78)
        self.assertEqual(summary["repos"][0]["weighted_minutes"], 78)

    def test_missing_job_evidence_cannot_be_silent_zero(self):
        self.groups.pop()
        with self.assertRaisesRegex(ValueError, "missing for 1 runs"):
            self.summary()
        self.groups.append({"repo": "active", "run_id": 101, "jobs": None})
        with self.assertRaisesRegex(ValueError, "jobs evidence missing"):
            self.summary()


if __name__ == "__main__":
    unittest.main()
