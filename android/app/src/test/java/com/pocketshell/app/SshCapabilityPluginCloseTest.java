package com.pocketshell.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;
import net.schmizz.sshj.SSHClient;
import org.junit.Test;

public final class SshCapabilityPluginCloseTest {
    @Test
    public void actualConnectionCloseKeepsSnapshotEntryUntilNativeSocketCloses() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch allowCloseToReturn = new CountDownLatch(1);
        BlockingCloseSshClient client = new BlockingCloseSshClient(closeStarted, allowCloseToReturn);
        SshCapabilityPlugin.SshConnection connection = new SshCapabilityPlugin.SshConnection(
            "connection-1", "generation-1", "host-1", "connect-request-1", client
        );
        registry(plugin, "connections").put(connection.connectionId, connection);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();

        Thread closer = new Thread(() -> {
            try {
                plugin.closeConnection(connection, "test-close", false);
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "test-production-ssh-close");
        closer.start();

        try {
            assertTrue("production client close must enter the controlled blocking window",
                    closeStarted.await(1, TimeUnit.SECONDS) || closeFailure.get() != null);
            if (closeFailure.get() != null) throw new AssertionError("production close path failed before native close", closeFailure.get());
            assertFalse("the captured SSH socket must still be open while close is blocked", client.socket.isClosed());
            assertEquals("the production snapshot must retain the connection until its physical socket close returns",
                    1, plugin.currentResourceSnapshot().connections);
        } finally {
            allowCloseToReturn.countDown();
            closer.join(1_000);
        }

        assertFalse("the production close thread must finish", closer.isAlive());
        assertTrue("the production close path must close the captured socket", client.socket.isClosed());
        assertNull("native close must not fail", closeFailure.get());
        assertEquals("the production snapshot must drop the connection after physical close returns",
                0, plugin.currentResourceSnapshot().connections);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> registry(SshCapabilityPlugin plugin, String name)
            throws Exception {
        Field field = SshCapabilityPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, T>) field.get(plugin);
    }

    @Test
    public void productionPtyCloseKeepsSnapshotEntryUntilNativeChannelCloses() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = new SshCapabilityPlugin.SshConnection(
            "connection-pty", "generation-pty", "host-1", "request-pty", null
        );
        assertTrue("fixture must reserve the production PTY channel permit", connection.channelPermits.tryAcquire());
        BlockingCloseOperation close = new BlockingCloseOperation();
        SshCapabilityPlugin.SshPty pty = new SshCapabilityPlugin.SshPty(
            "pty-1", connection, null, null, close.operation()
        );
        registry(plugin, "ptys").put(pty.channelId, pty);

        assertBlockedCloseKeepsProductionSnapshot(
            plugin, "PTY", () -> plugin.closePty(pty), close,
            snapshot -> snapshot.ptys
        );
    }

    @Test
    public void productionSftpCloseKeepsSnapshotEntryUntilNativeClientCloses() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = new SshCapabilityPlugin.SshConnection(
            "connection-sftp", "generation-sftp", "host-1", "request-sftp", null
        );
        registry(plugin, "connections").put(connection.connectionId, connection);
        BlockingCloseOperation close = new BlockingCloseOperation();
        connection.sftpResource = new SshCapabilityPlugin.SftpResource(null, close.operation());

