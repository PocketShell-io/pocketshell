package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.After;
import org.junit.Test;

/**
 * Issue #3060: the gateway byte transport, driven by a real WebSocket server
 * on loopback (the test-only insecure flag: the shipped plugin never sets
 * it). Covers the v1 handshake, the ordered binary stream, and the bounds —
 * including the proof that an oversized frame is refused from its HEADER,
 * while the peer is still stalling on the payload, so no allocation can ever
 * follow a declared length past the cap.
 */
public final class GatewayTunnelTest {
    private static final String ROUTING_TOKEN = "routing-token-secret-1";
    private static final String DEVICE = "host-1";
    private static final long OPEN_DEADLINE = System.currentTimeMillis() + 15_000;

    private FakeGateway gateway;

    @After public void tearDown() throws InterruptedException {
        if (gateway != null) gateway.stop(0);
    }

    private GatewayTunnel openTunnel(FakeGateway server) throws Exception {
        server.start();
        assertTrue("the fixture gateway must bind", server.started.await(10, TimeUnit.SECONDS));
        GatewayTargetPolicy.Target target = GatewayTargetPolicy.normalizeTarget(
                "ws://127.0.0.1:" + server.getPort(), DEVICE);
        GatewayTunnel tunnel = new GatewayTunnel(target, ROUTING_TOKEN, true);
        tunnel.open(OPEN_DEADLINE);
        return tunnel;
    }

