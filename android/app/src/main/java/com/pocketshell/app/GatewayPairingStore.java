package com.pocketshell.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The gateway pairing namespace (issue #3060): which host key fingerprint and
 * which local vault key handle belong to (account, gateway, device).
 *
 * <p>The namespace is deliberately wider than a hostname: direct-SSH trust is
 * keyed by host identity, and a gateway host's display hostname/port are
 * labels — two synced accounts can carry the same display endpoint for
 * different devices, so every lookup is keyed by the signed-in Google account
 * subject, the CANONICAL gateway origin, and the enrolled device id. A
 * sibling pin or key can never be borrowed across that triple.
 *
 * <p>A pairing is created ONLY by an explicit user action (the shared UI's
 * pairing flow, over an out-of-band channel): the pin is the line
 * {@code pocketshell gateway show --host-key} prints ON THE HOST (the exact
 * public key, matched byte for byte), or that key's SHA-256 fingerprint
 * ({@code ssh-keygen -lf}). It is never adopted from synced metadata, never
 * from the gateway's {@code ready.ssh_host_key} advisory, and never from the
 * device list's {@code ssh_host_key} (advisory too). This store
 * holds and checks pins; it never mints them. The associated key handle names
 * a key in the {@link CredentialHandleVault}; the vault remains the sole
 * holder of key bytes.
 */
public final class GatewayPairingStore {
    static final int MAX_PAIRINGS = 64;

    private final Repository repository;
    private final Clock clock;

    /** Crash-durable persistence. Production: SharedPreferences + commit. */
    public interface Repository {
        String read() throws IOException;

        void write(String json) throws IOException;
    }

    /** Injectable wall clock. */
    public interface Clock {
        long currentTimeMillis();
    }

    /** One stored pairing. Public, non-secret data only. */
    public static final class Pairing {
        final String accountSubject;
        final String serverUrl;
        final String deviceId;
        final String fingerprintSha256;
        /** The exact pinned public key when the user pasted the host-key
         * line; both null for a fingerprint-only pin. */
        final String hostKeyType;
        final String hostKeyB64;
        final String keyHandleId;
        final long pairedAtEpochMs;

        Pairing(String accountSubject, String serverUrl, String deviceId, String fingerprintSha256,
                String hostKeyType, String hostKeyB64, String keyHandleId, long pairedAtEpochMs) {
            this.accountSubject = accountSubject;
            this.serverUrl = serverUrl;
            this.deviceId = deviceId;
            this.fingerprintSha256 = fingerprintSha256;
            this.hostKeyType = hostKeyType;
            this.hostKeyB64 = hostKeyB64;
            this.keyHandleId = keyHandleId;
            this.pairedAtEpochMs = pairedAtEpochMs;
        }

        public String accountSubject() { return accountSubject; }
        public String serverUrl() { return serverUrl; }
        public String deviceId() { return deviceId; }
        public String fingerprintSha256() { return fingerprintSha256; }
        public String hostKeyType() { return hostKeyType; }
        public String hostKeyB64() { return hostKeyB64; }
        public String keyHandleId() { return keyHandleId; }
        public long pairedAtEpochMs() { return pairedAtEpochMs; }
    }

    /** The pairing data refused: fingerprint, URL, device id, or handle. */
    public static final class InvalidPairingException extends IOException {
        InvalidPairingException(String message) {
            super(message);
        }
    }

    public GatewayPairingStore(Repository repository, Clock clock) throws IOException {
        this.repository = repository;
        this.clock = clock;
        // A store we cannot parse refuses every dial (fail closed): the
        // callers treat any store error as "no usable pairing". It is never
        // silently emptied, which would read as unpaired-and-clean.
        repository.read();
    }

    /** Every pairing for the account, in store order. */
    public synchronized List<Pairing> list(String accountSubject) throws IOException {
        List<Pairing> result = new ArrayList<>();
        for (Pairing pairing : readAll()) {
            if (pairing.accountSubject.equals(accountSubject)) result.add(pairing);
        }
        return result;
    }

    /** The pairing for the exact (account, gateway origin, device) triple. */
    public synchronized Pairing lookup(String accountSubject, String serverUrl, String deviceId) throws IOException {
        for (Pairing pairing : readAll()) {
            if (pairing.accountSubject.equals(accountSubject) && pairing.serverUrl.equals(serverUrl)
                    && pairing.deviceId.equals(deviceId)) {
                return pairing;
            }
        }
        return null;
    }

    /**
     * Create or replace the pairing for the triple, with the pin strictly
     * validated and the key handle shape checked. {@code pin} is the
     * {@code gateway show --host-key} line (preferred: the exact key) or a
     * SHA-256 fingerprint. Re-pairing the same triple replaces the pin and
     * handle (a host key rotation is an explicit re-pair, never an automatic
     * update).
     */
    public synchronized Pairing pair(String accountSubject, String rawServerUrl, String deviceId,
            String pin, String keyHandleId) throws IOException {
        if (accountSubject == null || accountSubject.isEmpty() || accountSubject.length() > 255) {
            throw new InvalidPairingException("Pair the gateway host from a signed-in account.");
        }
        String serverUrl = GatewayTargetPolicy.normalizeServerUrl(rawServerUrl);
        if (serverUrl == null) {
            throw new InvalidPairingException("Enter the gateway address as a plain wss:// origin.");
        }
        if (!GatewayTargetPolicy.isValidDeviceId(deviceId == null ? "" : deviceId.trim())) {
            throw new InvalidPairingException("Enter the host's device id as the gateway enrolled it.");
        }
        GatewayTargetPolicy.HostKey hostKey = GatewayTargetPolicy.parseHostKeyLine(pin);
        String normalizedFingerprint = hostKey != null
                ? hostKey.fingerprintSha256
                : GatewayTargetPolicy.normalizeSha256Fingerprint(pin);
        if (normalizedFingerprint == null) {
            throw new InvalidPairingException(
                    "Paste the line `pocketshell gateway show --host-key` prints on the host, or its SHA256 fingerprint.");
        }
        if (keyHandleId == null || !keyHandleId.matches("[0-9a-fA-F-]{36}")) {
            throw new InvalidPairingException("Choose the SSH key this host pairs with.");
        }
        String trimmedDeviceId = deviceId.trim();
        List<Pairing> pairings = readAll();
        if (lookup(pairings, accountSubject, serverUrl, trimmedDeviceId) == null && pairings.size() >= MAX_PAIRINGS) {
            throw new InvalidPairingException("Too many paired hosts — remove one first.");
        }
        Pairing replacement = new Pairing(accountSubject, serverUrl, trimmedDeviceId, normalizedFingerprint,
                hostKey == null ? null : hostKey.keyType, hostKey == null ? null : hostKey.keyB64,
                keyHandleId, clock.currentTimeMillis());
        pairings.removeIf(existing -> existing.accountSubject.equals(accountSubject)
                && existing.serverUrl.equals(serverUrl) && existing.deviceId.equals(trimmedDeviceId));
        pairings.add(replacement);
        writeAll(pairings);
        return replacement;
    }

    /** Remove the pairing for the triple; true when one was removed. */
    public synchronized boolean remove(String accountSubject, String serverUrl, String deviceId) throws IOException {
        List<Pairing> pairings = readAll();
        String canonical = GatewayTargetPolicy.normalizeServerUrl(serverUrl);
        boolean removed = pairings.removeIf(existing -> existing.accountSubject.equals(accountSubject)
                && (canonical == null || existing.serverUrl.equals(canonical))
                && existing.deviceId.equals(deviceId));
        if (removed) writeAll(pairings);
        return removed;
    }

    private static Pairing lookup(List<Pairing> pairings, String accountSubject, String serverUrl, String deviceId) {
        for (Pairing pairing : pairings) {
            if (pairing.accountSubject.equals(accountSubject) && pairing.serverUrl.equals(serverUrl)
                    && pairing.deviceId.equals(deviceId)) {
                return pairing;
            }
        }
        return null;
    }

    private List<Pairing> readAll() throws IOException {
        String raw = repository.read();
        List<Pairing> pairings = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return pairings;
        JSONArray rows;
        try {
            rows = new JSONArray(raw);
        } catch (JSONException error) {
            throw new IOException("Saved gateway pairings are unreadable; no pairing was changed.");
        }
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row;
            try {
                row = rows.getJSONObject(index);
                String hostKeyType = row.has("hostKeyType") ? row.getString("hostKeyType") : null;
                String hostKeyB64 = row.has("hostKeyB64") ? row.getString("hostKeyB64") : null;
                Pairing pairing = new Pairing(
                        row.getString("accountSubject"),
                        row.getString("serverUrl"),
                        row.getString("deviceId"),
                        row.getString("fingerprintSha256"),
                        hostKeyType,
                        hostKeyB64,
                        row.getString("keyHandleId"),
                        row.getLong("pairedAtEpochMs"));
                if ((hostKeyType == null) != (hostKeyB64 == null)) {
                    throw new IOException("Saved gateway pairings are unreadable; no pairing was changed.");
                }
                if (hostKeyType != null) {
                    // A stored key must re-parse and agree with its stored
                    // fingerprint; a tampered or torn row is refused.
                    GatewayTargetPolicy.HostKey stored = GatewayTargetPolicy.parseHostKeyLine(hostKeyType + " " + hostKeyB64);
                    if (stored == null || !stored.fingerprintSha256.equals(pairing.fingerprintSha256)) {
                        throw new IOException("Saved gateway pairings are unreadable; no pairing was changed.");
                    }
                }
                if (!GatewayTargetPolicy.isValidDeviceId(pairing.deviceId)
                        || GatewayTargetPolicy.normalizeServerUrl(pairing.serverUrl) == null
                        || GatewayTargetPolicy.normalizeSha256Fingerprint(pairing.fingerprintSha256) == null
                        || !pairing.keyHandleId.matches("[0-9a-fA-F-]{36}")
                        || pairing.pairedAtEpochMs < 0
                        || !GatewayTargetPolicy.normalizeServerUrl(pairing.serverUrl).equals(pairing.serverUrl)) {
                    throw new IOException("Saved gateway pairings are unreadable; no pairing was changed.");
                }
                pairings.add(pairing);
            } catch (JSONException error) {
                throw new IOException("Saved gateway pairings are unreadable; no pairing was changed.");
            }
        }
        return pairings;
    }

    private void writeAll(List<Pairing> pairings) throws IOException {
        try {
            JSONArray rows = new JSONArray();
            for (Pairing pairing : pairings) {
                JSONObject row = new JSONObject()
                        .put("accountSubject", pairing.accountSubject)
                        .put("serverUrl", pairing.serverUrl)
                        .put("deviceId", pairing.deviceId)
                        .put("fingerprintSha256", pairing.fingerprintSha256)
                        .put("keyHandleId", pairing.keyHandleId)
                        .put("pairedAtEpochMs", pairing.pairedAtEpochMs);
                if (pairing.hostKeyType != null) {
                    row.put("hostKeyType", pairing.hostKeyType).put("hostKeyB64", pairing.hostKeyB64);
                }
                rows.put(row);
            }
            repository.write(rows.toString());
        } catch (JSONException error) {
            throw new IOException("The gateway pairing could not be serialized.");
        }
    }
}
