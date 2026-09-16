#!/usr/bin/env bash
# Self-test for scripts/ci-release-validation-noxml-rootcause.sh (issue #2675).
#
# No network, no real repo, no Actions environment: failed-step log content
# comes through the --failed-log seam, disk evidence through
# RELEASE_NOXML_DISK_ROOT, gh through a stubbed binary, and the step summary
# through a sandbox file. GITHUB_RUN_ID/GITHUB_REPOSITORY are explicitly
# emptied so a variable leaking from a calling environment can never steer a
# case onto the gh path. Covers: a boot-tier log (run 34759080447's actual
# signatures) surfaces the adb/snapshot excerpts and the boot verdict; a GL
# log surfaces the GL signature; an EMPTY log must NOT claim any cause
# (anti-vacuous: a diagnosis that always says "boot tier" is decoration); a
# failing gh is best-effort, not fatal; the validation chain's own summary.md
# is quoted when present; the ::error annotation appears exactly once; and a
# usage error exits 2.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-release-validation-noxml-rootcause.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"
[[ -x "$TARGET" ]] || chmod +x "$TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

# The exact failure shape of run 34759080447 (2026-09-13): adb daemon
# unreachable, snapshot load warning, then the runner action's process death.
BOOT_LOG="$SANDBOX/failed-boot.log"
cat > "$BOOT_LOG" <<'LOG'
2026-09-13T13:11:41Z [command]/usr/bin/sh -c /usr/local/lib/android/sdk/emulator/emulator -port 5554 -avd test -no-snapshot-save -no-window -gpu swiftshader_indirect &
2026-09-13T13:11:42Z ERROR        | Unable to connect to adb daemon on port: 5037
2026-09-13T13:11:45Z INFO         | Loading snapshot 'default_boot'...
2026-09-13T13:11:45Z WARNING      | Device 'cache' does not have the requested snapshot 'default_boot'
2026-09-13T13:11:45Z WARNING      | Failed to load snapshot 'default_boot'
2026-09-13T13:12:31Z ##[error]The process '/usr/bin/sh' failed with exit code 1
2026-09-13T13:12:32Z USER_INFO    | Snapshots have been disabled by the user, save request is ignored.
LOG

# A log with only the GL-tier signatures the #449 comment documents.
GL_LOG="$SANDBOX/failed-gl.log"
cat > "$GL_LOG" <<'LOG'
2026-09-13T14:00:01Z E GLESv1: Failed to initialize 101010-2 format, error=EGL_NOT_INITIALIZED
2026-09-13T14:05:00Z androidx.test: No compose hierarchies found for target semantics
LOG

EMPTY_DISK="$SANDBOX/empty-disk/build/release-emulator-validation"
mkdir -p "$EMPTY_DISK"

# Every case runs with the Actions env explicitly emptied (see header).
run_case() {
  local summary="$1"; shift
  env -u GITHUB_RUN_ID -u GITHUB_REPOSITORY -u GH_TOKEN \
    RELEASE_NOXML_DISK_ROOT="$EMPTY_DISK" \
    GITHUB_STEP_SUMMARY="$summary" \
    bash "$TARGET" "$@" 2>&1
}

# -----------------------------------------------------------------------
# 1. Boot-tier log: excerpts surfaced, boot verdict named, annotation once.
SUM="$SANDBOX/summary-1.md"
out="$(run_case "$SUM" --failed-log "$BOOT_LOG")"
echo "$out" | grep -q "Unable to connect to adb daemon on port: 5037" \
  || fail "boot log case must surface the adb daemon excerpt: $out"
echo "$out" | grep -q "Failed to load snapshot" \
  || fail "boot log case must surface the snapshot excerpt: $out"
echo "$out" | grep -q "Boot-tier failure suspected" \
  || fail "boot log case must name the boot-tier verdict: $out"
echo "$out" | grep -q "Release-gate guard BLOCKED (D37)" \
  && fail "boot log case must NOT claim the guard verdict (boot signatures are not guard evidence): $out"
echo "$out" | grep -q "34759080447" \
  || fail "boot log case must cite the observed run: $out"
[[ "$(echo "$out" | grep -c "^::error title=Release validation produced no JUnit XML")" -eq 1 ]] \
  || fail "exactly one ::error annotation expected, got: $out"
grep -q "### Root cause (provisional)" "$SUM" \
  || fail "step summary must gain the root-cause section"
grep -q "Unable to connect to adb daemon" "$SUM" \
  || fail "step summary must carry the excerpts"
