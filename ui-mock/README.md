# PocketShell UI Mock — terminal/browser visual loop

**Status: browser-renderer slice plus a standalone `:ui-mock` Android-library state seam. This is NOT yet a runnable mock application or the complete UI extraction requested in issue #2636.**

This tool displays PocketShell's existing real-screen Roborazzi fixtures in a browser and rerenders the selected fixture after source changes. It does not create a second HTML/React implementation of the app. Edit the same Kotlin composables that production uses: there is no visual-code copy-back step.

What it does:

- Discovers literal `@Test fun name() = render("label") { ... }` cases under `app2/src/test/java/com/pocketshell/next/render/`.
- Starts a Python-standard-library HTTP server on **127.0.0.1 only**.
- Pairs every navigation destination from app2's `Destination.all` graph with its render cases, in a browser coverage panel and in `--list` output; destinations without fixtures are listed as explicit GAP rows, never silently counted as complete (acceptance 2). UI-kit examples are counted but never attributed to a destination.
- Runs **one selected render test**, keeps Gradle's daemon and build cache, and never requests APK assembly/install, Docker or an emulator.
- Watches `app2/`, `shared/`, build configuration and resources, excluding generated `build/` trees. Edits are debounced; builds are serialized and superseded results are not published as current.
- Shows fresh images, test-source location, measured duration and bounded Gradle logs. Build failures keep the last image visibly stale; an image for a different selected fixture is hidden. The sidebar's "Покрытие экранов навигации" panel lists each destination with clickable fixture names or a GAP marker.
- Requires both a newly produced successful JUnit report for the selected test and fresh PNG output. A green/no-op Gradle invocation is not accepted as a fresh render.

What it **does not** do:

- It is **not a clickable Android emulator** and not Vite-style hot module replacement. The browser shows PNGs. Select scenarios to inspect states; clicks inside an image do nothing.
- The browser renderer still builds app2's Roborazzi fixtures. Separately, the
  standalone `:ui-mock` Android library owns deterministic mock data and its
  pure reducer without depending on app2, core modules, or Termux. It is a
  compile/test boundary, not yet a runnable mock shell.
- It does not guarantee complete destination/state coverage. Unsupported fixture shapes are reported under catalog warnings, not silently counted as supported. A fixture can intentionally render only a part of a screen.
- It cannot validate real keyboard/IME policy, terminal behavior, Android permission flows or platform file pickers. Keep device/emulator acceptance for these.
- It does not change production code, publish APKs, alter the release workflow, or bypass release gates.

## DevBox: Linux server, Windows/Linux browser client

Use a separate working copy/branch. Do not switch the maintainer's daily-use checkout or interfere with an existing emulator.

The DevBox needs Python **3.10+**, the repository's **JDK 17**, Android SDK, and Gradle-wrapper dependencies. An existing working PocketShell build environment is sufficient; no Android Studio or emulator is needed for this visual mode. First compilation and dependency downloads remain real costs; no latency promise has been benchmarked.

From the repository root on the DevBox:

```bash
python3 ui-mock/serve.py --list
python3 ui-mock/serve.py --port 4173
```

`--list` prints every supported case followed by the destination render-coverage report (`destination render coverage: N/M destinations covered`, one line per destination, `GAP` where no fixture exists). It reads source text only — no Gradle runs. The same inventory is served under the `coverage` key of `GET /api/catalog`; if `Destinations.kt` cannot be read or parsed, the panel shows the explicit reason instead of an empty list.

On the maintainer's usual Linux box, the existing resource scope can wrap the **whole session** rather than replacing the per-render incremental build profile:

```bash
scripts/cgroup-run.sh --unit pocketshell-ui-mock -- \
  python3 ui-mock/serve.py --port 4173
```

Use that wrapper only where the project's cgroup/systemd prerequisites already work. On another Linux machine, use the plain Python command above and size Gradle/JVM limits for that machine. The renderer uses one Gradle worker and one test fork.

The server prints a URL similar to:

```text
http://127.0.0.1:4173/#token=RANDOM_SESSION_TOKEN
```

On Windows PowerShell or a Linux client, open an SSH tunnel in another terminal:

```bash
ssh -N -L 4173:127.0.0.1:4173 USER@DEVBOX
```

