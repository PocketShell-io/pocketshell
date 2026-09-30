#!/usr/bin/env bash
# Run the full configured Vitest suite and reject empty or changed discovery.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="$ROOT_DIR/build/test-results/js-unit/vitest-results.json"
PNPM="${PNPM:-pnpm}"

usage() {
  cat <<'USAGE'
Usage: scripts/run-js-unit-gate.sh [--report <path>]

Run every Vitest unit test, write its JSON result, and check the exact
registered file and test-title set in scripts/js-unit-test-manifest.json.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      [[ $# -ge 2 && -n "$2" ]] || { printf 'FAIL: --report requires a path\n' >&2; exit 2; }
      REPORT="$2"
      [[ "$REPORT" = /* ]] || REPORT="$ROOT_DIR/$REPORT"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      printf 'FAIL: unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

cd "$ROOT_DIR"
command -v "$PNPM" >/dev/null 2>&1 || {
  printf 'FAIL: pnpm is required; run corepack prepare pnpm@12.5.1 --activate\n' >&2
  exit 1
}
[[ -x "$ROOT_DIR/node_modules/.bin/vitest" ]] || {
  printf 'FAIL: Vitest dependencies are missing; run pnpm install --frozen-lockfile\n' >&2
  exit 1
}

mkdir -p "$(dirname "$REPORT")"
: > "$REPORT"
printf 'Running the complete Vitest suite with JSON results at %s\n' "$REPORT"
"$PNPM" exec vitest run --reporter=json "--outputFile=$REPORT"
scripts/check-js-unit-results.py --report "$REPORT"
