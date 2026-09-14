#!/usr/bin/env bash
# scripts/ci-release-emulator-red-issue.sh — issue #2675
#
# On a SINGLE red Release Emulator Validation run (the #2356 validated-RC
# tier, fired by a green scheduled Tests run), file (or update) ONE tracking
# issue — the same immediate paging path scripts/ci-red-issue.sh established
# for red scheduled full-suite runs (#2353).
#
# Why this exists next to scripts/ci-nightly-rc-issue.sh (#2356): the
# nightly-RC tracker deliberately requires TWO consecutive infra failures so
# one flaky blip cannot open it, which is correct for its escalation job —
# but it also meant the red on 2026-09-13 (run 34759080447: the hosted
# emulator never produced a working device, and the ledger step failed on the
# missing JUnit XML) sat unnoticed for ~5 hours until manual triage, because
# full-suite-notify.yml only watched the Tests workflow. Single red opens
# THIS issue; two in a row ALSO updates the #2356 streak tracker.
#
# Run from inside the notifying workflow's checkout (a stable marker search
# is enough here; no commit-window computation — this tracks release
# validation infra health, not a `main` regression to bisect).
#
# USAGE
#   ci-release-emulator-red-issue.sh --repo OWNER/NAME --run-url URL
#     --sha SHA [--gh PATH]
#
# Self-test: scripts/test-ci-release-emulator-red-issue.sh
#
# Exits non-zero (and says why on stderr) on any `gh` failure — a red run
# that fails to notify must ITSELF be loud, not swallowed.

set -uo pipefail

TITLE="CI: Release Emulator Validation red on the scheduled chain (issue #2675)"
MARKER="pocketshell-release-emulator-red-marker"

REPO=""
RUN_URL=""
SHA=""
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --run-url) RUN_URL="$2"; shift 2 ;;
    --sha) SHA="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,32p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$REPO" || -z "$RUN_URL" || -z "$SHA" ]]; then
  echo "usage: $0 --repo OWNER/NAME --run-url URL --sha SHA [--gh PATH]" >&2
  exit 2
fi

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  echo "gh CLI not found ($GH_BIN) — cannot file/update the release-validation red tracking issue" >&2
  exit 1
fi

body="$(cat <<BODY
This is the standing tracking issue for a SINGLE red **Release Emulator
Validation** run (\`.github/workflows/release-emulator-validation.yml\` on
the #2356 scheduled chain — issue #2675). Repeated red runs comment here
instead of filing a new issue every night.

Marker: $MARKER

## Latest red run

- Release Emulator Validation run: $RUN_URL
- Commit under validation: \`$SHA\`

The validated-RC tier being red means \`main\` currently has no freshly
validated release candidate. One red may be hosted-emulator infra rot (the
failure mode #2350's plan called out: discovered on release day) or a real
regression — check the run's root-cause summary section (issue #2675) before
re-running. Two consecutive failures ALSO update the #2356 streak-tracking
issue. This issue is closed by convention once a nightly run goes green
again.
BODY
)"

existing=""
existing="$("$GH_BIN" issue list --repo "$REPO" --state open \
  --search "$MARKER in:body" --json number --limit 5 --jq '.[0].number // empty' \
  2>/dev/null)" || existing=""

if [[ -n "$existing" ]]; then
  if ! "$GH_BIN" issue comment "$existing" --repo "$REPO" --body "$body"; then
    echo "failed to comment on existing release-validation red tracking issue #$existing" >&2
    exit 1
  fi
  echo "Commented on existing tracking issue #$existing"
else
  created=""
  if ! created="$("$GH_BIN" issue create --repo "$REPO" --title "$TITLE" --body "$body")"; then
    echo "failed to create the release-validation red tracking issue" >&2
    exit 1
  fi
  echo "Created tracking issue: $created"
fi
