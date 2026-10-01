#!/usr/bin/env bash
set -euo pipefail

# JS-first connected-test dispatcher contract (issue #2863).
#
# Dispatch cases use scratch Git repositories and stub lane executables. The
# lane-contract case separately pins the real JS wrappers to their Android
# project, locks, exact JUnit guards, and independent Docker host oracles.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WRAPPER="$ROOT_DIR/scripts/connected-test.sh"
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-js-runner.XXXXXX")"
trap 'cleanup_runners; python3 -c '\''import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)'\'' "$SANDBOX"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

init_repo() {
  local path="$1"
  mkdir -p "$path"
  git -C "$path" init -q
  git -C "$path" -c user.name=js-runner-test -c user.email=test@example.invalid \
    commit --allow-empty -qm fixture
}

make_serial_fixture() {
  local repo="$SANDBOX/serial-repo"
  mkdir -p "$repo/scripts/lib" "$repo/android"
  cp "$WRAPPER" "$repo/scripts/connected-test.sh"
  cp "$ROOT_DIR/scripts/connected-js-smoke.sh" "$repo/scripts/connected-js-smoke.sh"
  cp "$ROOT_DIR/scripts/check-js-smoke-results.py" "$repo/scripts/check-js-smoke-results.py"
  cp "$ROOT_DIR/scripts/check-android-input-diagnostics.py" "$repo/scripts/check-android-input-diagnostics.py"
  cp "$ROOT_DIR/scripts/lib/android-input-preflight.sh" "$repo/scripts/lib/android-input-preflight.sh"
  cp "$ROOT_DIR/scripts/lib/avd-lock.sh" "$repo/scripts/lib/avd-lock.sh"
  cp "$ROOT_DIR/scripts/lib/disk-preflight.sh" "$repo/scripts/lib/disk-preflight.sh"
  cp "$ROOT_DIR/scripts/lib/gradle-output-lock.sh" "$repo/scripts/lib/gradle-output-lock.sh"

  cat > "$SANDBOX/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi
case "${1:-}" in
  devices)
    printf 'List of devices attached\nemulator-5554\tdevice\n'
    ;;
  get-state)
    printf 'device\n'
    ;;
  shell)
    case "${*:2}" in
      'getprop ro.build.version.sdk') printf '35\n' ;;
      # Android input preflight (#2946): disable system error dialogs, then
      # read the live window list (no error dialog) and the focus owner.
      'settings put global hide_error_dialogs 1') ;;
      'settings get global hide_error_dialogs') printf '1\n' ;;
      'settings put global device_provisioned 1'|'settings put secure user_setup_complete 1') ;;
      'dumpsys window windows') printf 'WINDOW MANAGER WINDOWS (dumpsys window windows)\n' ;;
      'dumpsys window displays') printf '  mCurrentFocus=Window{1 u0 fixture}\n' ;;
      # No third-party HOME launcher to disable on this fixture device.
      'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
        printf '1 activities found:\n  Activity #0:\n    com.android.settings/.FallbackHome\n' ;;
      *) printf 'unexpected adb shell command: %s\n' "$*" >&2; exit 90 ;;
    esac
    ;;
  *)
    printf 'unexpected adb command: %s\n' "$*" >&2
    exit 90
    ;;
esac
ADB
  chmod +x "$SANDBOX/adb"

  cat > "$repo/android/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
set -euo pipefail
suffix=""
for arg in "$@"; do
  case "$arg" in
    -PpocketshellAppIdSuffix=*) suffix="${arg#*=}" ;;
  esac
done
[[ -n "$suffix" ]] || { echo 'missing package suffix' >&2; exit 91; }
state="$FAKE_DEVICE_STATE"
printf '%s\n' "$*" > "$state/args-$suffix"
if ! mkdir "$state/mutating" 2>/dev/null; then
  touch "$state/overlap"
  exit 92
