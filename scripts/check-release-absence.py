#!/usr/bin/python3 -I
"""Fail closed unless the exact GitHub Release lookup proves it is absent.

The repository endpoint is checked first so a missing/inaccessible repository
cannot be mistaken for an unused release tag. Only a JSON 404 response from
the exact release-by-tag endpoint means the release is absent.

Usage:
  scripts/check-release-absence.py --release-tag v0.6.0
  scripts/check-release-absence.py --self-test
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from typing import Any, Callable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


REPOSITORY = "PocketShell-io/pocketshell"
API_ROOT = "https://api.github.com"
TAG_PATTERN = re.compile(r"v[0-9]+\.[0-9]+\.[0-9]+")
MAX_RESPONSE_BYTES = 1024 * 1024


class GateFailure(ValueError):
    """The release lookup did not prove that the requested release is absent."""


class _UnexpectedProbeError(RuntimeError):
    """A self-test made an API call the probe did not expect."""


@dataclass(frozen=True)
class APIResponse:
    status: int
    url: str
    body: bytes


Fetch = Callable[[Request], APIResponse]


def _fetch(request: Request, open_url: Callable[..., Any] = urlopen) -> APIResponse:
    try:
        with open_url(request, timeout=15) as response:
            return APIResponse(
                status=int(response.status),
                url=response.geturl(),
                body=response.read(MAX_RESPONSE_BYTES + 1),
            )
    except HTTPError as exc:
        try:
            body = exc.read(MAX_RESPONSE_BYTES + 1)
        except OSError:
            body = b""
        return APIResponse(status=exc.code, url=exc.geturl(), body=body)
    except (URLError, TimeoutError, OSError) as exc:
        raise GateFailure(f"GitHub API request failed: {exc}") from exc


def _get_json(path: str, token: str, fetch: Fetch) -> tuple[int, str, dict[str, object]]:
    url = f"{API_ROOT}/{path}"
    request = Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method="GET",
    )
    response = fetch(request)
    if len(response.body) > MAX_RESPONSE_BYTES:
        raise GateFailure(f"GitHub API response from {url} exceeded the size limit")
    if response.url != url:
        raise GateFailure(f"GitHub API redirected the exact lookup away from {url}")
    try:
        payload = json.loads(response.body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateFailure(f"GitHub API returned invalid JSON for {url}") from exc
    if not isinstance(payload, dict):
        raise GateFailure(f"GitHub API returned a non-object response for {url}")
    return response.status, url, payload


def verify_release_absent(tag: str, token: str, fetch: Fetch = _fetch) -> None:
    if not TAG_PATTERN.fullmatch(tag):
        raise GateFailure(f"release tag must be exactly vMAJOR.MINOR.PATCH, got {tag!r}")
    if not token.strip():
        raise GateFailure("GH_TOKEN is required to check for an existing GitHub Release")

    repository_status, repository_url, repository = _get_json(
        f"repos/{REPOSITORY}", token, fetch
    )
    if repository_status != 200 or repository.get("full_name") != REPOSITORY:
        raise GateFailure(
            f"could not verify repository access at {repository_url} "
            f"(HTTP {repository_status})"
        )

    release_path = f"repos/{REPOSITORY}/releases/tags/{tag}"
    release_status, release_url, release = _get_json(release_path, token, fetch)
    if release_status != 404:
        if release_status == 200:
            raise GateFailure(f"a GitHub Release already exists for {tag}; refusing to replace it")
        raise GateFailure(
            f"release lookup was inconclusive at {release_url} (HTTP {release_status}); refusing to publish"
        )
    if release.get("message") != "Not Found" or release.get("status") != "404":
        raise GateFailure(
            f"release lookup returned an unverified 404 at {release_url}; refusing to publish"
        )


def _response(status: int, url: str, payload: dict[str, object]) -> APIResponse:
    return APIResponse(status=status, url=url, body=json.dumps(payload).encode("utf-8"))


def self_test() -> int:
    repo_url = f"{API_ROOT}/repos/{REPOSITORY}"
    release_url = f"{API_ROOT}/repos/{REPOSITORY}/releases/tags/v0.6.0"
    repo_ok = _response(200, repo_url, {"full_name": REPOSITORY})
    release_missing = _response(404, release_url, {"message": "Not Found", "status": "404"})
    release_exists = _response(200, release_url, {"tag_name": "v0.6.0"})

    class ResponseSequence:
        def __init__(self, responses: tuple[APIResponse, ...]):
            self.remaining = list(responses)

        def __call__(self, request: Request) -> APIResponse:
            if not self.remaining:
                raise _UnexpectedProbeError("unexpected GitHub API request")
            response = self.remaining.pop(0)
            if response.url != request.full_url:
                raise _UnexpectedProbeError(f"unexpected API path: {request.full_url}")
            if request.get_header("Authorization") != "Bearer test-token":
                raise _UnexpectedProbeError("GitHub API request did not use the expected token")
            return response

        def assert_exhausted(self) -> None:
            if self.remaining:
                raise _UnexpectedProbeError("release lookup did not make every expected API request")

    def mocked_check(tag: str, *responses: APIResponse) -> None:
        fetch = ResponseSequence(responses)
        try:
            verify_release_absent(tag, "test-token", fetch)
        except GateFailure:
            fetch.assert_exhausted()
            raise
        fetch.assert_exhausted()

    def unexpected_request(_request: Request) -> APIResponse:
        raise _UnexpectedProbeError("unexpected GitHub API request")

    def network_failure(_request: Request) -> APIResponse:
        def offline_urlopen(_request: Request, *, timeout: int) -> Any:
            del timeout
            raise URLError("network down")

        return _fetch(_request, offline_urlopen)

    def redirected_response(request: Request) -> APIResponse:
        if request.get_header("Authorization") != "Bearer test-token":
            raise _UnexpectedProbeError("GitHub API request did not use the expected token")
        if request.full_url == repo_url:
            return repo_ok
        return APIResponse(
            404,
            "https://api.github.com/repos/other/repo/releases/tags/v0.6.0",
            b'{"message":"Not Found","status":"404"}',
        )

    probes: list[tuple[str, Callable[[], None], bool]] = [
        (
            "verified exact-tag 404 with repository access passes",
            lambda: mocked_check("v0.6.0", repo_ok, release_missing),
            True,
        ),
        (
            "existing release HTTP 200 blocks",
            lambda: mocked_check("v0.6.0", repo_ok, release_exists),
            False,
        ),
        (
            "HTTP 500 is inconclusive and blocks",
            lambda: mocked_check("v0.6.0", repo_ok, _response(500, release_url, {"message": "Server Error"})),
            False,
        ),
        (
            "authentication failure HTTP 401 blocks",
            lambda: mocked_check("v0.6.0", repo_ok, _response(401, release_url, {"message": "Bad credentials"})),
            False,
        ),
        (
            "forbidden HTTP 403 blocks",
            lambda: mocked_check("v0.6.0", repo_ok, _response(403, release_url, {"message": "Forbidden"})),
            False,
        ),
        (
            "malformed 404 body blocks",
            lambda: mocked_check("v0.6.0", repo_ok, APIResponse(404, release_url, b"<html>Not Found</html>")),
            False,
        ),
        (
            "404 for another resource blocks",
            lambda: mocked_check("v0.6.0", _response(404, repo_url, {"message": "Not Found", "status": "404"})),
            False,
        ),
        (
            "repository mismatch blocks before release lookup",
            lambda: mocked_check("v0.6.0", _response(200, repo_url, {"full_name": "other/repo"})),
            False,
        ),
        (
            "redirected release lookup blocks",
            lambda: verify_release_absent("v0.6.0", "test-token", redirected_response),
            False,
        ),
        (
            "network error blocks",
            lambda: verify_release_absent("v0.6.0", "test-token", network_failure),
            False,
        ),
        (
            "invalid tag blocks before API access",
            lambda: verify_release_absent("v0.6.0-rc1", "test-token", unexpected_request),
            False,
        ),
        (
            "missing token blocks before API access",
            lambda: verify_release_absent("v0.6.0", "", unexpected_request),
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
        except Exception as exc:
            failures += 1
            print(f"FAIL: self-test #{index}: {label}: unexpected {type(exc).__name__}: {exc}", file=sys.stderr)
            continue
        if passed != should_pass:
            failures += 1
            print(f"FAIL: self-test #{index}: {label}", file=sys.stderr)
        else:
            print(f"  ok  [{index}/{len(probes)}] {label}")
    if failures:
        print(f"FAIL: {failures} release absence checks failed", file=sys.stderr)
        return 1
    print(f"SELF-TEST PASS: existing releases and inconclusive lookups block ({len(probes)}/{len(probes)})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-tag")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if not args.release_tag:
        parser.error("--release-tag is required unless --self-test is used")
    try:
        verify_release_absent(args.release_tag, os.environ.get("GH_TOKEN", ""))
    except GateFailure as exc:
        print(f"BLOCK: {exc}", file=sys.stderr)
        return 1
    print(f"PASS: no GitHub Release exists for {args.release_tag} (verified exact-tag 404)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
