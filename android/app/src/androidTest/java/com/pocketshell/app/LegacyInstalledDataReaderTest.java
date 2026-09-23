package com.pocketshell.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import androidx.security.crypto.MasterKey;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.getcapacitor.JSObject;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Read-only and secret-boundary checks for the legacy compatibility reader. */
@RunWith(AndroidJUnit4.class)
public final class LegacyInstalledDataReaderTest {
    private static final String KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__";
    private static final String VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__";
    private static final String PRIVATE_KEY_MARKER = "TEST-PRIVATE-KEY-MUST-STAY-NATIVE";

    private Context targetContext;
    private File fixtureRoot;
    private File keysetFile;
    private String fixturePreferencesName;
    private boolean createdMasterKey;

    @Before
    public void setUp() throws Exception {
        targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixtureRoot = new File(targetContext.getCacheDir(), "migration-reader-test-" + UUID.randomUUID());
        assertTrue(fixtureRoot.mkdirs());
        fixturePreferencesName = "migration-reader-test-" + UUID.randomUUID();
        keysetFile = new File(new File(targetContext.getApplicationInfo().dataDir, "shared_prefs"),
            fixturePreferencesName + ".xml");
    }

    @After
    public void tearDown() throws Exception {
        if (fixturePreferencesName != null && targetContext != null) {
            targetContext.getSharedPreferences(fixturePreferencesName, Context.MODE_PRIVATE)
                .edit().clear().commit();
            new File(new File(targetContext.getApplicationInfo().dataDir, "shared_prefs"),
                fixturePreferencesName + ".xml").delete();
        }
        if (createdMasterKey) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
            }
        }
        deleteTree(fixtureRoot);
    }

    @Test
    public void corruptExistingKeysetsAreUnavailableWithoutRewritingXmlOrKeystoreAlias() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        boolean hadMasterKey = keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
        if (!hadMasterKey) {
            createdMasterKey = true;
            new MasterKey.Builder(targetContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        }
        assertTrue(keyStoreContainsMasterKey());

        SharedPreferences backingPreferences = targetContext.getSharedPreferences(
            fixturePreferencesName, Context.MODE_PRIVATE);
        assertTrue(backingPreferences.edit()
            .putString(KEY_KEYSET, "00")
            .putString(VALUE_KEYSET, "00")
            .commit());
        byte[] originalXml = Files.readAllBytes(keysetFile.toPath());
        String[] aliasesBefore = keyStoreAliases();

        MigrationContext context = new MigrationContext(targetContext, fixtureRoot, fixturePreferencesName);
        LegacyInstalledDataReader reader = new LegacyInstalledDataReader(context,
            new ConcurrentHashMap<String, InstalledDataMigrationPlugin.AssetDescriptor>(),
            new String[] {fixturePreferencesName});
        JSObject snapshot = reader.read();
        JSONObject encryptedStore = snapshot.getJSONObject("encryptedPreferences")
            .getJSONObject(fixturePreferencesName);
        assertEquals("unavailable", encryptedStore.getString("status"));
        assertTrue(encryptedStore.getString("error").contains("unreadable keyset"));
        assertEquals(0, encryptedStore.getJSONArray("keys").length());
        assertArrayEquals("the malformed encrypted preference source must remain byte-for-byte unchanged",
            originalXml, Files.readAllBytes(keysetFile.toPath()));
        assertArrayEquals("the failed read must not create, remove, or replace Keystore aliases",
            aliasesBefore, keyStoreAliases());
    }

    @Test
    public void malformedDatabaseFailsWithoutChangingSourceBytes() throws Exception {
        File database = new File(new File(fixtureRoot, "databases"), "pocketshell.db");
        assertTrue(database.getParentFile().mkdirs());
        byte[] malformed = "not-a-room-database".getBytes(StandardCharsets.UTF_8);
        Files.write(database.toPath(), malformed);
        byte[] original = Files.readAllBytes(database.toPath());

        MigrationContext context = new MigrationContext(targetContext, fixtureRoot, fixturePreferencesName);
        LegacyInstalledDataReader reader = new LegacyInstalledDataReader(context,
            new ConcurrentHashMap<String, InstalledDataMigrationPlugin.AssetDescriptor>(),
            new String[] {fixturePreferencesName});
        LegacyInstalledDataReader.MigrationReadException failure = assertThrows(
            LegacyInstalledDataReader.MigrationReadException.class, reader::read);

        assertTrue(failure.getMessage().contains("database"));
        assertArrayEquals("the malformed database source must remain byte-for-byte unchanged",
            original, Files.readAllBytes(database.toPath()));
        assertFalse(new File(database.getPath() + "-wal").exists());
        assertFalse(new File(database.getPath() + "-shm").exists());
    }

    @Test
    public void sshPrivateKeyIsMetadataOnlyAndNeverGetsAnAssetToken() throws Exception {
        File key = new File(new File(fixtureRoot, "files/ssh-keys"), "legacy-key.pem");
        assertTrue(key.getParentFile().mkdirs());
        Files.write(key.toPath(), PRIVATE_KEY_MARKER.getBytes(StandardCharsets.UTF_8));

        MigrationContext context = new MigrationContext(targetContext, fixtureRoot, fixturePreferencesName);
        Map<String, InstalledDataMigrationPlugin.AssetDescriptor> registry = new ConcurrentHashMap<>();
        JSObject snapshot = new LegacyInstalledDataReader(context, registry,
            new String[] {fixturePreferencesName}).read();
        JSONArray assets = snapshot.getJSONArray("assets");
        JSONArray nativeFiles = snapshot.getJSONArray("nativeFiles");

        assertEquals(0, assets.length());
        assertTrue(registry.isEmpty());
        assertEquals(1, nativeFiles.length());
        JSONObject nativeKey = nativeFiles.getJSONObject(0);
        assertEquals("ssh-private-key-unindexed", nativeKey.getString("category"));
        assertEquals("ssh-keys/legacy-key.pem", nativeKey.getString("relativePath"));
        assertFalse(snapshot.toString().contains(PRIVATE_KEY_MARKER));
        assertFalse(nativeKey.has("privateKeyPem"));
    }

    private boolean keyStoreContainsMasterKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
    }

    private String[] keyStoreAliases() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        Enumeration<String> aliases = keyStore.aliases();
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        while (aliases.hasMoreElements()) values.add(aliases.nextElement());
        String[] result = values.toArray(new String[0]);
        Arrays.sort(result);
        return result;
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static final class MigrationContext extends ContextWrapper {
        private final File root;
        private final String preferencesName;
        private final ApplicationInfo applicationInfo;

        MigrationContext(Context base, File root, String preferencesName) {
            super(base);
            this.root = root;
            this.preferencesName = preferencesName;
            applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = base.getPackageName();
            applicationInfo.dataDir = base.getApplicationInfo().dataDir;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return applicationInfo;
        }

        @Override
        public File getDatabasePath(String name) {
            return new File(new File(root, "databases"), name);
        }

        @Override
        public File getFilesDir() {
            File files = new File(root, "files");
            files.mkdirs();
            return files;
        }

        @Override
        public File getCacheDir() {
            File cache = new File(root, "cache");
            cache.mkdirs();
            return cache;
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            assertEquals("fixture access is isolated to the encrypted store", preferencesName, name);
            return getBaseContext().getSharedPreferences(preferencesName, mode);
        }
    }
}
