# JS-first Android rewrite plan (0.6.0)

Issue: [#2854](https://github.com/PocketShell-io/pocketshell/issues/2854). Branch: `rewrite/js-first-0.6.0`. This document is the architecture gate before deleting the current Android product implementation. `0.6.0` is a future candidate, not a release action. The `0.5.6` correction stays on `main`.

## Decision and constraints

The maintainer chose a fresh JS-first Android implementation and authorized replacing the current `app2`/`shared` product code on this branch. Keep `tests/docker/` and its pinned SSH/aplexer fixtures. Preserve installed user data, the Android package/signing identity, the host `pocketshell` CLI contract, and the release safety gates. The old app continues on `main` while this branch is built and reviewed.

This is the cardinal connection-core rewrite described by D28. The replacement will have one connection/session policy owner in TypeScript. Native Android code may perform physical SSH and device operations but must not contain a second reconnect/session decision path. D22 means deleting superseded product code and avoiding a hidden Kotlin fallback. The exact pre-rewrite baseline is `00bb3eff7ca23a6bce442ec9314af0100747ece5`; rebase the branch after the separate 0.5.6 correction without losing this reference.

“Only JS” means no Kotlin product logic and, if feasible, zero authored Kotlin files. Android still requires a host Activity, packaged WebView, native platform APIs and a small SSH capability layer. Generated Java bootstrap and narrow Java plugins are acceptable. Moving business decisions into Java to satisfy a file-count target is not.

## Proposed application architecture

Use Capacitor's Android shell with Vue 3, TypeScript, Pinia and xterm.js. This matches the desktop renderer's framework and terminal stack. A small Android capability plugin exposes direct SSH, SFTP, forwarding and device APIs; it does not use the web client's WebSocket-to-TCP relay. The remote host still runs the pinned `pocketshell` CLI and its bundled aplexer.

```text
Vue mobile screens + shared desktop UI components
              ↓
@pocketshell/core TypeScript contracts and app controllers
              ↓ typed async capability interface
Capacitor Android plugins: sshj, secure keys, lifecycle, voice, files, share
              ↓
pocketshell host CLI → bundled aplexer
```

This is a design to prove, not an assertion that Capacitor or the bridge already meets PocketShell's PTY throughput and lifecycle needs. The first executable milestone must test terminal byte flow, background grace, trust rejection and WebView recreation on a packaged APK. If it fails, revise the architecture in #2854 before deleting more code.

### Native boundary

Native code owns: SSH channel/PTY/SFTP/tunnel I/O; bounded reads/writes and cancellation; Android Keystore and biometric unlock; foreground service and absolute background-grace deadline; clipboard, document picker, share intents, OAuth callback, microphone/speech and notifications; and one-time reading of the old app's private data. The native service enforces grace expiry even if WebView timers stop. It reports physical state to TypeScript.

TypeScript owns: host CLI command and schema contracts, trust verdict policy, connection retry ladder, session identity/switch/attach decisions, workspace projection, composer delivery and draft policy, sync merge, file/attachment decisions, usage and port-discovery policy, and screen state. No native module decides which session to reopen or retries an ambiguous send.

The bridge uses typed operations, request and connection-generation IDs, ordered per-channel writes, cancellation and structured errors. PTY output is sequenced, batched and backpressured; a suspended WebView must get an explicit recovery event rather than silently losing bytes. Do not evaluate generated JavaScript strings containing user or host data. One app controller survives route changes; Activity/WebView recreation rehydrates state without a second SSH connection. Distinguish transport loss, timeout, cancellation, schema failure and uncertain delivery. Missing or mismatched JS assets show diagnostics rather than a blank WebView.

[Capacitor Android and plugin documentation](https://capacitorjs.com/docs/android) describe the shell and native plugin mechanism. [Android foreground-service restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) constrain grace handling. `sshj` is the current transport implementation; evaluate its use behind the plugin against [the upstream project](https://github.com/hierynomus/sshj).

## Shared source without publishing to npm

Pin the `pocketshell-core` repository as a git submodule under `vendor/pocketshell-core/`; pin a separate desktop repository commit under `vendor/pocketshell-desktop/` only after its reusable `packages/ui/` is extracted. Vite/TypeScript/Vitest aliases import the pinned source entry points directly. CI checks out the recorded commits without `--remote`, checks clean gitlinks, and records both commit IDs and built-bundle hashes in an APK build manifest. A clean checkout must reproduce those assets. Core changes land in its own repo first, then a reviewed gitlink update here. No `@pocketshell/core` npm publication, local sibling-directory dependency or runtime download is needed. JS build tools and third-party dependencies may still use a lockfile; that does not publish or fetch PocketShell core as a registry package. [Git submodule documentation](https://git-scm.com/docs/git-submodule).

The current core package contains contracts, not a whole mobile controller. Extend it for the portable policies below. Keep the UI package separate from the platform-free core. Desktop and Android import the same UI sources, with props/events at the platform boundary; web can adopt them later.

## Desktop-consistent design

Desktop's current sources of truth are `pocketshell-desktop/src/renderer/themes.ts`, `fonts.ts`, `App.vue`, `components/` and `docs/DESIGN.md`. Extract colors, spacing, typography hierarchy, icons, buttons, rows, tabs, menus, warnings, composer controls and terminal theme into a Vue UI library. Existing components tied to Electron IPC or desktop Pinia stores must first become presentational props/events; do not import an Electron-bound view unchanged. Bundle licensed fonts explicitly; a Windows font default may be absent on Android.

Match desktop color, icon, wording, visual states and interaction hierarchy. Adapt layout for phone width: session drawer instead of a permanent sidebar, touch targets, safe areas, Android Back, keyboard-aware composer, narrow file views and terminal viewport. Compare populated desktop and packaged Android screens side by side for hosts, workspace/session list, terminal, composer with attachments and keyboard up, files/editor, settings and error states. The maintainer signs off the actual Android app before this large UI change ships; isolated browser renders are only fast iteration evidence.

## Portable behavior inventory

This table is the initial deletion map. Each implementation issue must name exact old call sites, new core exports, shared fixture vectors and replacement Android journeys before removing its Kotlin counterpart.

| Behavior | Current Android owner | New TypeScript owner |
| --- | --- | --- |
| Host CLI commands/JSON/errors | `shared/core-hostapi/HostCliClient.kt`, `SessionsJson.kt`, `WorkspacesJson.kt` and models | New `HostCliCore`, with schema, timeout, error, idempotent-create and attach contracts; #2851 |
| Session/workspace projection | `app2/.../workspaces/WorkspaceProjection.kt`, `HostWorkspacesViewModel.kt`, session classes | Core identity, grouping, ordering and controller; Vue renders output |
| Connection/reconnect/grace | `ConnectionsRegistry.kt`, `ReconnectController.kt`, `ReconnectDriver.kt`, `GraceCoordinator.kt` | One core state machine over native effects; D28 journey proofs |
| Composer/drafts | `ComposerViewModel.kt`, input helpers and draft/history policy | Core delivery transaction, submit/insert, draft retention and uncertain-send handling |
| Settings sync | `app2/.../sync/SyncPayload.kt`, `SyncRepository.kt` | Core payload, merge/conflict policy, preserving remote extras; #2852 |
| Files and attachments | file explorer/viewer/stager/retention/sanitizer classes | Core paths, entry models, edit/conflict and retention decisions; native SFTP I/O |
| Usage | `shared/core-usage` parser and portable fetch/display logic | Core normalized record, reset/threshold and view decisions |
| Ports | `shared/core-portfwd` scanner/filter and auto-forward policy | Core discovery/selection policy; native tunnels |
| Terminal input | `shared/core-terminal` key encoding, app link/OSC52/path policy | Core input/link policy; xterm.js renders terminal |
| Supporting policies | host validation, settings, dictation merge, release comparison | Pure core functions/controllers where reused |

Do not replace Android `HostCliClient` with existing `AplexerCore`: the former invokes `pocketshell sessions/workspaces/engines/profiles` and exposes schema/errors; the latter invokes `a` directly and can map failures to empty results. Build `HostCliCore` with the Android contract first. Android host trust stores SHA256 fingerprints by durable host ID, while current core known-hosts compares OpenSSH key blobs; design migration and verdict parity before moving it. Android sync merges remote `extras` into locally owned host fields; current core selection can drop them. Composer byte framing differs: Android has UTF-8 writes and uncertain delivery across reconnect, while the current desktop core path uses timed bracketed-paste writes. Prove one contract on real PTYs before switching.

## Data and test preservation

Before deleting the old app, inventory Room schema exports/migrations and private state: hosts, SSH keys, trust pins, workspace settings, drafts/history, snippets, usage preferences, sync selections and encrypted preferences. Keep application ID and signing identity. Implement a one-time import reader into the new storage schema; test upgrade from the signed 0.5.x app and refusal of malformed data. Deleting Kotlin runtime code does not authorize a destructive database reset.

Keep `tests/docker/`, `tests/docker/fixture-pins.txt`, image self-checks and independent host-side oracles. Map each existing emulator journey to a new driver and behavioral assertion before deleting its Kotlin test; a zero-test green is invalid. Browser component/E2E checks give fast feedback, but packaged Android tests must prove SSH/PTY bytes, trust rejection, reconnect/grace, keyboard/IME, voice/share, files, sync and forwarding. Session/terminal assertions need host effects and logs/screens from the same run. Mutation checks must show critical assertions can fail. Preserve the D36/D37 scheduled fault verdict and release gate as real executable suites, adapting scripts only after replacement coverage is live.

## Dependency order and gates

1. **Document and inventory.** Commit this plan, desktop UI extraction contract, feature/journey map, data map and exact baseline on the rewrite branch. Update #2854 and split implementation issues. No product-code deletion precedes this gate.
2. **Foundation spike.** Build a minimal packaged Capacitor APK from pinned core/UI sources. Prove direct SSH, host-key rejection, PTY output/input/resize under load, foreground/background grace, WebView recreation and diagnostics. Reviewer runs the Android/Docker journey. If this fails, reconsider runtime before broad deletion.
3. **Core host/session path.** Implement `HostCliCore` in the core repo, native SSH plugin and one TS connection/session controller. Replace hosts/workspaces/tree/terminal screens with shared design. Delete corresponding Kotlin product modules on the rewrite branch only as replacement behavior becomes testable.
4. **Input and persisted data.** Define composer transaction, attachments/drafts, explicit-stop dictation, share adapters, per-host snippets and command chips, sync unknown-field semantics and installed-data migration. Keep portable input and draft policy in JS/core, with Android limited to speech, content URIs and secure storage. Use shared contract vectors and Android journeys.
5. **Daily-use parity.** Restore the phone's one-tap Up/Down/Enter controls and floating hotkeys palette, then files/editor, usage, ports/auto-forward, settings, diagnostics and remaining feature inventory. The hotkeys launcher stays reachable with the real IME open; the palette floats inside the terminal without resizing its cells. Review desktop/phone design side by side and prove exact key bytes with the Docker PTY oracle.
6. **Qualification.** Full Docker and device journeys, upgrade test, connection fault/soak tests, visual sign-off, versionCode check and exact-SHA release gates. Merge to `main` only after reviewer approval and green gates; tag 0.6.0 only later from that validated main commit.

Milestones 2–5 require issue-sized implementer/reviewer loops, not one large unreviewed branch change. A 6–9 week candidate is a planning estimate, not a delivery promise. The foundation spike is the decision point before removing most of the old app.

## Related issues

- [#2854](https://github.com/PocketShell-io/pocketshell/issues/2854): rewrite umbrella.
- [#2850](https://github.com/PocketShell-io/pocketshell/issues/2850): old QuickJS/Kotlin bridge scope, to close or replace after plan adoption.
- [#2851](https://github.com/PocketShell-io/pocketshell/issues/2851): host CLI contract work, still needed; adapt to the new JS shell.
- [#2852](https://github.com/PocketShell-io/pocketshell/issues/2852): sync parity, still needed.
- [#2853](https://github.com/PocketShell-io/pocketshell/issues/2853): separate 0.5.6 correction.
- [#2857](https://github.com/PocketShell-io/pocketshell/issues/2857): composer, dictation and share adapters.
- [#2884](https://github.com/PocketShell-io/pocketshell/issues/2884): mobile fast access keys and floating palette.
- [#2885](https://github.com/PocketShell-io/pocketshell/issues/2885): per-host snippets and command chips.
