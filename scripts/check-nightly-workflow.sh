#!/usr/bin/env bash
# check-nightly-workflow.sh — issue #2252, decision D37 (extended by #2509).
#
# WHAT THIS GUARDS, AND WHY IT MOVED.
#
# An invalid or mis-wired scheduled workflow creates a failed run with zero jobs
# and no log — the #2252 failure — and a scheduled cadence that silently does
# not run is indistinguishable from one that ran green. D37 exists because
# waiving the scheduled fault gate was routine (v0.4.31-v0.4.38, v0.4.45) and
# #1671 traced the #1610 reconnect storm reaching the maintainer to exactly that.
#
# It used to validate .github/workflows/nightly-extensive.yml's guard /
# extensive / fault-verdict job graph. That workflow was deleted with the `:app`
# module every one of its five Gradle invocations targeted, and D37's cadence
# moved to the `app2 journey suite` job of .github/workflows/app2.yml — the lane
# that now runs the toxiproxy network-fault journeys (issue #2474 runs them
# inside one unfiltered instrumentation pass rather than as a separate phase, so
# the job's own conclusion IS the fault verdict; see
# scripts/check-nightly-fault-run.sh, which consumes it).
#
# So this guard now pins the properties D37 actually depends on for that lane:
#   1. the workflow is valid YAML with a job mapping;
#   2. the `app2-journey` job exists and is named what the verdict consumer
#      greps for;
#   3. a `schedule:` trigger carries the 02:17 UTC cron (the cadence itself);
#   4. the journey job is FAIL-CLOSED — no `continue-on-error: true`;
#   5. the journey job has no bypass knob — its `if:` reads no `inputs.`, so no
#      workflow_dispatch input can turn the gate off;
#   6. a SCHEDULED run actually reaches the job — its `if:` excludes only
#      pull_request, never schedule or workflow_dispatch;
#   7. nothing in these groups is ever cancelled. `schedule` gets its own
#      concurrency group (a push can never join it and kill the nightly
#      mid-flight; a cancelled cadence would read as `cancelled`, not
#      `failure`: a bypass by accident, precisely the D37 shape), `queue: max`
#      keeps queued runs waiting FIFO instead of letting GitHub's default
#      pending-run collapse drop them (#2736: five never-started main runs
#      were collapsed within 0-8s of the next run's creation on 2026-09-16),
#      and `cancel-in-progress` stays unset or literal false — GitHub rejects
#      `queue: max` + `cancel-in-progress: true` as a workflow validation
#      error, and the pre-#2736 PR-only form is exactly what let the collapse
#      happen. The pre-D40 `!= 'schedule'` form is pinned out too: it made
#      push runs cancel each other, dropping a push's only validation.
#   8. on.push / on.pull_request do NOT carry paths: / paths-ignore. GitHub's
#      workflow-level path filter is the #2354 required-check footgun AND has
#      been observed to suppress this workflow's schedule: cadence entirely
#      (issue #2509: gh run list --workflow app2.yml --event schedule was empty
#      while tests.yml's schedule the same morning fired). Per-job selection
#      stays in the `changes` job, which already fail-opens on empty/unknown
#      base (what schedule/dispatch pass).
#   9. a schedule/workflow_dispatch run fail-opens every lane: `changes` always
#      runs, its --base expression yields empty on non-push/non-PR, and no
#      fail-open lane's if: skips schedule or workflow_dispatch.
#  10. binding-mutations still runs on schedule OR workflow_dispatch (and on
#      workflow_call if that trigger is introduced).
#  11. scripts/check-nightly-fault-run.sh still identifies this workflow's
#      journey job (default --workflow app2.yml, --job-needle matching .name).
#
# Issue #2739: tests.yml shares app2.yml's concurrency group scheme (own
# never-cancelled schedule group, per-PR and per-ref groups) and therefore the
# same #2736 pending-run collapse hazard, so the check-7 shape (schedule
# special-cased, queue: max, cancel-in-progress unset or literal false) is
# pinned for it too — concurrency only, since the job-graph checks above are
# app2.yml's D37 cadence; tests.yml's own integrity lives in its unit-gate
# wiring guard (scripts/check-unit-gate-wiring.sh) and scripts/test-ci-cadence.sh.
#
# Issue #2825: release-emulator-validation.yml — the D37 release gate itself —
# carried the pre-#2736 shape (`cancel-in-progress: false`, no queue) and was
# covered by neither fix nor by this guard. Three REV runs on `main` were
# collapsed while PENDING on 2026-09-19 (35426896435, 35427717341,
# 35427808592: each `cancelled` with zero jobs; the second died one second
# after the third was created), so the never-cancelled shape is pinned for it
# too. Same two assertions, one difference: REV has no `schedule:` trigger to
# split off — its cadence is the `workflow_run` reaction to tests.yml's
# schedule — so the schedule-group assertion does not apply and the group
# check is skipped for it, deliberately, rather than asserted against a group
# that shares dispatch and workflow_run by design. Concurrency only here as
# well: REV's own job/step integrity is covered by
# scripts/check-release-gate-bypass-absent.sh,
# scripts/check-release-selfheal-wiring.py and
# scripts/test-release-validation-provenance.sh.
#
# Usage:
#   scripts/check-nightly-workflow.sh              # check the real workflow
#   scripts/check-nightly-workflow.sh --self-test  # red/green proof per check

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_WORKFLOW="$ROOT_DIR/.github/workflows/app2.yml"
TESTS_WORKFLOW="$ROOT_DIR/.github/workflows/tests.yml"
RELEASE_WORKFLOW="$ROOT_DIR/.github/workflows/release-emulator-validation.yml"
FAULT_RUN_CONSUMER="$ROOT_DIR/scripts/check-nightly-fault-run.sh"
JOURNEY_JOB="app2-journey"
JOURNEY_JOB_NAME_NEEDLE="app2 journey suite"
JOURNEY_CRON="17 2 * * *"

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

