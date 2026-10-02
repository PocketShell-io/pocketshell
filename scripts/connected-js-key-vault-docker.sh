#!/usr/bin/env bash
# Import an encrypted SSH document through the packaged picker, authenticate to
# the real agents fixture using its opaque handle, and preserve same-run proof.
# A second exact-method cycle (#3021) pastes the key text, copies/shares a
# generated key's public line, installs it on the fixture over the live
# connection, and reconnects with it; the host oracles below verify both.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
PORT=""
CONTAINER=""
SUFFIX="i2926"
RUN_ID="keyvault-$(date -u '+%Y%m%dT%H%M%S')"
PREPARE_ONLY=0
TEST_ONLY=0

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

validate_png() {
  python3 - "$1" <<'PY'
import struct
import sys
import zlib
from pathlib import Path

data = Path(sys.argv[1]).read_bytes()
signature = b"\x89PNG\r\n\x1a\n"
if not data.startswith(signature):
    raise SystemExit("FAIL: screenshot is not a PNG")
offset = len(signature)
seen_header = seen_image = seen_end = False
while offset < len(data):
    if offset + 12 > len(data):
        raise SystemExit("FAIL: truncated PNG chunk header")
    length = struct.unpack(">I", data[offset:offset + 4])[0]
    kind = data[offset + 4:offset + 8]
    end = offset + 12 + length
    if end > len(data):
        raise SystemExit("FAIL: truncated PNG chunk payload")
    payload = data[offset + 8:offset + 8 + length]
    expected_crc = struct.unpack(">I", data[offset + 8 + length:end])[0]
    if zlib.crc32(kind + payload) & 0xffffffff != expected_crc:
        raise SystemExit("FAIL: PNG chunk checksum mismatch")
    if kind == b"IHDR": seen_header = True
    if kind == b"IDAT": seen_image = True
    offset = end
    if kind == b"IEND":
        seen_end = length == 0
        break
if not (seen_header and seen_image and seen_end) or offset != len(data):
    raise SystemExit("FAIL: incomplete PNG screenshot")
PY
}

capture_sanitized_logcat() {
  local output_path="$1"
  "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime \
    | python3 -c '
from pathlib import Path
import re
import sys

destination = Path(sys.argv[1])
sensitive = re.compile(r"-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----|pocketshell-vault-test-passphrase|\"passphrase\"\s*:|\"privateKeyPem\"\s*:", re.IGNORECASE)
redacted = 0
with destination.open("w", encoding="utf-8") as output:
    for line in sys.stdin:
        if sensitive.search(line):
            redacted += 1
            output.write("[REDACTED Android log line containing credential material]\n")
        else:
            output.write(line)
destination.with_name(destination.stem + "-redaction.txt").write_text(
    f"sensitive_log_lines_redacted={redacted}\n", encoding="utf-8"
)
if redacted:
    raise SystemExit(23)
' "$output_path"
}

usage() {
  cat <<'USAGE'
Usage: scripts/connected-js-key-vault-docker.sh --port PORT --container NAME [options]

Options:
  --port PORT          Docker SSH port as reached from the emulator
  --container NAME     Healthy Docker agents container name
  --suffix TOKEN       Isolated debug package suffix (default: i2926)
  --run-id ID          Unique session/screenshot ID (default: generated)
  --prepare-only       Build app/androidTest APKs and run local guard self-tests
  --test-only          Run the previously prepared APKs
  --help               Show this help

The lane requires an isolated healthy agents-pool port (2243, 2244, or 2245).
It captures the actual Android key list and host-form screens under
android/app/build/outputs/js-key-vault/<run-id>/device-screenshots for
maintainer visual sign-off. It verifies accepted-key fingerprint and the
created session independently using the host SSH client.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) [[ $# -ge 2 ]] || fail '--port needs a value'; PORT="$2"; shift 2 ;;
    --container) [[ $# -ge 2 ]] || fail '--container needs a value'; CONTAINER="$2"; shift 2 ;;
    --suffix) [[ $# -ge 2 ]] || fail '--suffix needs a value'; SUFFIX="$2"; shift 2 ;;
    --run-id) [[ $# -ge 2 ]] || fail '--run-id needs a value'; RUN_ID="$2"; shift 2 ;;
    --prepare-only) PREPARE_ONLY=1; shift ;;
    --test-only) TEST_ONLY=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[[ "$SUFFIX" =~ ^[A-Za-z0-9._]+$ ]] || fail '--suffix must match [A-Za-z0-9._]+'
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,38}$ ]] || fail '--run-id must contain 3-39 path-safe characters'
(( PREPARE_ONLY + TEST_ONLY <= 1 )) || fail '--prepare-only and --test-only cannot be combined'
if [[ "$PREPARE_ONLY" != 1 ]]; then
  [[ "$PORT" =~ ^(2243|2244|2245)$ ]] || fail '--port must be an isolated pool port: 2243, 2244, or 2245'
  [[ "$CONTAINER" == "pocketshell-test-agents-$PORT" ]] \
    || fail "--container must match the isolated pool identity pocketshell-test-agents-$PORT"
  [[ -x "$ADB" ]] || fail "adb is missing or not executable: $ADB"
