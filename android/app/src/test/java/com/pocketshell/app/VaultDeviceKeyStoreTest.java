package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.Before;
import org.junit.Test;

/**
 * Key-vault W4 "Remember on this device": the DEK record is stored only
 * sealed by the device key, per account, and a record that no longer opens
 * is dropped (the user types the passphrase again).
 */
public final class VaultDeviceKeyStoreTest {
    private static final String RECORD = "{\"vaultId\":\"v1\",\"epoch\":1,\"dek\":\"secret-dek\"}";

    private MemoryRepository repository;
    private AesProtector protector;
    private VaultDeviceKeyStore store;

    @Before public void setUp() throws Exception {
        repository = new MemoryRepository();
        protector = new AesProtector();
        store = new VaultDeviceKeyStore(protector, repository);
    }

    @Test public void remembersSealedAndRecallsPerAccount() throws Exception {
        store.remember("Me@Example.com", RECORD);
        assertEquals(1, repository.values.size());
        for (String stored : repository.values.values()) {
            assertFalse("the record is never stored in the clear", stored.contains("secret-dek"));
            assertFalse(new String(java.util.Base64.getDecoder().decode(stored), StandardCharsets.ISO_8859_1).contains("secret-dek"));
        }
        assertEquals(RECORD, store.recall("me@example.com"));
        assertNull(store.recall("other@example.com"));
    }

    @Test public void aRecordThatNoLongerOpensIsDeletedAndReadsAsAbsent() throws Exception {
        store.remember("me@example.com", RECORD);
        // The device key was replaced (reinstall, Keystore reset).
        protector.rotate();
        assertNull(store.recall("me@example.com"));
        assertTrue(repository.values.isEmpty());
    }

    @Test public void aTamperedRecordIsDeleted() throws Exception {
        store.remember("me@example.com", RECORD);
        String key = repository.values.keySet().iterator().next();
        byte[] sealed = java.util.Base64.getDecoder().decode(repository.values.get(key));
        sealed[sealed.length - 1] ^= 1;
        repository.values.put(key, java.util.Base64.getEncoder().encodeToString(sealed));
        assertNull(store.recall("me@example.com"));
        assertTrue(repository.values.isEmpty());
    }

    @Test public void forgetDropsOneAccountAndForgetAllDropsEverythingAndTheDeviceKey() throws Exception {
        store.remember("a@example.com", RECORD);
        store.remember("b@example.com", RECORD);
        store.forget("a@example.com");
        assertNull(store.recall("a@example.com"));
        assertEquals(RECORD, store.recall("b@example.com"));
        store.forgetAll();
        assertTrue(repository.values.isEmpty());
        assertTrue(protector.destroyed);
    }

    @Test public void refusesAnEmptyOrOversizedRecordAndAnInvalidScope() {
        assertRefused(() -> store.remember("me@example.com", ""));
        assertRefused(() -> store.remember("me@example.com", "x".repeat(VaultDeviceKeyStore.MAX_RECORD_BYTES + 1)));
        assertRefused(() -> store.remember(null, RECORD));
        assertRefused(() -> store.remember("bad\nscope", RECORD));
        assertTrue(repository.values.isEmpty());
    }

    private interface Action { void run() throws Exception; }

    private static void assertRefused(Action action) {
        try {
            action.run();
            fail("expected a refusal");
        } catch (IOException expected) {
            // refused
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    static final class MemoryRepository implements VaultDeviceKeyStore.Repository {
        final Map<String, String> values = new HashMap<>();
        @Override public String get(String key) { return values.get(key); }
        @Override public boolean put(String key, String value) { values.put(key, value); return true; }
        @Override public boolean remove(String key) { values.remove(key); return true; }
        @Override public boolean clear() { values.clear(); return true; }
    }

    /** A JVM AES-GCM stand-in for the Keystore key. */
    static final class AesProtector implements VaultDeviceKeyStore.Protector {
        private SecretKey key;
        boolean destroyed;
        AesProtector() throws Exception { rotate(); }
        void rotate() throws Exception {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            key = generator.generateKey();
        }
        @Override public byte[] seal(byte[] plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] body = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return out;
        }
        @Override public byte[] open(byte[] sealed) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, java.util.Arrays.copyOfRange(sealed, 0, 12)));
            return cipher.doFinal(sealed, 12, sealed.length - 12);
        }
        @Override public void destroy() { destroyed = true; }
    }
}
