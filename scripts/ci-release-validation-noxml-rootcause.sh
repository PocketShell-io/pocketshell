#!/usr/bin/env bash
# scripts/ci-release-validation-noxml-rootcause.sh — issue #2675
#
# When the release emulator validation produced NO JUnit XML, the #2082
# ledger step used to fail with a bare "no JUnit XML from the release run"
# annotation. Run 34759080447 (and 34775134824) showed why that is a trap:
# the hosted emulator died ~46s into a cold boot, so no journey ever ran and
# the only user-visible error named a symptom two steps downstream — the real
# failure ("Unable to connect to adb daemon", the emulator process exiting)
# sat in a DIFFERENT step's log that nobody was pointed at.
#
# This script is the ledger step's diagnosis pass. It is BEST-EFFORT by
# contract: it must never exit non-zero for missing evidence (that would
# invent a second, louder failure on top of the one being diagnosed), and it
# must never invent a root cause it cannot see. It gathers:
#
#   1. the run's failed-step logs, via `gh run view --log-failed` (the job
#      already holds `actions: read` and GH_TOKEN) or a caller-supplied file
#      (--failed-log, the self-test/local seam),
#   2. the newest build/release-emulator-validation/<run-id>/summary.md,
#   3. any *.log the validation chain managed to write before dying.
#
# and surfaces boot-tier signatures (adb daemon unreachable, snapshot load
# failure, GL init loss, emulator ERROR, process exit) as annotation +
# step-summary excerpts, with an explicit pointer at the step whose log holds
# the authoritative evidence.
#
# ISSUE #2716 — THE D37 GUARD TIER. The nightly fault/bootstrap run guard
# (scripts/release-emulator-validation.sh's check_nightly_fault_run) BLOCKs the
# chain BEFORE any Gradle work when the app2 journey fault verdict is red /
# stale / cancelled / missing on the release line — zero tests, no XML, the
# exact shape this script diagnoses. Its transcript is written to
# nightly-fault-guard/result.txt (not *.log, so the generic *.log find never
# collected it), and the signature list below was boot-only, so all three
# 2026-09-16 guard blocks (runs 35051420588, 35092088955, 35096339243) were
# mislabelled "No known failure signature found" with the BLOCK line sitting in
# the very summary.md the pass had collected. The pass now collects the guard
# transcript and names a guard-tier verdict (which takes precedence over the
# boot tier: when the guard blocks, the emulator tier is irrelevant — the chain
# never reached it). A guard block is the gate working as designed, not infra.
#
# USAGE
#   ci-release-validation-noxml-rootcause.sh [--run-id ID] [--failed-log FILE]
#                                            [--gh PATH]
#   Env: GITHUB_RUN_ID, GITHUB_REPOSITORY, GH_TOKEN, GITHUB_STEP_SUMMARY,
#        RUN_ID (same as --run-id).
#
# Self-test: scripts/test-ci-release-validation-noxml-rootcause.sh
#
# Exit codes: 0 diagnosis produced (best-effort, including "no evidence"),
# 2 usage error. The CALLER (the ledger step) exits 1 regardless — this
# script explains a red run, it never forgives one.

set -uo pipefail

RUN_ID_ARG="${RUN_ID:-}"
FAILED_LOG=""
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-id) RUN_ID_ARG="$2"; shift 2 ;;
    --failed-log) FAILED_LOG="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Boot-tier signatures observed on run 34759080447, plus the GL tier the
# #449 comment in release-emulator-validation.yml documents and the generic
# process-death line the runner action emits.
readonly SIGNATURES='Unable to connect to adb daemon|Failed to load snapshot|does not have the requested snapshot|Failed to initialize [0-9]+-[0-9]+ format|No compose hierarchies found|emulator: ERROR|failed with exit code'

# Guard-tier signatures (issue #2716): the D37 verdict lines the nightly
# fault/bootstrap guard writes into nightly-fault-guard/result.txt and quotes
# verbatim into summary.md. Each branch of check-nightly-fault-run.sh's
# evaluate_nightly_fault_run emits a "BLOCK: ..." line; the release wrapper's
# fail() line and the RED/STALE reasons are the two shapes seen in the wild.
readonly GUARD_SIGNATURES='nightly fault/bootstrap run guard BLOCKED|safety verdict is RED|is STALE for this release|BLOCK: '

