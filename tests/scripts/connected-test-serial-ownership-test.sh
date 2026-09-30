#!/usr/bin/env bash
set -euo pipefail

# Hermetic serial-ownership regression for the JS-first connected lanes (#2863).
# Drive the real dispatcher and connected-js-smoke.sh from separate scratch
# checkouts. Fake adb/Gradle model only the emulator mutation boundary; no AVD,
# Docker daemon, real Gradle daemon, or installed package is touched.
#
# Load-bearing contracts:
#   * two JS lanes targeting the same serial cannot overlap across checkouts;
#   * different serials remain concurrent;
#   * a red exact JUnit report fails and releases the serial for the next lane;
#   * killing the lane wrapper must not leave its Gradle child holding the
#     continuous AVD lock descriptor.

unset POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_FD \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID \
      ANDROID_SERIAL \
      ADB_SERIAL

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
REAL_FLOCK="$(command -v flock)"
SANDBOX=""
ACTIVE_GROUPS=()

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

forget_group() {
  local target="$1" pid
  local -a remaining=()
  for pid in "${ACTIVE_GROUPS[@]:-}"; do
    [[ "$pid" == "$target" ]] || remaining+=("$pid")
  done
  ACTIVE_GROUPS=()
  for pid in "${remaining[@]:-}"; do ACTIVE_GROUPS+=("$pid"); done
}

kill_group() {
  local group="$1" waited=0
  [[ -n "$group" ]] || return 0
  kill -TERM -- "-$group" 2>/dev/null || true
  while ps -eo pgid=,stat= | awk -v pgid="$group" '$1 == pgid && $2 !~ /^Z/ { found=1 } END { exit !found }'; do
    (( waited++ >= 50 )) && break
    sleep 0.02
  done
  kill -KILL -- "-$group" 2>/dev/null || true
  wait "$group" 2>/dev/null || true
}

cleanup_groups() {
  local group
  for group in "${ACTIVE_GROUPS[@]:-}"; do kill_group "$group"; done
  ACTIVE_GROUPS=()
}

cleanup() {
  cleanup_groups
  if [[ -n "$SANDBOX" && -d "$SANDBOX" ]]; then
    python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)' "$SANDBOX"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

wait_for_file() {
  local file="$1" timeout="${2:-8}" waited=0 limit
  limit=$((timeout * 50))
  while [[ ! -e "$file" ]]; do
    (( waited++ >= limit )) && return 1
    sleep 0.02
  done
}

wait_for_pattern() {
  local file="$1" pattern="$2" timeout="${3:-8}" waited=0 limit
  limit=$((timeout * 50))
  while ! grep -Fq -- "$pattern" "$file" 2>/dev/null; do
    (( waited++ >= limit )) && return 1
    sleep 0.02
  done
}

wait_for_serial_flock_reclaim() {
  local lock_file="$1" process_group="$2" timeout="${3:-2}"
  local waited=0 limit=$((timeout * 100))
  while ! "$REAL_FLOCK" -n "$lock_file" true; do
    if (( waited++ >= limit )); then
      serial_lock_fds_in_group "$lock_file" "$process_group" >&2
      return 1
    fi
    sleep 0.01
  done
}

serial_lock_fds_in_group() {
  local lock_file="$1" process_group="$2"
  local pid fd target
  while IFS= read -r pid; do
    [[ -n "$pid" ]] || continue
    for fd in "/proc/$pid/fd/"*; do
      [[ -e "$fd" ]] || continue
      target="$(readlink -f "$fd" 2>/dev/null || true)"
      [[ "$target" == "$lock_file" ]] || continue
      printf 'lock-fd: resource=%s process_group=%s pid=%s fd=%s comm=%s\n' \
        "$lock_file" "$process_group" "$pid" "${fd##*/}" \
        "$(tr '\0' ' ' < "/proc/$pid/comm" 2>/dev/null || true)"
    done
  done < <(ps -eo pid=,pgid= | awk -v pgid="$process_group" '$2 == pgid { print $1 }')
}

