# Settings sync — optional Google sign-in

Settings → Account & sync (also reachable from Settings → Advanced) lets a
user sign in with Google and sync selected hosts with PocketShell on the
desktop and the web (issue #3020). Signed out, the app behaves exactly as if
the feature did not exist and sends no account request.

The backend is the shared `pocketshell-sync` stack (API Gateway + Lambda +
DynamoDB, deployed from `aws-infra/sandbox/pocketshell-sync`, wire contract in
that repo's `docs/CLIENT-INTEGRATION.md`). Same API URL, same `main` slot, same
`{"hosts":[…]}` payload and same encryption envelope as the desktop and web
clients, so one account works from all three.

## Who does what on Android

| Layer | Owns |
| --- | --- |
| `GoogleSyncPlugin.java` / `GoogleSyncSession.java` | Credential Manager sign-in, the Google ID token in `EncryptedSharedPreferences` (`pocketshell-sync-auth`, Keystore master key), the authenticated HTTPS call to the fixed `/settings/{slot}` and `/me` routes, silent renewal of an expired token, one retry after a 401 |
| `src/sync/syncCrypto.ts` | PBKDF2-SHA256 (600 000 rounds) + AES-256-GCM envelope in the WebView (WebCrypto), byte-compatible with the desktop |
| `src/sync/androidSync.ts` | the sync API's status codes, the 8 KB limit, the cached account copy, and the phone's contribution to the round (only `name`, `hostname`, `port`, `user`) |
| core `runSyncRound` | pull → auto-select → merge → push → conflict retry (shared with desktop and web) |
| `AccountSyncScreen.vue` | sign in/out, status, passphrase, host selection, Sync now |

The ID token never crosses the Capacitor bridge: JS sees signed-in state, the
account email, and the sync API's status and body. Plugin rejections carry
fixed user-facing messages, never a token or a transport message. The sync
passphrase lives only in the Account screen's memory and never reaches native
code or storage. Sign-out deletes the token, clears Credential Manager's
authorized-account state and removes the cached account copy.

The same adapter serves the shared app's `api.sync` group
(`src/platform/android/androidApi.ts`), so the shared Account UI gets it when
the shared shell mounts it.

Hosts from the account are cached on the phone (metadata only) and listed on
the home screen as "Synced host from your account"; picking one fills the
connection form. The user still chooses an SSH key on the phone — key
material is never synced.

## OAuth configuration for real sign-in

Credential Manager returns an ID token whose audience is the
`serverClientId` it was asked for. The phone asks for the PocketShell **Web
application** client `1035162854462-kkqius5o2ni136ed6l58iig5pdpeh4u6` (the
pocketshell-web client), which the deployed sync stack already accepts
(`GoogleWebClientId`). No backend change is needed.

Google additionally requires an OAuth client of type **Android** in the same
Cloud project (1035162854462) for every package + signing certificate that
signs in. Until it exists, the Account screen reports "Google sign-in is not
available for <package>". To enable the 0.6.0 preview, create in Google Cloud
Console → APIs & Services → Credentials → Create OAuth client ID → Android:

| Field | Value |
| --- | --- |
| Package name | `com.pocketshell.app.preview` |
| SHA-1 certificate fingerprint | `63:48:A0:14:94:E0:06:D5:05:0E:C6:FC:61:7D:BC:6E:6E:16:E1:25` |

That SHA-1 is the committed `debug.keystore` (alias `androiddebugkey`), which
signs every debug and preview build; verified against the published
`pocketshell-0.6.0-preview-cebe73f.apk` with `apksigner verify --print-certs`.
The Android client has no secret and nothing needs to change in the app.
The OAuth consent screen must list the test user if the project is in
Testing mode, and the sync stack's `AllowedEmails` must include the account.

Other packages need their own Android client entry (same project, same
form): `com.pocketshell.app` for an unsuffixed debug install and, before a
release ships sync, `com.pocketshell.app` with the release keystore's SHA-1.

The 0.5.x client cannot be reused: 0.5.x never had a registered Android
client. Its `SyncConfig.GOOGLE_ANDROID_CLIENT_ID` was the placeholder
`unconfigured-android-client-id`, and its documented SHA-1
(`A0:4C:74:…`) came from a per-machine `~/.android/debug.keystore`, not the
committed keystore that signs current builds. 0.5.x also used a Custom Tab +
PKCE redirect; the rewrite uses Credential Manager, which needs no redirect
scheme in the manifest.

## Testing

`tests/unit/syncCrypto.test.ts` decrypts a desktop-written envelope and pins
the full-strength parameters; `tests/unit/androidSync.test.ts` covers the
round through the adapter (desktop-only fields preserved, conflict re-base,
refusals, sign-out). `GoogleSyncSessionTest` (native unit gate) covers token
storage, renewal, the route allow-list and redaction. The packaged journey
`AccountSyncJourneyTest` (`scripts/connected-js-account-sync.sh`, CI lane
`account-sync`) fakes only Google's account chooser and the sync API,
in-process through `GoogleSyncEnvironment`, and checks the encrypted token
file, WebView state and logcat for the token.

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

Selection ticks persist per device (the shared settings store's `syncSelectedHosts`). Account-only aliases
auto-select when the local host list lacks that alias; a locally available
alias the user unticked stays unticked. This lets a fresh device preserve its
account entries while keeping an explicit deletion as untick + sync.

Host entries contain connection metadata, never private key material. Fields
the local client does not model (`proxyJump`, forwards, `identityFile`, and
future extensions) must survive a sync round trip through shared core.

On a conflict, the round pulls the latest account again, parses it strictly,
recomputes the selected set through shared core, and retries up to three
conflicts. The adapter checks the backend's 8 KB limit in bytes before upload.

Malformed account data must stop the upload; it must never be treated as an
empty list and silently replace the account. The shared vector fixture at
`vendor/pocketshell-core/tests/fixtures/settings-sync-vectors.json` is the
cross-client contract for serialization, unknown fields, selection deletion,
auto-selection, and strict parsing. Its `fixtureSchemaVersion` is test
metadata and is not sent to the backend.