fi
[[ -x "$ROOT_DIR/android/gradlew" ]] || fail 'generated Android Gradle wrapper is missing'
[[ -f "$ROOT_DIR/tests/docker/key-vault-encrypted-test-key" ]] || fail 'encrypted SSH key fixture is missing'
[[ -f "$ROOT_DIR/tests/docker/test_key" && -f "$ROOT_DIR/tests/docker/test_key.pub" ]] || fail 'Docker SSH oracle keys are missing'
[[ -x "$ROOT_DIR/scripts/check-js-key-vault-results.py" ]] || fail 'exact key-vault JUnit checker is missing'
"$ROOT_DIR/scripts/check-js-key-vault-results.py" --self-test
python3 "$ROOT_DIR/scripts/check-js-key-vault-artifacts.py" --self-test

source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/gradle-output-lock.sh"
source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/android-input-preflight.sh"
pocketshell_disk_preflight "$ROOT_DIR/android" 'connected-js-key-vault-docker.sh' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "connected-js-key-vault-docker.sh suffix=$SUFFIX run=$RUN_ID"

if [[ "$TEST_ONLY" != 1 ]]; then
  command -v pnpm >/dev/null 2>&1 || fail 'pnpm is required to build packaged web assets'
  "$ROOT_DIR/scripts/assemble-debug.sh" --suffix "$SUFFIX"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi
if [[ "$PREPARE_ONLY" == 1 ]]; then
  printf 'PASS: key-vault journey APKs and exact-result checker prepared for suffix %s\n' "$SUFFIX"
  exit 0
fi
if [[ "$TEST_ONLY" == 1 ]]; then
  # Every suffixed lane writes the same apk/debug output path, so a prepared
  # APK can be overwritten by a later lane's build before this lane runs.
  # Re-package for this suffix (the synced web assets are reused).
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:assembleDebug :app:assembleDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" --stacktrace --console=plain
fi

APP_APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -s "$APP_APK" && -s "$TEST_APK" ]] || fail 'packaged app or instrumentation APK is missing'
docker inspect "$CONTAINER" >/dev/null 2>&1 || fail "Docker fixture container is missing: $CONTAINER"
health="$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)"
[[ "$health" == healthy ]] || fail "Docker fixture must be healthy; $CONTAINER reports ${health:-no health status}"

