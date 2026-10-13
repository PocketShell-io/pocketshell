package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The loopback sign-in's refresh-token record (issue #3020's fallback): it
 * behaves like the ID-token store — encrypted at rest in its own file, and
 * read-only until an actual sign-in writes, so a status or renewal check on a
 * signed-out phone never creates the record (the issue #3047 lesson).
 */
public final class PkceRefreshTokenStoreTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private MemoryBacking backing;

    @Before
    public void setUp() throws IOException {
        backing = new MemoryBacking(new File(folder.getRoot(), GoogleSyncConfig.PKCE_PREFERENCES_FILE + ".xml"));
    }

    @Test
    public void readsOnAFreshInstallStayReadOnly() throws Exception {
        PkceRefreshTokenStore store = new PkceRefreshTokenStore(backing);

        assertNull(store.read());
        store.clear();
        assertNull(store.read());

        assertFalse("no read may create " + backing.record, backing.record.exists());
        assertEquals("no read may open encrypted storage", 0, backing.opens);
    }

    @Test
    public void aWriteCreatesTheRecordAndItSurvivesARestart() throws Exception {
        PkceRefreshTokenStore store = new PkceRefreshTokenStore(backing);
        store.write("RT-1");

        assertTrue("the sign-in write must persist the record", backing.record.exists());
        assertEquals("RT-1", store.read());
        assertEquals("RT-1", new PkceRefreshTokenStore(backing).read());
    }

    @Test
    public void clearRemovesTheSecretButKeepsTheFileHarmless() throws Exception {
        PkceRefreshTokenStore store = new PkceRefreshTokenStore(backing);
        store.write("RT-2");
        store.clear();
        assertNull(store.read());
    }

    @Test
    public void anUnopenableRecordReadsAsSignedOutAndOnlyAWriteRecreatesIt() throws Exception {
        PkceRefreshTokenStore store = new PkceRefreshTokenStore(backing);
        store.write("RT-3");
        assertTrue(backing.record.exists());

        // A new process: nothing cached, and the keyset can no longer be opened.
        backing.failOpens = 1;
        PkceRefreshTokenStore restarted = new PkceRefreshTokenStore(backing);
        assertNull("a keyset the Keystore cannot open reads as signed out", restarted.read());
        assertFalse("the unreadable record is discarded", backing.record.exists());

        restarted.write("RT-3-NEW");
        assertEquals("only a write recreates the record", "RT-3-NEW", restarted.read());
    }

    @Test
    public void aFailedCommitSurfacesAsIOException() throws Exception {
        backing.failCommits = 1;
        PkceRefreshTokenStore store = new PkceRefreshTokenStore(backing);
        try {
            store.write("RT-4");
            fail("a failed write must surface");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not written"));
        }
    }

    /** In-memory stand-in for the EncryptedSharedPreferences record. */
    private static final class MemoryBacking implements PkceRefreshTokenStore.Backing {
        final File record;
        final Map<String, String> persisted = new HashMap<>();
        int opens;
        int failOpens;
        int failCommits;

        MemoryBacking(File record) {
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
            return new MemoryPreferences(this);
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

    private static final class MemoryPreferences implements SharedPreferences {
        private final MemoryBacking backing;

        MemoryPreferences(MemoryBacking backing) {
            this.backing = backing;
        }

        @Override public Map<String, ?> getAll() { return new HashMap<>(backing.persisted); }
        @Override public String getString(String key, String defValue) {
            return backing.persisted.containsKey(key) ? backing.persisted.get(key) : defValue;
        }
        @Override public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return backing.persisted.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(backing); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
    }

    private static final class MemoryEditor implements SharedPreferences.Editor {
        private final MemoryBacking backing;
        private final Map<String, String> puts = new LinkedHashMap<>();
        private boolean clear;

        MemoryEditor(MemoryBacking backing) {
            this.backing = backing;
        }

        @Override public SharedPreferences.Editor putString(String key, String value) { puts.put(key, value); return this; }
        @Override public SharedPreferences.Editor putStringSet(String key, java.util.Set<String> values) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putInt(String key, int value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putLong(String key, long value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putFloat(String key, float value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor putBoolean(String key, boolean value) { throw new UnsupportedOperationException(); }
        @Override public SharedPreferences.Editor remove(String key) { puts.put(key, null); return this; }
        @Override public SharedPreferences.Editor clear() { clear = true; return this; }

        @Override
        public boolean commit() {
            if (backing.failCommits > 0) {
                backing.failCommits--;
                return false;
            }
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
