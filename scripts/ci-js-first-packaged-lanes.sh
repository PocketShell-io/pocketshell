#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$ROOT_DIR"

: "${GITHUB_RUN_ID:?GITHUB_RUN_ID is required for packaged CI evidence}"
: "${GITHUB_RUN_ATTEMPT:?GITHUB_RUN_ATTEMPT is required for packaged CI evidence}"

if scripts/connected-js-smoke.sh --suffix i2855ci --test-only; then
  smoke_status=0
else
  smoke_status=$?
fi

smoke_results_dir="android/app/build/outputs/js-smoke-results"
connected_results_dir="android/app/build/outputs/androidTest-results/connected/debug"
copy_status=0
mkdir -p "$smoke_results_dir" || copy_status=$?
if (( copy_status == 0 )); then
  shopt -s nullglob
  junit_files=("$connected_results_dir"/TEST-*.xml)
  if (( ${#junit_files[@]} == 0 )); then
    printf 'FAIL: packaged smoke run produced no JUnit files in %s\n' \
      "$connected_results_dir" >&2
    copy_status=1
  else
    cp -a -- "${junit_files[@]}" "$smoke_results_dir/" || copy_status=$?
  fi
fi

if scripts/connected-js-lifecycle.sh \
  --suffix i2855ci \
  --port 2222 \
  --container pocketshell-test-agents \
  --run-id "js2861-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --test-only; then
  lifecycle_status=0
else
  lifecycle_status=$?
fi

if scripts/connected-js-usage-ports.sh \
  --suffix i2855ci \
  --port 2222 \
  --container pocketshell-test-agents \
  --run-id "js2859-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --test-only; then
  usage_status=0
else
  usage_status=$?
fi

if scripts/connected-js-composer-docker.sh \
  --suffix i2891ci \
  --port 2245 \
  --session-prefix "js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"; then
  composer_status=0
else
  composer_status=$?
fi

printf 'Packaged API 35 lane statuses: smoke=%s lifecycle=%s usage-ports=%s composer=%s smoke-junit-copy=%s\n' \
  "$smoke_status" "$lifecycle_status" "$usage_status" "$composer_status" "$copy_status"

if (( smoke_status != 0 || lifecycle_status != 0 || usage_status != 0 || composer_status != 0 || copy_status != 0 )); then
  exit 1
fi