make_fake_adb() {
  local sandbox="$1"
  cat > "$sandbox/bin/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
serial="${ANDROID_SERIAL:-}"
if [[ "${1:-}" == "-s" ]]; then
  serial="$2"
  shift 2
fi
case "${1:-}" in
  devices)
    printf 'List of devices attached\n'
    for candidate in $FAKE_ONLINE_SERIALS; do printf '%s\tdevice\n' "$candidate"; done
    ;;
  get-state)
    case " $FAKE_ONLINE_SERIALS " in
      *" $serial "*) printf 'device\n' ;;
      *) printf 'offline\n'; exit 1 ;;
    esac
    ;;
  shell)
    [[ "${2:-}" == 'getprop' && "${3:-}" == 'ro.build.version.sdk' ]] \
      || { printf 'unexpected adb shell command: %s\n' "$*" >&2; exit 90; }
    printf '35\n'
    ;;
  *)
    printf 'unexpected adb command: %s\n' "$*" >&2
    exit 90
    ;;
esac
ADB
  chmod +x "$sandbox/bin/adb"
}

make_fake_gradle() {
  local root="$1"
  cat > "$root/android/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail
serial="${ANDROID_SERIAL:?}"
run_id="${FAKE_RUN_ID:?}"
state="${FAKE_DEVICE_STATE:?}"
args="$*"
suffix=""
for arg in "$@"; do
  case "$arg" in
    -PpocketshellAppIdSuffix=*) suffix="${arg#*=}" ;;
  esac
done
[[ "$args" == *":app:connectedDebugAndroidTest"* ]] || {
  printf 'unexpected Gradle invocation: %s\n' "$args" >&2
  exit 91
}
[[ -n "$suffix" ]] || { printf 'missing app suffix\n' >&2; exit 92; }
printf '%s\n' "$args" > "$state/$run_id.gradle-args"
printf '%s\n' "$serial" > "$state/$run_id.serial"
printf '%s\n' "${POCKETSHELL_AVD_LOCK_FILE:-}" > "$state/$run_id.lock-file"
printf '%s\n' "$BASHPID" > "$state/$run_id.gradle-pid"

lock_path="$(readlink -f "${POCKETSHELL_AVD_LOCK_FILE:?}")"
inherited_fds=()
for fd in "/proc/$BASHPID/fd/"*; do
  [[ -e "$fd" ]] || continue
  target="$(readlink -f "$fd" 2>/dev/null || true)"
  [[ "$target" == "$lock_path" ]] && inherited_fds+=("${fd##*/}")
done
printf '%s\n' "${inherited_fds[*]:-}" > "$state/$run_id.inherited-lock-fds"

guard="$state/mutating-$serial"
if ! mkdir "$guard" 2>/dev/null; then
  printf '%s overlap %s\n' "$run_id" "$serial" >> "$state/events"
  touch "$state/overlap"
  exit 93
fi
touch "$state/$run_id.instrumentation-active"
finish_gradle() {
  rmdir "$guard" 2>/dev/null || true
  touch "$state/$run_id.gradle-exited"
}
trap finish_gradle EXIT
trap 'exit 143' TERM INT

touch "$state/$run_id.gradle-started"
if [[ "${FAKE_HOLD_RUN_ID:-}" == "$run_id" ]]; then
  while [[ ! -e "$state/$run_id.release" ]]; do sleep 0.02; done
fi

results="$(cd -- "$(dirname -- "$0")" && pwd -P)/app/build/outputs/androidTest-results/connected/debug"
mkdir -p "$results"
if [[ "${FAKE_RED_JUNIT_RUN_ID:-}" == "$run_id" ]]; then
  cat > "$results/TEST-smoke.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="smoke" tests="6" failures="1" errors="0" skipped="0">
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="launchShowsVerifiedSourcesAndAssetIdentity"><failure message="fixture red"/></testcase>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="singleOpenDocumentDataUriIsIncludedAndDeduplicated"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="packagedAndroidAdaptersDeliverSharedTextAndExactFileBytes"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="packagedMultipleShareReadsStandardStreamListWithoutClipData"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="settingsAndAndroidBackReturnHome"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="composerInputStaysAboveImeWithinSafeArea"/>
</testsuite>
XML
else
  cat > "$results/TEST-smoke.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="smoke" tests="6" failures="0" errors="0" skipped="0">
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="launchShowsVerifiedSourcesAndAssetIdentity"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="singleOpenDocumentDataUriIsIncludedAndDeduplicated"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="packagedAndroidAdaptersDeliverSharedTextAndExactFileBytes"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="packagedMultipleShareReadsStandardStreamListWithoutClipData"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="settingsAndAndroidBackReturnHome"/>
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="composerInputStaysAboveImeWithinSafeArea"/>
</testsuite>
XML
fi
exit 0
GRADLEW
  chmod +x "$root/android/gradlew"
}

