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
import android.database.sqlite.SQLiteDatabase;

import androidx.security.crypto.MasterKey;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.getcapacitor.JSObject;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.SecurityUtils;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyFormat;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.userauth.password.PasswordUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.security.Provider;
import java.security.Security;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

/** Read-only and secret-boundary checks for the legacy compatibility reader. */
@RunWith(AndroidJUnit4.class)
public final class LegacyInstalledDataReaderTest {
    private static final String KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__";
    private static final String VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__";
    private static final String PRIVATE_KEY_MARKER = "TEST-PRIVATE-KEY-MUST-STAY-NATIVE";
    private static final String SYNTHETIC_PRIVATE_KEY =
        "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
        "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW\n" +
        "QyNTUxOQAAACB1KFeoYJxQ9VzETsiJLqZfc2lX+08qOrpSmShxDe32AQAAAKioAUCjqAFA\n" +
        "owAAAAtzc2gtZWQyNTUxOQAAACB1KFeoYJxQ9VzETsiJLqZfc2lX+08qOrpSmShxDe32AQ\n" +
        "AAAEAOjK+JhLThQgj4CqO6i8B7BC+LQ4/zHTlNyfF5+OW98nUoV6hgnFD1XMROyIkupl9z\n" +
        "aVf7Tyo6ulKZKHEN7fYBAAAAJHBvY2tldHNoZWxsLW1pZ3JhdGlvbi1yZXZpZXctZml4dH\n" +
        "VyZQE=\n" +
        "-----END OPENSSH PRIVATE KEY-----\n";

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

    @Test
    public void indexedSshKeyExportsHashOnlyAndKeepsTheOriginalFile() throws Exception {
        File key = new File(new File(fixtureRoot, "files/ssh-keys"), "legacy-key.pem");
        assertTrue(key.getParentFile().mkdirs());
        byte[] sourceBytes = SYNTHETIC_PRIVATE_KEY.getBytes(StandardCharsets.UTF_8);
        Files.write(key.toPath(), sourceBytes);
        String expectedHash = sha256(sourceBytes);
        File databaseFile = createRoom22Fixture(key);
        byte[] databaseBytes = Files.readAllBytes(databaseFile.toPath());

        MigrationContext context = new MigrationContext(targetContext, fixtureRoot, fixturePreferencesName);
        JSObject snapshot = new LegacyInstalledDataReader(context,
            new ConcurrentHashMap<String, InstalledDataMigrationPlugin.AssetDescriptor>()).read();
        JSONArray nativeFiles = snapshot.getJSONArray("nativeFiles");

        assertEquals(1, nativeFiles.length());
        JSONObject nativeKey = nativeFiles.getJSONObject(0);
        assertEquals("ssh-private-key", nativeKey.getString("category"));
        assertEquals(7, nativeKey.getLong("keyId"));
        assertEquals(expectedHash, nativeKey.getString("sha256"));
        assertFalse(snapshot.toString().contains(SYNTHETIC_PRIVATE_KEY));
        assertFalse(nativeKey.has("privateKeyPem"));
        assertArrayEquals("the indexed key must remain byte-for-byte unchanged",
            sourceBytes, Files.readAllBytes(key.toPath()));
        assertArrayEquals("the Room snapshot must remain byte-for-byte unchanged",
            databaseBytes, Files.readAllBytes(databaseFile.toPath()));
    }

