package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * #3141 on the REAL plugin path: the @PluginMethod entry points route through
 * {@link SshCallRouting}, so a slow exec cannot delay another plugin call,
 * and the lane table is complete and keyed correctly.
 */
public final class SshCapabilityPluginRoutingTest {
    static final class FakeCall extends PluginCall {
        final CountDownLatch done = new CountDownLatch(1);
        volatile JSObject resolved;
        volatile String rejectedCode;
        FakeCall(String method, JSObject data) { super(null, "SshCapability", "cb-" + method, method, data); }
        @Override public void resolve(JSObject data) { resolved = data; done.countDown(); }
        @Override public void reject(String msg, String code, Exception ex, JSObject data) { rejectedCode = code; done.countDown(); }
        boolean await(long ms) throws InterruptedException { return done.await(ms, TimeUnit.MILLISECONDS); }
    }

    private static SshCapabilityPlugin plugin() { return new SshCapabilityPlugin(null, () -> 123L); }

    private static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    public void everyPluginMethodHasExactlyOneRoutingEntry() {
        List<String> missing = new ArrayList<>();
        int methods = 0;
        for (Method method : SshCapabilityPlugin.class.getDeclaredMethods()) {
            if (method.getAnnotation(PluginMethod.class) == null) continue;
            methods++;
            if (!SshCallRouting.isRouted(method.getName())) missing.add(method.getName());
        }
        assertTrue("expected the full SSH surface, saw " + methods, methods >= 24);
        assertTrue("unrouted @PluginMethods: " + missing, missing.isEmpty());
    }

    @Test
    public void laneKeysGroupByTheResourceTheProtocolOrdersOn() {
        JSObject pty = new JSObject().put("channelId", "ch-1").put("connectionId", "c-1");
        String write = SshCallRouting.lane("writePty", pty);
        assertEquals("resize and close ride the write lane so input/size/close stay ordered", write, SshCallRouting.lane("resizePty", pty));
        assertEquals(write, SshCallRouting.lane("closePty", pty));
        assertNotEquals("a blocked read must not queue a write", write, SshCallRouting.lane("readPty", pty));
        assertNotEquals("another channel is another lane", write, SshCallRouting.lane("writePty", new JSObject().put("channelId", "ch-2")));
        assertEquals(SshCallRouting.lane("sftpList", pty), SshCallRouting.lane("sftpWrite", pty));
        assertEquals("chunked uploads queue with every other SFTP call (#3191)", SshCallRouting.lane("sftpWrite", pty), SshCallRouting.lane("sftpWriteChunk", pty));
        assertEquals(SshCallRouting.lane("closeConnection", pty), SshCallRouting.lane("scheduleClose", pty));
        assertEquals(SshCallRouting.lane("openPortForward", pty), SshCallRouting.lane("closePortForward", pty));
        for (String free : new String[] {"exec", "connect", "cancelOperation", "openPty", "resourceSnapshot", "transportCapabilities"}) {
            assertEquals(free + " must be unordered", null, SshCallRouting.lane(free, pty));
        }
        try {
            SshCallRouting.lane("notARealMethod", pty);
            fail("an unrouted method must fail loudly");
        } catch (IllegalStateException expected) { /* ok */ }
    }

    @Test
    public void aSlowExecDoesNotDelayAnotherPluginCallOnTheRealPlugin() throws Exception {
        SshCapabilityPlugin plugin = plugin();
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeCall exec = new FakeCall("exec", new JSObject().put("connectionId", "c-1"));
        plugin.run(exec, "exec", options -> { slowStarted.countDown(); awaitQuietly(release); return new JSObject(); });
        assertTrue(slowStarted.await(2, TimeUnit.SECONDS));

        FakeCall capabilities = new FakeCall("transportCapabilities", new JSObject().put("requestId", "r"));
        plugin.transportCapabilities(capabilities);
        FakeCall state = new FakeCall("getConnectionState",
            new JSObject().put("requestId", "r2").put("connectionId", "none").put("generationId", "g"));
        plugin.getConnectionState(state);
        FakeCall snapshot = new FakeCall("resourceSnapshot", new JSObject().put("requestId", "r3"));
        plugin.resourceSnapshot(snapshot);
        try {
            assertTrue("transportCapabilities queued behind a slow exec", capabilities.await(1_000));
            assertTrue("getConnectionState queued behind a slow exec", state.await(1_000));
            assertTrue("resourceSnapshot queued behind a slow exec", snapshot.await(1_000));
            assertNotNull(capabilities.resolved);
            assertEquals("closed", state.resolved.getString("state"));
            assertTrue("the slow exec is genuinely still running", exec.resolved == null && exec.rejectedCode == null);
        } finally {
            release.countDown();
        }
        assertTrue(exec.await(2_000));
    }

    @Test
    public void aBlockedReadDoesNotDelayAWriteOnTheSameChannelButWritesStayOrdered() throws Exception {
        SshCapabilityPlugin plugin = plugin();
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JSObject channel = new JSObject().put("channelId", "ch-1");
        FakeCall read = new FakeCall("readPty", channel);
        plugin.run(read, "readPty", options -> { readStarted.countDown(); awaitQuietly(release); return new JSObject(); });
        assertTrue(readStarted.await(2, TimeUnit.SECONDS));
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());
        FakeCall write = new FakeCall("writePty", channel);
        FakeCall resize = new FakeCall("resizePty", channel);
        FakeCall close = new FakeCall("closePty", channel);
        plugin.run(write, "writePty", o -> { sleep(30); order.add("write"); return new JSObject(); });
        plugin.run(resize, "resizePty", o -> { order.add("resize"); return new JSObject(); });
        plugin.run(close, "closePty", o -> { order.add("close"); return new JSObject(); });
        try {
            assertTrue(close.await(2_000));
            assertEquals(List.of("write", "resize", "close"), order);
        } finally {
            release.countDown();
        }
    }

    @Test
    public void connectsAreBoundedAndCancelStillPreemptsThem() throws Exception {
        SshCapabilityPlugin plugin = plugin();
        int total = SshCapabilityPlugin.MAX_CONCURRENT_CONNECTS + 4;
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch full = new CountDownLatch(SshCapabilityPlugin.MAX_CONCURRENT_CONNECTS);
        List<FakeCall> calls = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            FakeCall call = new FakeCall("connect", new JSObject().put("requestId", "q" + index));
            calls.add(call);
            plugin.run(call, "connect", o -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                full.countDown();
                awaitQuietly(release);
                running.decrementAndGet();
                return new JSObject();
            });
        }
        assertTrue(full.await(2, TimeUnit.SECONDS));
        FakeCall cancel = new FakeCall("cancelOperation", new JSObject().put("requestId", "c")
            .put("target", new JSObject().put("kind", "connect").put("targetRequestId", "q0")));
        plugin.cancelOperation(cancel);
        try {
            assertTrue("cancel queued behind saturated connects", cancel.await(1_000));
            Thread.sleep(150);
            assertEquals("connects must be bounded", SshCapabilityPlugin.MAX_CONCURRENT_CONNECTS, peak.get());
        } finally {
            release.countDown();
        }
        for (FakeCall call : calls) assertTrue(call.await(3_000));
        assertEquals(SshCapabilityPlugin.MAX_CONCURRENT_CONNECTS, peak.get());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
