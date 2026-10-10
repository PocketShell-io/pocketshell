# Android gateway transport (native half)

Status: native transport from #3086 slice 2; dialling turned on in slice 3.
The Android platform boundary (`unsupportedTransportMessage` in
`src/platform/android/androidApi.ts`) admits a gateway host only when the
native plugin's own `transportCapabilities` reply reports the gateway
transport. Slice 4 adds the shared device list and pairing UI; until then a
pairing is written through the `GatewayPairing` plugin only.

Contracts this mirrors: pocketshell-core `src/gatewayTransport.ts` and
`docs/SYNC.md` (gateway dials, `GATEWAY_CLOSED`), pocketshell-gateway
`docs/tunnel.md` (client route, close codes, error frames), pocketshell-sync
`lambda/gateway-token.mjs` (`POST /gateway/token`).

## One dial

All of it runs inside the existing `SshCapability` plugin
(`SshCapabilityPlugin.connectNow`): same sshj engine, connection IDs and
`lost` events as a direct dial. Core's `ConnectionController` stays the only
owner of dial, trust, reconnect and grace (D28, D42).

1. **Plan, before any effect** (`resolveGatewayDialPlan`). A present
   `gateway` marker must validate; a JS-supplied `expectedHostKey`, a `link`
   marker, a password credential, a signed-out phone, an unpaired target, a
   pairing bound to another key, or a key missing from the vault each refuse
   with a typed code before the broker, a socket or the SSH engine exist.
2. **Routing token** (`GatewayRoutingTokens`, `GatewayTokenBroker`,
   `GoogleSyncSession.gatewayTokenExchange`). One empty-body
   `POST {syncApi}/gateway/token` with the stored Google ID token as Bearer
   (renewed silently first, retried once after a renewal on 401 — the same
   rule as sync requests). The answer must be exactly `{token, token_type:
   "Bearer", expires_in ≤ 300, expires_at}`; the local expiry is arrival time
   plus `expires_in`, so a phone whose clock is off still works. The token is
   cached in memory for the signed-in account and reused only while it
   outlives the attempt's whole connect budget; otherwise it is re-minted. A
   4401 on a reused token drops the cache and retries once with a fresh mint
   inside the same deadline; a 4401 on a fresh token is reported. If that
   re-mint itself fails (signed out, account switched, broker refused the
   sign-in), the dial reports that code, not the stale token's 4401. The token
   is bound to the account the dial was planned for: the signed-in subject is
   re-read before the cache is used or the broker asked, after the broker
   answers (before anything is cached), and right before the tunnel is
   created. A sign-out (`NOT_SIGNED_IN`) or another account
   (`GATEWAY_ACCOUNT_CHANGED`) fails the dial with nothing sent and the cache
   dropped.
3. **Tunnel** (`GatewayTunnel`). `wss://<gateway>/api/v1/hosts/<device>/ssh`
   only (plaintext needs a test-only flag and a loopback host), platform CA
   trust with SNI and hostname checks, one auth TEXT frame carrying the token,
   exactly one `ready`/`error` reply, then ordered binary frames. Bounds:
   1 MiB + 64 KiB per message (checked before allocation), 4 MiB / 64 frames
   inbound, 1 MiB pending outbound with a 30 s stall deadline.
4. **SSH.** sshj gets the tunnel's already-connected socket, so it never
   resolves or dials the display hostname. The pin verifier compares the key
   the host presents during KEX with the saved pairing — the exact key when
   the user pasted the host-key line, else its SHA-256 fingerprint — before
   userauth. A mismatch is `HOST_KEY_REJECTED` and never connects; the
   result of a good dial carries `gatewayHostKeyVerified: true`, core's
   receipt.

One whole-connect deadline (default 60 s, 5–90 s) covers token, dial,
`ready`, KEX and userauth. It and every tunnel deadline run on a monotonic
clock (`SystemClock.elapsedRealtime`), so a wall-clock jump cannot move them. The watchdog claims the deadline under the same
lock that registers a successful connection, so exactly one wins: a claimed
deadline makes registration refuse with `CONNECT_TIMEOUT`, and an accepted
connection is never claimed afterwards.

