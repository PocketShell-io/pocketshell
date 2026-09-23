# Installed Android data map

This is the source inventory for the one-time installed-data reader. It was
checked against the legacy Android owners on `main` at
`789bce7f59dc0d4f08aede4c76c03affdb398903` and the archived Room schemas
16–22 in this directory. The schemas are immutable evidence; this file records
where private user data lives and the constraints an importer must honor. It
does not claim that migration is implemented or that an upgrade has passed.

## Import rules

- Keep the existing install identity (`com.pocketshell.app`) and signing
  identity. A signed 0.5.x update retains access to the same private sandbox;
  a different signature or package cannot read that install's data.
- Open legacy stores read-only. Do not call a legacy owner that repairs bad
  data by deleting it, do not run Room migrations or create a replacement
  database over the source, and do not remove source files after copying.
- Validate the complete source before publishing imported data. An unknown
  Room version, malformed preference value, invalid attachment record,
  missing referenced private file, or failed decrypt must leave the source
  intact and produce a visible, actionable migration error. A partial copy
  must not be marked complete.
- Preserve primary IDs, host/session associations, ordering, timestamps,
  delivery state, SSH key bytes, workspace identity and host trust pins.
  Host-key trust is security state: never silently drop or reconstruct a pin.
- Keep OAuth tokens, API keys and SSH private-key bytes out of logs and general
  JavaScript storage. Read or retain these only through a native secure-storage
  boundary. A ciphertext copy alone is not a usable credential import.

## Room database

The database filename is `pocketshell.db`; the current archived schema is Room
version 22 with identity hash `998a588a2d2f383454698ea11820fca9`. The source
schema files are archived at
[`room-schemas/com.pocketshell.core.storage.AppDatabase/`](room-schemas/com.pocketshell.core.storage.AppDatabase/)
and their bytes are checked by that directory's `SHA256SUMS`.

| Table | Schema 22 columns | Import notes |
|---|---|---|
| `hosts` | `id`, `name`, `hostname`, `port`, `username`, `keyId`, `maxAutoPort`, `skipPortsBelow`, `scanIntervalSec`, `enabled`, `createdAt`, `lastConnectedAt`, `lastBootstrapAt`, `pocketshellInstalled`, `pocketshellLastDetectedAt`, `pocketshellCliVersion`, `pocketshellExpectedCliVersion`, `pocketshellVersionCompatible`, `pocketshellDaemonRunning`, `pocketshellDaemonEnabled`, `usageCommandOverride`, `treeIdentity`, `trustedHostKeyAlgorithm`, `trustedHostKeySha256` | Preserve ID, `treeIdentity`, and both trust fields exactly. `keyId` references `ssh_keys.id` with delete cascade. Nullable detection/connection fields stay nullable. |
| `ssh_keys` | `id`, `name`, `privateKeyPath`, `fingerprint`, `hasPassphrase`, `createdAt` | Preserve every metadata row and ID. Keep the referenced private file in place and give JS only the key ID and source hash; `hasPassphrase` does not contain the passphrase. |
| `port_remappings` | `id`, `hostId`, `remotePort`, `localPort`, `name` | Preserve row IDs, host association and the unique `(hostId, remotePort)` mapping. |
| `port_usage` | `hostId`, `remotePort`, `clickCount`, `totalBytes`, `lastUsedAt` | Composite primary key `(hostId, remotePort)`; preserve counts and last-use time. |
| `project_roots` | `id`, `hostId`, `label`, `path`, `createdAt`, `sortOrder` | Preserve row IDs and order. `sortOrder` was added in v22 and backfilled from `createdAt`. |
| `snippets` | `id`, `hostId`, nullable `label`, `body`, `kind` | Preserve nullable labels, exact bodies, kind, IDs and host association. |
| `ai_api_call_log` | `id`, `timestampMillis`, `provider`, `feature`, `inputUnits`, `outputUnits`, `unitCostUsdMillicents`, `computedCostUsdMillicents`, nullable `metadataJson` | Preserve usage/cost history and nullable metadata without parsing and reserializing it. |
| `pending_transcriptions` | `id`, `audioPath`, `recordingTimestampMs`, `destinationContext`, `retryCount`, nullable `lastErrorMessage`, `audioByteSize`, `createdAtMs` | Preserve the row and matching audio bytes. A missing or unreadable audio file is a visible per-item failure, not a reason to discard the queue row. |
| `command_templates` | `id`, `hostId`, `label`, `commands` | Preserve IDs, order as read, exact command text and host association. |
| `sent_messages` | `id`, `sessionKey`, `body`, `sentAtMs`, `delivered` | Preserve history including undelivered entries. `sessionKey` is a string association (currently `<hostId>/<sessionName>`), not a foreign key. |

