#!/usr/bin/env bash
# Issue #2474 — run app2's WHOLE instrumented journey set on one emulator.
#
# This is the script `.github/workflows/app2.yml`'s `app2-journey` job hands to
# reactivecircus/android-emulator-runner: by the time it starts, the emulator is
# booted and the Docker `agents` (2222) + `network-fault-proxy` (2228 / API
# 8474) fixtures are up.
#
# WHY ONE UNFILTERED RUN, NOT A CLASS LIST OR A PER-CLASS MATRIX
#
# The old module's `scripts/ci-journey-suite.sh` iterates an explicit FQCN
# registry, one `connectedDebugAndroidTest` invocation per class. That shape
# buys sharding and per-class retry, and it costs the ONE property app2's set
# most needs today: every journey runs in a FRESH instrumentation process, so
# any state one journey leaks into the next is structurally invisible. Running
# all 11 classes together in a single instrumentation run is exactly how issue
# #2477's cross-journey pollution was found; a per-class lane would have been
# green through it. So: no `-Pandroid.testInstrumentationRunnerArguments.class`
# filter, ever, and the self-test below asserts that no filter can creep back
# in. Revisit only when app2's suite is large enough to need sharding — and then
# shard so each shard still runs its classes in ONE process.
#
# EXIT-CODE DISCIPLINE
#
# The gradle invocation is piped through `tee`, which is exactly the shape
# docs/ci-pitfalls.md catalogues as exit-code laundering. `run_suite` reads
# PIPESTATUS and returns gradle's own rc (falling back to tee's, so a broken
# pipe cannot pass either). `--self-test` drives that red->green with a stub
# gradle, no emulator required.
#
# PRIMARY CAUSE (issue #2830)
#
# Run 35435668085 died twice on one tree with 14 identical
# `the window never took focus …` assertion failures across 8 journeys. The
# actual cause was one line nobody had opened yet, in the logcat ARTIFACT:
# `ANR in com.google.android.apps.nexuslauncher … Input dispatching timed out
# (Application does not have a focused window)` on a runner at load 10.52. So
# the two readings that identify a wedged device — a system ANR in logcat, and
# the harness's own `INFRA: device window-focus outage` verdict (see
# app2/src/androidTest/.../connect/DeviceFocus.kt) — are scanned here and
# written to $ARTIFACT_DIR/primary-cause.md. `--report-primary-cause` then puts
# that block at the top of the JOB SUMMARY, where the on-call reads it without
# downloading anything. Absence of evidence writes nothing: a lane that failed
# for an ordinary reason must not grow an infra-shaped excuse.
#
# USAGE
#   scripts/ci-app2-journey-suite.sh            # the CI entry point
#   scripts/ci-app2-journey-suite.sh --report-primary-cause
#   scripts/ci-app2-journey-suite.sh --self-test
#
# Overridable for the self-test and for a local reproduction:
#   POCKETSHELL_APP2_JOURNEY_GRADLE     gradle launcher (default ./gradlew)
#   POCKETSHELL_APP2_JOURNEY_ARTIFACTS  artifact root (default artifacts/app2-journey)
#   POCKETSHELL_APP2_JOURNEY_APP_ID     applicationId whose external files dir
#                                       holds the journey screenshots
#   POCKETSHELL_APP2_JOURNEY_RESULTS    androidTest result XML root, scanned for
#                                       the harness's outage verdict
#   POCKETSHELL_APP2_JOURNEY_GRADLE_EXTRA_ARGS
#                                       extra gradle args, space-separated (e.g.
#                                       -Pandroid.testInstrumentationRunnerArguments.agentsPort=2474
#                                       for a local run against a private fixture)

set -uo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

# This script's own absolute path, resolved BEFORE anything changes the working
# directory (`run_suite` cds to $ROOT_DIR). The self-test drives the real CLI
# route by exec'ing it (check 10), and `${BASH_SOURCE[0]}` alone is whatever
# argv the caller typed — `./ci-app2-journey-suite.sh --self-test` from
# scripts/ would exec a path that no longer resolves and score a 127 as a
# missing route.
SELF="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/$(basename -- "${BASH_SOURCE[0]}")"

