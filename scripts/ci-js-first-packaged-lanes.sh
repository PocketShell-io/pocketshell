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
  --session-prefix "js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"; then
  composer_status=0
else
  composer_status=$?
fi

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

printf 'Packaged API 35 lane statuses: smoke=%s lifecycle=%s usage-ports=%s files=%s durable-storage=%s composer=%s settings=%s key-vault=%s signed-upgrade=%s smoke-junit-copy=%s\n' \
  "$smoke_status" "$lifecycle_status" "$usage_status" "$files_status" "$durable_status" "$composer_status" "$settings_status" "$key_vault_status" "$signed_upgrade_status" "$copy_status"

if (( smoke_status != 0 || lifecycle_status != 0 || usage_status != 0 || files_status != 0 || durable_status != 0 || composer_status != 0 || settings_status != 0 || key_vault_status != 0 || signed_upgrade_status != 0 || copy_status != 0 )); then
  exit 1
fi
