# Roadmap

`main` is the JS-first 0.6.0 rewrite. The Kotlin `app2` line lives on
`release/0.5.x` and takes hotfixes only (see [release.md](release.md)).

## Current foundation

- A Vue/TypeScript app in `src/`, running in a Capacitor WebView under
  `android/`.
- D42: one shared `pocketshell-core` for every client. Android takes its
  contract layer and shared UI tokens from the pinned `vendor/pocketshell-core`.
- The native SSH plugin implements core's `SshCapability`. Core's
  `ConnectionController` and `HostCliCore` own session and reconnect policy.
- Installed 0.5.x data is imported once (#2860).
- Docker fixtures run real pinned aplexer binaries.

## Near term

- Reach 0.6.0 feature parity with the 0.5.x app. This is tracked under #2854:
  #2856 to #2862, #2924 to #2926, #2929, #2932.
- Replace the Android CI and the D36/D37 release gates with JS lanes (#2863).
  0.6.0 does not ship before that.
- Share more of `pocketshell-core`'s `packages/ui` with desktop and web instead
  of re-authoring phone screens (the #2854 code-reuse audit).
- Keep the host CLI and APK versions in lockstep, with the real fixture
  self-check as a release gate.

## Later

- Port-forwarding polish and host setup recovery.
- Biometric key handling improvements.
- Home-screen session/tunnel status surfaces.
- Mosh, only after a real UDP transport and a defined server installation path
  exist.

## Out of scope

- Desktop and web builds in this repository. Those clients have their own
  repositories and share `pocketshell-core` (D42).
- Cloud-stored terminal history.
- Multi-user host configuration sync.
- A second session runtime or compatibility path for retired host tooling.

See [architecture.md](architecture.md) for the module map and
[decisions.md](decisions.md) for locked choices. Historical rewrite plans are
kept in git history; they are not implementation instructions for current work.
