package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

/**
 * The loopback sign-in's renewal secret at rest: Google's refresh token, in
 * its own {@link EncryptedSharedPreferences} file so it shares no keyset with
 * the ID-token record. The ID token itself still travels only through
 * {@link GoogleSyncSession}'s token store; this file is
 * {@link LoopbackPkceSignIn}'s private state, and losing it costs nothing
 * worse than signing in again.
 *
 * <p>Read-only until a sign-in writes, for the same reason as
 * {@link EncryptedSyncTokenStore} (issue #3047): opening
 * {@code EncryptedSharedPreferences} commits keysets, so a signed-out status
 * or renewal check must never open the file.
 */
@SuppressWarnings("deprecation") // security-crypto 1.1.0 deprecates the helper; it remains the at-rest store used here.
final class PkceRefreshTokenStore implements LoopbackPkceSignIn.RefreshStore {
    static final String REFRESH_KEY = "google_refresh_token";

    /** Where the record lives; a seam so JVM tests can stand in for Android storage. */
    interface Backing {
        boolean exists();

        SharedPreferences open() throws Exception;

        void delete();
    }

    private final Backing backing;
    private SharedPreferences preferences;

    /** Production: the record of the app the current activity belongs to. */
    PkceRefreshTokenStore(Supplier<Context> context) {
        this(new AndroidBacking(context));
    }

    PkceRefreshTokenStore(Backing backing) {
        this.backing = backing;
    }

    @Override
    public synchronized String read() throws IOException {
        SharedPreferences existing = preferences(false);
        return existing == null ? null : existing.getString(REFRESH_KEY, null);
    }

    /** The only operation that may create the record: an actual sign-in. */
    @Override
    public synchronized void write(String token) throws IOException {
        if (!preferences(true).edit().putString(REFRESH_KEY, token).commit()) {
            throw new IOException("encrypted refresh token was not written");
        }
    }

    @Override
    public synchronized void clear() throws IOException {
        SharedPreferences existing = preferences(false);
        if (existing == null) return;
        if (!existing.edit().clear().commit()) {
            throw new IOException("encrypted refresh token was not cleared");
        }
    }

    /** The open record, or null when {@code create} is false and there is none. */
    private SharedPreferences preferences(boolean create) throws IOException {
        if (preferences != null) return preferences;
        if (!create && !backing.exists()) return null;
        try {
            preferences = backing.open();
        } catch (Exception unreadable) {
            // A keyset the Keystore can no longer open only ever guarded a
            // renewal secret; start over. Only a write creates a record.
            backing.delete();
            if (!create) return null;
            try {
                preferences = backing.open();
            } catch (Exception error) {
                throw new IOException("encrypted refresh-token storage is unavailable");
            }
        }
        return preferences;
    }

    private static final class AndroidBacking implements Backing {
        private final Supplier<Context> context;

        AndroidBacking(Supplier<Context> context) {
            this.context = context;
        }

        @Override
        public boolean exists() {
            Context active = context.get();
            if (active == null) return false;
            return new File(new File(active.getApplicationInfo().dataDir, "shared_prefs"),
                    GoogleSyncConfig.PKCE_PREFERENCES_FILE + ".xml").exists();
        }

        @Override
        public SharedPreferences open() throws Exception {
            Context active = context.get();
            if (active == null) throw new IllegalStateException("PocketShell is not running");
            MasterKey masterKey = new MasterKey.Builder(active)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    active.getApplicationContext(),
                    GoogleSyncConfig.PKCE_PREFERENCES_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        }

        @Override
        public void delete() {
            Context active = context.get();
            if (active != null) {
                active.getApplicationContext().deleteSharedPreferences(GoogleSyncConfig.PKCE_PREFERENCES_FILE);
            }
        }
    }
}