if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t online_emulators < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')
  (( ${#online_emulators[@]} == 1 )) || fail "expected one online emulator or ANDROID_SERIAL; found ${#online_emulators[@]}"
  export ANDROID_SERIAL="${online_emulators[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] \
  || fail "selected Android device is not online: $ANDROID_SERIAL"
device_api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 35 )) || fail "key-vault journey requires API 35+; got ${device_api:-unknown}"

export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

APP_PACKAGE="com.pocketshell.app.$SUFFIX"
TEST_PACKAGE="$APP_PACKAGE.test"
DEVICE_FILES="/sdcard/Android/data/$APP_PACKAGE/files"
DEVICE_KEY_NAME="key-$RUN_ID.pem"
DEVICE_KEY_PATH="$DEVICE_FILES/$DEVICE_KEY_NAME"
DEVICE_RUN_DIR="$DEVICE_FILES/pocketshell-key-vault/$RUN_ID"
DEVICE_GENERATED_PUBLIC_KEY="$DEVICE_RUN_DIR/generated-authorized-key.pub"
DEVICE_GENERATED_FINGERPRINT="$DEVICE_RUN_DIR/generated-key-fingerprint.txt"
DEVICE_GENERATED_READY="$DEVICE_RUN_DIR/generated-key-authorized"
DEVICE_EVIDENCE_CAPTURED="$DEVICE_RUN_DIR/artifacts-captured"
RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
ARTIFACTS_DIR="$ROOT_DIR/android/app/build/outputs/js-key-vault/$RUN_ID"
[[ ! -e "$ARTIFACTS_DIR" ]] || fail "refusing to overwrite existing run artifacts: $ARTIFACTS_DIR"
mkdir -p "$ARTIFACTS_DIR"
pocketshell_android_input_preflight "$ADB" "$ANDROID_SERIAL" "$ARTIFACTS_DIR/input-preflight.txt" \
  || fail "Android input preflight failed on $ANDROID_SERIAL; see $ARTIFACTS_DIR/input-preflight.txt"
ORIGINAL_AUTHORIZED_KEYS="$ARTIFACTS_DIR/docker-authorized-keys.before"
docker exec -u root "$CONTAINER" cat /home/testuser/.ssh/authorized_keys > "$ORIGINAL_AUTHORIZED_KEYS"
[[ -s "$ORIGINAL_AUTHORIZED_KEYS" ]] || fail 'Docker fixture authorized_keys could not be snapshotted for restoration'
AUTHORIZED_KEYS_MODIFIED=0
restore_fixture_authorized_keys() {
  local status=$?
  trap - EXIT
  if (( AUTHORIZED_KEYS_MODIFIED == 1 )); then
    if ! docker exec -i -u root "$CONTAINER" sh -c \
      'cat > /home/testuser/.ssh/authorized_keys && chown testuser:testuser /home/testuser/.ssh/authorized_keys && chmod 600 /home/testuser/.ssh/authorized_keys' \
      < "$ORIGINAL_AUTHORIZED_KEYS"; then
      printf 'FAIL: could not restore Docker fixture authorized_keys from %s\n' "$ORIGINAL_AUTHORIZED_KEYS" >&2
      status=1
    fi
  fi
  pocketshell_release_all
  exit "$status"
}
trap restore_fixture_authorized_keys EXIT
python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys
results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY

"$ADB" -s "$ANDROID_SERIAL" install -r "$APP_APK" > "$ARTIFACTS_DIR/install-app.txt"
"$ADB" -s "$ANDROID_SERIAL" install -r "$TEST_APK" > "$ARTIFACTS_DIR/install-android-test.txt"
"$ADB" -s "$ANDROID_SERIAL" shell pm path "$APP_PACKAGE" | tr -d '\r' | grep -q '^package:' \
  || fail "installed APK is not $APP_PACKAGE; the packaged app output belongs to another lane suffix"
"$ADB" -s "$ANDROID_SERIAL" shell pm clear "$APP_PACKAGE" > "$ARTIFACTS_DIR/clear-isolated-app-data.txt" \
  || fail "could not clear isolated app data for $APP_PACKAGE"
"$ADB" -s "$ANDROID_SERIAL" shell mkdir -p "$DEVICE_FILES"
"$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/key-vault-encrypted-test-key" "$DEVICE_KEY_PATH" \
  > "$ARTIFACTS_DIR/stage-encrypted-key.txt"

ssh_key_copy="$ROOT_DIR/android/app/build/outputs/js-key-vault-fixture-key"
mkdir -p "$(dirname -- "$ssh_key_copy")"
install -m 600 "$ROOT_DIR/tests/docker/test_key" "$ssh_key_copy"
fingerprint="$(ssh-keygen -lf "$ROOT_DIR/tests/docker/test_key.pub" -E sha256 | awk '{print $2}')"
[[ "$fingerprint" == 'SHA256:geJoGi64Up5pm2TGC6bdVNrvlIA1vuPIOtNKo2tLsuQ' ]] \
  || fail "test fixture key fingerprint changed: $fingerprint"
ssh_opts=(-i "$ssh_key_copy" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5 \
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null)
ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 \
  'command -v pocketshell-real >/dev/null && command -v a >/dev/null && command -v aplexer >/dev/null' \
  || fail 'independent SSH oracle cannot authenticate with the Docker fixture key'
docker_log_start_time="$(date -u +'%Y-%m-%dT%H:%M:%S.%NZ')"
printf '%s\n' "$docker_log_start_time" > "$ARTIFACTS_DIR/docker-log-started-at.txt"

# Do not copy credentials from an earlier journey on this shared API 35 AVD.
"$ADB" -s "$ANDROID_SERIAL" logcat -c

# One exact method per instrumentation cycle: $1 = method, $2 = Gradle log.
run_key_vault_instrumentation() {
  local method="$1" log="$2"
  "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
    "-PpocketshellAppIdSuffix=$SUFFIX" \
    "-Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.smoke.SshKeyVaultDockerJourneyTest#$method" \
    -Pandroid.testInstrumentationRunnerArguments.sshHost=10.0.2.2 \
    "-Pandroid.testInstrumentationRunnerArguments.sshPort=$PORT" \
    "-Pandroid.testInstrumentationRunnerArguments.keyFixtureName=$DEVICE_KEY_NAME" \
    "-Pandroid.testInstrumentationRunnerArguments.keyVaultRunId=$RUN_ID" \
    --stacktrace --console=plain 2>&1 | tee "$log"
}

run_document_cycle() {
local method="$1"
pocketshell_start_without_avd_lock_fd run_key_vault_instrumentation "$method" "$ARTIFACTS_DIR/gradle-connected.log"
gradle_pid="$POCKETSHELL_AVD_CHILD_PID"
screenshots="$ARTIFACTS_DIR/device-screenshots"
mkdir -p "$screenshots"
capture_live_screenshot() {
  local screenshot="$1"
  local device_screenshot="$DEVICE_FILES/pocketshell-key-vault/$RUN_ID/$screenshot"
  local output="$screenshots/$screenshot"
  local deadline=$((SECONDS + 90))
  local previous_size=""
  local stable_reads=0
  local current_size=""
  while (( stable_reads < 3 )); do
    current_size="$("$ADB" -s "$ANDROID_SERIAL" shell stat -c %s "$device_screenshot" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$current_size" =~ ^[0-9]+$ ]] && (( current_size > 0 )); then
      if [[ "$current_size" == "$previous_size" ]]; then
        stable_reads=$((stable_reads + 1))
      else
        previous_size="$current_size"
        stable_reads=1
      fi
    else
      stable_reads=0
      previous_size=""
    fi
    kill -0 "$gradle_pid" 2>/dev/null || return 1
    (( SECONDS < deadline )) || return 1
    (( stable_reads >= 3 )) || sleep 0.2
  done
  "$ADB" -s "$ANDROID_SERIAL" pull "$device_screenshot" "$output" >/dev/null || return 1
  [[ -s "$output" ]] || fail "missing Android screenshot: $screenshot"
  validate_png "$output" || fail "Android screenshot is truncated or invalid: $screenshot"
}
screenshots_captured=0
for screenshot in ssh-keys.png ssh-host-form.png ssh-key-delete-confirmation.png diagnostics-export-preview.png; do
  if capture_live_screenshot "$screenshot"; then
    screenshots_captured=$((screenshots_captured + 1))
  else
    break
  fi
