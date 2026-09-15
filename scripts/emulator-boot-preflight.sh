#!/bin/sh
# Hosted-emulator boot preflight for the Release Emulator Validation lane
# (issue #2675's boot attempt 1; script-delivery fix in issue #2690).
#
# Why this is a checked-in file instead of an inline `script:` block: the
# reactivecircus/android-emulator-runner action executes an inline
# `script:` input LINE BY LINE through separate `/usr/bin/sh -c`
# invocations (one fresh dash process per line), so no multi-line inline
# script can ever run there. Run 34924674083 died on line 1 (`set:
# Illegal option -o pipefail` — /usr/bin/sh is dash on hosted runners,
# not bash); after 3bf85778e POSIX-sh-ified the script, run 34946115391
# still died exit 2 with `Syntax error: end of file unexpected (expecting
# "done")` because the `for` line arrived alone at a fresh sh — and
# options/variables would not persist across the per-line processes
# anyway. Invoking a checked-in file by path is a single `sh -c <path>`
# invocation, the exact delivery shape the validation step already uses
# for scripts/release-emulator-validation.sh on the same pinned action.
#
# POSIX sh only (dash): `set -eu`, `[ ]` not `[[ ]]`, no `pipefail` —
# the poll guards its own pipeline with `|| true` and nothing here has a
# load-bearing pipeline status.
#
# Semantics (unchanged since #2675): the action has already awaited its
# own boot timeout before this runs; this bound re-checks the device it
# handed us is genuinely responsive rather than wedged half-booted —
# up to 30 polls of sys.boot_completed, 5s apart.
set -eu

boot_completed=""
for _ in $(seq 1 30); do
  boot_completed="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
  [ "$boot_completed" = "1" ] && break
  sleep 5
done
if [ "$boot_completed" != "1" ]; then
  echo "::error::Emulator boot preflight FAILED: sys.boot_completed='$boot_completed' after the bounded wait — the hosted emulator never produced a working device (run 34759080447 failure shape)."
  exit 1
fi
echo "Emulator boot preflight OK: $("$ADB" devices | tail -n +2 | grep -v '^$' | head -1)"
