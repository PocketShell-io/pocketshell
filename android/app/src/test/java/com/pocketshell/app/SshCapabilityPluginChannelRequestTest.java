package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.hierynomus.sshj.key.KeyAlgorithm;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.schmizz.concurrent.Promise;
import net.schmizz.keepalive.KeepAlive;
import net.schmizz.sshj.Config;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.Service;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.DisconnectReason;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.common.Message;
import net.schmizz.sshj.common.SSHPacket;
import net.schmizz.sshj.connection.Connection;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.Channel;
import net.schmizz.sshj.connection.channel.OpenFailException;
import net.schmizz.sshj.connection.channel.direct.SessionChannel;
import net.schmizz.sshj.connection.channel.direct.Signal;
import net.schmizz.sshj.connection.channel.forwarded.ForwardedChannelOpener;
import net.schmizz.sshj.transport.DisconnectListener;
import net.schmizz.sshj.transport.Transport;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.AlgorithmsVerifier;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import org.junit.Test;

/**
 * #3039: a PTY channel request must never reach the host after this side's
 * CLOSE. OpenSSH frees a channel number once both CLOSEs are exchanged and
 * answers any later request for it by dropping the WHOLE connection
 * ({@code server_input_channel_req: unknown channel 0}, reproduced against a
 * real sshd by core's SessionEndVerdict integration suite). sshj writes
 * channel requests without looking at the channel's state, so a resize racing
 * a session's exit turned a clean session end into a transport loss and a
 * full reconnect in SharedAppDockerJourneyTest.
 *
 * These specs drive the production PTY channel factory ({@code
 * startPtySession}, what {@code openPty} opens) over sshj's real channel code
 * with a recording transport in place of the socket, and read the exact
 * packet order the host would have received.
 */
public final class SshCapabilityPluginChannelRequestTest {
    @Test
    public void ptyResizeAfterTheChannelClosedNeverReachesTheHost() throws Exception {
        Recording host = new Recording();
        SessionChannel channel = SshCapabilityPlugin.startPtySession(host.client());

        channel.changeWindowDimensions(80, 24, 0, 0);
        assertEquals("a resize on an open PTY is delivered",
                Arrays.asList("CHANNEL_OPEN", "CHANNEL_REQUEST window-change"), host.sent());

        // The session ends on the host: its CLOSE arrives, sshj answers it.
        channel.handle(Message.CHANNEL_CLOSE, new SSHPacket());
        assertEquals("sshj answers the host's CLOSE with ours",
                "CHANNEL_CLOSE", host.sent().get(host.sent().size() - 1));

        // The pane pushes its geometry a moment too late, and a signal follows.
        channel.changeWindowDimensions(100, 30, 0, 0);
        channel.signal(Signal.HUP);

        // sshj closes its output (CHANNEL_EOF) before its CLOSE; that is fine,
        // the host still has the channel then. What may not exist is
        // anything AFTER our CLOSE.
        List<String> sent = host.sent();
        assertEquals("nothing may follow our CLOSE: the host has freed the channel and would drop the connection: "
                + sent, "CHANNEL_CLOSE", sent.get(sent.size() - 1));
        assertEquals("only the resize sent while the channel was open reached the host: " + sent,
                1, Collections.frequency(sent, "CHANNEL_REQUEST window-change"));
        assertFalse("no signal after the close: " + sent, sent.contains("CHANNEL_REQUEST signal"));
    }

