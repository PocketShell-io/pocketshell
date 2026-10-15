package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.security.KeyStore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The Android half of the vault's "Remember on this device" (key-vault W4):
 * {@link VaultDeviceKeyStore} over an Android Keystore AES-256-GCM key that
 * never leaves the Keystore ({@code setUserAuthenticationRequired(false)} for
 * now; a biometric gate is follow-up S5). The sealed record lives in a private
 * preferences file excluded from backup and device transfer.
 *
 * <p>JS: {@code remember({scope, record})}, {@code recall({scope}) -> {record|null}},
 * {@code forget({scope})}, {@code forgetAll()}.
 */
@CapacitorPlugin(name = "VaultDeviceKey")
public final class VaultDeviceKeyPlugin extends Plugin {
    static final String PREFERENCES_FILE = "pocketshell_vault_device_v1";
    private static final String KEY_ALIAS = "pocketshell.vault.device-key.v1";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private VaultDeviceKeyStore store;

    @Override
    public void load() {
        store = forContext(getContext());
    }

    static VaultDeviceKeyStore forContext(Context context) {
        return new VaultDeviceKeyStore(new KeystoreProtector(), new PreferencesRepository(context));
    }

    @PluginMethod
    public void remember(PluginCall call) {
        String scope = call.getString("scope");
        String record = call.getString("record");
        call.getData().remove("record");
        worker.execute(() -> {
            try {
                store.remember(scope, record);
                call.resolve(new JSObject().put("remembered", true));
            } catch (Exception error) {
                call.reject(error.getMessage(), "VAULT_REMEMBER_FAILED");
            }
        });
    }

    @PluginMethod
    public void recall(PluginCall call) {
        String scope = call.getString("scope");
        worker.execute(() -> {
            try {
                String record = store.recall(scope);
                JSObject reply = new JSObject();
                reply.put("record", record == null ? org.json.JSONObject.NULL : record);
                call.resolve(reply);
            } catch (Exception error) {
                call.reject(error.getMessage(), "VAULT_RECALL_FAILED");
            }
        });
    }

    @PluginMethod
    public void forget(PluginCall call) {
        String scope = call.getString("scope");
        worker.execute(() -> {
            try {
                store.forget(scope);
                call.resolve(new JSObject().put("forgotten", true));
            } catch (Exception error) {
                call.reject(error.getMessage(), "VAULT_FORGET_FAILED");
            }
        });
    }

    @PluginMethod
    public void forgetAll(PluginCall call) {
        worker.execute(() -> {
            store.forgetAll();
            call.resolve(new JSObject().put("forgotten", true));
        });
    }

    private static final class PreferencesRepository implements VaultDeviceKeyStore.Repository {
        private final SharedPreferences preferences;
        PreferencesRepository(Context context) {
            preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
        }
        @Override public String get(String key) { return preferences.getString(key, null); }
        @Override public boolean put(String key, String value) { return preferences.edit().putString(key, value).commit(); }
        @Override public boolean remove(String key) { return preferences.edit().remove(key).commit(); }
        @Override public boolean clear() { return preferences.edit().clear().commit(); }
    }

    private static final class KeystoreProtector implements VaultDeviceKeyStore.Protector {
        private SecretKey key() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
            return generator.generateKey();
        }

        @Override public byte[] seal(byte[] plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] nonce = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] output = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            return output;
        }

        @Override public byte[] open(byte[] sealed) throws Exception {
            if (sealed.length <= GCM_NONCE_BYTES) throw new java.io.IOException("malformed");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(GCM_TAG_BITS, java.util.Arrays.copyOfRange(sealed, 0, GCM_NONCE_BYTES)));
            return cipher.doFinal(sealed, GCM_NONCE_BYTES, sealed.length - GCM_NONCE_BYTES);
        }

        @Override public void destroy() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        }
    }
}