fi
trap 'python3 -c '\''import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)'\'' "$state/mutating"' EXIT
touch "$state/started-$suffix"
while [[ ! -e "$state/release-$suffix" ]]; do sleep 0.02; done
repo="$(cd -- "$(dirname -- "$0")/.." && pwd -P)"
results="$repo/android/app/build/outputs/androidTest-results/connected/debug"
mkdir -p "$results"
cat > "$results/TEST-smoke.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="smoke" tests="8" failures="0" errors="0" skipped="0">
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="launchShowsVerifiedSourcesAndAssetIdentity" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="singleOpenDocumentDataUriIsIncludedAndDeduplicated" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="packagedAndroidAdaptersDeliverSharedTextAndExactFileBytes" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="packagedMultipleShareReadsStandardStreamListWithoutClipData" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="settingsAndAndroidBackReturnHome" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="injectedInputProbeFailsClosedWhileAnotherWindowOwnsFocus" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="safeAreaBottomInsetBridgeCarriesANonZeroInset" />
  <testcase classname="com.pocketshell.app.smoke.JsShellPackagedSmokeTest" name="composerInputStaysAboveImeWithinSafeArea" />
</testsuite>
XML
GRADLEW
  chmod +x "$repo/android/gradlew"

  git -C "$repo" init -q
  git -C "$repo" add scripts android
  git -C "$repo" -c user.name=js-runner-test -c user.email=test@example.invalid \
    commit -qm 'packaged smoke runner fixture'
  git -C "$repo" worktree add -q "$SANDBOX/worktree-a" HEAD
  git -C "$repo" worktree add -q "$SANDBOX/worktree-b" HEAD
}

wait_for_file() {
  local file="$1" timeout="${2:-8}"
  local ticks=$((timeout * 50))
  for (( i = 0; i < ticks; i++ )); do
    [[ -e "$file" ]] && return 0
    sleep 0.02
  done
  return 1
}

wait_for_pattern() {
  local file="$1" pattern="$2" timeout="${3:-8}"
  local ticks=$((timeout * 50))
  for (( i = 0; i < ticks; i++ )); do
    grep -Fq -- "$pattern" "$file" 2>/dev/null && return 0
    sleep 0.02
  done
  return 1
}

start_serial_runner() {
  local worktree="$1" suffix="$2"
  setsid bash -c 'cd -- "$1"; shift; exec env "$@"' runner "$worktree" \
    TMPDIR="$SANDBOX/tmp" \
    PATH="$SANDBOX:$PATH" \
    ADB="$SANDBOX/adb" \
    ANDROID_SERIAL= \
    POCKETSHELL_AVD_LOCK_DIR="$SANDBOX/avd-locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$SANDBOX/output-locks" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=15 \
    POCKETSHELL_POOL_WAIT_SECONDS=15 \
    POCKETSHELL_DISK_MIN_FREE_MB=0 \
    POCKETSHELL_DISK_WARN_FREE_MB=0 \
    FAKE_DEVICE_STATE="$SANDBOX/device-state" \
    bash "$worktree/scripts/connected-test.sh" smoke --suffix "$suffix" --test-only \
    > "$SANDBOX/$suffix.out" 2> "$SANDBOX/$suffix.err" &
  ACTIVE_PID="$!"
}

ACTIVE_RUNNERS=()
cleanup_runners() {
  local pid
  for pid in "${ACTIVE_RUNNERS[@]:-}"; do
    kill -TERM -- "-$pid" 2>/dev/null || true
  done
  for pid in "${ACTIVE_RUNNERS[@]:-}"; do
    wait "$pid" 2>/dev/null || true
  done
  ACTIVE_RUNNERS=()
}

forget_runner() {
  local target="$1" pid
  local -a remaining=()
  for pid in "${ACTIVE_RUNNERS[@]:-}"; do
    [[ "$pid" == "$target" ]] || remaining+=("$pid")
  done
  ACTIVE_RUNNERS=()
  for pid in "${remaining[@]:-}"; do
    ACTIVE_RUNNERS+=("$pid")
  done
}

wait_runner_success() {
  local pid="$1" stderr="$2" rc=0
  if wait "$pid"; then
    rc=0
  else
    rc=$?
  fi
  forget_runner "$pid"
  if (( rc != 0 )); then
    cat "$stderr" >&2
    fail "connected runner process $pid exited $rc"
  fi
}

make_dispatch_fixture() {
  local path="$SANDBOX/script-root"
  python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)' "$path"
  mkdir -p "$path/scripts"
  cp "$WRAPPER" "$path/scripts/connected-test.sh"
  init_repo "$path"
  local lane
  for lane in smoke lifecycle composer-docker key-vault-docker; do
    cat > "$path/scripts/connected-js-$lane.sh" <<'LANE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$0" > "$POCKETSHELL_DISPATCH_CAPTURE"