done

authorize_generated_key() {
  local deadline=$((SECONDS + 180))
  local public_size=""
  local fingerprint_size=""
  while (( SECONDS < deadline )); do
    public_size="$("$ADB" -s "$ANDROID_SERIAL" shell stat -c %s "$DEVICE_GENERATED_PUBLIC_KEY" 2>/dev/null | tr -d '\r' || true)"
    fingerprint_size="$("$ADB" -s "$ANDROID_SERIAL" shell stat -c %s "$DEVICE_GENERATED_FINGERPRINT" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$public_size" =~ ^[0-9]+$ && "$fingerprint_size" =~ ^[0-9]+$ ]] \
      && (( public_size > 0 && fingerprint_size > 0 )); then
      local public_key="$ARTIFACTS_DIR/generated-authorized-key.pub"
      local fingerprint_file="$ARTIFACTS_DIR/generated-key-fingerprint.txt"
      "$ADB" -s "$ANDROID_SERIAL" pull "$DEVICE_GENERATED_PUBLIC_KEY" "$public_key" >/dev/null \
        || { printf 'FAIL: could not pull the generated public key from Android\n' >&2; return 1; }
      "$ADB" -s "$ANDROID_SERIAL" pull "$DEVICE_GENERATED_FINGERPRINT" "$fingerprint_file" >/dev/null \
        || { printf 'FAIL: could not pull the generated fingerprint from Android\n' >&2; return 1; }
      local expected_fingerprint
      local observed_fingerprint
      expected_fingerprint="$(tr -d '\r\n' < "$fingerprint_file")"
      observed_fingerprint="$(ssh-keygen -lf "$public_key" -E sha256 | awk '{print $2}')" \
        || { printf 'FAIL: generated public key is not valid OpenSSH public-key evidence\n' >&2; return 1; }
      [[ "$expected_fingerprint" =~ ^SHA256:[A-Za-z0-9+/]{43}$ ]] \
        || { printf 'FAIL: generated key fingerprint evidence is malformed\n' >&2; return 1; }
      [[ "$observed_fingerprint" == "$expected_fingerprint" ]] \
        || { printf 'FAIL: generated public key fingerprint differs from the UI selection\n' >&2; return 1; }
      [[ "$(wc -l < "$public_key" | tr -d ' ')" == 1 ]] \
        || { printf 'FAIL: generated public authorization evidence must contain exactly one key\n' >&2; return 1; }
      AUTHORIZED_KEYS_MODIFIED=1
      docker exec -i -u root "$CONTAINER" sh -c \
        'cat >> /home/testuser/.ssh/authorized_keys && chown testuser:testuser /home/testuser/.ssh/authorized_keys && chmod 600 /home/testuser/.ssh/authorized_keys' \
        < "$public_key" || { printf 'FAIL: could not authorize the generated public key in the isolated Docker fixture\n' >&2; return 1; }
      printf 'authorized_generated_fingerprint=%s\n' "$expected_fingerprint" > "$ARTIFACTS_DIR/generated-key-authorization.txt" \
        || { printf 'FAIL: could not preserve generated-key authorization evidence\n' >&2; return 1; }
      "$ADB" -s "$ANDROID_SERIAL" shell touch "$DEVICE_GENERATED_READY" \
        || { printf 'FAIL: could not release the generated-key authorization handshake to Android\n' >&2; return 1; }
      return 0
    fi
    kill -0 "$gradle_pid" 2>/dev/null || return 1
    sleep 0.2
  done
  printf 'FAIL: timed out waiting for the generated public key from the instrumented Android vault\n' >&2
  return 1
}

