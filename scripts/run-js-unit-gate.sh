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

CI runs this gate on the Node major in .nvmrc. A different local major prints
a warning; set JS_UNIT_GATE_STRICT_NODE=1 to make it fail instead.
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

# CI runs the gate on the Node major pinned in .nvmrc (#3023: a regex that
# Node 24 accepts failed to load on CI's Node 22). Keep .nvmrc and the
# workflow in step, and say loudly when the local Node differs.
NVMRC="$ROOT_DIR/.nvmrc"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
[[ -f "$NVMRC" ]] || { printf 'FAIL: %s is missing\n' "$NVMRC" >&2; exit 1; }
PINNED_NODE="$(tr -d '[:space:]v' < "$NVMRC")"
PINNED_NODE="${PINNED_NODE%%.*}"
[[ "$PINNED_NODE" =~ ^[0-9]+$ ]] || { printf 'FAIL: .nvmrc must hold a Node major version, found %q\n' "$PINNED_NODE" >&2; exit 1; }
if [[ -f "$WORKFLOW" ]]; then
  WORKFLOW_NODE="$(sed -nE 's/^[[:space:]]*node-version:[[:space:]]*["'"'"']?v?([0-9]+).*/\1/p' "$WORKFLOW" | sort -u)"
  [[ "$WORKFLOW_NODE" == "$PINNED_NODE" ]] || {
    printf 'FAIL: .nvmrc pins Node %s but %s uses node-version %s; keep them equal\n' \
      "$PINNED_NODE" ".github/workflows/js-first-rewrite.yml" "${WORKFLOW_NODE:-<none>}" >&2
    exit 1
  }
fi
command -v node >/dev/null 2>&1 || { printf 'FAIL: node is required\n' >&2; exit 1; }
LOCAL_NODE="$(node -p 'process.versions.node.split(".")[0]')"
if [[ "$LOCAL_NODE" != "$PINNED_NODE" ]]; then
  {
    printf 'WARNING: this gate is running on Node %s, but CI runs Node %s (.nvmrc).\n' "$LOCAL_NODE" "$PINNED_NODE"
    printf 'WARNING: a green result here does not prove CI is green; syntax or APIs newer than Node %s pass locally and fail in CI.\n' "$PINNED_NODE"
    printf 'WARNING: rerun on Node %s, for example in a node:%s-bookworm container, before reporting.\n' "$PINNED_NODE" "$PINNED_NODE"
  } >&2
  if [[ "${JS_UNIT_GATE_STRICT_NODE:-0}" == 1 ]]; then
    printf 'FAIL: JS_UNIT_GATE_STRICT_NODE=1 requires Node %s\n' "$PINNED_NODE" >&2
    exit 1
  fi
fi

mkdir -p "$(dirname "$REPORT")"
: > "$REPORT"
printf 'Running the complete Vitest suite with JSON results at %s\n' "$REPORT"
# The default reporter prints failing test names and assertion output to the
# log; the JSON report is what the exact-result checker reads. Run the checker
# even when Vitest fails so CI names the failing tests (#3043).
vitest_status=0
"$PNPM" exec vitest run --reporter=default --reporter=json "--outputFile.json=$REPORT" || vitest_status=$?
scripts/check-js-unit-results.py --report "$REPORT"
exit "$vitest_status"
