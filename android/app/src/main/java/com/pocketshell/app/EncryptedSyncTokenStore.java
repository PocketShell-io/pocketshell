package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.IOException;

/**
 * The sync sign-in record at rest: {@link EncryptedSharedPreferences} keyed by
 * an Android Keystore master key, in its own file so it shares no keyset with
 * anything else. The only place the Google ID token is ever written.
 */
@SuppressWarnings("deprecation") // security-crypto 1.1.0 deprecates the helper; it remains the at-rest store used here.
final class EncryptedSyncTokenStore implements GoogleSyncSession.TokenStore {
    static final String TOKEN_KEY = "google_id_token";

    private final Context context;
    private SharedPreferences preferences;

    EncryptedSyncTokenStore(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public synchronized String read() throws IOException {
        return preferences().getString(TOKEN_KEY, null);
    }

    @Override
    public synchronized void write(String token) throws IOException {
        if (!preferences().edit().putString(TOKEN_KEY, token).commit()) {
            throw new IOException("encrypted sign-in record was not written");
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        if (!preferences().edit().clear().commit()) {
            throw new IOException("encrypted sign-in record was not cleared");
        }
    }

    private SharedPreferences preferences() throws IOException {
        if (preferences != null) return preferences;
        try {
            preferences = open();
        } catch (Exception unreadable) {
            // A keyset the Keystore can no longer open (restored backup, wiped
            // key) only ever guarded a sign-in; start over signed out.
            context.deleteSharedPreferences(GoogleSyncConfig.AUTH_PREFERENCES_FILE);
            try {
                preferences = open();
            } catch (Exception error) {
                throw new IOException("encrypted sign-in storage is unavailable");
            }
        }
        return preferences;
    }

    private SharedPreferences open() throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                context,
                GoogleSyncConfig.AUTH_PREFERENCES_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }
}
