#!/usr/bin/env bash
set -euo pipefail

# Docker test-container TTL reaper (Hub intake 8a7a09c7 - AC3).
#
# The backstop behind AC1's teardown-on-exit: on 2026-10-26 the Hetzner orphan
# cleanup found 28 PocketShell test containers (pocketshell-test-agents-*,
# docker-bootstrap-*, tests-docker-*) that had outlived their runs by WEEKS,
# carrying ~790 aplexer workers and ~760 login shells and a material slice of
# ~586 forks/s of host load. Every container the test harness now creates is
# labeled (tests/docker/docker-compose.yml):
#
#     pocketshell.test=1
#
# and THIS script force-removes labeled containers older than a TTL. It is
# fail-closed by construction: it selects ONLY on that label, so unlabeled
# containers and Coolify-managed containers (which never carry it) are
# untouchable - there is no code path, flag or fallback that can widen the
# selection. "No deletions outside the harness's own labeled resources."
#
# Age source: the container's `pocketshell.test.created` epoch label when
# present and parseable, else the container's real creation time from Docker
# (always present). Containers that are stopped or exited count too - they
# still hold the fixture images' build cache entries and named volumes.
#
# Every removal (or would-remove, under --dry-run) is logged as one line to
# stdout and appended to the log file, so a cleanup can always be audited
# after the fact.
#
# Usage:
#   scripts/docker-test-reaper.sh [--ttl-hours N] [--dry-run] [--log-file PATH]
#
#   --ttl-hours N   remove labeled containers older than N hours (default 6;
#                   fractional values allowed). Overridable via
#                   POCKETSHELL_REAPER_TTL_HOURS.
#   --dry-run       log what would be removed; remove nothing.
#   --log-file PATH where the removal log is appended (default
#                   ~/.local/state/pocketshell/docker-test-reaper.log, i.e.
#                   the XDG state home).
#
# Scheduling (Hetzner): a systemd timer ships in scripts/systemd/ - see
# docs/testing.md "Docker test-harness hygiene" for the 4-line install and
# the equivalent cron line.

DEFAULT_TTL_HOURS=6
TTL_HOURS="${POCKETSHELL_REAPER_TTL_HOURS:-$DEFAULT_TTL_HOURS}"
DRY_RUN=0
LOG_FILE="${POCKETSHELL_REAPER_LOG:-${XDG_STATE_HOME:-$HOME/.local/state}/pocketshell/docker-test-reaper.log}"

usage() {
  sed -n '4,43p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --ttl-hours)
      [[ $# -ge 2 ]] || { printf 'docker-test-reaper: --ttl-hours needs a value\n' >&2; exit 2; }
      TTL_HOURS="$2"
      shift 2
      ;;
    --ttl-hours=*)
      TTL_HOURS="${1#*=}"
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --log-file)
      [[ $# -ge 2 ]] || { printf 'docker-test-reaper: --log-file needs a value\n' >&2; exit 2; }
      LOG_FILE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    *)
      printf 'docker-test-reaper: unknown argument: %s\n' "$1" >&2
      exit 2
      ;;
  esac
done

case "$TTL_HOURS" in
  ''|*[!0-9.]*)
    printf 'docker-test-reaper: --ttl-hours must be a positive number, got: %s\n' "$TTL_HOURS" >&2
    exit 2
    ;;
esac
awk -v v="$TTL_HOURS" 'BEGIN { exit !(v > 0) }' || {
  printf 'docker-test-reaper: --ttl-hours must be > 0, got: %s\n' "$TTL_HOURS" >&2
  exit 2
}

command -v docker >/dev/null || { printf 'docker-test-reaper: docker not found\n' >&2; exit 1; }
command -v awk >/dev/null || { printf 'docker-test-reaper: awk not found\n' >&2; exit 1; }

removed=0
now="$(date +%s)"

# label filter is THE selection contract; never widen it.
while IFS=$'\t' read -r id name created_at created_label session_label; do
  [[ -n "$id" ]] || continue
  created_epoch=""
  case "$created_label" in
    ''|*[!0-9]*) created_epoch="" ;;
    *) created_epoch="$created_label" ;;
  esac
  if [[ -z "$created_epoch" ]]; then
    created_epoch="$(date -d "$created_at" +%s 2>/dev/null || true)"
  fi
  if [[ -z "$created_epoch" ]]; then
    # `docker ps` CreatedAt carries a zone abbreviation GNU date may reject;
    # `docker inspect` returns RFC3339, which always parses.
    inspected="$(docker inspect -f '{{.Created}}' "$id" 2>/dev/null || true)"
    [[ -z "$inspected" ]] || created_epoch="$(date -d "$inspected" +%s 2>/dev/null || true)"
  fi
  if [[ -z "$created_epoch" ]]; then
    printf 'docker-test-reaper: cannot age container %s (%s); leaving it alone\n' "$name" "$id" >&2
    continue
  fi
  age_hours=$(awk -v now="$now" -v created="$created_epoch" 'BEGIN { printf "%.2f", (now - created) / 3600 }')
  ttl_seconds=$(awk -v h="$TTL_HOURS" 'BEGIN { printf "%d", h * 3600 }')
  if (( now - created_epoch < ttl_seconds )); then
    continue
  fi
  if (( DRY_RUN )); then
    line="would remove container $name ($id) age=${age_hours}h session=${session_label:-unknown} ttl=${TTL_HOURS}h"
    printf '%s\n' "$line"
    printf '%s %s\n' "$(date -Is)" "$line" >> "$LOG_FILE"
  else
    if docker rm -f "$id" >/dev/null 2>&1; then
      line="removed container $name ($id) age=${age_hours}h session=${session_label:-unknown} ttl=${TTL_HOURS}h"
      printf '%s\n' "$line"
      printf '%s %s\n' "$(date -Is)" "$line" >> "$LOG_FILE"
      removed=$((removed + 1))
    else
      printf '%s failed to remove container %s (%s); it may already be gone\n' \
        "$(date -Is)" "$name" "$id" >> "$LOG_FILE" 2>/dev/null || true
      printf 'docker-test-reaper: failed to remove container %s (%s)\n' "$name" "$id" >&2
    fi
  fi
done < <(docker ps -a \
  --filter 'label=pocketshell.test=1' \
  --format '{{.ID}}\t{{.Names}}\t{{.CreatedAt}}\t{{.Label "pocketshell.test.created"}}\t{{.Label "agent.session"}}')

if (( DRY_RUN )); then
  printf 'docker-test-reaper: dry run complete (nothing removed)\n'
else
  printf 'docker-test-reaper: done (%d container(s) removed, ttl=%sh)\n' "$removed" "$TTL_HOURS"
fi
