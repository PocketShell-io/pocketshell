#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
WORKFLOW="$ROOT_DIR/.github/workflows/js-first-rewrite.yml"
RUNNER="$ROOT_DIR/scripts/connected-js-composer-docker.sh"
EXTRACTOR="$ROOT_DIR/scripts/extract-js-composer-artifacts.py"

[[ -f "$WORKFLOW" && -x "$RUNNER" && -f "$EXTRACTOR" ]] || {
  printf 'FAIL: rewrite composer gate inputs are missing\n' >&2
  exit 1
}

bash -n "$RUNNER"
python3 - "$WORKFLOW" "$RUNNER" "$EXTRACTOR" <<'PY'
import ast
import re
import subprocess
import sys
from pathlib import Path

workflow_path, runner_path, extractor_path = map(Path, sys.argv[1:])
workflow = workflow_path.read_text()
runner = runner_path.read_text()
ast.parse(extractor_path.read_text(), filename=str(extractor_path))


def require_contract(source: str) -> None:
    required = (
        ("isolated fixture", "scripts/agents-pool.sh up 2245"),
        ("composer runner", "scripts/connected-js-composer-docker.sh --suffix i2891ci --port 2245"),
        ("exact result guard", "scripts/check-js-composer-journey-results.py"),
        ("run-scoped artifact output", "android/app/build/outputs/js-composer/"),
        ("always-run artifact upload", "name: Upload packaged JS composer run evidence"),
        ("artifact uploader", "uses: actions/upload-artifact@v6"),
        ("JUnit upload", "android/app/build/outputs/androidTest-results/connected/debug/TEST-*.xml"),
        ("runtime preflight step", "- name: Capture and verify emulator JS runtime"),
        ("captured node path", 'node_path="$(command -v node)"'),
        ("captured pnpm path", 'pnpm_path="$(command -v pnpm)"'),
        ("emulator PATH forwarding", "PATH: ${{ steps.emulator-js-runtime.outputs.path }}"),
        ("emulator pnpm forwarding", "PNPM: ${{ steps.emulator-js-runtime.outputs.pnpm }}"),
    )
    for label, needle in required:
        if needle not in source:
            raise AssertionError(f"workflow is missing {label}: {needle}")
    composer = source.index("scripts/connected-js-composer-docker.sh --suffix i2891ci --port 2245")
    lifecycle = source.index("scripts/connected-js-lifecycle.sh --suffix i2855ci --port 2222")
    if lifecycle >= composer:
        raise AssertionError("composer journey must run after the smoke/lifecycle portion of the API 35 script")
    guard_start = source.index("- name: Assert the packaged JS composer journey executed exactly once")
    guard_end = source.index("- name:", guard_start + 8)
    guard = source[guard_start:guard_end]
    if "if: always()" not in guard or "--results-dir android/app/build/outputs/androidTest-results/connected/debug" not in guard:
        raise AssertionError("the exact JUnit result guard must run after the emulator step even when it fails")
    upload_start = source.index("- name: Upload packaged JS composer run evidence")
    upload_end = source.index("- name:", upload_start + 8)
    upload = source[upload_start:upload_end]
    if "if: always()" not in upload or "if-no-files-found: error" not in upload:
        raise AssertionError("composer evidence upload must run always and fail if the bundle is absent")
    if "android/app/build/outputs/js-composer/" not in upload:
        raise AssertionError("composer artifact upload omits the run-scoped evidence directory")
    if "${{ github.run_id }}-${{ github.run_attempt }}" not in upload:
        raise AssertionError("composer artifact name must identify its workflow run and attempt")


require_contract(workflow)

runtime_step = workflow.index("- name: Capture and verify emulator JS runtime")
fixture_step = workflow.index("- name: Start version-matched Docker agents fixture")
emulator_step = workflow.index("uses: reactivecircus/android-emulator-runner@")
if not runtime_step < fixture_step < emulator_step:
    raise AssertionError("the Node/pnpm runtime preflight must fail before Docker fixture and emulator work")

