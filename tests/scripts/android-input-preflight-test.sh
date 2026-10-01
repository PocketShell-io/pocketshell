#!/usr/bin/env bash
set -euo pipefail

# Contract for scripts/lib/android-input-preflight.sh (issue #2946).
#
# A stateful fake adb replays the hosted API 35 image: Pixel Launcher answers
# HOME, can own an ANR dialog, and a second HOME provider
# (com.google.android.googlesdksetup) can appear only after the launcher is
# disabled. The preflight must disable HOME providers until the set stays
# empty, record each one on the host before disabling it, restore exactly
# what it disabled, recover launchers a SIGKILLed lane left disabled, dismiss
# other error dialogs, and fail closed when any of that does not hold.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-input-preflight.XXXXXX")"
trap 'python3 -c '\''import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)'\'' "$SANDBOX"' EXIT

export POCKETSHELL_AVD_LOCK_DIR="$SANDBOX/locks"
export POCKETSHELL_LAUNCHER_SAMPLE_SECONDS=0.05
export POCKETSHELL_LAUNCHER_STABLE_SAMPLES=3
export POCKETSHELL_LAUNCHER_DISABLE_SECONDS=3
export POCKETSHELL_INPUT_PREFLIGHT_DISMISS_SECONDS=2

# shellcheck source=scripts/lib/avd-lock.sh
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
# shellcheck source=scripts/lib/android-input-preflight.sh
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

LAUNCHER=com.google.android.apps.nexuslauncher
SETUP=com.google.android.googlesdksetup
SERIAL=emulator-5554
RECORD="$POCKETSHELL_AVD_LOCK_DIR/avd-lock-$SERIAL.disabled-launchers"

cat > "$SANDBOX/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
state="$FAKE_ADB_STATE"
[[ "${1:-}" == -s ]] && shift 2
[[ "${1:-}" == shell ]] || { printf 'unexpected adb command: %s\n' "$*" >&2; exit 90; }
shift
printf '%s\n' "$*" >> "$state/commands"
args="$*"
last="${args##* }"
launcher=com.google.android.apps.nexuslauncher
setup=com.google.android.googlesdksetup
disabled() { [[ -e "$state/disabled-$1" ]]; }
case "$*" in
  'settings put global hide_error_dialogs 1')
    [[ -e "$state/setting-ignored" ]] || printf '1\n' > "$state/hide"
    ;;
  'settings get global hide_error_dialogs')
    cat "$state/hide" 2>/dev/null || printf 'null\n'
    ;;
  'settings put global device_provisioned 1'|'settings put secure user_setup_complete 1')
    ;;
  'settings put secure long_press_timeout 3000')
    [[ -e "$state/long-press-ignored" ]] || printf '3000\n' > "$state/long-press"
    ;;
  'settings get secure long_press_timeout')
    cat "$state/long-press" 2>/dev/null || printf '400\n'
    ;;
  'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
    [[ -e "$state/query-fails" ]] && { printf 'error: closed\n'; exit 1; }
    printf 'activities found:\n'
    disabled "$launcher" && [[ ! -e "$state/disable-ignored" ]] || printf '    %s/.NexusLauncherActivity\n' "$launcher"
    # The hosted post-boot race: the setup app answers HOME only once the
    # launcher is gone.
    if [[ -e "$state/late-setup" ]] && disabled "$launcher" && ! disabled "$setup"; then
      printf '    %s/.SetupActivity\n' "$setup"
    fi
    printf '    com.android.settings/.FallbackHome\n'
    ;;
  'pm list packages -d')
    for f in "$state"/disabled-*; do [[ -e "$f" ]] && printf 'package:%s\n' "${f##*/disabled-}"; done
    exit 0
    ;;
  pm\ disable-user\ --user\ 0\ *)
    touch "$state/disabled-$last"
    printf 'Package %s new state: disabled-user\n' "$last"
    ;;
  pm\ enable\ *)
    [[ -e "$state/enable-fails" ]] && { printf 'error: device offline\n'; exit 1; }
    rm -f "$state/disabled-$last"
    printf 'Package %s new state: enabled\n' "$last"
    ;;
  am\ force-stop\ *)
    [[ -e "$state/anr-sticky" ]] || rm -f "$state/anr-$last"
    ;;
  pidof\ *)
    ;;
  'dumpsys window windows')
    printf 'WINDOW MANAGER WINDOWS (dumpsys window windows)\n'
    printf '  Window #0 Window{ad4ae09 u0 ScreenDecorOverlayBottom}:\n'
    for f in "$state"/anr-*; do
      [[ -e "$f" && "$f" != */anr-sticky ]] && printf '  Window #1 Window{5bd2cc6 u0 Application Not Responding: %s}:\n' "${f##*/anr-}"
    done
    true
    printf '    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION} Application Not Responding: in an attribute line\n'
    printf '  Window #2 Window{74237e0 u0 com.pocketshell.app.i2946/com.pocketshell.app.MainActivity}:\n'
    ;;
  'dumpsys window displays')
    printf '  mCurrentFocus=Window{74237e0 u0 com.pocketshell.app.i2946/com.pocketshell.app.MainActivity}\n'
    ;;
  *)
    printf 'unexpected adb shell command: %s\n' "$*" >&2
    exit 90
    ;;
