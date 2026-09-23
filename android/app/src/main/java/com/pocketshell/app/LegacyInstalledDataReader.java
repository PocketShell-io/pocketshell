package com.pocketshell.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Xml;
import com.getcapacitor.JSObject;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import com.google.crypto.tink.integration.android.AndroidKeysetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Read-only compatibility boundary for the installed 0.5.x private sandbox. */
final class LegacyInstalledDataReader {
    private static final int MAX_DATABASE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_PREFERENCE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ROWS_PER_TABLE = 10_000;
    private static final int MAX_PREFERENCE_ENTRIES = 20_000;
    private static final int MAX_ASSETS = 1_000;
    private static final long MAX_ASSET_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_TOTAL_ASSET_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_KEY_FILE_BYTES = 1024 * 1024;
    private static final String KEY_KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_key_keyset__";
    private static final String VALUE_KEYSET_ENTRY = "__androidx_security_crypto_encrypted_prefs_value_keyset__";
    private static final String MASTER_KEY_URI_PREFIX = "android-keystore://";

    private static final String[] PREFERENCE_FILES = {
        "composer_drafts",
        "next_settings",
        "app_settings",
        "next_sync_selection",
        "workspace_order",
        "port_forward_panel",
        "update_check",
    };

    private static final String[] ENCRYPTED_PREFERENCE_FILES = {
        "pocketshell-sync-auth",
        "pocketshell-voice-secrets",
        "pocketshell-assistant-secrets",
    };

    private static final String[] TABLE_NAMES = {
        "hosts",
        "ssh_keys",
        "port_remappings",
        "port_usage",
        "project_roots",
        "snippets",
        "ai_api_call_log",
        "pending_transcriptions",
        "command_templates",
        "sent_messages",
    };

    private static final int[] ROOM_VERSIONS = {16, 17, 18, 19, 20, 21, 22};
    private static final String[] ROOM_IDENTITY_HASHES = {
        "afc67c7758ec9cfe23ecb21326abe06d",
        "274c6967510073a57e89482cbfd30958",
        "05f1073ebb70b0b41033a4e4bbe22082",
        "de42603f8ceee22afa2a074bcc9be30e",
        "be97992383b7a66363efbe2cbb142d0b",
        "08d40b0e3508acf5ffa4f2378d79325e",
        "998a588a2d2f383454698ea11820fca9",
    };

    private final Context context;
    private final Map<String, InstalledDataMigrationPlugin.AssetDescriptor> assetRegistry;
    private final String[] encryptedPreferenceFiles;
    private long totalAssetBytes;

    LegacyInstalledDataReader(
        Context context,
        Map<String, InstalledDataMigrationPlugin.AssetDescriptor> assetRegistry
    ) {
        this(context, assetRegistry, ENCRYPTED_PREFERENCE_FILES);
    }

    LegacyInstalledDataReader(
        Context context,
        Map<String, InstalledDataMigrationPlugin.AssetDescriptor> assetRegistry,
        String[] encryptedPreferenceFiles
    ) {
        this.context = context.getApplicationContext();
        this.assetRegistry = assetRegistry;
        this.encryptedPreferenceFiles = encryptedPreferenceFiles.clone();
    }

    JSObject read() throws Exception {
        JSObject result = new JSObject();
        result.put("schemaVersion", 1);
        result.put("environment", new JSObject()
            .put("applicationId", context.getPackageName())
            .put("displayDensity", (double) context.getResources().getDisplayMetrics().density));
        JSObject database = readDatabase();
        result.put("database", database);
        result.put("preferences", readPreferences());
        result.put("encryptedPreferences", readEncryptedPreferences());
        JSONArray assets = readAssets();
        validatePendingAudioReferences(context.getFilesDir(), database, assets);
        result.put("assets", assets);
        result.put("nativeFiles", readNativeFiles(database));

        int payloadSize = result.toString().getBytes(StandardCharsets.UTF_8).length;
        if (payloadSize > MAX_SNAPSHOT_BYTES) {
            throw invalid("The installed data snapshot is larger than the supported import limit.");
        }
        return result;
    }