GRADLE_CMD="${POCKETSHELL_APP2_JOURNEY_GRADLE:-./gradlew}"
ARTIFACT_DIR="${POCKETSHELL_APP2_JOURNEY_ARTIFACTS:-artifacts/app2-journey}"
APP_ID="${POCKETSHELL_APP2_JOURNEY_APP_ID:-com.pocketshell.app}"
RESULTS_DIR="${POCKETSHELL_APP2_JOURNEY_RESULTS:-app2/build/outputs/androidTest-results/connected/debug}"
ADB="${ADB:-adb}"

# The harness's own environment verdict, byte-identical to
# DEVICE_FOCUS_OUTAGE_MARKER in app2/src/androidTest/.../connect/DeviceFocus.kt.
# One string, so "did the lane die of a device wedge?" is one grep across the
# test XML, the gradle log and the job summary.
#
# It is a COPY, and the self-test (check 9, issue #2833) reads the Kotlin const
# and compares — a drift would silently cost the in-test half of the scan.
OUTAGE_MARKER="INFRA: device window-focus outage"

# Reads DEVICE_FOCUS_OUTAGE_MARKER out of a DeviceFocus.kt. Prints the string
# literal (empty if the const is not there), so the self-test can compare the
# two copies instead of trusting the comment above.
read_kotlin_outage_marker() {
  sed -n 's/^const val DEVICE_FOCUS_OUTAGE_MARKER: String = "\(.*\)"$/\1/p' "$1" 2>/dev/null |
    head -n 1
}

# The one task this lane runs. Kept in a named variable so the self-test can
# assert it, and so the "no class filter" property is checkable rather than a
# comment.
JOURNEY_TASK=":app2:connectedDebugAndroidTest"

gradle_args() {
  printf '%s\n' \
    "$JOURNEY_TASK" \
    "--no-build-cache" \
    "--no-daemon" \
    "--stacktrace" \
    "--console=plain" \
    "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
  local extra
  for extra in ${POCKETSHELL_APP2_JOURNEY_GRADLE_EXTRA_ARGS:-}; do
    printf '%s\n' "$extra"
  done
}

# Runs the suite, tees the output to $1, and returns GRADLE's exit code.
run_suite() {
  local log="$1"
  local -a args=()
  local line
  while IFS= read -r line; do args+=("$line"); done < <(gradle_args)

  "$GRADLE_CMD" "${args[@]}" 2>&1 | tee "$log"
  local -a status=("${PIPESTATUS[@]}")
  local gradle_rc="${status[0]:-1}"
  local tee_rc="${status[1]:-1}"
  if [[ "$gradle_rc" -ne 0 ]]; then
    return "$gradle_rc"
  fi
  if [[ "$tee_rc" -ne 0 ]]; then
    echo "::error title=app2 journey suite::gradle succeeded but tee failed (rc=$tee_rc); treating the run as failed rather than trusting a truncated log." >&2
    return "$tee_rc"
  fi
  return 0
}

start_logcat() {
  "$ADB" logcat -c >/dev/null 2>&1 || true
  "$ADB" logcat -v threadtime > "$ARTIFACT_DIR/logcat.txt" 2>&1 &
  echo $!
}

# ---------------------------------------------------------------------------
# Primary cause (issue #2830)
# ---------------------------------------------------------------------------

# The `ANR in …` banner plus the lines that explain it. `Reason:` says whether
# input dispatch timed out, `Load:`/`avg10=` say whether the runner was starved
# — the two facts that turn "an ANR happened" into a retry decision.
anr_evidence() {
  local logcat="$ARTIFACT_DIR/logcat.txt"
  [[ -r "$logcat" ]] || return 0
  grep -aE 'ANR in |Reason: Input dispatching timed out|Load: |avg10=' "$logcat" 2>/dev/null |
    head -n 20
}

# The harness's own verdict, from wherever it surfaced: gradle's console echo of
# the failure, or the JUnit XML the run left behind.
outage_verdict() {
  local hits=""
  if [[ -r "$ARTIFACT_DIR/gradle.log" ]]; then
    hits="$(grep -aF "$OUTAGE_MARKER" "$ARTIFACT_DIR/gradle.log" 2>/dev/null | head -n 3)"
  fi
  if [[ -z "$hits" && -d "$RESULTS_DIR" ]]; then
    hits="$(grep -arhF "$OUTAGE_MARKER" "$RESULTS_DIR" 2>/dev/null | head -n 3)"
  fi
  printf '%s' "$hits"
}