esac
ADB
chmod +x "$SANDBOX/adb"
ADB="$SANDBOX/adb"

CASES=0
reset_state() {
  export FAKE_ADB_STATE="$SANDBOX/state-$1"
  mkdir -p "$FAKE_ADB_STATE"
  rm -f "$RECORD"
  POCKETSHELL_DISABLED_LAUNCHERS=()
}
pass() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}
preflight() {
  pocketshell_android_input_preflight "$ADB" "$SERIAL" "$1"
}

# 1. The hosted round-2 shape: a live Pixel Launcher ANR dialog. The launcher
#    is disabled for the lane (which kills it and its dialog), recorded on the
#    host first, and re-enabled by the lane's release path.
reset_state launcher
touch "$FAKE_ADB_STATE/anr-$LAUNCHER"
evidence="$SANDBOX/launcher/input-preflight.txt"
preflight "$evidence" > "$SANDBOX/launcher.out" 2>&1 \
  || { cat "$SANDBOX/launcher.out" >&2; fail 'preflight did not recover from a launcher ANR dialog'; }
[[ -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'launcher is not disabled during the lane'
[[ ! -e "$FAKE_ADB_STATE/anr-$LAUNCHER" ]] || fail 'launcher ANR dialog survived the preflight'
grep -Fxq "$LAUNCHER" "$RECORD" || fail 'disabled launcher is not recorded next to the AVD lock'
grep -Fq "DISABLED_LAUNCHER_FOR_LANE: $LAUNCHER" "$evidence" || fail 'evidence does not record the disabled launcher'
grep -Fq 'HOME during lane: com.android.settings FallbackHome only' "$evidence" || fail 'evidence does not record FallbackHome as the only HOME'
grep -Fxq 'hide_error_dialogs=1' "$evidence" || fail 'evidence does not record hide_error_dialogs=1'
grep -Fxq 'long_press_timeout=3000' "$evidence" || fail 'evidence does not record long_press_timeout=3000'
grep -Fq 'mCurrentFocus=Window{74237e0' "$evidence" || fail 'evidence does not record the focus owner'
first_disable="$(grep -n "pm disable-user --user 0 $LAUNCHER" "$FAKE_ADB_STATE/commands" | head -1 | cut -d: -f1)"
[[ -n "$first_disable" ]] || fail 'launcher was never disabled'
provisioned_line="$(grep -n 'settings put secure user_setup_complete 1' "$FAKE_ADB_STATE/commands" | head -1 | cut -d: -f1)"
[[ -n "$provisioned_line" ]] && (( provisioned_line < first_disable )) \
  || fail 'preflight must mark setup complete before disabling HOME providers'
grep -Fxq 'settings put global device_provisioned 1' "$FAKE_ADB_STATE/commands" \
  || fail 'preflight did not mark the device provisioned'
pocketshell_android_restore_launchers 2> "$SANDBOX/launcher-restore.err"
[[ ! -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'launcher stayed disabled after the lane'
[[ ! -e "$RECORD" ]] || fail 'record survived a successful restore'
grep -Fq "RESTORED_LAUNCHER: $LAUNCHER" "$SANDBOX/launcher-restore.err" || fail 'launcher restore is not logged'
pass 'launcher is recorded, disabled for the lane (killing its ANR dialog), and restored after'

# 2. A second HOME provider that appears only after the launcher is disabled
#    (hosted run 36803911407) is disabled too, and both are restored.
reset_state late
touch "$FAKE_ADB_STATE/late-setup"
evidence="$SANDBOX/late/input-preflight.txt"
preflight "$evidence" > "$SANDBOX/late.out" 2>&1 \
  || { cat "$SANDBOX/late.out" >&2; fail 'preflight failed on a late-appearing second HOME provider'; }
[[ -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" && -e "$FAKE_ADB_STATE/disabled-$SETUP" ]] \
  || fail 'both HOME providers must be disabled for the lane'
grep -Fq "DISABLED_LAUNCHER_FOR_LANE: $SETUP" "$evidence" || fail 'late HOME provider is not logged'
grep -Fxq "$SETUP" "$RECORD" || fail 'late HOME provider is not recorded'
pocketshell_android_restore_launchers 2> "$SANDBOX/late-restore.err"
[[ ! -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" && ! -e "$FAKE_ADB_STATE/disabled-$SETUP" ]] \
  || fail 'restore did not re-enable every HOME provider it disabled'
[[ "$(grep -c "^RESTORED_LAUNCHER: " "$SANDBOX/late-restore.err")" == 2 ]] || fail 'restore did not log both providers'
[[ ! -e "$RECORD" ]] || fail 'record survived restoring both providers'
pass 'late-appearing second HOME provider is disabled and restored'

# 3. SIGKILL after the disable leaves the launcher disabled and the record
#    behind; the next lane's preflight re-enables it first, then disables it
#    for its own lane and restores it normally.
reset_state sigkill
( bash -c '
  source "$1/scripts/lib/avd-lock.sh"
  source "$1/scripts/lib/android-input-preflight.sh"
  pocketshell_android_input_preflight "$2" "$3" "$4" > /dev/null 2>&1
  kill -KILL $$
' killed-lane "$ROOT_DIR" "$ADB" "$SERIAL" "$SANDBOX/killed/input-preflight.txt" ) 2> /dev/null || true
[[ -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'fixture: the killed lane did not leave the launcher disabled'
grep -Fxq "$LAUNCHER" "$RECORD" || fail 'fixture: the killed lane left no record'
: > "$FAKE_ADB_STATE/commands"
evidence="$SANDBOX/next/input-preflight.txt"
preflight "$evidence" > "$SANDBOX/next.out" 2>&1 \
  || { cat "$SANDBOX/next.out" >&2; fail 'next lane preflight failed after a SIGKILLed lane'; }
grep -Fq "RECOVERED_STALE_DISABLED_LAUNCHER: $LAUNCHER (recorded in" "$evidence" \
  || fail 'next lane did not log recovering the stale launcher'
enable_line="$(grep -n "pm enable $LAUNCHER" "$FAKE_ADB_STATE/commands" | head -1 | cut -d: -f1)"
disable_line="$(grep -n "pm disable-user --user 0 $LAUNCHER" "$FAKE_ADB_STATE/commands" | head -1 | cut -d: -f1)"
[[ -n "$enable_line" && -n "$disable_line" ]] && (( enable_line < disable_line )) \
  || fail 'stale launcher was not re-enabled before the new lane disabled it'
pocketshell_android_restore_launchers 2> /dev/null
[[ ! -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'launcher stayed disabled after the recovering lane exited'
[[ ! -e "$RECORD" ]] || fail 'record survived the recovering lane'
pass 'SIGKILLed lane leaves a record and the next lane re-enables the launcher'

# 4. The emulator start path recovers a stale launcher when no lane holds the
#    AVD lock, and leaves it alone while a live lane holds it.
reset_state start-path
touch "$FAKE_ADB_STATE/disabled-$LAUNCHER"
printf '%s\n' "$LAUNCHER" > "$RECORD"
lock_file="$(pocketshell_avd_lock_file_for_serial "" "$SERIAL")"
exec 7> "$lock_file"
flock 7
pocketshell_android_recover_stale_launchers_if_idle "$ADB" "$SERIAL" 2> /dev/null
[[ -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'start path re-enabled a launcher under a live lane'
exec 7>&-
pocketshell_android_recover_stale_launchers_if_idle "$ADB" "$SERIAL" 2> /dev/null
[[ ! -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'start path did not recover the stale launcher'
[[ ! -e "$RECORD" ]] || fail 'start path left the record after a successful re-enable'
# start-local-avd.sh itself is executed end to end by
# tests/scripts/start-local-avd-launcher-recovery-test.sh.
pass 'emulator start path recovers stale launchers only when no lane holds the lock'

# 5. An unrecorded known launcher left disabled is recovered too.
reset_state unrecorded
touch "$FAKE_ADB_STATE/disabled-$LAUNCHER"
evidence="$SANDBOX/unrecorded/input-preflight.txt"
preflight "$evidence" > /dev/null 2>&1 || fail 'preflight failed with an unrecorded disabled launcher'
grep -Fq "RECOVERED_STALE_DISABLED_LAUNCHER: $LAUNCHER (unrecorded)" "$evidence" \
  || fail 'unrecorded disabled launcher was not recovered'
pocketshell_android_restore_launchers 2> /dev/null
[[ ! -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'launcher stayed disabled'
pass 'unrecorded disabled known launcher is recovered'

# 6. A restore that cannot reach the device keeps the record; recovery
#    succeeds later and only then deletes it.
reset_state offline
preflight "$SANDBOX/offline/input-preflight.txt" > /dev/null 2>&1 || fail 'offline fixture preflight failed'
touch "$FAKE_ADB_STATE/enable-fails"
pocketshell_android_restore_launchers 2> "$SANDBOX/offline-restore.err"
grep -Fq "WARNING: could not re-enable launcher $LAUNCHER" "$SANDBOX/offline-restore.err" || fail 'failed restore is not warned'
grep -Fxq "$LAUNCHER" "$RECORD" || fail 'failed restore dropped the record'
if pocketshell_android_recover_stale_launchers "$ADB" "$SERIAL" "$SANDBOX/offline/recover.txt" 2> /dev/null; then
  fail 'recovery reported success while pm enable kept failing'
fi
grep -Fxq "$LAUNCHER" "$RECORD" || fail 'failed recovery dropped the record'
rm -f "$FAKE_ADB_STATE/enable-fails"
pocketshell_android_recover_stale_launchers "$ADB" "$SERIAL" "$SANDBOX/offline/recover.txt" 2> /dev/null \
  || fail 'recovery failed once the device answered again'
[[ ! -e "$RECORD" && ! -e "$FAKE_ADB_STATE/disabled-$LAUNCHER" ]] || fail 'recovery did not re-enable and clear the record'
pass 'record is kept until pm enable succeeds'

# 7. Another system app's ANR dialog is dismissed by force-stopping its owner.
reset_state dismiss
touch "$FAKE_ADB_STATE/anr-com.google.android.apps.messaging"
evidence="$SANDBOX/dismiss/input-preflight.txt"
preflight "$evidence" > "$SANDBOX/dismiss.out" 2>&1 \
  || { cat "$SANDBOX/dismiss.out" >&2; fail 'preflight did not recover from a dismissible ANR dialog'; }
pocketshell_android_restore_launchers 2> /dev/null
grep -Fq 'DISMISSED_SYSTEM_ERROR_DIALOG: Application Not Responding: com.google.android.apps.messaging' "$evidence" \
  || fail 'evidence does not record the dismissed dialog'
[[ "$(grep -c 'force-stop com.google.android.apps.messaging' "$FAKE_ADB_STATE/commands")" == 1 ]] \
  || fail 'preflight did not force-stop the dialog owner exactly once'
pass 'other system ANR dialog is dismissed and recorded'

# 8. A dialog that survives dismissal fails the lane.
reset_state sticky
touch "$FAKE_ADB_STATE/anr-com.google.android.apps.messaging" "$FAKE_ADB_STATE/anr-sticky"
if preflight "$SANDBOX/sticky/input-preflight.txt" > "$SANDBOX/sticky.out" 2>&1; then
  fail 'preflight passed while a system ANR dialog still owned the screen'
fi
pocketshell_android_restore_launchers 2> /dev/null
grep -Fq 'FAIL: system error dialog still owns the screen' "$SANDBOX/sticky.out" \
  || fail 'surviving dialog failure lacks its precise message'
pass 'surviving ANR dialog fails closed'

# 9. A device that ignores hide_error_dialogs fails.
reset_state ignored
touch "$FAKE_ADB_STATE/setting-ignored"
if preflight "$SANDBOX/ignored/input-preflight.txt" > "$SANDBOX/ignored.out" 2>&1; then
  fail 'preflight passed although system error dialogs stayed enabled'
fi
pocketshell_android_restore_launchers 2> /dev/null
grep -Fq 'could not disable system error dialogs' "$SANDBOX/ignored.out" \
  || fail 'ignored setting failure lacks its precise message'
pass 'unset hide_error_dialogs fails closed'

# 9b. A device that keeps the stock long-press timeout fails (#2884, #2946).
reset_state longpress
touch "$FAKE_ADB_STATE/long-press-ignored"
if preflight "$SANDBOX/longpress/input-preflight.txt" > "$SANDBOX/longpress.out" 2>&1; then
  fail 'preflight passed although injected taps could still turn into long presses'
fi
pocketshell_android_restore_launchers 2> /dev/null
grep -Fq 'could not raise the long-press timeout' "$SANDBOX/longpress.out" \
  || fail 'ignored long-press timeout lacks its precise message'
pass 'stock long-press timeout fails closed'

# 10. PocketShell's own ANR dialog is a product failure: fail loudly, never
#     force-stop it into a green lane.
reset_state own
touch "$FAKE_ADB_STATE/anr-com.pocketshell.app.i2855ci"
if preflight "$SANDBOX/own/input-preflight.txt" > "$SANDBOX/own.out" 2>&1; then
  fail 'preflight passed while PocketShell itself owned an ANR dialog'
fi
pocketshell_android_restore_launchers 2> /dev/null
grep -Fq "FAIL: POCKETSHELL_ERROR_DIALOG: PocketShell itself owns a system error dialog on $SERIAL: Application Not Responding: com.pocketshell.app.i2855ci" "$SANDBOX/own.out" \
  || fail 'own-app ANR failure lacks its precise message'
! grep -q 'force-stop com.pocketshell' "$FAKE_ADB_STATE/commands" || fail 'preflight force-stopped PocketShell to hide its own ANR'
pass 'PocketShell ANR dialog fails loudly without force-stop'

# 11. A launcher that refuses to be disabled fails the lane before any test.
reset_state stuck-launcher
touch "$FAKE_ADB_STATE/disable-ignored"
if preflight "$SANDBOX/stuck/input-preflight.txt" > "$SANDBOX/stuck.out" 2>&1; then
  fail 'preflight passed while the HOME launcher stayed enabled'
fi
grep -Fq "FAIL: HOME launcher still enabled on $SERIAL after disable: $LAUNCHER" "$SANDBOX/stuck.out" \
  || fail 'stuck launcher failure lacks its precise message'
rm -f "$FAKE_ADB_STATE/disable-ignored"
pocketshell_android_restore_launchers 2> /dev/null
pass 'launcher that stays enabled fails closed'

# 12b. An adb error while listing HOME providers fails the lane instead of
#     being read as "no launcher".
reset_state query-fails
touch "$FAKE_ADB_STATE/query-fails"
if preflight "$SANDBOX/query/input-preflight.txt" > "$SANDBOX/query.out" 2>&1; then
  fail 'preflight passed although the HOME query failed'
fi
grep -Fq "FAIL: could not list HOME activities on $SERIAL" "$SANDBOX/query.out" \
  || fail 'HOME query failure lacks its precise message'
pass 'failed HOME query fails closed'

# 12. The shared lock release path re-enables launchers on every lane exit.
grep -Fq 'pocketshell_android_restore_launchers' "$ROOT_DIR/scripts/lib/avd-lock.sh" \
  || fail 'pocketshell_release_all does not restore launchers'
pass 'pocketshell_release_all restores disabled launchers'

(( CASES == 14 )) || fail "ran $CASES/14 cases"
printf 'PASS: Android input preflight contract (%s/14 cases)\n' "$CASES"