    @Test
    public void ptyResizeRacingTheHostsCloseNeverLandsAfterOurClose() throws Exception {
        for (int round = 0; round < 300; round += 1) {
            Recording host = new Recording();
            SessionChannel channel = SshCapabilityPlugin.startPtySession(host.client());
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread resizer = new Thread(() -> {
                try {
                    start.await();
                    int cols = 80;
                    while (!stop.get()) channel.changeWindowDimensions(cols++ % 200 + 20, 24, 0, 0);
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "test-pty-resizer");
            Thread reader = new Thread(() -> {
                try {
                    start.await();
                    channel.handle(Message.CHANNEL_CLOSE, new SSHPacket());
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "test-sshj-reader");
            resizer.start();
            reader.start();
            start.countDown();
            reader.join(2_000);
            stop.set(true);
            resizer.join(2_000);
            assertFalse(resizer.isAlive() || reader.isAlive());
            if (failure.get() != null) throw new AssertionError("round " + round, failure.get());

            List<String> sent = host.sent();
            int close = sent.indexOf("CHANNEL_CLOSE");
            assertTrue("round " + round + ": our CLOSE went out: " + sent, close > 0);
            assertEquals("round " + round + ": nothing after our CLOSE: " + sent, sent.size() - 1, close);
        }
    }

    @Test
    public void openPtyRunsOnTheGuardedChannel() throws Exception {
        Recording host = new Recording();
        SessionChannel channel = SshCapabilityPlugin.startPtySession(host.client());
        assertTrue("openPty's channel must be the guarded PTY channel",
                channel instanceof SshCapabilityPlugin.PtySessionChannel);
        assertFalse(((SshCapabilityPlugin.PtySessionChannel) channel).closeSent());
        channel.handle(Message.CHANNEL_CLOSE, new SSHPacket());
        assertTrue(((SshCapabilityPlugin.PtySessionChannel) channel).closeSent());
    }

    /**
     * #3039 review B1: {@code closePty} closing the channel while sshj's
     * reader answers the host's CLOSE must never deadlock. sshj's
     * {@code close()} holds the channel's {@code openCloseLock} when it calls
     * {@code sendClose()}; the reader's {@code gotClose()} calls
     * {@code sendClose()} with no lock and takes {@code openCloseLock} inside
     * it. A guard held around {@code super.sendClose()} took the two locks in
     * opposite orders and froze both threads — the connection's reader (every
     * pane on it) and Capacitor's single plugin thread.
     *
     * Two pause hooks force the losing interleaving deterministically: the
     * reader stops between {@code closeAllStreams()} and its
     * {@code sendClose()} until the closer holds {@code openCloseLock}; the
     * closer (inside {@code close()}, lock held, at its {@code isOpen()}
     * check) then waits until the reader is parked inside
     * {@code sendClose()} before going on.
     */
    @Test
    public void ptyCloseRacingTheHostsCloseNeverDeadlocks() throws Exception {
        Recording host = new Recording();
        CloseRace race = new CloseRace();
        CloseRaceChannel channel = new CloseRaceChannel(host.connection, race);
        channel.open();
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                race.readerPastStreams.await();
                channel.close();
            } catch (Throwable ignored) {
                // close() may time out waiting for the host's CLOSE echo on
                // the fake transport; only termination matters here.
            }
        }, "test-plugin-closePty");
        Thread reader = new Thread(() -> {
            try {
                channel.handle(Message.CHANNEL_CLOSE, new SSHPacket());
            } catch (Throwable error) {
                readerFailure.set(error);
            }
        }, "test-sshj-reader");
        closer.setDaemon(true);
        reader.setDaemon(true);
        race.closer = closer;
        race.reader = reader;
        reader.start();
        closer.start();
        closer.join(10_000);
        reader.join(10_000);

        assertTrue("the reader must have parked inside its sendClose while the closer held openCloseLock"
                + " (otherwise this run did not exercise the race)", race.readerParkedInSendClose);
        assertTrue("close() racing the host's CLOSE deadlocked: closer " + closer.getState()
                + " at " + Arrays.toString(closer.getStackTrace()) + ", reader " + reader.getState()
                + " at " + Arrays.toString(reader.getStackTrace()) + ", sent " + host.sent(),
                !closer.isAlive() && !reader.isAlive());
        if (readerFailure.get() != null) throw new AssertionError("reader", readerFailure.get());
        List<String> sent = host.sent();
        assertEquals("exactly one CLOSE went out: " + sent, 1, Collections.frequency(sent, "CHANNEL_CLOSE"));
        assertEquals("nothing after our CLOSE: " + sent, "CHANNEL_CLOSE", sent.get(sent.size() - 1));
        assertTrue(channel.closeSent());
        channel.changeWindowDimensions(90, 24, 0, 0);
        assertEquals("a resize after the race is dropped: " + host.sent(), sent, host.sent());
    }

    /** Pause hooks that force close() and the reader's gotClose() into the losing interleaving. */
    private static final class CloseRace {
        final CountDownLatch readerPastStreams = new CountDownLatch(1);
        final CountDownLatch closerHoldsLock = new CountDownLatch(1);
        volatile Thread closer;
        volatile Thread reader;
        volatile boolean readerParkedInSendClose;

        /** Closer, inside close() with openCloseLock held: let the reader reach its sendClose first. */
        void closerPause() {
            if (Thread.currentThread() != closer) return;
            closerHoldsLock.countDown();
            long until = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < until) {
                boolean inSendClose = false;
                for (StackTraceElement frame : reader.getStackTrace()) {
                    if (frame.getMethodName().equals("sendClose")) inSendClose = true;
                }
                Thread.State state = reader.getState();
                if (inSendClose && (state == Thread.State.WAITING || state == Thread.State.BLOCKED)) {
                    readerParkedInSendClose = true;
                    return;
                }
                Thread.yield();
            }
        }

        /** Reader, between closeAllStreams() and sendClose() in gotClose(). */
        void readerPause() {
            if (Thread.currentThread() != reader) return;
            readerPastStreams.countDown();
            try {
                closerHoldsLock.await();
            } catch (InterruptedException error) {
                throw new RuntimeException(error);
            }
        }
    }