runtime_section = workflow[runtime_step:fixture_step]
runtime_lines = runtime_section.splitlines()
runtime_run_index = next(
    index for index, line in enumerate(runtime_lines)
    if re.match(r"\s+run:\s*\|\s*$", line)
)
runtime_run_indent = len(runtime_lines[runtime_run_index]) - len(runtime_lines[runtime_run_index].lstrip())
runtime_script_lines = []
for line in runtime_lines[runtime_run_index + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= runtime_run_indent:
        break
    runtime_script_lines.append(
        line[runtime_run_indent + 2:]
        if line.startswith(" " * (runtime_run_indent + 2)) else ""
    )
subprocess.run(
    ["bash", "-n"],
    input="\n".join(runtime_script_lines),
    text=True,
    check=True,
)

for needle in (
    '[[ "$node_path" == /* && -x "$node_path" ]] ||',
    '[[ "$pnpm_path" == /* && -x "$pnpm_path" ]] ||',
    'printf \'path=%s\\n\' "$PATH" >> "$GITHUB_OUTPUT"',
    'printf \'pnpm=%s\\n\' "$pnpm_path" >> "$GITHUB_OUTPUT"',
):
    if needle not in workflow:
        raise AssertionError(f"emulator JavaScript runtime preflight is missing: {needle}")

for label, damaged in (
    ("emulator PATH forwarding", workflow.replace(
        "          PATH: ${{ steps.emulator-js-runtime.outputs.path }}\n",
        "",
        1,
    )),
    ("early runtime preflight", workflow.replace(
        "- name: Capture and verify emulator JS runtime",
        "- name: Capture runtime without verification",
        1,
    )),
):
    try:
        require_contract(damaged)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed removed {label}")

for label, damaged in (
    ("composer invocation", workflow.replace(
        '            scripts/connected-js-composer-docker.sh --suffix i2891ci --port 2245 --session-prefix "js2891-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}"\n',
        "",
        1,
    )),
    ("composer artifact path", workflow.replace(
        "            android/app/build/outputs/js-composer/\n",
        "            android/app/build/outputs/other/\n",
        1,
    )),
):
    try:
        require_contract(damaged)
    except (AssertionError, ValueError):
        print(f"PASS: missing {label} fails the rewrite composer workflow contract")
    else:
        raise AssertionError(f"workflow contract missed a removed {label}")

# The emulator action executes this embedded block as Bash. Parse it here so
# quoting/continuations fail in the cheap contract job before an AVD is used.
section_start = workflow.index("- name: Run packaged JS smoke suite on API 35")
section_end = workflow.index("\n      - name:", section_start + 8)
section = workflow[section_start:section_end]
lines = section.splitlines()
script_index = next(index for index, line in enumerate(lines) if re.match(r"\s+script:\s*\|\s*$", line))
script_indent = len(lines[script_index]) - len(lines[script_index].lstrip())
script_lines = []
for line in lines[script_index + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= script_indent:
        break
    script_lines.append(line[script_indent + 2:] if line.startswith(" " * (script_indent + 2)) else "")
subprocess.run(["bash", "-n"], input="\n".join(script_lines), text=True, check=True)

if "android/app/build/outputs/js-composer/$ARTIFACT_RUN_ID" not in runner:
    raise AssertionError("composer runner evidence is not stored in run-scoped Android build outputs")
if "${TMPDIR:-/tmp}/pocketshell-js2857-" in runner:
    raise AssertionError("composer artifacts still default to ephemeral /tmp storage")
if 'check-js-composer-journey-results.py" --results-dir "$RESULTS_DIR"' not in runner:
    raise AssertionError("composer runner lost its exact same-run JUnit guard")
if "composer-host-oracle.txt" not in runner:
    raise AssertionError("host-side composer acceptance evidence is not staged for upload")
if "trap finish_composer_run EXIT" not in runner or '"$RESULTS_DIR"/TEST-*.xml' not in runner:
    raise AssertionError("composer runner does not preserve JUnit and exit status in its run bundle")
if "--preserve-on-failure" not in runner or '--output-dir "$evidence_dir"' not in runner:
    raise AssertionError("composer runner does not extract contemporaneous failure artifacts into its run bundle")
if 'tee "$evidence_dir/composer-gradle.log"' not in runner:
    raise AssertionError("composer runner does not retain its packaged Gradle output")

print("PASS: rewrite composer CI invokes the isolated API 35 journey after lifecycle, validates exact JUnit, and uploads run-scoped evidence")
print("PASS: composer runner and embedded emulator workflow scripts parse successfully")
PY
