#!/usr/bin/env bash
set -euo pipefail

# Contract for scripts/lib/android-input-preflight.sh (issue #2946).
#
# A fake adb replays the live window list of a hosted API 35 boot whose Pixel
# Launcher ANR dialog owns input focus. The preflight must disable the HOME
# launcher for the lane (and the release path must re-enable it), disable
# system error dialogs, force-stop exactly the owner of any other error
# dialog, prove the dialog is gone, and fail closed when a dialog survives,
# the launcher stays enabled, or the setting does not stick.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-input-preflight.XXXXXX")"
trap 'python3 -c '\''import shutil, sys; shutil.rmtree(sys.argv[1], ignore_errors=True)'\'' "$SANDBOX"' EXIT

# shellcheck source=scripts/lib/android-input-preflight.sh
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

cat > "$SANDBOX/adb" <<'ADB'
#!/usr/bin/env bash
set -euo pipefail
state="$FAKE_ADB_STATE"
[[ "${1:-}" == -s ]] && shift 2
[[ "${1:-}" == shell ]] || { printf 'unexpected adb command: %s\n' "$*" >&2; exit 90; }
shift
printf '%s\n' "$*" >> "$state/commands"
case "$*" in
  'settings put global hide_error_dialogs 1')
    [[ -e "$state/setting-ignored" ]] || printf '1\n' > "$state/hide"
    ;;
  'settings get global hide_error_dialogs')
    cat "$state/hide" 2>/dev/null || printf 'null\n'
    ;;
  'dumpsys window windows')
    printf 'WINDOW MANAGER WINDOWS (dumpsys window windows)\n'
    printf '  Window #0 Window{ad4ae09 u0 ScreenDecorOverlayBottom}:\n'
    if [[ -e "$state/anr-live" ]]; then
      printf '  Window #1 Window{5bd2cc6 u0 Application Not Responding: com.google.android.apps.nexuslauncher}:\n'
    fi
    if [[ -e "$state/messaging-anr-live" ]]; then
      printf '  Window #1 Window{7aa1e01 u0 Application Not Responding: com.google.android.apps.messaging}:\n'
    fi
    if [[ -e "$state/own-anr-live" ]]; then
      printf '  Window #1 Window{6ce3dd7 u0 Application Not Responding: com.pocketshell.app.i2855ci}:\n'
    fi
    printf '    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION} Application Not Responding: in an attribute line\n'
    printf '  Window #2 Window{74237e0 u0 com.pocketshell.app.i2946/com.pocketshell.app.MainActivity}:\n'
    ;;
  'dumpsys window displays')
    printf '  mCurrentFocus=Window{74237e0 u0 com.pocketshell.app.i2946/com.pocketshell.app.MainActivity}\n'
    ;;
  'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
    printf '2 activities found:\n  Activity #0:\n    priority=0 preferredOrder=0\n'
    [[ -e "$state/launcher-disabled" ]] || printf '    com.google.android.apps.nexuslauncher/.NexusLauncherActivity\n'
    printf '  Activity #1:\n    priority=-1000 preferredOrder=0\n    com.android.settings/.FallbackHome\n'
    ;;
  'pm disable-user --user 0 com.google.android.apps.nexuslauncher')
    [[ -e "$state/disable-ignored" ]] || touch "$state/launcher-disabled"
    printf 'Package com.google.android.apps.nexuslauncher new state: disabled-user\n'
    ;;
  'pm enable com.google.android.apps.nexuslauncher')
    rm -f "$state/launcher-disabled"
    printf 'Package com.google.android.apps.nexuslauncher new state: enabled\n'
    ;;
  'am force-stop com.google.android.apps.nexuslauncher')
    [[ -e "$state/anr-sticky" ]] || rm -f "$state/anr-live"
    ;;
  'am force-stop com.google.android.apps.messaging')
    [[ -e "$state/anr-sticky" ]] || rm -f "$state/messaging-anr-live"
    ;;
  pidof\ *)
    ;;
  *)
    printf 'unexpected adb shell command: %s\n' "$*" >&2
    exit 90
    ;;
