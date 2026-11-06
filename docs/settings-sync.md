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
| `src/sync/androidSync.ts` | the sync API's status codes, the 8 KB limit, the in-memory account copy, the Android selection rule, and the phone's contribution to every upload (only `name`, `hostname`, `port`, `user`) |
| core `runSyncRound` | pull → auto-select → merge → push → conflict retry (shared with desktop and web) |
| `AccountSyncScreen.vue` | sign in/out, status, passphrase, host selection, Sync now |

The ID token never crosses the Capacitor bridge: JS sees signed-in state, the
account email, and the sync API's status and body. Plugin rejections carry
fixed user-facing messages, never a token or a transport message. The sync
passphrase lives only in the Account screen's memory and never reaches native
code or storage. Sign-out deletes the token, clears Credential Manager's
authorized-account state and forgets the in-memory account copy.

The same adapter serves the shared app's `api.sync` group
(`src/platform/android/androidApi.ts`), so the shared Account UI gets it when
the shared shell mounts it.

Hosts from the account are listed on the home screen as "Synced host from
your account"; picking one fills the connection form. The user still chooses
an SSH key on the phone — key material is never synced. The decrypted account
copy is kept in memory only (#3026): after a restart the list is empty until
Account & sync → **Show account hosts** unlocks it again with the passphrase
(nothing is uploaded).

On the shared app (#3063) the host picker's header account button opens the
shared Account & sync screen; once it is unlocked the picker lists the
account's hosts under "From your account". Tapping one that this phone has no
key for asks for the phone's key and saves it as a phone host. A keyless phone
host gets the key attached in place only when the dial is for that exact host
(its name, or the same address *and* user); a host that merely shares the
address under another user is kept separate. An account host carrying a `gateway` marker (any value, also with `link`)
or a `link` marker is listed but refused when tapped, before any key prompt or
socket: Android has neither transport yet, and core's #3059 contract forbids
dialling such a host as plain SSH. Sync keeps the markers verbatim.

Selection on Android is core's one tick rule, the same on every client
(#3072, core docs/SYNC.md): every account host stays ticked unless the user
explicitly unticked it, so an untouched Sync now never removes an account
host. An untick is saved in the shared settings (`syncUntickedHosts`, aliases
only) and is one-shot: the push that removes the host spends it, so a host
another device adds back later is kept. A failed push keeps it pending.
Signing out clears the saved unticks; forgetting an untick can only keep a
host in the account, never remove one. The #3063 build's
`pocketshell.sync.known-aliases.v1` list is deleted on upgrade, so a host
the phone had unticked under it comes back ticked.
Every upload carries only the fields the phone owns (name, hostname, port,
user) over the account's entry, so desktop-only fields
(`identityFile`, `proxyJump`, forwards, unknown fields) survive a phone sync.

## OAuth configuration for real sign-in

Credential Manager returns an ID token whose audience is the
`serverClientId` it was asked for. The phone asks for the PocketShell **Web
application** client `1035162854462-kkqius5o2ni136ed6l58iig5pdpeh4u6` (the
pocketshell-web client), which the deployed sync stack already accepts
(`GoogleWebClientId`). No backend change is needed.

Native sign-in also requires an OAuth client of type Android in the same
Google Cloud project (`pocketshell-508120`, project number 1035162854462),
registered for the installed package and signing-certificate SHA-1. The
registered private APK identity is:

| Field | Value |
| --- | --- |
| Name | PocketShell Android private APK |
| Package | `com.pocketshell.app` |
| SHA-1 | `63:48:A0:14:94:E0:06:D5:05:0E:C6:FC:61:7D:BC:6E:6E:16:E1:25` |
| Android client | `1035162854462-hfgrvtlgs915lt9merdmug4db39qocqr.apps.googleusercontent.com` |

The Android client establishes the package/signing identity. It does not
replace `GoogleSyncConfig.SERVER_CLIENT_ID`: the requested ID token's audience
remains the approved Web application client that the backend accepts.

`android/app/build.gradle` signs debug APKs with this repository's committed
`debug.keystore` (alias `androiddebugkey`). Keep that signing identity stable;
a machine-generated `~/.android/debug.keystore` has a different fingerprint.
Verify the actual APK with `apksigner verify --print-certs`, not a keystore
filename or the displayed application version. Preview/test package suffixes
need their own Android OAuth registration with the same certificate. Keep
registrations for other supported package/certificate pairs intact when adding
one. Production signing requires its own registered certificate.

The app's Sign in with Google button opens Android Credential Manager's
account picker directly. There is no provider-choice dialog, browser fallback,
loopback listener, OAuth redirect scheme or desktop-client token relay in the
Android sign-in path. A successful native credential removes only the retired
browser provider's encrypted refresh-token preferences; the ID-token store,
account vault, remembered vault keys, SSH keys and host pairings are preserved.

Google's consent configuration must permit the account, and the sync stack's
`AllowedEmails` must include it. Some Play Services versions mask an incorrect
package/certificate registration as cancellation, so diagnose that registration
when selection immediately returns unsigned-in; do not replace native sign-in
with another authentication protocol.

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

## Trusted servers on Android

The shared Android picker uses the account vault's authenticated trusted-host
records for its normal server list. Google sign-in is followed by one vault
unlock. Legacy settings host rows and local phone keys do not establish trust
for this list. Explicit settings sync remains available separately.

The native `GoogleSync.accountId` verifies `/me` through the backend authorizer
and returns only `google:<sub>`. The Google bearer stays native. Native
`GET /settings` returns bounded slot metadata to core's vault transport; the
vault decrypts and validates the `th-*` descriptor, managed key binding, current
vault ID/epoch and revision before a row can connect.

Android persists descriptor revision floors in Keystore-encrypted preferences,
scoped by account, vault and target slot and excluded from backup/transfer.
A trusted pairing stores the exact SSH host key and a vault key ID independently
of local phone key UUIDs. Each held native key is bound to its account, vault,
epoch and key ID. Explicit dials revalidate the descriptor; native reconnects
also refuse pairings older than an observed protected revision floor. Locked,
missing, revoked, tampered or mismatched trust never falls back to a phone key.

After connection, core resumes an existing workspace or opens a home shell.
Android resolves the directory through SFTP and creates the real session through
the existing connection controller and host CLI. This uses the same native
SSH transport and pin checks as an existing session.
