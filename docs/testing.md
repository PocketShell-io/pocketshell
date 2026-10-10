# Testing and QA

Since #2934, `main` is the JS-first 0.6.0 development line: the product is
the Vue/Capacitor app. Run its checks with `scripts/run-js-unit-gate.sh`,
`pnpm typecheck`, `scripts/assemble-debug.sh`, the JS-first connected lanes
below, and `scripts/test-agents-fixture-aplexer.sh --docker`. The later app2
journey inventory ("Legacy app2 ..." sections) describes the Kotlin contract
that now lives only on `release/0.5.x`. `main` is not a complete 0.6.0
release gate: its full feature suite and D36/D37 verdicts are #2863's work,
and it currently has no scheduled run. The CI boundary is documented in
[js-first-rewrite-foundation.md](js-first-rewrite-foundation.md).

PocketShell has two end-to-end surfaces:

1. the Android emulator, which runs the app and validates visible UI behavior;
2. disposable Docker SSH hosts, which provide the host CLI, aplexer, agent
   fixtures, and fault-injection targets.

The app and host fixture must be tested together for session work. A test that
only finds a canned row or checks an internal ViewModel state is not evidence
that attach, input, or stop works for a user.

## Fast local checks

Run these from the repository root on `main`, after
`git submodule update --init --recursive` and `pnpm install --frozen-lockfile`:

```bash
scripts/run-js-unit-gate.sh
pnpm typecheck
scripts/assemble-debug.sh
git diff --check
```

`scripts/run-js-unit-gate.sh` runs the complete Vitest suite and fails unless
every registered test file and title ran (`pnpm test:unit` is the unchecked
quick loop). `assemble-debug.sh` builds the JS app,
syncs Capacitor, and assembles a debug APK; it does not build connected tests.

For UI iteration without Android, `pnpm dev:mock` / `pnpm dev:live` run the
app in a desktop browser with HMR ([browser-dev-mode.md](browser-dev-mode.md)).
Their headless smokes are part of the JS-first CI job:

```bash
node scripts/dev-browser-smoke.mjs mock
node scripts/dev-browser-smoke.mjs live --host testuser@127.0.0.1:2222=fixture --identity tests/docker/test_key
python3 scripts/check-no-dev-shims.py --dist dist --apk android/app/build/outputs/apk/debug/app-debug.apk
```

The last command proves the dev shims are absent from the built APK. Browser
runs are fast evidence only; the packaged emulator lanes below stay the gate.

The remaining legacy app2 Gradle commands and journey inventory describe
`release/0.5.x`. They are not available on `main`, where the Kotlin product
modules and root Gradle graph were removed.

## JS-first packaged Android lanes

The JS-first `connected-test.sh` requires a lane name and an explicit package
suffix. It dispatches only to the existing `android/` packaged runners; it does
not accept raw Gradle tasks or the app2 module selectors of `release/0.5.x`.

```bash
scripts/connected-test.sh smoke --suffix i2863
docker compose -f tests/docker/docker-compose.yml up -d --build agents
scripts/connected-test.sh lifecycle --suffix i2863 --port 2222 \
  --container pocketshell-test-agents --run-id js2863-local
scripts/agents-pool.sh up 2245
scripts/connected-test.sh composer-docker --suffix i2863 --port 2245 \
  --session-prefix js2863-local
scripts/connected-test.sh durable-storage --suffix i2863 --run-id js2993-local
```