## The routing token never leaves native code

It exists only in `GatewayRoutingTokens` (memory) and the tunnel's auth
frame. No bridge method returns it, no error message or `data` carries it,
nothing logs it, and `toString()` redacts it. `GoogleSync`'s bridge methods
are unchanged and its `request` route table still allows only `/me` and
`/settings/<slot>`, so JS cannot mint one either.

## Gateway refusals reach core intact

Any gateway verdict (a remote close code in 4000–4999 only; a remote
1000/1001/1011 is an ordinary drop) rejects `connect()` with code
`GATEWAY_CLOSED` and `data.gatewayCloseCode`, whether it arrived as a close frame before `ready`,
as a handshake `error` frame, or as a close after `ready` that sshj saw as a
stream error. Error frames map to the gateway's own pairs: `protocol` 4400,
`unauthorized` 4401, `forbidden`/`revoked` 4403, `not_found` 4404, `timeout`
4408, `quota` 4429, `host_offline` 4503. Any other frame code (`internal`, or
the agent route's stage-dependent `challenge`) takes the close code that
follows it. A failure the gateway never ruled on (DNS, TCP, TLS, a malformed
frame the client refused) keeps its ordinary code, so core keeps its default
retry. Core's `classifyGatewayDialFailure` decides retries from the code.

## Pairing (host-key pin)

`GatewayPairingStore` keys each pairing by (Google account subject, canonical
`wss://` origin, device id) and holds the pin and the vault key handle. The
pin is the line `pocketshell gateway show --host-key` prints on the host,
validated with the CLI's own grammar (one `<keytype> <base64>` line, a
well-formed key of that type, nothing else), or that key's `SHA256:`
fingerprint. The gateway's `ready.ssh_host_key` and the device list's
`ssh_host_key` are advisory and never become a pin. There is no TOFU: no
pairing means no dial.

Storage is an `EncryptedSharedPreferences` file
(`pocketshell-gateway-pairings`, Android Keystore master key), excluded from
backup and device transfer like the key vault. A keyset that can no longer be
opened is deleted, which only leaves hosts unpaired.

The `GatewayPairing` plugin (`list`, `pair`, `remove`) is the native API the
shared pairing UI will call in slice 4. It never returns the account subject:
a `pair` call names the account email the user saw, and native checks it
against the current sign-in before and after the vault lookup. `remove`
validates the complete target (a usable gateway origin and a device id)
before the store is opened, and deletes only the exact (account, canonical
origin, device) pairing; a missing or malformed target removes nothing.

## Capability and the Android boundary

`SshCapabilityPlugin.transportCapabilities` reports `{gatewayTransport: true,
linkTransport: false}`. `src/native/sshCapability.ts` copies that into
`sshCapability.gatewayTransport` (false until the reply arrives, and false on
any unreadable reply); core's controller reads it.

`unsupportedTransportMessage` is the one place Android decides transport
support. It awaits the same native reply at dial time and passes
`{gateway: <native report>, link: false}` to core's `unsupportedTransport`:

- transport not reported: every gateway marker refuses before any effect;
- reported: null, malformed or future-shaped markers, `gateway` + `link`, and
  a `link` marker alone still refuse; an admitted `ws://` origin refuses too
  (the native tunnel dials `wss://` only, and also refuses it at plan time);
- an admitted marker (on the request, or on the account entry the request
  names) resolves through `src/platform/android/gatewayTarget.ts`: the
  credential is the vault key of the phone's pairing for that device
  (`GatewayPairing.list`), and the target carries the normalized `gateway`.
  It is dialled by the same ConnectionController and native `connect()` as any
  host; it is never re-dialled as plain SSH (D28). Signed out and unpaired
  refuse with core's own advice.

