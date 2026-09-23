#!/usr/bin/python3 -I
"""Fail closed before publishing a manually requested main-branch release.

The trusted proof is an artifact uploaded by the successful
release-emulator-validation workflow run for the exact tag commit. The
artifact's summary must record that commit, an overall PASS, and the D37 fault
verdict for that same SHA. A tag annotation or locally supplied summary is not
publication evidence.

Usage:
  scripts/check-tag-release-authorization.py --release-tag v0.6.0 --release-sha <sha> --workflow-ref refs/heads/main
  scripts/check-tag-release-authorization.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = "PocketShell-io/pocketshell"
RELEASE_WORKFLOW = ".github/workflows/release-emulator-validation.yml"
RELEASE_JOB = "Emulator-only release validation"
RELEASE_ARTIFACT_PREFIX = "release-emulator-validation-"
PUBLISH_WORKFLOW = ROOT / ".github/workflows/publish-release.yml"
LEGACY_BUILD_WORKFLOW = ROOT / ".github/workflows/build.yml"
EXPECTED_SELF_TESTS = 33
SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
TAG_PATTERN = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")


class GateFailure(ValueError):
    """The requested tag lacks a trusted exact-SHA release verdict."""


def _one_line(lines: list[str], expected: str, label: str) -> None:
    matches = [line for line in lines if line == expected]
    if len(matches) != 1:
        raise GateFailure(f"release summary must contain exactly one {label}: {expected!r}")


def validate_summary(summary: str, expected_sha: str) -> None:
    """Validate the existing release-summary contract, including an exact D37 PASS."""
    if not SHA_PATTERN.fullmatch(expected_sha):
        raise GateFailure(f"release SHA is not a full lowercase commit hash: {expected_sha!r}")

    lines = summary.splitlines()
    _one_line(lines, f"Commit SHA: {expected_sha}", "commit identity")
    _one_line(lines, "Branch: main", "main branch identity")
    _one_line(lines, "Automated status: PASS", "automated result")

    heading = "## Nightly fault/bootstrap run guard (issue #851)"
    if lines.count(heading) != 1:
        raise GateFailure("release summary must contain exactly one D37 fault-guard section")
    start = lines.index(heading) + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
    d37 = lines[start:end]
    if any("[FIXTURE DRY RUN]" in line for line in d37):
        raise GateFailure("D37 summary contains fixture dry-run output, not a release verdict")

    expected_run = re.compile(
        rf"^Nightly fault run: workflow=app2\.yml id=[1-9][0-9]* status=completed "
        rf"fault-verdict-job-conclusion=success headSha={re.escape(expected_sha)}$"
    )
    expected_release = f"Release HEAD={expected_sha} head_is_ancestor=yes"
    expected_pass = (
        "PASS: journey fault-injection safety verdict is green "
        f"(app2 journey job conclusion=success) and covers the release HEAD ({expected_sha}). "
        "The network-fault + bootstrap safety journeys passed on this line."
    )
    if sum(bool(expected_run.fullmatch(line)) for line in d37) != 1:
        raise GateFailure("D37 summary must record one completed green fault run at the exact release SHA")
    _one_line(d37, expected_release, "D37 release-head coverage")
    _one_line(d37, expected_pass, "D37 fault-verdict PASS")


def validate_tag_and_main(tag: str, release_sha: str, main_sha: str) -> None:
    if not TAG_PATTERN.fullmatch(tag):
        raise GateFailure(f"release tag must be exactly vMAJOR.MINOR.PATCH, got {tag!r}")
    for label, value in (("release SHA", release_sha), ("origin/main SHA", main_sha)):
        if not SHA_PATTERN.fullmatch(value):
            raise GateFailure(f"{label} is not a full lowercase commit hash: {value!r}")
    if release_sha != main_sha:
        raise GateFailure(f"tag commit {release_sha} is not the exact origin/main head {main_sha}")


def validate_workflow_ref(workflow_ref: str) -> None:
    if workflow_ref != "refs/heads/main":
        raise GateFailure(
            f"release publication workflow must run from refs/heads/main, got {workflow_ref!r}"
        )


def validate_release_run(
    run: dict[str, Any], job: dict[str, Any], summary: str, expected_sha: str
) -> None:
    if run.get("path") != RELEASE_WORKFLOW:
        raise GateFailure(f"release proof came from the wrong workflow: {run.get('path')!r}")
    if run.get("head_branch") != "main":
        raise GateFailure(f"release proof was not run on main: {run.get('head_branch')!r}")
    if str(run.get("head_sha", "")).lower() != expected_sha:
        raise GateFailure("release validation workflow run is not for the exact release SHA")
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise GateFailure(
            "latest exact-SHA release validation workflow run is not completed/success "
            f"(status={run.get('status')!r}, conclusion={run.get('conclusion')!r})"
        )
    if job.get("name") != RELEASE_JOB:
        raise GateFailure(f"release workflow is missing job {RELEASE_JOB!r}")
    if job.get("status") != "completed" or job.get("conclusion") != "success":
        raise GateFailure(
            "exact-SHA release validation job is not completed/success "
            f"(status={job.get('status')!r}, conclusion={job.get('conclusion')!r})"
        )
    validate_summary(summary, expected_sha)


def _workflow_job_bounds(workflow: str, job_name: str) -> tuple[list[str], int, int]:
    lines = workflow.splitlines()
    jobs_heading = next((i for i, line in enumerate(lines) if line == "jobs:"), None)
    if jobs_heading is None:
        raise GateFailure("workflow has no jobs section")
    starts = [
        i for i in range(jobs_heading + 1, len(lines))
        if re.fullmatch(r"  [A-Za-z0-9_-]+:\s*", lines[i])
    ]
    matches = [i for i in starts if lines[i].strip() == f"{job_name}:"]
    if len(matches) != 1:
        raise GateFailure(f"workflow must contain exactly one {job_name!r} job")
    start = matches[0]
    end = next((i for i in starts if i > start), len(lines))
    return lines, start, end


def _job_text(workflow: str, job_name: str) -> str:
    lines, start, end = _workflow_job_bounds(workflow, job_name)
    return "\n".join(lines[start:end])


def _job_step_blocks(workflow: str, job_name: str) -> list[tuple[str, str]]:
    lines, job_start, job_end = _workflow_job_bounds(workflow, job_name)
    starts = [
        i for i in range(job_start + 1, job_end)
        if lines[i].startswith("      - name: ")
    ]
    blocks = []
    for offset, start in enumerate(starts):
        end = starts[offset + 1] if offset + 1 < len(starts) else job_end
        name = lines[start].removeprefix("      - name: ")
        blocks.append((name, "\n".join(lines[start:end])))
    return blocks


def _drop_job_step(workflow: str, job_name: str, step_name: str) -> str:
    lines, job_start, job_end = _workflow_job_bounds(workflow, job_name)
    starts = [
        i for i in range(job_start + 1, job_end)
        if lines[i] == f"      - name: {step_name}"
    ]
    if len(starts) != 1:
        raise GateFailure(f"self-test setup expected exactly one {step_name!r} step")
    start = starts[0]
    end = next(
        (i for i in range(start + 1, job_end) if lines[i].startswith("      - name: ")),
        job_end,
    )
    return "\n".join(lines[:start] + lines[end:]) + "\n"


def _move_job_step_after(workflow: str, job_name: str, moved_name: str, after_name: str) -> str:
    lines, job_start, job_end = _workflow_job_bounds(workflow, job_name)
    starts = [
        i for i in range(job_start + 1, job_end)
        if lines[i].startswith("      - name: ")
    ]
    blocks: list[list[str]] = []
    for offset, start in enumerate(starts):
        end = starts[offset + 1] if offset + 1 < len(starts) else job_end
        blocks.append(lines[start:end])
    moved = [block for block in blocks if block and block[0] == f"      - name: {moved_name}"]
    following = [block for block in blocks if block and block[0] == f"      - name: {after_name}"]
    if len(moved) != 1 or len(following) != 1:
        raise GateFailure("self-test setup could not find the workflow steps to reorder")
    blocks.remove(moved[0])
    blocks.insert(blocks.index(following[0]) + 1, moved[0])
    return "\n".join(lines[:job_start] + [line for block in blocks for line in block] + lines[job_end:]) + "\n"


def validate_workflow_wiring(workflow: str, legacy_build: str) -> None:
    """Require default-branch manual publishing and an artifact-only legacy Build."""
    if not re.search(r"(?m)^  workflow_dispatch:$", workflow):
        raise GateFailure("publisher must use workflow_dispatch")
    if re.search(r"(?m)^  push:", workflow) or re.search(r"(?m)^  schedule:", workflow):
        raise GateFailure("publisher must not publish from tag pushes or a schedule")
    if not re.search(
        r"(?ms)^      release_tag:\n(?:(?!^      [A-Za-z0-9_-]+:).)*?^        required: true\n(?:(?!^      [A-Za-z0-9_-]+:).)*?^        type: string$",
        workflow,
    ):
        raise GateFailure("publisher must require a string release_tag dispatch input")
    if "permissions:\n  contents: read" not in workflow:
        raise GateFailure("publisher workflow default permissions must be read-only")

    authorize = _job_text(workflow, "authorize")
    build = _job_text(workflow, "build")
    publish = _job_text(workflow, "publish")
    if "if: github.ref == 'refs/heads/main'" not in authorize:
        raise GateFailure("authorize job must run only from refs/heads/main")
    if "if: github.ref == 'refs/heads/main' && needs.authorize.result == 'success'" not in build:
        raise GateFailure("build job must require main and successful authorization")
    if (
        "if: github.ref == 'refs/heads/main' && needs.authorize.result == 'success' && needs.build.result == 'success'"
        not in publish
    ):
        raise GateFailure("publish job must require main and successful authorization/build jobs")
    if "permissions:\n      actions: read\n      contents: read" not in authorize:
        raise GateFailure("authorize job must have read-only Actions and contents permissions")
    if "permissions:\n      contents: read" not in build or "contents: write" in build:
        raise GateFailure("build job must remain read-only")
    if "permissions:\n      actions: read\n      contents: write" not in publish:
        raise GateFailure("only publish job may receive Actions read and contents write")
    if workflow.count("contents: write") != 1:
        raise GateFailure("contents: write must be scoped only to the publish job")

    authorize_steps = _job_step_blocks(workflow, "authorize")
    auth_names = [name for name, _ in authorize_steps]
    if "Self-test exact-main release authorization" not in auth_names:
        raise GateFailure("authorize job must self-test the release authorization guard")
    auth_matches = [block for name, block in authorize_steps if name == "Authorize the requested tag against current main and D37"]
    if len(auth_matches) != 1:
        raise GateFailure("authorize job must contain one release authorization step")
    auth_step = auth_matches[0]
    expected_script = "scripts/check-tag-release-authorization.py"
    if (
        expected_script not in auth_step
        or '--release-tag "$RELEASE_TAG"' not in auth_step
        or '--release-sha "$RELEASE_SHA"' not in auth_step
        or '--workflow-ref "$WORKFLOW_REF"' not in auth_step
    ):
        raise GateFailure("authorize job must check the dispatch ref, tag, exact main SHA, and D37 proof")
    if "RELEASE_SHA: ${{ github.sha }}" not in auth_step or "WORKFLOW_REF: ${{ github.ref }}" not in auth_step:
        raise GateFailure("authorize job must bind the dispatched branch and SHA")
    if "release_sha: ${{ steps.authorize.outputs.release_sha }}" not in authorize:
        raise GateFailure("authorize job must pass its exact SHA to downstream jobs")

    publish_steps = _job_step_blocks(workflow, "publish")
    publish_names = [name for name, _ in publish_steps]
    reauth_index = publish_names.index("Reauthorize current main, tag, and exact-SHA D37 proof") if "Reauthorize current main, tag, and exact-SHA D37 proof" in publish_names else -1
    release_index = publish_names.index("Create GitHub Release") if "Create GitHub Release" in publish_names else -1
    if reauth_index < 0 or release_index < 0 or reauth_index >= release_index:
        raise GateFailure("publish job must reauthorize immediately before creating the release")
    reauth_step = publish_steps[reauth_index][1]
    if expected_script not in reauth_step:
        raise GateFailure("publish job must rerun the exact main/tag/D37 authorization guard")
    release_step = publish_steps[release_index][1]
    if "uses: softprops/action-gh-release@v3" not in release_step:
        raise GateFailure("publish job must use the GitHub Release action")
    if "token: ${{ github.token }}" not in release_step or "target_commitish: ${{ needs.authorize.outputs.release_sha }}" not in release_step:
        raise GateFailure("release action must use the scoped token and authorized commit SHA")
    if "tag_name: ${{ needs.authorize.outputs.release_tag }}" not in release_step:
        raise GateFailure("release action must use the authorized dispatch tag")
    if "actions/download-artifact@v7" not in publish or "pocketshell-release-apks" not in publish:
        raise GateFailure("publish job must consume APKs built in this workflow run")
    if "actions/upload-artifact@v7" not in build or "pocketshell-release-apks" not in build:
        raise GateFailure("build job must upload the validated APKs for publication")
    required_apk_checks = (
        "scripts/check-apk-metadata.py --self-test",
        "scripts/check-apk-metadata.py \\",
        "--variant debug",
        "--variant release",
        "scripts/check-apk-signing.sh --variant debug",
        "scripts/check-apk-signing.sh --variant release",
    )
    if any(check not in build for check in required_apk_checks):
        raise GateFailure("build job must test APK metadata and verify package/version/signature for both variants")
    if (
        "app2/build/outputs/apk/debug/app2-debug.apk" not in build
        or "app2/build/outputs/apk/release/app2-release.apk" not in build
    ):
        raise GateFailure("build job must package the Kotlin app2 debug and release APKs")

    if (
        "Create GitHub Release" in legacy_build
        or "uses: softprops/action-gh-release@" in legacy_build
        or re.search(r"\bgh\s+release\s+(?:create|upload)\b", legacy_build)
    ):
        raise GateFailure("legacy Build workflow must remain artifact-only")
    if "contents: write" in legacy_build:
        raise GateFailure("legacy Build workflow must not have publication permission")


def _gh_environment() -> dict[str, str]:
    env = os.environ.copy()
    # Keep a caller's repo/host override from redirecting this publication gate.
    env.pop("GH_REPO", None)
    env.pop("GH_HOST", None)
    if not env.get("GH_TOKEN"):
        raise GateFailure("GH_TOKEN is required to inspect trusted Actions release evidence")
    return env


def _run(argv: list[str], *, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(
        argv,
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit status {result.returncode}"
        raise GateFailure(f"command failed ({argv[0]}): {detail}")
    return result.stdout.strip()


def _gh_objects(endpoint: str, jq: str, env: dict[str, str]) -> list[dict[str, Any]]:
    output = _run(["gh", "api", "--paginate", endpoint, "--jq", jq], env=env)
    if not output:
        return []
    objects = []
    for line in output.splitlines():
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise GateFailure(f"GitHub API returned invalid JSON for {endpoint}: {exc}") from exc
        if not isinstance(value, dict):
            raise GateFailure(f"GitHub API returned a non-object entry for {endpoint}")
        objects.append(value)
    return objects


def _run_order(run: dict[str, Any]) -> tuple[str, int, int]:
    created = run.get("created_at")
    attempt = run.get("run_attempt")
    run_id = run.get("id")
    if not isinstance(created, str) or not isinstance(attempt, int) or not isinstance(run_id, int):
        raise GateFailure("GitHub API release-run record is missing created_at, run_attempt, or id")
    return created, attempt, run_id


def select_latest_release_validation(
    runs: list[dict[str, Any]], sha: str, jobs_for_run: Callable[[dict[str, Any]], list[dict[str, Any]]]
) -> tuple[dict[str, Any], dict[str, Any]]:
    candidates = [
        run
        for run in runs
        if run.get("path") == RELEASE_WORKFLOW
        and run.get("head_branch") == "main"
        and str(run.get("head_sha", "")).lower() == sha
    ]
    candidates.sort(key=_run_order, reverse=True)

    # A workflow_run invocation can be skipped by its trigger condition and has
    # no D37 verdict. Ignore such jobs; the newest invocation that actually
    # reached the release-validation job owns the verdict for this SHA.
    for run in candidates:
        jobs = jobs_for_run(run)
        matching = [job for job in jobs if job.get("name") == RELEASE_JOB]
        if not matching:
            continue
        if len(matching) != 1:
            raise GateFailure(f"release run {run.get('id')} contains duplicate {RELEASE_JOB!r} jobs")
        if matching[0].get("conclusion") == "skipped":
            continue
        return run, matching[0]

    raise GateFailure(f"no exact-SHA {RELEASE_JOB!r} run exists for {sha}")


def find_latest_release_validation(sha: str, env: dict[str, str]) -> tuple[dict[str, Any], dict[str, Any]]:
    endpoint = (
        f"repos/{REPOSITORY}/actions/workflows/release-emulator-validation.yml/runs"
        f"?head_sha={sha}&per_page=100"
    )
    runs = _gh_objects(endpoint, ".workflow_runs[]", env)

    def jobs_for_run(run: dict[str, Any]) -> list[dict[str, Any]]:
        endpoint = f"repos/{REPOSITORY}/actions/runs/{run.get('id')}/jobs?per_page=100"
        return _gh_objects(endpoint, ".jobs[]", env)

    return select_latest_release_validation(runs, sha, jobs_for_run)


def download_release_summary(run: dict[str, Any], env: dict[str, str]) -> str:
    run_id = run.get("id")
    if not isinstance(run_id, int):
        raise GateFailure("release workflow record has no numeric run id")
    endpoint = f"repos/{REPOSITORY}/actions/runs/{run_id}/artifacts?per_page=100"
    artifacts = _gh_objects(endpoint, ".artifacts[]", env)
    matching = [
        artifact
        for artifact in artifacts
        if isinstance(artifact.get("name"), str)
        and artifact["name"].startswith(RELEASE_ARTIFACT_PREFIX)
        and artifact.get("expired") is False
    ]
    if len(matching) != 1:
        raise GateFailure(
            f"exact-SHA release run {run_id} must have one unexpired "
            f"{RELEASE_ARTIFACT_PREFIX!r} artifact; found {len(matching)}"
        )
    artifact_name = matching[0]["name"]

    with tempfile.TemporaryDirectory(prefix="pocketshell-tag-release-proof-") as temp:
        _run(
            [
                "gh",
                "run",
                "download",
                str(run_id),
                "--repo",
                REPOSITORY,
                "--name",
                artifact_name,
                "--dir",
                temp,
            ],
            env=env,
        )
        summaries = sorted(Path(temp).rglob("summary.md"))
        if len(summaries) != 1:
            raise GateFailure(
                f"release artifact {artifact_name!r} must contain exactly one summary.md; found {len(summaries)}"
            )
        if summaries[0].stat().st_size > 5 * 1024 * 1024:
            raise GateFailure("release validation summary is unexpectedly larger than 5 MiB")
        try:
            return summaries[0].read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            raise GateFailure(f"could not read trusted release validation summary: {exc}") from exc


def authorize_tag(tag: str, release_sha: str, workflow_ref: str) -> None:
    if not TAG_PATTERN.fullmatch(tag):
        raise GateFailure(f"release tag must be exactly vMAJOR.MINOR.PATCH, got {tag!r}")
    if not SHA_PATTERN.fullmatch(release_sha):
        raise GateFailure(f"release SHA is not a full lowercase commit hash: {release_sha!r}")
    validate_workflow_ref(workflow_ref)

    _run(["git", "fetch", "--quiet", "--force", "origin", f"+refs/tags/{tag}:refs/tags/{tag}"])
    tag_sha = _run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"])
    _run(["git", "fetch", "--quiet", "--force", "origin", "+refs/heads/main:refs/remotes/origin/main"])
    main_sha = _run(["git", "rev-parse", "refs/remotes/origin/main"])
    if tag_sha != release_sha:
        raise GateFailure(f"tag {tag} resolves to {tag_sha}, but the dispatched main SHA is {release_sha}")
    validate_tag_and_main(tag, release_sha, main_sha)

    env = _gh_environment()
    run, job = find_latest_release_validation(release_sha, env)
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise GateFailure(
            "latest exact-SHA release validation run is not completed/success "
            f"(status={run.get('status')!r}, conclusion={run.get('conclusion')!r})"
        )
    summary = download_release_summary(run, env)
    validate_release_run(run, job, summary, release_sha)
    print(
        f"PASS: main-branch dispatch tag {tag} is exact origin/main head {release_sha} and has "
        f"exact-SHA D37 release proof from Actions run {run['id']}"
    )


def _synthetic_summary(
    sha: str,
    *,
    automated: str = "PASS",
    run_sha: str | None = None,
    release_sha: str | None = None,
    head_ancestor: str = "yes",
    include_pass: bool = True,
    fixture: bool = False,
) -> str:
    run_sha = run_sha or sha
    release_sha = release_sha or sha
    pass_line = (
        "PASS: journey fault-injection safety verdict is green "
        f"(app2 journey job conclusion=success) and covers the release HEAD ({release_sha}). "
        "The network-fault + bootstrap safety journeys passed on this line."
        if include_pass
        else "BLOCK: nightly fault verdict missing"
    )
    fixture_prefix = "[FIXTURE DRY RUN] " if fixture else ""
    return "\n".join(
        [
            "# PocketShell Release Emulator Validation",
            f"Commit SHA: {sha}",
            "Branch: main",
            f"Automated status: {automated}",
            "## Nightly fault/bootstrap run guard (issue #851)",
            "```",
            f"{fixture_prefix}Nightly fault run: workflow=app2.yml id=123 status=completed fault-verdict-job-conclusion=success headSha={run_sha}",
            f"{fixture_prefix}Release HEAD={release_sha} head_is_ancestor={head_ancestor}",
            f"{fixture_prefix}{pass_line}",
            "```",
            "## Validated APK identity",
            "",
        ]
    )


def _synthetic_run(sha: str, **overrides: Any) -> dict[str, Any]:
    run: dict[str, Any] = {
        "id": 123,
        "path": RELEASE_WORKFLOW,
        "head_branch": "main",
        "head_sha": sha,
        "status": "completed",
        "conclusion": "success",
        "created_at": "2026-09-23T05:26:31Z",
        "run_attempt": 1,
    }
    run.update(overrides)
    return run


def _synthetic_job(**overrides: Any) -> dict[str, Any]:
    job: dict[str, Any] = {"name": RELEASE_JOB, "status": "completed", "conclusion": "success"}
    job.update(overrides)
    return job


def _newest_failed_run_probe(sha: str, summary: str) -> None:
    older_green = _synthetic_run(sha, id=124, created_at="2026-09-23T05:00:00Z")
    newest_red = _synthetic_run(sha, id=125, created_at="2026-09-23T06:00:00Z")
    run, job = select_latest_release_validation(
        [older_green, newest_red],
        sha,
        lambda candidate: [
            _synthetic_job(conclusion="failure" if candidate["id"] == newest_red["id"] else "success")
        ],
    )
    validate_release_run(run, job, summary, sha)


def self_test() -> int:
    sha = "a" * 40
    good_run = _synthetic_run(sha)
    good_job = _synthetic_job()
    good_summary = _synthetic_summary(sha)
    workflow = PUBLISH_WORKFLOW.read_text(encoding="utf-8")
    legacy_build = LEGACY_BUILD_WORKFLOW.read_text(encoding="utf-8")

    probes: list[tuple[str, Callable[[], None], bool]] = [
        (
            "main dispatch with exact main SHA and exact-SHA D37 proof passes",
            lambda: (
                validate_workflow_ref("refs/heads/main"),
                validate_tag_and_main("v0.6.0", sha, sha),
                validate_release_run(good_run, good_job, good_summary, sha),
            ),
            True,
        ),
        ("dispatch from any non-main ref blocks", lambda: validate_workflow_ref("refs/heads/rewrite"), False),
        (
            "workflow_dispatch from a tag ref blocks",
            lambda: validate_workflow_ref("refs/tags/v0.5.6"),
            False,
        ),
        ("tag not at exact origin/main head blocks", lambda: validate_tag_and_main("v0.6.0", "b" * 40, sha), False),
        ("non-semver v tag blocks", lambda: validate_tag_and_main("v0.6.0-rc1", sha, sha), False),
        (
            "release run for another SHA blocks",
            lambda: validate_release_run(_synthetic_run("b" * 40), good_job, good_summary, sha),
            False,
        ),
        (
            "release run on another branch blocks",
            lambda: validate_release_run(_synthetic_run(sha, head_branch="rewrite"), good_job, good_summary, sha),
            False,
        ),
        (
            "wrong release workflow blocks",
            lambda: validate_release_run(_synthetic_run(sha, path=".github/workflows/tests.yml"), good_job, good_summary, sha),
            False,
        ),
        (
            "incomplete release run blocks",
            lambda: validate_release_run(_synthetic_run(sha, status="in_progress", conclusion=None), good_job, good_summary, sha),
            False,
        ),
        (
            "failed release job blocks",
            lambda: validate_release_run(good_run, _synthetic_job(conclusion="failure"), good_summary, sha),
            False,
        ),
        (
            "summary for another SHA blocks",
            lambda: validate_release_run(good_run, good_job, _synthetic_summary("b" * 40), sha),
            False,
        ),
        (
            "missing automated PASS blocks",
            lambda: validate_release_run(good_run, good_job, _synthetic_summary(sha, automated="FAIL"), sha),
            False,
        ),
        (
            "D37 run at a different SHA blocks",
            lambda: validate_release_run(good_run, good_job, _synthetic_summary(sha, run_sha="b" * 40), sha),
            False,
        ),
        (
            "D37 release HEAD without exact coverage blocks",
            lambda: validate_release_run(good_run, good_job, _synthetic_summary(sha, head_ancestor="no"), sha),
            False,
        ),
        (
            "fixture or missing D37 PASS blocks",
            lambda: validate_release_run(good_run, good_job, _synthetic_summary(sha, fixture=True), sha),
            False,
        ),
        (
            "missing D37 PASS line blocks",
            lambda: validate_release_run(good_run, good_job, _synthetic_summary(sha, include_pass=False), sha),
            False,
        ),
        (
            "no exact-SHA release validation job blocks",
            lambda: select_latest_release_validation([], sha, lambda _run: []),
            False,
        ),
        (
            "ambiguous duplicate release validation jobs block",
            lambda: select_latest_release_validation([good_run], sha, lambda _run: [good_job, good_job]),
            False,
        ),
        (
            "newest failed exact-SHA run cannot fall back to an older PASS",
            lambda: _newest_failed_run_probe(sha, good_summary),
            False,
        ),
        (
            "manual publisher is default-branch-only and legacy Build is artifact-only",
            lambda: validate_workflow_wiring(workflow, legacy_build),
            True,
        ),
        (
            "authorize job without main guard blocks",
            lambda: validate_workflow_wiring(
                workflow.replace("if: github.ref == 'refs/heads/main'", "if: github.ref != 'refs/heads/main'", 1),
                legacy_build,
            ),
            False,
        ),
        (
            "build job without main and authorize success guard blocks",
            lambda: validate_workflow_wiring(
                workflow.replace(
                    "if: github.ref == 'refs/heads/main' && needs.authorize.result == 'success'",
                    "if: needs.authorize.result == 'success'",
                    1,
                ),
                legacy_build,
            ),
            False,
        ),
        (
            "publish job without main and dependency guards blocks",
            lambda: validate_workflow_wiring(
                workflow.replace(
                    "if: github.ref == 'refs/heads/main' && needs.authorize.result == 'success' && needs.build.result == 'success'",
                    "if: needs.authorize.result == 'success' && needs.build.result == 'success'",
                    1,
                ),
                legacy_build,
            ),
            False,
        ),
        (
            "missing pre-publication reauthorization blocks",
            lambda: validate_workflow_wiring(
                _drop_job_step(workflow, "publish", "Reauthorize current main, tag, and exact-SHA D37 proof"),
                legacy_build,
            ),
            False,
        ),
        (
            "reauthorization after release creation blocks",
            lambda: validate_workflow_wiring(
                _move_job_step_after(
                    workflow,
                    "publish",
                    "Reauthorize current main, tag, and exact-SHA D37 proof",
                    "Create GitHub Release",
                ),
                legacy_build,
            ),
            False,
        ),
        (
            "optional release tag input blocks",
            lambda: validate_workflow_wiring(
                workflow.replace("        required: true\n        type: string", "        required: false\n        type: string", 1),
                legacy_build,
            ),
            False,
        ),
        (
            "tag push trigger on publisher blocks",
            lambda: validate_workflow_wiring(
                workflow.replace("on:\n  workflow_dispatch:", 'on:\n  push:\n    tags: ["v*"]\n  workflow_dispatch:', 1),
                legacy_build,
            ),
            False,
        ),
        (
            "write permission leaked into authorization job blocks",
            lambda: validate_workflow_wiring(
                workflow.replace(
                    "      actions: read\n      contents: read\n    outputs:",
                    "      actions: read\n      contents: write\n    outputs:",
                    1,
                ),
                legacy_build,
            ),
            False,
        ),
        (
            "write permission leaked into APK build job blocks",
            lambda: validate_workflow_wiring(
                workflow.replace(
                    "      contents: read\n    steps:\n      - name: Checkout the authorized release commit",
                    "      contents: write\n    steps:\n      - name: Checkout the authorized release commit",
                    1,
                ),
                legacy_build,
            ),
            False,
        ),
        (
            "APK identity checks removed from build job block",
            lambda: validate_workflow_wiring(workflow.replace("scripts/check-apk-metadata.py", ""), legacy_build),
            False,
        ),
        (
            "release action using a caller-selected ref instead of authorized tag blocks",
            lambda: validate_workflow_wiring(
                workflow.replace(
                    "tag_name: ${{ needs.authorize.outputs.release_tag }}",
                    "tag_name: ${{ github.ref_name }}",
                    1,
                ),
                legacy_build,
            ),
            False,
        ),
        (
            "historical Build workflow with a release action blocks",
            lambda: validate_workflow_wiring(
                workflow,
                legacy_build + "\n      - name: Create GitHub Release\n        uses: softprops/action-gh-release@v3\n",
            ),
            False,
        ),
        (
            "legacy Build workflow with a gh release command blocks",
            lambda: validate_workflow_wiring(
                workflow,
                legacy_build + "\n      - name: Create GitHub Release\n        run: gh release create v0.6.0\n",
            ),
            False,
        ),
    ]

    failures = 0
    for index, (label, check, should_pass) in enumerate(probes, start=1):
        try:
            check()
            passed = True
        except GateFailure:
            passed = False
        if passed != should_pass:
            failures += 1
            print(f"FAIL: self-test #{index}: {label}", file=sys.stderr)
        else:
            print(f"  ok  [{index}/{len(probes)}] {label}")
    if len(probes) != EXPECTED_SELF_TESTS:
        failures += 1
        print(f"FAIL: defined {len(probes)} probes, expected {EXPECTED_SELF_TESTS}", file=sys.stderr)
    if failures:
        print(f"FAIL: {failures} release authorization checks failed", file=sys.stderr)
        return 1
    print(
        "SELF-TEST PASS: main-only manual publisher + exact tag/main SHA + trusted exact-SHA D37 evidence "
        f"required ({EXPECTED_SELF_TESTS}/{EXPECTED_SELF_TESTS})"
    )
    return 0

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-tag")
    parser.add_argument("--release-sha")
    parser.add_argument("--workflow-ref")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.release_tag or not args.release_sha or not args.workflow_ref:
        parser.error("--release-tag, --release-sha, and --workflow-ref are required unless --self-test is used")
    try:
        authorize_tag(args.release_tag, args.release_sha.lower(), args.workflow_ref)
    except GateFailure as exc:
        print(f"BLOCK: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
