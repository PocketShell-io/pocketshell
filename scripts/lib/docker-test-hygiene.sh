#!/usr/bin/env bash

# Docker test-harness hygiene (Hub intake 8a7a09c7 - Hetzner orphan buildup).
#
# The 2026-10-26 orphan cleanup (~/tmp/agent-reports/hetzner-orphan-cleanup-
# 2026-10-26.md on the Grok Bot box) found 28 PocketShell test containers left
# running for WEEKS after their runs ended - crashed lanes, cancelled runs,
# never-issued `down`s - carrying ~790 aplexer workers and ~760 shells inside
# them and ~586 forks/s on the host. Three defences, all wired through here:
#
#   AC1  every harness-created container is torn down on normal exit, failure
#        AND interrupt, via a teardown stack registered BEFORE anything is
#        created (a failed start must also come back down);
#   AC2  every test container carries `pocketshell.test=1` plus
#        `agent.session=<id>` and a `pocketshell.test.created` epoch, so a
#        leftover is attributable without guessing from its name;
#   AC3  scripts/docker-test-reaper.sh force-removes labeled containers older
#        than a TTL - the backstop for whatever still leaks.
#
# This file owns the SESSION/CREATED label env (consumed by
# tests/docker/docker-compose.yml and tests/docker/gateway/docker-compose.yml)
# and the teardown stack. The stack is flushed from TWO places so no
# EXIT-trap ownership race can drop it (several scripts legitimately juggle
# the EXIT trap - avd-lock claims, connected lanes):
#   * pocketshell_release_all (scripts/lib/avd-lock.sh), the repo's universal
#     cleanup hook, flushes it FIRST while the port flocks are still held;
#   * pocketshell_test_install_teardown_trap installs a dispatcher directly
#     for scripts that hold no avd-lock.
#
# Stack entries are executed by word-splitting in the caller's environment, so
# a command must not contain arguments with whitespace (repo paths do not).

POCKETSHELL_TEST_HYGIENE_TEARDOWN_DESCS=()
POCKETSHELL_TEST_HYGIENE_TEARDOWN_CMDS=()
POCKETSHELL_TEST_HYGIENE_TEARDOWN_DONE=0

# Resolve + export the AC2 label env exactly once per shell.
#
#   POCKETSHELL_TEST_SESSION  explicit override; else the aplexer session id;
#                             else "unknown" (manual runs - the label still
#                             marks the container as ours for the reaper).
#   POCKETSHELL_TEST_CREATED_AT  epoch set once per shell; compose bakes it
#                             into the container label. Left unset (blank
#                             label) for containers created outside this lib;
#                             the reaper then falls back to the container's
#                             real creation time.
pocketshell_test_hygiene_init() {
  if [[ -z "${POCKETSHELL_TEST_SESSION:-}" ]]; then
    POCKETSHELL_TEST_SESSION="${APLEXER_SESSION_ID:-unknown}"
  fi
  if [[ -z "${POCKETSHELL_TEST_CREATED_AT:-}" ]]; then
    POCKETSHELL_TEST_CREATED_AT="$(date +%s)"
  fi
  export POCKETSHELL_TEST_SESSION POCKETSHELL_TEST_CREATED_AT
}

# Register one teardown command to run when this shell exits (or is
# interrupted). Call it BEFORE creating the resource it undoes: a failed or
# interrupted start must still come back down. Runs in reverse registration
# order; a failing entry is logged and does not stop the rest.
pocketshell_test_register_teardown() {
  local description="$1"
  shift
  if (( POCKETSHELL_TEST_HYGIENE_TEARDOWN_DONE )); then
    printf 'docker-test-hygiene: refusing to register teardown "%s" after teardown already ran\n' \
      "$description" >&2
    return 1
  fi
  POCKETSHELL_TEST_HYGIENE_TEARDOWN_DESCS+=("$description")
  POCKETSHELL_TEST_HYGIENE_TEARDOWN_CMDS+=("$*")
}

# Flush the teardown stack (reverse order). Idempotent and re-entrant safe:
# the first caller wins, later calls (nested release_all paths, both trap
# flavours firing for one exit) are no-ops.
pocketshell_test_teardown_run() {
  if (( POCKETSHELL_TEST_HYGIENE_TEARDOWN_DONE )); then
    return 0
  fi
  POCKETSHELL_TEST_HYGIENE_TEARDOWN_DONE=1
  local i cmd_rc
  for (( i=${#POCKETSHELL_TEST_HYGIENE_TEARDOWN_CMDS[@]}-1; i>=0; i-- )); do
    printf 'docker-test-hygiene: teardown [%s] %s\n' \
      "${POCKETSHELL_TEST_HYGIENE_TEARDOWN_DESCS[$i]}" \
      "${POCKETSHELL_TEST_HYGIENE_TEARDOWN_CMDS[$i]}" >&2
    cmd_rc=0
    ${POCKETSHELL_TEST_HYGIENE_TEARDOWN_CMDS[$i]} >&2 || cmd_rc=$?
    if (( cmd_rc != 0 )); then
      printf 'docker-test-hygiene: teardown [%s] failed (rc=%s); continuing\n' \
        "${POCKETSHELL_TEST_HYGIENE_TEARDOWN_DESCS[$i]}" "$cmd_rc" >&2
    fi
  done
  POCKETSHELL_TEST_HYGIENE_TEARDOWN_DESCS=()
  POCKETSHELL_TEST_HYGIENE_TEARDOWN_CMDS=()
}

# Install the EXIT/INT/TERM dispatcher for scripts that do NOT route their
# cleanup through pocketshell_release_all. `exit 130/143` inside the signal
# traps falls through to the EXIT dispatcher, so one code path owns teardown.
pocketshell_test_install_teardown_trap() {
  pocketshell_test_hygiene_init
  trap pocketshell_test_teardown_run EXIT
  # shellcheck disable=SC2317  # reached via signal, not call site
  trap 'exit 130' INT
  # shellcheck disable=SC2317  # reached via signal, not call site
  trap 'exit 143' TERM
}
