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

# The fake connected Gradle publishes exactly the smoke methods the REAL
# result checker requires (issue #2946 added one). Deriving the set instead of
# hard-coding it keeps this harness from rotting into a lane that dies at the
# exact-report check before reaching the property under test.
SMOKE_METHODS="$(python3 -c 'import runpy, sys; print("\n".join(sorted(runpy.run_path(sys.argv[1])["REQUIRED_METHODS"])))' \
  "$ROOT_DIR/scripts/check-js-smoke-results.py")"
SMOKE_COUNT="$(grep -c . <<< "$SMOKE_METHODS")"
(( SMOKE_COUNT > 0 )) || { printf 'FAIL: could not read the required smoke methods\n' >&2; exit 1; }
SMOKE_PASS_LINE="$SMOKE_COUNT executed tests, $SMOKE_COUNT passed"
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
    shift
    state="${FAKE_DEVICE_STATE:?}"
    # Issue #2946: every lane runs the Android input preflight before it
    # touches the device and captures input diagnostics on failure. Answer
    # exactly those commands like an API 35 emulator with no system error
    # dialog; anything else is still an unexpected command.
    case "$*" in
      'getprop ro.build.version.sdk') printf '35\n' ;;
      'settings put global hide_error_dialogs 1')
        printf '1\n' > "$state/$serial.hide-error-dialogs" ;;
      'settings get global hide_error_dialogs')
        if [[ -f "$state/$serial.hide-error-dialogs" ]]; then
          cat "$state/$serial.hide-error-dialogs"
        else
          printf 'null\n'
        fi
        ;;
      'dumpsys window windows')
        printf 'WINDOW MANAGER WINDOWS (dumpsys window windows)\n'
        printf '  Window #0 Window{1a2b3c u0 com.pocketshell.app/com.pocketshell.app.MainActivity}:\n'
        ;;
      'dumpsys window displays')
        printf '  mCurrentFocus=Window{1a2b3c u0 com.pocketshell.app/com.pocketshell.app.MainActivity}\n'
        printf '  mFocusedApp=ActivityRecord{4d5e6f u0 com.pocketshell.app/.MainActivity}\n'
        ;;
      'dumpsys input') printf 'Input Dispatcher State:\n' ;;
      'dumpsys window lastanr') printf 'WINDOW MANAGER LAST ANR (dumpsys window lastanr)\n' ;;
      'dumpsys input_method') printf '  mCurTokenDisplayId=0\n' ;;
      'dumpsys activity activities') printf 'ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)\n' ;;
      # Lifecycle lane only: host/device timebase and artifact-state probes.
      'date +%s%3N') date +%s%3N ;;
      'pm list packages '*) printf 'package:%s\n' "${*##* }" ;;
      'ls -la '*) printf 'ls: %s: No such file or directory\n' "${*##* }" ;;
      *) printf 'unexpected adb shell command: %s\n' "$*" >&2; exit 90 ;;
    esac
    ;;
  logcat)
    case "${2:-}" in
      -c) ;;
      -d) printf -- '--------- beginning of main\n' ;;
      # The lifecycle lane's live collector streams until the lane stops it.
      -v) exec sleep 600 ;;
      *) printf 'unexpected adb logcat command: %s\n' "$*" >&2; exit 90 ;;
    esac
    ;;
  pull)
    printf 'adb: error: remote object %s does not exist\n' "${2:-}" >&2
    exit 1
    ;;
  uninstall)
    # Lifecycle lane (#2943) removes its own APKs before and after the run.
    printf 'Success\n'
    ;;
  exec-out)
    [[ "${2:-}" == 'screencap' ]] || { printf 'unexpected adb exec-out command: %s\n' "$*" >&2; exit 90; }
    printf '\x89PNG\r\n\x1a\n'
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
# The #2946 input preflight must have disabled error dialogs on THIS serial
# before instrumentation started.
cat "$state/$serial.hide-error-dialogs" > "$state/$run_id.preflight" 2>/dev/null || true

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
methods_file="$(cd -- "$(dirname -- "$0")" && pwd -P)/fake-smoke-methods.txt"
mapfile -t methods < "$methods_file"
(( ${#methods[@]} > 0 )) || { printf 'fake smoke method list is empty\n' >&2; exit 94; }
failures=0
[[ "${FAKE_RED_JUNIT_RUN_ID:-}" == "$run_id" ]] && failures=1
{
  printf '<?xml version="1.0" encoding="UTF-8"?>\n'
  printf '<testsuite name="smoke" tests="%s" failures="%s" errors="0" skipped="0">\n' "${#methods[@]}" "$failures"
  for index in "${!methods[@]}"; do
    if (( failures == 1 && index == 0 )); then
      printf '  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="%s"><failure message="fixture red"/></testcase>\n' "${methods[$index]}"
    else
      printf '  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="%s"/>\n' "${methods[$index]}"
    fi
  done
  printf '</testsuite>\n'
} > "$results/TEST-smoke.xml"
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
  cp "$ROOT_DIR/scripts/check-android-input-diagnostics.py" "$root/scripts/check-android-input-diagnostics.py"
  cp "$ROOT_DIR/scripts/lib/android-input-preflight.sh" "$root/scripts/lib/android-input-preflight.sh"
  cp "$ROOT_DIR/scripts/lib/avd-lock.sh" "$root/scripts/lib/avd-lock.sh"
  cp "$ROOT_DIR/scripts/lib/disk-preflight.sh" "$root/scripts/lib/disk-preflight.sh"
  cp "$ROOT_DIR/scripts/lib/gradle-output-lock.sh" "$root/scripts/lib/gradle-output-lock.sh"
  chmod +x "$root/scripts/connected-test.sh" "$root/scripts/connected-js-smoke.sh" \
    "$root/scripts/check-js-smoke-results.py" "$root/scripts/check-android-input-diagnostics.py"
  printf '%s\n' "$SMOKE_METHODS" > "$root/android/fake-smoke-methods.txt"
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
  ! grep -Fq "$SMOKE_PASS_LINE" "$SANDBOX/$run_id.out" \
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
  [[ "$(<"$SANDBOX/device-state/$run_id.preflight")" == 1 ]] \
    || fail "$run_id reached instrumentation without the #2946 Android input preflight on $serial"
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
  grep -Fq "$SMOKE_PASS_LINE" "$SANDBOX/same-a.out" \
    || fail 'first JS lane did not validate its own exact smoke-method report'
  grep -Fq "$SMOKE_PASS_LINE" "$SANDBOX/same-b.out" \
    || fail 'second JS lane did not validate its own exact smoke-method report'
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
  grep -Fq "$SMOKE_PASS_LINE" "$SANDBOX/after-red.out" \
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

# ---------------------------------------------------------------------------
# Lifecycle lane (issue #2975 x #2863): the lane backgrounds its Gradle
# instrumentation and a same-run artifact-pull watcher keyed to that
# instrumentation PID. Neither background process (nor the tee/logcat/socket
# watcher children) may inherit the wrapper-owned continuous AVD lock FD;
# only the lane shell itself may hold it.
# ---------------------------------------------------------------------------

make_lifecycle_stub_bin() {
  local bindir="$1"
  mkdir -p "$bindir"
  cat > "$bindir/tesseract" <<'STUB'
#!/usr/bin/env bash
printf 'tesseract 5.3.0 (serial-ownership harness stub)\n'
STUB
  cat > "$bindir/convert" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
  cat > "$bindir/docker" <<'STUB'
#!/usr/bin/env bash
case "${1:-} ${2:-}" in
  'inspect --format') printf 'healthy\n' ;;
  'inspect '*) printf '[{}]\n' ;;
  'logs '*) printf 'fixture log\n' ;;
  *) printf 'unexpected docker command: %s\n' "$*" >&2; exit 90 ;;
