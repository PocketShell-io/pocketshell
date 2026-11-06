package com.pocketshell.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;

/** Device-protected account/vault-scoped monotonic floors, never stored in sync or WebView storage. */
final class TrustedHostRevisions {
    // All instances share one root JSON record, even when repositories/contexts differ.
    // Plugin shutdown leaves queued workers alive, so an instance lock is insufficient.
    private static final Object PROCESS_STORAGE_LOCK = new Object();
    private final GatewayPairingStore.Repository repository;
    TrustedHostRevisions(GatewayPairingStore.Repository repository) { this.repository = repository; }
    private static void validate(String account, String vault) throws IOException {
        if (account == null || !account.matches("google:[0-9A-Za-z_-]{1,128}") || vault == null || !vault.matches("[A-Za-z0-9_-]{22}")) throw new IOException("Invalid trust namespace.");
    }
    static long positive(JSONObject row, String field) throws IOException {
        Object value = row.opt(field);
        if (!(value instanceof Number)) throw new IOException("Invalid trust revision.");
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < 1 || number > 9007199254740991L || number != Math.rint(number)) throw new IOException("Invalid trust revision.");
        return ((Number) value).longValue();
    }
    private String namespace(String account, String vault) { return account + ":" + vault; }
    JSONObject load(String account, String vault) throws Exception {
        synchronized (PROCESS_STORAGE_LOCK) {
            return loadLocked(account, vault);
        }
    }
    private JSONObject loadLocked(String account, String vault) throws Exception {
        validate(account, vault);
        String raw = repository.read();
        JSONObject root = raw == null ? new JSONObject() : new JSONObject(raw);
        JSONObject result = root.optJSONObject(namespace(account, vault));
        if (root.has(namespace(account, vault)) && result == null) throw new IOException("Unreadable trust floors.");
        if (result == null) return new JSONObject();
        java.util.Iterator<String> keys = result.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.matches("th-[A-Za-z0-9_-]{22}") || positive(result, key) < 1) throw new IOException("Unreadable trust floor.");
        }
        return result;
    }
    void save(String account, String vault, String slot, long revision) throws Exception {
        synchronized (PROCESS_STORAGE_LOCK) {
            saveLocked(account, vault, slot, revision);
        }
    }
    private void saveLocked(String account, String vault, String slot, long revision) throws Exception {
        if (slot == null || !slot.matches("th-[A-Za-z0-9_-]{22}") || (revision < 1 || revision > 9007199254740991L)) throw new IOException("Invalid trust floor.");
        JSONObject floors = loadLocked(account, vault);
        if (revision < floors.optLong(slot, 0)) throw new IOException("Trusted host rollback refused.");
        floors.put(slot, revision);
        String raw = repository.read();
        JSONObject root = raw == null ? new JSONObject() : new JSONObject(raw);
        root.put(namespace(account, vault), floors);
        repository.write(root.toString());
    }
    static String slot(String server, String device) throws Exception {
        if (server == null || !server.equals(GatewayTargetPolicy.normalizeServerUrl(server)) || !GatewayTargetPolicy.isValidDeviceId(device)) throw new IOException("Invalid target.");
        String aad = new JSONArray().put(server).put(device).toString().replace("\\/", "/");
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(aad.getBytes(StandardCharsets.UTF_8));
        return "th-" + Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(hash, 16));
    }
}