make_checkout() {
  local root="$1"
  mkdir -p "$root/scripts/lib" "$root/android/app/build" "$root/scripts" "$root/android"
  cp "$ROOT_DIR/scripts/connected-test.sh" "$root/scripts/connected-test.sh"
  cp "$ROOT_DIR/scripts/connected-js-smoke.sh" "$root/scripts/connected-js-smoke.sh"
  cp "$ROOT_DIR/scripts/check-js-smoke-results.py" "$root/scripts/check-js-smoke-results.py"
  cp "$ROOT_DIR/scripts/lib/avd-lock.sh" "$root/scripts/lib/avd-lock.sh"
  cp "$ROOT_DIR/scripts/lib/disk-preflight.sh" "$root/scripts/lib/disk-preflight.sh"
  cp "$ROOT_DIR/scripts/lib/gradle-output-lock.sh" "$root/scripts/lib/gradle-output-lock.sh"
  chmod +x "$root/scripts/connected-test.sh" "$root/scripts/connected-js-smoke.sh" \
    "$root/scripts/check-js-smoke-results.py"
  make_fake_gradle "$root"
}

new_sandbox() {
  cleanup_groups
  SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-js-avd-serial.XXXXXX")"
  mkdir -p "$SANDBOX/bin" "$SANDBOX/device-state" "$SANDBOX/avd-locks" \
    "$SANDBOX/output-locks" "$SANDBOX/tmp"
  make_fake_adb "$SANDBOX"
}

start_runner() {
  local root="$1" run_id="$2" suffix="$3" serial="$4" online_serials="$5"
  local hold_run_id="${6:-}" red_run_id="${7:-}"
  setsid env \
    TMPDIR="$SANDBOX/tmp" \
    PATH="$SANDBOX/bin:$PATH" \
    ADB="$SANDBOX/bin/adb" \
    ANDROID_SDK="$SANDBOX" \
    ANDROID_SERIAL="$serial" \
    POCKETSHELL_AVD_LOCK_DIR="$SANDBOX/avd-locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$SANDBOX/output-locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=15 \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_PARENT_POLL_SECONDS=0.05 \
    POCKETSHELL_DISK_MIN_FREE_MB=0 \
    POCKETSHELL_DISK_WARN_FREE_MB=0 \
    FAKE_ONLINE_SERIALS="$online_serials" \
    FAKE_DEVICE_STATE="$SANDBOX/device-state" \
    FAKE_RUN_ID="$run_id" \
    FAKE_HOLD_RUN_ID="$hold_run_id" \
    FAKE_RED_JUNIT_RUN_ID="$red_run_id" \
    bash -c 'cd -- "$1"; exec bash "$1/scripts/connected-test.sh" smoke --suffix "$2" --test-only' \
    runner "$root" "$suffix" > "$SANDBOX/$run_id.out" 2> "$SANDBOX/$run_id.err" &
  WRAPPER_PID="$!"
  ACTIVE_GROUPS+=("$WRAPPER_PID")
}

wait_runner_success() {
  local pid="$1" run_id="$2" rc=0
  if wait "$pid"; then rc=0; else rc=$?; fi
  forget_group "$pid"
  [[ "$rc" == 0 ]] || {
    cat "$SANDBOX/$run_id.err" >&2
    fail "JS connected lane $run_id exited $rc"
  }
}

wait_runner_failure() {
  local pid="$1" run_id="$2" rc=0
  if wait "$pid"; then rc=0; else rc=$?; fi
  forget_group "$pid"
  (( rc != 0 )) || fail "JS connected lane $run_id unexpectedly accepted a red JUnit report"
  grep -Fq 'instrumentation tests failed' "$SANDBOX/$run_id.err" \
    || fail "JS connected lane $run_id did not report the exact JUnit failure"
  ! grep -Fq '6 executed tests, 6 passed' "$SANDBOX/$run_id.out" \
    || fail "JS connected lane $run_id printed a green result for a red JUnit report"
}

assert_run_identity() {
  local run_id="$1" suffix="$2" serial="$3"
  grep -Fq -- ":app:connectedDebugAndroidTest" "$SANDBOX/device-state/$run_id.gradle-args" \
    || fail "$run_id did not execute the JS :app connected task"
  grep -Fq -- "-PpocketshellAppIdSuffix=$suffix" "$SANDBOX/device-state/$run_id.gradle-args" \
    || fail "$run_id lost its isolated package suffix"
  [[ "$(<"$SANDBOX/device-state/$run_id.serial")" == "$serial" ]] \
    || fail "$run_id mutated the wrong emulator serial"
}

