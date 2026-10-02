#!/usr/bin/env bash
# Issue #3021: run the real authorized_keys install command under every login
# shell sshd may use (sh/ash, bash, dash, zsh, fish, tcsh, csh, mksh) in Docker,
# and fail unless every registered matrix test executed and passed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="${POCKETSHELL_LOGIN_SHELL_IMAGE:-pocketshell-test:login-shells}"
REPORT="${REPORT:-$ROOT_DIR/build/test-results/login-shells/vitest-results.json}"
TEST_FILE="tests/login-shells/authorizedKeyInstallLoginShells.test.ts"
# 8 shells + the newline-label rejection test.
EXPECTED_TESTS=9

cd "$ROOT_DIR"
command -v docker >/dev/null 2>&1 || { printf 'FAIL: docker is required\n' >&2; exit 1; }
docker build -q -t "$IMAGE" -f tests/docker/Dockerfile.login-shells tests/docker >/dev/null

mkdir -p "$(dirname "$REPORT")"
: > "$REPORT"
POCKETSHELL_LOGIN_SHELL_IMAGE="$IMAGE" pnpm exec vitest run \
  --config vitest.login-shells.config.ts --reporter=default --reporter=json \
  "--outputFile.json=$REPORT" "$TEST_FILE"

python3 - "$REPORT" "$EXPECTED_TESTS" <<'PY'
import json, sys
report = json.load(open(sys.argv[1], encoding="utf-8"))
expected = int(sys.argv[2])
results = [a for f in report.get("testResults", []) for a in f.get("assertionResults", [])]
passed = [a for a in results if a.get("status") == "passed"]
shells = sorted(a["title"].rsplit(" ", 1)[-1] for a in passed if a["title"].startswith("installs every hostile label"))
if len(results) != expected or len(passed) != expected:
    sys.exit(f"FAIL: expected {expected} passed login-shell tests, got {len(passed)} passed of {len(results)}")
print(f"PASS: {len(passed)}/{expected} login-shell tests passed; shells: {' '.join(shells)}")
PY
