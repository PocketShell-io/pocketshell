#!/usr/bin/env bash
# Build-profile guards for the `Static guards` job of .github/workflows/tests.yml.
#
# The local APK assemble profile must stay on the FAST profile (daemon + cache +
# multi-worker) and must not silently pick up the release-gate
# --no-daemon/--no-build-cache/--max-workers=1 flags. Cheap, no Gradle.
#
# Issue #2515: the tag-triggered Build workflow must rename/upload/release the
# app2 APK (`app2/build/outputs/apk/debug/app2-debug.apk`), not the deleted
# `app` module output. v0.5.0's Build died on `mv app-debug.apk`. Cheap grep
# of .github/workflows/build.yml, no Gradle.
#
# Issue #2570: no module may declare a native build again. #2566 deleted the
# last `externalNativeBuild` (core-terminal's vendored local-pty JNI), so #2570
# deleted the Build workflow's corrupted-NDK-download retry wrapper (#1581)
# along with its shell test — a hard cut, no dormant fallback. The guard makes
# a reintroduced native build (which would silently regain an unprotected
# on-demand NDK download on the release path) a loud per-push failure.
#
# Issue #2578: pin the #2566 hard cut itself, next to the native-build guard
# above. The vendored terminal is remote-only: no local-pty ByteQueue, no JNI,
# no mShellPid, no MSG_NEW_INPUT anywhere in the product sources this repo
# owns. A single grep, blocking the lane the same way the other guards here do.
#
# These guards live in one script (rather than inline `run:` blocks) because
# tests.yml is held under the 128 KiB file-size hygiene threshold with 1 KiB of
# required headroom; see scripts/check-file-size-hygiene.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

chmod +x scripts/assemble-debug.sh scripts/test-assemble-debug.sh \
  scripts/test-build-workflow-apk-path.sh scripts/check-no-native-build.sh

scripts/test-assemble-debug.sh
scripts/test-build-workflow-apk-path.sh --self-test
scripts/test-build-workflow-apk-path.sh
scripts/check-no-native-build.sh --self-test
scripts/check-no-native-build.sh

# Issue #2578, folding in the #2566 reviewer's ask: the deleted vendored
# local-pty plumbing must not seep back under any of its old names. Empty is
# the only green; every hit is a failure with the match lines printed. The
# repo check first: git grep over a non-repo would exit non-zero into the
# `|| true` and PASS having scanned nothing — the vacuous green this lane
# exists to make impossible.
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ci-build-profile-guards: FAIL — #2578 vendored-cut guard needs a git checkout to scan" >&2
  exit 1
fi
vendored_cut_hits="$(git grep -n -E 'ByteQueue|JNI|mShellPid|MSG_NEW_INPUT' -- app2/src shared/core-terminal/src || true)"
if [[ -n "$vendored_cut_hits" ]]; then
  echo "ci-build-profile-guards: FAIL — #2566 vendored local-pty symbols reappeared in product sources:" >&2
  printf '%s\n' "$vendored_cut_hits" >&2
  exit 1
fi
echo "ci-build-profile-guards: #2566 vendored cut intact (no ByteQueue/JNI/mShellPid/MSG_NEW_INPUT in app2/src, shared/core-terminal/src)"
