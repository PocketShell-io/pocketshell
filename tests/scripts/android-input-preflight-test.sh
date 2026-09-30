#!/usr/bin/env bash
set -euo pipefail

# Contract for scripts/lib/android-input-preflight.sh (issue #2946).
#
# A fake adb replays the live window list of a hosted API 35 boot whose Pixel
# Launcher ANR dialog owns input focus. The preflight must disable system
# error dialogs, force-stop exactly the dialog's package, prove the dialog is
# gone, and fail closed when a dialog survives or the setting does not stick.

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
    printf '    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION} Application Not Responding: in an attribute line\n'
    printf '  Window #2 Window{74237e0 u0 com.pocketshell.app.i2946/com.pocketshell.app.MainActivity}:\n'
    ;;
  'dumpsys window displays')
    printf '  mCurrentFocus=Window{74237e0 u0 com.pocketshell.app.i2946/com.pocketshell.app.MainActivity}\n'
    ;;
  'am force-stop com.google.android.apps.nexuslauncher')
    [[ -e "$state/anr-sticky" ]] || rm -f "$state/anr-live"
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

# 1. A live launcher ANR dialog is dismissed by force-stopping its package.
reset_state dismiss
touch "$FAKE_ADB_STATE/anr-live"
evidence="$SANDBOX/dismiss/input-preflight.txt"
pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$evidence" > "$SANDBOX/dismiss.out" 2>&1 \
  || { cat "$SANDBOX/dismiss.out" >&2; fail 'preflight did not recover from a dismissible launcher ANR dialog'; }
grep -Fxq 'am force-stop com.google.android.apps.nexuslauncher' "$FAKE_ADB_STATE/commands" \
  || fail 'preflight did not force-stop the package that owns the ANR dialog'
grep -Fq 'DISMISSED_SYSTEM_ERROR_DIALOG: Application Not Responding: com.google.android.apps.nexuslauncher' "$evidence" \
  || fail 'preflight evidence does not record the dismissed dialog'
grep -Fxq 'hide_error_dialogs=1' "$evidence" || fail 'preflight evidence does not record hide_error_dialogs=1'
grep -Fq 'mCurrentFocus=Window{74237e0' "$evidence" || fail 'preflight evidence does not record the focus owner'
[[ "$(grep -c 'force-stop' "$FAKE_ADB_STATE/commands")" == 1 ]] \
  || fail 'preflight force-stopped more than the one dialog owner'
pass 'live system ANR dialog is dismissed and recorded'

# 2. A clean device is not touched beyond the setting and the window read.
reset_state clean
pocketshell_android_input_preflight "$SANDBOX/adb" emulator-5554 "$SANDBOX/clean/input-preflight.txt" > /dev/null 2>&1 \
  || fail 'preflight failed on a device with no error dialog'
! grep -q 'force-stop' "$FAKE_ADB_STATE/commands" || fail 'preflight force-stopped a package on a clean device'
pass 'clean device passes without force-stopping anything'

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
pass 'unset hide_error_dialogs fails closed'

(( CASES == 4 )) || fail "ran $CASES/4 cases"
printf 'PASS: Android input preflight contract (%s/4 cases)\n' "$CASES"