capture_live_evidence() {
  local deadline=$((SECONDS + 120))
  local evidence
  local previous_size
  local stable_reads
  while (( SECONDS < deadline )); do
    local ready=1
    for evidence in diagnostics-export-preview.json connect-to-session-timing.json locked-key-reconnect-phases.json locked-key-reconnect-native-codes.txt; do
      local current_size
      current_size="$("$ADB" -s "$ANDROID_SERIAL" shell stat -c %s "$DEVICE_RUN_DIR/$evidence" 2>/dev/null | tr -d '\r' || true)"
      if [[ ! "$current_size" =~ ^[0-9]+$ ]] || (( current_size == 0 )); then
        ready=0
        break
      fi
      previous_size="${evidence_sizes[$evidence]:-}"
      stable_reads="${evidence_stable_reads[$evidence]:-0}"
      if [[ "$current_size" == "$previous_size" ]]; then
        stable_reads=$((stable_reads + 1))
      else
        previous_size="$current_size"
        stable_reads=1
      fi
      evidence_sizes[$evidence]="$previous_size"
      evidence_stable_reads[$evidence]="$stable_reads"
      (( stable_reads >= 2 )) || ready=0
    done
    if (( ready == 1 )); then
      for evidence in diagnostics-export-preview.json connect-to-session-timing.json locked-key-reconnect-phases.json locked-key-reconnect-native-codes.txt; do
        "$ADB" -s "$ANDROID_SERIAL" pull "$DEVICE_RUN_DIR/$evidence" "$ARTIFACTS_DIR/$evidence" >/dev/null \
          || { printf 'FAIL: could not capture live Android evidence: %s\n' "$evidence" >&2; return 1; }
      done
      "$ADB" -s "$ANDROID_SERIAL" shell touch "$DEVICE_EVIDENCE_CAPTURED" \
        || { printf 'FAIL: could not confirm live evidence capture to Android\n' >&2; return 1; }
      return 0
    fi
    kill -0 "$gradle_pid" 2>/dev/null || return 1
    sleep 0.2
  done
  printf 'FAIL: timed out waiting for same-run Android evidence files\n' >&2
  return 1
}
declare -A evidence_sizes=()
declare -A evidence_stable_reads=()

generated_authorization_status=0
authorize_generated_key || generated_authorization_status=$?
if (( generated_authorization_status == 0 )); then
  capture_live_evidence || generated_authorization_status=$?
fi
if wait "$gradle_pid"; then
  gradle_status=0
else
  gradle_status=$?
fi
if (( gradle_status != 0 )); then
  mkdir -p "$ARTIFACTS_DIR/failure-diagnostics"
  capture_sanitized_logcat "$ARTIFACTS_DIR/failure-diagnostics/logcat.txt" || true
  "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$ARTIFACTS_DIR/failure-diagnostics/screen.png" 2>&1 || true
  pocketshell_android_capture_input_diagnostics "$ADB" "$ANDROID_SERIAL" "$ARTIFACTS_DIR/failure-diagnostics/android-input"
  "$ROOT_DIR/scripts/check-android-input-diagnostics.py" --dir "$ARTIFACTS_DIR/failure-diagnostics/android-input" >&2 || true
  docker logs --since "$docker_log_start_time" --timestamps "$CONTAINER" > "$ARTIFACTS_DIR/failure-diagnostics/docker-agents.log" 2>&1 || true
  exit "$gradle_status"
fi

"$ROOT_DIR/scripts/check-js-key-vault-results.py" --results-dir "$RESULTS_DIR" \
  --method "$method" --evidence-subdir document \
  --evidence-dir "$ARTIFACTS_DIR/instrumentation-results"
(( generated_authorization_status == 0 )) || fail 'Android instrumentation passed but generated-key authorization did not complete'
(( screenshots_captured == 4 )) || fail "instrumentation passed but only $screenshots_captured Android screenshots were retrieved before the test package was removed"
for evidence in diagnostics-export-preview.json connect-to-session-timing.json locked-key-reconnect-phases.json locked-key-reconnect-native-codes.txt; do
  [[ -s "$ARTIFACTS_DIR/$evidence" ]] || fail "missing same-run Android evidence: $evidence"
done
capture_sanitized_logcat "$ARTIFACTS_DIR/android-logcat.txt" \
  || fail 'Android logcat contained credential fields; sensitive lines were redacted from the artifact'
docker logs --since "$docker_log_start_time" --timestamps "$CONTAINER" > "$ARTIFACTS_DIR/docker-agents.log" 2>&1
generated_fingerprint="$(tr -d '\r\n' < "$ARTIFACTS_DIR/generated-key-fingerprint.txt")"
python3 - "$ARTIFACTS_DIR/docker-agents.log" "$fingerprint" "$generated_fingerprint" \
  > "$ARTIFACTS_DIR/app-authenticated-fingerprint.txt" <<'PY'
import re
import sys
from pathlib import Path

path, imported_fingerprint, generated_fingerprint = sys.argv[1:]
lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
expected = {
    "imported-key": imported_fingerprint,
    "generated-key": generated_fingerprint,
}
accepted_by_fingerprint = {fingerprint: [] for fingerprint in expected.values()}
for line in lines:
    match = re.search(r"Accepted publickey for testuser from (\S+) port \d+ ssh2: ED25519 (\S+)", line)
    if not match or match.group(2) not in accepted_by_fingerprint:
        continue
    source = match.group(1)
    if source in {"::1", "127.0.0.1", "localhost"}:
        continue
    accepted_by_fingerprint[match.group(2)].append((line, source))
