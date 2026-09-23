#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-js-lifecycle-cleanup.XXXXXX")"
cleanup_tmp_root() {
  python3 - "$TMP_ROOT" <<'PY'
from pathlib import Path
import shutil
import sys
shutil.rmtree(Path(sys.argv[1]), ignore_errors=True)
PY
}
trap cleanup_tmp_root EXIT

AVD_LOCK="$TMP_ROOT/locks/avd.lock"
GRADLE_ROOT="$TMP_ROOT/gradle"
mkdir -p "$GRADLE_ROOT"
GRADLE_LOCK_DIR="$TMP_ROOT/locks/gradle"

set +e
bash -c '
  set -euo pipefail
  source "$1/scripts/lib/gradle-output-lock.sh"
  source "$1/scripts/lib/avd-lock.sh"
  source "$1/scripts/lib/js-lifecycle-cleanup.sh"
  export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
  export POCKETSHELL_AVD_LOCK_FILE="$2"
  export POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$4"
  pocketshell_acquire_gradle_output_lock "$3" "" "js-lifecycle-cleanup-self-test"
  pocketshell_acquire_avd_lock "$1"

  watcher_pid=""
  stop_host_socket_watcher() {
    if [[ -n "$watcher_pid" ]]; then
      kill "$watcher_pid" 2>/dev/null || true
      wait "$watcher_pid" 2>/dev/null || true
      watcher_pid=""
    fi
  }
  pocketshell_install_js_lifecycle_cleanup_trap

  bash -c "exit 23" &
  watcher_pid="$!"
  set +e
  wait "$watcher_pid"
  watcher_status="$?"
  set -e
  [[ "$watcher_status" == 23 ]] || {
    printf "FAIL: simulated host watcher returned %s, expected 23\\n" "$watcher_status" >&2
    exit 1
  }
  watcher_pid=""
  printf "Observed simulated host watcher failure (exit %s); forcing runner failure to exercise EXIT cleanup.\\n" "$watcher_status"
  exit 42
' _ "$ROOT_DIR" "$AVD_LOCK" "$GRADLE_ROOT" "$GRADLE_LOCK_DIR"
child_status=$?
set -e
if [[ "$child_status" != 42 ]]; then
  printf 'FAIL: cleanup probe child exited %s, expected simulated runner exit 42\n' "$child_status" >&2
  exit 1
fi

if ! flock -n 9 9>>"$AVD_LOCK"; then
  printf 'FAIL: failed watcher left the AVD lock held\n' >&2
  exit 1
fi
GRADLE_LOCK="$(POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$GRADLE_LOCK_DIR" bash -c '
  source "$1/scripts/lib/gradle-output-lock.sh"
  pocketshell_gradle_output_lock_file "$2" ""
' _ "$ROOT_DIR" "$GRADLE_ROOT")"
if ! flock -n 9 9>>"$GRADLE_LOCK"; then
  printf 'FAIL: failed watcher left the Gradle output lock held\n' >&2
  exit 1
fi
printf 'PASS: failed host watcher still releases AVD and Gradle locks\n'
