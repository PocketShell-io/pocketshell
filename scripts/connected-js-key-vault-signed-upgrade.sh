#!/usr/bin/env bash
# Signed 0.5.6-to-candidate migration proof; never touches an existing base app.
# Blocking CI lane: scripts/ci-js-first-packaged-lanes.sh runs this after the
# other packaged lanes, and scripts/check-js-first-android-journeys.py fails if
# that invocation, the exact three-method selector, or the result contract drifts.
set -euo pipefail
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$ROOT_DIR"
ANDROID_SDK="${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/alexey/Android/Sdk}}}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"
LEGACY_APK="${LEGACY_APK:-$ROOT_DIR/android/app/build/outputs/js-key-vault/signed-upgrade-fixture/pocketshell-0.5.6-debug.apk}"
# The fixture is the published v0.5.6 debug APK (signed with the committed
# debug.keystore that the candidate also uses). Pin its bytes so a replaced or
# truncated download can never stand in for the real signed prior install.
LEGACY_APK_URL=https://github.com/PocketShell-io/pocketshell/releases/download/v0.5.6/pocketshell-0.5.6-debug.apk
LEGACY_APK_SHA256=2994e0a48d6fdb690ba41f5a613ee0fc89b283591bee19cbd542512a6cfc1c67
APP_PACKAGE=com.pocketshell.app
TEST_PACKAGE=com.pocketshell.app.test
PORT="" CONTAINER="" RUN_ID=""
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --port) [[ $# -ge 2 ]] || fail '--port needs a value'; PORT="$2"; shift 2 ;;
    --container) [[ $# -ge 2 ]] || fail '--container needs a value'; CONTAINER="$2"; shift 2 ;;
    --run-id) [[ $# -ge 2 ]] || fail '--run-id needs a value'; RUN_ID="$2"; shift 2 ;;
    --help|-h) echo 'Usage: scripts/connected-js-key-vault-signed-upgrade.sh --port PORT --container NAME --run-id ID'; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ "$PORT" =~ ^(2243|2244|2245)$ ]] || fail '--port must be an isolated fixture port (2243-2245)'
[[ "$CONTAINER" == "pocketshell-test-agents-$PORT" ]] || fail '--container must match the isolated fixture port'
[[ "$RUN_ID" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{2,28}$ ]] || fail '--run-id must be a path-safe 3-29 character token'
[[ -x "$ADB" ]] || fail "adb is missing: $ADB"
legacy_apk_sha256() { sha256sum "$LEGACY_APK" 2>/dev/null | awk '{print $1}'; }
if [[ "$(legacy_apk_sha256)" != "$LEGACY_APK_SHA256" ]]; then
  mkdir -p "$(dirname -- "$LEGACY_APK")"
  curl --fail --silent --show-error --location --retry 3 --retry-all-errors \
    --output "$LEGACY_APK.partial" "$LEGACY_APK_URL" || fail "could not download signed v0.5.6 debug APK: $LEGACY_APK_URL"
  mv -f -- "$LEGACY_APK.partial" "$LEGACY_APK"
fi
[[ "$(legacy_apk_sha256)" == "$LEGACY_APK_SHA256" ]] || fail "signed v0.5.6 debug APK does not match pinned SHA-256 $LEGACY_APK_SHA256"
[[ -f tests/docker/test_key && -f tests/docker/test_key.pub ]] || fail 'Docker key fixtures are missing'
docker inspect "$CONTAINER" >/dev/null 2>&1 || fail "Docker fixture is missing: $CONTAINER"
[[ "$(docker inspect --format '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || true)" == healthy ]] || fail 'Docker fixture is unhealthy'

source scripts/lib/disk-preflight.sh
source scripts/lib/gradle-output-lock.sh
source scripts/lib/avd-lock.sh
source scripts/lib/android-input-preflight.sh
pocketshell_disk_preflight "$ROOT_DIR/android" 'signed key-vault upgrade' || exit $?
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' "signed key-vault upgrade $RUN_ID"
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
  mapfile -t devices < <("$ADB" devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
  (( ${#devices[@]} == 1 )) || fail "expected one online emulator; found ${#devices[@]}"
  export ANDROID_SERIAL="${devices[0]}"
fi
[[ "$("$ADB" -s "$ANDROID_SERIAL" get-state 2>/dev/null || true)" == device ]] || fail 'selected emulator is offline'
api="$("$ADB" -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api" =~ ^[0-9]+$ ]] && (( api >= 35 )) || fail "API 35+ is required; got ${api:-unknown}"
export POCKETSHELL_AVD_LOCK_CONTINUOUS=1
export POCKETSHELL_AVD_LOCK_FILE="$(pocketshell_avd_lock_file_for_serial "$ROOT_DIR" "$ANDROID_SERIAL")"
pocketshell_acquire_avd_lock "$ROOT_DIR"
pocketshell_assert_avd_lock_owned "$POCKETSHELL_AVD_LOCK_FILE"

if "$ADB" -s "$ANDROID_SERIAL" shell pm path "$APP_PACKAGE" | tr -d '\r' | grep -q '^package:'; then
  fail "refusing to overwrite existing $APP_PACKAGE data; use a fresh API 35 emulator"
fi
if "$ADB" -s "$ANDROID_SERIAL" shell pm path "$TEST_PACKAGE" | tr -d '\r' | grep -q '^package:'; then
  fail "refusing to replace existing $TEST_PACKAGE"
fi
ARTIFACTS_DIR="$ROOT_DIR/android/app/build/outputs/js-key-vault-upgrade/$RUN_ID"
RESULTS_DIR="$ROOT_DIR/android/app/build/outputs/androidTest-results/connected/debug"
[[ ! -e "$ARTIFACTS_DIR" ]] || fail "refusing to overwrite $ARTIFACTS_DIR"
mkdir -p "$ARTIFACTS_DIR"
pocketshell_android_input_preflight "$ADB" "$ANDROID_SERIAL" "$ARTIFACTS_DIR/input-preflight.txt" \
  || fail "Android input preflight failed on $ANDROID_SERIAL; see $ARTIFACTS_DIR/input-preflight.txt"
installed_by_run=0
cleanup() {
  local status=$?
  trap - EXIT
  if (( installed_by_run )); then
    "$ADB" -s "$ANDROID_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    "$ADB" -s "$ANDROID_SERIAL" uninstall "$APP_PACKAGE" >/dev/null 2>&1 || true
  fi
  pocketshell_release_all
  exit "$status"
}
trap cleanup EXIT

APP_APK="$ROOT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="$ROOT_DIR/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
pocketshell_run_without_avd_lock_fd scripts/assemble-debug.sh
pocketshell_run_without_avd_lock_fd android/gradlew -p android :app:assembleDebugAndroidTest --stacktrace --console=plain
[[ -s "$APP_APK" && -s "$TEST_APK" ]] || fail 'candidate APKs were not built'
# Same resolution as scripts/check-apk-signing.sh: the newest build-tools that
# ships BOTH tools (hosted runners carry partial/newer directories).
AAPT="" APKSIGNER=""
while IFS= read -r tools_dir; do
  if [[ -x "$ANDROID_SDK/build-tools/$tools_dir/aapt" && -x "$ANDROID_SDK/build-tools/$tools_dir/apksigner" ]]; then
    AAPT="$ANDROID_SDK/build-tools/$tools_dir/aapt"
    APKSIGNER="$ANDROID_SDK/build-tools/$tools_dir/apksigner"
    break
  fi
done < <(find "$ANDROID_SDK/build-tools" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -rV)
[[ -n "$AAPT" ]] || fail "no build-tools version with both aapt and apksigner under $ANDROID_SDK/build-tools"
# apksigner labels the signer "Signer #1" or, for v3/rotation-aware output,
# "Signer (minSdkVersion=..)"; take the first SHA-256 digest either way.
signer_sha256() {
  local output
  output="$("$APKSIGNER" verify --print-certs "$1" 2>&1)" || { printf '%s\n' "$output" >&2; return 1; }
  printf '%s\n' "$output" | grep -m1 'certificate SHA-256 digest:' | sed 's/.*certificate SHA-256 digest:[[:space:]]*//' | tr -d ': ' | tr 'A-F' 'a-f'
}
legacy_meta="$("$AAPT" dump badging "$LEGACY_APK" | sed -n '1p')"
candidate_meta="$("$AAPT" dump badging "$APP_APK" | sed -n '1p')"
[[ "$legacy_meta" == *"name='$APP_PACKAGE'"* && "$candidate_meta" == *"name='$APP_PACKAGE'"* ]] || fail 'legacy/candidate APK package mismatch'
[[ "$legacy_meta" == *"versionName='0.5.6'"* ]] || fail 'legacy APK must be version 0.5.6'
legacy_cert="$(signer_sha256 "$LEGACY_APK")" || fail "apksigner could not read the legacy APK signer ($APKSIGNER)"
candidate_cert="$(signer_sha256 "$APP_APK")" || fail "apksigner could not read the candidate APK signer ($APKSIGNER)"
[[ "$legacy_cert" =~ ^[0-9a-f]{64}$ && "$legacy_cert" == "$candidate_cert" ]] \
  || fail "signed APK certificates differ: legacy=${legacy_cert:-<none>} candidate=${candidate_cert:-<none>} ($APKSIGNER)"
candidate_code="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$candidate_meta")"
legacy_code="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$legacy_meta")"
[[ "$candidate_code" =~ ^[0-9]+$ && "$legacy_code" =~ ^[0-9]+$ ]] && (( candidate_code >= legacy_code )) || fail 'candidate APK versionCode must not be lower than signed v0.5.6'
python3 - "$ARTIFACTS_DIR/apk-provenance.json" "$LEGACY_APK" "$APP_APK" "$legacy_meta" "$candidate_meta" "$legacy_cert" "$candidate_cert" <<'PY'
import hashlib
import json
import sys
from pathlib import Path
destination, legacy, candidate, legacy_meta, candidate_meta, legacy_cert, candidate_cert = sys.argv[1:]
Path(destination).write_text(json.dumps({
    "schema": 1,
    "legacy": {"metadata": legacy_meta, "sha256": hashlib.sha256(Path(legacy).read_bytes()).hexdigest(), "certificateSha256": legacy_cert},
    "candidate": {"metadata": candidate_meta, "sha256": hashlib.sha256(Path(candidate).read_bytes()).hexdigest(), "certificateSha256": candidate_cert},
}, indent=2) + "\n", encoding="utf-8")
PY

key_oracle="$ROOT_DIR/android/app/build/outputs/js-key-vault-upgrade-test-key"
install -m 600 tests/docker/test_key "$key_oracle"
key_fingerprint="$(ssh-keygen -lf tests/docker/test_key.pub -E sha256 | awk '{print $2}')"
host_fingerprint="$(ssh-keyscan -T 5 -p "$PORT" -t ed25519 127.0.0.1 2>/dev/null | ssh-keygen -lf - -E sha256 | awk 'NR == 1 {print $2}')"
[[ "$host_fingerprint" =~ ^SHA256:[A-Za-z0-9+/]{43}$ ]] || fail 'could not read Docker SSH host fingerprint'
ssh -q -i "$key_oracle" -p "$PORT" -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null testuser@127.0.0.1 true || fail 'host Docker SSH oracle failed'
printf '%s\n' "$RUN_ID|$api|$legacy_cert|$candidate_cert|$key_fingerprint|$host_fingerprint" > "$ARTIFACTS_DIR/provenance.txt"

seed_sources() {
  local cycle="$1"
  local fixture="$ARTIFACTS_DIR/$cycle/fixture"
  mkdir -p "$fixture"
  python3 - "$fixture/pocketshell.db" "$PORT" "$host_fingerprint" <<'PY'
import sqlite3
import sys
from pathlib import Path

path, port, host_key = sys.argv[1:]
db = sqlite3.connect(path)
db.execute("PRAGMA user_version = 22")
db.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
db.execute("INSERT INTO room_master_table VALUES (42, '998a588a2d2f383454698ea11820fca9')")
db.execute("CREATE TABLE hosts (id INTEGER, name TEXT, hostname TEXT, port INTEGER, username TEXT, keyId INTEGER, maxAutoPort INTEGER, skipPortsBelow INTEGER, scanIntervalSec INTEGER, enabled INTEGER, createdAt INTEGER, lastConnectedAt INTEGER, lastBootstrapAt INTEGER, pocketshellInstalled INTEGER, pocketshellLastDetectedAt INTEGER, pocketshellCliVersion TEXT, pocketshellExpectedCliVersion TEXT, pocketshellVersionCompatible INTEGER, pocketshellDaemonRunning INTEGER, pocketshellDaemonEnabled INTEGER, usageCommandOverride TEXT, treeIdentity TEXT, trustedHostKeyAlgorithm TEXT, trustedHostKeySha256 TEXT)")
db.execute("CREATE TABLE ssh_keys (id INTEGER, name TEXT, privateKeyPath TEXT, fingerprint TEXT, hasPassphrase INTEGER, createdAt INTEGER)")
db.execute("CREATE TABLE port_remappings (id INTEGER, hostId INTEGER, remotePort INTEGER, localPort INTEGER, name TEXT)")
db.execute("CREATE TABLE port_usage (hostId INTEGER, remotePort INTEGER, clickCount INTEGER, totalBytes INTEGER, lastUsedAt INTEGER)")
db.execute("CREATE TABLE project_roots (id INTEGER, hostId INTEGER, label TEXT, path TEXT, createdAt INTEGER, sortOrder INTEGER)")
db.execute("CREATE TABLE snippets (id INTEGER, hostId INTEGER, label TEXT, body TEXT, kind TEXT)")
db.execute("CREATE TABLE ai_api_call_log (id INTEGER, timestampMillis INTEGER, provider TEXT, feature TEXT, inputUnits INTEGER, outputUnits INTEGER, unitCostUsdMillicents INTEGER, computedCostUsdMillicents INTEGER, metadataJson TEXT)")
db.execute("CREATE TABLE pending_transcriptions (id TEXT, audioPath TEXT, recordingTimestampMs INTEGER, destinationContext TEXT, retryCount INTEGER, lastErrorMessage TEXT, audioByteSize INTEGER, createdAtMs INTEGER)")
db.execute("CREATE TABLE command_templates (id INTEGER, hostId INTEGER, label TEXT, commands TEXT)")
db.execute("CREATE TABLE sent_messages (id INTEGER, sessionKey TEXT, body TEXT, sentAtMs INTEGER, delivered INTEGER)")
db.execute("INSERT INTO ssh_keys VALUES (7, 'Migrated Docker key', '/data/user/0/com.pocketshell.app/files/ssh-keys/fixture.pem', 'fixture', 0, 1)")
db.execute("INSERT INTO hosts (id,name,hostname,port,username,keyId,trustedHostKeyAlgorithm,trustedHostKeySha256) VALUES (41,'Migrated Docker host','10.0.2.2',?,'testuser',7,'SHA256',?)", (int(port), host_key))
db.execute("INSERT INTO snippets VALUES (1,41,'Migration fixture','echo preserved-snippet','command')")
db.commit()
db.close()
Path(path).chmod(0o600)
PY
  cat > "$fixture/next_settings.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><int name="terminal_text_size_px" value="32" /><long name="background_grace_millis" value="90000" /></map>
EOF
  cat > "$fixture/composer_drafts.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="host-41">Draft kept after update</string></map>
EOF
  cat > "$fixture/next_sync_selection.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map><string name="sync_future_field">unknown value retained</string></map>
EOF
  cat > "$fixture/workspace_order.xml" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map />
EOF
  chmod 600 "$fixture"/*.xml
}

push_private_file() {
  local local_file="$1" relative_path="$2"
  [[ "$relative_path" =~ ^[A-Za-z0-9_./-]+$ ]] || fail 'invalid private fixture path'
  # adb joins shell arguments into one remote command. Keep the redirection
  # quoted for the shell inside run-as, which owns the app-private directory.
  "$ADB" -s "$ANDROID_SERIAL" shell -T "run-as $APP_PACKAGE sh -c 'umask 077; cat > $relative_path'" < "$local_file"
  "$ADB" -s "$ANDROID_SERIAL" shell run-as "$APP_PACKAGE" chmod 600 "$relative_path"
  local expected observed
  expected="$(sha256sum "$local_file" | awk '{print $1}')"
  observed="$("$ADB" -s "$ANDROID_SERIAL" exec-out run-as "$APP_PACKAGE" cat "$relative_path" | sha256sum | awk '{print $1}')"
  [[ "$expected" == "$observed" ]] || fail "private fixture copy differs: $relative_path"
}

capture_hash_report() {
  local cycle="$1" run_token="$2" filename="$3"
  local device_dir="/sdcard/Android/data/$APP_PACKAGE/files/pocketshell-installed-data-migration/$run_token"
  local device_file="$device_dir/$filename" destination="$ARTIFACTS_DIR/$cycle/$filename"
  # The window opens when Gradle starts, before its build/install; a cold
  # daemon can take >90s before the test writes the report. The loop already
  # stops when Gradle exits; after this cap the run fails, but the following
  # `wait` on Gradle is still bounded only by the CI step timeout.
  local deadline=$((SECONDS + 900)) previous_size="" stable=0
  while (( SECONDS < deadline )); do
    local size
    size="$("$ADB" -s "$ANDROID_SERIAL" shell stat -c %s "$device_file" 2>/dev/null | tr -d '\r' || true)"
    if [[ "$size" =~ ^[0-9]+$ ]] && (( size > 0 )); then
      if [[ "$size" == "$previous_size" ]]; then stable=$((stable + 1)); else previous_size="$size"; stable=1; fi
      if (( stable >= 2 )); then
        "$ADB" -s "$ANDROID_SERIAL" pull "$device_file" "$destination" >/dev/null
        python3 - "$destination" "$filename" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
before, after = report.get("sourceHashesBefore"), report.get("sourceHashesAfter")
if report.get("schema") != 1 or report.get("unchanged") is not True or not isinstance(before, dict) or before != after:
    raise SystemExit("FAIL: source hash report is malformed or shows changed data")
required = {"databases/pocketshell.db", "shared_prefs/next_settings.xml", "shared_prefs/composer_drafts.xml", "shared_prefs/next_sync_selection.xml", "shared_prefs/workspace_order.xml", "files/ssh-keys/fixture.pem"}
if not required.issubset(before): raise SystemExit("FAIL: source hash report omitted a legacy source")
if sys.argv[2] == "malformed-encrypted-source-hashes.json" and "shared_prefs/pocketshell-voice-secrets.xml" not in before:
    raise SystemExit("FAIL: malformed encrypted source hash is missing")
PY
        "$ADB" -s "$ANDROID_SERIAL" shell touch "$device_file.captured"
        return 0
      fi
    else
      previous_size=""; stable=0
    fi
    kill -0 "$gradle_pid" 2>/dev/null || return 1
    sleep 0.2
  done
  return 1
}

run_cycle() {
  local name="$1" method="$2" run_token="$3" fixture_mode="$4"
  local fixture="$ARTIFACTS_DIR/$name/fixture"
  mkdir -p "$ARTIFACTS_DIR/$name"
  seed_sources "$name"
  "$ADB" -s "$ANDROID_SERIAL" install "$LEGACY_APK" > "$ARTIFACTS_DIR/$name/install-signed-v056.txt"
  installed_by_run=1
  "$ADB" -s "$ANDROID_SERIAL" shell run-as "$APP_PACKAGE" mkdir -p databases shared_prefs files/ssh-keys
  push_private_file "$fixture/pocketshell.db" databases/pocketshell.db
  local key_source=tests/docker/test_key
  if [[ "$fixture_mode" == private-key ]]; then
    printf 'malformed private-key fixture\n' > "$fixture/malformed-key.pem"
    key_source="$fixture/malformed-key.pem"
  fi
  push_private_file "$key_source" files/ssh-keys/fixture.pem
  for preference in next_settings composer_drafts next_sync_selection workspace_order; do
    push_private_file "$fixture/$preference.xml" "shared_prefs/$preference.xml"
  done
  sha256sum "$fixture/pocketshell.db" "$fixture"/*.xml "$key_source" > "$ARTIFACTS_DIR/$name/seed-source-sha256.txt"
  "$ADB" -s "$ANDROID_SERIAL" install -r "$APP_APK" > "$ARTIFACTS_DIR/$name/update-to-candidate.txt"

  python3 - "$RESULTS_DIR" <<'PY'
from pathlib import Path
import shutil
import sys
path = Path(sys.argv[1])
if path.exists(): shutil.rmtree(path)
PY
  local log_start_time
  log_start_time="$(date -u +'%Y-%m-%dT%H:%M:%S.%NZ')"
  printf '%s\n' "$log_start_time" > "$ARTIFACTS_DIR/$name/docker-log-started-at.txt"
  "$ADB" -s "$ANDROID_SERIAL" logcat -c
  local connect="true" encrypted="false" malformed_key="false" evidence_name=migration-source-hashes.json
  if [[ "$fixture_mode" == encrypted-preferences ]]; then
    connect=false
    encrypted=true
    evidence_name=malformed-encrypted-source-hashes.json
  elif [[ "$fixture_mode" == private-key ]]; then
    connect=false
    malformed_key=true
    evidence_name=malformed-key-source-hashes.json
  fi
  run_migration_instrumentation() {
    "$ROOT_DIR/android/gradlew" -p "$ROOT_DIR/android" :app:connectedDebugAndroidTest \
      "-Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.migration.InstalledDataMigrationJourneyTest#$method" \
      -Pandroid.testInstrumentationRunnerArguments.installedDataMigrationFixture=true \
      "-Pandroid.testInstrumentationRunnerArguments.installedDataMigrationConnect=$connect" \
      "-Pandroid.testInstrumentationRunnerArguments.installedDataMigrationMalformedEncryptedFixture=$encrypted" \
      "-Pandroid.testInstrumentationRunnerArguments.installedDataMigrationMalformedKeyFixture=$malformed_key" \
      "-Pandroid.testInstrumentationRunnerArguments.installedDataMigrationExpectedHostKey=$host_fingerprint" \
      "-Pandroid.testInstrumentationRunnerArguments.installedDataMigrationRunId=$run_token" \
      --stacktrace --console=plain 2>&1 | tee "$ARTIFACTS_DIR/$name/gradle-connected.log"
  }
  pocketshell_start_without_avd_lock_fd run_migration_instrumentation
  gradle_pid="$POCKETSHELL_AVD_CHILD_PID"
  local hash_status=0
  capture_hash_report "$name" "$run_token" "$evidence_name" || hash_status=$?
  if wait "$gradle_pid"; then
    gradle_status=0
  else
    gradle_status=$?
  fi
  mkdir -p "$ARTIFACTS_DIR/$name/junit"
  find "$RESULTS_DIR" -maxdepth 1 -name 'TEST-*.xml' -type f -exec cp -t "$ARTIFACTS_DIR/$name/junit" {} +
  if (( gradle_status != 0 || hash_status != 0 )); then
    "$ADB" -s "$ANDROID_SERIAL" logcat -d -v threadtime > "$ARTIFACTS_DIR/$name/android-logcat.txt" || true
    pocketshell_android_capture_input_diagnostics "$ADB" "$ANDROID_SERIAL" "$ARTIFACTS_DIR/$name/failure-diagnostics/android-input"
    "$ROOT_DIR/scripts/check-android-input-diagnostics.py" --dir "$ARTIFACTS_DIR/$name/failure-diagnostics/android-input" >&2 || true
    (( gradle_status != 0 )) || fail "same-run migration source hashes were not captured ($name)"
    return "$gradle_status"
  fi
  docker logs --since "$log_start_time" --timestamps "$CONTAINER" > "$ARTIFACTS_DIR/$name/docker-agents.log" 2>&1
  if [[ "$fixture_mode" == none ]]; then
    python3 - "$ARTIFACTS_DIR/$name/docker-agents.log" "$key_fingerprint" > "$ARTIFACTS_DIR/$name/migrated-host-authentication.txt" <<'PY'
import re
import sys
from pathlib import Path
path, fingerprint = sys.argv[1:]
lines = Path(path).read_text(encoding="utf-8", errors="replace").splitlines()
pattern = re.compile(r"Accepted publickey for testuser from (\S+) port \d+ ssh2: ED25519 " + re.escape(fingerprint) + r"(?:\s|$)")
matches = [line for line in lines if (match := pattern.search(line)) and match.group(1) not in {"::1", "127.0.0.1", "localhost"}]
if not matches: raise SystemExit("FAIL: Docker did not record same-run external authentication with the migrated key")
print("accepted_publickey_fingerprint=" + fingerprint)
print("accepted_external_log_line_count=" + str(len(matches)))
print("accepted_line=" + matches[0])
PY
  fi
}

remove_synthetic_install() {
  local cycle="$1"
  "$ADB" -s "$ANDROID_SERIAL" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
  if "$ADB" -s "$ANDROID_SERIAL" shell pm path "$APP_PACKAGE" | tr -d '\r' | grep -q '^package:'; then
    "$ADB" -s "$ANDROID_SERIAL" uninstall "$APP_PACKAGE" > "$ARTIFACTS_DIR/$cycle/uninstall-synthetic-data.txt"
  else
    printf 'synthetic app was already removed by instrumentation cleanup\n' > "$ARTIFACTS_DIR/$cycle/uninstall-synthetic-data.txt"
  fi
  installed_by_run=0
}

run_cycle migrated-host startupStagesLegacyDataAndLeavesOriginalFilesUntouched "$RUN_ID-host" none
remove_synthetic_install migrated-host
run_cycle malformed-source malformedEncryptedPreferencesAppearInPackagedWebView "$RUN_ID-malformed" encrypted-preferences
remove_synthetic_install malformed-source
run_cycle malformed-key malformedPrivateKeyAppearsInPackagedWebViewAndLeavesSourceUntouched "$RUN_ID-key" private-key
remove_synthetic_install malformed-key
# One exact result contract for all three cycles: JUnit identity/count, no
# skips, unchanged-source hash reports, migrated-key Docker auth, provenance.
"$ROOT_DIR/scripts/check-js-signed-upgrade-results.py" --artifacts-dir "$ARTIFACTS_DIR"
printf 'PASS: signed v0.5.6 upgrade, migrated-key Docker authentication, and unchanged-source evidence: %s\n' "$ARTIFACTS_DIR"
