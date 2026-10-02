# Browser dev mode (no Android)

Run the Android JS app in an ordinary desktop browser with Vite hot reload: no
emulator, no phone, no adb. Issue [#3022](https://github.com/PocketShell-io/pocketshell/issues/3022).

```sh
pnpm dev:mock     # fake host, zero setup: open http://localhost:5173/
pnpm dev:live -- --host user@hostname[:port][=name] --identity ~/.ssh/id_ed25519
                  # then open the URL it prints (…/#devBridgeToken=…)
```

Both shells work: `http://localhost:5173/` is the legacy phone shell and
`http://localhost:5173/?shell=shared` is the shared app. Edits under `src/`
(and the shared UI in `vendor/pocketshell-core/packages/ui`) hot-reload.

This is for UI work and quick checks. It is not acceptance evidence: the
packaged emulator journeys stay the gate (see [testing.md](testing.md) and
[review-standards.md](review-standards.md)).

## Phone-sized viewport

In Chrome or Edge open DevTools (F12), toggle the device toolbar
(Ctrl+Shift+M / Cmd+Shift+M) and pick a phone such as "Pixel 7" or set
412 × 915. Firefox: Responsive Design Mode (Ctrl+Shift+M). The page then has a
phone viewport and touch emulation; a desktop keyboard still types into the
terminal and the composer.

## `pnpm dev:mock`

A believable fake dev box lives in the page:

- one saved host, `mock-devbox` (`dev@devbox.mock:22`), and an in-memory SSH
  key; the legacy connect form fills itself in;
- the first connect shows the real host-key trust prompt (the mock host has a
  fixed ed25519 key);
- five sessions in three workspaces, two with mock agents (Claude working,
  Codex waiting);
- each session's terminal is a small scripted shell: it echoes keystrokes and
  understands `help`, `echo`, `pwd`, `cd`, `ls`, `cat`, `seq`, `clear`,
  `exit` and Ctrl-C;
- files (SFTP) over an in-memory tree under `/home/dev`, provider usage for
  four providers, and listening ports 3000/5173/5432/8000 for the port screen;
- host names containing `fail` or `denied` simulate a refused connection or a
  rejected login.

The mock Vite server listens on all interfaces like `pnpm dev`, so a phone on
the same network can open it too; nothing secret crosses a mock page.

## `pnpm dev:live`

The page talks to real hosts through a small local bridge
(`scripts/dev-ssh-bridge/bridge.mjs`, ssh2 over a WebSocket) that
`pnpm dev:live` starts alongside Vite and stops with it. The bridge implements
the same `SshCapability` contract as the Android plugin.

```sh
# The Docker agents fixture (docs/docker-emulator-runbook.md):
pnpm dev:live -- --host testuser@127.0.0.1:2222=fixture --identity tests/docker/test_key

# Your own server:
pnpm dev:live -- --host you@dev.example.com=devbox --identity ~/.ssh/id_ed25519
```

- `--host` (repeatable) seeds a saved host; `=name` is its display name.
  `POCKETSHELL_DEV_HOSTS` (comma-separated) is the env equivalent.
- `--identity` (repeatable) loads a private key file into the bridge's
  in-memory key vault; the first one is bound to the seeded hosts.
  `POCKETSHELL_DEV_IDENTITY` is the env equivalent. A key loaded this way is
  read by the bridge (Node) only and never reaches the page.
- You can also import or generate keys in the app (Manage keys); they stay
  in the bridge's memory until it stops. **Import is different:** the page
  reads the chosen file in JavaScript (`src/dev/browser/keyVaultPlugin.ts`)
  and sends its text to the bridge, so the key bytes pass through the
  browser tab. Generated keys never do. Prefer `--identity` for a real key.
- `--bridge-port`, `--port` pick the bridge and Vite ports; anything after a
  further `--` goes to Vite. Live mode uses exactly `--port` (default 5173,
  `--strictPort`): the bridge admits only that page origin.

### Opening the page

`pnpm dev:live` prints one URL to its own terminal:

```text
[dev-browser]   http://127.0.0.1:5173/#devBridgeToken=<per-run token>
```

Open that URL. The token is in the URL fragment, which the browser never
sends to a server; the page moves it into the tab's `sessionStorage` and
removes it from the address bar and history. A reload keeps working in that
tab; a new tab needs the printed URL again. The plain
`http://127.0.0.1:5173/` page loads but cannot reach the bridge.

### Security boundary

What protects the bridge, which can run commands on your hosts with your key:

- The bridge and the Vite server both bind `127.0.0.1` only. Nothing on
  another machine can connect.
- In live mode the Vite server answers only `Host: 127.0.0.1:<port>` or
  `localhost:<port>` (pages and WebSocket upgrades alike) and returns 403 to
  anything else. A DNS-rebinding page reaches 127.0.0.1 under its own host
  name, so it gets 403 and never reads the page or opens the bridge proxy.
  (Vite itself is 6.4.3, which also has the upstream `server.allowedHosts`
  check and the `server.fs.deny` fixes.)
- The bridge accepts a WebSocket only when all three hold: `Host` is its own
  loopback address, `Origin` is exactly `http://127.0.0.1:<port>` or
  `http://localhost:<port>` of the dev page, and the client offers the per-run
  random token (as a `Sec-WebSocket-Protocol` value, so it never appears in a
  URL or a proxy log). Another web site, or another local dev server on a
  different port, is a different origin and is refused even with the token.
- The token is never in the served HTML or any file the dev server serves.
  It exists in the launcher's memory, the terminal that started it, and the
  tab you opened. Whoever can read that terminal (or your browser profile)
  can obtain it.
- What this does **not** cover: another local account that can read your
  terminal, attach to your processes or read your files already has your key.
  Another local account *can* fetch the dev server's pages and source files
  over 127.0.0.1 (every Vite dev server serves the source tree) and see the
  seeded host names and user names in the page; it cannot obtain the token or
  drive the bridge. Do not run `dev:live` on a machine you share with people
  you do not trust with your source tree.
- To use it from another machine, tunnel the Vite port to the same local
  port number (the page origin must stay `…:<port>`):
  `ssh -L 5173:127.0.0.1:5173 devbox`, then open the printed URL.
- Host keys are verified before any credential is sent. An unknown or changed
  key fails the dial with `HOST_KEY_REJECTED` and the presented key, and the
  app's own trust prompt asks you, exactly as on the phone. Pins are stored in
  the browser's localStorage for that origin. This protects against a wrong
  server, not against a caller that already holds the token: the caller
  supplies the pin.
- The bridge logs method names, timings and error codes only — never
  passwords, passphrases, key material, commands, file contents, PTY bytes or
  the token.

## What else is simulated

Every native Capacitor plugin has a dev implementation
(`src/dev/browser/`), selected by a fake Android bridge that is installed
before `@capacitor/core` loads, so the app code runs unchanged and sees the
`android` platform:

| Plugin | In the browser |
| --- | --- |
| `SshCapability` | mock host, or the live bridge |
| `SshKeyVault` | in-memory vault (page for mock, bridge for live); key files are picked with `<input type=file>` |
| `DurableStorage` | the browser's localStorage, already durable |
| `KeyboardInsets` | the soft keyboard is "up" while an editable element has focus; the toolbar's Kbd button forces it |
| `SpeechRecognition` | the Web Speech API when the browser has one; `?devSpeech=stub` (or no API) uses a stub that hears "hello from browser dev mode" |
| `DocumentContent` | picks via `<input type=file>`, saves via a download; the toolbar's Share button simulates the Android share sheet |
| `@capacitor/app` | the toolbar's Back button is the Android back button; tab visibility drives pause/resume |
| `NativeCrashReports`, `InstalledDataMigration`, `BridgeReady` | in-memory list, a fresh install with nothing to migrate, an immediate ping answer |

The small **DEV** tab on the right edge holds Back, Kbd, Share, Host (fill the
connect form) and, in mock mode, Drop (simulate a network loss). Click DEV to
collapse it; `?devToolbar=0` hides it. `?devTrace=1` logs each SSH call
(method, command, outcome code — never PTY bytes) to the browser console.

The legacy shell's build strip reads "Build verification failed" under the dev
server: there is no bundled asset manifest to verify, exactly as with plain
`pnpm dev`.

## Never in the APK

The shims are injected by a Vite plugin that only exists in the `mock`/`live`
dev-server modes; the production entry never imports them, and Vite refuses to
build in those modes. CI proves it on every run:
`scripts/check-no-dev-shims.py --dist dist --apk <debug APK>` scans the built
web assets and the APK's `assets/public/` for the shim markers and fails if it
finds one (or finds nothing to scan).

## Tests

- `tests/unit/devBrowserShims.test.ts`, `devBrowserMockHost.test.ts`,
  `devBrowserCapacitor.test.ts` (the app's real Capacitor plugin proxy and the
  shared connection controller driven over the mock host),
  `devSshBridge.test.ts` (the bridge against an in-process SSH server,
  including foreign `Origin`/`Host` refusal) and `devBrowserServer.test.ts`
  (the real `dev:live` launcher: foreign `Host` gets 403, the page carries no
  token, the proxied bridge refuses a foreign `Origin`) run in
  `scripts/run-js-unit-gate.sh`.
- `node scripts/dev-browser-smoke.mjs mock` starts `dev:mock` and drives both
  shells headlessly in Chromium (connect, trust, sessions, terminal echo,
  composer, settings, back, HMR). `node scripts/dev-browser-smoke.mjs live
  --host testuser@127.0.0.1:2222=fixture --identity tests/docker/test_key`
  types into a real PTY on the Docker fixture and checks the result over an
  independent SSH connection. Both run in the `JS checks and Android debug APK`
  CI job; screenshots and `result.json` land in `build/dev-browser-smoke/`.