for label, expected_fingerprint in expected.items():
    accepted = accepted_by_fingerprint[expected_fingerprint]
    if not accepted:
        raise SystemExit(f"FAIL: no post-start external Docker authentication accepted {label} {expected_fingerprint}")
    print(f"{label}_accepted_line={accepted[0][0]}")
    print(f"{label}_accepted_external_source={accepted[0][1]}")
    print(f"{label}_accepted_external_auth_count={len(accepted)}")
    print(f"{label}_expected_fingerprint={expected_fingerprint}")
print("imported_key_docker_authentication=PASS")
print("generated_key_docker_authentication=PASS")
PY

imported_session_name="keyvault-$RUN_ID"
generated_session_name="keyvault-generated-$RUN_ID"
ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 \
  '/usr/local/bin/pocketshell-real sessions list --json' > "$ARTIFACTS_DIR/host-session-list.json"
python3 - "$ARTIFACTS_DIR/host-session-list.json" "$imported_session_name" "$generated_session_name" > "$ARTIFACTS_DIR/host-oracle.txt" <<'PY'
import json
import sys
from pathlib import Path

path, *expected_tags = sys.argv[1:]
payload = json.loads(Path(path).read_text(encoding="utf-8"))
if not isinstance(payload, dict) or payload.get("schema") != 3 or not isinstance(payload.get("sessions"), list):
    raise SystemExit("FAIL: host pocketshell CLI did not return schema-3 sessions")
for expected_tag in expected_tags:
    rows = [row for row in payload["sessions"] if isinstance(row, dict) and row.get("tag") == expected_tag]
    if len(rows) != 1 or not rows[0].get("id") or not rows[0].get("workspace"):
        raise SystemExit(f"FAIL: expected one independent host session row for {expected_tag}; found {len(rows)}")
    row = rows[0]
    print(f"session_tag={row['tag']}")
    print(f"session_id={row['id']}")
    print(f"workspace={row['workspace']}")
print("host_ssh_authentication=PASS")
print("imported_and_generated_docker_fingerprints=PASS")
print("independent_session_list_for_both_credentials=PASS")
PY

}

reset_device_for_cycle() {
  local label="$1"
  "$ADB" -s "$ANDROID_SERIAL" install -r "$APP_APK" > "$ARTIFACTS_DIR/install-app-$label.txt"
  "$ADB" -s "$ANDROID_SERIAL" install -r "$TEST_APK" > "$ARTIFACTS_DIR/install-android-test-$label.txt"
  "$ADB" -s "$ANDROID_SERIAL" shell pm clear "$APP_PACKAGE" > "$ARTIFACTS_DIR/clear-isolated-app-data-$label.txt" \
    || fail "could not clear isolated app data for $APP_PACKAGE before the $label cycle"
  "$ADB" -s "$ANDROID_SERIAL" shell mkdir -p "$DEVICE_FILES"
  "$ADB" -s "$ANDROID_SERIAL" push "$ROOT_DIR/tests/docker/key-vault-encrypted-test-key" "$DEVICE_KEY_PATH" \
    > "$ARTIFACTS_DIR/stage-encrypted-key-$label.txt"
}

restore_authorized_keys_now() {
  docker exec -i -u root "$CONTAINER" sh -c \
    'cat > /home/testuser/.ssh/authorized_keys && chown testuser:testuser /home/testuser/.ssh/authorized_keys && chmod 600 /home/testuser/.ssh/authorized_keys' \
    < "$ORIGINAL_AUTHORIZED_KEYS" || fail 'could not restore Docker fixture authorized_keys between cycles'
}