same_serial_lanes_serialize_across_worktrees() {
  new_sandbox
  make_checkout "$SANDBOX/worktree-a"
  make_checkout "$SANDBOX/worktree-b"

  start_runner "$SANDBOX/worktree-a" same-a i2863a emulator-5554 emulator-5554 same-a
  local first_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/same-a.gradle-started" 10 \
    || fail 'first real JS lane never reached its fake connected Gradle boundary'

  start_runner "$SANDBOX/worktree-b" same-b i2863b emulator-5554 emulator-5554 same-b
  local second_pid="$WRAPPER_PID"
  wait_for_pattern "$SANDBOX/same-b.err" 'queuing for it' 10 \
    || fail 'second JS worktree did not queue on the shared emulator serial lock'
  [[ ! -e "$SANDBOX/device-state/same-b.gradle-started" ]] \
    || fail 'second JS lane reached Gradle while the first held the same serial'
  [[ ! -e "$SANDBOX/device-state/overlap" ]] \
    || fail 'fake emulator observed overlapping same-serial mutations'

  touch "$SANDBOX/device-state/same-a.release"
  wait_runner_success "$first_pid" same-a
  wait_for_file "$SANDBOX/device-state/same-b.gradle-started" 10 \
    || fail 'second JS lane did not enter after the first released the serial'
  touch "$SANDBOX/device-state/same-b.release"
  wait_runner_success "$second_pid" same-b

  assert_run_identity same-a i2863a emulator-5554
  assert_run_identity same-b i2863b emulator-5554
  grep -Fq '6 executed tests, 6 passed' "$SANDBOX/same-a.out" \
    || fail 'first JS lane did not validate its own exact six-method report'
  grep -Fq '6 executed tests, 6 passed' "$SANDBOX/same-b.out" \
    || fail 'second JS lane did not validate its own exact six-method report'
  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$second_pid" 3 \
    || fail 'same-serial lock remained held after both JS wrappers exited'
}

distinct_serial_lanes_run_concurrently() {
  new_sandbox
  make_checkout "$SANDBOX/worktree-a"
  make_checkout "$SANDBOX/worktree-b"

  start_runner "$SANDBOX/worktree-a" diff-a i2863c emulator-5554 'emulator-5554 emulator-5556' diff-a
  local first_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/diff-a.gradle-started" 10 \
    || fail 'emulator-5554 JS lane never reached Gradle'

  start_runner "$SANDBOX/worktree-b" diff-b i2863d emulator-5556 'emulator-5554 emulator-5556' diff-b
  local second_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/diff-b.gradle-started" 3 \
    || fail 'emulator-5556 was globally serialized behind emulator-5554'
  [[ ! -e "$SANDBOX/device-state/overlap" ]] \
    || fail 'different serials incorrectly entered the same mutation guard'
  [[ "$(<"$SANDBOX/device-state/diff-a.lock-file")" != "$(<"$SANDBOX/device-state/diff-b.lock-file")" ]] \
    || fail 'different emulator serials resolved to one shared lock file'

  touch "$SANDBOX/device-state/diff-a.release" "$SANDBOX/device-state/diff-b.release"
  wait_runner_success "$first_pid" diff-a
  wait_runner_success "$second_pid" diff-b
  assert_run_identity diff-a i2863c emulator-5554
  assert_run_identity diff-b i2863d emulator-5556
  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$first_pid" 3 \
    || fail 'emulator-5554 serial lock remained held after its JS lane exited'
  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5556" "$second_pid" 3 \
    || fail 'emulator-5556 serial lock remained held after its JS lane exited'
}

