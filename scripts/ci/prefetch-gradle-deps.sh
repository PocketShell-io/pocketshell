#!/usr/bin/env bash
# Resolve (and cache) all Android app + androidTest dependencies with bounded
# retry/backoff so a transient Maven Central 429/5xx does not fail a CI job.
# Fails loudly, naming the unresolved dependency and any 429, if all attempts fail.
set -uo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INIT="$ROOT_DIR/scripts/ci/resolve-all.init.gradle"
LOG="$(mktemp)"
ATTEMPTS="${PREFETCH_ATTEMPTS:-5}"
for attempt in $(seq 1 "$ATTEMPTS"); do
  if "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" -I "$INIT" :app:resolveAndroidDeps \
      --console=plain --no-daemon >"$LOG" 2>&1; then
    echo "Gradle dependencies resolved (attempt $attempt)"
    exit 0
  fi
  echo "dependency resolution attempt ${attempt}/${ATTEMPTS} failed:" >&2
  grep -E "Could not (resolve|GET|HEAD|download)|429|Too Many Requests" "$LOG" | head -5 >&2
  [ "$attempt" -lt "$ATTEMPTS" ] && sleep $((attempt * 20))
done
deps="$(grep -oE "Could not (resolve|download|GET) [^ ]+" "$LOG" | sort -u | head -10 | tr '\n' ';')"
rate="no HTTP 429 seen"
grep -qE "429|Too Many Requests" "$LOG" && rate="HTTP 429 (rate limited by Maven Central) seen"
echo "::error title=Gradle dependency resolution failed::after ${ATTEMPTS} attempts: ${rate}; unresolved: ${deps:-see log}"
echo "FAIL: could not resolve Android Gradle dependencies after ${ATTEMPTS} attempts (${rate}). Unresolved: ${deps:-unknown}" >&2
tail -60 "$LOG" >&2
exit 1