# #3021: paste the fixture key as text, connect, generate a key, copy/share
# its public line, install it on the fixture through the app, reconnect with
# it. Only the app may authorize the generated key in this cycle.
run_setup_cycle() {
  local method="$1"
  local setup_dir="$ARTIFACTS_DIR/setup"
  local device_setup_dir="$DEVICE_RUN_DIR/setup"
  mkdir -p "$setup_dir"
  restore_authorized_keys_now
  reset_device_for_cycle setup
  "$ADB" -s "$ANDROID_SERIAL" logcat -c
  local setup_log_start
  setup_log_start="$(date -u +'%Y-%m-%dT%H:%M:%S.%NZ')"
  printf '%s\n' "$setup_log_start" > "$setup_dir/docker-log-started-at.txt"
  python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys
results = Path(sys.argv[1])
if results.exists():
    shutil.rmtree(results)
PY
  AUTHORIZED_KEYS_MODIFIED=1
  pocketshell_start_without_avd_lock_fd run_key_vault_instrumentation "$method" "$setup_dir/gradle-connected.log"
  local setup_pid="$POCKETSHELL_AVD_CHILD_PID"
  local capture_status=1
  local deadline=$((SECONDS + 600))
  while (( SECONDS < deadline )); do
    if "$ADB" -s "$ANDROID_SERIAL" shell test -f "$device_setup_dir/evidence-ready" 2>/dev/null; then
      if "$ADB" -s "$ANDROID_SERIAL" pull "$device_setup_dir/." "$setup_dir/device" >/dev/null; then
        "$ADB" -s "$ANDROID_SERIAL" shell touch "$device_setup_dir/artifacts-captured" && capture_status=0
      fi
      break
    fi
    kill -0 "$setup_pid" 2>/dev/null || break
    sleep 0.5
  done
  local setup_status=0
  wait "$setup_pid" || setup_status=$?
  if (( setup_status != 0 )); then
    mkdir -p "$setup_dir/failure-diagnostics"
    capture_sanitized_logcat "$setup_dir/failure-diagnostics/logcat.txt" || true
    "$ADB" -s "$ANDROID_SERIAL" exec-out screencap -p > "$setup_dir/failure-diagnostics/screen.png" 2>&1 || true
    docker logs --since "$setup_log_start" --timestamps "$CONTAINER" > "$setup_dir/failure-diagnostics/docker-agents.log" 2>&1 || true
    docker exec -u root "$CONTAINER" cat /home/testuser/.ssh/authorized_keys > "$setup_dir/failure-diagnostics/authorized_keys" 2>&1 || true
    exit "$setup_status"
  fi
  "$ROOT_DIR/scripts/check-js-key-vault-results.py" --results-dir "$RESULTS_DIR" \
    --method "$method" --evidence-subdir setup \
    --evidence-dir "$ARTIFACTS_DIR/instrumentation-results"
  (( capture_status == 0 )) || fail 'setup cycle passed but its same-run device evidence was not captured'
  local screenshot
  for screenshot in host-form-add-key.png settings-ssh-keys.png ssh-key-paste.png ssh-key-paste-ime.png ssh-key-paste-error.png \
      ssh-key-generated-public.png ssh-key-install-confirmation.png ssh-key-installed.png; do
    [[ -s "$setup_dir/device/$screenshot" ]] || fail "missing setup-cycle Android screenshot: $screenshot"
    validate_png "$setup_dir/device/$screenshot" || fail "setup-cycle screenshot is truncated or invalid: $screenshot"
    cp "$setup_dir/device/$screenshot" "$screenshots/setup-$screenshot"
  done
  capture_sanitized_logcat "$setup_dir/android-logcat.txt" \
    || fail 'Android logcat contained credential fields during the setup cycle'
  docker exec -u root "$CONTAINER" cat /home/testuser/.ssh/authorized_keys > "$setup_dir/authorized_keys.after"
  docker logs --since "$setup_log_start" --timestamps "$CONTAINER" > "$setup_dir/docker-agents.log" 2>&1
  ssh -q "${ssh_opts[@]}" testuser@127.0.0.1 \
    '/usr/local/bin/pocketshell-real sessions list --json' > "$setup_dir/host-session-list.json"
  python3 - "$setup_dir" "$ORIGINAL_AUTHORIZED_KEYS" "$fingerprint" "keysetup-$RUN_ID" > "$setup_dir/setup-oracle.txt" <<'PY'
import base64
import hashlib
import json
import re
import sys
from datetime import datetime
from pathlib import Path

setup_dir, original_path, pasted_fingerprint, session_tag = sys.argv[1:]
root = Path(setup_dir)
device = root / "device"
public_line = (device / "generated-public-key.pub").read_text(encoding="ascii").strip()
match = re.fullmatch(r"(ssh-ed25519) ([A-Za-z0-9+/]+=*) (.+)", public_line)
if not match:
    raise SystemExit(f"FAIL: app public key is not one OpenSSH Ed25519 line: {public_line!r}")
blob = base64.b64decode(match.group(2))
generated_fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(blob).digest()).decode().rstrip("=")
recorded = (device / "generated-key-fingerprint.txt").read_text(encoding="ascii").strip()
if recorded != generated_fingerprint:
    raise SystemExit(f"FAIL: UI fingerprint {recorded} differs from the shown public line {generated_fingerprint}")

share = json.loads((device / "public-key-share.json").read_text(encoding="utf-8"))
if share.get("clipboard") != public_line or share.get("sharedText") != public_line or share.get("sharedType") != "text/plain":
    raise SystemExit("FAIL: clipboard or Android share sheet did not carry the exact public line")

installs = json.loads((device / "install-results.json").read_text(encoding="utf-8"))
if [item.get("outcome") for item in installs] != ["installed", "already-present"]:
    raise SystemExit(f"FAIL: install outcomes were {installs}")

original = Path(original_path).read_text(encoding="utf-8").splitlines()
after = (root / "authorized_keys.after").read_text(encoding="utf-8").splitlines()
added = [line for line in after if line not in original]
if after[: len(original)] != original or added != [public_line]:
    raise SystemExit(f"FAIL: authorized_keys must gain exactly the app's public line once; added={added}")
if sum(1 for line in after if match.group(2) in line) != 1:
    raise SystemExit("FAIL: the installed key appears more than once in authorized_keys")

