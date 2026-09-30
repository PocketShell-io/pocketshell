#!/usr/bin/env bash
# Build the local JS/Capacitor debug APK. This is not the release/visual-audit
# profile; it keeps the Gradle daemon and build cache for quick iteration.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/home/alexey/Android/Sdk}}}"
ADB="${ASSEMBLE_DEBUG_ADB:-${ADB:-$ANDROID_SDK/platform-tools/adb}}"
PNPM="${PNPM:-pnpm}"
INSTALL=0
PRINT_COMMAND=0
APP_ID_SUFFIX=""

usage() {
  cat <<'USAGE'
Usage: scripts/assemble-debug.sh [options]

Build the Vue/TypeScript app, sync Capacitor, and assemble a debug APK.

Options:
  --suffix <token>      Append a package suffix for an isolated debug install.
  --install             adb install -r the debug APK after a successful build.
  --print-command       Print the build commands and exit (no build).
  -h, --help            Show this help.

Environment:
  ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT
  PNPM                    pnpm executable (default: pnpm)
  ADB / ASSEMBLE_DEBUG_ADB
  ANDROID_SERIAL          pin which device --install uses
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h)
      usage
      exit 0
      ;;
    --suffix)
      [[ $# -ge 2 ]] || fail "--suffix requires a token"
      APP_ID_SUFFIX="$2"
      shift 2
      ;;
    --install)
      INSTALL=1
      shift
      ;;
    --print-command)
      PRINT_COMMAND=1
      shift
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

if [[ -n "$APP_ID_SUFFIX" && ! "$APP_ID_SUFFIX" =~ ^[A-Za-z0-9._]+$ ]]; then
  fail "--suffix must match [A-Za-z0-9._]+"
fi

GRADLE_ARGS=("-p" "$ROOT_DIR/android" :app:assembleDebug --stacktrace)
if [[ -n "$APP_ID_SUFFIX" ]]; then
  GRADLE_ARGS+=("-PpocketshellAppIdSuffix=$APP_ID_SUFFIX")
fi

if [[ "$PRINT_COMMAND" -eq 1 ]]; then
  printf '%s\n' "$PNPM build:web" "$PNPM cap:sync"
  printf '%q' "$ROOT_DIR/android/gradlew"
  printf ' %q' "${GRADLE_ARGS[@]}"
  printf '\n'
  exit 0
fi

command -v "$PNPM" >/dev/null || fail "pnpm is required; install pnpm 12.5.1 and run pnpm install --frozen-lockfile"
[[ -x "$ROOT_DIR/node_modules/.bin/vite" ]] \
  || fail "JavaScript dependencies are missing; run pnpm install --frozen-lockfile"

export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"

printf 'PocketShell JS-first local debug APK\n'
printf '  app ID suffix: %s\n' "${APP_ID_SUFFIX:-none}"
printf '  install: %s\n' "$([[ "$INSTALL" -eq 1 ]] && echo yes || echo no)"

start_seconds="$(date +%s)"
"$PNPM" build:web
"$PNPM" cap:sync
"$ROOT_DIR/android/gradlew" "${GRADLE_ARGS[@]}"
end_seconds="$(date +%s)"

apk="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$apk" ]] || fail "expected APK missing at $apk"
printf 'PASS: assembled %s in %ss\n' "$apk" "$((end_seconds - start_seconds))"

if [[ "$INSTALL" -eq 1 ]]; then
  ADB="$ADB" "$ROOT_DIR/scripts/install-update-apk.sh" "$apk"
fi