printf '%s\n' "$@" >> "$POCKETSHELL_DISPATCH_CAPTURE"
LANE
    chmod +x "$path/scripts/connected-js-$lane.sh"
  done
  # The dispatcher maps the public lane names to these established paths.
  chmod +x "$path/scripts/connected-test.sh"
}

RUN_RC=0
RUN_OUT=""
RUN_ERR=""
RUN_CAPTURE=""
run_dispatch() {
  local cwd="$1" override="${2:-}"; shift 2
  RUN_OUT="$SANDBOX/run.out"
  RUN_ERR="$SANDBOX/run.err"
  RUN_CAPTURE="$SANDBOX/dispatch.txt"
  python3 -c 'import pathlib, sys; pathlib.Path(sys.argv[1]).unlink(missing_ok=True)' "$RUN_CAPTURE"
  set +e
  (
    cd "$cwd" || exit 97
    env \
      POCKETSHELL_DISPATCH_CAPTURE="$RUN_CAPTURE" \
      POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT="$override" \
      bash "$SANDBOX/script-root/scripts/connected-test.sh" "$@"
  ) > "$RUN_OUT" 2> "$RUN_ERR"
  RUN_RC=$?
  set -e
}

assert_no_dispatch() {
  [[ ! -e "$RUN_CAPTURE" ]] || fail 'invalid runner invocation reached a lane executable'
}

CASE_COUNT=0
run_case() {
  local name="$1"
  cleanup_runners
  python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)' "$SANDBOX"
  mkdir -p "$SANDBOX"
  "$name"
  CASE_COUNT=$((CASE_COUNT + 1))
  printf '  ok: %s\n' "$name"
}

no_lane_and_unknown_lane_fail_closed() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' --help
  (( RUN_RC == 0 )) || fail "top-level help exited $RUN_RC"
  grep -q 'composer-docker' "$RUN_OUT" || fail 'top-level help omitted the composer Docker lane'
  grep -q 'key-vault-docker' "$RUN_OUT" || fail 'top-level help omitted the key-vault Docker lane'
  assert_no_dispatch
  run_dispatch "$SANDBOX/script-root" ''
  (( RUN_RC == 2 )) || fail "missing lane exited $RUN_RC, expected 2"
  assert_no_dispatch
  run_dispatch "$SANDBOX/script-root" '' nonsense --suffix i2863
  (( RUN_RC == 2 )) || fail "unknown lane exited $RUN_RC, expected 2"
  assert_no_dispatch
}

suffix_is_required_and_validated_once() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' smoke --test-only
  (( RUN_RC == 2 )) || fail "missing suffix exited $RUN_RC, expected 2"
  assert_no_dispatch
  run_dispatch "$SANDBOX/script-root" '' smoke --suffix 'bad suffix' --test-only
  (( RUN_RC == 2 )) || fail "invalid suffix exited $RUN_RC, expected 2"
  assert_no_dispatch
  run_dispatch "$SANDBOX/script-root" '' smoke --suffix i2863 --suffix i2864 --test-only
  (( RUN_RC == 2 )) || fail "duplicate suffix exited $RUN_RC, expected 2"
  assert_no_dispatch
}

smoke_dispatch_keeps_explicit_package_identity() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' smoke --suffix=i2863 --test-only
  (( RUN_RC == 0 )) || fail "smoke lane exited $RUN_RC: $(cat "$RUN_ERR")"
  local -a expected=(
    "$SANDBOX/script-root/scripts/connected-js-smoke.sh"
    --suffix i2863 --test-only
  )
  diff -u <(printf '%s\n' "${expected[@]}") "$RUN_CAPTURE" \
    || fail 'smoke lane did not receive the canonical suffix and test-only arguments'
}

lifecycle_dispatch_preserves_fixture_identity() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' lifecycle --suffix i2863 \
    --port 2222 --container pocketshell-test-agents --run-id js2863-local
  (( RUN_RC == 0 )) || fail "lifecycle lane exited $RUN_RC: $(cat "$RUN_ERR")"
  local -a expected=(
    "$SANDBOX/script-root/scripts/connected-js-lifecycle.sh"
    --suffix i2863 --port 2222 --container pocketshell-test-agents
    --run-id js2863-local
  )
  diff -u <(printf '%s\n' "${expected[@]}") "$RUN_CAPTURE" \
    || fail 'lifecycle lane lost its suffix, Docker port/container, or run ID'
}

