#!/usr/bin/env python3
"""Exercise the Usage/Ports runner's stale-output cleanup and failure capture."""

from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / "scripts/lib/connected-js-usage-ports-artifacts.sh"
RUNNER = ROOT / "scripts/connected-js-usage-ports.sh"
JOURNEY = ROOT / "android/app/src/androidTest/java/com/pocketshell/app/smoke/UsagePortsDockerJourneyTest.java"


def check_failure_diagnostic_contract() -> None:
    runner = RUNNER.read_text(encoding="utf-8")
    journey = JOURNEY.read_text(encoding="utf-8")
    failure_branch = runner.split("\nelse\n", 1)[1].split("\nfi\n", 1)[0]
    required_journey_fragments = (
        "captureFailureDiagnostics(failure)",
        "awaitJsTrue(\"!!document.querySelector('[data-testid=ports-screen]')\")",
        "scanReadiness",
        "route:shell?.dataset.route",
        "ssh:{phase:shell?.dataset.sshPhase",
        "rows:Array.from(document.querySelectorAll('[data-testid=port-row]'))",
        "jsErrors:window.__usagePortsJourneyErrors??[]",
        "captureFailureScreenshot(artifactDirectory)",
        'new File(artifactDirectory, "journey-summary.json")',
    )
    missing_journey = [fragment for fragment in required_journey_fragments if fragment not in journey]
    if missing_journey:
        raise AssertionError("journey failure diagnostics are missing: " + ", ".join(missing_journey))
    if "chromium Chromium" not in runner:
        raise AssertionError("the Usage/Ports runner does not capture Chromium logcat")
    if "extract-js-lifecycle-artifacts.py" not in failure_branch:
        raise AssertionError("the runner does not extract packaged failure artifacts before teardown evidence is lost")


def main() -> int:
    check_failure_diagnostic_contract()
    with tempfile.TemporaryDirectory(prefix="pocketshell-usage-ports-failure-") as raw_temp:
        temp = Path(raw_temp)
        results = temp / "results"
        reports = temp / "reports"
        artifacts = temp / "artifacts"
        status_path = temp / "observed-exit-code"
        fake_gradle = temp / "fake-gradle"

        (results / "stale").mkdir(parents=True)
        (results / "stale/old.xml").write_text("stale junit", encoding="utf-8")
        (reports / "stale").mkdir(parents=True)
        (reports / "stale/index.html").write_text("stale report", encoding="utf-8")
        artifacts.mkdir()
        fake_gradle.write_text(
            "#!/usr/bin/env python3\n"
            "import os\n"
            "from pathlib import Path\n"
            "Path(os.environ['RESULTS_DIR']).mkdir(parents=True, exist_ok=True)\n"
            "Path(os.environ['RESULTS_DIR'], 'partial.xml').write_text('partial junit')\n"
            "Path(os.environ['REPORTS_DIR']).mkdir(parents=True, exist_ok=True)\n"
            "Path(os.environ['REPORTS_DIR'], 'index.html').write_text('failure report')\n"
            "print('simulated instrumentation failure')\n"
            "raise SystemExit(23)\n",
            encoding="utf-8",
        )
        fake_gradle.chmod(0o755)

        env = os.environ.copy()
        env.update(
            HELPER=str(HELPER),
            RESULTS_DIR=str(results),
            REPORTS_DIR=str(reports),
            ARTIFACTS_DIR=str(artifacts),
            FAKE_GRADLE=str(fake_gradle),
            STATUS_PATH=str(status_path),
        )
        script = r"""
set -euo pipefail
source "$HELPER"
pocketshell_reset_connected_js_usage_ports_outputs "$RESULTS_DIR" "$REPORTS_DIR"
[[ ! -e "$RESULTS_DIR/stale/old.xml" ]]
[[ ! -e "$REPORTS_DIR/stale/index.html" ]]
if pocketshell_run_connected_js_usage_ports_gradle \
    "$RESULTS_DIR" "$REPORTS_DIR" "$ARTIFACTS_DIR" "$FAKE_GRADLE"; then
  printf 'expected fake Gradle to fail\n' >&2
  exit 1
else
  status=$?
fi
printf '%s' "$status" > "$STATUS_PATH"
"""
        completed = subprocess.run(
            ["bash", "-c", script],
            check=False,
            capture_output=True,
            text=True,
            env=env,
        )
        if completed.returncode != 0:
            raise AssertionError(
                f"runner helper failed ({completed.returncode})\n"
                f"stdout:\n{completed.stdout}\nstderr:\n{completed.stderr}"
            )
        if status_path.read_text(encoding="utf-8") != "23":
            raise AssertionError("the helper did not return Gradle's original nonzero status")
        if (artifacts / "instrumentation-results/partial.xml").read_text(encoding="utf-8") != "partial junit":
            raise AssertionError("partial JUnit output was not preserved")
        if (artifacts / "failure-diagnostics/androidTest-report/index.html").read_text(encoding="utf-8") != "failure report":
            raise AssertionError("the Android HTML report was not preserved")
        if "simulated instrumentation failure" not in (
            artifacts / "gradle-connected.log"
        ).read_text(encoding="utf-8"):
            raise AssertionError("the Gradle transcript was not retained")

        print("PASS: stale JUnit and HTML outputs are removed before the run")
        print("PASS: partial JUnit and HTML report are preserved after Gradle failure")
        print("PASS: the original Gradle exit code is returned after evidence capture")
        print("PASS: packaged failure route, SSH, Ports, JS error, screenshot, and Chromium diagnostics are wired")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
