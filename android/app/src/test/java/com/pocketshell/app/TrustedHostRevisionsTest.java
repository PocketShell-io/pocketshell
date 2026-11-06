package com.pocketshell.app;
import static org.junit.Assert.*;
import org.junit.Test;
public final class TrustedHostRevisionsTest {
    static final class Memory implements GatewayPairingStore.Repository {
        String value;
        public String read() { return value; }
        public void write(String json) { value = json; }
    }
    @Test public void floorsSurviveReloadAndNeverCrossAccountOrVault() throws Exception {
        Memory memory = new Memory();
        TrustedHostRevisions first = new TrustedHostRevisions(memory);
        String vault = "A".repeat(22), slot = "th-" + "B".repeat(22);
        first.save("google:one", vault, slot, 3);
        TrustedHostRevisions next = new TrustedHostRevisions(memory);
        assertEquals(3, next.load("google:one", vault).getLong(slot));
        assertEquals(0, next.load("google:two", vault).length());
        assertEquals(0, next.load("google:one", "C".repeat(22)).length());
        try { next.save("google:one", vault, slot, 2); fail("rollback"); } catch (java.io.IOException expected) { }
        assertEquals(3, next.load("google:one", vault).getLong(slot));
    }
    @Test public void malformedProtectedStorageFailsClosed() throws Exception {
        Memory memory = new Memory(); memory.value = "broken";
        try { new TrustedHostRevisions(memory).load("google:one", "A".repeat(22)); fail("tampering"); } catch (Exception expected) { }
        assertEquals("broken", memory.value);
    }
    @Test public void numericFloorsRefuseFractionsStringsAndUnsafeIntegers() throws Exception {
        for (Object value : new Object[] {1.5, "3", 0L, 9007199254740992L}) {
            org.json.JSONObject row = new org.json.JSONObject().put("revision", value);
            try { TrustedHostRevisions.positive(row, "revision"); fail("invalid numeric floor"); }
            catch (java.io.IOException expected) { }
        }
    }
    @Test public void slotMatchesSharedJsonArrayHash() throws Exception {
        assertEquals("th-S9OyMfV4tcZ_QPHpsTKybg", TrustedHostRevisions.slot("wss://gateway.pocketshell.io", "dev-1"));
    }
    /** Distinct repository wrappers model recreated plugin contexts sharing protected storage. */
    private static GatewayPairingStore.Repository wrapper(Memory memory) {
        return new GatewayPairingStore.Repository() {
            public String read() { return memory.read(); }
            public void write(String json) { memory.write(json); }
        };
    }
    private static final class PausedWrite implements GatewayPairingStore.Repository {
        final Memory memory;
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        PausedWrite(Memory memory) { this.memory = memory; }
        public String read() { return memory.read(); }
        public void write(String json) throws java.io.IOException {
            entered.countDown();
            try {
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.io.IOException("Writer release timed out");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
            memory.write(json);
        }
    }
    private static void awaitBlocked(Thread thread) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < deadline) Thread.yield();
        assertEquals("new context must wait outside the protected transaction", Thread.State.BLOCKED, thread.getState());
    }
    @Test public void recreatedPluginCannotLowerFloorWhileShutdownWorkerFinishes() throws Exception {
        Memory memory = new Memory();
        String vault = "A".repeat(22), slot = "th-" + "B".repeat(22);
        new TrustedHostRevisions(wrapper(memory)).save("google:one", vault, slot, 1);
        PausedWrite oldRepository = new PausedWrite(memory);
        java.util.concurrent.ExecutorService oldExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicReference<Throwable> lowResult = new java.util.concurrent.atomic.AtomicReference<>();
        Thread nextWorker = new Thread(() -> {
            try { new TrustedHostRevisions(wrapper(memory)).save("google:one", vault, slot, 2); }
            catch (Throwable error) { lowResult.set(error); }
        }, "recreated-plugin-writer");
        java.util.concurrent.Future<?> high = oldExecutor.submit(() -> {
            try { new TrustedHostRevisions(oldRepository).save("google:one", vault, slot, 3); }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        try {
            assertTrue(oldRepository.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            oldExecutor.shutdown(); // Same lifecycle as plugin destruction: running work continues.
            nextWorker.start();
            awaitBlocked(nextWorker);
            oldRepository.release.countDown();
            high.get(3, java.util.concurrent.TimeUnit.SECONDS);
            nextWorker.join(3000);
            assertFalse(nextWorker.isAlive());
            assertTrue("lower revision must be refused", lowResult.get() instanceof java.io.IOException);
            assertEquals(3, new TrustedHostRevisions(wrapper(memory)).load("google:one", vault).getLong(slot));
        } finally {
            oldRepository.release.countDown();
            oldExecutor.shutdownNow();
            assertTrue(oldExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));
            nextWorker.join(3000);
        }
    }
    @Test public void concurrentContextsPreserveSlotsAndAccountVaultNamespaces() throws Exception {
        Memory memory = new Memory();
        String vault = "A".repeat(22), otherVault = "C".repeat(22);
        String slot = "th-" + "B".repeat(22), otherSlot = "th-" + "D".repeat(22);
        PausedWrite firstRepository = new PausedWrite(memory);
        java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread first = new Thread(() -> {
            try { new TrustedHostRevisions(firstRepository).save("google:one", vault, slot, 3); }
            catch (Throwable error) { errors.add(error); }
        }, "old-context");
        Thread second = new Thread(() -> {
            try {
                TrustedHostRevisions store = new TrustedHostRevisions(wrapper(memory));
                store.save("google:one", vault, otherSlot, 4);
                store.save("google:two", vault, slot, 5);
                store.save("google:one", otherVault, slot, 6);
            } catch (Throwable error) { errors.add(error); }
        }, "new-context");
        try {
            first.start();
            assertTrue(firstRepository.entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            second.start();
            awaitBlocked(second);
            firstRepository.release.countDown();
            first.join(3000); second.join(3000);
            assertFalse(first.isAlive()); assertFalse(second.isAlive());
            assertTrue(errors.toString(), errors.isEmpty());
            TrustedHostRevisions reader = new TrustedHostRevisions(wrapper(memory));
            assertEquals(3, reader.load("google:one", vault).getLong(slot));
            assertEquals(4, reader.load("google:one", vault).getLong(otherSlot));
            assertEquals(5, reader.load("google:two", vault).getLong(slot));
            assertEquals(6, reader.load("google:one", otherVault).getLong(slot));
        } finally {
            firstRepository.release.countDown();
            first.join(3000); second.join(3000);
        }
    }

}
