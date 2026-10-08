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
 * pairing flow, over an out-of-band channel): the SHA-256 fingerprint is
 * verified by the user against the host itself ({@code ssh-keygen -lf} on the
 * machine, or its own enrollment output), never adopted from synced metadata,
 * never from the gateway's {@code ready.ssh_host_key} advisory. This store
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
        final String keyHandleId;
        final long pairedAtEpochMs;

        Pairing(String accountSubject, String serverUrl, String deviceId, String fingerprintSha256,
                String keyHandleId, long pairedAtEpochMs) {
            this.accountSubject = accountSubject;
            this.serverUrl = serverUrl;
            this.deviceId = deviceId;
            this.fingerprintSha256 = fingerprintSha256;
            this.keyHandleId = keyHandleId;
            this.pairedAtEpochMs = pairedAtEpochMs;
        }

        public String accountSubject() { return accountSubject; }
        public String serverUrl() { return serverUrl; }
        public String deviceId() { return deviceId; }
        public String fingerprintSha256() { return fingerprintSha256; }
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
     * Create or replace the pairing for the triple, with the fingerprint
     * strictly validated and the key handle shape checked. Re-pairing the
     * same triple replaces the fingerprint and handle (a host key rotation is
     * an explicit re-pair, never an automatic update).
     */
    public synchronized Pairing pair(String accountSubject, String rawServerUrl, String deviceId,
            String fingerprint, String keyHandleId) throws IOException {
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
        String normalizedFingerprint = GatewayTargetPolicy.normalizeSha256Fingerprint(fingerprint);
        if (normalizedFingerprint == null) {
            throw new InvalidPairingException("Enter the host's SHA-256 fingerprint (ssh-keygen -lf output).");
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
                Pairing pairing = new Pairing(
                        row.getString("accountSubject"),
                        row.getString("serverUrl"),
                        row.getString("deviceId"),
                        row.getString("fingerprintSha256"),
                        row.getString("keyHandleId"),
                        row.getLong("pairedAtEpochMs"));
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
                rows.put(new JSONObject()
                        .put("accountSubject", pairing.accountSubject)
                        .put("serverUrl", pairing.serverUrl)
                        .put("deviceId", pairing.deviceId)
                        .put("fingerprintSha256", pairing.fingerprintSha256)
                        .put("keyHandleId", pairing.keyHandleId)
                        .put("pairedAtEpochMs", pairing.pairedAtEpochMs));
            }
            repository.write(rows.toString());
        } catch (JSONException error) {
            throw new IOException("The gateway pairing could not be serialized.");
        }
    }
}
