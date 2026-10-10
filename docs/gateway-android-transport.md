# Android gateway transport (native half)

Status: native transport landed dark in #3086 slice 2. Android still refuses
every gateway host at its platform boundary (`unsupportedTransportMessage` in
`src/platform/android/androidApi.ts`, `gateway: false`); slice 3 lifts that
behind the native capability, and slice 4 adds the shared device list and
pairing UI.

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
   inside the same deadline; a 4401 on a fresh token is reported. The token
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
`ready`, KEX and userauth. The watchdog claims the deadline under the same
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

Any gateway verdict rejects `connect()` with code `GATEWAY_CLOSED` and
`data.gatewayCloseCode`, whether it arrived as a close frame before `ready`,
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

## Capability

`SshCapabilityPlugin.transportCapabilities` reports `{gatewayTransport: true,
linkTransport: false}`. `src/native/sshCapability.ts` copies that into
`sshCapability.gatewayTransport` (false until the reply arrives, and false on
any unreadable reply); core's controller reads it. Slice 2 leaves the Android
boundary refusal in place regardless.

## Tests

JVM (`scripts/run-android-unit-gate.sh`): `GatewayTunnelTest`,
`GatewayTargetPolicyTest`, `GatewayTokenBrokerTest`,
`GoogleSyncSessionGatewayExchangeTest`, `GatewayPairingStoreTest`,
`GatewayPairingPluginContractTest`, `SshCapabilityPluginGatewayRefusalTest`,
and three end-to-end classes over `GatewayDialFixture` — a loopback gateway
relaying to a real SSH server (Apache MINA sshd, test-only):
`SshCapabilityPluginGatewayCloseCodeTest`, `SshCapabilityPluginGatewayPinTest`,
`SshCapabilityPluginGatewayTokenTest`, `SshCapabilityPluginGatewayAccountTest`,
`SshCapabilityPluginConnectDeadlineTest`. JS: `tests/unit/androidGatewayCapability.test.ts`.
