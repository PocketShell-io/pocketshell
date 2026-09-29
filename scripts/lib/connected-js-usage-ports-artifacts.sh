#!/usr/bin/env bash

pocketshell_reset_connected_js_usage_ports_outputs() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import shutil
import sys

for raw_path in sys.argv[1:]:
    path = Path(raw_path)
    if path.exists():
        shutil.rmtree(path)
PY
}

pocketshell_preserve_connected_js_usage_ports_failure_outputs() {
  local results_dir="$1"
  local reports_dir="$2"
  local artifacts_dir="$3"
  local diagnostics_dir="$artifacts_dir/failure-diagnostics"

  mkdir -p "$diagnostics_dir"
  if [[ -d "$results_dir" ]]; then
    if cp -a "$results_dir" "$artifacts_dir/instrumentation-results"; then
      printf 'Preserved partial JUnit results under %s\n' \
        "$artifacts_dir/instrumentation-results"
    else
      printf 'WARNING: could not preserve partial JUnit results from %s\n' \
        "$results_dir" >&2
    fi
  else
    printf 'No instrumentation results directory to preserve after test failure: %s\n' \
      "$results_dir" >&2
  fi
  if [[ -d "$reports_dir" ]]; then
    cp -a "$reports_dir" "$diagnostics_dir/androidTest-report" \
      || printf 'WARNING: could not preserve Android test report from %s\n' \
        "$reports_dir" >&2
  fi
}

pocketshell_run_connected_js_usage_ports_gradle() {
  local results_dir="$1"
  local reports_dir="$2"
  local artifacts_dir="$3"
  local test_exit_code
  shift 3

  set -o pipefail
  if "$@" 2>&1 | tee "$artifacts_dir/gradle-connected.log"; then
    return 0
  else
    test_exit_code=$?
    pocketshell_preserve_connected_js_usage_ports_failure_outputs \
      "$results_dir" "$reports_dir" "$artifacts_dir"
    return "$test_exit_code"
  fi
}