evidence_files=()
temp_files=()
summary_note=""

cleanup() {
  local f
  for f in "${temp_files[@]:-}"; do
    [[ -n "$f" ]] && rm -f -- "$f"
  done
}
trap cleanup EXIT

collect_failed_log() {
  if [[ -n "$FAILED_LOG" ]]; then
    if [[ -f "$FAILED_LOG" ]]; then
      evidence_files+=("$FAILED_LOG")
    else
      summary_note+="Requested failed-log file not found: $FAILED_LOG"$'\n'
    fi
    return 0
  fi
  if [[ -z "${GITHUB_RUN_ID:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
    summary_note+="No failed-step log source: GITHUB_RUN_ID/GITHUB_REPOSITORY unset outside Actions."$'\n'
    return 0
  fi
  if ! command -v "$GH_BIN" >/dev/null 2>&1; then
    summary_note+="No failed-step log source: gh CLI ($GH_BIN) not found."$'\n'
    return 0
  fi
  local tmp
  tmp="$(mktemp "${TMPDIR:-/tmp}/release-noxml-failed-log.XXXXXX")"
  temp_files+=("$tmp")
  if ! "$GH_BIN" run view "$GITHUB_RUN_ID" --repo "$GITHUB_REPOSITORY" --log-failed > "$tmp" 2>/dev/null; then
    summary_note+="gh could not fetch failed-step logs for run $GITHUB_RUN_ID (run still in progress or token limited)."$'\n'
    return 0
  fi
  evidence_files+=("$tmp")
  return 0
}

# The validation chain's own artifacts, if it got far enough to write any.
collect_disk_evidence() {
  local root="${RELEASE_NOXML_DISK_ROOT:-build/release-emulator-validation}"
  [[ -d "$root" ]] || return 0
  local newest=""
  if [[ -n "$RUN_ID_ARG" && -f "$root/$RUN_ID_ARG/summary.md" ]]; then
    newest="$root/$RUN_ID_ARG/summary.md"
  else
    newest="$(find "$root" -name summary.md -type f 2>/dev/null | sort | tail -1)"
  fi
  [[ -n "$newest" ]] && evidence_files+=("$newest")
  # Issue #2716: the D37 guard's transcript is result.txt, not *.log — the
  # generic find below never picked it up. Prefer the named run-id dir, else
  # the newest one on disk.
  local guard=""
  if [[ -n "$RUN_ID_ARG" && -f "$root/$RUN_ID_ARG/nightly-fault-guard/result.txt" ]]; then
    guard="$root/$RUN_ID_ARG/nightly-fault-guard/result.txt"
  else
    guard="$(find "$root" -path '*/nightly-fault-guard/result.txt' -type f 2>/dev/null | sort | tail -1)"
  fi
  [[ -n "$guard" ]] && evidence_files+=("$guard")
  while IFS= read -r log; do
    evidence_files+=("$log")
  done < <(find "$root" -name '*.log' -type f 2>/dev/null | head -20)
  return 0
}

extract_excerpts() {
  local file="$1"
  # `echo "::error` lines are the workflow's OWN annotations quoted inside
  # the log — never evidence.
  grep -aE "$SIGNATURES" "$file" 2>/dev/null | grep -av 'echo ::error' | head -12 || true
}

extract_guard_excerpts() {
  local file="$1"
  grep -aE "$GUARD_SIGNATURES" "$file" 2>/dev/null | grep -av 'echo ::error' | head -12 || true
}

main() {
  collect_failed_log
  collect_disk_evidence

  # The chain's own verdict line: a summary.md saying FAIL means the script
  # ran and died mid-suite (NOT a boot-tier death), while its absence means
  # the run never got that far. Either fact narrows the diagnosis.
  local chain_status=""
  local chain_status_src=""
  local f
  for f in "${evidence_files[@]:-}"; do
    if [[ -n "$f" && "$f" == *summary.md && -z "$chain_status" ]]; then
      chain_status="$(grep -a '^Automated status:' "$f" 2>/dev/null | head -1)"
      chain_status_src="$f"
    fi
  done
  local guard_excerpts=""
  local boot_excerpts=""
  local f
  for f in "${evidence_files[@]:-}"; do
    [[ -n "$f" ]] || continue
    local got
    got="$(extract_guard_excerpts "$f")"
    if [[ -n "$got" ]]; then
      guard_excerpts+="$(printf 'From %s:\n' "$f")$got"$'\n'
    fi
    got="$(extract_excerpts "$f")"
    if [[ -n "$got" ]]; then
      boot_excerpts+="$(printf 'From %s:\n' "$f")$got"$'\n'
    fi
  done
  # The excerpts actually shown: guard evidence wins (issue #2716) — when the
  # D37 guard blocked, the chain never reached the emulator/Gradle tiers, so
  # any boot-tier matches (e.g. a post-mortem `gh --log-failed` fetch that
  # includes an unrelated failed step) are not why the XML is missing.
  local excerpts="$guard_excerpts"
  [[ -n "$excerpts" ]] || excerpts="$boot_excerpts"

  local verdict
  if [[ -n "$guard_excerpts" ]]; then
    verdict="Release-gate guard BLOCKED (D37) — the nightly fault/bootstrap run guard refused this release because the app2 journey fault-injection verdict is red, stale or missing on the release line. This is the gate working as designed, not an emulator/boot failure: the chain stopped BEFORE any Gradle or journey work, which is exactly why no JUnit XML exists (observed identically on runs 35051420588, 35092088955 and 35096339243, 2026-09-16). The authoritative evidence is nightly-fault-guard/result.txt under this run's build/release-emulator-validation/<run-id>/ directory and the latest 'app2 journey suite' run; the sanctioned ways forward (D37, no override) are fixing or quarantining the failing journey class, or getting a green app2 journey run on the release commit."
  elif [[ -n "$boot_excerpts" ]]; then
    verdict="Boot-tier failure suspected: the excerpts match the hosted-emulator boot signatures (adb daemon unreachable, snapshot load failure, GL init loss, or the emulator process exiting) recorded on run 34759080447. No JUnit XML exists because no journey ever executed."
  else
    verdict="No known failure signature found in the available evidence. The authoritative source is the failed step's own log — in order: 'Boot-check hosted emulator', 'Run emulator-only release validation', then the Gradle stage logs under build/release-emulator-validation/."
  fi

  printf 'Provisional root cause for the missing JUnit XML (issue #2675):\n\n%s\n\n' "$verdict"
  if [[ -n "$excerpts" ]]; then
    printf 'Signature excerpts (bounded to 12 per source):\n\n```\n%s```\n' "$excerpts"
  else
    printf 'Signature excerpts: none found in: %s\n' "${evidence_files[*]:-<nothing on disk>}"
  fi
  if [[ -n "$chain_status" ]]; then
    printf 'Validation chain summary verdict (%s): %s\n' "$chain_status_src" "$chain_status"
  fi
  if [[ -n "$summary_note" ]]; then
    printf 'Evidence-collection notes:\n%s' "$summary_note"
  fi

  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      printf '\n### Root cause (provisional) — no JUnit XML (issue #2675)\n\n'
      printf '%s\n\n' "$verdict"
      if [[ -n "$excerpts" ]]; then
        printf '```\n%s```\n' "$excerpts"
      fi
      if [[ -n "$chain_status" ]]; then
        printf 'Validation chain summary verdict (%s): %s\n' "$chain_status_src" "$chain_status"
      fi
      if [[ -n "$summary_note" ]]; then
        printf '%s\n' "$summary_note"
      fi
    } >> "$GITHUB_STEP_SUMMARY"
  fi

  # One bounded, single-line annotation pointing at the full section.
  if [[ -n "$guard_excerpts" ]]; then
    echo "::error title=Release validation produced no JUnit XML (issue #2675)::Provisional root cause: the D37 nightly fault/bootstrap guard BLOCKED the release (app2 journey fault verdict red/stale on this line) — zero tests ran by design. See 'Root cause (provisional)' in the run summary and nightly-fault-guard/result.txt."
  elif [[ -n "$boot_excerpts" ]]; then
    echo "::error title=Release validation produced no JUnit XML (issue #2675)::Provisional root cause: hosted-emulator boot-tier failure. See 'Root cause (provisional)' in the run summary and the failed boot/run step logs for the authoritative error."
  else
    echo "::error title=Release validation produced no JUnit XML (issue #2675)::No known signature found in available evidence; inspect the failed boot/run step logs directly."
  fi
  return 0
}

main