    @Test public void openSendsTheAuthFrameThenStreamsOrderedBytesBothWays() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.READY_ECHO);
        try (GatewayTunnel tunnel = openTunnel(gateway)) {
            assertTrue(gateway.authReceived.await(10, TimeUnit.SECONDS));
            org.json.JSONObject auth = new org.json.JSONObject(gateway.receivedAuthFrame);
            assertEquals("auth", auth.getString("type"));
            assertEquals(1, auth.getInt("v"));
            assertEquals("the routing token travels in the auth frame, natively",
                    ROUTING_TOKEN, auth.getString("token"));
            assertEquals(DEVICE, auth.getString("device_id"));

            // Outbound: one write larger than one frame, chunked by the tunnel.
            byte[] payload = new byte[150_000];
            for (int index = 0; index < payload.length; index++) payload[index] = (byte) index;
            OutputStream out = tunnel.socket().getOutputStream();
            out.write(payload);
            out.flush();

            // Inbound: the fixture echoes everything back; ordering must hold
            // across many frames that do not align with read() calls.
            byte[] echoed = readFully(tunnel.socket().getInputStream(), payload.length, 20_000);
            assertTrue(java.util.Arrays.equals(payload, echoed));
        }
    }

    @Test public void inboundFramesArriveInOrderAndComplete() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.READY_THEN_FRAGMENTED_ECHO);
        try (GatewayTunnel tunnel = openTunnel(gateway)) {
            byte[] echoed = readFully(tunnel.socket().getInputStream(), 100 + 10 + 1, 10_000);
            assertEquals("aaaaaaaaaa", new String(java.util.Arrays.copyOfRange(echoed, 0, 10)));
            assertEquals("frame boundaries must not leak into the byte stream", 'b', echoed[100]);
            assertEquals('c', echoed[110]);
        }
    }

    @Test public void anOversizedFrameRefusesFromItsHeaderWhileThePeerStalls() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.READY_THEN_OVERSIZED_HEADER);
        try (GatewayTunnel tunnel = openTunnel(gateway)) {
            long start = System.currentTimeMillis();
            try {
                // The fixture wrote ONLY a frame header declaring 2 MiB and
                // then stalls. A client that bounded after allocating would
                // block here until the payload never arrives; a bounded
                // client refuses from the header alone.
                tunnel.socket().getInputStream().read();
                fail("an oversized frame must fail the tunnel");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("gateway"));
            }
            assertTrue("the refusal must come from the header, not a payload wait",
                    System.currentTimeMillis() - start < 10_000);
        }
    }

    @Test public void binaryDataBeforeReadyIsAProtocolViolation() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.BINARY_BEFORE_READY);
        try {
            openTunnel(gateway);
            fail("binary before ready must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException expected) {
            assertEquals(GatewayTunnel.GatewayTunnelException.PROTOCOL, expected.code);
        }
    }

    @Test public void aMalformedHandshakeRefuses() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.MALFORMED_TEXT);
        try {
            openTunnel(gateway);
            fail("a malformed handshake must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException expected) {
            assertEquals(GatewayTunnel.GatewayTunnelException.PROTOCOL, expected.code);
        }
    }

    @Test public void anErrorFrameRefusesWithItsDocumentedCloseCode() throws Exception {
        // #3086: the frame's code is the gateway's verdict; 7b882759e
        // collapsed every error frame into UNREACHABLE with no close code.
        gateway = new FakeGateway(FakeGateway.Script.ERROR_FRAME);
        try {
            openTunnel(gateway);
            fail("a gateway error frame must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException expected) {
            assertEquals(GatewayTunnel.GatewayTunnelException.HOST_OFFLINE, expected.code);
            assertEquals(4503, expected.closeCode);
            assertTrue(expected.hasGatewayCloseCode());
            assertTrue("the gateway's free text is never surfaced", !expected.getMessage().contains("not now"));
        }
    }

    @Test public void aReadyFrameForAnotherDeviceRefuses() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.WRONG_DEVICE_READY);
        try {
            openTunnel(gateway);
            fail("a ready frame for another route must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException expected) {
            assertEquals(GatewayTunnel.GatewayTunnelException.PROTOCOL, expected.code);
        }
    }

    @Test public void aProtocolCloseDuringAuthenticationClassifies() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.CLOSE_4503_BEFORE_READY);
        try {
            openTunnel(gateway);
            fail("a 4503 close before ready must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException expected) {
            assertEquals(GatewayTunnel.GatewayTunnelException.HOST_OFFLINE, expected.code);
            assertEquals("the close frame's code is kept for core", 4503, expected.closeCode);
        }
    }

    @Test public void aLocalProtocolRefusalCarriesNoGatewayCloseCode() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.MALFORMED_TEXT);
        try {
            openTunnel(gateway);
            fail("a malformed handshake must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException expected) {
            assertEquals("the client refused; the gateway gave no verdict",
                    GatewayTunnel.GatewayTunnelException.NO_CLOSE_CODE, expected.closeCode);
        }
    }

    @Test public void aTextFrameAfterReadyIsAProtocolViolation() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.READY_THEN_TEXT);
        try (GatewayTunnel tunnel = openTunnel(gateway)) {
            tunnel.socket().getInputStream().read();
            fail("TEXT after ready must fail the tunnel");
        } catch (IOException expected) {
            // open() succeeded; the violation surfaces on the stream
        }
    }

    @Test public void cancellationJoinsADialingTunnel() throws Exception {
        gateway = new FakeGateway(FakeGateway.Script.SILENT);
        gateway.start();
        assertTrue(gateway.started.await(10, TimeUnit.SECONDS));
        GatewayTargetPolicy.Target target = GatewayTargetPolicy.normalizeTarget(
                "ws://127.0.0.1:" + gateway.getPort(), DEVICE);
        GatewayTunnel tunnel = new GatewayTunnel(target, ROUTING_TOKEN, true);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread dialer = new Thread(() -> {
            try {
                tunnel.open(System.currentTimeMillis() + 30_000);
            } catch (Throwable failure) {
                outcome.set(failure);
            }
        }, "test-gateway-dial");
        dialer.start();
        Thread.sleep(200);
        tunnel.close();
        dialer.join(5_000);
        assertTrue("close() must end the pending dial promptly", !dialer.isAlive());
        assertNotNull(outcome.get());
    }

    // --- fixture ------------------------------------------------------------------

    // --- fixture ------------------------------------------------------------------

    /** A scripted loopback gateway. Plaintext ws:// on 127.0.0.1 only — the
     * same test-only shape GatewayTunnel's insecure flag accepts. */
    static final class FakeGateway extends WebSocketServer {
        enum Script {
            READY_ECHO, READY_THEN_FRAGMENTED_ECHO, READY_THEN_OVERSIZED_HEADER,
            READY_THEN_TEXT, BINARY_BEFORE_READY, MALFORMED_TEXT, ERROR_FRAME,
            WRONG_DEVICE_READY, CLOSE_4503_BEFORE_READY, SILENT
        }

        final Script script;
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch authReceived = new CountDownLatch(1);
        volatile String receivedAuthFrame;
        private boolean handshakeAnswered;

        FakeGateway(Script script) {
            super(new InetSocketAddress("127.0.0.1", 0));
            this.script = script;
            setConnectionLostTimeout(0);
        }

        @Override public void onStart() {
            started.countDown();
        }

        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) { }

        @Override
        public synchronized void onMessage(WebSocket conn, String message) {
            if (receivedAuthFrame == null) {
                receivedAuthFrame = message;
                authReceived.countDown();
                answerHandshake(conn);
                return;
            }
            // Any later TEXT is the client's protocol error; ignore.
        }

        private void answerHandshake(WebSocket conn) {
            if (handshakeAnswered) return;
            handshakeAnswered = true;
            try {
                switch (script) {
                    case READY_ECHO, READY_THEN_FRAGMENTED_ECHO, READY_THEN_OVERSIZED_HEADER, READY_THEN_TEXT ->
                        conn.send("{\"type\":\"ready\",\"v\":1,\"device_id\":\"host-1\",\"ssh_host_key\":\"ssh-ed25519 AAAAADVISORY\"}");
                    case WRONG_DEVICE_READY ->
                        conn.send("{\"type\":\"ready\",\"v\":1,\"device_id\":\"other-host\",\"ssh_host_key\":\"\"}");
                    case MALFORMED_TEXT -> conn.send("hello there");
                    case ERROR_FRAME ->
                        conn.send("{\"type\":\"error\",\"v\":1,\"code\":\"host_offline\",\"message\":\"not now\"}");
                    case BINARY_BEFORE_READY -> conn.send(new byte[] {1, 2, 3});
                    case CLOSE_4503_BEFORE_READY -> conn.close(4503, "agent offline");
                    case SILENT -> { }
                }
            } catch (RuntimeException ignored) {
                // the client-side assertion covers the outcome
            }
            // Follow-ups run after the handshake answer is out.
            if (script == Script.READY_THEN_FRAGMENTED_ECHO) {
                new Thread(() -> {
                    try {
                        conn.send(binaryFrame('a', 100));
                        conn.send(binaryFrame('b', 10));
                        conn.send(binaryFrame('c', 1));
                    } catch (RuntimeException ignored) {
                    }
                }, "fixture-fragmented-echo").start();
            }
            if (script == Script.READY_THEN_TEXT) {
                new Thread(() -> {
                    try {
                        Thread.sleep(150);
                        conn.send("{\"type\":\"unexpected\",\"v\":1}");
                    } catch (Exception ignored) {
                    }
                }, "fixture-text-after-ready").start();
            }
            if (script == Script.READY_THEN_OVERSIZED_HEADER) {
                new Thread(() -> {
                    try {
                        Thread.sleep(150);
                        // BIN+FIN, 127 (64-bit length follows), 2 MiB — and
                        // then NOTHING: the payload never arrives.
                        ByteBuffer header = ByteBuffer.allocate(10);
                        header.put((byte) 0x82).put((byte) 0x7F).putLong(2L * 1024 * 1024);
                        header.flip();
                        while (header.hasRemaining()) ((SocketChannel) ((WebSocketImpl) conn).getChannel()).write(header);
                    } catch (Exception ignored) {
                    }
                }, "fixture-oversized-header").start();
            }
        }

        private static byte[] binaryFrame(char filler, int length) {
            byte[] payload = new byte[length];
            java.util.Arrays.fill(payload, (byte) filler);
            return payload;
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            if (script == Script.READY_ECHO) {
                byte[] payload = new byte[message.remaining()];
                message.get(payload);
                conn.send(payload);
            }
            // Other scripts intentionally ignore client bytes.
        }

        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            System.out.println("[fixture] onClose code=" + code + " reason=" + reason + " remote=" + remote);
        }

        @Override public void onError(WebSocket conn, Exception error) {
            System.out.println("[fixture] onError");
            error.printStackTrace();
        }
    }

    private static byte[] readFully(InputStream input, int length, int timeoutMs) throws IOException {
        byte[] out = new byte[length];
        int read = 0;
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (read < length) {
            int count = input.read(out, read, length - read);
            if (count < 0) throw new IOException("the tunnel closed after " + read + " of " + length + " bytes");
            read += count;
            if (read < length && System.currentTimeMillis() > deadline) {
                throw new IOException("the tunnel stalled after " + read + " of " + length + " bytes");
            }
        }
        return out;
    }
}