Replace `USER@DEVBOX` with your existing SSH alias/login. In the client's browser, open the **exact URL printed by the server, including the token fragment**. The Windows client needs only SSH and a browser, not Java, Android SDK, Python or Android Studio.

Keep the server and SSH tunnel running while iterating. Ctrl+C stops each foreground process. This tool does not stop a global Gradle daemon, kill any emulator, touch a production app installation or clear app data.

The token stays in browser session storage after opening the URL. A new server session produces a new token: reopen its printed URL after a server restart. Do not expose port 4173, ADB, or a Gradle service directly to the internet. Do not run other render commands writing the same module's `build/renders` directory concurrently with this viewer.

## Native Windows host (optional)

Running **the build on Windows**, rather than only viewing a Linux DevBox, additionally requires Python 3.10+, JDK 17 and the Android SDK command-line tools. The renderer uses `gradlew.bat` there.

```powershell
# From a working PocketShell checkout whose Android SDK is configured:
py -3 ui-mock/serve.py --list
py -3 ui-mock/serve.py --port 4173
```

Configure `ANDROID_HOME` or `local.properties` as in an ordinary command-line PocketShell build. With `sdk.dir` on Windows, forward slashes avoid Java-properties escaping issues, for example `sdk.dir=C:/Android/sdk`. No SSH tunnel is needed when the browser is on the same Windows machine.

Android toolchain provisioning reference: <https://developer.android.com/tools/sdkmanager>.

## Editing workflow for a coding agent

1. Select a fixture in the browser. The source path shown points to its `*Renders.kt` file; its `render { ... }` body shows the production composable and mock data used.
2. Change the **production composable** for layout/type/color changes. Change the fixture for mock states, long names, empty/loading/error states, or fixture-only data.
3. Save. The watcher invokes the single selected test again. Wait for the browser's **current** render status; a dimmed image is explicitly out of date.
4. Inspect error details in **Build log** if the render fails. Do not turn off freshness checks or make a mirrored HTML/Kotlin copy to get a green result.
5. For a new scenario, add a literal case following the existing convention. The catalog rescans after edits:

```kotlin
@Test
fun composerLongDraft() = render("composer-long-draft") {
    // Call the production composable with a deterministic UI state.
}
```

Use existing fixtures for valid types, callbacks and imports; the snippet illustrates the discoverable declaration format, not a complete test.

`--include-ui-kit` additionally exposes shared UI-kit render examples. These are explicitly labeled: some compose primitives to **mirror** a screen rather than call the actual screen, and are not evidence that the production layout changed. Default mode favors `app2` fixtures for this reason.

```bash
python3 ui-mock/serve.py --include-ui-kit
python3 ui-mock/serve.py --no-watch
python3 ui-mock/serve.py --case CASE_ID_FROM_LIST
```

## Architecture and limits

```text
Browser on Windows/Linux
  └─ SSH-forwarded loopback HTTP + session token
       └─ Python UI Mock server / one debounced render queue
            └─ one app2 Roborazzi test on the DevBox JVM
                 └─ existing fixture data → production Compose screen → PNG
```

`render.init.gradle` opts **only the selected `testDebugUnitTest` task** out of cache/up-to-date reuse. Compilation/resource tasks remain incremental and cacheable. It does not use `--rerun-tasks`, `clean`, `--no-daemon` or `--no-build-cache`.

## Standalone Android module coverage

`:ui-mock` is a `com.android.library` module. Its only project dependencies are
`:shared:ui-kit` and `:shared:ui-screens`; `UiMockDependencyBoundaryTest`
rejects app2, `core-*`, and Termux leakage. The pure `MockAppState`, reducer,
events, deterministic data, and tests moved here from app2; the D18 slice
extended the state machine to all 28 destinations (loading, error, typing and
long-content cases where each real screen has them) with no new dependencies.
Remaining app-owned display inputs (`SessionRow`, `WorkspaceMembership`,
terminal/session state, the session tree, the file viewer, and the
workspace-roots state) are explicit mock-local mirrors. They are
not production implementations and perform no I/O.

The two coverage columns below deliberately measure different things:

- Browser fixture: a real production composable has at least one catalogued
  Roborazzi case. This remains the fast visible loop.
- Interactive state seam: `MockDestination` and the pure reducer can represent
  navigation/state for that destination. It does not claim the screen is
  runnable until a mock shell is added.

