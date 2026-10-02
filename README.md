# PocketShell

PocketShell is a voice-first Android SSH client with an app UI that follows the
shared PocketShell desktop design. The `main` branch is the
0.6.0 development line: the new Vue 3, TypeScript, and Capacitor app. It is
untagged and unreleased until its release gates pass. The released 0.5.x
Kotlin app lives on the `release/0.5.x` branch. The shell currently shows
offline host/workspace states and a sample terminal; it does not yet connect to
SSH hosts or implement the old app's feature set.

The rewrite is tracked by umbrella issue [#2854](https://github.com/PocketShell-io/pocketshell/issues/2854).
The pre-deletion destination, journey, and stored-data map is in
[docs/js-first-rewrite-inventory.md](docs/js-first-rewrite-inventory.md). The
shared Vue UI package lives in `pocketshell-core/packages/ui`.

## Build and test

Requirements: Node.js 22, pnpm 12.5.1, JDK 21, and Android SDK platform 36.
Clone the repository with its pinned core source (which carries the shared UI):

```sh
git clone --recurse-submodules https://github.com/PocketShell-io/pocketshell.git
cd pocketshell
pnpm install --frozen-lockfile
scripts/run-js-unit-gate.sh
scripts/assemble-debug.sh
```

`vendor/pocketshell-core` is the only Git submodule, pinned by the
superproject. Android imports core TypeScript (`@pocketshell/core`) and core's
browser-safe `packages/ui/` source (`@ui`, the alias desktop and web use)
directly; it is not an npm package or registry dependency. The APK build
manifest records the core commit and the SHA-256 of its bundled web assets. JS tooling
dependencies are locked in `pnpm-lock.yaml`. To build and install under an
isolated package name:

```sh
scripts/assemble-debug.sh --suffix local --install
```

### Run it in a browser (no Android)

For UI work, run the app in a desktop browser with Vite hot reload — no
emulator, phone or adb:

```sh
pnpm dev:mock   # fake host with sessions, a scripted terminal, files, usage and ports
pnpm dev:live -- --host testuser@127.0.0.1:2222=fixture --identity tests/docker/test_key
```

Open <http://localhost:5173/> (legacy shell) or
<http://localhost:5173/?shell=shared> (shared app), then switch Chrome DevTools
to a phone viewport (Ctrl+Shift+M, e.g. "Pixel 7"). `dev:live` reaches real
hosts through a local ssh2 bridge bound to 127.0.0.1; open the URL it prints
(it carries the bridge token in its `#` fragment). Details, security notes
and the simulated plugins: [docs/browser-dev-mode.md](docs/browser-dev-mode.md).
None of this ships in the APK.

The preserved Docker agents fixture can be checked with:

```sh
scripts/test-agents-fixture-aplexer.sh --docker
```

The app keeps `applicationId = com.pocketshell.app`, the existing debug key,
minimum SDK 26, target SDK 35, and compile SDK 36. Room schema exports retained
for the future installed-data reader live under
[docs/migration/room-schemas/](docs/migration/room-schemas/).

## Current branch gates

The rewrite branch CI builds the web app and debug APK, runs its unit tests, and
checks the Docker fixture. Emulator parity journeys, scheduled D36/D37 verdicts,
and release packaging are still owned by the existing Android gates on
`main`/`stable`. Issue [#2863](https://github.com/PocketShell-io/pocketshell/issues/2863)
tracks their validated replacement before branch integration. See
[docs/js-first-rewrite-foundation.md](docs/js-first-rewrite-foundation.md) and
[docs/README.md](docs/README.md) for details.
