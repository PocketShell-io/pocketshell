#!/usr/bin/env bash
set -euo pipefail

# JS-first connected-test entrypoint. Keep this as an explicit dispatcher:
# every lane below has its own exact instrumentation-result contract and, for
# Docker lanes, its own independent host-side oracle. The old Gradle/app2 task
# selector was deleted with that Android project and must not silently route a
# request into a different test surface.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
CALLER_PWD="$(pwd -P 2>/dev/null || printf '%s' "$PWD")"

usage() {
  cat <<'USAGE'
Usage:
  scripts/connected-test.sh <lane> --suffix TOKEN [lane options]
  scripts/connected-test.sh --help

JS-first packaged Android lanes:
  smoke             Exact packaged-shell smoke suite (3 JUnit methods)
  lifecycle         SSH session switching and background-grace journey plus
                    independent Docker host/screenshot evidence
  composer-docker   Packaged composer journey plus Docker PTY byte oracles

Each run requires an explicit per-worktree --suffix TOKEN. Docker lanes also
require their fixture's port; lifecycle additionally requires its container
name. Start pooled Docker fixtures with scripts/agents-pool.sh up PORT first.

Examples:
  scripts/connected-test.sh smoke --suffix i2863
  scripts/connected-test.sh lifecycle --suffix i2863 --port 2222 \
    --container pocketshell-test-agents --run-id js2863-local
  scripts/agents-pool.sh up 2245
  scripts/connected-test.sh composer-docker --suffix i2863 --port 2245 \
    --session-prefix js2863-local

During its connected phase, the selected lane owns the android/ Gradle output
tree and one emulator while it installs and collects its exact same-run JUnit
report. Invoke the copy inside the checkout being tested. An invocation from a
different checkout is refused because it could otherwise test another tree
and report green. A deliberate override is available through
POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT=1.
There is no generic Gradle-argument, app2-module, or cleanup-suffix mode in
this JS-first runner.
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 2
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
[[ $# -gt 0 ]] || { usage >&2; exit 2; }

LANE="$1"
shift
case "$LANE" in
  smoke)
    TARGET="$ROOT_DIR/scripts/connected-js-smoke.sh"
    ;;
  lifecycle)
    TARGET="$ROOT_DIR/scripts/connected-js-lifecycle.sh"
    ;;
  composer-docker)
    TARGET="$ROOT_DIR/scripts/connected-js-composer-docker.sh"
    ;;
  *)
    fail "unknown JS-first connected lane '$LANE'; choose smoke, lifecycle, or composer-docker"
    ;;
esac

# Issue #2500: resolve the checkout that owns the caller's cwd and the checkout
# that owns this script before any lane can build or mutate an emulator. The
# override is retained for deliberate recovery/debug use and is never set by a
# gate.
CALLER_GIT_ROOT="$(git -C "$CALLER_PWD" rev-parse --show-toplevel 2>/dev/null || true)"
SCRIPT_GIT_ROOT="$(git -C "$ROOT_DIR" rev-parse --show-toplevel 2>/dev/null || true)"
if [[ -n "$CALLER_GIT_ROOT" && -n "$SCRIPT_GIT_ROOT" \
      && "$CALLER_GIT_ROOT" != "$SCRIPT_GIT_ROOT" \
      && "${POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT:-}" != "1" ]]; then
  printf 'FAIL: refusing to run connected-test.sh from a different checkout (issue #2500).\n' >&2
  printf '  Caller cwd belongs to:   %s\n' "$CALLER_GIT_ROOT" >&2
  printf '  Script checkout is:      %s\n' "$SCRIPT_GIT_ROOT" >&2
  printf '  Run the copy inside the checkout under test:\n' >&2
  printf '    cd %s && ./scripts/connected-test.sh %s ...\n' "$CALLER_GIT_ROOT" "$LANE" >&2
  printf '  Deliberate override: POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT=1\n' >&2
  exit 2
fi
printf 'testing checkout %s\n' "${SCRIPT_GIT_ROOT:-$ROOT_DIR}" >&2

[[ -x "$TARGET" ]] || fail "selected JS-first lane runner is missing or not executable: $TARGET"

SUFFIX=""
SUFFIX_SEEN=0
LANE_ARGS=()
HELP_REQUESTED=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --suffix)
      (( SUFFIX_SEEN == 0 )) || fail '--suffix may be supplied only once'
      [[ $# -ge 2 ]] || fail '--suffix needs a token'
      SUFFIX="$2"
      SUFFIX_SEEN=1
      shift 2
      ;;
    --suffix=*)
      (( SUFFIX_SEEN == 0 )) || fail '--suffix may be supplied only once'
      SUFFIX="${1#--suffix=}"
      SUFFIX_SEEN=1
      shift
      ;;
    --help|-h)
      HELP_REQUESTED=1
      LANE_ARGS+=("$1")
      shift
      ;;
    --module|--module=*|--pool|--no-pool|--cleanup-suffixes|-P*|:app2:*|:app:connectedDebugAndroidTest|:shared:*)
      fail "retired Gradle/app2 selector '$1' is not supported; choose an explicit JS-first lane"
      ;;
    *)
      LANE_ARGS+=("$1")
      shift
      ;;
  esac
done

if (( HELP_REQUESTED == 0 )); then
  (( SUFFIX_SEEN == 1 )) || fail "lane '$LANE' requires an explicit --suffix TOKEN for package isolation"
  [[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] \
    || fail "suffix must match [A-Za-z0-9._]+ (got: $SUFFIX)"
fi

printf 'Running JS-first connected lane: %s (suffix %s)\n' \
  "$LANE" "${SUFFIX:-help}"
if (( HELP_REQUESTED == 1 )); then
  exec "$TARGET" "${LANE_ARGS[@]}"
fi
exec "$TARGET" --suffix "$SUFFIX" "${LANE_ARGS[@]}"