    /** The production PTY channel with the two race hooks; nothing else overridden. */
    private static final class CloseRaceChannel extends SshCapabilityPlugin.PtySessionChannel {
        private final CloseRace race;

        CloseRaceChannel(Connection connection, CloseRace race) {
            super(connection, StandardCharsets.UTF_8);
            this.race = race;
        }

        @Override
        public boolean isOpen() {
            if (race != null) race.closerPause();
            return super.isOpen();
        }

        @Override
        protected void closeAllStreams() {
            super.closeAllStreams();
            if (race != null) race.readerPause();
        }
    }

    /** An sshj connection whose transport records what would go on the wire. */
    private static final class Recording {
        private final List<String> sent = Collections.synchronizedList(new ArrayList<>());
        private final RecordingTransport transport = new RecordingTransport(this);
        private final FakeConnection connection = new FakeConnection(transport);

        List<String> sent() {
            synchronized (sent) {
                return new ArrayList<>(sent);
            }
        }

        SSHClient client() {
            return new SSHClient(transport.config) {
                @Override public boolean isConnected() { return true; }
                @Override public boolean isAuthenticated() { return true; }
                @Override public Connection getConnection() { return connection; }
                @Override public Charset getRemoteCharset() { return StandardCharsets.UTF_8; }
            };
        }

        void record(SSHPacket packet) {
            Buffer.PlainBuffer copy = new Buffer.PlainBuffer(
                    Arrays.copyOfRange(packet.array(), packet.rpos(), packet.wpos()));
            try {
                Message message = Message.fromByte(copy.readByte());
                String entry = message.name();
                if (message == Message.CHANNEL_REQUEST) {
                    copy.readUInt32();
                    entry += " " + copy.readString();
                }
                sent.add(entry);
                if (message == Message.CHANNEL_OPEN) confirmOpen();
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }

        /** The host confirms every channel open at once (the reader thread's job). */
        private void confirmOpen() throws Exception {
            Channel channel = connection.attached.get();
            assertNotNull(channel);
            SSHPacket confirmation = new SSHPacket();
            confirmation.putUInt32(0).putUInt32(2 * 1024 * 1024).putUInt32(32 * 1024);
            channel.handle(Message.CHANNEL_OPEN_CONFIRMATION, confirmation);
        }
    }

