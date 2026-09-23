# JS-first Android foundation

The Android client is a Capacitor host around a Vue 3 and TypeScript app. The
first shell is an explicitly offline preview: host and workspace lists are
empty, terminal output is marked as mock, and the composer field is local only.
Feature behavior and parity work belong to the issues listed in
[the pre-deletion inventory](js-first-rewrite-inventory.md).

## Build and test

Clone the repository with the pinned core source, install the locked JS tool
dependencies, and build the debug APK:

```sh
git clone --recurse-submodules https://github.com/PocketShell-io/pocketshell.git
cd pocketshell
pnpm install --frozen-lockfile
pnpm test:unit
pnpm build:android:debug
```

The shell imports TypeScript directly from `vendor/pocketshell-core`, a git
submodule pinned by the superproject's gitlink. Core is not an npm package and
is not published to a package registry. `pnpm-lock.yaml` pins the JS app,
Capacitor, Vue, xterm, and test/build tool dependencies. `pnpm-workspace.yaml`
allows build scripts only for esbuild and vue-demi, the packages that require
them for Vite's native executable and Vue compatibility setup.

`pnpm build:web` fails unless the submodule checkout is present, clean, and at
the gitlink revision. Vite embeds that core revision and writes a manifest with
SHA-256 hashes for packaged JS, CSS, fonts, and other assets. The app verifies
the embedded revision and fetched asset bytes at startup. A mismatch appears as
a visible failed build status with the reason, rather than a verified status.

Capacitor generates `android/` and copies `dist/` into the Android asset bundle.
`pnpm build:android:debug` runs the source check, type check, web bundle,
Capacitor sync, and the Gradle debug APK build. Run `pnpm test:unit` separately
for the core formatter and build-manifest diagnostics tests.
Gradle preserves `applicationId = com.pocketshell.app`, the committed
`debug.keystore`, and the release signing inputs documented in
[release.md](release.md). Version code and name continue to come from
`scripts/derive-version.sh`.

The first APK preserves the current Android compatibility settings: minimum
SDK 26, target SDK 35, and compile SDK 36. A later target-SDK change requires an
explicit compatibility review and must not be introduced as incidental
Capacitor template drift.

## Visual baseline

`src/styles.css` starts from the shared desktop dark-tool palette and type
hierarchy: GitHub-like surfaces, cyan action color, Inter, 4 px spacing steps,
and shared terminal colors. The phone layout keeps that visual language while
using a vertical card flow, safe-area padding, touch-sized controls, responsive
viewport sizing, and Android Back handling. `TerminalPreview.vue` uses xterm
with fixed sample output and cannot connect to a host.

The shell is not a visual acceptance claim. Follow
[review-standards.md](review-standards.md) for emulator review; later UI work
should use the extracted shared desktop components tracked by
[pocketshell-desktop#3](https://github.com/PocketShell-io/pocketshell-desktop/issues/3).

## Preserved test environment

The existing Docker fixture remains pinned and untouched. Run
`scripts/test-agents-fixture-aplexer.sh --docker` to build its pinned image and
run the installed CLI self-check. Do not change `tests/docker/` as part of the
foundation or the app-module hard cut; new JS client journeys and their Docker
assertions belong to their feature issues.
