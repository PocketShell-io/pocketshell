#!/usr/bin/env bash
# Run the native Android JVM unit tests and fail closed unless every required
# class actually executed with at least one passing test and no skips.
#
# A green Gradle task alone is not evidence (zero-test and filtered runs are
# green too), so the exact class inventory below is re-checked from the JUnit
# XML this run wrote.
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd -- "$ROOT_DIR"

REQUIRED_CLASSES=(
  com.pocketshell.app.CredentialHandleVaultTest
  com.pocketshell.app.DurableKeyValueStoreTest
  com.pocketshell.app.EncryptedSyncTokenStoreTest
  com.pocketshell.app.GatewayOriginAllowlistTest
  com.pocketshell.app.GatewayPairingPluginContractTest
  com.pocketshell.app.GatewayPairingStoreTest
  com.pocketshell.app.GatewayTargetPolicyTest
  com.pocketshell.app.GatewayTokenBrokerTest
  com.pocketshell.app.GatewayTunnelTest
  com.pocketshell.app.GatewayTunnelTlsTest
  com.pocketshell.app.GoogleSyncSessionGatewayExchangeTest
  com.pocketshell.app.GoogleSyncSessionTest
  com.pocketshell.app.KeyHandleConnectFailuresTest
  com.pocketshell.app.SpeechAudioPresenceTest
  com.pocketshell.app.SpeechRecognitionOptionsTest
  com.pocketshell.app.SpeechRecognitionPluginTest
  com.pocketshell.app.SshCapabilityPluginChannelRequestTest
  com.pocketshell.app.SshCapabilityPluginCloseTest
  com.pocketshell.app.SshCapabilityPluginConnectDeadlineTest
  com.pocketshell.app.SshCapabilityPluginGatewayAccountTest
  com.pocketshell.app.SshCapabilityPluginGatewayClockTest
  com.pocketshell.app.SshCapabilityPluginGatewayCloseCodeTest
  com.pocketshell.app.SshCapabilityPluginGatewayPinTest
  com.pocketshell.app.SshCapabilityPluginGatewayRefusalTest
  com.pocketshell.app.SshCapabilityPluginGatewayTokenTest
)
RESULTS_DIR="$ROOT_DIR/android/app/build/test-results/testDebugUnitTest"

source scripts/lib/gradle-output-lock.sh
pocketshell_acquire_gradle_output_lock "$ROOT_DIR/android" '' 'native Android unit gate'

rm -rf -- android/app/build/test-results/testDebugUnitTest
android/gradlew -p "$ROOT_DIR/android" :app:testDebugUnitTest --stacktrace --console=plain

python3 - "$RESULTS_DIR" "${REQUIRED_CLASSES[@]}" <<'PY'
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

results, required = Path(sys.argv[1]), sys.argv[2:]
suites = {}
for report in sorted(results.glob("TEST-*.xml")):
    root = ET.parse(report).getroot()
    for suite in [root] if root.tag == "testsuite" else root.findall("testsuite"):
        suites[suite.get("name")] = suite
failed = []
total = 0
for name in required:
    suite = suites.get(name)
    if suite is None:
        failed.append(f"{name}: no JUnit report (class did not run)")
        continue
    tests = int(suite.get("tests", "0"))
    bad = {key: int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")}
    if tests < 1 or any(bad.values()):
        failed.append(f"{name}: tests={tests} {bad}")
    total += tests
    print(f"native unit {name}: {tests} passed")
if failed:
    print("FAIL: native Android unit gate:\n  " + "\n  ".join(failed), file=sys.stderr)
    raise SystemExit(1)
print(f"PASS: native Android unit gate executed {total} tests across {len(required)} required classes")
PY
