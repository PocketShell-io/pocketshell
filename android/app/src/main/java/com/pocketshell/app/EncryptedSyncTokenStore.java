package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.File;
import java.io.IOException;

/**
 * The sync sign-in record at rest: {@link EncryptedSharedPreferences} keyed by
 * an Android Keystore master key, in its own file so it shares no keyset with
 * anything else. The only place the Google ID token is ever written.
 */
@SuppressWarnings("deprecation") // security-crypto 1.1.0 deprecates the helper; it remains the at-rest store used here.
final class EncryptedSyncTokenStore implements GoogleSyncSession.TokenStore {
    static final String TOKEN_KEY = "google_id_token";

    /** Where the record lives; a seam so JVM tests can stand in for Android storage. */
    interface Backing {
        boolean exists();

        SharedPreferences open() throws Exception;

        void delete();
    }

    private final Backing backing;
    private SharedPreferences preferences;

    EncryptedSyncTokenStore(Context context) {
        this(new AndroidBacking(context.getApplicationContext()));
    }

    EncryptedSyncTokenStore(Backing backing) {
        this.backing = backing;
    }

    /**
     * Read-only: with no record on disk this is "signed out" without opening
     * {@link EncryptedSharedPreferences}, because opening it writes its keysets
     * and so creates {@code pocketshell-sync-auth.xml}. App startup reads the
     * sign-in status on every launch, and a file created there is reported by
     * the installed-data migration as leftover 0.5.6 data (issue #3047).
     */
    @Override
    public synchronized String read() throws IOException {
        SharedPreferences existing = preferences(false);
        return existing == null ? null : existing.getString(TOKEN_KEY, null);
    }

    /** The only operation that may create the record: an actual sign-in. */
    @Override
    public synchronized void write(String token) throws IOException {
        if (!preferences(true).edit().putString(TOKEN_KEY, token).commit()) {
            throw new IOException("encrypted sign-in record was not written");
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        SharedPreferences existing = preferences(false);
        if (existing == null) return;
        if (!existing.edit().clear().commit()) {
            throw new IOException("encrypted sign-in record was not cleared");
        }
    }

    /** The open record, or null when {@code create} is false and there is none. */
    private SharedPreferences preferences(boolean create) throws IOException {
        if (preferences != null) return preferences;
        if (!create && !backing.exists()) return null;
        try {
            preferences = backing.open();
        } catch (Exception unreadable) {
            // A keyset the Keystore can no longer open (restored backup, wiped
            // key) only ever guarded a sign-in; start over signed out. Only a
            // write starts a new record.
            backing.delete();
            if (!create) return null;
            try {
                preferences = backing.open();
            } catch (Exception error) {
                throw new IOException("encrypted sign-in storage is unavailable");
            }
        }
        return preferences;
    }

    private static final class AndroidBacking implements Backing {
        private final Context context;

        AndroidBacking(Context context) {
            this.context = context;
        }

        @Override
        public boolean exists() {
            // Same path LegacyInstalledDataReader inspects for this store.
            return new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"),
                    GoogleSyncConfig.AUTH_PREFERENCES_FILE + ".xml").exists();
        }

        @Override
        public SharedPreferences open() throws Exception {
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

        @Override
        public void delete() {
            context.deleteSharedPreferences(GoogleSyncConfig.AUTH_PREFERENCES_FILE);
        }
    }
}