pass "boot-tier log surfaces adb/snapshot excerpts, boot verdict, one annotation, and the step-summary section"

# -----------------------------------------------------------------------
# 2. GL-only log: GL signature surfaced (not silently swallowed).
SUM="$SANDBOX/summary-2.md"
out="$(run_case "$SUM" --failed-log "$GL_LOG")"
echo "$out" | grep -q "No compose hierarchies found" \
  || fail "GL case must surface the compose-hierarchies excerpt: $out"
echo "$out" | grep -q "Boot-tier failure suspected" \
  || fail "GL case still names the boot tier (GL loss is a boot-tier signature): $out"
pass "GL-tier log surfaces the GL signature"

# -----------------------------------------------------------------------
# 3. Empty log + empty disk: NO cause invented (anti-vacuous guard).
SUM="$SANDBOX/summary-3.md"
: > "$SANDBOX/empty.log"
out="$(run_case "$SUM" --failed-log "$SANDBOX/empty.log")"
echo "$out" | grep -q "No known failure signature found" \
  || fail "empty-evidence case must say no signature was found: $out"
echo "$out" | grep -q "Boot-tier failure suspected" \
  && fail "empty-evidence case must NOT claim a boot-tier cause: $out"
echo "$out" | grep -q "Run emulator-only release validation" \
  || fail "empty-evidence case must point at the authoritative step log: $out"
pass "empty evidence honestly reports no signature instead of inventing a cause"

# -----------------------------------------------------------------------
# 4. gh failing mid-call is best-effort: exit 0, note, honest fallback.
mkdir -p "$SANDBOX/bin"
cat > "$SANDBOX/bin/gh" <<'FAKEGH'
#!/usr/bin/env bash
echo "gh: simulated API failure" >&2
exit 1
FAKEGH
chmod +x "$SANDBOX/bin/gh"
SUM="$SANDBOX/summary-4.md"
set +e
out="$(env GH_TOKEN= \
  GITHUB_RUN_ID=123 GITHUB_REPOSITORY=owner/repo \
  RELEASE_NOXML_DISK_ROOT="$EMPTY_DISK" \
  GITHUB_STEP_SUMMARY="$SUM" \
  bash "$TARGET" --gh "$SANDBOX/bin/gh" 2>&1)"
rc=$?
set -e
[[ "$rc" -eq 0 ]] || fail "a failing gh must stay best-effort (exit 0), got $rc: $out"
echo "$out" | grep -q "gh could not fetch failed-step logs" \
  || fail "a failing gh must be reported as an evidence-collection note: $out"
echo "$out" | grep -q "No known failure signature found" \
  || fail "after a gh failure with no disk evidence, no cause may be claimed: $out"
pass "failing gh is best-effort: exit 0, collection note, no invented cause"

# -----------------------------------------------------------------------
# 4b/4c. Issue #2716: the D37 nightly fault/bootstrap guard BLOCKs BEFORE any
# Gradle work — zero tests, no XML — and its transcript lives in
# nightly-fault-guard/result.txt (NOT *.log, so the generic find misses it).
# The diagnosis pass must name the guard verdict as the root cause from local
# disk evidence alone (the in-run `gh run view --log-failed` fetch of the
# current run can never succeed — the run is not finalized while its own
# ledger step executes, which is why runs 35051420588/35092088955/35096339243
# all said "No known failure signature found" with the BLOCK line sitting in
# summary.md). Both observed shapes: STALE and RED.
GUARD_DISK="$SANDBOX/disk-guard/build/release-emulator-validation"
mkdir -p "$GUARD_DISK/gha-x/nightly-fault-guard"
cat > "$GUARD_DISK/gha-x/nightly-fault-guard/result.txt" <<'LOG'
Nightly fault run: workflow=app2.yml id=35047836550 status=completed fault-verdict-job-conclusion=failure headSha=abe00b2b0638d6586c926449f386ad6cd8e71b85
Release HEAD=43115fb2c09b8b8ede3b8e91bbee3dfed4273086 head_is_ancestor=no
BLOCK: latest journey run tested headSha=abe00b2b0638d6586c926449f386ad6cd8e71b85 which does NOT contain the release HEAD (43115fb2c09b8b8ede3b8e91bbee3dfed4273086) — the run is STALE for this release. Re-run the 'app2' workflow on the release commit.
LOG
printf '# PocketShell Release Emulator Validation\n\nAutomated status: FAIL\n' > "$GUARD_DISK/gha-x/summary.md"
SUM="$SANDBOX/summary-5b.md"
out="$(env -u GITHUB_RUN_ID -u GITHUB_REPOSITORY \
  RELEASE_NOXML_DISK_ROOT="$GUARD_DISK" GITHUB_STEP_SUMMARY="$SUM" \
  bash "$TARGET" --run-id gha-x --failed-log "$SANDBOX/empty.log" 2>&1)"