    @Test
    public void savedKeyIdResolvesTheExactNativePrivateFileWithoutChangingIt() throws Exception {
        File key = new File(new File(fixtureRoot, "files/ssh-keys"), "legacy-key.pem");
        assertTrue(key.getParentFile().mkdirs());
        Files.write(key.toPath(), SYNTHETIC_PRIVATE_KEY.getBytes(StandardCharsets.UTF_8));

        File databaseFile = new File(new File(fixtureRoot, "databases"), "pocketshell.db");
        assertTrue(databaseFile.getParentFile().mkdirs());
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        database.execSQL("CREATE TABLE ssh_keys (id INTEGER PRIMARY KEY, privateKeyPath TEXT NOT NULL)");
        database.execSQL("INSERT INTO ssh_keys (id, privateKeyPath) VALUES (?, ?)",
            new Object[] {7L, key.getAbsolutePath()});
        database.close();

        byte[] sourceBytes = Files.readAllBytes(key.toPath());
        String sourceSha256 = sha256(sourceBytes);
        byte[] databaseBytes = Files.readAllBytes(databaseFile.toPath());
        MigrationContext context = new MigrationContext(targetContext, fixtureRoot, fixturePreferencesName);
        String resolvedKey = LegacyPrivateKeyResolver.readPrivateKey(context, 7, sourceSha256);
        assertEquals(SYNTHETIC_PRIVATE_KEY, resolvedKey);
        assertArrayEquals("native key resolution must not rewrite the original key file",
            sourceBytes, Files.readAllBytes(key.toPath()));
        assertThrows(IOException.class, () -> LegacyPrivateKeyResolver.readPrivateKey(context, 8, sourceSha256));
        assertThrows("a key changed after import must be rejected",
            IOException.class, () -> LegacyPrivateKeyResolver.readPrivateKey(context, 7, "0".repeat(64)));
        assertArrayEquals("read-only key lookup must not rewrite the Room source",
            databaseBytes, Files.readAllBytes(databaseFile.toPath()));
        assertFalse(new File(databaseFile.getPath() + "-wal").exists());
        assertFalse(new File(databaseFile.getPath() + "-shm").exists());

        database = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        database.execSQL("UPDATE ssh_keys SET privateKeyPath = ? WHERE id = ?",
            new Object[] {new File(fixtureRoot, "outside.pem").getAbsolutePath(), 7L});
        database.close();
        assertThrows("A key path outside the private key directory must be rejected",
            IOException.class, () -> LegacyPrivateKeyResolver.readPrivateKey(context, 7, sourceSha256));

        File symlink = new File(key.getParentFile(), "legacy-key-link.pem");
        Files.createSymbolicLink(symlink.toPath(), key.toPath());
        database = SQLiteDatabase.openDatabase(databaseFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        database.execSQL("UPDATE ssh_keys SET privateKeyPath = ? WHERE id = ?",
            new Object[] {symlink.getAbsolutePath(), 7L});
        database.close();
        assertThrows("A symbolic-link key path must be rejected",
            IOException.class, () -> LegacyPrivateKeyResolver.readPrivateKey(context, 7, sourceSha256));

        loadKeyThroughSshj(resolvedKey);
    }

    private File createRoom22Fixture(File key) throws Exception {
        File databaseFile = new File(new File(fixtureRoot, "databases"), "pocketshell.db");
        assertTrue(databaseFile.getParentFile().mkdirs());
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        database.execSQL("PRAGMA user_version = 22");
        database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)");
        database.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, '998a588a2d2f383454698ea11820fca9')");
        database.execSQL("CREATE TABLE hosts (id INTEGER, name TEXT, hostname TEXT, port INTEGER, username TEXT, keyId INTEGER, " +
            "maxAutoPort INTEGER, skipPortsBelow INTEGER, scanIntervalSec INTEGER, enabled INTEGER, createdAt INTEGER, " +
            "lastConnectedAt INTEGER, lastBootstrapAt INTEGER, pocketshellInstalled INTEGER, pocketshellLastDetectedAt INTEGER, " +
            "pocketshellCliVersion TEXT, pocketshellExpectedCliVersion TEXT, pocketshellVersionCompatible INTEGER, " +
            "pocketshellDaemonRunning INTEGER, pocketshellDaemonEnabled INTEGER, usageCommandOverride TEXT, treeIdentity TEXT, " +
            "trustedHostKeyAlgorithm TEXT, trustedHostKeySha256 TEXT)");
        database.execSQL("CREATE TABLE ssh_keys (id INTEGER, name TEXT, privateKeyPath TEXT, fingerprint TEXT, hasPassphrase INTEGER, createdAt INTEGER)");
        database.execSQL("CREATE TABLE port_remappings (id INTEGER, hostId INTEGER, remotePort INTEGER, localPort INTEGER, name TEXT)");
        database.execSQL("CREATE TABLE port_usage (hostId INTEGER, remotePort INTEGER, clickCount INTEGER, totalBytes INTEGER, lastUsedAt INTEGER)");
        database.execSQL("CREATE TABLE project_roots (id INTEGER, hostId INTEGER, label TEXT, path TEXT, createdAt INTEGER, sortOrder INTEGER)");
        database.execSQL("CREATE TABLE snippets (id INTEGER, hostId INTEGER, label TEXT, body TEXT, kind TEXT)");
        database.execSQL("CREATE TABLE ai_api_call_log (id INTEGER, timestampMillis INTEGER, provider TEXT, feature TEXT, inputUnits INTEGER, outputUnits INTEGER, unitCostUsdMillicents INTEGER, computedCostUsdMillicents INTEGER, metadataJson TEXT)");
        database.execSQL("CREATE TABLE pending_transcriptions (id TEXT, audioPath TEXT, recordingTimestampMs INTEGER, destinationContext TEXT, retryCount INTEGER, lastErrorMessage TEXT, audioByteSize INTEGER, createdAtMs INTEGER)");
        database.execSQL("CREATE TABLE command_templates (id INTEGER, hostId INTEGER, label TEXT, commands TEXT)");
        database.execSQL("CREATE TABLE sent_messages (id INTEGER, sessionKey TEXT, body TEXT, sentAtMs INTEGER, delivered INTEGER)");
        database.execSQL("INSERT INTO ssh_keys (id, name, privateKeyPath, fingerprint, hasPassphrase, createdAt) VALUES (?, ?, ?, ?, ?, ?)",
            new Object[] {7L, "Synthetic test key", key.getAbsolutePath(), "fixture", 0, 1L});
        database.execSQL("INSERT INTO hosts (id, name, hostname, port, username, keyId) VALUES (?, ?, ?, ?, ?, ?)",
            new Object[] {41L, "Synthetic host", "example.test", 22, "fixture", 7L});
        database.close();
        return databaseFile;
    }

    private static void loadKeyThroughSshj(String pem) throws Exception {
        Provider installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
        if (installed == null || installed.getService("KeyPairGenerator", "X25519") == null) {
            if (installed != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
            Security.insertProviderAt(new BouncyCastleProvider(), 1);
        }
        SecurityUtils.setRegisterBouncyCastle(false);
        SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME);

        try (SSHClient client = new SSHClient()) {
            KeyFormat format = KeyProviderUtil.detectKeyFileFormat(pem, false);
            FileKeyProvider keyProvider = Factory.Named.Util.create(
                client.getTransport().getConfig().getFileKeyProviderFactories(), format.toString());
            assertTrue("the imported private key format must be supported by native sshj", keyProvider != null);
            keyProvider.init(pem, null, PasswordUtils.createOneOff(new char[0]));
            assertTrue("native sshj must be able to load the imported private key", keyProvider.getPrivate() != null);
            assertTrue("native sshj must derive the imported private key's public key", keyProvider.getPublic() != null);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item & 0xff));
        return result.toString();
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
