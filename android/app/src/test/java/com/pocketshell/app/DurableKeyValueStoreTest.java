package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Issue #2993: the native store behind localStorage must survive an abrupt process death. */
public final class DurableKeyValueStoreTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void committedWriteAndRemovalSurviveAnImmediateProcessHalt() throws Exception {
        File directory = folder.newFolder("store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        store.importAll(Map.of("pocketshell.js.host-snippets.v1", "{\"chips\":[\"keep\",\"drop\"]}", "gone", "x"));

        // A separate JVM writes, then halts the moment put/remove return: no
        // shutdown hooks, no finalizers, the same as a SIGKILL right after the
        // UI acknowledged the change.
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process child = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                HaltAfterWrite.class.getName(), directory.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
        assertTrue("child JVM did not exit", child.waitFor(60, TimeUnit.SECONDS));
        String output = new String(child.getInputStream().readAllBytes());
        assertEquals("child must halt right after its writes returned: " + output, 77, child.exitValue());

        Map<String, String> restarted = new DurableKeyValueStore(directory).readAll();
        assertEquals("{\"chips\":[\"keep\"]}", restarted.get("pocketshell.js.host-snippets.v1"));
        assertFalse(restarted.containsKey("gone"));
        assertEquals("gruvbox-dark", restarted.get("theme"));
    }

    @Test
    public void entriesRoundTripExactJavaScriptStringsAcrossInstances() throws Exception {
        File directory = folder.newFolder("store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("emoji 🙂 β", "multi\nline\u0000with nul");
        values.put("", "empty key");
        values.put("lone-surrogate", "x\uD83Dy");
        values.put("k".repeat(4096), "long key needs a bounded file name");
        values.put("empty-value", "");
        for (Map.Entry<String, String> entry : values.entrySet()) store.put(entry.getKey(), entry.getValue());

        assertEquals(values, new DurableKeyValueStore(directory).readAll());
    }

    @Test
    public void storeIsAuthoritativeOnlyAfterACompleteImport() throws Exception {
        File directory = folder.newFolder("store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        assertFalse(store.isInitialized());
        store.put("written-before-import", "1");
        assertFalse("a stray entry must not mark the store authoritative", store.isInitialized());

        store.importAll(Map.of("a", "1", "b", "2"));

        DurableKeyValueStore restarted = new DurableKeyValueStore(directory);
        assertTrue(restarted.isInitialized());
        assertEquals(Map.of("a", "1", "b", "2", "written-before-import", "1"), restarted.readAll());
    }

    @Test
    public void removeAndClearAreDurableAndIdempotent() throws Exception {
        File directory = folder.newFolder("store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        store.importAll(Map.of("a", "1", "b", "2", "c", "3"));
        store.remove("a");
        store.remove("a");
        store.remove("never-written");
        assertEquals(Map.of("b", "2", "c", "3"), new DurableKeyValueStore(directory).readAll());

        store.clear();
        DurableKeyValueStore restarted = new DurableKeyValueStore(directory);
        assertTrue(restarted.readAll().isEmpty());
        assertTrue("clear keeps the import marker", restarted.isInitialized());
    }

    @Test
    public void clearIsAllOrNothingWhenItCannotComplete() throws Exception {
        File parent = folder.newFolder("parent");
        File directory = new File(parent, "store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        store.importAll(Map.of("a", "1", "b", "2"));

        // The swap cannot happen in a read-only parent: nothing may be lost.
        assertTrue(parent.setWritable(false));
        try {
            assertThrows(IOException.class, store::clear);
        } finally {
            assertTrue(parent.setWritable(true));
        }
        assertEquals(Map.of("a", "1", "b", "2"), new DurableKeyValueStore(directory).readAll());

        // A kill between the two renames is completed by the next call.
        File next = new File(parent, "store.next");
        assertTrue(next.mkdirs());
        assertTrue(new File(next, "initialized").createNewFile());
        assertTrue(directory.renameTo(new File(parent, "store.old")));
        DurableKeyValueStore restarted = new DurableKeyValueStore(directory);
        assertTrue(restarted.isInitialized());
        assertTrue(restarted.readAll().isEmpty());
        assertFalse(new File(parent, "store.old").exists());
        assertFalse(next.exists());
    }

    @Test
    public void aWriteThatDiedBeforeItsRenameLeavesThePreviousValue() throws Exception {
        File directory = folder.newFolder("store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        store.put("theme", "light");
        File abandoned = new File(directory, DurableKeyValueStore.fileNameFor("theme") + ".tmp");
        try (FileOutputStream output = new FileOutputStream(abandoned)) {
            output.write(new byte[] {0x50, 0x53});
        }

        assertEquals(Map.of("theme", "light"), new DurableKeyValueStore(directory).readAll());
        assertFalse("the abandoned partial write is cleaned up", abandoned.exists());
    }

    @Test
    public void aCorruptEntryFailsTheReadInsteadOfSilentlyDroppingData() throws Exception {
        File directory = folder.newFolder("store");
        DurableKeyValueStore store = new DurableKeyValueStore(directory);
        store.put("theme", "light");
        Files.write(new File(directory, DurableKeyValueStore.fileNameFor("theme")).toPath(), new byte[] {1, 2, 3});

        assertThrows(IOException.class, () -> new DurableKeyValueStore(directory).readAll());
    }

    /** Child-JVM entry point for the halt test. */
    public static final class HaltAfterWrite {
        public static void main(String[] args) throws Exception {
            DurableKeyValueStore store = new DurableKeyValueStore(new File(args[0]));
            store.put("pocketshell.js.host-snippets.v1", "{\"chips\":[\"keep\"]}");
            store.remove("gone");
            store.put("theme", "gruvbox-dark");
            Runtime.getRuntime().halt(77);
        }
    }
}