echo "$out" | grep -q "Release-gate guard BLOCKED (D37)" \
  || fail "guard STALE case must name the D37 guard verdict: $out"
echo "$out" | grep -q "is STALE for this release" \
  || fail "guard STALE case must surface the guard's BLOCK excerpt: $out"
echo "$out" | grep -q "nightly-fault-guard/result.txt" \
  || fail "guard case must cite the guard transcript path: $out"
echo "$out" | grep -q "Boot-tier failure suspected" \
  && fail "guard case must NOT claim a boot-tier cause: $out"
echo "$out" | grep -q "No known failure signature found" \
  && fail "guard case must NOT fall through to the unknown-signature verdict: $out"
[[ "$(echo "$out" | grep -c "^::error title=Release validation produced no JUnit XML")" -eq 1 ]] \
  || fail "guard case must keep exactly one ::error annotation, got: $out"
grep -q "Release-gate guard BLOCKED (D37)" "$SUM" \
  || fail "guard case must carry the verdict into the step summary"
pass "guard STALE transcript is diagnosed as the D37 guard verdict from disk alone"

# RED verdict shape (run 35096339243, 2026-09-16): same disk layout.
GUARD_DISK2="$SANDBOX/disk-guard2/build/release-emulator-validation"
mkdir -p "$GUARD_DISK2/gha-y/nightly-fault-guard"
cat > "$GUARD_DISK2/gha-y/nightly-fault-guard/result.txt" <<'LOG'
Nightly fault run: workflow=app2.yml id=35091852693 status=completed fault-verdict-job-conclusion=failure headSha=0b5c0df1db2a971e8ab9ccdb510607be73dff4bc
Release HEAD=0b5c0df1db2a971e8ab9ccdb510607be73dff4bc head_is_ancestor=yes
BLOCK: latest nightly fault-injection safety verdict is RED (fault-verdict job conclusion='failure'). The safety suite (toxiproxy network-fault + bootstrap matrix) failed on the release line — fix the failure or re-run before releasing.
LOG
printf '# PocketShell Release Emulator Validation\n\nAutomated status: FAIL\n' > "$GUARD_DISK2/gha-y/summary.md"
SUM="$SANDBOX/summary-5c.md"
out="$(env -u GITHUB_RUN_ID -u GITHUB_REPOSITORY \
  RELEASE_NOXML_DISK_ROOT="$GUARD_DISK2" GITHUB_STEP_SUMMARY="$SUM" \
  bash "$TARGET" --run-id gha-y --failed-log "$SANDBOX/empty.log" 2>&1)"
echo "$out" | grep -q "Release-gate guard BLOCKED (D37)" \
  || fail "guard RED case must name the D37 guard verdict: $out"
echo "$out" | grep -q "safety verdict is RED" \
  || fail "guard RED case must surface the RED excerpt: $out"
echo "$out" | grep -q "Boot-tier failure suspected" \
  && fail "guard RED case must NOT claim a boot-tier cause: $out"
pass "guard RED transcript is diagnosed as the D37 guard verdict"

# -----------------------------------------------------------------------
# 5. The validation chain's own summary.md is quoted when present.
DISK="$SANDBOX/disk5/build/release-emulator-validation"
mkdir -p "$DISK/run-x"
printf '# PocketShell Release Emulator Validation\n\nAutomated status: FAIL\n' > "$DISK/run-x/summary.md"
SUM="$SANDBOX/summary-5.md"
out="$(env -u GITHUB_RUN_ID -u GITHUB_REPOSITORY \
  RELEASE_NOXML_DISK_ROOT="$DISK" GITHUB_STEP_SUMMARY="$SUM" \
  bash "$TARGET" --run-id run-x --failed-log "$SANDBOX/empty.log" 2>&1)"
echo "$out" | grep -q "Automated status: FAIL" \
  || fail "a present summary.md must be collected as evidence: $out"
echo "$out" | grep -q "run-x/summary.md" \
  || fail "the collected summary must be cited by path: $out"
pass "validation chain summary.md is collected and cited"
# -----------------------------------------------------------------------
# 6. Usage error exits 2.
set +e
bash "$TARGET" --not-a-flag >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "unknown argument must exit 2, got $rc"
pass "unknown argument exits 2"

echo "OK: $pass_count self-test case(s) passed."
