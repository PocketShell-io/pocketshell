package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Issue #3047 round 2: the startup sign-in status read must be read-only.
 *
 * <p>App startup calls {@code GoogleSync.status()} on every launch. Opening
 * {@code EncryptedSharedPreferences} writes its keysets into
 * {@code shared_prefs/pocketshell-sync-auth.xml}, so a status read on a
 * signed-out phone used to create that file, and the installed-data migration
 * then reported it as leftover 0.5.6 data ("Some installed data needs
 * attention"). Only an actual sign-in may create the record.
 *
 * <p>The fake backing behaves like the real one where it matters: opening the
 * store creates the record file immediately, exactly as
 * {@code EncryptedSharedPreferences.create(...)} commits its keysets.
 */
public final class EncryptedSyncTokenStoreTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private FileBacking backing;

    @Before
    public void setUp() throws IOException {
        File sharedPrefs = folder.newFolder("shared_prefs");
        backing = new FileBacking(new File(sharedPrefs, GoogleSyncConfig.AUTH_PREFERENCES_FILE + ".xml"));
    }

    @Test
    public void statusOnAFreshInstallReportsSignedOutAndCreatesNoRecord() throws Exception {
        GoogleSyncSession session = new GoogleSyncSession(new EncryptedSyncTokenStore(backing), null, null,
                "https://sync.example.test", () -> 1_800_000_000L);

        assertFalse(session.status().signedIn);
        assertFalse(session.status().signedIn);

        assertFalse("a signed-out status read must not create " + backing.record, backing.record.exists());
        assertEquals("a signed-out status read must not open encrypted storage", 0, backing.opens);
    }

    @Test
    public void readAndClearOnAFreshInstallStayReadOnly() throws Exception {
        EncryptedSyncTokenStore store = new EncryptedSyncTokenStore(backing);

        assertNull(store.read());
        store.clear();
        assertNull(store.read());

        assertFalse(backing.record.exists());
        assertEquals(0, backing.opens);
    }

    @Test
    public void signInWriteCreatesTheRecordAndItSurvivesARestart() throws Exception {
        EncryptedSyncTokenStore store = new EncryptedSyncTokenStore(backing);
        store.write("token-1");

        assertTrue("sign-in must persist the record", backing.record.exists());
        assertEquals("token-1", store.read());

        // A new process: nothing cached, the record file exists, so it is opened.
        EncryptedSyncTokenStore restarted = new EncryptedSyncTokenStore(backing);
        assertEquals("token-1", restarted.read());

        restarted.clear();
        assertNull(restarted.read());
        assertNull(new EncryptedSyncTokenStore(backing).read());
    }

    @Test
    public void anUnreadableRecordReadsSignedOutAndIsNotRecreated() throws Exception {
        Files.write(backing.record.toPath(), "<map>stale keyset</map>".getBytes(StandardCharsets.UTF_8));
        backing.failOpens = 1;
        EncryptedSyncTokenStore store = new EncryptedSyncTokenStore(backing);

        assertNull(store.read());
        assertFalse("an unreadable record is discarded, and a read never recreates it", backing.record.exists());

        // A later sign-in starts a fresh record.
        store.write("token-2");
        assertTrue(backing.record.exists());
        assertEquals("token-2", new EncryptedSyncTokenStore(backing).read());
    }

    /** File-backed stand-in for the EncryptedSharedPreferences record. */
    private static final class FileBacking implements EncryptedSyncTokenStore.Backing {
        final File record;
        final Map<String, String> persisted = new HashMap<>();
        int opens;
        int failOpens;

        FileBacking(File record) {
            this.record = record;
        }

        @Override
        public boolean exists() {
            return record.exists();
        }

        @Override
        public SharedPreferences open() throws Exception {
            opens++;
            if (failOpens > 0) {
                failOpens--;
                throw new IllegalStateException("keyset cannot be decrypted");
            }
            if (!record.exists()) persisted.clear();
            flush();
            return new FilePreferences(this);
        }

        @Override
        public void delete() {
            persisted.clear();
            if (record.exists() && !record.delete()) throw new IllegalStateException("could not delete " + record);
        }

        void flush() throws IOException {
            Files.write(record.toPath(), ("<map keyset=\"1\">" + persisted + "</map>").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class FilePreferences implements SharedPreferences {
        private final FileBacking backing;

        FilePreferences(FileBacking backing) {
            this.backing = backing;
        }

        @Override public Map<String, ?> getAll() { return new HashMap<>(backing.persisted); }
        @Override public String getString(String key, String defValue) {
            return backing.persisted.containsKey(key) ? backing.persisted.get(key) : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return backing.persisted.containsKey(key); }
        @Override public Editor edit() { return new FileEditor(backing); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }

    private static final class FileEditor implements SharedPreferences.Editor {
        private final FileBacking backing;
        private final Map<String, String> puts = new LinkedHashMap<>();
        private boolean clear;

        FileEditor(FileBacking backing) {
            this.backing = backing;
        }

        @Override public SharedPreferences.Editor putString(String key, String value) { puts.put(key, value); return this; }
        @Override public SharedPreferences.Editor putStringSet(String key, Set<String> values) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putInt(String key, int value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putLong(String key, long value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putFloat(String key, float value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putBoolean(String key, boolean value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor remove(String key) { puts.put(key, null); return this; }
        @Override public SharedPreferences.Editor clear() { clear = true; return this; }

        @Override
        public boolean commit() {
            if (clear) backing.persisted.clear();
            for (Map.Entry<String, String> entry : puts.entrySet()) {
                if (entry.getValue() == null) backing.persisted.remove(entry.getKey());
                else backing.persisted.put(entry.getKey(), entry.getValue());
            }
            try {
                backing.flush();
                return true;
            } catch (IOException error) {
                return false;
            }
        }

        @Override public void apply() { commit(); }
    }
}