| Production destination (28) | Browser fixture | Interactive state seam | Remaining work |
|---|---:|---:|---|
| Hosts | yes | yes | runnable shell wiring |
| Workspaces | yes | yes | replace the mock workspace mirror after the app2-side extraction (no shared type yet) |
| Workspace | yes | yes | shared SessionTreeUiState extraction and shell wiring |
| Session | yes | yes | shared terminal display seam and shell wiring |
| Files | yes | yes | shell wiring (state projects the shared FileExplorerDisplayState) |
| FileViewer | yes | yes | shell wiring (mock-local viewer mirror until app2's ViewerUiState is shared) |
| Ports | yes | yes | runnable shell wiring |
| Settings | yes | yes | runnable shell wiring |
| TerminalSettings | yes | yes | shell wiring (state carries the shared AppSettings) |
| VoiceSettings | yes | yes | shell wiring (state carries the shared AppSettings) |
| ConnectionSettings | yes | yes | shell wiring (state carries the shared AppSettings) |
| AdvancedSettings | yes | yes | shell wiring (state carries the shared AppSettings) |
| AccountSync | yes | yes | shell wiring (state carries the shared AccountSyncUiState) |
| Diagnostics | yes | yes | shell wiring (state carries the shared crash load state) |
| DiagnosticReport | GAP | yes | fixture plus shell wiring |
| About | GAP | yes | fixture plus shell wiring (shared build/update-check state) |
| Update | GAP | yes | fixture plus shell wiring (shared update-check state) |
| Usage | yes | yes | runnable shell wiring |
| HostUsage | yes (same Usage screen) | yes | runnable shell wiring |
| TunnelDetail | GAP | yes | fixture plus shell wiring |
| AddTunnel | GAP | yes | fixture plus shell wiring |
| HostForm | yes | yes | runnable shell wiring |
| SshKeys | yes | yes | runnable shell wiring (mock mirror replaced by the shared SshKeysUiState) |
| WorkspaceRoots | GAP | yes | fixture plus shell wiring |
| AddWorkspaceRoot | GAP | yes | fixture plus shell wiring |
| WorkspaceStart | GAP | yes | fixture, shared display state, shell wiring |
| ReorderWorkspaces | GAP | yes | fixture plus shell wiring |
| WorkspaceRootAction | GAP | yes | fixture plus shell wiring |

Current totals: **18/28 browser-covered**, **28/28 represented by the interactive
state seam** (closed by the D18 slice), and **0/28 claimed runnable in a
standalone mock app**. The ten fixture gaps and the runnable-shell gap are
explicit by design; the state seam carrying a destination still does not claim
the screen is runnable.

Mirror-vs-shared note: where a destination's display type already lives in
`:shared:ui-screens`, the state carries or projects THAT type — AppSettings
(D6), the update-check state (D3), AccountSyncUiState (D7), the crash load
state and report rows (D12), SshKeysUiState (D13, which replaced and deleted
the former `MockSshKeysUiState` mirror), FileExplorerDisplayState (D14), and
the usage/ports/hosts display types (D1/D11). The remaining mock-local mirrors
are exactly the families still app-side: the session tree (`SessionTreeUiState`),
the file viewer (`ViewerUiState`), the workspace-roots manager, the terminal
session stand-in, and the host workspaces list.

The existing `App.kt` has a Robolectric guard for its eager production side effects. This viewer relies on the existing fixture/test harness; it is not a proof that the complete production dependency graph or every initializer is absent. Full runtime/dependency isolation is the separate extraction task in [AGENT-HANDOFF.md](AGENT-HANDOFF.md).

## Validation

Run the server's local tests (no Android required):

```bash
python3 -m unittest discover -s ui-mock/tests -v
node --check ui-mock/web/app.js
```

The tests cover catalog discovery, unsupported cases, the destination coverage inventory (real-graph parsing, explicit gaps, degraded mode), serialized/superseded builds, stale output rejection, successful selected-test report requirements, error recovery, bounded image validation, render locks, path/command allowlists, session-token checks, Origin checks and DNS-rebinding-style Host rejection.

**Still required on the DevBox before calling the loop verified:** execute a real fixture; edit a composable and observe a fresh PNG; introduce a compile error and observe explicit failure/staleness; restore the source and observe recovery; open the browser through a Windows SSH tunnel. Python tests use fake Gradle output and do not prove Android rendering or native Windows process behavior.
