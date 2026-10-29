#!/usr/bin/env python3
"""Summarize saved `gh api` evidence without treating elapsed time as billing.

Usage: actions_minutes.py --repos repos.json --runs runs.json --jobs jobs.json
       [--format markdown]

repos.json is the org repositories API array; runs.json contains workflow-run
objects augmented with `repo`. jobs.json is an array of {repo, run_id, jobs}
where jobs contains the jobs API objects. Collect jobs with filter=all and
paginate every endpoint to include rerun attempts. This utility does no network
access and cannot reconstruct deleted runs or infer actual billed minutes.
"""

import argparse
from collections import Counter
from datetime import datetime
import json
import math
from pathlib import Path
import sys


MULTIPLIERS = {"linux": 1, "windows": 2, "macos": 10}


def empty_totals():
    return {"raw_seconds": 0, "rounded_minutes": 0, "weighted_minutes": 0,
            "timed_jobs": 0, "excluded_jobs": 0,
            "os": {name: {"raw_seconds": 0, "rounded_minutes": 0,
                          "weighted_minutes": 0} for name in MULTIPLIERS}}


def runner_os(job):
    labels = [str(label).lower() for label in job.get("labels", [])]
    found = {name for name, prefix in (("linux", "ubuntu"), ("windows", "windows"),
                                       ("macos", "macos"))
             if any(label == name or label.startswith(prefix) for label in labels)}
    if "self-hosted" in labels or len(found) != 1:
        raise ValueError(f"job {job['id']}: unsupported or ambiguous runner labels {labels}")
    return found.pop()


def timestamp(value, job_id):
    if value is None:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("timezone missing")
        return parsed
    except (ValueError, AttributeError) as error:
        raise ValueError(f"job {job_id}: invalid timestamp {value!r}") from error


def measure(job):
    start = timestamp(job.get("started_at"), job["id"])
    end = timestamp(job.get("completed_at"), job["id"])
    if start is not None and end is not None and end < start:
        raise ValueError(f"job {job['id']}: completed before it started")
    # GitHub can timestamp a cancelled queued job even though no runner ever
    # accepted it. Preserve jobs whose historical runner metadata disappeared
    # but whose executed steps still prove that the job ran.
    never_assigned = ("runner_id" in job and not job["runner_id"]
                      and not job.get("runner_name") and not job.get("steps"))
    if job.get("conclusion") == "skipped" or never_assigned or start is None or end is None:
        return None
    seconds = (end - start).total_seconds()
    if seconds == 0:
        return None
    os_name = runner_os(job)
    minutes = math.ceil(seconds / 60)
    return os_name, seconds, minutes, minutes * MULTIPLIERS[os_name]


def accumulate(totals, measured):
    if measured is None:
        totals["excluded_jobs"] += 1
        return
    os_name, seconds, rounded, weighted = measured
    totals["timed_jobs"] += 1
    for key, value in (("raw_seconds", seconds), ("rounded_minutes", rounded),
                       ("weighted_minutes", weighted)):
        totals[key] += value
        totals["os"][os_name][key] += value