# Writes $ARTIFACT_DIR/primary-cause.md and returns 0 when this run carries
# device-wedge evidence; returns 1 (writing nothing) when it does not.
detect_primary_cause() {
  local anr verdict
  anr="$(anr_evidence)"
  verdict="$(outage_verdict)"
  # An `ANR in ` banner, not merely a Load: line, is what makes this a wedge.
  if ! printf '%s' "$anr" | grep -q 'ANR in '; then
    anr=""
  fi
  if [[ -z "$anr" && -z "$verdict" ]]; then
    rm -f "$ARTIFACT_DIR/primary-cause.md"
    return 1
  fi

  mkdir -p "$ARTIFACT_DIR"
  {
    echo "## app2-journey PRIMARY CAUSE: the device wedged, not the product"
    echo
    echo "This run's own device evidence says the emulator — not the tree under test —"
    echo "is why window-sensitive journeys could not start. **Rerun the lane**; a"
    echo "window-focus outage is an environment failure (issue #2830)."
    echo
    if [[ -n "$verdict" ]]; then
      echo "### The journey harness diagnosed it in-test"
      echo
      echo '```'
      printf '%s\n' "$verdict"
      echo '```'
      echo
    fi
    if [[ -n "$anr" ]]; then
      echo "### ANR on this device during the run (artifacts/app2-journey/logcat.txt)"
      echo
      echo '```'
      printf '%s\n' "$anr"
      echo '```'
      echo
    fi
    echo "Full evidence: \`artifacts/app2-journey/logcat.txt\`,"
    echo "\`artifacts/app2-journey/gradle.log\`, \`app2/build/reports/androidTests/\`."
  } > "$ARTIFACT_DIR/primary-cause.md"
  return 0
}

# The `--report-primary-cause` entry point: puts the block at the top of the job
# summary. NEVER fails the job — it is a reporting step, and a reporting step
# that reddens a run would hide the failure it exists to explain.
report_primary_cause() {
  if ! detect_primary_cause; then
    echo "app2 journey: no device-wedge evidence in $ARTIFACT_DIR — nothing to report as a primary cause."
    return 0
  fi
  cat "$ARTIFACT_DIR/primary-cause.md"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    cat "$ARTIFACT_DIR/primary-cause.md" >> "$GITHUB_STEP_SUMMARY"
  fi
  return 0
}

collect_device_evidence() {
  # Journey screenshots (JourneyScreenshots.capture) land in the app's external
  # files dir. Best-effort: absent screenshots must never redden a green suite.
  "$ADB" pull "/sdcard/Android/data/$APP_ID/files" "$ARTIFACT_DIR/screenshots" \
    >/dev/null 2>&1 || true
  "$ADB" shell dumpsys meminfo > "$ARTIFACT_DIR/meminfo.txt" 2>&1 || true
}

main() {
  cd "$ROOT_DIR" || exit 1
  mkdir -p "$ARTIFACT_DIR"

  echo "app2 journey lane: running the FULL suite in ONE instrumentation run (issue #2474)"
  echo "gradle command: $GRADLE_CMD"
  gradle_args | sed 's/^/  arg: /'

  local logcat_pid=""
  logcat_pid="$(start_logcat)"

  run_suite "$ARTIFACT_DIR/gradle.log"
  local rc=$?

  if [[ -n "$logcat_pid" ]]; then
    kill "$logcat_pid" >/dev/null 2>&1 || true
    wait "$logcat_pid" 2>/dev/null || true
  fi
  collect_device_evidence

  if [[ "$rc" -ne 0 ]]; then
    # Issue #2830: say WHY first when the device itself is the reason, so the
    # `::error` an on-call sees in the log is the cause rather than the symptom.
    if detect_primary_cause; then
      echo "::error title=app2 journey: the DEVICE wedged::$OUTAGE_MARKER / system ANR observed on this run — window-sensitive journeys could not start. This is an environment failure; rerun the lane (issue #2830). Details in the job summary and $ARTIFACT_DIR/primary-cause.md." >&2
      cat "$ARTIFACT_DIR/primary-cause.md"
    fi
    echo "::error title=app2 journey suite::$JOURNEY_TASK failed (rc=$rc). Reports: app2/build/reports/androidTests/connected/, raw log: $ARTIFACT_DIR/gradle.log, device log: $ARTIFACT_DIR/logcat.txt" >&2
  fi
  return "$rc"
}

