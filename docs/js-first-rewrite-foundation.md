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
scripts/run-js-unit-gate.sh
pnpm build:android:debug
scripts/connected-js-smoke.sh --suffix i2855
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
for the core formatter and build-manifest diagnostics tests. The canonical
rewrite-branch unit command is `scripts/run-js-unit-gate.sh`; it requires the
complete registered Vitest suite and checks its result count and titles.
Gradle preserves `applicationId = com.pocketshell.app`, the committed
`debug.keystore`, and the release signing inputs documented in
[release.md](release.md). Version code and name continue to come from
`scripts/derive-version.sh`.

For fast local builds, use `scripts/assemble-debug.sh`; it runs `pnpm build:web`,
`pnpm cap:sync`, and the generated Android Gradle wrapper. Use
`scripts/assemble-debug.sh --suffix i2855 --install` for an isolated package
install while testing alongside an existing app. JS dependencies are installed
with `pnpm install --frozen-lockfile`; this repository does not use npm.

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

## Temporary branch CI boundary

`.github/workflows/js-first-rewrite.yml` runs on pushes and pull requests to
`rewrite/js-first-0.6.0`. It installs the locked JS dependencies, runs unit
`rewrite/js-first-0.6.0`. It requires the exact registered JS unit suite,
packages the debug APK, runs a packaged API 35 Android smoke suite, and runs
the pinned Docker agents fixture. The smoke suite executes exactly three tests:
the installed shell must show the verified core revision and asset hash, a
Settings tap and Android Back must return to Hosts, and the focused composer
must remain above the real IME while Capacitor safe-area insets are applied.
This is shell coverage, not feature parity. It does not cover the SSH/session
journeys, create a signed release artifact, or establish a nightly release
verdict. The existing `app2.yml` and `tests.yml` D36/D37 lanes remain attached
to `main` and `stable`; they are not copied onto this branch because their
Kotlin modules are being removed. Pull requests into those branches must wait
for [#2863](https://github.com/PocketShell-io/pocketshell/issues/2863), which
owns the broader scheduled test and release-gate migration. Do not manually
dispatch a legacy Gradle workflow against this branch; its old build graph is
intentionally absent.

The legacy `scripts/check-unit-gate-wiring.sh` is not part of the rewrite CI.
On this branch it exits 123 with no output: its C9 scan treats the retained
Capacitor `android/app/build.gradle` as the old Kotlin test graph, then `xargs`
returns 123 when `grep` finds no Kotlin test harness path in that file. Keep
that guard unchanged until #2863 replaces its Gradle-specific scan with a
nonvacuous check for the JS test graph.

## Preserved test environment

The existing Docker fixture remains pinned and untouched. Run
`scripts/test-agents-fixture-aplexer.sh --docker` to build its pinned image and
run the installed CLI self-check. Do not change `tests/docker/` as part of the
foundation or the app-module hard cut; new JS client journeys and their Docker
assertions belong to their feature issues.

Room schemas 16 through 22, the source evidence for the later installed-data
reader, are retained in
[`migration/room-schemas/com.pocketshell.core.storage.AppDatabase/`](migration/room-schemas/com.pocketshell.core.storage.AppDatabase/).
They are exact copies of the exports formerly under
`shared/core-storage/schemas/`; #2860 owns the reader and migration tests.