composer_dispatch_preserves_pool_port_and_session_prefix() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' composer-docker --suffix i2863 \
    --port 2245 --session-prefix js2863-local
  (( RUN_RC == 0 )) || fail "composer lane exited $RUN_RC: $(cat "$RUN_ERR")"
  local -a expected=(
    "$SANDBOX/script-root/scripts/connected-js-composer-docker.sh"
    --suffix i2863 --port 2245 --session-prefix js2863-local
  )
  diff -u <(printf '%s\n' "${expected[@]}") "$RUN_CAPTURE" \
    || fail 'composer lane lost its suffix, Docker pool port, or session prefix'
}

key_vault_dispatch_preserves_fixture_identity() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' key-vault-docker --suffix i2926 \
    --port 2244 --container pocketshell-test-agents-2244 --run-id js2926-local
  (( RUN_RC == 0 )) || fail "key-vault lane exited $RUN_RC: $(cat "$RUN_ERR")"
  local -a expected=(
    "$SANDBOX/script-root/scripts/connected-js-key-vault-docker.sh"
    --suffix i2926 --port 2244 --container pocketshell-test-agents-2244
    --run-id js2926-local
  )
  diff -u <(printf '%s\n' "${expected[@]}") "$RUN_CAPTURE" \
    || fail 'key-vault lane lost its suffix, Docker port/container, or run ID'
}

foreign_checkout_is_refused_before_lane_dispatch() {
  make_dispatch_fixture
  init_repo "$SANDBOX/foreign"
  run_dispatch "$SANDBOX/foreign" '' smoke --suffix i2863
  (( RUN_RC == 2 )) || fail "foreign checkout exited $RUN_RC, expected 2"
  assert_no_dispatch
  grep -q 'refusing to run connected-test.sh from a different checkout' "$RUN_ERR" \
    || fail 'foreign checkout refusal was missing'
  grep -q 'POCKETSHELL_CONNECTED_TEST_ALLOW_FOREIGN_ROOT' "$RUN_ERR" \
    || fail 'foreign checkout refusal omitted its explicit override'

  run_dispatch "$SANDBOX/foreign" 1 smoke --suffix i2863 --test-only
  (( RUN_RC == 0 )) || fail "explicit foreign-root override exited $RUN_RC"
  grep -qF "testing checkout $SANDBOX/script-root" "$RUN_ERR" \
    || fail 'override did not name the checkout whose code would run'
}

old_gradle_selectors_are_not_forwarded() {
  make_dispatch_fixture
  run_dispatch "$SANDBOX/script-root" '' smoke --suffix i2863 \
    --module app2 :app2:connectedDebugAndroidTest
  # Lane stubs accept arguments, so a nonzero status here must come from the
  # dispatcher rejecting the retired selector itself.
  (( RUN_RC != 0 )) || fail 'retired app2/module selectors reached the lane'
  assert_no_dispatch
}