Room's `room_master_table` is bookkeeping, not user data. Host-owned live
sessions are not in this database and are not migrated.

Schema evolution in the archived versions:

- Version 16 included obsolete `sessions` and `agent_sessions` tables; 16→17
  drops them. Version 17 is the first shape without those tables.
- Version 18 adds `hosts.treeIdentity`.
- Version 19 adds `hosts.trustedHostKeyAlgorithm` and
  `hosts.trustedHostKeySha256`.
- Version 20 adds `sent_messages`.
- Version 21 rebuilds `hosts` without obsolete `tmuxInstalled` and adds
  `port_remappings.name`.
- Version 22 adds `project_roots.sortOrder`, initialized from `createdAt`.

Accept only known versions 16–22 with the expected table/column shape. Do not
open the file through Room during import: that can mutate it by applying a
migration. A missing database is an empty source; a present but malformed or
unsupported database is an error.

## Files in the app-private sandbox

| Location | Contents and rules |
|---|---|
| `files/ssh-keys/` | SSH private-key material named by `ssh_keys.privateKeyPath`. Keep bytes at the original app-private path. JS stores only the Room key ID and a SHA-256 change check; the native SSH capability resolves that ID back to the exact Room row, validates the canonical key path and content hash, and reads the bytes into the native sshj authentication path. A missing, changed, or unsafe path fails the connection visibly. Some PEM files require a passphrase that was never stored; request it for the connection and never persist or log it. |
| `files/voice-pending/<uuid>.wav` | Audio for `pending_transcriptions`. Copy as bytes and validate each row/file pair. Do not let startup cleanup delete unmatched source files before import has completed. |
| `files/voice-exports/` | User-created audio exports. Preserve them as files if this location is still present; no Room row describes them. |
| `files/crash-reports/` | User-visible crash reports. Preserve readable reports; malformed individual reports must not block unrelated data. |
| `files/diagnostics/pocketshell-diagnostics.jsonl` | Diagnostic history. Preserve source lines; do not parse-and-rewrite before the migration state is durable. |

## SharedPreferences stores

All names below are private Android preferences files. Read raw entries without
calling owners that silently drop invalid values. Preserve unknown keys and
types in the migration envelope until a destination owner explicitly handles
them.