check_workflow() {
  local workflow="$1"
  command -v ruby >/dev/null 2>&1 || {
    fail "ruby is required to parse GitHub Actions YAML"
  }

  ruby - "$workflow" "$JOURNEY_JOB" "$JOURNEY_JOB_NAME_NEEDLE" "$JOURNEY_CRON" <<'RUBY'
require "yaml"

path, job_key, job_needle, cron = ARGV.values_at(0, 1, 2, 3)

begin
  document = YAML.safe_load_file(path, aliases: true)
rescue Psych::Exception => error
  abort "FAIL: #{path} is not valid YAML: #{error.message.lines.first.strip}"
end

abort "FAIL: #{path} must contain a mapping at the document root" unless document.is_a?(Hash)

# Psych's YAML 1.1 loader treats the YAML 1.2 `on` key as boolean true. Accept
# either representation so this validates the document, not the parser's schema.
events = document["on"] || document[true]
abort "FAIL: #{path} has no top-level on: mapping" unless events.is_a?(Hash)

# 3. the cadence itself.
schedule = events["schedule"]
unless schedule.is_a?(Array) && schedule.any? { |e| e.is_a?(Hash) && e["cron"] == cron }
  abort "FAIL: the #{cron} scheduled trigger is missing — D37 requires this lane to run on a cadence, not only on push"
end

# 8. workflow-level path filters suppress the D37 cadence (#2509) and are the
# #2354 required-check footgun. Per-job selection already lives in `changes`.
%w[push pull_request].each do |event_name|
  spec = events[event_name]
  next unless spec.is_a?(Hash)
  filter = if spec.key?("paths")
             "paths"
           elsif spec.key?("paths-ignore")
             "paths-ignore"
           end
  next unless filter
  abort "FAIL: on.#{event_name} carries a #{filter}: filter — GitHub's workflow-level path filter is the #2354 required-check footgun and has been observed to suppress this workflow's schedule: cadence (issue #2509). Per-job selection belongs in the changes job, which already fail-opens on empty/unknown base"
end

unless events.key?("workflow_dispatch")
  abort "FAIL: workflow_dispatch trigger is missing — D37's cadence must be runnable on demand as well as on schedule"
end

jobs = document["jobs"]
abort "FAIL: jobs must be a non-empty mapping" unless jobs.is_a?(Hash) && !jobs.empty?

# 2. the job exists, and is named what the verdict consumer greps for.
journey = jobs[job_key]
abort "FAIL: the #{job_key} job is missing" unless journey.is_a?(Hash)
unless journey["name"].to_s.include?(job_needle)
  abort "FAIL: #{job_key}.name must contain #{job_needle.inspect} — scripts/check-nightly-fault-run.sh identifies the job by that substring, so renaming it silently removes the release gate's only fault signal (got #{journey["name"].inspect})"
end

# 4. fail-closed.
if journey["continue-on-error"] == true
  abort "FAIL: #{job_key} must not set continue-on-error: true — a fault verdict that cannot fail is not a verdict"
end

journey_if = journey["if"].to_s

# 5. no bypass knob.
if journey_if.include?("inputs.")
  abort "FAIL: #{job_key}'s if: reads a workflow input (#{journey_if}) — D37 forbids an off switch for this gate"
end

# 6. a scheduled / dispatched run must reach the job. The if: excludes only
# pull_request; naming schedule or workflow_dispatch is how the cadence skips
# its own job.
unless journey_if.include?("github.event_name != 'pull_request'")
  abort "FAIL: #{job_key}'s if: must exclude only pull_request (got #{journey_if}) — schedule and workflow_dispatch must reach this job"
end
if journey_if.include?("schedule")
  abort "FAIL: #{job_key}'s if: mentions the schedule event (#{journey_if}) — the cadence must not be able to skip its own job"
end
if journey_if.include?("workflow_dispatch")
  abort "FAIL: #{job_key}'s if: mentions workflow_dispatch (#{journey_if}) — an on-demand cadence run must not be able to skip its own job"
end

# 7. nothing in these groups is ever cancelled: the scheduled cadence cannot
#    be killed by an unrelated push (served by the schedule-specific group
#    below), and a queued run must not be collapsed by the next one (#2736:
#    GitHub's default concurrency cancels a group's PENDING run when a newer
#    run joins it, regardless of cancel-in-progress).
concurrency = document["concurrency"]
abort "FAIL: #{path} has no concurrency mapping" unless concurrency.is_a?(Hash)
group = concurrency["group"].to_s
queue = concurrency["queue"].to_s
cancel = concurrency["cancel-in-progress"]
unless group.include?("schedule")
  abort "FAIL: the concurrency group does not special-case schedule (#{group}) — a scheduled run sharing a push's group can be cancelled mid-flight, and a cancelled cadence reads as 'not failed' while proving nothing"
end
unless queue == "max"
  abort "FAIL: concurrency.queue must be max (got #{queue.inspect}) — GitHub's default concurrency cancels a group's PENDING (never-started) run the moment a newer run joins it, regardless of cancel-in-progress (issue #2736: five queued app2 runs on main were collapsed within seconds of the next run's creation on 2026-09-16, each with zero jobs). queue: max keeps up to 100 runs waiting FIFO so a queued run stays a delayed validation, never a lost one (D40)"
end
if cancel && cancel.to_s.strip != "false"
  abort "FAIL: cancel-in-progress must be unset (or the literal false) alongside queue: max (got #{cancel.to_s}) — GitHub rejects queue: max + cancel-in-progress: true as a workflow validation error, and any conditional form either breaks runs outright or re-arms the #2736 pending-run collapse. Nothing in these groups may be cancelled: the schedule keeps its own group (above) and PR heads queue FIFO instead"
end

# 9. a schedule/dispatch run fail-opens every lane.
changes = jobs["changes"]
abort "FAIL: the changes job is missing — schedule/dispatch fail-open every lane through it" unless changes.is_a?(Hash)
if changes.key?("if") && !changes["if"].to_s.strip.empty?
  abort "FAIL: the changes job has an if: (#{changes["if"]}) — it must always run so a cadence can fail-open"
end

steps = changes["steps"]
abort "FAIL: the changes job has no steps" unless steps.is_a?(Array)
select = steps.find { |s| s.is_a?(Hash) && s["run"].to_s.include?("ci-app2-changed-modules.sh") && s["run"].to_s.include?("--base") }
abort "FAIL: the changes job does not invoke ci-app2-changed-modules.sh --base" unless select
select_run = select["run"].to_s
unless select_run.include?("github.event_name == 'pull_request'") &&
       select_run.include?("github.event_name == 'push'") &&
       select_run.match?(/\|\|\s*(''|\"\")/)
  abort "FAIL: the changes job's --base expression does not fail-open on schedule/dispatch (empty-string fallback) — a cadence run would under-select and skip app2-journey"
end

fail_open_jobs = %w[
  hostapi-test
  transport-test
  transport-integration
  portfwd-test
  portfwd-integration
  app2-unit
]
fail_open_jobs.each do |key|
  job = jobs[key]
  abort "FAIL: fail-open lane #{key} is missing — a cadence run must execute the unit/integration lanes, not only the journey" unless job.is_a?(Hash)
  job_if = job["if"].to_s
  if job_if.include?("schedule") || job_if.include?("workflow_dispatch")
    abort "FAIL: #{key}'s if: mentions schedule/workflow_dispatch (#{job_if}) — a cadence run must fail-open this lane, not skip it"
  end
end

# 10. binding-mutations still runs on schedule OR dispatch.
binding = jobs["binding-mutations"]
abort "FAIL: the binding-mutations job is missing" unless binding.is_a?(Hash)
binding_if = binding["if"].to_s
unless binding_if.include?("github.event_name == 'schedule'") &&
       binding_if.include?("github.event_name == 'workflow_dispatch'")
  abort "FAIL: binding-mutations must run on schedule OR workflow_dispatch (got #{binding_if})"
end
if events.key?("workflow_call") && !binding_if.include?("github.event_name == 'workflow_call'")
  abort "FAIL: binding-mutations must also run on workflow_call when that trigger exists (got #{binding_if})"
end

puts "PASS: #{path} is valid YAML, carries the #{cron} cadence, has no workflow-level paths filter, fail-opens every lane on schedule/dispatch, and its #{job_key} job is fail-closed, bypass-free, schedule-reachable and never-cancelled (own schedule group, queue: max)"
RUBY
}

# Issue #2739 (tests.yml), issue #2825 (release-emulator-validation.yml): both
# carry app2.yml's pending-run collapse hazard, so the same never-cancelled
# shape is pinned for them. Only the concurrency mapping is asserted here — the
# cadence/job-graph checks above are app2.yml's; tests.yml's own integrity is
# covered by scripts/check-unit-gate-wiring.sh and scripts/test-ci-cadence.sh,
# REV's by scripts/check-release-gate-bypass-absent.sh and
# scripts/check-release-selfheal-wiring.py.
#
# $2 is the substring the concurrency group must contain so a scheduled run
# lands in its own never-cancelled group. Pass it EMPTY only for a workflow
# with no `schedule:` trigger to split off (REV: its cadence is the
# workflow_run reaction to tests.yml's schedule, and its single per-ref group
# is shared by dispatch and workflow_run on purpose) — the queue/cancel
# assertions, which are what #2736 turns on, always apply.
check_concurrency_shape() {
  local workflow="$1"
  local group_needle="${2:-}"
  command -v ruby >/dev/null 2>&1 || {
    fail "ruby is required to parse GitHub Actions YAML"
  }

  ruby - "$workflow" "$group_needle" <<'RUBY'
require "yaml"

path, group_needle = ARGV.values_at(0, 1)

begin
  document = YAML.safe_load_file(path, aliases: true)
rescue Psych::Exception => error
  abort "FAIL: #{path} is not valid YAML: #{error.message.lines.first.strip}"
end

abort "FAIL: #{path} must contain a mapping at the document root" unless document.is_a?(Hash)

concurrency = document["concurrency"]
abort "FAIL: #{path} has no concurrency mapping" unless concurrency.is_a?(Hash)
group = concurrency["group"].to_s
queue = concurrency["queue"].to_s
cancel = concurrency["cancel-in-progress"]
unless group_needle.empty? || group.include?(group_needle)
  abort "FAIL: #{path}'s concurrency group does not special-case schedule (#{group}) — a scheduled run sharing a push's group can be cancelled mid-flight, and a cancelled cadence reads as 'not failed' while proving nothing"
end
unless queue == "max"
  abort "FAIL: #{path}'s concurrency.queue must be max (got #{queue.inspect}) — GitHub's default concurrency cancels a group's PENDING (never-started) run the moment a newer run joins it, regardless of cancel-in-progress (issue #2736: five queued app2 runs on main were collapsed within seconds of the next run's creation on 2026-09-16, each with zero jobs; tests.yml carried the same latent shape, #2739; release-emulator-validation.yml carried it into the D37 release gate itself and lost three runs to it on 2026-09-19, #2825). queue: max keeps up to 100 runs waiting FIFO so a queued run stays a delayed validation, never a lost one (D40)"
end
if cancel && cancel.to_s.strip != "false"
  abort "FAIL: #{path}'s cancel-in-progress must be unset (or the literal false) alongside queue: max (got #{cancel.to_s}) — GitHub rejects queue: max + cancel-in-progress: true as a workflow validation error, and any conditional form either breaks runs outright or re-arms the #2736 pending-run collapse. Nothing in these groups may be cancelled: a schedule that needs its own group keeps one and the rest queue FIFO instead"
end

group_note = group_needle.empty? ? "" : "own schedule group, "
puts "PASS: #{path} concurrency is never-cancelled (#{group_note}queue: max, cancel-in-progress unset)"
RUBY
}

# 11. the verdict consumer still looks at this workflow's journey job.
check_fault_run_consumer() {
  local fault="${1:-$FAULT_RUN_CONSUMER}"
  if [[ ! -f "$fault" ]]; then
    printf 'FAIL: %s is missing — D37'\''s verdict consumer\n' "$fault" >&2
    return 1
  fi
  if ! grep -qE '^WORKFLOW="app2.yml"$' "$fault"; then
    printf 'FAIL: %s WORKFLOW default is not app2.yml — the verdict consumer would look at the wrong cadence file\n' "$fault" >&2
    return 1
  fi
  if ! grep -qE '^JOB_NEEDLE="app2 journey suite"$' "$fault"; then
    printf 'FAIL: %s JOB_NEEDLE default is not '\''app2 journey suite'\'' — the verdict consumer would miss the journey job\n' "$fault" >&2
    return 1
  fi
  return 0
}

self_test() {
  local temp_dir valid m
  temp_dir="$(mktemp -d "${TMPDIR:-/tmp}/journey-workflow-check.XXXXXX")"
  trap 'rm -rf "$temp_dir"' RETURN

  # The REAL workflow is the known-good control, so the fixture cannot drift
  # away from the job graph it claims to validate.
  valid="$temp_dir/valid.yml"
  cp "$DEFAULT_WORKFLOW" "$valid"
  check_workflow "$valid" >/dev/null || fail "self-test control: the real workflow does not pass its own guard"
  echo "  ok: the shipped workflow is the green control"

  check_fault_run_consumer >/dev/null || fail "self-test control: the real fault-run consumer does not match the cadence workflow"
  echo "  ok: the shipped fault-run consumer points at this cadence"

  # Each mutation removes exactly one pinned property and must be named.
  expect_red() {  # $1 = label, $2 = mutant file, $3 = expected substring
    if check_workflow "$2" >"$temp_dir/out" 2>&1; then
      cat "$temp_dir/out" >&2
      fail "$1: mutant was accepted"
    fi
    grep -qF "$3" "$temp_dir/out" || {
      cat "$temp_dir/out" >&2
      fail "$1: reddened for the wrong reason (wanted: $3)"
    }
    echo "  ok: $1"
  }

  expect_consumer_red() {  # $1 = label, $2 = mutant file, $3 = expected substring
    if check_fault_run_consumer "$2" >"$temp_dir/out" 2>&1; then
      cat "$temp_dir/out" >&2
      fail "$1: mutant was accepted"
    fi
    grep -qF "$3" "$temp_dir/out" || {
      cat "$temp_dir/out" >&2
      fail "$1: reddened for the wrong reason (wanted: $3)"
    }
    echo "  ok: $1"
  }

  m="$temp_dir/syntax.yml"; cp "$valid" "$m"
  printf '\n  bad: [unclosed\n' >> "$m"
  expect_red "malformed YAML is rejected" "$m" "not valid YAML"

  m="$temp_dir/nocron.yml"; cp "$valid" "$m"
  sed -i 's/    - cron: "17 2 \* \* \*"/    - cron: "17 2 * * 0"/' "$m"
  expect_red "a moved/removed cadence is rejected" "$m" "scheduled trigger is missing"

  m="$temp_dir/renamed.yml"; cp "$valid" "$m"
  sed -i 's/^    name: app2 journey suite.*$/    name: something else entirely/' "$m"
  expect_red "renaming the job away from the verdict needle is rejected" "$m" "identifies the job by that substring"

  m="$temp_dir/soft.yml"; cp "$valid" "$m"
  sed -i "/^  app2-journey:/a\\    continue-on-error: true" "$m"
  expect_red "a continue-on-error journey job is rejected" "$m" "not a verdict"

  m="$temp_dir/bypass.yml"; cp "$valid" "$m"
  sed -i "s|^    if: \${{ !cancelled() && github.event_name != 'pull_request'|    if: \${{ !cancelled() \&\& inputs.skip_journeys != true \&\& github.event_name != 'pull_request'|" "$m"
  expect_red "a workflow-input bypass is rejected" "$m" "D37 forbids an off switch"

  m="$temp_dir/cancellable.yml"; cp "$valid" "$m"
  sed -i "/^  queue: max$/a\\  cancel-in-progress: true" "$m"
  expect_red "a schedule-cancellable lane is rejected" "$m" "cancel-in-progress must be unset"

  # The pre-D40 push-cancelling form, and the D40-era PR-only conditional:
  # both are cancel-in-progress expressions, and any conditional re-arms
  # either the #2736 pending-run collapse or a workflow validation error.
  m="$temp_dir/pushcancel.yml"; cp "$valid" "$m"
  sed -i "/^  queue: max$/a\\  cancel-in-progress: \${{ github.event_name != 'schedule' }}" "$m"
  expect_red "a cancel-in-progress expression is rejected" "$m" "cancel-in-progress must be unset"

  # The exact regression that hit main on 2026-09-16 (#2736): a group without
  # queue: max lets GitHub's default pending-run collapse drop every queued
  # run the next one replaces — five never-started main runs died that way.
  m="$temp_dir/noqueue.yml"; cp "$valid" "$m"
  sed -i "/^  queue: max$/d" "$m"
  expect_red "a queue-less group that collapses pending runs is rejected" "$m" "concurrency.queue must be max"

  m="$temp_dir/sharedgroup.yml"; cp "$valid" "$m"
  sed -i "s|^  group: .*$|  group: \${{ github.workflow }}-\${{ github.ref }}|" "$m"
  expect_red "a shared concurrency group is rejected" "$m" "does not special-case schedule"

  # Issue #2509: a workflow-level paths: filter is the hole that let the
  # schedule never fire while this guard stayed green. Re-adding it must fail.
  m="$temp_dir/pushpaths.yml"; cp "$valid" "$m"
  sed -i '/^  push:$/a\    paths: [app2/**]' "$m"
  expect_red "on.push.paths is rejected" "$m" "on.push carries a paths: filter"

  m="$temp_dir/prpaths.yml"; cp "$valid" "$m"
  sed -i '/^  pull_request:$/a\    paths: [app2/**]' "$m"
  expect_red "on.pull_request.paths is rejected" "$m" "on.pull_request carries a paths: filter"

  m="$temp_dir/pushignore.yml"; cp "$valid" "$m"
  sed -i '/^  push:$/a\    paths-ignore: ["**/*.md"]' "$m"
  expect_red "on.push.paths-ignore is rejected" "$m" "on.push carries a paths-ignore: filter"

  m="$temp_dir/nodispatch.yml"; cp "$valid" "$m"
  sed -i '/^  workflow_dispatch:$/d' "$m"
  expect_red "a missing workflow_dispatch trigger is rejected" "$m" "workflow_dispatch trigger is missing"

  m="$temp_dir/skipsched.yml"; cp "$valid" "$m"
  sed -i "s|github.event_name != 'pull_request'|github.event_name != 'pull_request' \&\& github.event_name != 'schedule'|" "$m"
  expect_red "a journey if: that skips schedule is rejected" "$m" "mentions the schedule event"

  m="$temp_dir/skipdispatch.yml"; cp "$valid" "$m"
  sed -i "s|github.event_name != 'pull_request'|github.event_name != 'pull_request' \&\& github.event_name != 'workflow_dispatch'|" "$m"
  expect_red "a journey if: that skips workflow_dispatch is rejected" "$m" "mentions workflow_dispatch"

  m="$temp_dir/changesif.yml"; cp "$valid" "$m"
  sed -i "/^  changes:/a\\    if: \${{ github.event_name == 'push' }}" "$m"
  expect_red "a changes job that can skip the cadence is rejected" "$m" "the changes job has an if:"

  m="$temp_dir/base.yml"; cp "$valid" "$m"
  sed -i "s/|| ''/|| github.sha/" "$m"
  expect_red "a --base that does not fail-open on schedule is rejected" "$m" "does not fail-open on schedule/dispatch"

  m="$temp_dir/hostapiskip.yml"; cp "$valid" "$m"
  sed -i "s|needs.changes.outputs.hostapi == 'true'|github.event_name != 'schedule' \&\& needs.changes.outputs.hostapi == 'true'|" "$m"
  expect_red "a fail-open unit lane that skips schedule is rejected" "$m" "hostapi-test's if: mentions schedule/workflow_dispatch"

  m="$temp_dir/bindnosched.yml"; cp "$valid" "$m"
  sed -i "s#github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'#github.event_name == 'workflow_dispatch'#" "$m"
  expect_red "binding-mutations that skip schedule is rejected" "$m" "binding-mutations must run on schedule OR workflow_dispatch"

  m="$temp_dir/bindnodisp.yml"; cp "$valid" "$m"
  sed -i "s#github.event_name == 'schedule' || github.event_name == 'workflow_dispatch'#github.event_name == 'schedule'#" "$m"
  expect_red "binding-mutations that skip dispatch is rejected" "$m" "binding-mutations must run on schedule OR workflow_dispatch"

  m="$temp_dir/fault-workflow.sh"; cp "$FAULT_RUN_CONSUMER" "$m"
  sed -i 's/^WORKFLOW="app2.yml"/WORKFLOW="tests.yml"/' "$m"
  expect_consumer_red "a fault-run consumer pointed at the wrong workflow is rejected" "$m" "wrong cadence file"

  m="$temp_dir/fault-needle.sh"; cp "$FAULT_RUN_CONSUMER" "$m"
  sed -i 's/^JOB_NEEDLE="app2 journey suite"/JOB_NEEDLE="something else"/' "$m"
  expect_consumer_red "a fault-run consumer with the wrong job needle is rejected" "$m" "would miss the journey job"

  # Issue #2739: the same never-cancelled shape is pinned for tests.yml. The
  # green control is the real workflow. The noqueue mutant is the exact #2736
  # regression shape (GitHub collapses every queued run), the D40-era mutant
  # re-arms the pre-#2739 PR-only conditional, and the cancel-in-progress:
  # true mutant is the combination GitHub rejects as a workflow validation error.
  # $4 is the group needle (empty for a workflow with no schedule: trigger).
  expect_concurrency_red() {  # $1 = label, $2 = mutant file, $3 = expected substring, $4 = group needle
    if check_concurrency_shape "$2" "${4:-}" >"$temp_dir/out" 2>&1; then
      cat "$temp_dir/out" >&2
      fail "$1: mutant was accepted"
    fi
    grep -qF "$3" "$temp_dir/out" || {
      cat "$temp_dir/out" >&2
      fail "$1: reddened for the wrong reason (wanted: $3)"
    }
    echo "  ok: $1"
  }

  t="$temp_dir/tests-valid.yml"; cp "$TESTS_WORKFLOW" "$t"
  check_concurrency_shape "$t" sched >/dev/null || fail "self-test control: tests.yml does not pass its own concurrency guard"
  echo "  ok: the shipped tests.yml is the green control"

  m="$temp_dir/tests-noqueue.yml"; cp "$TESTS_WORKFLOW" "$m"
  sed -i "/^  queue: max$/d" "$m"
  expect_concurrency_red "a queue-less tests.yml group that collapses pending runs is rejected" "$m" "concurrency.queue must be max" sched

  m="$temp_dir/tests-d40cancel.yml"; cp "$TESTS_WORKFLOW" "$m"
  sed -i "/^  queue: max$/a\\  cancel-in-progress: \${{ github.event_name=='pull_request' }}" "$m"
  expect_concurrency_red "the D40-era PR-only cancel-in-progress form is rejected" "$m" "cancel-in-progress must be unset" sched

  m="$temp_dir/tests-cictrue.yml"; cp "$TESTS_WORKFLOW" "$m"
  sed -i "/^  queue: max$/a\\  cancel-in-progress: true" "$m"
  expect_concurrency_red "queue: max + cancel-in-progress: true is rejected" "$m" "cancel-in-progress must be unset" sched

  m="$temp_dir/tests-sharedgroup.yml"; cp "$TESTS_WORKFLOW" "$m"
  sed -i "s|^  group: .*$|  group: \${{ github.workflow }}-\${{ github.ref }}|" "$m"
  expect_concurrency_red "a shared tests.yml concurrency group is rejected" "$m" "does not special-case schedule" sched

  # Issue #2825: the same shape for the D37 release gate itself. No group
  # needle — REV has no schedule: trigger to split off (see
  # check_concurrency_shape) — so the queue/cancel pins ARE the whole check
  # for it, and each one must be able to fail on its own bytes. The precancel
  # mutant is the pre-#2825 block verbatim: the shape that let 35426896435 /
  # 35427717341 / 35427808592 be collapsed while pending, each with zero jobs.
  r="$temp_dir/release-valid.yml"; cp "$RELEASE_WORKFLOW" "$r"
  check_concurrency_shape "$r" >/dev/null || fail "self-test control: release-emulator-validation.yml does not pass its own concurrency guard"
  echo "  ok: the shipped release-emulator-validation.yml is the green control"

  m="$temp_dir/release-noqueue.yml"; cp "$RELEASE_WORKFLOW" "$m"
  sed -i "/^  queue: max$/d" "$m"
  expect_concurrency_red "a queue-less release-emulator-validation.yml group that collapses pending runs is rejected" "$m" "concurrency.queue must be max"

  m="$temp_dir/release-precancel.yml"; cp "$RELEASE_WORKFLOW" "$m"
  sed -i "s/^  queue: max$/  cancel-in-progress: false/" "$m"
  expect_concurrency_red "the pre-#2825 release-emulator-validation.yml shape (cancel-in-progress: false, no queue) is rejected" "$m" "concurrency.queue must be max"

  m="$temp_dir/release-cictrue.yml"; cp "$RELEASE_WORKFLOW" "$m"
  sed -i "/^  queue: max$/a\\  cancel-in-progress: true" "$m"
  expect_concurrency_red "queue: max + cancel-in-progress: true in release-emulator-validation.yml is rejected" "$m" "cancel-in-progress must be unset"

  m="$temp_dir/release-condcancel.yml"; cp "$RELEASE_WORKFLOW" "$m"
  sed -i "/^  queue: max$/a\\  cancel-in-progress: \${{ github.event_name=='workflow_dispatch' }}" "$m"
  expect_concurrency_red "a conditional cancel-in-progress in release-emulator-validation.yml is rejected" "$m" "cancel-in-progress must be unset"

  echo "PASS: check-nightly-workflow self-test."
}

case "${1:-}" in
  --self-test) self_test ;;
  "")
    check_workflow "$DEFAULT_WORKFLOW"
    check_fault_run_consumer || exit 1
    check_concurrency_shape "$TESTS_WORKFLOW" sched
    check_concurrency_shape "$RELEASE_WORKFLOW"
    ;;
  *) echo "unknown argument: $1" >&2; echo "usage: $0 [--self-test]" >&2; exit 1 ;;
esac