self_test() {
  local failures=0
  local checks=0

  check() {
    checks=$((checks + 1))
    if [[ "$2" != "$3" ]]; then
      echo "FAIL $1: expected '$3', got '$2'" >&2
      failures=$((failures + 1))
    fi
  }

  local tmp
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN

  # 1. A failing gradle must fail the script — the tee-laundering trap. `false`
  #    exits 1 and prints nothing, so a script that returned tee's rc (0) would
  #    pass here.
  GRADLE_CMD="false" run_suite "$tmp/red.log"
  check "failing gradle propagates" "$?" "1"

  # 2. ...and a green one must still pass, so check 1 is not vacuous.
  GRADLE_CMD="true" run_suite "$tmp/green.log"
  check "passing gradle passes" "$?" "0"

  # 3. Gradle's own exit code, not a flattened 1, reaches the caller.
  cat > "$tmp/gradle-42" <<'STUB'
#!/usr/bin/env bash
exit 42
STUB
  chmod +x "$tmp/gradle-42"
  GRADLE_CMD="$tmp/gradle-42" run_suite "$tmp/rc42.log"
  check "gradle rc is preserved" "$?" "42"

  # 4. The task really is app2's connected suite.
  check "task" "$(gradle_args | head -n 1)" ":app2:connectedDebugAndroidTest"

  # 5. THE load-bearing property (issue #2477): no class filter, so all 11
  #    journeys share one instrumentation process and cross-journey state
  #    pollution is observable. Checked against the assembled argv AND the
  #    script source, so neither a code path nor a copy-paste can smuggle one in.
  local filtered=no
  if gradle_args | grep -q 'testInstrumentationRunnerArguments\.class'; then
    filtered=yes
  fi
  check "no class filter in argv" "$filtered" "no"

  # Executable lines only: everything above `self_test() {`, minus comments —
  # the header comment above deliberately NAMES the forbidden flag so a reader
  # knows what is banned and why, and that must not trip its own guard.
  local in_source=no
  if sed -n '1,/^self_test() {/p' "${BASH_SOURCE[0]}" |
    grep -v '^[[:space:]]*#' |
    grep -q 'testInstrumentationRunnerArguments\.class'; then
    in_source=yes
  fi
  check "no class filter in source" "$in_source" "no"

  # ...and that scan is not vacuous: the same scan over a copy WITH a filter
  # line spliced in must find it.
  cp "${BASH_SOURCE[0]}" "$tmp/mutant.sh"
  sed -i '1a MUTANT_ARG="-Pandroid.testInstrumentationRunnerArguments.class=Foo"' "$tmp/mutant.sh"
  local mutant_caught=no
  if sed -n '1,/^self_test() {/p' "$tmp/mutant.sh" |
    grep -v '^[[:space:]]*#' |
    grep -q 'testInstrumentationRunnerArguments\.class'; then
    mutant_caught=yes
  fi
  check "source scan catches a spliced-in filter" "$mutant_caught" "yes"

  # 6. A caller-supplied extra arg is forwarded (the local-fixture-port path).
  local forwarded=no
  if POCKETSHELL_APP2_JOURNEY_GRADLE_EXTRA_ARGS="-PsomeFlag=1" gradle_args |
    grep -qx -- "-PsomeFlag=1"; then
    forwarded=yes
  fi
  check "extra args are forwarded" "$forwarded" "yes"

  # 7. run_suite is not the entry point CI calls — `main` is, and it does work
  #    AFTER the suite (killing logcat, pulling screenshots) that could swallow
  #    the failure. Drive the real entry point end to end with a stub gradle and
  #    a stub adb, both directions.
  local prev_artifacts="$ARTIFACT_DIR" prev_adb="$ADB"
  ARTIFACT_DIR="$tmp/main-red"
  ADB="/bin/true"
  GRADLE_CMD="$tmp/gradle-42" main >/dev/null 2>&1
  check "main propagates a failing suite" "$?" "42"
  ARTIFACT_DIR="$tmp/main-green"
  GRADLE_CMD="true" main >/dev/null 2>&1
  check "main passes a green suite" "$?" "0"
  ARTIFACT_DIR="$prev_artifacts"
  ADB="$prev_adb"

  # 8. PRIMARY CAUSE (issue #2830). The on-call's question is "is this the tree
  #    or the device?", and the answer used to live only in a logcat artifact.
  #    Driven through the real entry points with a stub `adb` that replays a
  #    device log, so the scan, the file and the summary append are all real.
  local prev_results="$RESULTS_DIR"
  cat > "$tmp/adb-replay" <<'STUB'
#!/usr/bin/env bash
# Stands in for `adb logcat -v threadtime`, which main redirects into logcat.txt.
if [[ "${1:-}" == "logcat" && "${2:-}" == "-v" ]]; then
  cat "$REPLAY_LOGCAT"
fi
exit 0
STUB
  chmod +x "$tmp/adb-replay"

  # A gradle stub that lasts long enough for that log to be DELIVERED. `main`
  # backgrounds the stub adb, runs gradle, then kills it; the real suite runs
  # for minutes while `adb logcat` streams, but a stub that exits instantly can
  # be killed before its `cat` has run at all, leaving an empty logcat.txt and
  # reddening 8a with "an ANR run writes primary-cause.md" — a self-test flake
  # (measured ~1 run in 20 on an idle box), not a script defect. The replay is
  # one `cat` of a few lines, so a non-empty file is a complete one.
  cat > "$tmp/gradle-42-wait" <<'STUB'
#!/usr/bin/env bash
deadline=$((SECONDS + 10))
while [[ ! -s "${WAIT_FOR_LOGCAT:-}" && "$SECONDS" -lt "$deadline" ]]; do
  sleep 0.05
done
exit 42
STUB
  chmod +x "$tmp/gradle-42-wait"

  # The five lines run 35435668085 actually logged, plus one that must NOT be
  # mistaken for them.
  cat > "$tmp/anr-logcat.txt" <<'LOGCAT'
09-19 11:24:11.100  1234  1250 I ActivityManager: Start proc for com.pocketshell.app
09-19 11:24:12.205  1234  1250 E ActivityManager: ANR in com.google.android.apps.nexuslauncher (com.google.android.apps.nexuslauncher/.NexusLauncherActivity)
09-19 11:24:12.205  1234  1250 E ActivityManager: Reason: Input dispatching timed out (Application does not have a focused window).
09-19 11:24:12.205  1234  1250 E ActivityManager: Load: 10.52 / 2.4 / 0.79
09-19 11:24:12.205  1234  1250 E ActivityManager: ----- Output from /proc/pressure/cpu -----
09-19 11:24:12.205  1234  1250 E ActivityManager: some avg10=77.71 avg60=30.47 avg300=7.60
LOGCAT
  cat > "$tmp/quiet-logcat.txt" <<'LOGCAT'
09-19 11:24:11.100  1234  1250 I ActivityManager: Start proc for com.pocketshell.app
09-19 11:24:31.900  1234  1250 I ActivityManager: Displayed com.pocketshell.app/.MainActivity
LOGCAT

  RESULTS_DIR="$tmp/no-results"

  # 8a. A failing suite on a device that ANR'd: main writes the block and says
  #     so on the annotation channel, and still returns gradle's rc.
  ARTIFACT_DIR="$tmp/cause-anr"
  ADB="$tmp/adb-replay"
  REPLAY_LOGCAT="$tmp/anr-logcat.txt" WAIT_FOR_LOGCAT="$ARTIFACT_DIR/logcat.txt" \
    GRADLE_CMD="$tmp/gradle-42-wait" main > "$tmp/cause-anr.out" 2> "$tmp/cause-anr.err"
  check "main still propagates rc with a primary cause" "$?" "42"
  local anr_block="$tmp/cause-anr/primary-cause.md"
  local wrote_block=no
  [[ -r "$anr_block" ]] && wrote_block=yes
  check "an ANR run writes primary-cause.md" "$wrote_block" "yes"
  local names_anr=no
  grep -q 'ANR in com.google.android.apps.nexuslauncher' "$anr_block" 2>/dev/null && names_anr=yes
  check "the block quotes the ANR banner" "$names_anr" "yes"
  local names_pressure=no
  grep -q 'avg10=77.71' "$anr_block" 2>/dev/null && names_pressure=yes
  check "the block quotes the CPU-pressure line" "$names_pressure" "yes"
  local says_rerun=no
  grep -q 'Rerun the lane' "$anr_block" 2>/dev/null && says_rerun=yes
  check "the block states the action" "$says_rerun" "yes"
  local annotated=no
  grep -q '::error title=app2 journey: the DEVICE wedged::' "$tmp/cause-anr.err" && annotated=yes
  check "the device wedge is annotated before the generic failure" "$annotated" "yes"
  local ignores_noise=no
  grep -q 'Start proc for' "$anr_block" 2>/dev/null || ignores_noise=yes
  check "unrelated ActivityManager lines are not quoted as evidence" "$ignores_noise" "yes"

  # 8b. ...and a failing suite on a HEALTHY device writes nothing, so 8a is not
  #     a block this script emits on every red run.
  ARTIFACT_DIR="$tmp/cause-quiet"
  REPLAY_LOGCAT="$tmp/quiet-logcat.txt" WAIT_FOR_LOGCAT="$ARTIFACT_DIR/logcat.txt" \
    GRADLE_CMD="$tmp/gradle-42-wait" main >/dev/null 2>"$tmp/cause-quiet.err"
  check "an ordinary red run propagates rc" "$?" "42"
  local quiet_block=no
  [[ -r "$tmp/cause-quiet/primary-cause.md" ]] && quiet_block=yes
  check "an ordinary red run writes no primary cause" "$quiet_block" "no"
  local quiet_annotation=no
  grep -q 'the DEVICE wedged' "$tmp/cause-quiet.err" && quiet_annotation=yes
  check "an ordinary red run is not annotated as a device wedge" "$quiet_annotation" "no"

  # 8c. The harness's own in-test verdict is evidence on its own — a wedge whose
  #     ANR aged out of the log buffer still reports as one.
  ARTIFACT_DIR="$tmp/cause-verdict"
  mkdir -p "$ARTIFACT_DIR"
  printf 'com.pocketshell.next.terminal.J03AttachAndTypeJourney > attach FAILED\n    %s — the DEVICE never granted window focus\n' \
    "$OUTAGE_MARKER" > "$ARTIFACT_DIR/gradle.log"
  cp "$tmp/quiet-logcat.txt" "$ARTIFACT_DIR/logcat.txt"
  detect_primary_cause
  check "the in-test outage verdict alone is a primary cause" "$?" "0"
  local names_verdict=no
  grep -q 'diagnosed it in-test' "$ARTIFACT_DIR/primary-cause.md" 2>/dev/null && names_verdict=yes
  check "the block names the harness verdict" "$names_verdict" "yes"

  # 8d. --report-primary-cause appends to the JOB SUMMARY and never reddens the
  #     job, with evidence or without it. This is the half app2.yml calls.
  local summary="$tmp/step-summary.md"
  : > "$summary"
  GITHUB_STEP_SUMMARY="$summary" report_primary_cause >/dev/null 2>&1
  check "--report-primary-cause exits 0 with evidence" "$?" "0"
  local summarised=no
  grep -q 'PRIMARY CAUSE: the device wedged' "$summary" && summarised=yes
  check "the job summary carries the primary cause" "$summarised" "yes"

  ARTIFACT_DIR="$tmp/cause-quiet"
  : > "$summary"
  GITHUB_STEP_SUMMARY="$summary" report_primary_cause >/dev/null 2>&1
  check "--report-primary-cause exits 0 without evidence" "$?" "0"
  local empty_summary=no
  [[ ! -s "$summary" ]] && empty_summary=yes
  check "a healthy run adds nothing to the job summary" "$empty_summary" "yes"

  ARTIFACT_DIR="$tmp/never-ran"
  GITHUB_STEP_SUMMARY="$summary" report_primary_cause >/dev/null 2>&1
  check "--report-primary-cause exits 0 with no artifacts at all" "$?" "0"

  # 9. THE MARKER IS DUPLICATED (issue #2833, follow-up 2). `$OUTAGE_MARKER`
  #    above and `DEVICE_FOCUS_OUTAGE_MARKER` in DeviceFocus.kt are two copies
  #    of one string, and check 8c cannot notice a drift because it builds its
  #    own fixture out of `$OUTAGE_MARKER`. A drift is SILENT in the worst way:
  #    the ANR half of the scan still works, so a wedged run still reports —
  #    just without the harness's own in-test verdict, the half that survives
  #    when the ANR has aged out of the log buffer. So read the Kotlin const and
  #    compare the two.
  local focus_kt="$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/connect/DeviceFocus.kt"
  local kt_marker=""
  kt_marker="$(read_kotlin_outage_marker "$focus_kt")"
  check "the Kotlin outage marker is readable" "$([[ -n "$kt_marker" ]] && echo yes || echo no)" "yes"
  check "shell and Kotlin outage markers agree" "$kt_marker" "$OUTAGE_MARKER"

  # ...and the reader is not a constant function: over a copy whose const has
  # drifted it must return the DRIFTED string, not the shell's. Without this,
  # an extractor that quietly returned "$OUTAGE_MARKER" would pass check 9
  # forever.
  cp "$focus_kt" "$tmp/DeviceFocus-drifted.kt"
  sed -i 's/^const val DEVICE_FOCUS_OUTAGE_MARKER: String = ".*"$/const val DEVICE_FOCUS_OUTAGE_MARKER: String = "INFRA: drifted marker"/' \
    "$tmp/DeviceFocus-drifted.kt"
  check "the marker reader reports a drift rather than the shell copy" \
    "$(read_kotlin_outage_marker "$tmp/DeviceFocus-drifted.kt")" "INFRA: drifted marker"

  # 10. THE CLI ROUTE (issue #2833, follow-up 3). Everything above calls
  #     `report_primary_cause` as a shell FUNCTION; app2.yml calls the SCRIPT
  #     with `--report-primary-cause`. Delete that dispatcher case and the
  #     function-level checks stay green while the real step falls through to
  #     `*)` and exits 2 — reddening the triage step on exactly the run it
  #     exists to explain. Drive the real argv.
  local route_dir="$tmp/cli-route"
  mkdir -p "$route_dir"
  printf 'com.pocketshell.next.terminal.J03AttachAndTypeJourney > attach FAILED\n    %s — the DEVICE never granted window focus\n' \
    "$OUTAGE_MARKER" > "$route_dir/gradle.log"
  local route_summary="$tmp/cli-route-summary.md"
  : > "$route_summary"
  POCKETSHELL_APP2_JOURNEY_ARTIFACTS="$route_dir" \
    POCKETSHELL_APP2_JOURNEY_RESULTS="$tmp/no-results" \
    GITHUB_STEP_SUMMARY="$route_summary" \
    "$SELF" --report-primary-cause > "$tmp/cli-route.out" 2>&1
  check "the --report-primary-cause CLI route exits 0 with evidence" "$?" "0"
  local route_reported=no
  grep -q 'PRIMARY CAUSE: the device wedged' "$tmp/cli-route.out" && route_reported=yes
  check "the CLI route prints the primary cause" "$route_reported" "yes"
  local route_summarised=no
  grep -q 'PRIMARY CAUSE: the device wedged' "$route_summary" && route_summarised=yes
  check "the CLI route appends to the job summary" "$route_summarised" "yes"

  # ...on a healthy tree the same route is still a 0, so the triage step never
  # reddens a run it only annotates.
  POCKETSHELL_APP2_JOURNEY_ARTIFACTS="$tmp/cli-route-empty" \
    POCKETSHELL_APP2_JOURNEY_RESULTS="$tmp/no-results" \
    "$SELF" --report-primary-cause >/dev/null 2>&1
  check "the --report-primary-cause CLI route exits 0 without evidence" "$?" "0"

  # ...and rc=0 above is not vacuous: an unrecognised flag DOES exit 2, which is
  # precisely what a deleted dispatcher case would turn the real step into.
  "$SELF" --not-a-route >/dev/null 2>&1
  check "an unknown flag exits 2 (the fall-through a deleted case would hit)" "$?" "2"

  ARTIFACT_DIR="$prev_artifacts"
  RESULTS_DIR="$prev_results"
  ADB="$prev_adb"

  if [[ "$failures" -ne 0 ]]; then
    echo "ci-app2-journey-suite.sh --self-test: $failures/$checks check(s) FAILED" >&2
    return 1
  fi
  echo "ci-app2-journey-suite.sh --self-test: $checks check(s) passed"
  return 0
}

case "${1:-}" in
  --self-test)
    self_test
    exit $?
    ;;
  --report-primary-cause)
    report_primary_cause
    exit $?
    ;;
  "")
    main
    exit $?
    ;;
  *)
    echo "usage: $0 [--self-test|--report-primary-cause]" >&2
    exit 2
    ;;
esac
