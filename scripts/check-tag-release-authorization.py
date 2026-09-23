#!/usr/bin/python3 -I
"""Fail closed before publishing a release for a pushed vMAJOR.MINOR.PATCH tag.

The trusted proof is an artifact uploaded by the successful
release-emulator-validation workflow run for the exact tag commit. The
artifact's summary must record that commit, an overall PASS, and the D37 fault
verdict for that same SHA. A tag annotation or locally supplied summary is not
publication evidence.

Usage:
  scripts/check-tag-release-authorization.py --release-tag v0.6.0 --release-sha <sha>
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
BUILD_WORKFLOW = ROOT / ".github/workflows/build.yml"
EXPECTED_SELF_TESTS = 22
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


def _workflow_step_blocks(workflow: str) -> list[tuple[str, str]]:
    lines = workflow.splitlines()
    starts = [index for index, line in enumerate(lines) if line.startswith("      - name: ")]
    blocks: list[tuple[str, str]] = []
    for offset, start in enumerate(starts):
        end = starts[offset + 1] if offset + 1 < len(starts) else len(lines)
        name = lines[start].removeprefix("      - name: ")
        blocks.append((name, "\n".join(lines[start:end])))
    return blocks


def validate_workflow_wiring(workflow: str) -> None:
    """Require the live build job to authorize tags before release publication."""
    steps = _workflow_step_blocks(workflow)
    names = [name for name, _ in steps]

    def one_step(name: str) -> tuple[int, str]:
        matches = [(index, block) for index, (step_name, block) in enumerate(steps) if step_name == name]
        if len(matches) != 1:
            raise GateFailure(f"Build workflow must contain exactly one {name!r} step")
        return matches[0]

    _, self_test = one_step("Self-test exact-main tag publication guard")
    if "run: scripts/check-tag-release-authorization.py --self-test" not in self_test:
        raise GateFailure("Build workflow must run the tag-publication guard self-test")

    guard_index, guard = one_step("Authorize release tag against exact main validation")
    expected_guard = 'run: scripts/check-tag-release-authorization.py --release-tag "$RELEASE_TAG" --release-sha "$RELEASE_SHA"'
    if expected_guard not in guard:
        raise GateFailure("Build workflow must run the exact tag + event SHA authorization command")
    if "if: startsWith(github.ref, 'refs/tags/v')" not in guard:
        raise GateFailure("Build workflow must authorize every v* tag ref before packaging")
    if "GH_TOKEN: ${{ github.token }}" not in guard or "RELEASE_SHA: ${{ github.sha }}" not in guard:
        raise GateFailure("Build workflow must use its GitHub token and event SHA for release proof")

    release_index, release = one_step("Create GitHub Release")
    if guard_index >= release_index:
        raise GateFailure("release authorization must run before GitHub Release creation")
    if "uses: softprops/action-gh-release@v3" not in release:
        raise GateFailure("Build workflow release publication action changed unexpectedly")
    if "if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')" not in release:
        raise GateFailure("GitHub Release creation must be restricted to pushed v* tags after authorization")

    if names.index("Authorize release tag against exact main validation") >= names.index("Install locked JS dependencies"):
        raise GateFailure("tag authorization must run before dependency installation and APK build")
    if "permissions:\n      actions: read\n      contents: write" not in workflow:
        raise GateFailure("Build workflow must grant read-only Actions access and release contents access")
    if workflow.count("uses: softprops/action-gh-release@v3") != 1:
        raise GateFailure("Build workflow must have exactly one GitHub Release publication action")


def _drop_step(workflow: str, name: str) -> str:
    lines = workflow.splitlines()
    starts = [index for index, line in enumerate(lines) if line == f"      - name: {name}"]
    if len(starts) != 1:
        raise GateFailure(f"self-test setup expected exactly one {name!r} step")
    start = starts[0]
    end = next(
        (index for index in range(start + 1, len(lines)) if lines[index].startswith("      - name: ")),
        len(lines),
    )
    return "\n".join(lines[:start] + lines[end:]) + "\n"


def _move_step_after(workflow: str, moved_name: str, after_name: str) -> str:
    lines = workflow.splitlines()
    starts = [index for index, line in enumerate(lines) if line.startswith("      - name: ")]
    blocks: list[list[str]] = []
    for offset, start in enumerate(starts):
        end = starts[offset + 1] if offset + 1 < len(starts) else len(lines)
        blocks.append(lines[start:end])
    moved = [block for block in blocks if block and block[0] == f"      - name: {moved_name}"]
    following = [block for block in blocks if block and block[0] == f"      - name: {after_name}"]
    if len(moved) != 1 or len(following) != 1:
        raise GateFailure("self-test setup could not find the workflow steps to reorder")
    blocks.remove(moved[0])
    position = blocks.index(following[0]) + 1
    blocks.insert(position, moved[0])
    return "\n".join(line for block in blocks for line in block).rstrip() + "\n"


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


def authorize_tag(tag: str, release_sha: str) -> None:
    if not TAG_PATTERN.fullmatch(tag):
        raise GateFailure(f"release tag must be exactly vMAJOR.MINOR.PATCH, got {tag!r}")
    if not SHA_PATTERN.fullmatch(release_sha):
        raise GateFailure(f"release SHA is not a full lowercase commit hash: {release_sha!r}")

    tag_sha = _run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"])
    if tag_sha != release_sha:
        raise GateFailure(f"tag {tag} resolves to {tag_sha}, but the event commit is {release_sha}")

    _run(["git", "fetch", "--quiet", "--force", "origin", "+refs/heads/main:refs/remotes/origin/main"])
    main_sha = _run(["git", "rev-parse", "refs/remotes/origin/main"])
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
        f"PASS: tag {tag} is exact origin/main head {release_sha} and has "
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


def _expect_invalid_workflow(workflow: str) -> None:
    validate_workflow_wiring(workflow)


def self_test() -> int:
    sha = "a" * 40
    good_run = _synthetic_run(sha)
    good_job = _synthetic_job()
    good_summary = _synthetic_summary(sha)

    workflow = BUILD_WORKFLOW.read_text(encoding="utf-8")
    probes: list[tuple[str, Callable[[], None], bool]] = [
        (
            "exact main SHA and exact-SHA D37 release proof pass",
            lambda: (validate_tag_and_main("v0.6.0", sha, sha), validate_release_run(good_run, good_job, good_summary, sha)),
            True,
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
            lambda: select_latest_release_validation(
                [good_run], sha, lambda _run: [good_job, good_job]
            ),
            False,
        ),
        (
            "newest failed exact-SHA run cannot fall back to an older PASS",
            lambda: _newest_failed_run_probe(sha, good_summary),
            False,
        ),
        ("Build workflow wires tag authorization before publication", lambda: validate_workflow_wiring(workflow), True),
        (
            "Build workflow without authorization blocks",
            lambda: _expect_invalid_workflow(
                _drop_step(workflow, "Authorize release tag against exact main validation")
            ),
            False,
        ),
        (
            "authorization moved after release creation blocks",
            lambda: _expect_invalid_workflow(
                _move_step_after(
                    workflow,
                    "Authorize release tag against exact main validation",
                    "Create GitHub Release",
                )
            ),
            False,
        ),
        (
            "unconditional tag authorization wiring blocks",
            lambda: _expect_invalid_workflow(
                workflow.replace(
                    "if: startsWith(github.ref, 'refs/tags/v')",
                    "if: github.event_name == 'workflow_dispatch'",
                    1,
                )
            ),
            False,
        ),
        (
            "workflow-dispatched tag cannot publish a release",
            lambda: _expect_invalid_workflow(
                workflow.replace(
                    "if: github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')",
                    "if: startsWith(github.ref, 'refs/tags/v')",
                    1,
                )
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
            print(f"  ok  [{index}/{EXPECTED_SELF_TESTS}] {label}")
    if len(probes) != EXPECTED_SELF_TESTS:
        failures += 1
        print(f"FAIL: defined {len(probes)} probes, expected {EXPECTED_SELF_TESTS}", file=sys.stderr)
    if failures:
        print(f"FAIL: {failures} tag publication authorization checks failed", file=sys.stderr)
        return 1
    print(f"SELF-TEST PASS: exact-main tag + trusted exact-SHA D37 evidence required ({EXPECTED_SELF_TESTS}/{EXPECTED_SELF_TESTS})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-tag")
    parser.add_argument("--release-sha")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.release_tag or not args.release_sha:
        parser.error("--release-tag and --release-sha are required unless --self-test is used")
    try:
        authorize_tag(args.release_tag, args.release_sha.lower())
    except GateFailure as exc:
        print(f"BLOCK: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