real_js_lanes_keep_exact_same_run_guards_and_host_oracles() {
  local source="$ROOT_DIR/scripts/connected-js-smoke.sh"
  local lifecycle_script="$ROOT_DIR/scripts/connected-js-lifecycle.sh"
  local smoke_test_source="$ROOT_DIR/android/app/src/androidTest/java/com/pocketshell/app/smoke/JsShellPackagedSmokeTest.java"
  local host_check_line recheck_line
  [[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'JS Android Gradle wrapper is missing'
  [[ -x "$source" && -x "$ROOT_DIR/scripts/connected-js-lifecycle.sh" \
     && -x "$ROOT_DIR/scripts/connected-js-composer-docker.sh" \
     && -x "$ROOT_DIR/scripts/connected-js-key-vault-docker.sh" ]] \
    || fail 'one or more dispatched JS lane runners are missing'

  grep -Fq '"$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest' "$source" \
    || fail 'smoke runner does not use the JS-first :app task under android/'
  grep -Fq 'check-js-smoke-results.py" --results-dir "$RESULTS_DIR"' "$source" \
    || fail 'smoke runner does not validate the report from its own run'
  grep -Fq 'check-js-lifecycle-results.py" --results-dir "$RESULTS_DIR"' "$lifecycle_script" \
    || fail 'lifecycle runner does not validate its exact same-run JUnit report'
  grep -Fq 'check-js-lifecycle-host-evidence.py"' "$lifecycle_script" \
    || fail 'lifecycle runner does not validate independent Docker host evidence'
  grep -Fq -- '--host-evidence-directory "$HOST_EVIDENCE_DIR"' "$lifecycle_script" \
    || fail 'lifecycle host oracle does not keep sidecars outside the exact device artifact directory'
  host_check_line="$(grep -nF 'check-js-lifecycle-host-evidence.py' "$lifecycle_script" | tail -n1 | cut -d: -f1)"
  recheck_line="$(grep -nF 'check-js-lifecycle-results.py" --results-dir "$RESULTS_DIR"' "$lifecycle_script" | tail -n1 | cut -d: -f1)"
  [[ "$host_check_line" =~ ^[0-9]+$ && "$recheck_line" =~ ^[0-9]+$ ]] \
    || fail 'lifecycle runner is missing its host check or post-host exact result revalidation'
  (( recheck_line > host_check_line )) \
    || fail 'lifecycle runner does not revalidate the exact result bundle after writing host evidence'
  grep -Fq 'check-js-composer-journey-results.py" --results-dir "$RESULTS_DIR"' \
    "$ROOT_DIR/scripts/connected-js-composer-docker.sh" \
    || fail 'composer runner does not validate its exact same-run JUnit report'
  grep -Fq 'PASS: host PTY output contained' "$ROOT_DIR/scripts/connected-js-composer-docker.sh" \
    || fail 'composer runner lost its independent remote PTY output oracle'
  grep -Fq 'check-js-key-vault-results.py" --results-dir "$RESULTS_DIR"' \
    "$ROOT_DIR/scripts/connected-js-key-vault-docker.sh" \
    || fail 'key-vault runner does not validate its exact same-run JUnit report'
  grep -Fq 'Accepted publickey for testuser' "$ROOT_DIR/scripts/connected-js-key-vault-docker.sh" \
    || fail 'key-vault runner lost its Docker accepted-fingerprint oracle'
  grep -Fq 'independent_session_list_for_both_credentials=PASS' "$ROOT_DIR/scripts/connected-js-key-vault-docker.sh" \
    || fail 'key-vault runner lost its independent host session-list oracle for imported and generated keys'
  grep -Fq 'secret-nonleak-oracle.txt' "$ROOT_DIR/scripts/connected-js-key-vault-docker.sh" \
    || fail 'key-vault runner lost its secret non-leak evidence'
  grep -Fq 'Exact packaged-shell smoke suite (8 JUnit methods)' "$WRAPPER" \
    || fail 'connected-test help does not describe the eight-method packaged smoke contract'
  grep -Fq 'scrollDomTargetIntoWebViewViewport("[data-testid=open-about]");' "$smoke_test_source" \
    || fail 'About navigation does not scroll its target into the WebView viewport first'
  grep -Fq 'WebView tap target must be fully inside its viewport before coordinate tap:' "$smoke_test_source" \
    || fail 'physical DOM taps do not assert the target is inside the WebView viewport'
  grep -Fq 'WebView tap center must map to visible on-screen pixels before coordinate tap:' "$smoke_test_source" \
    || fail 'physical DOM taps do not assert their mapped point is within visible WebView pixels'
  grep -Fq 'point.optDouble("viewportWidth")' "$smoke_test_source" \
    || fail 'physical DOM tap mapping is not scaled from the captured CSS viewport width'
  grep -Fq 'WebView tap center must map to finite on-screen pixels before coordinate tap:' "$smoke_test_source" \
    || fail 'physical DOM tap mapping does not reject non-finite screen coordinates'

  "$ROOT_DIR/scripts/check-js-smoke-results.py" --self-test
  "$ROOT_DIR/scripts/check-js-lifecycle-results.py" --self-test
  "$ROOT_DIR/scripts/check-js-lifecycle-host-evidence.py" --self-test
  "$ROOT_DIR/scripts/test-js-lifecycle-cleanup.sh"
  "$ROOT_DIR/scripts/check-js-composer-journey-results.py" --self-test
  "$ROOT_DIR/scripts/extract-js-composer-artifacts.py" --self-test
  "$ROOT_DIR/scripts/check-js-key-vault-results.py" --self-test
}

same_emulator_is_serialized_across_worktrees_and_reports_are_run_local() {
  mkdir -p "$SANDBOX/tmp" "$SANDBOX/device-state"
  make_serial_fixture

  start_serial_runner "$SANDBOX/worktree-a" i2863a
  local first_pid="$ACTIVE_PID"
  ACTIVE_RUNNERS+=("$first_pid")
  wait_for_file "$SANDBOX/device-state/started-i2863a" 8 \
    || fail 'first worktree never reached its fake connected Gradle run'

  start_serial_runner "$SANDBOX/worktree-b" i2863b
  local second_pid="$ACTIVE_PID"
  ACTIVE_RUNNERS+=("$second_pid")
  wait_for_pattern "$SANDBOX/i2863b.err" 'queuing for it' 8 \
    || fail 'second worktree did not report queuing on the shared emulator lock'
  if [[ -e "$SANDBOX/device-state/args-i2863b" ]]; then
    fail 'second worktree reached Gradle while the first held the same emulator lock'
  fi
  [[ ! -e "$SANDBOX/device-state/overlap" ]] \
    || fail 'fake emulator observed overlapping worktree mutations'

  touch "$SANDBOX/device-state/release-i2863a"
  wait_runner_success "$first_pid" "$SANDBOX/i2863a.err"
  wait_for_file "$SANDBOX/device-state/started-i2863b" 8 \
    || fail 'second worktree did not reach Gradle after the first released the emulator'
  [[ ! -e "$SANDBOX/device-state/overlap" ]] \
    || fail 'fake emulator observed overlapping worktree mutations after lock handoff'
  touch "$SANDBOX/device-state/release-i2863b"
  wait_runner_success "$second_pid" "$SANDBOX/i2863b.err"

  grep -Fq -- '-PpocketshellAppIdSuffix=i2863a' "$SANDBOX/device-state/args-i2863a" \
    || fail 'first worktree did not pass its unique package suffix to Gradle'
  grep -Fq -- '-PpocketshellAppIdSuffix=i2863b' "$SANDBOX/device-state/args-i2863b" \
    || fail 'second worktree did not pass its unique package suffix to Gradle'
  grep -Fq 'PASS: packaged JS smoke results contain 8 executed tests, 8 passed' \
    "$SANDBOX/i2863a.out" \
    || fail 'first run did not validate its own exact JUnit report'
  grep -Fq 'PASS: packaged JS smoke results contain 8 executed tests, 8 passed' \
    "$SANDBOX/i2863b.out" \
    || fail 'second run did not validate its own exact JUnit report'
  [[ ! -e "$SANDBOX/device-state/overlap" ]] \
    || fail 'fake emulator recorded a cross-worktree overlap'
}

failure_artifacts_are_preserved_after_gradle_failure() {
  python3 "$ROOT_DIR/tests/scripts/connected-js-usage-ports-failure-artifacts-test.py"
}

CASES=(
  no_lane_and_unknown_lane_fail_closed
  suffix_is_required_and_validated_once
  smoke_dispatch_keeps_explicit_package_identity
  lifecycle_dispatch_preserves_fixture_identity
  composer_dispatch_preserves_pool_port_and_session_prefix
  key_vault_dispatch_preserves_fixture_identity
  foreign_checkout_is_refused_before_lane_dispatch
  old_gradle_selectors_are_not_forwarded
  real_js_lanes_keep_exact_same_run_guards_and_host_oracles
  same_emulator_is_serialized_across_worktrees_and_reports_are_run_local
  failure_artifacts_are_preserved_after_gradle_failure
)
EXPECTED_FULL_CASES=11
(( ${#CASES[@]} == EXPECTED_FULL_CASES )) \
  || fail "expected $EXPECTED_FULL_CASES cases; found ${#CASES[@]}"

for case_name in "${CASES[@]}"; do
  run_case "$case_name"
done

(( CASE_COUNT == EXPECTED_FULL_CASES )) \
  || fail "expected $EXPECTED_FULL_CASES cases to run, saw $CASE_COUNT"
printf 'PASS: JS-first connected-test runner contract (%s/%s cases)\n' \
  "$CASE_COUNT" "$EXPECTED_FULL_CASES"

python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)' "$SANDBOX"
