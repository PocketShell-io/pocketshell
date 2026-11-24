#!/usr/bin/env bash
# The phone's clock runs AHEAD of the gateway's for a gateway lane, on purpose.
#
# Why the lane needs this state (Hub intake f93181cd): the gateway checks a
# routing token's iat/nbf against its own clock with NO leeway (pinned source,
# internal/routingauth VerifyToken: `iat > now` is refused), and the real
# broker stamps them from the server-side clock it shares with the gateway. The
# lane's fake broker runs on the emulator, so a journey that stamps iat from the
# phone's clock gets 4401 "iat missing or in the future" whenever the phone
# leads the gateway. Main run 45261502924 failed the pin-mismatch step of
# GatewayDockerJourneyTest with "Your sign-in expired — sign in again and
# retry.": the emulator led the gateway's host by at least 0.12-0.16 s for the
# whole run and that dial's token was minted inside the lead of a second
# boundary. The offset is nobody's decision - the same lane led by 0.16 s on
# 2026-11-23 and trailed by 0.05 s on 2026-11-19 - so a phone-clock broker
# passes most runs and fails an arbitrary fresh dial on the others.
#
# The journey's fake broker therefore stamps every token from the gateway
# host's own clock (controller op `clock`), and the lane makes the state that
# broke it certain instead of incidental:
#   1. before the journey, automatic time is turned off and the emulator's
#      clock is set GATEWAY_PHONE_CLOCK_LEAD_MS ahead of the host's;
#   2. the lead is measured right after that and again after the journey into
#      the lane's evidence, where the result checkers require it of both.
# A broker that takes token time from the phone again fails every dial on every
# run, instead of one step on some runs. pocketshell_release_all puts the clock
# and the setting back while the lane still holds the emulator.
#
# While the lead lasts, the emulator's logcat timestamps run ahead of every
# host-side log by that much; phone-clock.jsonl in the lane's evidence says by
# how much.

# How far the emulator's clock is set ahead of the gateway host's.
GATEWAY_PHONE_CLOCK_LEAD_MS="${GATEWAY_PHONE_CLOCK_LEAD_MS:-3000}"
# What both readings must show. Larger than any emulator's natural offset from
# its host, and larger than a dial's mint-to-auth latency, so a phone-clock
# token is refused by every fresh dial rather than by a boundary coincidence.
GATEWAY_PHONE_CLOCK_MIN_LEAD_MS=2000

POCKETSHELL_GATEWAY_CLOCK_EVIDENCE=""
POCKETSHELL_GATEWAY_CLOCK_ADB=""
POCKETSHELL_GATEWAY_CLOCK_SERIAL=""
POCKETSHELL_GATEWAY_CLOCK_AUTO_TIME=""

# adb without the continuous AVD lock descriptor when the lock helper is loaded.
_pocketshell_gateway_adb() {
  if declare -F pocketshell_run_without_avd_lock_fd >/dev/null 2>&1; then
    pocketshell_run_without_avd_lock_fd "$@"
  else
    "$@"
  fi
}

_pocketshell_host_ms() {
  date +%s%3N
}

# The device clock in epoch milliseconds (mksh's EPOCHREALTIME); non-zero when
# it cannot be read to that precision.
_pocketshell_gateway_device_ms() {
  local raw whole fraction
  raw="$(_pocketshell_gateway_adb "$1" -s "$2" shell 'printf %s "$EPOCHREALTIME"' 2>/dev/null | tr -d '\r')"
  [[ "$raw" =~ ^([0-9]+)\.([0-9]+)$ ]] || return 1
  whole="${BASH_REMATCH[1]}"
  fraction="${BASH_REMATCH[2]}"
  while (( ${#fraction} < 3 )); do fraction="${fraction}0"; done
  printf '%s%s\n' "$whole" "${fraction:0:3}"
}

# Append one reading to the evidence and require the lead of it. The device
# clock is read between two host readings, so deviceMs - hostAfterMs is a FLOOR
# of the true lead: a reading only ever understates how far ahead the phone is.
pocketshell_gateway_phone_clock_record() {
  local before after device
  before="$(_pocketshell_host_ms)"
  device="$(_pocketshell_gateway_device_ms "$POCKETSHELL_GATEWAY_CLOCK_ADB" "$POCKETSHELL_GATEWAY_CLOCK_SERIAL")" || return 1
  after="$(_pocketshell_host_ms)"
  printf '{"phase":"%s","leadMs":%d,"deviceMs":%s,"hostBeforeMs":%s,"hostAfterMs":%s}\n' \
    "$1" "$(( device - after ))" "$device" "$before" "$after" >> "$POCKETSHELL_GATEWAY_CLOCK_EVIDENCE"
  (( device - after >= GATEWAY_PHONE_CLOCK_MIN_LEAD_MS ))
}

# Set SERIAL's clock ahead of the host's and start the evidence file. Later
# readings, and the restore, address the emulator recorded here.
pocketshell_gateway_phone_clock_ahead() {
  local adb="$1" serial="$2" auto target
  POCKETSHELL_GATEWAY_CLOCK_EVIDENCE="$3"
  POCKETSHELL_GATEWAY_CLOCK_ADB="$adb"
  POCKETSHELL_GATEWAY_CLOCK_SERIAL="$serial"
  auto="$(_pocketshell_gateway_adb "$adb" -s "$serial" shell settings get global auto_time 2>/dev/null | tr -d '\r')"
  # `null` is an unset setting, which Android treats as automatic time on.
  [[ "$auto" == "0" || "$auto" == "1" ]] || auto="null"
  POCKETSHELL_GATEWAY_CLOCK_AUTO_TIME="$auto"
  # Automatic time would pull the clock back to network time mid-journey.
  _pocketshell_gateway_adb "$adb" -s "$serial" shell settings put global auto_time 0 || return 1
  target="$(( $(_pocketshell_host_ms) + GATEWAY_PHONE_CLOCK_LEAD_MS ))"
  _pocketshell_gateway_adb "$adb" -s "$serial" shell cmd alarm set-time "$target" || return 1
  : > "$POCKETSHELL_GATEWAY_CLOCK_EVIDENCE"
  pocketshell_gateway_phone_clock_record before
}

# Put the clock and the setting back. Called from pocketshell_release_all while
# the lane still holds the emulator; a no-op once done, and never fatal.
pocketshell_gateway_phone_clock_restore() {
  local adb="$POCKETSHELL_GATEWAY_CLOCK_ADB" serial="$POCKETSHELL_GATEWAY_CLOCK_SERIAL"
  [[ -n "$serial" ]] || return 0
  POCKETSHELL_GATEWAY_CLOCK_SERIAL=""
  if ! _pocketshell_gateway_adb "$adb" -s "$serial" shell cmd alarm set-time "$(_pocketshell_host_ms)" 2>/dev/null; then
    printf 'WARNING: could not put the clock of %s back to the host time\n' "$serial" >&2
  fi
  case "$POCKETSHELL_GATEWAY_CLOCK_AUTO_TIME" in
    0|1) _pocketshell_gateway_adb "$adb" -s "$serial" shell settings put global auto_time "$POCKETSHELL_GATEWAY_CLOCK_AUTO_TIME" >/dev/null 2>&1 ;;
    *) _pocketshell_gateway_adb "$adb" -s "$serial" shell settings delete global auto_time >/dev/null 2>&1 ;;
  esac || printf 'WARNING: could not restore automatic time on %s\n' "$serial" >&2
  POCKETSHELL_GATEWAY_CLOCK_AUTO_TIME=""
}