def summarize(repos, runs, job_groups):
    repo_rows = {repo["name"]: {"repo": repo["name"], "private": repo["private"],
                 "archived": repo.get("archived", False), "runs": 0, **empty_totals()}
                 for repo in repos}
    run_map, workflows = {}, {}
    for run in runs:
        repo = run["repo"]
        if repo not in repo_rows:
            raise ValueError(f"run {run['id']}: unknown repository {repo}")
        key = (repo, run["id"])
        if key in run_map:
            continue
        name = run.get("name") or run.get("workflow") or "(unnamed)"
        identity = run.get("path") or name
        workflow_key = (repo, identity)
        row = workflows.setdefault(workflow_key, {"repo": repo, "workflow": name,
                    "path": run.get("path"), "runs": 0, "events": {}, **empty_totals()})
        row["runs"] += 1
        event = run.get("event", "unknown")
        row["events"][event] = row["events"].get(event, 0) + 1
        repo_rows[repo]["runs"] += 1
        run_map[key] = (run, row)

    seen_jobs, seen_runs, top_jobs = {}, set(), []
    excluded_reasons = Counter()
    total = empty_totals()
    for group in job_groups:
        key = (group["repo"], group["run_id"])
        if key not in run_map:
            raise ValueError(f"jobs refer to an unknown run: {key}")
        if not isinstance(group.get("jobs"), list):
            raise ValueError(f"run {key}: jobs evidence missing or invalid")
        seen_runs.add(key)
        run, workflow_row = run_map[key]
        for job in group["jobs"]:
            job_key = (group["repo"], job["id"])
            if job_key in seen_jobs:
                if seen_jobs[job_key] != (key, job):
                    raise ValueError(f"job {job['id']}: conflicting duplicate evidence")
                continue
            seen_jobs[job_key] = (key, job)
            measured = measure(job)
            for row in (total, repo_rows[group["repo"]], workflow_row):
                accumulate(row, measured)
            if measured is None:
                excluded_reasons["skipped" if job.get("conclusion") == "skipped"
                                 else "unstarted_unfinished_or_zero_duration"] += 1
                continue
            os_name, seconds, rounded, weighted = measured
            top_jobs.append({"repo": group["repo"], "workflow": workflow_row["workflow"],
                "run_id": group["run_id"], "job_id": job["id"], "name": job["name"],
                "event": run.get("event"), "branch": run.get("head_branch") or run.get("branch"),
                "os": os_name, "raw_seconds": seconds, "rounded_minutes": rounded,
                "weighted_minutes": weighted,
                "url": job.get("html_url") or
                       f"https://github.com/PocketShell-io/{group['repo']}/actions/runs/{group['run_id']}/job/{job['id']}"})
    missing = set(run_map) - seen_runs
    if missing:
        raise ValueError(f"jobs evidence missing for {len(missing)} runs; first: {sorted(missing)[0]}")
    return {"method": "ceil(each completed job elapsed seconds / 60) * OS multiplier",
            "multipliers": MULTIPLIERS, "actual_billed_minutes": None,
            "billing_note": "Weighted minutes are runner-time equivalents, not billed usage. "
                            "Public standard runners may be free; consult billing API evidence.",
            "total": total, "runs": len(run_map), "unique_jobs": len(seen_jobs),
            "excluded_reasons": dict(excluded_reasons),
            "repos": sorted(repo_rows.values(), key=lambda row: (-row["weighted_minutes"], row["repo"])),
            "workflows": sorted(workflows.values(), key=lambda row: (row["repo"], row["path"] or row["workflow"])),
            "top_jobs": sorted(top_jobs, key=lambda row: (-row["weighted_minutes"], row["job_id"]))[:10]}


def markdown(summary):
    lines = ["Weighted minutes use Linux 1×, Windows 2×, macOS 10× after rounding each job up.",
             summary["billing_note"], "", "| Repository | Runs | Raw min | Linux equiv | Windows equiv | macOS equiv | Total equiv |",
             "|---|---:|---:|---:|---:|---:|---:|"]
    for row in summary["repos"]:
        os_values = " | ".join(str(row["os"][name]["weighted_minutes"]) for name in MULTIPLIERS)
        lines.append(f"| {row['repo']} | {row['runs']} | {row['raw_seconds']/60:.2f} | {os_values} | {row['weighted_minutes']} |")
    lines += ["", "| Repository / workflow | Runs | Raw min | Linux equiv | Windows equiv | macOS equiv | Total equiv |",
              "|---|---:|---:|---:|---:|---:|---:|"]
    for row in summary["workflows"]:
        label = (row["path"] or row["workflow"]).replace("|", "\\|")
        os_values = " | ".join(str(row["os"][name]["weighted_minutes"]) for name in MULTIPLIERS)
        lines.append(f"| {row['repo']} / {label} | {row['runs']} | {row['raw_seconds']/60:.2f} | {os_values} | {row['weighted_minutes']} |")
    lines += ["", "| Job | OS | Raw min | Rounded min | Weighted equiv |", "|---|---|---:|---:|---:|"]
    for job in summary["top_jobs"]:
        label = f"{job['repo']} / {job['name']}".replace("|", "\\|")
        lines.append(f"| [{label}]({job['url']}) | {job['os']} | {job['raw_seconds']/60:.2f} | {job['rounded_minutes']} | {job['weighted_minutes']} |")
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repos", "runs", "jobs"):
        parser.add_argument(f"--{name}", required=True, type=Path)
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    args = parser.parse_args()
    try:
        result = summarize(*(json.loads(getattr(args, name).read_text()) for name in ("repos", "runs", "jobs")))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, f"Invalid Actions evidence: {error}\n")
    print(markdown(result) if args.format == "markdown" else json.dumps(result, indent=2), end="\n")


if __name__ == "__main__":
    main()
