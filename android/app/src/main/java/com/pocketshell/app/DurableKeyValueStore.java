package com.pocketshell.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Crash-durable string key/value store behind the WebView's localStorage
 * (issue #2993).
 *
 * <p>Android WebView keeps localStorage writes in memory and commits them to
 * disk later, so a process kill shortly after a write silently loses it. Every
 * mutating call here returns only after the bytes are fsynced and atomically
 * renamed into place, so once it returns the write survives SIGKILL. (The parent directory is not
 * fsynced after the rename: this targets process death, not power loss.)
 *
 * <p>One file per key keeps each write proportional to that entry, instead of
 * rewriting every user record on each keystroke-sized change. A file holds a
 * magic header and the key and value as raw UTF-16 code units, so any JS
 * string (including a lone surrogate) round-trips exactly. The file name is the
 * SHA-256 of the key, which bounds name length for arbitrarily long keys.
 *
 * <p>The {@code initialized} marker is written only after a complete one-time
 * import of the pre-existing localStorage contents; until it exists the store
 * is not authoritative and the import is simply retried on the next launch.
 */
public final class DurableKeyValueStore {
    private static final int MAGIC = 0x50533239; // "PS29"
    private static final int FORMAT_VERSION = 1;
    private static final String ENTRY_SUFFIX = ".entry";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String MARKER_NAME = "initialized";
    private static final String NEXT_SUFFIX = ".next";
    private static final String OLD_SUFFIX = ".old";

    private final File directory;

    public DurableKeyValueStore(File directory) {
        this.directory = directory;
    }

    public synchronized boolean isInitialized() throws IOException {
        recover();
        return new File(directory, MARKER_NAME).isFile();
    }

    /** Every committed entry, in no particular order. Unreadable entries fail the whole read. */
    public synchronized Map<String, String> readAll() throws IOException {
        ensureDirectory();
        Map<String, String> entries = new LinkedHashMap<>();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Durable storage directory could not be listed.");
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(TEMP_SUFFIX)) {
                // A write that died before its rename never became visible.
                if (!file.delete() && file.exists()) throw new IOException("Could not remove an abandoned write.");
                continue;
            }
            if (!name.endsWith(ENTRY_SUFFIX)) continue;
            String[] entry = readEntry(file);
            if (!name.equals(fileNameFor(entry[0]))) {
                throw new IOException("Durable storage entry " + name + " does not match its key.");
            }
            entries.put(entry[0], entry[1]);
        }
        return entries;
    }

    synchronized void put(String key, String value) throws IOException {
        requireKey(key);
        if (value == null) throw new IOException("Durable storage values must not be null.");
        ensureDirectory();
        writeAtomically(new File(directory, fileNameFor(key)), key, value);
    }

    synchronized void remove(String key) throws IOException {
        requireKey(key);
        recover();
        File file = new File(directory, fileNameFor(key));
        if (!file.delete() && file.exists()) throw new IOException("Durable storage entry could not be removed.");
    }

    /**
     * All-or-nothing clear: an empty, already-initialized replacement directory
     * is prepared and fsynced first, then swapped in by two renames. A failure
     * before the first rename leaves every entry in place; a kill between the
     * renames is completed by {@link #recover()} on the next call.
     */
    synchronized void clear() throws IOException {
        ensureDirectory();
        File next = sibling(NEXT_SUFFIX);
        File old = sibling(OLD_SUFFIX);
        deleteTree(next);
        deleteTree(old);
        if (!next.mkdirs()) throw new IOException("Durable storage could not prepare an empty store.");
        boolean initialized = new File(directory, MARKER_NAME).isFile();
        if (initialized) writeMarker(next);
        Files.move(directory.toPath(), old.toPath(), StandardCopyOption.ATOMIC_MOVE);
        Files.move(next.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE);
        deleteTree(old);
    }

    /**
     * One-time import of the WebView's existing localStorage. Entries land
     * first and the marker last, so a kill mid-import leaves the store
     * non-authoritative and the next launch repeats the (idempotent) import.
     */
    synchronized void importAll(Map<String, String> entries) throws IOException {
        ensureDirectory();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
        writeMarker(directory);
    }

    private static void writeMarker(File target) throws IOException {
        File marker = new File(target, MARKER_NAME);
        File temp = new File(target, MARKER_NAME + TEMP_SUFFIX);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(new byte[] {'1', '\n'});
            output.flush();
            output.getFD().sync();
        }
        Files.move(temp.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Finish or discard an interrupted {@link #clear()}. */
    private void recover() throws IOException {
        File next = sibling(NEXT_SUFFIX);
        File old = sibling(OLD_SUFFIX);
        if (!directory.exists() && next.isDirectory()) {
            Files.move(next.toPath(), directory.toPath(), StandardCopyOption.ATOMIC_MOVE);
        }
        if (directory.isDirectory()) {
            deleteTree(next);
            deleteTree(old);
        }
    }

    private File sibling(String suffix) {
        return new File(directory.getParentFile(), directory.getName() + suffix);
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.isDirectory() ? file.listFiles() : null;
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete() && file.exists()) throw new IOException("Could not remove " + file.getName());
    }

    private void ensureDirectory() throws IOException {
        recover();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Durable storage directory could not be created.");
        }
    }

    private static void requireKey(String key) throws IOException {
        if (key == null) throw new IOException("Durable storage keys must not be null.");
    }

    private static void writeAtomically(File target, String key, String value) throws IOException {
        File temp = new File(target.getPath() + TEMP_SUFFIX);
        try (FileOutputStream file = new FileOutputStream(temp);
                DataOutputStream output = new DataOutputStream(new BufferedOutputStream(file))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeInt(key.length());
            output.writeChars(key);
            output.writeInt(value.length());
            output.writeChars(value);
            output.flush();
            file.getFD().sync();
        } catch (IOException error) {
            temp.delete();
            throw error;
        }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String[] readEntry(File file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IOException("Durable storage entry " + file.getName() + " has an unknown format.");
            }
            String key = readChars(input, file);
            String value = readChars(input, file);
            if (input.read() != -1) throw new IOException("Durable storage entry " + file.getName() + " has trailing bytes.");
            return new String[] {key, value};
        }
    }

    private static String readChars(DataInputStream input, File file) throws IOException {
        int length = input.readInt();
        long remaining = file.length();
        if (length < 0 || (long) length * 2 > remaining) {
            throw new IOException("Durable storage entry " + file.getName() + " is truncated.");
        }
        char[] chars = new char[length];
        for (int index = 0; index < length; index++) chars[index] = input.readChar();
        return new String(chars);
    }

    static String fileNameFor(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int index = 0; index < key.length(); index++) {
                char unit = key.charAt(index);
                digest.update((byte) (unit >>> 8));
                digest.update((byte) unit);
            }
            StringBuilder name = new StringBuilder(64 + ENTRY_SUFFIX.length());
            for (byte part : digest.digest()) {
                name.append(Character.forDigit((part >>> 4) & 0xf, 16)).append(Character.forDigit(part & 0xf, 16));
            }
            return name.append(ENTRY_SUFFIX).toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