esac
ADB
chmod +x "$SANDBOX/adb"

CASES=0
reset_state() {
  export FAKE_ADB_STATE="$SANDBOX/state-$1"
  mkdir -p "$FAKE_ADB_STATE"
}
pass() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

# 1. The hosted round-2 shape: a live Pixel Launcher ANR dialog. The
#    launcher is disabled for the lane (which kills it and its dialog) and is
#    re-enabled by the lane's release path.
reset_state launcher
touch "$FAKE_ADB_STATE/anr-live"
evidence="$SANDBOX/launcher/input-preflight.txt"
pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$evidence" > "$SANDBOX/launcher.out" 2>&1 \
  || { cat "$SANDBOX/launcher.out" >&2; fail 'preflight did not recover from a launcher ANR dialog'; }
grep -Fxq 'pm disable-user --user 0 com.google.android.apps.nexuslauncher' "$FAKE_ADB_STATE/commands" \
  || fail 'preflight did not disable the HOME launcher for the lane'
[[ -e "$FAKE_ADB_STATE/launcher-disabled" ]] || fail 'launcher is not disabled during the lane'
[[ ! -e "$FAKE_ADB_STATE/anr-live" ]] || fail 'launcher ANR dialog survived the preflight'
grep -Fq 'DISABLED_LAUNCHER_FOR_LANE: com.google.android.apps.nexuslauncher' "$evidence" \
  || fail 'preflight evidence does not record the disabled launcher'
grep -Fq 'HOME during lane: com.android.settings FallbackHome only' "$evidence" \
  || fail 'preflight evidence does not record FallbackHome as the only HOME'
grep -Fxq 'hide_error_dialogs=1' "$evidence" || fail 'preflight evidence does not record hide_error_dialogs=1'
grep -Fq 'mCurrentFocus=Window{74237e0' "$evidence" || fail 'preflight evidence does not record the focus owner'
pocketshell_android_restore_launchers 2> "$SANDBOX/launcher-restore.err"
grep -Fxq 'pm enable com.google.android.apps.nexuslauncher' "$FAKE_ADB_STATE/commands" \
  || fail 'release path did not re-enable the launcher'
[[ ! -e "$FAKE_ADB_STATE/launcher-disabled" ]] || fail 'launcher stayed disabled after the lane'
grep -Fq 'RESTORED_LAUNCHER: com.google.android.apps.nexuslauncher' "$SANDBOX/launcher-restore.err" \
  || fail 'launcher restore is not logged'
pass 'launcher is disabled for the lane, killing its ANR dialog, and restored after'

# 1b. Another system app's ANR dialog is dismissed by force-stopping its owner.
reset_state dismiss
touch "$FAKE_ADB_STATE/messaging-anr-live"
evidence="$SANDBOX/dismiss/input-preflight.txt"
pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$evidence" > "$SANDBOX/dismiss.out" 2>&1 \
  || { cat "$SANDBOX/dismiss.out" >&2; fail 'preflight did not recover from a dismissible ANR dialog'; }
pocketshell_android_restore_launchers 2> /dev/null
grep -Fq 'DISMISSED_SYSTEM_ERROR_DIALOG: Application Not Responding: com.google.android.apps.messaging' "$evidence" \
  || fail 'preflight evidence does not record the dismissed dialog'
[[ "$(grep -c 'force-stop com.google.android.apps.messaging' "$FAKE_ADB_STATE/commands")" == 1 ]] \
  || fail 'preflight did not force-stop the dialog owner exactly once'
pass 'other system ANR dialog is dismissed and recorded'

# 2. A clean device is not touched beyond the setting and the window read.
reset_state clean
pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$SANDBOX/clean/input-preflight.txt" > /dev/null 2>&1 \
  || fail 'preflight failed on a device with no error dialog'