    private JSObject readDatabase() throws Exception {
        File file = context.getDatabasePath("pocketshell.db");
        JSObject database = new JSObject();
        if (Files.isSymbolicLink(file.toPath())) {
            throw invalid("The installed database path is a symbolic link and cannot be read safely.");
        }
        if (!file.exists()) {
            database.put("present", false);
            database.put("tables", new JSObject());
            return database;
        }
        if (!file.isFile() || !file.canRead() || file.length() > MAX_DATABASE_BYTES) {
            throw invalid("The installed database exceeds the supported read-only import limit.");
        }
        database.put("present", true);

        SQLiteDatabase db = null;
        File databaseSnapshot = null;
        try {
            databaseSnapshot = copyDatabaseSnapshot(file);
            db = SQLiteDatabase.openDatabase(databaseSnapshot.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            int version = (int) scalarLong(db, "PRAGMA user_version");
            String expectedHash = identityForVersion(version);
            if (expectedHash == null) {
                throw invalid("The installed database schema version " + version + " is unsupported.");
            }
            String actualHash = roomIdentityHash(db);
            if (!expectedHash.equals(actualHash)) {
                throw invalid("The installed database identity does not match Room schema " + version + ".");
            }
            String integrity = scalarString(db, "PRAGMA quick_check(1)");
            if (!"ok".equalsIgnoreCase(integrity)) {
                throw invalid("The installed database failed its read-only integrity check.");
            }
            validateSchema(db, version);
            validateForeignKeys(db);

            JSObject tables = new JSObject();
            for (String table : expectedTables(version)) {
                tables.put(table, readRows(db, table));
            }
            database.put("version", version);
            database.put("identityHash", actualHash);
            database.put("tables", tables);
            return database;
        } catch (MigrationReadException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("The installed database could not be read safely; its source was left untouched.");
        } finally {
            if (db != null) db.close();
            deleteDatabaseSnapshot(databaseSnapshot);
        }
    }

    /**
     * SQLite can need to create or update WAL/SHM state while opening a database.
     * Read a bounded, consistency-checked copy so even malformed input cannot
     * cause sidecar writes in the installed app's private data directory.
     */
    private File copyDatabaseSnapshot(File source) throws Exception {
        if (Files.isSymbolicLink(source.toPath()) || !source.isFile() || !source.canRead() || source.length() > MAX_DATABASE_BYTES) {
            throw invalid("The installed database is missing or exceeds the supported read-only import limit.");
        }
        File cache = context.getCacheDir();
        if (!cache.isDirectory() && !cache.mkdirs()) {
            throw invalid("A private temporary location for read-only database inspection is unavailable.");
        }
        File snapshot = File.createTempFile("pocketshell-legacy-", ".db", cache);
        String[] suffixes = {"", "-wal", "-shm", "-journal"};
        File[] sources = new File[suffixes.length];
        SourceFingerprint[] fingerprints = new SourceFingerprint[suffixes.length];
        try {
            long totalBytes = 0;
            for (int index = 0; index < suffixes.length; index++) {
                File candidate = index == 0 ? source : new File(source.getAbsolutePath() + suffixes[index]);
                sources[index] = candidate;
                if (!candidate.exists()) continue;
                if (Files.isSymbolicLink(candidate.toPath()) || !candidate.isFile() || !candidate.canRead() || candidate.length() > MAX_DATABASE_BYTES) {
                    throw invalid("The installed database has an unreadable or oversized SQLite sidecar.");
                }
                totalBytes += candidate.length();
                if (totalBytes > MAX_TOTAL_DATABASE_BYTES) {
                    throw invalid("The installed database and SQLite sidecars exceed the supported read-only import limit.");
                }
                fingerprints[index] = fingerprint(candidate);
            }
            for (int index = 0; index < suffixes.length; index++) {
                if (fingerprints[index] == null) continue;
                File destination = index == 0 ? snapshot : new File(snapshot.getAbsolutePath() + suffixes[index]);
                copyFile(sources[index], destination);
            }
            for (int index = 0; index < suffixes.length; index++) {
                SourceFingerprint before = fingerprints[index];
                File current = sources[index];
                if (before == null) {
                    if (current.exists()) throw invalid("The installed database changed while it was being inspected.");
                } else if (!current.isFile() || !before.matches(fingerprint(current))) {
                    throw invalid("The installed database changed while it was being inspected.");
                }
            }
            return snapshot;
        } catch (Exception error) {
            deleteDatabaseSnapshot(snapshot);
            if (error instanceof MigrationReadException) throw error;
            throw invalid("The installed database could not be copied safely; its source was left untouched.");
        }
    }

    private static SourceFingerprint fingerprint(File file) throws Exception {
        return new SourceFingerprint(file.length(), file.lastModified(), sha256(file));
    }

    private static void copyFile(File source, File destination) throws Exception {
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            output.getFD().sync();
        }
    }