red_junit_fails_closed_and_releases_the_serial() {
  new_sandbox
  make_checkout "$SANDBOX/worktree-red"
  make_checkout "$SANDBOX/worktree-green"

  start_runner "$SANDBOX/worktree-red" red-junit i2863e emulator-5554 emulator-5554 red-junit red-junit
  local red_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/red-junit.gradle-started" 10 \
    || fail 'red-JUnit fixture never reached the real JS connected lane'
  touch "$SANDBOX/device-state/red-junit.release"
  wait_runner_failure "$red_pid" red-junit

  start_runner "$SANDBOX/worktree-green" after-red i2863f emulator-5554 emulator-5554 after-red
  local green_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/after-red.gradle-started" 5 \
    || fail 'a red JUnit verdict left the shared serial lock wedged'
  touch "$SANDBOX/device-state/after-red.release"
  wait_runner_success "$green_pid" after-red
  grep -Fq '6 executed tests, 6 passed' "$SANDBOX/after-red.out" \
    || fail 'post-failure JS lane did not validate its exact JUnit report'
  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$green_pid" 3 \
    || fail 'serial lock remained held after the post-failure lane exited'
}

killed_js_lane_wrapper_does_not_leave_gradle_holding_the_serial() {
  new_sandbox
  make_checkout "$SANDBOX/worktree-victim"
  make_checkout "$SANDBOX/worktree-contender"

  start_runner "$SANDBOX/worktree-victim" killed-victim i2863g emulator-5554 emulator-5554 killed-victim
  local wrapper_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/killed-victim.gradle-started" 10 \
    || fail 'victim real JS lane never reached the fake Gradle child'
  local child_pid inherited_fds
  child_pid="$(<"$SANDBOX/device-state/killed-victim.gradle-pid")"
  inherited_fds="$(<"$SANDBOX/device-state/killed-victim.inherited-lock-fds")"
  kill -KILL "$wrapper_pid" 2>/dev/null || true
  wait "$wrapper_pid" 2>/dev/null || true

  if ! wait_for_serial_flock_reclaim \
      "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$wrapper_pid" 1; then
    printf 'evidence: wrapper_pid=%s gradle_pid=%s inherited_lock_fds=%s\n' \
      "$wrapper_pid" "$child_pid" "${inherited_fds:-<not recorded>}" >&2
    fail 'killed connected-test smoke wrapper left its live Gradle child holding the per-serial AVD lock'
  fi
  [[ -z "$inherited_fds" ]] \
    || fail "Gradle child inherited AVD lock fd(s) $inherited_fds even though the serial became reclaimable"
  kill -TERM "$child_pid" 2>/dev/null || true
  wait_for_file "$SANDBOX/device-state/killed-victim.gradle-exited" 5 \
    || fail 'fake Gradle child did not exit after harness cleanup'

  start_runner "$SANDBOX/worktree-contender" after-kill i2863h emulator-5554 emulator-5554 after-kill
  local contender_pid="$WRAPPER_PID"
  wait_for_file "$SANDBOX/device-state/after-kill.gradle-started" 5 \
    || fail 'next JS lane could not acquire the serial after wrapper crash cleanup'
  touch "$SANDBOX/device-state/after-kill.release"
  wait_runner_success "$contender_pid" after-kill
  assert_run_identity after-kill i2863h emulator-5554
  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$contender_pid" 3 \
    || fail 'serial lock remained held after crash-recovery contender exited'
}

ALL_CASES=(
  same_serial_lanes_serialize_across_worktrees
  distinct_serial_lanes_run_concurrently
  red_junit_fails_closed_and_releases_the_serial
  killed_js_lane_wrapper_does_not_leave_gradle_holding_the_serial
)
EXPECTED_FULL_CASES=4
CASES=("${ALL_CASES[@]}")
if [[ $# -gt 0 ]]; then
  CASES=("$@")
  for case_name in "${CASES[@]}"; do
    allowed=0
    for known_case in "${ALL_CASES[@]}"; do
      [[ "$case_name" == "$known_case" ]] && allowed=1
    done
    (( allowed == 1 )) || fail "unknown focused serial-ownership case '$case_name'"
  done
fi
(( ${#ALL_CASES[@]} == EXPECTED_FULL_CASES )) \
  || fail "expected $EXPECTED_FULL_CASES full cases, declared ${#ALL_CASES[@]}"

CASE_COUNT=0
for case_name in "${CASES[@]}"; do
  cleanup_groups
  "$case_name"
  cleanup_groups
  CASE_COUNT=$((CASE_COUNT + 1))
  printf '  ok: %s\n' "$case_name"
done
(( CASE_COUNT == ${#CASES[@]} && CASE_COUNT > 0 )) \
  || fail "expected ${#CASES[@]} cases to run, saw $CASE_COUNT"
printf 'PASS: JS connected-lane serial ownership (%s/%s cases)\n' "$CASE_COUNT" "$EXPECTED_FULL_CASES"
