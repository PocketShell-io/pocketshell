# Settings sync — optional Google sign-in

The earlier Android app let a user sign in with Google and sync selected host
settings in an encrypted payload. The JS-first rewrite currently contains the
portable payload policy only. Account sync is unavailable in this build: no
OAuth flow, secure credential storage, encryption adapter, or network service
is connected. The Settings screen makes no account request and changes no
account data.

This is the Android half of the feature the desktop client already ships
(`~/git/pocketshell-electron/docs/SYNC.md`). The backend is unchanged and
shared: API Gateway + Lambda + DynamoDB, deployed from
`aws-infra/sandbox/pocketshell-sync`, whose wire contract lives in that repo's
`docs/CLIENT-INTEGRATION.md`. Same API URL, same `main` slot, same
`{"hosts":[…]}` payload, same envelope — so one account works from the phone
and the laptop at once once platform integration is restored.

The sync round is core's `runSyncRound`
(`vendor/pocketshell-core/src/syncRound.ts`), the same loop the shared desktop
and web sync store runs; Android keeps no copy of it. Settings → Advanced →
Account sync is informational until native integration is available.

## JS-first rewrite status

Selection and retry orchestration belong to the shared core round. Android
does not own credentials, encryption, network requests, or persistent storage
yet. Its platform effects are injected at the boundary; there
is no production implementation in this rewrite.

The payload parser refuses malformed JSON, invalid entries, unsupported
`schemaVersion`, and unknown top-level fields before a replacement can be
assembled. An explicit `{"hosts":[]}` remains a valid payload, but the normal
sync flow refuses to upload an empty assembled host list. Clearing an account
needs its own explicit user action.

The earlier Android integration was blocked on an Android OAuth client. If
that flow is resumed, register an OAuth client of type **Android** (not
"Desktop" or "Web") in the same Google Cloud project as the desktop client's
`GOOGLE_CLIENT_ID`. It needs:

| Field | Value |
| --- | --- |
| Package name | `com.pocketshell.app` |
| Debug signing SHA-1 | `A0:4C:74:33:93:AD:23:1C:54:9E:CB:81:E7:43:FA:D7:D9:63:C4:17` |

(The debug certificate is the committed `debug.keystore`, shared by every
build on the dev box. A release-signing SHA-1 is a separate, later step.)

The legacy app then required both of these values in the same change:

1. Put the issued client ID in `SyncConfig.GOOGLE_ANDROID_CLIENT_ID`.
2. Update the `android:scheme` of the sync redirect `<intent-filter>` in
   `android/app/src/main/AndroidManifest.xml` to the reversed form
   (`com.googleusercontent.apps.<the-id-without-the-suffix>`).

Both, or neither. The legacy `SyncConfigTest` asked the real `PackageManager`
whether the manifest resolved the redirect URI the app requested.

## Why the desktop flow could not be ported as-is

The desktop client runs a one-shot loopback HTTP listener on `127.0.0.1` and
uses a "Desktop app" OAuth credential whose client secret it reads from a
dotfile. Neither works on Android:

- Google rejects `http://127.0.0.1` redirects for Android clients, and a
  mobile app cannot hold a loopback listener open across a browser handoff.
- A client secret embedded in an APK is not a secret — it ships to every
  device and falls out of a decompile.

So the Android side is a **public client with PKCE and no secret at all**:

| | desktop | Android |
| --- | --- | --- |
| client type | "Desktop app" | "Android" |
| client secret | required at the token endpoint | none |
| redirect | `http://127.0.0.1:<port>` listener | reversed-client-ID scheme, caught by `SyncOAuthRedirectActivity` |
| browser | `shell.openExternal` | Custom Tab (`androidx.browser`), never a WebView |
| tokens at rest | Electron `safeStorage` | `EncryptedSharedPreferences` over an Android Keystore master key |

The `state` nonce matters more here than it does on the desktop: on Android
the redirect arrives as an `Intent`, which any installed app can send, so the
nonce is what makes an injected authorization code unusable.

## Legacy encryption behavior

The rewrite does not yet contain a native encryption adapter. This section
records the earlier Android/Desktop wire contract for that future integration.

`SyncCrypto` is a byte-for-byte port: PBKDF2-SHA256, 600 000 iterations,
256-bit key over a fresh 16-byte salt; AES-256-GCM under a fresh 12-byte IV;
the 16-byte tag appended to the ciphertext; the envelope
`{v, kdf, iter, salt, iv, ct}` uploaded as the `data` string. The salt travels
in the header, which is what makes the same passphrase work on every device.

Two things the port had to add, both covered by `SyncCryptoTest`:

- **PBKDF2 is hand-rolled over explicit UTF-8 bytes.** The JCE's `PBEKeySpec`
  takes a `char[]` and leaves the char→byte conversion to the provider, and
  providers disagree. A non-ASCII passphrase would then derive a different key
  on Android than on the laptop, for exactly the users who could never guess
  why. Pinned to published known-answer vectors.
- **A real desktop-produced envelope is a committed fixture.** Decrypting it
  is the only assertion that proves the wire format still matches; a
  round-trip test would pass just as happily against a drifted format.

In the earlier app the passphrase lived only in the Settings screen's
composition memory for that session. The rewrite does not currently request
or store it.

## Payload compatibility and selection

The encrypted plaintext remains the versionless JSON object `{"hosts":[…]}`
in the existing `main` slot. Do not add `schemaVersion` to this current
format. The backend's integer version is an optimistic concurrency revision
for the whole slot; the encryption envelope's `v` versions encryption. Neither
versions the host payload.

Unknown JSON fields inside host entries are data. For each selected alias the
shared core uses explicitly present local fields when a local host exists and
carries remote-only fields through unchanged. If a selected host exists only
in the account, its remote entry is retained. Unticked aliases are omitted
from the replacement list, so selection still controls which entries leave
the device and which entries remain in the account.

The legacy selection ticks persisted per device. Account-only aliases
auto-select when the local host list lacks that alias; a locally available
alias the user unticked stays unticked. This lets a fresh device preserve its
account entries while keeping an explicit deletion as untick + sync.

Host entries contain connection metadata, never private key material. Fields
the local client does not model (`proxyJump`, forwards, `identityFile`, and
future extensions) must survive a sync round trip through shared core.

On a conflict, the adapter pulls the latest account again, parses it strictly,
recomputes the selected set through shared core, and retries up to three
conflicts. A future platform adapter must continue to check the backend's 8 KB
limit in bytes before upload.

Malformed account data must stop the upload; it must never be treated as an
empty list and silently replace the account. The shared vector fixture at
`vendor/pocketshell-core/tests/fixtures/settings-sync-vectors.json` is the
cross-client contract for serialization, unknown fields, selection deletion,
auto-selection, and strict parsing. Its `fixtureSchemaVersion` is test
metadata and is not sent to the backend.

## Legacy restore limitation

The earlier Android app preserved account-only host aliases during sync but
did not write them into its local `hosts` table. Its Room `HostEntity` needed
a `keyId` pointing at a private key that might not exist on this device, so
restoring a host required a separate key-selection step. The JS-first rewrite
has not implemented account restore or the sync selection UI yet.