| File | Durable keys / shape | Migration notes |
|---|---|---|
| `composer_drafts` | A bare `<sessionKey>` string holds draft text; `@att/<sessionKey>` holds newline-separated `path<TAB>name<TAB>mime` attachment rows with `\\`, `\t`, and `\n` escapes; `@migrated/<legacyKey>` is a Boolean copy marker. | Parse escapes strictly and fail the affected import on malformed rows. Preserve keys and markers; do not use the legacy decoder that drops malformed records. |
| `next_settings` | `terminal_text_size_px` (int), `voice_language` (string), `voice_silence_seconds` (float), `usage_warn_threshold_percent` (int), `background_grace_millis` (long), `agent_submit_enter_delay_ms` (int), nullable `default_host_id` (long), nullable `last_workspace_path`/`last_session_id` (strings), `show_common_keys` and `reconnect_when_return` (Booleans). | Preserve every present key and type. `SettingsRepository` can delete this file after parse failure and drops malformed typed keys; do not use it for migration. |
| `app_settings` | Legacy settings file; `voice_silence_seconds` is the only fallback key read by the current `SettingsRepository`. | Preserve opaque additional keys; do not treat the current fallback as proof other legacy values are disposable. |
| `workspace_order` | String entries named `host-root-<hostId>`; values encode ordered roots using `:identity:`, `:raw:`, or `:canonical:` prefixes. | Preserve the entire key and exact value so paths and duplicate/order semantics remain available to the new owner. |
| `port_forward_panel` | `show_all_ports` Boolean. | Preserve if present. |
| `next_sync_selection` | `sync_selected_hosts` JSON array of account aliases; array order matters. | Parse strictly. Invalid JSON or item shape is a visible failure; do not turn corruption into an empty selection because an empty selection can replace remote sync data. |
| `update_check` | Release-check bookkeeping. | Preserve opaquely or leave untouched; it is not user-authored content. |

The current #2861 settings-store draft uses localStorage key
`pocketshell.js.settings.v1` with `themeChoice`, `terminalFontSize`, and
`backgroundGraceMs`. This old app has no persisted theme choice. When the
reader is implemented, copy `terminal_text_size_px` and
`background_grace_millis` only into absent JS fields; preserve any values the
new app has already written, including `themeChoice`. Keep other old settings
in the migration envelope until their destination features define a mapping.

## Encrypted preferences and credentials

These files use AndroidX `EncryptedSharedPreferences`: AES256-SIV preference
key encryption, AES256-GCM values, and an AES256-GCM Android Keystore master
key. Their XML ciphertext and keysets are not credentials and cannot be
decoded by copying files. They can only be opened with the matching app
identity, intact Keystore state, and compatible crypto implementation. The
legacy owners have repair paths that delete the encrypted file and shared
keysets after an open failure; do not call those paths during import.

| Encrypted preferences file | Entry and payload | Handling |
|---|---|---|
| `pocketshell-sync-auth` | `google_sync_auth`, JSON fields `sub`, `email`, `idToken`, `refreshToken`, `obtainedAtMs`, `expiresInS` | Check decryptability with the original Keystore key. Keep the file unchanged and plaintext out of JS storage/logs. The rewrite has no native consumer yet, so a present file keeps migration status `partial`. |
| `pocketshell-voice-secrets` | `openai_api_key` | Keep the file unchanged and plaintext out of JS storage/logs. The rewrite has no native consumer yet, so a present file keeps migration status `partial`. |
| `pocketshell-assistant-secrets` | `assistant_provider`; provider keys `openai_api_key`, `anthropic_api_key`, `zai_api_key`; provider settings `openai_base_url`/`openai_model`, `anthropic_base_url`/`anthropic_model`, `zai_base_url`/`zai_model` | Keep the file unchanged and plaintext out of JS storage/logs. Provider values are not surfaced until a native secure-secret contract exists; a present file keeps migration status `partial`. |

The three files have independent encrypted preference contents. Each file also
contains AndroidX keyset entries under the same two reserved preference names;
the entries are inside that file, not separate shared preference files. A failure to
read one credential store must not trigger cleanup or prevent import of
unrelated categories. Present encrypted stores remain untouched. The importer
records the other data and marks the overall result `partial`, whether the
encrypted values were readable or unavailable. A successful decrypt proves the
legacy value remains readable; it does not prove the JS app can use it.

## Explicitly outside this inventory

The `update_check` file is bookkeeping. Remote hosts, live aplexer sessions,
server-side agent logs, and remote files are not installed private data. The
reader must not infer or fabricate them from a host list. The target
representation for encrypted credentials, crash/diagnostic files, audio
exports, and legacy settings not present in #2861 remains a follow-up contract;
until a destination owner uses them, retain their source unchanged and report
their status visibly.