esac
STUB
  chmod +x "$bindir/tesseract" "$bindir/convert" "$bindir/docker"
}

make_lifecycle_checkout() {
  local root="$1" file
  mkdir -p "$root/scripts/lib" "$root/android/app/build" "$root/tests/docker"
  for file in connected-test.sh connected-js-lifecycle.sh test-js-lifecycle-cleanup.sh \
      check-js-lifecycle-results.py check-js-lifecycle-host-evidence.py \
      check-android-input-diagnostics.py; do
    cp "$ROOT_DIR/scripts/$file" "$root/scripts/$file"
    chmod +x "$root/scripts/$file"
  done
  for file in avd-lock.sh disk-preflight.sh gradle-output-lock.sh js-lifecycle-cleanup.sh \
      android-input-preflight.sh; do
    cp "$ROOT_DIR/scripts/lib/$file" "$root/scripts/lib/$file"
  done
  cp "$ROOT_DIR/tests/docker/test_key" "$root/tests/docker/test_key"

  # Background helpers are replaced by probes that record which lock FDs they
  # inherited. The lane's spawn shape -- not the helpers' logic -- is under test.
  cat > "$root/scripts/watch-js-lifecycle-host-connections.py" <<'PY'
#!/usr/bin/env python3
import os, signal, sys, time
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
while True:
    time.sleep(0.05)
PY
  cat > "$root/scripts/pull-js-lifecycle-artifacts.py" <<'PY'
#!/usr/bin/env python3
import os, sys, time
from pathlib import Path
if "--self-test" in sys.argv:
    print("PASS: serial-ownership harness pull-watcher probe self-test")
    sys.exit(0)
pid = int(sys.argv[sys.argv.index("--instrumentation-pid") + 1])
state = Path(os.environ["FAKE_DEVICE_STATE"])
run_id = os.environ["FAKE_RUN_ID"]
(state / f"{run_id}.watcher-pid").write_text(f"{os.getpid()}\n")
(state / f"{run_id}.watcher-instrumentation-pid").write_text(f"{pid}\n")
(state / f"{run_id}.watcher-started").touch()
while Path(f"/proc/{pid}").exists():
    time.sleep(0.05)
print("FAIL: instrumentation exited before the journey MANIFEST", file=sys.stderr)
sys.exit(1)
PY
  chmod +x "$root/scripts/watch-js-lifecycle-host-connections.py" \
    "$root/scripts/pull-js-lifecycle-artifacts.py"

  cat > "$root/android/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail
state="${FAKE_DEVICE_STATE:?}"
run_id="${FAKE_RUN_ID:?}"
[[ "$*" == *":app:connectedDebugAndroidTest"* && "$*" == *"SshPtyDockerJourneyTest"* ]] \
  || { printf 'unexpected lifecycle Gradle invocation: %s\n' "$*" >&2; exit 91; }
printf '%s\n' "$BASHPID" > "$state/$run_id.gradle-pid"
printf '%s\n' "$PPID" > "$state/$run_id.instrumentation-parent-pid"
cat "$state/${ANDROID_SERIAL:?}.hide-error-dialogs" > "$state/$run_id.preflight" 2>/dev/null || true
trap 'exit 143' TERM INT
touch "$state/$run_id.gradle-started"
while [[ ! -e "$state/$run_id.release" ]]; do sleep 0.02; done
# Fail the instrumentation so the lane exercises its failure-evidence path
# without needing a real lifecycle report.
printf 'fake lifecycle instrumentation failed\n' >&2
exit 3
GRADLEW
  chmod +x "$root/android/gradlew"
}

