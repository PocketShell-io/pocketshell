# PocketShell

PocketShell is a voice-first Android SSH client with an app UI that follows the
shared PocketShell desktop design. This `rewrite/js-first-0.6.0` branch is the
new Vue 3, TypeScript, and Capacitor foundation. The shell currently shows
offline host/workspace states and a sample terminal; it does not yet connect to
SSH hosts or implement the old app's feature set.

The rewrite is tracked by umbrella issue [#2854](https://github.com/PocketShell-io/pocketshell/issues/2854).
The pre-deletion destination, journey, and stored-data map is in
[docs/js-first-rewrite-inventory.md](docs/js-first-rewrite-inventory.md). UI
component extraction is tracked by
[pocketshell-desktop#3](https://github.com/PocketShell-io/pocketshell-desktop/issues/3).

## Build and test

Requirements: Node.js 22, pnpm 12.5.1, JDK 21, and Android SDK platform 36.
Clone the repository with its pinned core source:

```sh
git clone --branch rewrite/js-first-0.6.0 --recurse-submodules https://github.com/PocketShell-io/pocketshell.git
cd pocketshell
pnpm install --frozen-lockfile
pnpm test:unit
scripts/assemble-debug.sh
```

`vendor/pocketshell-core` is a Git submodule pinned by the superproject; it is
not an npm package or registry dependency. JS tooling dependencies are locked
in `pnpm-lock.yaml`. To build and install under an isolated package name:

```sh
scripts/assemble-debug.sh --suffix local --install
```

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
