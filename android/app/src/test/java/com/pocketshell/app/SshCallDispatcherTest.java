package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Test;

public class SshCallDispatcherTest {
    private final SshCallDispatcher dispatcher = SshCallDispatcher.withDaemonWorkers("test-ssh-call");

    @After
    public void drained() throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (dispatcher.activeLaneCount() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(5);
        assertEquals("lanes must not leak", 0, dispatcher.activeLaneCount());
    }

    @Test
    public void slowExecDoesNotDelayAnotherPluginCall() throws Exception {
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        dispatcher.submit(null, () -> {
            slowStarted.countDown();
            await(releaseSlow);
        });
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
        dispatcher.submit(null, fastDone::countDown);
        // The fast call must finish while the slow exec is still blocked.
        assertTrue("fast call queued behind slow exec", fastDone.await(1, TimeUnit.SECONDS));
        releaseSlow.countDown();
    }

    @Test
    public void blockedReadPtyDoesNotDelayWriteOnSameChannelOrOtherLanes() throws Exception {
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch writeDone = new CountDownLatch(1);
        CountDownLatch sftpDone = new CountDownLatch(1);
        dispatcher.submit("pty-read:a", () -> { readStarted.countDown(); await(releaseRead); });
        assertTrue(readStarted.await(2, TimeUnit.SECONDS));
        dispatcher.submit("pty-op:a", writeDone::countDown);
        dispatcher.submit("sftp:c", sftpDone::countDown);
        assertTrue(writeDone.await(1, TimeUnit.SECONDS));
        assertTrue(sftpDone.await(1, TimeUnit.SECONDS));
        releaseRead.countDown();
    }

    @Test
    public void sameLaneRunsInSubmissionOrderOneAtATime() throws Exception {
        int count = 200;
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicInteger concurrent = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxConcurrent = new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch done = new CountDownLatch(count);
        for (int index = 0; index < count; index++) {
            int id = index;
            dispatcher.submit("pty-op:a", () -> {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                if (id % 17 == 0) sleep(2);
                order.add(id);
                concurrent.decrementAndGet();
                done.countDown();
            });
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, maxConcurrent.get());
        for (int index = 0; index < count; index++) assertEquals(Integer.valueOf(index), order.get(index));
    }

    @Test
    public void throwingTaskDoesNotWedgeItsLane() throws Exception {
        CountDownLatch next = new CountDownLatch(1);
        dispatcher.submit("l", () -> { throw new IllegalStateException("boom"); });
        dispatcher.submit("l", next::countDown);
        assertTrue(next.await(2, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