lifecycle_lane_background_children_do_not_inherit_the_serial_lock() {
  new_sandbox
  make_lifecycle_stub_bin "$SANDBOX/bin"
  local root="$SANDBOX/worktree-lifecycle" run_id=lifecycle-a rc=0
  make_lifecycle_checkout "$root"
  setsid env \
    TMPDIR="$SANDBOX/tmp" \
    PATH="$SANDBOX/bin:$PATH" \
    ADB="$SANDBOX/bin/adb" \
    ANDROID_SDK="$SANDBOX" \
    ANDROID_SERIAL=emulator-5554 \
    POCKETSHELL_AVD_LOCK_DIR="$SANDBOX/avd-locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$SANDBOX/output-locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=15 \
    POCKETSHELL_DISK_MIN_FREE_MB=0 \
    POCKETSHELL_DISK_WARN_FREE_MB=0 \
    FAKE_ONLINE_SERIALS=emulator-5554 \
    FAKE_DEVICE_STATE="$SANDBOX/device-state" \
    FAKE_RUN_ID="$run_id" \
    bash -c 'cd -- "$1"; exec bash "$1/scripts/connected-test.sh" lifecycle --suffix i2863l \
      --port 2222 --container pocketshell-test-agents --run-id js2863serial --test-only' \
    runner "$root" > "$SANDBOX/$run_id.out" 2> "$SANDBOX/$run_id.err" &
  local lane_pid="$!"
  ACTIVE_GROUPS+=("$lane_pid")

  wait_for_file "$SANDBOX/device-state/$run_id.gradle-started" 20 \
    || { cat "$SANDBOX/$run_id.err" >&2; fail 'lifecycle lane never reached its fake background instrumentation'; }
  wait_for_file "$SANDBOX/device-state/$run_id.watcher-started" 10 \
    || { cat "$SANDBOX/$run_id.err" >&2; fail 'lifecycle lane never started its artifact-pull watcher'; }
  [[ "$(<"$SANDBOX/device-state/$run_id.preflight")" == 1 ]] \
    || fail 'lifecycle lane reached instrumentation without the #2946 Android input preflight'

  local instrumentation_pid watcher_pid watcher_target gradle_pid
  instrumentation_pid="$(<"$SANDBOX/device-state/$run_id.instrumentation-parent-pid")"
  watcher_pid="$(<"$SANDBOX/device-state/$run_id.watcher-pid")"
  watcher_target="$(<"$SANDBOX/device-state/$run_id.watcher-instrumentation-pid")"
  gradle_pid="$(<"$SANDBOX/device-state/$run_id.gradle-pid")"
  [[ "$watcher_target" == "$instrumentation_pid" ]] \
    || fail "artifact-pull watcher tracks pid $watcher_target, not the instrumentation process $instrumentation_pid"
  [[ "$(ps -o ppid= -p "$instrumentation_pid" | tr -d ' ')" == "$lane_pid" ]] \
    || fail "background instrumentation $instrumentation_pid is not a direct child of the lane $lane_pid"

  # The lane's own short foreground commands (e.g. its `sleep 0.2` start-up
  # probe) legitimately inherit the FD for a moment while the lane waits on
  # them. The defect is a LONG-LIVED holder: instrumentation stays blocked on
  # the release file and the watcher on instrumentation, so any inherited FD
  # there persists. Poll (bounded) for the holder set to settle on the lane.
  local holders holder_pids attempt
  for attempt in $(seq 1 50); do
    holders="$(serial_lock_fds_in_group "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$lane_pid")"
    holder_pids="$(sed -n 's/.* pid=\([0-9]*\) .*/\1/p' <<< "$holders" | sort -u | tr '\n' ' ')"
    [[ "$holder_pids" == "$lane_pid " ]] && break
    sleep 0.1
  done
  [[ -e "$SANDBOX/device-state/$run_id.release" ]] \
    && fail 'harness released instrumentation before the lock-holder check settled'
  kill -0 "$instrumentation_pid" 2>/dev/null && kill -0 "$watcher_pid" 2>/dev/null \
    || fail 'instrumentation or watcher exited before the lock-holder check, so it would pass vacuously'
  [[ "$holder_pids" == "$lane_pid " ]] || {
    printf 'evidence: lane=%s instrumentation=%s gradle=%s watcher=%s\n%s\n' \
      "$lane_pid" "$instrumentation_pid" "$gradle_pid" "$watcher_pid" "$holders" >&2
    fail "only the lifecycle lane shell may hold the serial lock; holders: ${holder_pids:-<none>}"
  }

  touch "$SANDBOX/device-state/$run_id.release"
  if wait "$lane_pid"; then rc=0; else rc=$?; fi
  forget_group "$lane_pid"
  [[ "$rc" == 3 ]] || { cat "$SANDBOX/$run_id.err" >&2; fail "lifecycle lane should return the instrumentation exit 3, got $rc"; }
  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$lane_pid" 3 \
    || fail 'serial lock remained held after the failed lifecycle lane exited'
}

ALL_CASES=(
  same_serial_lanes_serialize_across_worktrees
  distinct_serial_lanes_run_concurrently
  red_junit_fails_closed_and_releases_the_serial
  killed_js_lane_wrapper_does_not_leave_gradle_holding_the_serial
  lifecycle_lane_background_children_do_not_inherit_the_serial_lock
)
EXPECTED_FULL_CASES=5
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
