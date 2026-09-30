package com.pocketshell.app;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

/** Resolves an old SSH key id directly inside the Android private-data boundary. */
final class LegacyPrivateKeyResolver {
    private static final int MAX_PRIVATE_KEY_BYTES = 1024 * 1024;

    private LegacyPrivateKeyResolver() {}

    static String readPrivateKey(Context context, long keyId, String expectedSha256) throws IOException {
        if (keyId < 1 || expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) {
            throw new IOException("Saved SSH key reference is invalid.");
        }
        File database = context.getDatabasePath("pocketshell.db");
        if (Files.isSymbolicLink(database.toPath()) || !database.isFile() || !database.canRead()) {
            throw new IOException("Saved SSH key database is unavailable.");
        }

        String storedPath;
        SQLiteDatabase source = null;
        try {
            source = SQLiteDatabase.openDatabase(database.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor cursor = source.rawQuery(
                "SELECT privateKeyPath FROM ssh_keys WHERE id = ?",
                new String[] {Long.toString(keyId)}
            )) {
                if (!cursor.moveToFirst() || cursor.isNull(0)) {
                    throw new IOException("Saved SSH key is no longer available.");
                }
                storedPath = cursor.getString(0);
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Saved SSH key could not be resolved safely.");
        } finally {
            if (source != null) source.close();
        }

        File filesDirectory = context.getFilesDir().getCanonicalFile();
        File keyDirectory = new File(filesDirectory, "ssh-keys");
        if (Files.isSymbolicLink(keyDirectory.toPath()) || !keyDirectory.isDirectory()) {
            throw new IOException("Saved SSH key storage is unavailable.");
        }
        File canonicalKeyDirectory = keyDirectory.getCanonicalFile();
        if (!canonicalKeyDirectory.toPath().startsWith(filesDirectory.toPath())) {
            throw new IOException("Saved SSH key storage is outside app-private storage.");
        }
        File keyFile = new File(storedPath);
        if (Files.isSymbolicLink(keyFile.toPath())) {
            throw new IOException("Saved SSH key is a symbolic link and cannot be opened safely.");
        }
        File canonicalKeyFile = keyFile.getCanonicalFile();
        if (!canonicalKeyDirectory.equals(canonicalKeyFile.getParentFile()) ||
            !canonicalKeyFile.isFile() || !canonicalKeyFile.canRead() ||
            canonicalKeyFile.length() < 1 || canonicalKeyFile.length() > MAX_PRIVATE_KEY_BYTES) {
            throw new IOException("Saved SSH key is missing or outside app-private key storage.");
        }
        byte[] contents = Files.readAllBytes(canonicalKeyFile.toPath());
        if (contents.length < 1 || contents.length > MAX_PRIVATE_KEY_BYTES) {
            throw new IOException("Saved SSH key exceeds the supported size.");
        }
        if (!expectedSha256.equals(sha256(contents))) {
            throw new IOException("Saved SSH key changed since the installed-data import.");
        }
        return new String(contents, StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] contents) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(contents);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (Exception error) {
            throw new IOException("Saved SSH key could not be checked safely.");
        }
    }
}