    private static final class FakeConnection implements Connection {
        final AtomicReference<Channel> attached = new AtomicReference<>();
        private final Transport transport;
        private int nextId;

        FakeConnection(Transport transport) {
            this.transport = transport;
        }

        @Override public void attach(Channel channel) { attached.set(channel); }
        @Override public void attach(ForwardedChannelOpener opener) {}
        @Override public void forget(Channel channel) {}
        @Override public void forget(ForwardedChannelOpener opener) {}
        @Override public Channel get(int id) { return attached.get(); }
        @Override public void join() {}
        @Override public ForwardedChannelOpener get(String type) { return null; }
        @Override public synchronized int nextID() { return nextId++; }
        @Override public Promise<SSHPacket, ConnectionException> sendGlobalRequest(String name, boolean wantReply, byte[] data) {
            throw new UnsupportedOperationException();
        }
        @Override public void sendOpenFailure(int recipient, OpenFailException.Reason reason, String message) {}
        @Override public int getMaxPacketSize() { return 32 * 1024; }
        @Override public void setMaxPacketSize(int size) {}
        @Override public long getWindowSize() { return 2 * 1024 * 1024; }
        @Override public void setWindowSize(long size) {}
        @Override public Transport getTransport() { return transport; }
        @Override public int getTimeoutMs() { return 2_000; }
        @Override public void setTimeoutMs(int timeout) {}
        @Override public KeepAlive getKeepAlive() { return null; }
    }

    private static final class RecordingTransport implements Transport {
        final Config config = new DefaultConfig();
        private final Recording recording;

        RecordingTransport(Recording recording) {
            this.recording = recording;
        }

        @Override public long write(SSHPacket packet) {
            recording.record(packet);
            return 0;
        }
        @Override public Config getConfig() { return config; }
        @Override public int getTimeoutMs() { return 2_000; }
        @Override public boolean isRunning() { return true; }
        @Override public boolean isAuthenticated() { return true; }
        @Override public void init(String host, int port, java.io.InputStream in, java.io.OutputStream out) {}
        @Override public void addHostKeyVerifier(HostKeyVerifier verifier) {}
        @Override public void addAlgorithmsVerifier(AlgorithmsVerifier verifier) {}
        @Override public void doKex() {}
        @Override public String getClientVersion() { return "test"; }
        @Override public void setTimeoutMs(int timeout) {}
        @Override public String getRemoteHost() { return "test"; }
        @Override public int getRemotePort() { return 22; }
        @Override public String getServerVersion() { return "test"; }
        @Override public byte[] getSessionID() { return new byte[0]; }
        @Override public Service getService() { return null; }
        @Override public void reqService(Service service) {}
        @Override public void setService(Service service) {}
        @Override public void setAuthenticated() {}
        @Override public long sendUnimplemented() { return 0; }
        @Override public void join() {}
        @Override public void join(int timeout, TimeUnit unit) {}
        @Override public void disconnect() {}
        @Override public void disconnect(DisconnectReason reason) {}
        @Override public void disconnect(DisconnectReason reason, String message) {}
        @Override public void setDisconnectListener(DisconnectListener listener) {}
        @Override public DisconnectListener getDisconnectListener() { return null; }
        @Override public void die(Exception error) {}
        @Override public KeyAlgorithm getHostKeyAlgorithm() { return null; }
        @Override public List<KeyAlgorithm> getClientKeyAlgorithms(KeyType type) { return Collections.emptyList(); }
        @Override public void handle(Message message, SSHPacket packet) {}
        @Override public InetSocketAddress getRemoteSocketAddress() { return null; }
    }
}