pocketshell_android_restore_launchers 2> /dev/null
! grep -v 'nexuslauncher' "$FAKE_ADB_STATE/commands" | grep -q 'force-stop' \
  || fail 'preflight force-stopped a non-launcher package on a clean device'
pass 'clean device passes without force-stopping anything but the launcher'

# 3. A dialog that survives dismissal fails the lane.
reset_state sticky
touch "$FAKE_ADB_STATE/anr-live" "$FAKE_ADB_STATE/anr-sticky"
export POCKETSHELL_INPUT_PREFLIGHT_DISMISS_SECONDS=2
if pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$SANDBOX/sticky/input-preflight.txt" \
    > "$SANDBOX/sticky.out" 2>&1; then
  fail 'preflight passed while a system ANR dialog still owned the screen'
fi
grep -Fq 'FAIL: system error dialog still owns the screen' "$SANDBOX/sticky.out" \
  || fail 'surviving dialog failure lacks its precise message'
pocketshell_android_restore_launchers 2> /dev/null
pass 'surviving ANR dialog fails closed'

# 4. A device that ignores hide_error_dialogs fails before any dismissal.
reset_state ignored
touch "$FAKE_ADB_STATE/setting-ignored"
if pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$SANDBOX/ignored/input-preflight.txt" \
    > "$SANDBOX/ignored.out" 2>&1; then
  fail 'preflight passed although system error dialogs stayed enabled'
fi
grep -Fq 'could not disable system error dialogs' "$SANDBOX/ignored.out" \
  || fail 'ignored setting failure lacks its precise message'
pocketshell_android_restore_launchers 2> /dev/null
pass 'unset hide_error_dialogs fails closed'

# 5. PocketShell's own ANR dialog is a product failure: fail loudly, never
#    force-stop it into a green lane.
reset_state own
touch "$FAKE_ADB_STATE/own-anr-live"
if pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$SANDBOX/own/input-preflight.txt" \
    > "$SANDBOX/own.out" 2>&1; then
  fail 'preflight passed while PocketShell itself owned an ANR dialog'
fi
grep -Fq 'FAIL: POCKETSHELL_ERROR_DIALOG: PocketShell itself owns a system error dialog on emulator-5554: Application Not Responding: com.pocketshell.app.i2855ci' "$SANDBOX/own.out" \
  || fail 'own-app ANR failure lacks its precise message'
grep -Fq 'POCKETSHELL_ERROR_DIALOG' "$SANDBOX/own/input-preflight.txt" \
  || fail 'own-app ANR failure is not recorded in the preflight evidence'
! grep -q 'force-stop com.pocketshell' "$FAKE_ADB_STATE/commands" || fail 'preflight force-stopped PocketShell to hide its own ANR'
pocketshell_android_restore_launchers 2> /dev/null
pass 'PocketShell ANR dialog fails loudly without force-stop'

# 6. A launcher that refuses to be disabled fails the lane before any test.
reset_state stuck-launcher
touch "$FAKE_ADB_STATE/disable-ignored"
if pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$SANDBOX/stuck/input-preflight.txt" \
    > "$SANDBOX/stuck.out" 2>&1; then
  fail 'preflight passed while the HOME launcher stayed enabled'
fi
grep -Fq 'FAIL: HOME launcher still enabled on emulator-5554 after disable: com.google.android.apps.nexuslauncher' "$SANDBOX/stuck.out" \
  || fail 'stuck launcher failure lacks its precise message'
pocketshell_android_restore_launchers 2> /dev/null
pass 'launcher that stays enabled fails closed'

# 7. The shared lock release path re-enables the launcher on every lane exit.
grep -Fq 'pocketshell_android_restore_launchers' "$ROOT_DIR/scripts/lib/avd-lock.sh" \
  || fail 'pocketshell_release_all does not restore launchers'
pass 'pocketshell_release_all restores disabled launchers'

(( CASES == 8 )) || fail "ran $CASES/8 cases"
printf 'PASS: Android input preflight contract (%s/8 cases)\n' "$CASES"
