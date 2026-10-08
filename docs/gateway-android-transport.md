# Android gateway transport (native backend chunk)

Status: backend-complete, pending the remaining chunks listed at the bottom.
Issue: #3060. Contract: pocketshell-core `src/gatewayTransport.ts` at pin
`4096fc569e` (the behavioral specification this chunk mirrors natively).

This chunk adds the ANDROID NATIVE HALF of the gateway transport: a real SSH
engine (sshj 0.40.0, unchanged) running over a bounded gateway-WebSocket byte
transport, a dedicated native broker-token service, and the pairing storage
namespace. It owns no UI: the existing App/shared gateway guards
(#3059, uncommitted in the native worktree, inherited here as exact blobs)
still refuse gateway dials before any of this code runs, until the separate
UI/resolver integration lands.

## The dial, end to end

1. (future) The resolver hands `SshCapability.connect` a target carrying a
   validated `gateway` object alongside the key-handle credential.
2. `resolveGatewayDialPlan` (SshCapabilityPlugin) — every refusal is typed and
   happens before any effect: no engine, no broker call, no socket. It
   refuses a JS-supplied host-key pin (the pin lives only in the pairing
   store), a conflicting `link`+`gateway` marker, non-key-handle
   credentials (gateway is key-only), malformed targets, a signed-out phone,
   an unpaired target, a pairing bound to a different key, and a key that no
   longer resolves from the vault.
3. Broker mint (inside the owned connect attempt): one empty-body
   `POST /gateway/token` at the CONFIGURED sync base through
   `GoogleSyncSession.gatewayTokenExchange` — the same Credential Manager
   sign-in provider, encrypted token store, transport (no redirects, 15s
   deadlines, 64 KiB body cap) and test seams as settings sync. The routing
   token never crosses the Capacitor bridge: JS has no method that returns
   one, and the exchange answer exposes only a bearer-echo check.
4. `GatewayTunnel.open` — dial `wss://<canonical>/api/v1/hosts/<device>/ssh`,
   send the one auth TEXT frame (`{type:"auth",v:1,token,device_id}`), await
   exactly one strict v1 `ready`/`error` TEXT frame. `ready.ssh_host_key` is
   read nowhere. Then the connection is an ordered binary byte stream.
5. sshj runs its NORMAL handshake over the tunnel socket: the plugin installs
   a `javax.net.SocketFactory` that hands back the tunnel's ALREADY-CONNECTED
   socket, so `SocketClient.connect(String,int)` (0.40.0 bytecode verified)
   skips DNS and TCP entirely — the display hostname/port are labels only.
   The `PinVerifier` compares the pairing's independently provisioned
   SHA-256 pin against the key the host ACTUALLY presents — before userauth.
6. One whole-connect deadline (default 60s, bounds 5–90s) covers broker,
   dial, ready, KEX and userauth; a watchdog cancels the attempt at it, and
   `ConnectAttempt` owns the tunnel so a cancel at any phase — dialing
   included — joins the WebSocket and cannot register a stale winner.

## Library decision: Java-WebSocket 1.5.7

Evaluated against "bounded BEFORE allocation + verified TLS trust":

- **OkHttp 4.12** (already on the classpath): excellent TLS
  (OkHostnameVerifier, SNI, platform trust) and a public `queueSize()` for
  write backpressure — but its WebSocketReader assembles each message with
  NO size cap, so a hostile gateway can drive an unbounded allocation before
  any callback sees a byte. Fails the hard requirement.
- **nv-websocket-client 2.14**: `WebSocketInputStream.readFrame` allocates
  `new byte[payloadLength]` from the declared header length with only an
  `Integer.MAX_VALUE` check. Fails worse than OkHttp.
- **Java-WebSocket 1.5.7** (chosen): `Draft_6455` checks the DECLARED frame
  length against `maxFrameSize` BEFORE `ByteBuffer.allocate`
  (`translateSingleFrameCheckLengthLimit`), bounds the total accumulated
  fragment buffer the same way (`checkBufferLimit`), and closes with 1009 on
  violation. The draft is pinned at the protocol message cap (1 MiB + 64 KiB,
  core `GATEWAY_MAX_WS_MESSAGE_BYTES`).

Known limitations (documented honestly):

- Write backpressure observes `WebSocketImpl.outQueue` (a public field in
  1.5.7), blocking the writer above a 1 MiB pending-bytes cap with a 30s
  stall deadline. It is an observation of a public field, not a curated API.
- Read backpressure works by blocking the library's READER thread on the
  bounded inbound queue (4 MiB / 64 frames), which is why the library's
  60s ping/pong connection-lost checker is DISABLED — it would kill a
  healthy stream whose reader is legitimately blocked. Liveness stays with
  the SSH layer, as in direct dials.
- The gateway's free-text close REASON and error-frame message are never
  surfaced (fixed sentences keyed on codes only).
- TLS: platform default CA trust via `SSLSocketFactory.getDefault()`, SNI and
  endpoint identification set explicitly (`onSetSSLParameters`), plus a
  belt-and-braces post-handshake `HostnameVerifier` check on the negotiated
  session — Android SSLSocket support for endpoint identification has varied
  by release, so nothing depends on it alone.

`ws://` is refused unless BOTH the explicit test-only constructor flag is set
AND the host is loopback; the shipped plugin never sets the flag, so no synced
target or stored record can produce a plaintext dial.

## Pairing namespace and broker

`GatewayPairingStore` keys every record by (current Google account subject,
canonical `wss://` origin, device id). A hostname/port label can never borrow
a sibling pin or key. Pairings are created ONLY by the explicit `pair()` of
the `GatewayPairing` plugin (registered in MainActivity; narrow API:
`currentAccount` / `list` / `pair` / `remove`), carrying an out-of-band
SHA-256 fingerprint and a local key-vault handle. Nothing derives a pairing
from synced metadata or the ready advisory. An unreadable pairing store
refuses dials; it is never silently emptied.

## What is NOT done here (honest boundaries)

- The App.vue / androidApi / hostStore / androidSync guards (exact inherited
  blobs) still refuse gateway dials — correct until the UI/resolver chunk.
  **New API the integration will need**: core `SshHostTarget.gateway?
  : GatewayTransportTarget` (connect-level, mirroring `HostEntry.gateway`),
  the resolver returning a gateway host with its paired key handle, and the
  shared UI pairing flow over `GatewayPairing` (JS side already typed in
  `src/native/gatewayPairing.ts`).
- The desktop transport/broker/pair-store chunk, and the portable contract
  worker's core descendant, are separate owners.
- REAL end-to-end proof (accepted Go gateway + enrolled home agent + loopback
  sshd with no direct route + installed app exec/PTY/SFTP + no-direct-route
  refusal + wrong-pin/unknown-key behavior) is PENDING: the root Go accepted
  marker is absent, and this chunk deliberately shipped no emulator/device
  work. Nothing here substitutes old sources for that proof; the JVM tests
  exercise the real byte transport and the real refusal order only.