The smoke lane requires exactly the six registered packaged-shell JUnit
methods. The lifecycle lane requires its one registered JUnit method, then
checks run-scoped screenshots, session rows, PTY state, and grace/reconnect
evidence against the Docker host. The composer lane requires its one registered
method and checks UTF-8 command bytes, bracketed multiline bytes, Insert without
execution, and no execution after an uncertain write against the Docker host.
It also captures the keyboard-up screenshot and computed viewport bounds from
live logcat, verifies their SHA-256 values, and saves them under `/tmp` so
Gradle's suffixed-app cleanup does not remove them.
The durable-storage lane (issue #2993) needs no Docker fixture. It runs
`DurableStorageRestartJourneyTest` three times with a force-stop between each
run. `seed` creates two command chips, a theme and raw localStorage keys, then
waits for them to settle. `mutate` changes the theme, deletes a chip, updates
and removes the raw keys, logs an acknowledgement and SIGKILLs its own process
`--kill-delay-ms` (default 0) later. `verify` requires every mutation after
restart. Because `mutate` ends in its own kill, it has no JUnit pass; the
runner accepts it only when it ends with `Process crashed` after the logged
ACK and KILL lines. `check-js-durable-storage-results.py --run-dir` then
requires passing `seed` and `verify` reports and an ACK-to-KILL gap under one
second. Without the native store behind localStorage, the deleted chip comes
back after the restart. The lane builds its own suffix into the shared
`app-debug.apk` output, so CI runs it before the composer and key-vault builds,
never between key-vault and the signed upgrade.
Each connected phase owns the Android output tree and selected emulator,
removes stale JUnit XML before instrumentation, and checks the report from that
run. Provide a new suffix for each worktree so parallel APK installs have
distinct package IDs.

The J1 dispatch guard is `scripts/check-test-validity.sh --j1-only`. On this
rewrite tree it verifies the eight packaged contracts: smoke selects the exact
nine methods in `JsShellPackagedSmokeTest`; lifecycle selects
`SshPtyDockerJourneyTest#sshSessionSwitchingGraceAndAbruptServerDropReconnectAgainstDockerFixture`;
Usage and Ports selects
`UsagePortsDockerJourneyTest#usageAndPortForwardingPoliciesUseDockerAndNativePlugin`;
Files selects
`J10FilesBrowseEditJourneyTest#browseEditConflictAndTransferFilesWithinTheConfiguredRoot`;
and composer selects
`JsComposerDockerJourneyTest#composerWritesUtf8AndMultilineInsertAndRetainsAfterDrop`.
The durable-storage lane selects
`DurableStorageRestartJourneyTest#userDataWritesSurviveForceStopShortlyAfterAcknowledgement`.
The key vault lane selects
`SshKeyVaultDockerJourneyTest#importsEncryptedDocumentConnectsAndKeepsSecretsOutOfWebViewState`.
It imports an encrypted document URI, generates a second key, and authenticates
both against Docker. It checks accepted public-key fingerprints and sessions
independently, exercises referenced-key deletion confirmation, and inspects
the actual Diagnostics export. It saves timings and the actual key list,
host form, and resource status screenshots under
`android/app/build/outputs/js-key-vault/<run-id>/device-screenshots` for
maintainer visual sign-off. Run it with
`scripts/agents-pool.sh up 2244`, then run
`scripts/connected-test.sh key-vault-docker --suffix i2926 --port 2244 --container pocketshell-test-agents-2244 --run-id js2926-local`.
The account-sync lane (issue #3020) needs no Docker fixture. It selects
`AccountSyncJourneyTest`, which installs a fake Google sign-in and an
in-process fake sync API through `GoogleSyncEnvironment` before launch, then
signs in, reads a desktop-written envelope, syncs through a mid-sync
conflict, picks a synced host on the home screen and signs out. Its checker
requires both methods, seven distinct screenshots and a same-run logcat with
the journey's EVIDENCE line and no ID token. Run it with
`scripts/connected-js-account-sync.sh --suffix i3020 --run-id js3020-local`.
The gateway-docker lane (issue #3086) dials a host registered on the REAL
PocketShell gateway end to end: its own Docker project (gateway, TLS front,
enrolled pocketshell-link agent, agents-image host with no published port) on
TLS port 3287 via `adb reverse`, a per-run test CA trusted only by its
`gwlane`-suffixed debug build, and a host-side controller that is the oracle.
It needs the private gateway checkout at the pinned commit:
`scripts/connected-test.sh gateway-docker --suffix i3086gwlane --gateway-src ~/git/pocketshell-gateway`
(CI checks it out with a fine-grained read-only Contents token for that one repository, secret `POCKETSHELL_GATEWAY_READ_TOKEN`). Details and the TLS
design: [gateway-android-transport.md](gateway-android-transport.md#emulator-lane-gateway-docker).
The signed-upgrade lane runs `InstalledDataMigrationJourneyTest` last in the
blocking packaged CI run (`scripts/ci-js-first-packaged-lanes.sh`), after the
suffixed lanes, because it owns the unsuffixed `com.pocketshell.app` install.
Its fixture is the published v0.5.6 debug APK, downloaded and pinned by
SHA-256; it is signed with the same committed debug keystore as the candidate.
Run it locally with
`scripts/connected-js-key-vault-signed-upgrade.sh --port 2244 --container pocketshell-test-agents-2244 --run-id upgrade2926-local`
on an API 35 emulator without an existing `com.pocketshell.app` install.
`LEGACY_APK` only moves the download cache; the pinned hash still applies.
The runner checks matching certificates, installs 0.5.6 with synthetic private
data, then updates it in place. Three exact-method cycles verify migrated-key
Docker authentication, malformed encrypted preferences, and a malformed
private key. Source hashes must remain unchanged in all three cycles. Evidence
lives under `android/app/build/outputs/js-key-vault-upgrade/<run-id>`; existing
run directories are never overwritten. `scripts/check-js-signed-upgrade-results.py`
checks the whole run: 3/3 exact tests with no skips, unchanged sources,
migrated-key authentication, and APK provenance. The workflow runs that check
again after the emulator step. Missing fixture arguments fail the test instead
of skipping it. The J1 guard rejects removing the invocation, dropping a cycle,
or restoring an opt-in exclusion marker. It also rejects any other undispatched
`*SmokeTest`, `*JourneyTest`, `*DockerTest`, or `*E2eTest` source.

Run its synthetic contract checks with
`scripts/check-test-validity.sh --j1-only --self-test`. This verifies the JS
selectors and exact result-checker method sets, rejects missing/extra dispatch
and unjustified journey classes, and retains a synthetic `release/0.5.x` app2 whole-suite
regression case. The Files result contract is also self-tested by
`scripts/check-js-files-results.py --self-test`. In hosted CI, an
`if: always()` report step independently runs each lane's exact JUnit checker
against that run's preserved smoke, lifecycle, Files, Usage/Ports, or Composer
results. This catches an omitted or dormant runner even if the packaged-lanes
wrapper reports a zero status for it; in particular, Files must leave its
exact report under `js-files/<run-id>/instrumentation-results`. J1 only proves
dispatch of the existing packaged tests; it does not qualify the separate
24-class feature inventory or complete the 0.6.0 release gates.

For a session-runtime change, also run the focused fixture contract checks:

```bash
scripts/test-agents-fixture-aplexer.sh
scripts/test-agents-fixture-aplexer.sh --docker
```

The first command statically checks the image, shims, and journey sources. The
Docker mode builds the image, runs the bundled-aplexer lifecycle self-check, and
probes create → list → attach → kill against an actual container.

### Android input preflight (#2946)

A system-app ANR dialog (Pixel Launcher in every recorded hosted failure) owns
input focus on a starved emulator, and every injected key and tap goes to it.
Every packaged lane runner calls `pocketshell_android_input_preflight`
(`scripts/lib/android-input-preflight.sh`) before the device is used:

- It disables every HOME provider for the lane (`pm disable-user` plus
  `am force-stop`) and re-queries until the set stays empty, because a setup
  app (`com.google.android.googlesdksetup` on the hosted image) can start
  answering HOME only after the launcher is gone. Android's own Settings
  `FallbackHome` is HOME meanwhile. Each package is written to
  `avd-lock-<serial>.disabled-launchers` next to the AVD lock before it is
  disabled. `pocketshell_release_all` re-enables exactly those packages on
  every lane exit and drops a record entry only after `pm enable` succeeded.
  A lane killed with SIGKILL leaves the record, so the next lane's preflight
  and the emulator start path re-enable it first and log
  `RECOVERED_STALE_DISABLED_LAUNCHER`. `start-local-avd.sh` checks
  `ANDROID_SERIAL`, or every booted emulator when it is unset; it and
  `avd-pool.sh start` skip only a serial whose AVD lock another process holds
  (`STALE_LAUNCHER_RECOVERY` lines record each decision).
- Before that it sets `device_provisioned=1` and `user_setup_complete=1`,
  because the hosted SDK setup app can still be the provisioning HOME right
  after boot, and SystemUI keeps the notification shade locked until setup
  is complete.
- Where the launcher draws the navigation bar (the hosted Pixel image and the
  local Launcher3 image), the bottom inset is 0 while it is disabled. The
  smoke test `safeAreaBottomInsetBridgeCarriesANonZeroInset` logs the natural
  insets, enables Android's emulated bottom display cutout
  (`com.android.internal.display.cutout.emulation.double`), and requires the
  KeyboardInsets plugin's own `getState().safeBottomDp` to equal the native
  bottom inset, with `--safe-area-inset-bottom` holding it for 10 consecutive
  samples (Capacitor SystemBars also writes that CSS variable, so the CSS
  value alone is not proof), then the same on the way back down. The other safe-area checks run on the
  device's own insets.
- It sets `hide_error_dialogs=1` (best effort: hosted run 36792962871 showed a
  launcher ANR dialog despite it) and force-stops the owner of any other
  "isn't responding" or crash dialog already on screen.
- A dialog owned by a `com.pocketshell*` package is never dismissed: the lane
  fails with `POCKETSHELL_ERROR_DIALOG`, because that is a product ANR or crash.

The lane's `input-preflight.txt` records every action. Inside the tests, the
`AndroidInputGuardRule` JUnit rule repeats the dismissal before each test
method's first injected input, then `AndroidInputDeliveryProbe` injects a no-op
Shift key and fails with `ANDROID_INPUT_INJECTION_NOT_DELIVERED` plus the
system focus owner when the page does not see it. The probe is never retried.
On failure, every packaged runner writes `dumpsys input`/`window`/
`input_method`/`activity`, unfiltered `logcat -b all`, and a screenshot to its
failure diagnostics, checked by `scripts/check-android-input-diagnostics.py`.

## Disk preflight

The canonical local gates check free space before claiming an emulator, Docker
fixture, or Gradle output lock. This keeps an `ENOSPC` capacity problem distinct
from a product or test failure (issue #1989).

| Free space on the gate's filesystem | Behaviour |
|---|---|
| below 10 GiB | refuse to start, exit **76**, and print the cleanup command |
| 10–20 GiB | run with a `WARN: disk preflight` line naming the cleanup command |
| above 20 GiB | run silently |

The legacy `release/0.5.x` app2 runner's
`connected-test.sh --cleanup-suffixes` mode is exempt because it builds nothing.
That option is not part of the JS-first runner. Use `scripts/disk-cleanup.sh`
for serialized safe-list cleanup; it defaults to a dry run and `--apply`
performs the bounded cleanup.

Release validation has a larger fixed admission budget:

| Free space on the release-validation filesystem | Behaviour |
|---|---|
| below 24 GiB | refuse the release validation, exit **76**, and print the safe cleanup command |
| 24 GiB or more | reclaim stale copied worktrees, then start normally |

## Legacy app2 Android emulator on release/0.5.x

These commands apply only on `release/0.5.x`, where the legacy app2 runner is
still present. On `main`, use the explicit JS-first lanes above.

The maintained local AVD is `test`. The SDK paths on the maintainer box are:

```text
/home/alexey/Android/Sdk/platform-tools/adb
/home/alexey/Android/Sdk/emulator/emulator
```

If the shell cannot access `/dev/kvm`, start the emulator with
`AVD_HOLD=1 scripts/start-local-avd.sh`. Do not kill an emulator that another
lane already owns.

Build and install the debug APK, then run the connected suite against a running
emulator and the Docker agents fixture:

```bash
scripts/assemble-debug.sh --install
docker compose -f tests/docker/docker-compose.yml up -d --build agents
scripts/connected-test.sh --suffix i2561
```

Always invoke the copy of `connected-test.sh` inside the checkout you want
tested, from that checkout. The wrapper refuses to run when invoked from a
different checkout — a wrong-tree run would silently report green for changes
it does not contain — and prints `testing checkout ...` on every run so the
tree under test is visible in the log (issue #2500).

The legacy app2 connected lane is unfiltered. It runs every app2 journey in
one process so session creation, attach, background grace, and cleanup are
tested in the same state model. A lane that uses a non-default fixture port
passes `-Pandroid.testInstrumentationRunnerArguments.agentsPort=<port>` through
the legacy wrapper or uses the agents-pool workflow described in
[docker-emulator-runbook.md](docker-emulator-runbook.md).

Network-fault journeys under the legacy `connected-test.sh --pool` are
isolated per lane (issue #2128): the claimed agents port derives that lane's
Toxiproxy SSH/API ports and compose project. The default `--no-pool` path keeps
the single fixture ports for local and nightly runs. If a pool lane's proxy is
recreated mid-run, the wrapper fails with a fixture error rather than
reporting an empty session list as an app result.

User-facing changes require reviewer evidence from the real app: capture the
screen with `adb exec-out screencap -p > /tmp/pocketshell-screen.png`, include
the Docker target and command, and report the visible result on the issue.

## Docker targets

All Dockerfiles live in `tests/docker/`.

| Target | Purpose |
|---|---|
| `pocketshell-test:ssh` | Minimal OpenSSH host for transport and port-forward tests |
| `pocketshell-test:agents` | Main emulator fixture with real pinned aplexer and deterministic agent/helper shims |
| `pocketshell-test:agents-old-cli` | Real aplexer host whose wrapper rejects newer connect probes |
| `pocketshell-test:agents-daemon` | Real aplexer host with the durable Python tree registry enabled |
| `pocketshell-test:bootstrap-*` | Setup-detection scenarios for installation and service state |

The `agents` image is based on glibc because the production aplexer release
publishes glibc binaries. Its build derives the pinned aplexer version from
`tests/docker/fixture-pins.txt` (the same file pins the published `pocketshell`
wheel it installs — issue #2643), installs `/usr/bin/a` beside the Python
interpreter and its sibling `/usr/bin/aplexer` worker, and runs
`agents-aplexer-selfcheck.py`. The self-check creates a named session, lists a
live `phase: running` / `alive: true` row, kills it, and verifies that it is
gone. A zero-row or stub implementation fails the image build.

The fixture's `/usr/local/bin/pocketshell` command keeps deterministic answers
for non-session probes. Its `sessions`, engine, and tree paths delegate to
`/usr/local/bin/pocketshell-real`, which runs the repository's real Python
package against the real aplexer binaries. The entrypoint seeds one idle
aplexer session; journeys create their own records and clean them up.

Build and inspect the targets manually:

```bash
docker compose -f tests/docker/docker-compose.yml build agents agents-old-cli agents-daemon
docker compose -f tests/docker/docker-compose.yml up -d agents
ssh -i tests/docker/test_key -p 2222 \
  -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'command -v a && command -v aplexer && pocketshell sessions list --json'
```

Port 2222 belongs to the default `agents` fixture. It is reserved for the
Docker `agents` target and must not be taken over by an unrelated container.
Use `scripts/agents-pool.sh` for isolated ports when parallel lanes are needed.

## Session journeys (legacy app2, `release/0.5.x`)

The load-bearing app2 journeys use real aplexer records:

| Journey | Evidence |
|---|---|
| J02 session tree | Seeds and lists independent live aplexer sessions, then compares the rendered tree with a separate host listing |
| J03 attach and type | Creates a session, attaches through the app, writes a marker, and verifies it through the host |
| J04 create session | Submits the create sheet and verifies the new aplexer row, workspace, and cleanup |
| J14 stop session | Stops the selected record and verifies the row disappears from a fresh aplexer listing |
| J15 terminal scroll | Attaches the alternate-screen fixture and proves a drag does not become a cursor key |
| J20 composer upload progress | Stages three real files through the app's own attach path over a bandwidth-throttled SFTP link and proves the determinate bar is on screen mid-flight with the keyboard up, then gone with no residual track once the host holds all three payloads in full |
| J23 key-bar dictation | Taps the terminal bar mic (J08's scripted recognizer at the `VoiceModule` seam), proves partials and errors stay in the bar's chip while a final transcript lands at the remote cursor on both the rendered viewport and an independent host `a capture`, with stop and error paths |

J05/J06 exercise reconnect and bounded background grace against the same PTY
path. J07/J08 exercise composer and voice input, J23 dictates through the
session key bar into the live PTY, and J12 verifies that the
usage refresh and live session enumeration do not overwrite each other.

Every journey asserts the rendered viewport or an independent host-side oracle.
Internal state is useful diagnostics but does not satisfy the acceptance bar.
No journey is skipped to accommodate a missing session runtime; a fixture
capability failure is an infrastructure failure.

### Fixture errors-file guard

`ERRORS_FILE` (`$HOME/.pocketshell-fixture-session-errors.json`) is shared
fixture-side state across the unfiltered emulator container (#2474): a journey
that writes it without clearing it poisons every later journey while CI stays
green — the failure shape #2586/#2596 guarded against (#2670). The blocking
static guard pairs every `writeFile(ERRORS_FILE, ...)` with an `rm -f` in the
same journey file, counting identifier aliases and literal/template paths
alike (#2596's lesson) while ignoring prose-only mentions (#2586's lesson):

```bash
scripts/check-fixture-errors-file-guard.sh --self-test
scripts/check-fixture-errors-file-guard.sh
```

The self-test pins the measured real-tree selection (J02/J04/J14 in scope;
J02 the only writer), so a new reference forces a conscious baseline update.

## Storage migration coverage

Schema 21 removes `HostEntity.tmuxInstalled`. Migration 20→21 rebuilds the
`hosts` table without that column. Earlier migration code, the exported schemas
for versions 1–20, and old-schema test setup SQL retain the literal column name
because they must read databases produced by older APKs. That SQL is historical
input, not a product feature: current Room entities, DAOs, queries, and schema
21 contain no such field. `AppDatabaseTest` covers both opening old shapes and
the post-migration column list. The upgrade-preservation and pre-release gate
scripts use the same old-schema setup SQL to exercise that data-preserving
boundary; their literals are fixtures for migration history, not executable
session behavior.

The documented product grep excludes only those old migration/schema files and
the vendored `shared/core-terminal/src/main/java/com/termux/**` comments. The
vendored source is kept byte-compatible with upstream. Operational tmux socket
and cgroup code is also excluded from the product grep because it belongs to
the agent runner; its paths are listed in the final audit command below.

## Product grep audit

The blocking static guard runs this audit on every unit-gate invocation:

```bash
scripts/check-product-tmux-absent.sh --self-test
scripts/check-product-tmux-absent.sh
```

On `release/0.5.x` (legacy Kotlin tree), inspect the same product surface
manually after a session-runtime change:

```bash
rg -n -i 'tmux' \
  app2/src/main shared/*/src/main \
  --glob '!shared/core-terminal/src/main/java/com/termux/**' \
  --glob '!shared/core-storage/src/main/java/com/pocketshell/core/storage/AppDatabase.kt' \
  --glob '!shared/core-storage/src/main/java/com/pocketshell/core/storage/LegacyVersionOneMigration.kt'
```

The only permitted hits are the historical Room migration boundary explained
above and upstream vendored comments. Keep the agent-runner socket safeguards
in `AGENTS.md`, `process.md`, `scripts/lib/scope-run.sh`,
`scripts/test-full-jvm-gate-detached.sh`, `scripts/avd-pool.sh`,
`scripts/connected-test.sh`, and [tmux-socket-recovery.md](tmux-socket-recovery.md).

## CI and release evidence

On `main`, `.github/workflows/js-first-rewrite.yml` runs on every push and
pull request. Its job `JS checks and Android debug APK` is the required PR
check (JS unit gate, typecheck, result-guard self-tests, APK identity and
signing, packaged API 35 lanes against Docker), and `Docker agents fixture
contract` exercises the pinned fixture. There is no scheduled workflow on
`main` until #2863 replaces the D36/D37 verdicts. On `release/0.5.x`, the
required unit lanes run the JVM suites plus static guards, and the app2
workflow runs the unfiltered emulator journey lane and the real SSH
integration lanes, but those workflows trigger only for `main`/`stable`
pushes and PRs (see [release.md](release.md#release-05x-hotfixes)). The pre-release confidence gate repeats the APK identity,
Docker fixture, emulator, and release-test ledger checks before a tag.

Release work follows [release.md](release.md). A release note or status report
must name the exact commands and distinguish a green product check from a
fixture, emulator, or host-capability blocker.