## Emulator lane (`gateway-docker`)

`scripts/connected-js-gateway-docker.sh` (`scripts/connected-test.sh
gateway-docker --suffix i3086gwlane --gateway-src DIR`), run by
`scripts/ci-js-first-packaged-lanes.sh` and checked by
`scripts/check-js-gateway-results.py`. Journey:
`GatewayDockerJourneyTest`.

- Fixture (`tests/docker/gateway/docker-compose.yml`, one compose project per
  run): the REAL pocketshell-gateway and pocketshell-link, built from the
  private repository at `tests/docker/gateway/gateway-source.pin`; the host is
  the agents image (sshd, the pinned CLI, aplexer) with no published port, so
  the emulator has no route to its sshd. The agent enrolls like a user's host
  and pins the host key, checked against the key file read from the host; the
  phone pairs with the agent's `show` line (what `pocketshell gateway show
  --host-key` prints). The phone's vault key is authorized on the host
  through the runner's controller, as a user would paste it.
- Broker: the only stand-in is the ISSUER. `gwfixture brokerkey` makes a
  per-run RSA key; `gwfixture jwks` serves its public half and the gateway
  runs its full RS256/iss/aud/scope/exp/allowlist verification. The journey's
  fake sync backend (installed through `GoogleSyncEnvironment`, like the
  account lanes) answers `POST /gateway/token` with a token signed by that
  key. No Google token is used anywhere.
- TLS: `gwfixture certs` makes a per-prepare test CA (its private key is never
  written) and a `localhost` server certificate; `gwfixture tlsfront`
  terminates TLS in front of the gateway on 127.0.0.1:3287, which `adb
  reverse` mirrors on the emulator. Only a debug build with
  `-PpocketshellGatewayTestCa=<ca.pem>` AND a package suffix containing
  `gwlane` gets a generated network-security-config that trusts that CA, for
  the domain `localhost` only (`android/app/build.gradle`). Release, the
  unsuffixed and preview packages and every other lane keep platform trust;
  the tunnel's wss-only rule and hostname checks are unchanged.
- What it proves: session list, attach, terminal I/O and resize read back
  from host files; a tunnel drop re-dialled by the controller with a fresh
  token; a pin mismatch that reaches sshd with zero userauth attempts; 4401
  (a token the gateway cannot verify), 4404 (an unenrolled device) and 4503
  (the agent stopped; the gateway's presence says offline) refused before
  sshd, each with its own message. The real gateway answers revoked and
  other-account devices on the client route with 4404 (uniform denial), so
  4403 cannot be produced end to end; its mapping is covered by the JVM
  close-code tests.
- CI: the workflow checks the gateway repository out with the
  `POCKETSHELL_GATEWAY_READ_TOKEN` secret (read-only contents). Without it the
  checkout fails and the required check is red; the lane never skips.

## Tests

JVM (`scripts/run-android-unit-gate.sh`): `GatewayTunnelTest`,
`GatewayTargetPolicyTest`, `GatewayTokenBrokerTest`,
`GoogleSyncSessionGatewayExchangeTest`, `GatewayPairingStoreTest`,
`GatewayPairingPluginContractTest`, `SshCapabilityPluginGatewayRefusalTest`,
and three end-to-end classes over `GatewayDialFixture` — a loopback gateway
relaying to a real SSH server (Apache MINA sshd, test-only):
`SshCapabilityPluginGatewayCloseCodeTest`, `SshCapabilityPluginGatewayPinTest`,
`SshCapabilityPluginGatewayTokenTest`, `SshCapabilityPluginGatewayAccountTest`,
`SshCapabilityPluginConnectDeadlineTest`, plus `SshCapabilityPluginGatewayClockTest`
(monotonic deadline) and `GatewayTunnelTlsTest` (the real wss:// socket
factory). JS: `tests/unit/androidGatewayCapability.test.ts`,
`tests/unit/androidGatewayDial.test.ts`. Packaged: the `gateway-docker` lane above.
