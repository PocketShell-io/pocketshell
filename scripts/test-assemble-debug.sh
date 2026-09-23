#!/usr/bin/env bash
# Fast checks for the JS-first local debug-APK wrapper.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT_DIR/scripts/assemble-debug.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

cmd="$("$SCRIPT" --print-command)"
printf '%s\n' "$cmd" | grep -Fq 'pnpm build:web' \
  || fail "local build must compile the JS application; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq 'pnpm cap:sync' \
  || fail "local build must sync Capacitor; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq ':app:assembleDebug' \
  || fail "local build must assemble the Capacitor debug APK; got: $cmd"
printf '%s\n' "$cmd" | grep -Fq 'android/gradlew' \
  || fail "local build must use the generated Android wrapper; got: $cmd"
for retired in ':app2:assembleDebug' 'assembleDebugAndroidTest' 'cgroup-run' '--no-daemon' '--no-build-cache' '--max-workers=1'; do
  printf '%s\n' "$cmd" | grep -Fq -- "$retired" \
    && fail "local debug build must not invoke retired product/release-gate command '$retired'; got: $cmd"
done

cmd="$("$SCRIPT" --suffix i2855 --print-command)"
printf '%s\n' "$cmd" | grep -Fq -- '-PpocketshellAppIdSuffix=i2855' \
  || fail "--suffix must isolate a debug install; got: $cmd"
if "$SCRIPT" --suffix '../bad' --print-command >/dev/null 2>&1; then
  fail "--suffix must reject package-name characters outside the Gradle contract"
fi
if "$SCRIPT" --suffix --print-command >/dev/null 2>&1; then
  fail "--suffix without a token must fail"
fi

grep -Fq 'ADB="$ADB" "$ROOT_DIR/scripts/install-update-apk.sh" "$apk"' "$SCRIPT" \
  || fail "--install must pass the selected ADB to the data-preserving installer"

if "$SCRIPT" --nonsense --print-command >/dev/null 2>&1; then
  fail "an unknown argument must fail"
fi

printf 'PASS: scripts/test-assemble-debug.sh\n'
