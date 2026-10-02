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

if scripts/connected-js-files-docker.sh \
  --suffix i2858ci \
  --port 2222 \
  --container pocketshell-test-agents \
  --run-id "js2858-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --test-only; then
  files_status=0
else
  files_status=$?
fi

# Issue #2993: user-data writes must survive a kill right after the UI
# acknowledged them. It builds its own i2993ci suffix into the shared
# app-debug.apk output, so it runs before the composer and key-vault builds and
# never inside the key-vault -> signed-upgrade window.
if scripts/connected-js-durable-storage.sh \
  --suffix i2993ci \
  --run-id "js2993-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"; then
  durable_status=0
else
  durable_status=$?
fi

if scripts/connected-js-composer-docker.sh \
  --suffix i2891ci \
  --port 2245 \
  --session-prefix "js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --force-first-post-attach-tap-miss \
  --composer-focus-max-attempts 2; then
  composer_status=0
else
  composer_status=$?
fi

composer_root="android/app/build/outputs/js-composer"
composer_prefix="js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-"
composer_junit_copy_status=0
shopt -s nullglob
composer_runs=("$composer_root/$composer_prefix"*)
if (( ${#composer_runs[@]} != 1 )); then
  printf 'FAIL: expected exactly one run-scoped composer evidence directory for %s; found %s\n' \
    "$composer_prefix" "${#composer_runs[@]}" >&2
  composer_junit_copy_status=1
else
  composer_run="${composer_runs[0]}"
  for phase in prepare resume; do
    if [[ ! -s "$composer_run/phase-$phase/TEST-composer.xml" ]]; then
      printf 'FAIL: packaged composer %s phase has no same-run JUnit report\n' "$phase" >&2
      composer_junit_copy_status=1
    fi
  done
fi
shopt -u nullglob

composer_junit_status=0
if (( composer_junit_copy_status != 0 )); then
  composer_junit_status=1
else
  for phase in prepare resume; do
    if ! scripts/check-js-composer-journey-results.py --results-dir "$composer_run/phase-$phase"; then
      composer_junit_status=1
    fi
  done
fi

if scripts/connected-js-hotkeys-docker.sh \
  --suffix i2884ci \
  --port 2243 \
  --session-prefix "js2884-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"; then
  hotkeys_status=0
else
  hotkeys_status=$?
fi

hotkeys_root="android/app/build/outputs/js-hotkeys"
hotkeys_prefix="js2884-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-"
hotkeys_junit_status=0
shopt -s nullglob
hotkeys_runs=("$hotkeys_root/$hotkeys_prefix"*)
if (( ${#hotkeys_runs[@]} != 1 )); then
  printf 'FAIL: expected exactly one run-scoped fast-key evidence directory for %s; found %s\n' \
    "$hotkeys_prefix" "${#hotkeys_runs[@]}" >&2
  hotkeys_junit_status=1
elif ! scripts/check-js-hotkeys-journey-results.py --results-dir "${hotkeys_runs[0]}"; then
  hotkeys_junit_status=1
fi
shopt -u nullglob

if scripts/connected-js-settings.sh \
  --suffix i2855ci \
  --port 2222 \
  --container pocketshell-test-agents \
  --run-id "js2861s-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --test-only; then
  settings_status=0
else
  settings_status=$?
fi

# Google sign-in and settings sync (#3020): fake Google and fake sync API
# in-process, no Docker fixture.
if scripts/connected-js-account-sync.sh \
  --suffix i2855ci \
  --run-id "js3020-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --test-only; then
  account_sync_status=0
else
  account_sync_status=$?
fi

# The shared PocketShell app (#2936): list, attach, re-attach and type, on
# its own isolated agents lane (the workflow starts 2243 for it).
if scripts/connected-js-shared-app.sh \
  --suffix i2855ci \
  --port 2243 \
  --test-only; then
  shared_app_status=0
else
  shared_app_status=$?
fi

if scripts/connected-js-key-vault-docker.sh \
  --suffix i2926ci \
  --port 2244 \
  --container pocketshell-test-agents-2244 \
  --run-id "js2926-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}" \
  --test-only; then
  key_vault_status=0
else
  key_vault_status=$?
fi

# Signed 0.5.6-to-candidate upgrade (#2926/#2860). It must run last: it builds
# and installs the unsuffixed com.pocketshell.app over a pinned signed v0.5.6
# install, which the suffixed lanes above never touch. It reuses the key-vault
# lane's isolated fixture after that lane has restored authorized_keys.
if scripts/connected-js-key-vault-signed-upgrade.sh \
  --port 2244 \
  --container pocketshell-test-agents-2244 \
  --run-id "up2926-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"; then
  signed_upgrade_status=0
else
  signed_upgrade_status=$?
fi

printf 'Packaged API 35 lane statuses: smoke=%s lifecycle=%s usage-ports=%s files=%s composer=%s composer-junit-copy=%s composer-junit=%s hotkeys=%s hotkeys-junit=%s durable-storage=%s settings=%s account-sync=%s shared-app=%s key-vault=%s signed-upgrade=%s smoke-junit-copy=%s\n' \
  "$smoke_status" "$lifecycle_status" "$usage_status" "$files_status" "$composer_status" \
  "$composer_junit_copy_status" "$composer_junit_status" "$hotkeys_status" \
  "$hotkeys_junit_status" "$durable_status" "$settings_status" "$account_sync_status" "$shared_app_status" \
  "$key_vault_status" "$signed_upgrade_status" "$copy_status"

if (( smoke_status != 0 || lifecycle_status != 0 || usage_status != 0 || files_status != 0 \
      || composer_status != 0 || composer_junit_copy_status != 0 || composer_junit_status != 0 \
      || hotkeys_status != 0 || hotkeys_junit_status != 0 || durable_status != 0 \
      || settings_status != 0 || account_sync_status != 0 || shared_app_status != 0 || key_vault_status != 0 \
      || signed_upgrade_status != 0 || copy_status != 0 )); then
  exit 1
fi