    private static void deleteDatabaseSnapshot(File snapshot) {
        if (snapshot == null) return;
        snapshot.delete();
        new File(snapshot.getAbsolutePath() + "-wal").delete();
        new File(snapshot.getAbsolutePath() + "-shm").delete();
        new File(snapshot.getAbsolutePath() + "-journal").delete();
    }

    private static final long MAX_TOTAL_DATABASE_BYTES = 64L * 1024L * 1024L;

    private static final class SourceFingerprint {
        final long length;
        final long lastModified;
        final String sha256;

        SourceFingerprint(long length, long lastModified, String sha256) {
            this.length = length;
            this.lastModified = lastModified;
            this.sha256 = sha256;
        }

        boolean matches(SourceFingerprint other) {
            return length == other.length && lastModified == other.lastModified && sha256.equals(other.sha256);
        }
    }

    private static void validateSchema(SQLiteDatabase db, int version) throws Exception {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(expectedTables(version)));
        Set<String> actual = new LinkedHashSet<>();
        try (Cursor cursor = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name != 'room_master_table' AND name != 'android_metadata' ORDER BY name",
            null
        )) {
            while (cursor.moveToNext()) actual.add(cursor.getString(0));
        }
        if (!expected.equals(actual)) throw invalid("The installed database has an unexpected table set.");

        Map<String, List<String>> columns = expectedColumns(version);
        for (String table : expected) {
            List<String> found = new ArrayList<>();
            try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
                while (cursor.moveToNext()) found.add(cursor.getString(cursor.getColumnIndexOrThrow("name")));
            }
            if (!columns.get(table).equals(found)) {
                throw invalid("The installed database table " + table + " does not match Room schema " + version + ".");
            }
        }
    }

    private static void validateForeignKeys(SQLiteDatabase db) throws Exception {
        try (Cursor cursor = db.rawQuery("PRAGMA foreign_key_check", null)) {
            if (cursor.moveToFirst()) throw invalid("The installed database contains broken host or key references.");
        }
    }

    private static JSONArray readRows(SQLiteDatabase db, String table) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT * FROM " + table + " ORDER BY rowid", null)) {
            if (cursor.getCount() > MAX_ROWS_PER_TABLE) {
                throw invalid("The installed database table " + table + " exceeds the supported import limit.");
            }
            while (cursor.moveToNext()) {
                JSObject row = new JSObject();
                for (int index = 0; index < cursor.getColumnCount(); index++) {
                    String column = cursor.getColumnName(index);
                    switch (cursor.getType(index)) {
                        case Cursor.FIELD_TYPE_NULL:
                            row.put(column, JSONObject.NULL);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            long number = cursor.getLong(index);
                            if (number > 9_007_199_254_740_991L || number < -9_007_199_254_740_991L) {
                                throw invalid("The installed database contains an integer outside the exact JavaScript range.");
                            }
                            row.put(column, number);
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            double decimal = cursor.getDouble(index);
                            if (!Double.isFinite(decimal)) throw invalid("The installed database contains an invalid number.");
                            row.put(column, decimal);
                            break;
                        case Cursor.FIELD_TYPE_STRING:
                            row.put(column, cursor.getString(index));
                            break;
                        default:
                            throw invalid("The installed database contains a value type this reader cannot preserve.");
                    }
                }
                rows.put(row);
            }
        }
        return rows;
    }

    private JSObject readPreferences() throws Exception {
        JSObject all = new JSObject();
        for (String name : PREFERENCE_FILES) {
            File file = new File(new File(context.getApplicationInfo().dataDir, "shared_prefs"), name + ".xml");
            if (Files.isSymbolicLink(file.toPath())) {
                throw invalid("A legacy preferences file is a symbolic link and cannot be read safely: " + file.getName());
            }
            boolean present = file.exists();
            if (present && (!file.isFile() || !file.canRead())) {
                throw invalid("A legacy preferences file is unreadable: " + file.getName());
            }
            JSObject store = new JSObject();
            store.put("present", present);
            store.put("entries", present ? readPreferenceXml(file) : new JSObject());
            all.put(name, store);
        }
        return all;
    }

    private static JSObject readPreferenceXml(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) {
            throw invalid("A legacy preferences file is a symbolic link and cannot be read safely: " + file.getName());
        }
        if (file.length() > MAX_PREFERENCE_BYTES) {
            throw invalid("A legacy preferences file exceeds the supported import limit: " + file.getName());
        }
        JSObject entries = new JSObject();
        try (InputStream input = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(input, StandardCharsets.UTF_8.name());
            int event = parser.nextTag();
            if (event != XmlPullParser.START_TAG || !"map".equals(parser.getName())) {
                throw invalid("A legacy preferences file has invalid XML: " + file.getName());
            }
            int rootDepth = parser.getDepth();
            while (true) {
                int next = parser.next();
                if (next == XmlPullParser.END_TAG && parser.getDepth() == rootDepth) break;
                if (next == XmlPullParser.END_DOCUMENT) throw invalid("A legacy preferences file is incomplete: " + file.getName());
                if (next != XmlPullParser.START_TAG) continue;
                String tag = parser.getName();
                String name = parser.getAttributeValue(null, "name");
                if (name == null || name.isEmpty() || entries.has(name)) {
                    throw invalid("A legacy preferences file has a missing or duplicate key: " + file.getName());
                }
                if (entries.length() >= MAX_PREFERENCE_ENTRIES) {
                    throw invalid("A legacy preferences file has too many entries: " + file.getName());
                }
                JSObject typed = parsePreferenceEntry(parser, tag);
                entries.put(name, typed);
            }
            if (parser.next() != XmlPullParser.END_DOCUMENT) {
                throw invalid("A legacy preferences file has trailing XML: " + file.getName());
            }
        } catch (MigrationReadException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("A legacy preferences file could not be parsed safely: " + file.getName());
        }
        return entries;
    }

    private static JSObject parsePreferenceEntry(XmlPullParser parser, String tag) throws Exception {
        switch (tag) {
            case "string":
                return new JSObject().put("type", "string").put("value", parser.nextText());
            case "int":
                return new JSObject().put("type", "int").put("value", Integer.parseInt(requiredAttribute(parser, "value")));
            case "long":
                // Keep the full signed 64-bit value exact across the JS bridge.
                return new JSObject().put("type", "long").put("value", Long.toString(Long.parseLong(requiredAttribute(parser, "value"))));
            case "float":
                float floatValue = Float.parseFloat(requiredAttribute(parser, "value"));
                if (!Float.isFinite(floatValue)) throw invalid("A legacy preference contains an invalid float.");
                return new JSObject().put("type", "float").put("value", floatValue);
            case "boolean":
                String booleanValue = requiredAttribute(parser, "value");
                if (!"true".equals(booleanValue) && !"false".equals(booleanValue)) {
                    throw invalid("A legacy preference contains an invalid Boolean.");
                }
                return new JSObject().put("type", "boolean").put("value", Boolean.parseBoolean(booleanValue));
            case "set":
                return new JSObject().put("type", "string-set").put("value", parseStringSet(parser));
            default:
                throw invalid("A legacy preferences file contains an unsupported value type.");
        }
    }

    private static JSONArray parseStringSet(XmlPullParser parser) throws Exception {
        JSONArray values = new JSONArray();
        Set<String> distinct = new LinkedHashSet<>();
        int depth = parser.getDepth();
        while (true) {
            int event = parser.next();
            if (event == XmlPullParser.END_TAG && parser.getDepth() == depth) break;
            if (event == XmlPullParser.END_DOCUMENT) throw invalid("A legacy preference set is incomplete.");
            if (event == XmlPullParser.START_TAG) {
                if (!"string".equals(parser.getName()) || parser.getDepth() != depth + 1) {
                    throw invalid("A legacy preference set contains an unsupported value.");
                }
                String value = parser.nextText();
                if (!distinct.add(value)) throw invalid("A legacy preference set contains duplicate values.");
                values.put(value);
            }
        }
        return values;
    }

    private JSObject readEncryptedPreferences() throws Exception {
        JSObject result = new JSObject();
        File prefsDirectory = new File(context.getApplicationInfo().dataDir, "shared_prefs");
        for (String name : encryptedPreferenceFiles) {
            File file = new File(prefsDirectory, name + ".xml");
            if (Files.isSymbolicLink(file.toPath())) {
                throw invalid("Encrypted preferences " + name + " are a symbolic link and cannot be read safely.");
            }
            boolean present = file.exists();
            if (present && (!file.isFile() || !file.canRead())) {
                throw invalid("Encrypted preferences " + name + " are unreadable.");
            }
            if (present && file.length() > MAX_PREFERENCE_BYTES) {
                throw invalid("Encrypted preferences " + name + " exceed the supported read-only import limit.");
            }
            JSObject store = new JSObject();
            store.put("present", present);
            if (!present) {
                store.put("status", "absent");
                store.put("keys", new JSONArray());
                result.put(name, store);
                continue;
            }
            store.put("keys", new JSONArray());
            String originalXmlHash = sha256(file);
            try {
                preflightEncryptedPreferences(file, name);
                MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
                android.content.SharedPreferences prefs = EncryptedSharedPreferences.create(
                    context,
                    name,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                Map<String, ?> values = prefs.getAll();
                if (values.size() > 100) throw invalid("An encrypted preference store exceeds the supported key count: " + name);
                JSONArray keys = new JSONArray();
                for (Map.Entry<String, ?> entry : values.entrySet()) {
                    if (!(entry.getValue() instanceof String)) {
                        throw invalid("An encrypted preference store contains an unsupported value type: " + name);
                    }
                    keys.put(entry.getKey());
                }
                if (!originalXmlHash.equals(sha256(file))) {
                    throw invalid("Encrypted preferences " + name + " changed while being inspected; the import was stopped.");
                }
                store.put("status", "decrypted-native-retained");
                store.put("keys", keys);
            } catch (MigrationReadException error) {
                if (!originalXmlHash.equals(sha256(file))) {
                    throw invalid("Encrypted preferences " + name + " changed while being inspected; the import was stopped.");
                }
                store.put("status", "unavailable");
                store.put("error", error.getMessage());
            } catch (Exception error) {
                if (!originalXmlHash.equals(sha256(file))) {
                    throw invalid("Encrypted preferences " + name + " changed while being inspected; the import was stopped.");
                }
                store.put("status", "unavailable");
                store.put("error", "Encrypted preferences " + name + " could not be decrypted. Its source and Keystore entries were left untouched.");
            }
            result.put(name, store);
        }
        return result;
    }

    private void preflightEncryptedPreferences(File file, String preferenceName) throws Exception {
        JSObject entries = readPreferenceXml(file);
        requireExistingKeyset(entries, KEY_KEYSET_ENTRY, preferenceName);
        requireExistingKeyset(entries, VALUE_KEYSET_ENTRY, preferenceName);

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
            throw invalid("Encrypted preferences " + preferenceName + " have no existing Android Keystore master key; no replacement key was created.");
        }

        String masterKeyUri = MASTER_KEY_URI_PREFIX + MasterKey.DEFAULT_MASTER_KEY_ALIAS;
        try {
            // No key template is supplied. The builder can only read existing
            // keysets; if one is absent it throws before it can write a new one.
            new AndroidKeysetManager.Builder()
                .withSharedPref(context, KEY_KEYSET_ENTRY, preferenceName)
                .withMasterKeyUri(masterKeyUri)
                .build();
            new AndroidKeysetManager.Builder()
                .withSharedPref(context, VALUE_KEYSET_ENTRY, preferenceName)
                .withMasterKeyUri(masterKeyUri)
                .build();
        } catch (Exception error) {
            throw invalid("Encrypted preferences " + preferenceName + " have an unreadable keyset; the source was not repaired.");
        }
    }

    private static void requireExistingKeyset(JSObject entries, String key, String preferenceName) throws Exception {
        JSONObject entry = entries.optJSONObject(key);
        if (entry == null || !"string".equals(entry.optString("type"))) {
            throw invalid("Encrypted preferences " + preferenceName + " are missing a required keyset; the source was not opened.");
        }
        String hex = entry.optString("value", "");
        if (hex.isEmpty() || (hex.length() % 2) != 0 || !hex.matches("[0-9a-fA-F]+")) {
            throw invalid("Encrypted preferences " + preferenceName + " have a malformed keyset entry; the source was not opened.");
        }
    }

    private JSONArray readAssets() throws Exception {
        JSONArray assets = new JSONArray();
        File filesDir = context.getFilesDir().getCanonicalFile();
        File voicePending = new File(filesDir, "voice-pending");
        File voiceExports = new File(filesDir, "voice-exports");
        File crashReports = new File(filesDir, "crash-reports");
        File diagnosticsDirectory = new File(filesDir, "diagnostics");
        if (Files.isSymbolicLink(diagnosticsDirectory.toPath())) throw invalid("The diagnostics directory is a symbolic link.");
        File diagnostics = new File(diagnosticsDirectory, "pocketshell-diagnostics.jsonl");
        if (Files.isSymbolicLink(diagnostics.toPath())) throw invalid("The diagnostics file is a symbolic link.");

        addDirectoryAssets(assets, "pending-transcription-audio", voicePending, voicePending);
        addDirectoryAssets(assets, "voice-export", voiceExports, voiceExports);
        addDirectoryAssets(assets, "crash-report", crashReports, crashReports);
        if (diagnostics.exists()) {
            addAsset(assets, "diagnostic-history", diagnostics, diagnosticsDirectory);
        }
        return assets;
    }

    private void addDirectoryAssets(
        JSONArray result,
        String category,
        File directory,
        File allowedRoot
    ) throws Exception {
        if (Files.isSymbolicLink(directory.toPath())) {
            throw invalid("A private data directory is a symbolic link: " + directory.getName());
        }
        if (!directory.exists()) return;
        File canonicalRoot = allowedRoot.getCanonicalFile();
        File[] children = directory.listFiles();
        if (children == null) throw invalid("A private data directory could not be listed: " + directory.getName());
        Arrays.sort(children, (left, right) -> left.getName().compareTo(right.getName()));
        for (File child : children) {
            if (child.isDirectory()) throw invalid("A private data directory contains an unexpected nested directory: " + directory.getName());
            if (Files.isSymbolicLink(child.toPath())) throw invalid("A private data directory contains a symbolic link: " + directory.getName());
            if (!child.isFile()) throw invalid("A private data directory contains an unsupported entry: " + directory.getName());
            addAsset(result, category, child, canonicalRoot);
        }
    }

    private static void validatePendingAudioReferences(File filesDir, JSObject database, JSONArray assets) throws Exception {
        if (!database.optBoolean("present", false)) return;
        JSObject tables = database.getJSObject("tables");
        JSONArray rows = tables.optJSONArray("pending_transcriptions");
        if (rows == null) return;
        Set<String> available = new LinkedHashSet<>();
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if ("pending-transcription-audio".equals(asset.optString("category"))) {
                available.add(asset.getString("relativePath"));
            }
        }
        for (int index = 0; index < rows.length(); index++) {
            JSONObject row = rows.getJSONObject(index);
            String audioPath = row.optString("audioPath", "");
            File audio = new File(audioPath).getCanonicalFile();
            File root = new File(filesDir, "voice-pending").getCanonicalFile();
            if (!audio.toPath().startsWith(root.toPath()) || !available.contains(
                filesDir.getCanonicalFile().toPath()
                    .relativize(audio.toPath()).toString().replace(File.separatorChar, '/')
            )) {
                throw invalid("A pending transcription references missing or out-of-scope audio.");
            }
        }
    }

    private void addAsset(JSONArray result, String category, File file, File allowedRoot) throws Exception {
        if (result.length() >= MAX_ASSETS) throw invalid("The installed files exceed the supported import count.");
        File canonicalRoot = allowedRoot.getCanonicalFile();
        File canonical = file.getCanonicalFile();
        File filesDirectory = context.getFilesDir().getCanonicalFile();
        if (Files.isSymbolicLink(file.toPath()) ||
            !canonical.toPath().startsWith(canonicalRoot.toPath()) ||
            !canonicalRoot.toPath().startsWith(filesDirectory.toPath()) ||
            !canonical.toPath().startsWith(filesDirectory.toPath()) ||
            !canonical.isFile() || !canonical.canRead()) {
            throw invalid("A private data file is missing or unreadable: " + category);
        }
        long length = canonical.length();
        if (length < 0 || length > MAX_ASSET_BYTES || totalAssetBytes + length > MAX_TOTAL_ASSET_BYTES) {
            throw invalid("The installed files exceed the supported import size.");
        }
        String digest = sha256(canonical);
        String id = sha256((canonical.getAbsolutePath() + "\u0000" + length + "\u0000" + canonical.lastModified()).getBytes(StandardCharsets.UTF_8));
        InstalledDataMigrationPlugin.AssetDescriptor descriptor =
            new InstalledDataMigrationPlugin.AssetDescriptor(canonical, length, canonical.lastModified(), digest);
        assetRegistry.put(id, descriptor);
        totalAssetBytes += length;
        String relativePath = context.getFilesDir().getCanonicalFile().toPath()
            .relativize(canonical.toPath()).toString().replace(File.separatorChar, '/');
        result.put(new JSObject()
            .put("assetId", id)
            .put("category", category)
            .put("relativePath", relativePath)
            .put("byteLength", length)
            .put("sha256", digest));
    }

    private JSONArray readNativeFiles(JSObject database) throws Exception {
        JSONArray files = new JSONArray();
        File filesDir = context.getFilesDir().getCanonicalFile();
        File keyDirectory = new File(filesDir, "ssh-keys");
        if (Files.isSymbolicLink(keyDirectory.toPath())) throw invalid("The SSH key directory is a symbolic link.");
        File keyRoot = keyDirectory.getCanonicalFile();
        if (!keyRoot.toPath().startsWith(filesDir.toPath())) throw invalid("The SSH key directory is outside app-private storage.");
        File[] keys = keyRoot.exists() ? keyRoot.listFiles() : new File[0];
        Set<String> indexed = new LinkedHashSet<>();

        JSONObject tables = database.optJSONObject("tables");
        JSONArray keyRows = tables == null ? null : tables.optJSONArray("ssh_keys");
        if (keyRows != null) {
            for (int index = 0; index < keyRows.length(); index++) {
                JSONObject row = keyRows.getJSONObject(index);
                long keyId = row.getLong("id");
                String rawPath = row.getString("privateKeyPath");
                File keyFile = checkedKeyFile(rawPath, keyRoot);
                indexed.add(keyFile.getCanonicalPath());
                files.put(nativeFileRecord("ssh-private-key", filesDir, keyFile)
                    .put("keyId", keyId));
            }
        }
        if (keys == null) throw invalid("The SSH key directory could not be listed.");
        Arrays.sort(keys, (left, right) -> left.getName().compareTo(right.getName()));
        for (File key : keys) {
            if (Files.isSymbolicLink(key.toPath())) throw invalid("The SSH key directory contains a symbolic link.");
            if (!key.isFile()) throw invalid("The SSH key directory contains an unsupported entry.");
            if (!indexed.contains(key.getCanonicalPath())) files.put(nativeFileRecord("ssh-private-key-unindexed", filesDir, key));
        }
        return files;
    }

    private JSObject nativeFileRecord(String category, File filesDir, File file) throws Exception {
        File canonical = file.getCanonicalFile();
        File canonicalFilesDir = filesDir.getCanonicalFile();
        if (Files.isSymbolicLink(file.toPath()) ||
            !canonical.toPath().startsWith(canonicalFilesDir.toPath()) || !canonical.isFile() || !canonical.canRead()) {
            throw invalid("A legacy SSH key file is missing or unreadable.");
        }
        if (canonical.length() > MAX_KEY_FILE_BYTES) throw invalid("A legacy SSH key file exceeds the supported size.");
        return new JSObject()
            .put("category", category)
            .put("relativePath", canonicalFilesDir.toPath().relativize(canonical.toPath()).toString().replace(File.separatorChar, '/'))
            .put("byteLength", canonical.length())
            .put("lastModified", canonical.lastModified());
    }

    private File checkedKeyFile(String path, File keyRoot) throws Exception {
        File source = new File(path);
        if (Files.isSymbolicLink(source.toPath())) throw invalid("A referenced legacy SSH key is a symbolic link.");
        File value = source.getCanonicalFile();
        if (!value.getParentFile().equals(keyRoot) || !value.isFile() || !value.canRead()) {
            throw invalid("A referenced legacy SSH key is missing or outside the private key directory.");
        }
        return value;
    }

    private static String requiredAttribute(XmlPullParser parser, String name) throws MigrationReadException {
        String value = parser.getAttributeValue(null, name);
        if (value == null) throw invalid("A legacy preference is missing a required XML attribute.");
        return value;
    }

    private static String roomIdentityHash(SQLiteDatabase db) throws Exception {
        try (Cursor cursor = db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42", null)) {
            if (!cursor.moveToFirst()) throw invalid("The installed database is missing its Room identity record.");
            return cursor.getString(0);
        }
    }

    private static long scalarLong(SQLiteDatabase db, String sql) throws Exception {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (!cursor.moveToFirst()) throw invalid("The installed database metadata is incomplete.");
            return cursor.getLong(0);
        }
    }

    private static String scalarString(SQLiteDatabase db, String sql) throws Exception {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            if (!cursor.moveToFirst()) throw invalid("The installed database integrity check returned no result.");
            return cursor.getString(0);
        }
    }

    private static String identityForVersion(int version) {
        for (int index = 0; index < ROOM_VERSIONS.length; index++) {
            if (ROOM_VERSIONS[index] == version) return ROOM_IDENTITY_HASHES[index];
        }
        return null;
    }

    private static String[] expectedTables(int version) {
        List<String> tables = new ArrayList<>(Arrays.asList(TABLE_NAMES));
        if (version < 20) tables.remove("sent_messages");
        if (version == 16) {
            tables.add("sessions");
            tables.add("agent_sessions");
        }
        return tables.toArray(new String[0]);
    }

    private static Map<String, List<String>> expectedColumns(int version) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("hosts", csv("id,name,hostname,port,username,keyId,maxAutoPort,skipPortsBelow,scanIntervalSec,enabled,createdAt,lastConnectedAt" +
            (version <= 20 ? ",tmuxInstalled" : "") +
            ",lastBootstrapAt,pocketshellInstalled,pocketshellLastDetectedAt,pocketshellCliVersion,pocketshellExpectedCliVersion,pocketshellVersionCompatible,pocketshellDaemonRunning,pocketshellDaemonEnabled,usageCommandOverride" +
            (version >= 18 ? ",treeIdentity" : "") +
            (version >= 19 ? ",trustedHostKeyAlgorithm,trustedHostKeySha256" : "")));
        columns.put("ssh_keys", csv("id,name,privateKeyPath,fingerprint,hasPassphrase,createdAt"));
        columns.put("port_remappings", csv("id,hostId,remotePort,localPort" + (version >= 21 ? ",name" : "")));
        columns.put("port_usage", csv("hostId,remotePort,clickCount,totalBytes,lastUsedAt"));
        columns.put("project_roots", csv("id,hostId,label,path,createdAt" + (version >= 22 ? ",sortOrder" : "")));
        columns.put("snippets", csv("id,hostId,label,body,kind"));
        columns.put("ai_api_call_log", csv("id,timestampMillis,provider,feature,inputUnits,outputUnits,unitCostUsdMillicents,computedCostUsdMillicents,metadataJson"));
        columns.put("pending_transcriptions", csv("id,audioPath,recordingTimestampMs,destinationContext,retryCount,lastErrorMessage,audioByteSize,createdAtMs"));
        columns.put("command_templates", csv("id,hostId,label,commands"));
        if (version >= 20) columns.put("sent_messages", csv("id,sessionKey,body,sentAtMs,delivered"));
        if (version == 16) {
            columns.put("sessions", csv("id,hostId,name,lastSeenAt,tags"));
            columns.put("agent_sessions", csv("id,paneRef,agent,jsonlPath,detectedAt"));
        }
        return columns;
    }

    private static List<String> csv(String value) {
        return Arrays.asList(value.split(","));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String sha256(byte[] value) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private static MigrationReadException invalid(String message) {
        return new MigrationReadException(message);
    }

    static final class MigrationReadException extends Exception {
        MigrationReadException(String message) { super(message); }
    }
}