start = datetime.fromisoformat((root / "docker-log-started-at.txt").read_text(encoding="ascii").strip().replace("Z", "+00:00"))
accepted = {pasted_fingerprint: [], generated_fingerprint: []}
for line in (root / "docker-agents.log").read_text(encoding="utf-8", errors="replace").splitlines():
    found = re.search(r"Accepted publickey for testuser from (\S+) port \d+ ssh2: ED25519 (\S+)", line)
    if not found or found.group(2) not in accepted or found.group(1) in {"::1", "127.0.0.1", "localhost"}:
        continue
    if datetime.fromisoformat(line.split()[0].replace("Z", "+00:00")) < start:
        continue
    accepted[found.group(2)].append(line)
for label, value in (("pasted-key", pasted_fingerprint), ("installed-generated-key", generated_fingerprint)):
    if not accepted[value]:
        raise SystemExit(f"FAIL: no post-start external Docker authentication accepted the {label} {value}")
    print(f"{label}_accepted_line={accepted[value][0]}")
    print(f"{label}_fingerprint={value}")

sessions = json.loads((root / "host-session-list.json").read_text(encoding="utf-8")).get("sessions", [])
if sum(1 for row in sessions if isinstance(row, dict) and row.get("tag") == session_tag) != 1:
    raise SystemExit(f"FAIL: independent host lookup did not find session {session_tag}")
print(f"session_tag={session_tag}")
print("pasted_key_docker_authentication=PASS")
print("public_key_clipboard_and_share_sheet=PASS")
print("install_on_host_appended_exactly_once=PASS")
print("installed_generated_key_reconnect_authentication=PASS")
PY
  cat "$setup_dir/setup-oracle.txt"
}

run_cycle() {
  local name="$1" method="$2"
  case "$name" in
    document) run_document_cycle "$method" ;;
    setup) run_setup_cycle "$method" ;;
    *) fail "unknown key-vault cycle: $name" ;;
  esac
}

run_cycle document importsEncryptedDocumentConnectsAndKeepsSecretsOutOfWebViewState
run_cycle setup pastesKeySharesPublicKeyAndInstallsGeneratedKeyOnHost
"$ROOT_DIR/scripts/check-js-key-vault-results.py" --results-dir "$ARTIFACTS_DIR/instrumentation-results"

python3 - "$ARTIFACTS_DIR" "$ROOT_DIR/tests/docker/key-vault-encrypted-test-key" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
# The fixture's own base64 body lines: the pasted text must never reach a log.
fixture_body = [line.strip() for line in Path(sys.argv[2]).read_text(encoding="ascii").splitlines()
                if len(line.strip()) >= 40 and not line.startswith("-----")]
if len(fixture_body) < 3:
    raise SystemExit("FAIL: could not derive the fixture private-key body markers")
markers = tuple(fixture_body) + (
    "pocketshell-vault-test-passphrase",
    "-----BEGIN OPENSSH PRIVATE KEY-----",
    "-----BEGIN PRIVATE KEY-----",
    "-----BEGIN RSA PRIVATE KEY-----",
    '"passphrase":',
    '"privateKeyPem":',
)
text_suffixes = {".txt", ".log", ".json", ".jsonl", ".xml", ".html", ".csv"}
leaks = []
for path in root.rglob("*"):
    if not path.is_file() or path.suffix.lower() not in text_suffixes:
        continue
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        continue
    for marker in markers:
        if marker in content:
            leaks.append(f"{path.relative_to(root)} contains {marker!r}")
if leaks:
    raise SystemExit("FAIL: key secret material leaked to logs or diagnostics:\n" + "\n".join(leaks))
(root / "secret-nonleak-oracle.txt").write_text(
    "passphrase_absent_from_gradle_log_logcat_docker_log_and_host_reports=PASS\n"
    "private_key_pem_marker_absent_from_gradle_log_logcat_docker_log_and_host_reports=PASS\n"
    f"pasted_fixture_body_lines_absent_from_text_artifacts=PASS ({len(fixture_body)} lines checked)\n",
    encoding="utf-8",
)
PY
python3 "$ROOT_DIR/scripts/check-js-key-vault-artifacts.py" \
  --artifact-dir "$ARTIFACTS_DIR" --run-id "$RUN_ID"

installed_package="$("$ADB" -s "$ANDROID_SERIAL" shell pm list packages "$APP_PACKAGE" | tr -d '\r')"
if [[ "$installed_package" == *"package:$APP_PACKAGE"* ]]; then
  "$ADB" -s "$ANDROID_SERIAL" shell pm clear "$APP_PACKAGE" > "$ARTIFACTS_DIR/clear-vault-after-capture.txt"
else
  printf 'target app package was removed by connected-test cleanup; no vault remained on device\n' \
    > "$ARTIFACTS_DIR/clear-vault-after-capture.txt"
fi
printf 'PASS: packaged key-vault Docker journey completed on API %s. Evidence: %s\n' "$device_api" "$ARTIFACTS_DIR"
