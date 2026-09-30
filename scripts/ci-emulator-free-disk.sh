#!/usr/bin/env bash
# ci-emulator-free-disk.sh — reclaim runner disk before the AVD is created.
#
# Originally extracted from the `Free disk space for the AVD` step of
# .github/workflows/tests.yml's `emulator-journey` job (issue #2134), this
# script now also protects the active Node/pnpm toolcache used by JS CI.
# The workflow calls this file directly.
#
# The inline step ran under Actions' DEFAULT shell, `bash -e {0}` — errexit was
# already on before the body's own `set -uo pipefail`. `set -e` here reproduces
# that exactly, and the body keeps its own `set -uo pipefail` line unchanged, so
# every `|| true` in it still guards the same thing it always did.
set -e

# Issue #760: the AVD create intermittently FATAL-ed with
# "Not enough space to create userdata partition. Available: 6957 MB,
# need 7372 MB" — pure runner disk pressure, not a test failure. The
# ubuntu-latest image ships ~30 GB of preinstalled SDKs/toolchains we do
# not use for this job (.NET, Haskell/GHC, the bundled Android NDK, the
# large CodeQL/boost caches). Reclaim them BEFORE the emulator-runner
# creates the AVD so the userdata partition always fits, then print the
# free space so a future disk regression is visible in the log. The `|| true`
# guards keep the step green if a path is already gone on a newer image.
#
# Issue #771 (round-3, PROVEN ROOT CAUSE): the original #760 version also
# ran
#     sudo rm -rf "$AGENT_TOOLSDIRECTORY"   # == /opt/hostedtoolcache
# which DELETED the JDK that `actions/setup-java@v5` installs under
# `/opt/hostedtoolcache/Java_*` and that `JAVA_HOME` points into
# (e.g. /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.19-10/x64).
# After that delete, EVERY Java tool died with
#     ERROR: JAVA_HOME is set to an invalid directory: …
# so the runner's sdkmanager (and the #771 freshly-reinstalled copy)
# crashed instantly and the emulator never booted. The cmdline-tools were
# never the real problem — the missing JDK was. Fix: prune the OTHER
# tool-caches under $AGENT_TOOLSDIRECTORY but preserve the roots containing
# the live JAVA_HOME, node, and pnpm executables, so Java and JS survive.
set -uo pipefail
echo "::group::Disk before cleanup"
df -h /
echo "::endgroup::"
sudo rm -rf /usr/share/dotnet || true
sudo rm -rf /usr/local/lib/android/sdk/ndk || true
sudo rm -rf /opt/ghc /usr/local/.ghcup || true
sudo rm -rf /usr/local/share/boost || true
sudo rm -rf /opt/hostedtoolcache/CodeQL || true
# Preserve the active JDK, Node, and pnpm toolcache roots while pruning unused
# caches. The helper verifies the exact pre-cleanup Node/pnpm paths still run.
scripts/ci-emulator-prune-toolcache.sh
sudo docker image prune -af || true
echo "::group::Disk after cleanup"
df -h /
echo "::endgroup::"
# Confirm the active JDK survived the prune (issue #771): a deleted
# JAVA_HOME makes every Java tool fail with
# "JAVA_HOME is set to an invalid directory" and the emulator never
# boots. Fail NOW with an explicit infra classification if it's gone.
if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "::error title=EMULATOR INFRA UNAVAILABLE::JAVA_HOME=$JAVA_HOME has no bin/java after disk cleanup; the JDK was deleted (issue #771 regression guard). This is an infra setup error, not a test failure."
  exit 1
fi
avail_mb="$(df -m --output=avail / | tail -1 | tr -d ' ')"
echo "Available on / after cleanup: ${avail_mb} MB"
# The AVD userdata partition needs ~7372 MB; require a safe margin so
# the create cannot FATAL on disk. If it still can't be freed, fail
# NOW with an explicit infra classification rather than letting the
# emulator-runner FATAL deeper in with a confusing message.
if [[ "$avail_mb" -lt 9000 ]]; then
  echo "::error title=EMULATOR INFRA UNAVAILABLE::Only ${avail_mb} MB free on / after cleanup; the AVD userdata partition needs ~7372 MB. This is a runner-disk infra shortage (issue #760), not a test failure."
  exit 1
fi