        assertBlockedCloseKeepsProductionSnapshot(
            plugin, "SFTP", () -> plugin.closeSftpClient(connection), close,
            snapshot -> snapshot.sftpClients
        );
    }

    @Test
    public void productionForwardCloseKeepsSnapshotEntryUntilNativeListenerCloses() throws Exception {
        SshCapabilityPlugin plugin = new SshCapabilityPlugin(null, () -> 123L);
        SshCapabilityPlugin.SshConnection connection = new SshCapabilityPlugin.SshConnection(
            "connection-forward", "generation-forward", "host-1", "request-forward", null
        );
        assertTrue("fixture must reserve the production forward channel permit", connection.channelPermits.tryAcquire());
        assertTrue("fixture must reserve the production forward permit", forwardPermits().tryAcquire());
        BlockingCloseOperation close = new BlockingCloseOperation();
        SshCapabilityPlugin.SshForward forward = new SshCapabilityPlugin.SshForward(
            "forward-1", connection, null, null, 2222, close.operation()
        );
        registry(plugin, "forwards").put(forward.forwardId, forward);

        assertBlockedCloseKeepsProductionSnapshot(
            plugin, "forward", () -> plugin.closeForward(forward), close,
            snapshot -> snapshot.forwards
        );
    }

    private static Semaphore forwardPermits() throws Exception {
        Field field = SshCapabilityPlugin.class.getDeclaredField("FORWARD_PERMITS");
        field.setAccessible(true);
        return (Semaphore) field.get(null);
    }

    private static void assertBlockedCloseKeepsProductionSnapshot(
            SshCapabilityPlugin plugin,
            String resourceName,
            Runnable closeOperation,
            BlockingCloseOperation close,
            ToIntFunction<SshCapabilityPlugin.NativeResourceSnapshot> count
    ) throws Exception {
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                closeOperation.run();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "test-production-" + resourceName.toLowerCase() + "-close");
        closer.start();

        try {
            assertTrue(resourceName + " native close must enter its controlled blocking window",
                    close.started.await(2, TimeUnit.SECONDS) || closeFailure.get() != null);
            if (closeFailure.get() != null) {
                throw new AssertionError(resourceName + " production close failed before native close", closeFailure.get());
            }
            assertEquals(resourceName + " production snapshot must retain its count while native close is blocked",
                    1, count.applyAsInt(plugin.currentResourceSnapshot()));
        } finally {
            close.allowReturn.countDown();
            closer.join(2_000);
        }

        assertFalse(resourceName + " production close thread must finish after native close returns", closer.isAlive());
        assertNull(resourceName + " native close callback must not fail", closeFailure.get());
        assertEquals(resourceName + " production snapshot count must reach zero after native close returns",
                0, count.applyAsInt(plugin.currentResourceSnapshot()));
    }

    private static final class BlockingCloseOperation {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch allowReturn = new CountDownLatch(1);

        Runnable operation() {
            return () -> {
                started.countDown();
                try {
                    if (!allowReturn.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test did not release the native close barrier");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("native close barrier was interrupted", interrupted);
                }
            };
        }
    }

    private static final class BlockingCloseSshClient extends SSHClient {
        private final CountDownLatch closeStarted;
        private final CountDownLatch allowCloseToReturn;
        private final Socket socket = new Socket();

        BlockingCloseSshClient(CountDownLatch closeStarted, CountDownLatch allowCloseToReturn) {
            this.closeStarted = closeStarted;
            this.allowCloseToReturn = allowCloseToReturn;
        }

        @Override
        public Socket getSocket() {
            return socket;
        }

        @Override
        public void disconnect() throws IOException {
            closeStarted.countDown();
            try {
                if (!allowCloseToReturn.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release the native close barrier");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("native close barrier was interrupted", interrupted);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    @Test
    public void nativeWorkerAccountingReturnsToZeroAfterCompletionOrFailure() {
        AtomicInteger activeWorkers = new AtomicInteger();

        Runnable successfulWorker = SshCapabilityPlugin.trackedWorker(activeWorkers, () -> {
            assertEquals("running native work must be visible to the resource snapshot", 1, activeWorkers.get());
        });
        successfulWorker.run();
        assertEquals("completed native work must not remain counted", 0, activeWorkers.get());

        Runnable failedWorker = SshCapabilityPlugin.trackedWorker(activeWorkers, () -> {
            assertEquals("failing native work must be visible before it exits", 1, activeWorkers.get());
            throw new IllegalStateException("injected worker failure");
        });
        try {
            failedWorker.run();
        } catch (IllegalStateException expected) {
            assertEquals("failed native work must release its worker count", 0, activeWorkers.get());
            return;
        }
        throw new AssertionError("tracked worker must preserve work failures");
    }

    @Test
    public void rawSocketFallbackClosesSocketWhenSshjDisconnectThrows() throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            Socket clientSocket = new Socket("127.0.0.1", listener.getLocalPort());
            try (Socket serverSocket = listener.accept()) {
                SshCapabilityPlugin.ClientCloseAttempt attempt = SshCapabilityPlugin.closeSshjTransportAndSocket(
                    clientSocket,
                    () -> { throw new IOException("injected disconnect failure"); },
                    () -> { throw new IOException("injected close failure"); }
                );

                assertTrue("raw fallback must close the captured SSH socket", clientSocket.isClosed());
                assertTrue("fallback use must be reported", attempt.rawSocketCloseFallbackUsed);
                assertTrue("physical close must be reported", attempt.socketClosed);
                assertEquals("IOException", attempt.disconnectErrorClass);
                assertEquals("IOException", attempt.clientCloseErrorClass);
                assertEquals("", attempt.rawSocketCloseErrorClass);
                assertTrue("successful raw close must not report a close error", attempt.rawSocketCloseErrorClass.isEmpty());
            } finally {
                if (!clientSocket.isClosed()) clientSocket.close();
            }
        }
    }
}
