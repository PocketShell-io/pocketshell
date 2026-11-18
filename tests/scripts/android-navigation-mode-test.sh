#!/usr/bin/env bash
# #3158: the hotkeys runner records the emulator's navigation mode before the
# tests and restores it from its EXIT trap, so a J30 process that died after
# switching to gesture navigation cannot leak that mode into later lanes.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
LIB="$ROOT_DIR/scripts/lib/android-navigation-mode.sh"
RUNNER="$ROOT_DIR/scripts/connected-js-hotkeys-docker.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ps-navmode-test.XXXXXX")"
cleanup() { find "$WORK" -delete 2>/dev/null || true; }
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$1"; }

# A fake adb: navigation_mode lives in a file; enabling an overlay changes it,
# as SystemUI does. Every invocation is logged.
STATE="$WORK/mode"
LOG="$WORK/adb.log"
FAKE_ADB="$WORK/adb"
cat > "$FAKE_ADB" <<'ADB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"
shift 2 # -s SERIAL
case "$*" in
  "shell settings get secure navigation_mode") cat "$FAKE_ADB_STATE"; printf '\r\n' ;;
  "shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton")
    [[ "${FAKE_ADB_STUCK:-0}" == 1 ]] || printf '0' > "$FAKE_ADB_STATE" ;;
  "shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.twobutton") printf '1' > "$FAKE_ADB_STATE" ;;
  "shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural") printf '2' > "$FAKE_ADB_STATE" ;;
  *) exit 1 ;;
esac
ADB
chmod +x "$FAKE_ADB"
export FAKE_ADB_LOG="$LOG" FAKE_ADB_STATE="$STATE" POCKETSHELL_NAV_MODE_RESTORE_SLEEP=0 POCKETSHELL_NAV_MODE_RESTORE_POLLS=3

run_case() { # $1 = a bash snippet run with the lib sourced
  ( source "$LIB"; eval "$1" )
}

# 1. Three-button recorded; J30 switched to gesture and died: the trap restores three-button.
printf '0' > "$STATE"; : > "$LOG"
out="$(run_case 'pocketshell_android_record_navigation_mode "$FAKE_ADB" emulator-5554
  printf 2 > "$FAKE_ADB_STATE"   # J30 switched, then its process died before @After
  pocketshell_android_restore_navigation_mode "$FAKE_ADB" emulator-5554')"
[[ "$(cat "$STATE")" == 0 ]] || fail "a killed J30 must end back on three-button; mode is $(cat "$STATE")"
grep -q 'RESTORED_NAVIGATION_MODE: 0 on emulator-5554' <<<"$out" || fail "restore must report what it restored: $out"
grep -q 'navbar.threebutton' "$LOG" || fail 'restore must enable the three-button overlay'
pass 'gesture navigation left by a killed J30 is restored to the recorded three-button mode'

# 2. J30's own @After already restored: no overlay change.
printf '0' > "$STATE"; : > "$LOG"
out="$(run_case 'pocketshell_android_record_navigation_mode "$FAKE_ADB" emulator-5554
  pocketshell_android_restore_navigation_mode "$FAKE_ADB" emulator-5554')"
grep -q 'NAVIGATION_MODE_UNCHANGED: 0' <<<"$out" || fail "an unchanged mode must be left alone: $out"
! grep -q 'overlay' "$LOG" || fail 'an unchanged mode must not touch overlays'
pass 'an already-restored mode is left untouched'

# 3. A device that was on gesture navigation before the lane stays on it.
printf '2' > "$STATE"; : > "$LOG"
run_case 'pocketshell_android_record_navigation_mode "$FAKE_ADB" emulator-5554
  printf 0 > "$FAKE_ADB_STATE"
  pocketshell_android_restore_navigation_mode "$FAKE_ADB" emulator-5554' >/dev/null
[[ "$(cat "$STATE")" == 2 ]] || fail 'the recorded mode, not three-button, is what gets restored'
pass 'the recorded mode is restored, whatever it was'

# 4. A restore that does not take effect fails loudly.
printf '0' > "$STATE"; : > "$LOG"
if FAKE_ADB_STUCK=1 run_case 'pocketshell_android_record_navigation_mode "$FAKE_ADB" emulator-5554
  printf 2 > "$FAKE_ADB_STATE"
  pocketshell_android_restore_navigation_mode "$FAKE_ADB" emulator-5554' >/dev/null 2>&1; then
  fail 'a restore that never takes effect must fail'
fi
pass 'a restore that never takes effect fails'

# 5. Nothing recorded (unknown mode): restore is a no-op, never a guess.
printf 'null' > "$STATE"; : > "$LOG"
run_case 'pocketshell_android_record_navigation_mode "$FAKE_ADB" emulator-5554 || true
  printf 2 > "$FAKE_ADB_STATE"
  pocketshell_android_restore_navigation_mode "$FAKE_ADB" emulator-5554' >/dev/null 2>&1
! grep -q 'overlay' "$LOG" || fail 'an unrecorded mode must not be guessed'
pass 'an unrecognised recorded mode is never guessed at'

# 6. Runner wiring: sourced; recorded in the runner's own shell (not a
#    pipeline subshell) before the EXIT trap is installed; restored inside the
#    trap before the locks are released.
python3 - "$RUNNER" <<'PY'
import re
import sys
runner = open(sys.argv[1], encoding="utf-8").read()
assert 'source "$ROOT_DIR/scripts/lib/android-navigation-mode.sh"' in runner, "runner must source the lib"
record = runner.index('pocketshell_android_record_navigation_mode "$ADB" "$ANDROID_SERIAL"')
line = runner[record:runner.index("\n", record)]
assert "|" not in line, "record must not run in a pipeline subshell: " + line
trap = runner.index("trap finish_hotkeys_run EXIT")
assert record < trap, "the mode must be recorded before the trap that restores it"
finish = runner.index("finish_hotkeys_run() {")
end = runner.index("\n}\n", finish)
body = runner[finish:end]
restore = body.find('pocketshell_android_restore_navigation_mode "$ADB" "$ANDROID_SERIAL"')
release = body.find("\n  pocketshell_release_all\n")
assert restore != -1, "the EXIT trap must restore the navigation mode"
assert release != -1 and restore < release, "restore while the AVD lock is still held"
instrument = runner.index(":app:connectedDebugAndroidTest")
assert record < instrument, "record before instrumentation"
print("PASS: runner records the navigation mode before instrumentation and restores it in its EXIT trap")
PY
