package com.pocketshell.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * "Remember on this device" for the account key vault (key-vault W4, design
 * section 3). What is remembered is the vault's data key (DEK) record that the
 * WebView hands over after an unlock — never the passphrase, never a private
 * key — sealed by a non-exportable Android Keystore AES-GCM key, so the stored
 * bytes are useless off this device and the record cannot be edited unseen.
 *
 * <p>The record is opaque here (the WebView checks its {@code vaultId} and
 * {@code epoch} and discards a stale one); this class only seals, stores,
 * opens and forgets it, one per account scope. A record that fails to open is
 * deleted and reported as absent: the user types the passphrase again.
 */
final class VaultDeviceKeyStore {
    /** Scope = the signed-in account's e-mail as the sync plugin reports it. */
    private static final Pattern SCOPE = Pattern.compile("[^\\u0000-\\u001f\\u007f]{1,254}");
    static final int MAX_RECORD_BYTES = 4096;
    private static final String PREFIX = "remembered:";

    interface Protector {
        byte[] seal(byte[] plaintext) throws Exception;
        byte[] open(byte[] sealed) throws Exception;
        /** Drop the device key itself (sign-out of the last account). */
        void destroy() throws Exception;
    }

    interface Repository {
        String get(String key);
        boolean put(String key, String value);
        boolean remove(String key);
        boolean clear();
    }

    private final Protector protector;
    private final Repository repository;

    VaultDeviceKeyStore(Protector protector, Repository repository) {
        this.protector = protector;
        this.repository = repository;
    }

    static String requireScope(String scope) throws IOException {
        if (scope == null || !SCOPE.matcher(scope).matches()) throw new IOException("The account for the remembered vault is invalid.");
        return scope.toLowerCase(java.util.Locale.ROOT);
    }

    synchronized void remember(String scope, String record) throws IOException {
        String key = PREFIX + requireScope(scope);
        if (record == null || record.isEmpty()) throw new IOException("The vault record to remember is empty.");
        byte[] plain = record.getBytes(StandardCharsets.UTF_8);
        try {
            if (plain.length > MAX_RECORD_BYTES) throw new IOException("The vault record to remember is too large.");
            byte[] sealed;
            try {
                sealed = protector.seal(plain);
            } catch (Exception error) {
                throw new IOException("This device could not protect the vault key.");
            }
            if (!repository.put(key, Base64.getEncoder().encodeToString(sealed))) {
                throw new IOException("This device could not save the vault key.");
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    /** The remembered record, or null when none is stored or it no longer opens (then it is deleted). */
    synchronized String recall(String scope) throws IOException {
        String key = PREFIX + requireScope(scope);
        String stored = repository.get(key);
        if (stored == null) return null;
        byte[] plain = null;
        try {
            plain = protector.open(Base64.getDecoder().decode(stored));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            repository.remove(key);
            return null;
        } finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    synchronized void forget(String scope) throws IOException {
        repository.remove(PREFIX + requireScope(scope));
    }

    /** Sign-out: every account's record and the device key go. */
    synchronized void forgetAll() {
        repository.clear();
        try {
            protector.destroy();
        } catch (Exception ignored) {
            // The records are gone; a key that could not be deleted seals nothing.
        }
    }
}
