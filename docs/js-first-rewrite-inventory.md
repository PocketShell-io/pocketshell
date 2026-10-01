# JS-first Android rewrite inventory

This is the pre-deletion map for the 0.6.0 rewrite branch. Baseline: `a6c7e8dab52e944d2b0aafb366d05374c1ace412` (`rewrite/js-first-0.6.0`). It records destinations, all 24 app2 Android journey classes, persisted private data, and the intended replacement work. It is an inventory, not a claim that the replacement features exist yet.

## Old Android destinations → replacement work

Every route in `app2/src/main/java/com/pocketshell/next/nav/Destinations.kt` is represented below. Shared TypeScript presentation work lives in `pocketshell-core/packages/ui` (continuing [pocketshell-desktop#3](https://github.com/PocketShell-io/pocketshell-desktop/issues/3)); shell bootstrap and the explicit empty-state screen are this issue, #2855.

The executable old-to-new route map, including the current available, partial, information-only, and planned status for each destination, is maintained in [src/destinationInventory.ts](../src/destinationInventory.ts). Its unit test checks the map against the complete pre-rewrite destination list, including the deprecated Tree and CrashReports aliases.

| Existing destination(s) | Existing surface / behavior | Replacement issue and proof target |
|---|---|---|
| `Hosts`, `HostForm`, `SshKeys` | Saved hosts, add/edit, key import/generation and credentials | #2851 HostCliCore contracts; #2856 SSH/session/reconnect; #2860 installed-data migration. Docker-backed `J01ConnectAndTrustJourney.kt` and `J21HostAddConnectJourney.kt`. |
| `Workspaces`, `Workspace`, `WorkspaceStart`, `WorkspaceRootAction`, `Tree`, `ReorderWorkspaces`, `WorkspaceRoots`, `AddWorkspaceRoot` | Host workspace/root and session lists, grouping/order, root shortcuts, session creation entry | #2851 HostCliCore; #2856 SSH/session/reconnect. Docker-backed `J02SessionTreeListJourney.kt` and `J04CreateSessionJourney.kt`. |
| `Session` | Live terminal, attach/switch/stop, reconnect, keyboard, composer entry points; host-reported agent identity/status in selected-session chrome | #2856 SSH/session/reconnect; #2907 session identity/status using the [agent-awareness contract](agent-awareness.md#js-session-identity-and-status). `J03AttachAndTypeJourney.kt`, `J05ReconnectAfterDropJourney.kt`, `J06BackgroundGraceReturnJourney.kt`, `J14StopSessionJourney.kt`, and `J15TerminalScrollJourney.kt`. |
| `Files`, `FileViewer` | Remote SFTP browser, text/image viewer and editor | #2858 files/SFTP. Docker-backed `J10FilesBrowseEditJourney.kt`. |
| `Ports`, `TunnelDetail`, `AddTunnel` | Port discovery, auto/manual forwarding and service status | #2859 usage/ports. Docker-backed `J13PortForwardOpenJourney.kt`, `J18AutoForwardResumeJourney.kt`, and `J22ForwardServiceFgsJourney.kt`. |
| `Usage`, `HostUsage` | Provider quota panel, global and host-scoped | #2859 usage/ports. Docker-backed `J12UsagePanelJourney.kt`. |
| `Settings`, `TerminalSettings`, `VoiceSettings`, `ConnectionSettings`, `AdvancedSettings` | App, terminal, dictation, lifecycle, and compatibility settings | #2861 settings/diagnostics/lifecycle. Android replacement journey `J24SettingsReachJourney.kt`. |
| `AccountSync` | Google sign-in and encrypted host settings sync | #2852 sync parity; #2861 lifecycle/OAuth handoff. Android replacement journey under #2852. |
| `Diagnostics`, `DiagnosticReport` | On-device diagnostics log and crash-report detail/share | #2861 settings/diagnostics/lifecycle. Android replacement journey `J16SettingsSupportJourney.kt` plus report export assertion. |
| `About`, `Update` | Build identity, release check and Android update handoff | #2861 settings/diagnostics/lifecycle. Android replacement journey under #2861. |

### Settings, diagnostics and About/Update gap list (#2861)

Behavioral reference: `origin/release/0.5.x` `shared/ui-screens/.../settings/*`, `app2/.../settings/SettingsRepository.kt`, `app2/.../crash/*` and `app2/.../release/*`. The maintainer's decisions are to carry everything over with no product cuts, and to have Android run the shared app from `pocketshell-core/packages/ui` (#2936) with no Android-only screens.

Shared settings, diagnostics and About therefore live in pocketshell-core, on branch `issue-2861-shared-settings`:

- `packages/ui` `SettingsView`, `stores/settings.ts`, `DiagnosticsPanel.vue`, `settingsSections.ts` and `diag.ts`;
- core `appSettingsPolicy.ts`, `releaseCheck.ts`, `diagnosticReports.ts` and `ConnectionController`.

Android-only pieces stay in this repository behind the shared seams. They are wired in `src/platform/` and `android/`, and plug into the `settingsSections` extension point once #2936 mounts the shared app.

| 0.5.x setting or surface | Owner and status |
|---|---|
| Terminal text size, theme | Done (Android route today; shared `SettingsView` Display group). |
| Show common keys (`show_common_keys`) | Pending with #2884: the fast-keys dock owns its consumer. It registers as a platform settings section. |
| Dictation language and silence window | #2857 / PR #2897. On the shared path this is an Android settings section. |
| Background grace 30 s / 1 min / 90 s / 5 min / 10 min | Done. Core `BACKGROUND_GRACE_OPTIONS` lists all five, and `ConnectionController` caps grace at 10 minutes by default (`maxBackgroundGraceMs`). The Android route and importer both use the core list, so a legacy 10-minute value imports exactly. Shared `SettingsView` shows it where `api.app.backgroundGrace` is set. |
| Reconnect when I return (`reconnect_when_return`) | Done. Core `returnToForeground({ reconnect })` releases a grace-spent connection and waits in `lost`; `reconnect()` reattaches the same session. Grace and this switch have one owner, the shared settings store `pocketshell.settings.v1` (D42): the Android lifecycle reads it, and both the shared Connections group and the phone Connections route edit it. The phone shows a Reconnect banner while `lost`. The shared desktop/web workspace already has its own Reconnect banner; honouring the switch in the shared connection store is #2936's wiring. |
| Enter-key delay (`agent_submit_enter_delay_ms`) | Done in the shared composer. `useComposerSend` reads `settings.submitEnterDelayMs` per send (default 250 ms, 0–1000 in 50 ms steps). The importer writes the legacy value into the shared settings store. The Android route's composer adopts it with #2936. |
| Usage warning threshold, Reset advanced defaults | Done in the shared Advanced group. The threshold defaults to not set, which keeps the shipped desktop/web meter bands (core `usageMeterTone`); only an explicit threshold (or an imported 0.5.x one) switches to the threshold bands. |
| Connections → per-host workspace roots | #2924 / #2925. |
| Account & sync | #2852. |
| Diagnostics list, report, export, clear | Done (Android route today). The shared `DiagnosticsPanel` covers the same flow over `api.diagnostics`. |
| Delete one report | Done in shared `DiagnosticsPanel` with the Android `api.diagnostics` implementation (`src/platform/androidDiagnostics.ts`). |
| Uncaught-error capture | Done. Shared `installDiagCapture` covers Vue, unhandled rejections and window errors, and Android installs the same capture at startup (`src/platform/androidPlatformServices.ts`). Native Java crashes go through `NativeCrashRecorder` plus the `NativeCrashReports` plugin; the recorder writes exception classes and stack frames only, never messages, so nothing sensitive crosses the bridge or reaches debug logcat. All reports pass core redaction (hosts, credentials, key blocks, known host-store names). |
| Imported 0.5.x crash reports and diagnostic history | Done. The installed-data import stages them read-only; `api.diagnostics` lists them redacted by core `diagnosticReports.ts`. Deleting one hides it, and the source files are never modified. |
| About: installed version and versionCode | Done as `api.app.info` (Android: `App.getInfo()`), shown by the shared About group. |
| Update check, release notes, open release, update banner | Done. Core `releaseCheck.ts` plus the Android `api.update` capability hand the download to `ACTION_VIEW`, and the shared `UpdateBanner` and Updates group render it. Per-worktree test installs omit the capability so journeys stay deterministic. |
| `update_check` preferences | Deliberately expired: they are bookkeeping, not user data. #2860 leaves the source file untouched. |
| Open-source licenses | Pending: a real licenses screen is still to be built (it was a toast placeholder in 0.5.x). |

Packaged proof: API 35 `JsSettingsSupportJourneyTest` via `scripts/connected-js-settings.sh`, wired as the `settings` lane of `scripts/ci-js-first-packaged-lanes.sh`.

- `j16SupportReportsCaptureNativeRuntimeAndSshFailuresAndExport` covers J16: a native crash, uncaught errors and a real SSH bridge failure to a named host, seeded with host names, a PKCS#8 block, a password and a Bearer token; none survive in the reports or (for the native path) the same-run logcat. Then review, per-report delete, clear and export.
- `j24SettingsDestinationsReachableWithAndroidBackAndPersist` covers J24: every settings destination and Android Back, plus the 10-minute grace and the reconnect switch persisting in the shared store across activity recreation.
- `reconnectWhenIReturnOffWaitsThenReconnectsSameSession` (Docker agents fixture): with the switch off, a session outlives grace, the app waits in `lost` with a visible Reconnect, and Reconnect reattaches the same host session on a new connection, checked against the host's own session list.
- Theme and terminal text size still live in the phone blob `pocketshell.js.settings.v1` beside the shared store's `theme`/`terminalFontSize`; they move to the shared store when #2936 mounts the shared app.
- The class name is distinct from #2897's voice-settings `J24SettingsReachJourney`.

## Existing Android journeys → replacement tests

All 24 `*Journey.kt` classes under `app2/src/androidTest/` are mapped below. Replacement tests must run against the packaged WebView APK; host behavior assertions use the real Docker fixture and an independent host-side oracle. These target names are proposed test contracts, not tests already present.

| Existing journey | Behavior that must remain covered | Replacement issue / journey test |
|---|---|---|
| `connect/J01ConnectAndTrustJourney.kt` | Connect to a real host; unknown and rejected host-key trust | #2851 + #2856 — `J01ConnectAndTrustJourney.kt` |
| `tree/J02SessionTreeListJourney.kt` | List and group host workspaces/sessions; show only host-reported agent identity and fresh reported state | #2851 + #2856 + #2907 — `J02SessionTreeListJourney.kt`; packaged A→B→A metadata proof against the independent Docker host oracle |
| `terminal/J03AttachAndTypeJourney.kt` | Attach to a live aplexer session and exchange PTY bytes; bind agent identity/status to the selected host session | #2856 + #2907 — `J03AttachAndTypeJourney.kt` |
| `tree/J04CreateSessionJourney.kt` | Create a session and observe the real host-side row | #2856 — `J04CreateSessionJourney.kt` |
| `terminal/J05ReconnectAfterDropJourney.kt` | Recover after a dropped transport without attaching the wrong session | #2856 — `J05ReconnectAfterDropJourney.kt` |
| `terminal/J06BackgroundGraceReturnJourney.kt` | Return inside/outside background grace and verify attach/reconnect behavior | #2856 + #2861 — `J06BackgroundGraceReturnJourney.kt` |
| `composer/J07ComposerSendJourney.kt` | Deliver a prompt through the real PTY and retain uncertain sends | #2857 — `J07ComposerSendJourney.kt` |
| `composer/J08VoiceDictationJourney.kt` | Dictation editing and draft merge | #2857 — `J08VoiceDictationJourney.kt` |
| `composer/J08VoiceProductionProviderJourney.kt` | Select and exercise a production speech provider path | #2857 — `J08VoiceProductionProviderJourney.kt` |
| `files/J10FilesBrowseEditJourney.kt` | Browse, read, edit, and save a remote file | #2858 — `J10FilesBrowseEditJourney.kt` |
| `share/J11ShareUploadJourney.kt` | Receive an Android share intent and upload the selected content | #2857 — `J11ShareUploadJourney.kt` |
| `usage/J12UsagePanelJourney.kt` | Fetch and render real host quota data | #2859 — `J12UsagePanelJourney.kt` |
| `ports/J13PortForwardOpenJourney.kt` | Open and validate a real forwarded service | #2859 — `J13PortForwardOpenJourney.kt` |
| `tree/J14StopSessionJourney.kt` | Stop the selected real host session | #2856 — `J14StopSessionJourney.kt` |
| `terminal/J15TerminalScrollJourney.kt` | Scroll terminal history while the PTY continues to produce output | #2856 — `J15TerminalScrollJourney.kt` |
| `settings/J16SettingsSupportJourney.kt` | Reach support/diagnostic actions and produce an export | #2861 — `J16SettingsSupportJourney.kt` |
| `hosts/J17HostToolsJourney.kt` | Host-scoped tools and CLI setup state | #2851 + #2856 — `J17HostToolsJourney.kt` |
| `ports/J18AutoForwardResumeJourney.kt` | Resume configured forwarding after lifecycle/process transitions | #2859 + #2861 — `J18AutoForwardResumeJourney.kt` |
| `hosts/J19HostFormImeJourney.kt` | Reach and use host form controls with the Android IME visible | #2856 — `J19HostFormImeJourney.kt` |
| `composer/J20ComposerUploadProgressJourney.kt` | Upload progress, errors, and draft retention | #2857 — `J20ComposerUploadProgressJourney.kt` |
| `hosts/J21HostAddConnectJourney.kt` | Add a host and connect to it end-to-end | #2851 + #2856 — `J21HostAddConnectJourney.kt` |
| `ports/J22ForwardServiceFgsJourney.kt` | Foreground-service notification and tunnel survival | #2859 + #2861 — `J22ForwardServiceFgsJourney.kt` |
| `terminal/J23InlineDictationJourney.kt` | Dictate into a live session composer while preserving edits | #2857 — `J23InlineDictationJourney.kt` |
| `settings/J24SettingsReachJourney.kt` | Reach settings destinations with working Back navigation | #2861 — `J24SettingsReachJourney.kt` |

`Issue887TerminalFixedUnderImeProofTest` and `AddEditHostImePlacementTest` are additional focused Android proofs rather than `*Journey` classes. Their view-containment and real-IME assertions move into the relevant replacement host/terminal journeys in #2856; they are not counted as separate parity journeys. The other legacy `app2` unit and instrumented tests are implementation tests for deleted Kotlin code, not a claim of retained parity. New policy tests belong beside their TypeScript owners, and user-visible behavior is covered by the mapped APK journeys.

## Mobile-only parity added after the 24-journey baseline

The original baseline and 24-class map above remain unchanged. The following follow-up issues specify mobile behavior added after that snapshot. These rows map ownership and required packaged proof targets; they are acceptance contracts, not a live completion report or a claim that every target is already present on this branch.

| Mobile behavior | Owning issue and JS/core versus Android boundary | Required packaged Android / Docker proof target |
|---|---|---|
| Prompt-composer dictation | [#2857](https://github.com/PocketShell-io/pocketshell/issues/2857) owns dictation state, draft merge, editable review, and explicit Send/Insert policy in JS/core. The narrow Android adapter owns microphone permission and the platform speech recognizer. The behavior contract is in [input-methods.md](input-methods.md). | API 35 packaged `JsComposerDockerJourneyTest` via `scripts/connected-js-composer-docker.sh`; capture, streaming partials, pause handling, cancel, Stop-to-editable-review, and explicit Send/Insert, using settings-backed language and silence timing (4,000 ms default, 2,000 ms composer floor). The independent Docker PTY oracle must show no prompt transcript before the explicit action, exact delivered bytes afterward, and no stale delivery after background or target changes. |
| Inline terminal dictation | [#2857](https://github.com/PocketShell-io/pocketshell/issues/2857) owns the shared JS speech controller and Android speech adapter; [#2884](https://github.com/PocketShell-io/pocketshell/issues/2884) owns the terminal dock, mic/Stop presentation, and integration with fast keys. See [input-methods.md](input-methods.md). Partials are preview-only; Stop inserts the validated final once at the active PTY cursor, without Enter. | API 35 packaged `JsFastKeysDockerJourneyTest#fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect` via `scripts/connected-js-hotkeys-docker.sh`; the Docker PTY oracle checks preview-only partials, one exact final insertion, active-session targeting, and cancellation after background or attach changes. |
| Android share and file-picker routing into the composer | [#2857](https://github.com/PocketShell-io/pocketshell/issues/2857) owns JS routing and draft/attachment policy; [#2858](https://github.com/PocketShell-io/pocketshell/issues/2858) owns shared file classification, staging, and retention policy. Android owns only share-intent delivery, document-provider access, content-URI reads, and native SFTP byte I/O. Shares and picks remain draft content until explicit Send or Insert. | API 35 packaged `JsComposerDockerJourneyTest` via `scripts/connected-js-composer-docker.sh`; deliver an Android `ACTION_SEND` before and after a live composer target exists and apply the queued payload once, pick through a real `ACTION_OPEN_DOCUMENT` content URI, and verify exact shared/picked bytes independently on Docker. The host marker must remain absent until the user sends. |
| Fast access keys, main/Ctrl catalog, and terminal dock | [#2884](https://github.com/PocketShell-io/pocketshell/issues/2884) owns the JS dock, catalog, portable key-to-byte mapping, and repeat/hold policy. Android supplies only required IME/inset events. See [input-methods.md](input-methods.md). With the real IME open, the launcher, main catalog, Ctrl page, and dictation mic/Stop target must remain touch-reachable. While terminal dictation is listening, the catalog closes and the dock exposes Stop; Ctrl actions and listening mode are each exercised with the IME open. | API 35 packaged `JsFastKeysDockerJourneyTest#fastKeysStayReachableAndWriteExactBytesAcrossImeBackAndReconnect` via `scripts/connected-js-hotkeys-docker.sh`; use real touch/swipe actions, both Back layers, and the independent Docker PTY oracle. Check Up/Down/Enter, Esc/Tab, CR versus LF, atomic Ctrl-C/Ctrl-D (`03 03` / `04 04`), five visible terminal rows, and an unchanged accepted PTY grid while opening, using, and closing the dock. |
| Per-host snippets and command chips | [#2885](https://github.com/PocketShell-io/pocketshell/issues/2885) owns JS models, host lookup, CRUD/order, literal insertion, and send policy; [#2860](https://github.com/PocketShell-io/pocketshell/issues/2860) owns legacy-data extraction. Android is limited to secure persistence or the one-time legacy reader. A selected chip inserts literal draft text and never executes it. See [input-methods.md](input-methods.md). | API 35 packaged `JsComposerDockerJourneyTest` via `scripts/connected-js-composer-docker.sh`; cover host separation, create/edit/delete/reorder, force-stop/restart persistence, explicit Send, and exact Docker bytes with no PTY write before Send. Also verify 48dp chip controls and TalkBack focus, spoken label, and activation on an accessibility-enabled packaged device or emulator. |
| Host-reported agent identity and state | [#2907](https://github.com/PocketShell-io/pocketshell/issues/2907) owns JS projection and presentation of authoritative `HostCliCore` session fields; Android remains a narrow transport adapter. Follow the [agent-awareness contract](agent-awareness.md): missing or unknown metadata stays absent, and JS must not infer state from labels, terminal text, cwd, or process detection. | Packaged API 35/Docker lifecycle journey `SshPtyDockerJourneyTest#sshSessionSwitchingAndBackgroundGraceAgainstDockerFixture` via `scripts/connected-test.sh lifecycle`; compare rendered known/unknown states and A→B→A switching against an independent host oracle, with same-run full-screen screenshots and logs. |

## Persisted private data → migration contract

The install identity remains `applicationId = com.pocketshell.app`, with the committed `debug.keystore` and the existing release signing inputs/property-file schema. A same-package, same-signature Android update retains the private sandbox. The new startup must not delete, rename, recreate-over, or migrate any old file before #2860 implements and tests the one-time reader. #2860 owns malformed-data refusal and a signed-upgrade test.

| Existing private data | Current location / durable shape | Required replacement handling |
|---|---|---|
| Hosts, SSH-key metadata, trusted-key SHA-256 pins, connection/usage/CLI state | `pocketshell.db`, Room schema 22 tables `hosts` and `ssh_keys`; trust pins are tied to the durable host row/id | #2860 reads all fields and preserves host IDs and trust verdict inputs; #2856 tests trust behavior after import. |
| SSH private key material and passphrase presence | Internal `files/ssh-keys/`; `ssh_keys.privateKeyPath`, fingerprint and `hasPassphrase` in `pocketshell.db` | #2860 keeps referenced key bytes usable and reports missing/unreadable material without overwriting it; platform secure-key work remains in later native adapter issues. |
| Workspace roots/order and port preferences | `project_roots`, `port_remappings`, `port_usage` in Room; `workspace_order` and `port_forward_panel` SharedPreferences | #2860 imports local records/preferences; #2859 verifies ordering/port behavior. |
| Snippets, command templates and composer history | Room tables `snippets`, `command_templates`, `sent_messages` (including delivery state and session keys) | #2860 preserves contents and identity; #2857 tests draft/send handling. |
| AI usage/cost and pending voice transcription queue | Room tables `ai_api_call_log`, `pending_transcriptions`; audio in `files/voice-pending/<uuid>.wav`, exports in `files/voice-exports/` | #2860 preserves queued audio/metadata and cost history; #2857 tests voice retry/delivery. |
| UI settings and launch resume state | `next_settings`; legacy `app_settings`; keys include default host, last workspace/session, terminal size, voice language/silence threshold, usage warning threshold, background grace, submit delay, common-key visibility and reconnect-on-return | #2860 imports known keys and unknown extras safely; #2861 tests preferences/lifecycle. |
| Composer drafts and staged attachment references | `composer_drafts` SharedPreferences; per-session text and attachment paths/metadata | #2860 retains records until import is confirmed; #2857 defines restored-draft and attachment behavior. |
| Settings-sync selection and credentials | `next_sync_selection`; encrypted `pocketshell-sync-auth`, `pocketshell-voice-secrets`, and `pocketshell-assistant-secrets` plus AndroidX encrypted-preference keysets | #2860 leaves ciphertext/keysets in place unless the reader proves they can be opened; no plaintext secrets in JS storage. See the exact [installed-data map](migration/installed-data-map.md). #2852 specifies sync data parity and #2861 owns OAuth lifecycle. |
| Crash reports and diagnostic history | `files/crash-reports/` text reports; `files/diagnostics/pocketshell-diagnostics.jsonl`; export cache is disposable | #2860 does not remove source data; #2861 owns diagnostics display/export. |
| Update-check bookkeeping | `update_check` SharedPreferences | #2860 may leave as opaque legacy metadata; #2861 defines whether it is read or deliberately expired. |

Room schema exports `16.json` through `22.json` are retained exactly at [`migration/room-schemas/com.pocketshell.core.storage.AppDatabase/`](migration/room-schemas/com.pocketshell.core.storage.AppDatabase/) as evidence for the importer. The source exports are removed with the old Kotlin module after their bytes are archived. Schema 22 currently contains 10 application tables: `hosts`, `ssh_keys`, `port_remappings`, `port_usage`, `project_roots`, `snippets`, `ai_api_call_log`, `pending_transcriptions`, `command_templates`, and `sent_messages`. Room also carries its internal `room_master_table`. The schema version is 22. The detailed column and preference inventory is in [migration/installed-data-map.md](migration/installed-data-map.md). Do not infer user data from the host-side `a` session list: live sessions remain host-owned and are not in Room.

## Desktop design extraction contract

The shared UI now lives in `pocketshell-core/packages/ui/src` (`themes.ts`, `fonts.ts`, `components/`, and the desktop/web app tree under `app/`), consumed through the pinned core submodule. Prioritize shared tokens/type/icon semantics and prop/event based presentation components: `AppIcon`, host/session rows, session tree, terminal theme and terminal view, composer/attachment controls, workspace tabs, buttons/menus/overlays, and warning/update banners. Shared `app/views/HostPickerView.vue`, `HostWorkspaceView.vue`, `FolderWorkspaceView.vue`, `FilesView.vue`, `SettingsView.vue`, `UsageView.vue`, and `PortPanelView.vue` contain Electron stores/IPC assumptions and must be decomposed before reuse. Font files need explicit licensing and Android-compatible packaging.

The phone shell keeps desktop colors, wording, icon family, type hierarchy, spacing rhythm, selected/disabled/warning states, and terminal palette as its starting point. It adapts navigation to a host/session drawer, uses touch-sized controls, applies safe areas, handles Android Back, and keeps the composer above the IME. #2855 only supplies an unfinished host/workspace/terminal-shaped empty mock shell; it does not claim any old journey above is implemented.

## CI and release-gate boundary

The `main` CI (`js-first-rewrite.yml`; the rewrite branch was promoted to `main` by #2934) requires the exact JS unit suite,
packages the Android debug APK, runs the seven-method packaged API 35 shell smoke
suite, and exercises the unchanged Docker fixture. The smoke test checks
visible core/asset identity, open-document and share-adapter byte delivery,
Android Back from Settings, and composer placement above the real IME with
safe-area insets. It does not cover feature parity,
scheduled test verdicts, or signed release packaging. The D36/D37
Gradle workflows remain only on `release/0.5.x`; #2863 owns their JS replacement and
validation before any 0.6.0 release. Do not dispatch a legacy Gradle
workflow against `main`.

## Product-code hard cut

This foundation branch removes the Kotlin product modules `app2/` and
`shared/`, the obsolete Kotlin/Python `ui-mock/` and `ui-mock-app/` projects,
and the root Gradle graph that assembled them. The Android platform project
under `android/` remains the generated Capacitor host; JS product code lives in
`src/`, and `vendor/pocketshell-core` remains a pinned Git submodule. Build and
release-gate harness scripts stay for the #2863 migration; they are not product
runtime code and their old Gradle checks must not be run against this branch.

## Docker preservation baseline

The baseline Docker contract is the tracked `tests/docker/` tree and `tests/docker/fixture-pins.txt` at the baseline commit. The fixture pins are `POCKETSHELL_PIN=0.5.5` and `APLEXER_PIN=0.1.5`. Keep the directory and every tracked fixture file unchanged on this issue. Run `scripts/test-agents-fixture-aplexer.sh --docker` to build the image and exercise its installed self-check, then run `git diff --exit-code a6c7e8dab52e944d2b0aafb366d05374c1ace412 -- tests/docker`. Docker journeys for the rewritten client are added under their feature issues; the existing self-check is not a parity claim.
