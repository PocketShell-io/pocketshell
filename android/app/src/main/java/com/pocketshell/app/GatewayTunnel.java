package com.pocketshell.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;

/**
 * The Android gateway byte transport (issue #3060): one ordered binary
 * WebSocket byte stream under the existing sshj engine, behind a real
 * {@link Socket}. The gateway relays opaque SSH bytes end-to-end; the routing
 * token travels in the one auth TEXT frame and lives NOWHERE else in the app
 * — not in any URL, header, log line, bridge reply, or error message.
 *
 * <p>Bounds and lifecycle (the security case this class exists for):
 * <ul>
 *   <li>The WebSocket draft is constructed with {@code maxFrameSize} equal to
 *       the protocol message cap; Java-WebSocket 1.5.7 checks the DECLARED
 *       frame payload length against it BEFORE allocating, and bounds the
 *       total accumulated fragment buffer the same way — a misbehaving
 *       gateway cannot drive an allocation larger than the cap, and no
 *       message is ever assembled past it.</li>
 *   <li>Binary frames enter a bounded queue (frames and total bytes); when it
 *       is full the READER thread blocks, which is the transport's read
 *       backpressure — TCP flow control pushes back on the gateway, and SSH
 *       channel windows push back on the host.</li>
 *   <li>Writes chunk into 64 KiB binary frames and block while the
 *       library's outbound queue exceeds a hard pending-bytes cap, with a
 *       finite stall deadline — the write path can never queue without
 *       bound.</li>
 *   <li>The auth→ready exchange is strictly the v1 handshake: one TEXT auth
 *       frame, then exactly one TEXT ready/error frame; any binary frame
 *       before ready, any TEXT frame after ready, a malformed or
 *       wrong-device ready frame, or a gateway error frame fails the tunnel
 *       closed. Close codes map to the protocol vocabulary; the gateway's
 *       free-text close REASON and ready key ADVISORY are never surfaced or
 *       trusted.</li>
 *   <li>{@code connectionLostTimeout} is disabled: the library's 60s
 *       ping/pong checker would kill a healthy idle SSH stream whenever the
 *       reader thread is legitimately blocked on the bounded queue. Liveness
 *       stays with the SSH layer, as in direct dials.</li>
 * </ul>
 *
 * <p>Production is WSS only, against the platform default CA trust, with SNI
 * and hostname verification set explicitly on the TLS socket and re-verified
 * against the negotiated session after the handshake. The insecure flag is a
 * test-only constructor parameter that additionally requires a loopback host;
 * the shipped plugin never sets it, so no synced target or stored record can
 * produce a plaintext dial.
 */
public final class GatewayTunnel implements AutoCloseable {
    /** Outbound SSH bytes are chunked at this frame size (≤ the 1 MiB cap). */
    static final int OUTBOUND_FRAME_BYTES = 64 * 1024;
    /** Hard cap on bytes queued for the wire; writers block above it. */
    static final int MAX_OUTBOUND_PENDING_BYTES = 1 << 20;
    /** Hard cap on buffered inbound bytes; the reader blocks above it. */
    static final int MAX_INBOUND_BUFFER_BYTES = 4 << 20;
    /** Hard cap on queued inbound frames (many tiny frames case). */
    static final int MAX_INBOUND_QUEUED_FRAMES = 64;
    /** How long a write may stay above the pending cap before failing. */
    static final long WRITE_STALL_TIMEOUT_MS = 30_000L;

    private final GatewayTargetPolicy.Target target;
    private final GatewayTargetPolicy.Endpoint endpoint;
    private final String routingToken;
    private final boolean insecureLoopbackAllowed;

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.DIALING);
    private final AtomicReference<GatewayTunnelException> failure = new AtomicReference<>();
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);

    /** An unrecognized handshake error frame arrived; the close frame that
     * follows carries the verdict. Any other TEXT now is a violation. */
    private volatile boolean errorFrameAwaitingClose;

    private final ArrayBlockingQueue<byte[]> inboundFrames = new ArrayBlockingQueue<>(MAX_INBOUND_QUEUED_FRAMES);
    private final Semaphore inboundBytes = new Semaphore(MAX_INBOUND_BUFFER_BYTES);

    private volatile WebSocketClient client;
    private volatile GatewayTunnelSocket socket;
    /** The socket's read timeout (java.net.Socket semantics); both streams honor it. */
    private volatile int socketSoTimeoutMs;

    /** DIALING → AUTHENTICATING (auth frame sent) → READY; FAILED/CLOSED are final. */
    private enum Phase { DIALING, AUTHENTICATING, READY, FAILED, CLOSED }

    /** A tunnel failure with a stable kind; the message is fixed text that
     * never carries gateway-supplied content, URLs, or token material.
     * {@link #closeCode} is the gateway's verdict when it gave one (a close
     * frame, or a handshake error frame mapped to its documented close
     * code), and {@link #NO_CLOSE_CODE} for transport failures the gateway
     * never ruled on. Core reads it as {@code data.gatewayCloseCode}. */
    public static final class GatewayTunnelException extends IOException {
        /** No gateway verdict: DNS/TCP/TLS failure, timeout, cancel, local protocol refusal. */
        public static final int NO_CLOSE_CODE = 0;
        public static final String PROTOCOL = "GATEWAY_PROTOCOL";
        public static final String UNAUTHORIZED = "GATEWAY_UNAUTHORIZED";
        public static final String FORBIDDEN = "GATEWAY_FORBIDDEN";
        public static final String HOST_UNKNOWN = "GATEWAY_HOST_UNKNOWN";
        public static final String TIMEOUT = "GATEWAY_TIMEOUT";
        public static final String QUOTA = "GATEWAY_QUOTA";
        public static final String HOST_OFFLINE = "GATEWAY_HOST_OFFLINE";
        public static final String UNREACHABLE = "GATEWAY_UNREACHABLE";
        public static final String CONNECT_FAILED = "GATEWAY_CONNECT_FAILED";

        final String code;
        final int closeCode;

        GatewayTunnelException(String code, String message) {
            this(code, message, NO_CLOSE_CODE);
        }

        GatewayTunnelException(String code, String message, int closeCode) {
            super(message);
            this.code = code;
            this.closeCode = closeCode;
        }

        /** Whether the gateway itself delivered this verdict. */
        boolean hasGatewayCloseCode() {
            return closeCode != NO_CLOSE_CODE;
        }
    }

    public GatewayTunnel(GatewayTargetPolicy.Target target, String routingToken, boolean insecureLoopbackAllowed) {
        this.target = target;
        this.routingToken = routingToken;
        this.insecureLoopbackAllowed = insecureLoopbackAllowed;
        this.endpoint = GatewayTargetPolicy.endpoint(target, insecureLoopbackAllowed);
    }

    /**
     * Dial, authenticate and reach READY within {@code budgetMs} from now,
     * measured on the monotonic clock (a wall-clock jump cannot move it). On
     * any failure the tunnel is closed and a {@link GatewayTunnelException}
     * is thrown; on success the socket is connected and its streams are live.
     */
    public void open(long budgetMs) throws GatewayTunnelException, InterruptedException {
        long deadlineMs = monotonicMs() + budgetMs;
        try {
            client = buildClient();
            // The library's own keepalive checker is disabled (class doc);
            // passing a non-positive timeout cancels it before it starts.
            client.setConnectionLostTimeout(0);
            socket = new GatewayTunnelSocket();
            long remaining = deadlineMs - monotonicMs();
            if (remaining <= 0) throw new GatewayTunnelException(
                    GatewayTunnelException.CONNECT_FAILED, "The gateway connection ran out of time before dialing.");
            boolean opened;
            try {
                opened = client.connectBlocking(remaining, TimeUnit.MILLISECONDS);
            } catch (IllegalStateException | IllegalArgumentException error) {
                throw failedOr(new GatewayTunnelException(
                        GatewayTunnelException.CONNECT_FAILED, "The gateway connection could not be dialed."));
            }
            if (!opened) {
                throw failedOr(new GatewayTunnelException(
                        GatewayTunnelException.TIMEOUT, "The gateway did not complete the connection in time."));
            }
            verifyTlsIdentity();
            if (!phase.compareAndSet(Phase.DIALING, Phase.AUTHENTICATING)) {
                throw failedOr(new GatewayTunnelException(
                        GatewayTunnelException.CONNECT_FAILED, "The gateway connection ended during the handshake."));
            }
            // The routing token travels in this one TEXT frame and nowhere else.
            sendText(GatewayTargetPolicy.buildAuthFrame(routingToken, target.deviceId));
            remaining = deadlineMs - monotonicMs();
            GatewayTunnelException failureWhileWaiting = awaitHandshakeFailure(remaining);
            if (failureWhileWaiting != null) throw failureWhileWaiting;
            if (phase.get() != Phase.READY) {
                throw new GatewayTunnelException(
                        GatewayTunnelException.TIMEOUT, "The gateway did not finish the handshake in time.");
            }
        } catch (GatewayTunnelException | InterruptedException error) {
            close();
            throw error;
        } catch (RuntimeException error) {
            close();
            throw failedOr(new GatewayTunnelException(
                    GatewayTunnelException.CONNECT_FAILED, "The gateway connection could not be established."));
        }
    }

    /** Every tunnel deadline (connect, read, write stall) is on this monotonic clock. */
    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** The connected socket for sshj's SocketFactory; valid after {@link #open}. */
    public Socket socket() {
        if (socket == null) throw new IllegalStateException("The gateway tunnel is not open.");
        return socket;
    }

    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) return;
        phase.set(Phase.CLOSED);
        // Wake a blocked reader/writer: the bounded structures poll the phase.
        inboundBytes.release(MAX_INBOUND_BUFFER_BYTES);
        WebSocketClient current = client;
        if (current != null) {
            try {
                current.close();
            } catch (Throwable ignored) {
            }
            try {
                current.closeBlocking();
            } catch (Throwable ignored) {
            }
        }
    }

    // --- WebSocketClient --------------------------------------------------------

    private WebSocketClient buildClient() throws GatewayTunnelException {
        URI uri;
        try {
            uri = new URI((endpoint.secure ? "wss://" : "ws://") + endpoint.host
                    + (endpoint.port > 0 ? ":" + endpoint.port : "") + endpoint.path);
        } catch (Exception error) {
            throw new GatewayTunnelException(GatewayTunnelException.CONNECT_FAILED, "The gateway address is not usable.");
        }
        WebSocketClient ws = new WebSocketClient(uri, new Draft_6455(
                Collections.emptyList(), GatewayTargetPolicy.MAX_WS_MESSAGE_BYTES)) {

            @Override
            public void onOpen(ServerHandshake handshake) {
                // The pinned Draft_6455 requested no extensions and no
                // subprotocol; nothing in the server's answer changes that.
            }

            @Override
            public void onMessage(String message) {
                onTextFrame(message);
            }

            @Override
            public void onMessage(ByteBuffer bytes) {
                onBinaryFrame(bytes);
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                onClosed(code, remote);
            }

            @Override
            public void onError(Exception error) {
                // The exception text can name network specifics; the failure
                // carries only a fixed sentence. Close codes still classify.
                failIfOpen(new GatewayTunnelException(
                        GatewayTunnelException.CONNECT_FAILED, "The gateway connection failed."));
            }

            @Override
            protected void onSetSSLParameters(SSLParameters parameters) {
                // Hostname validation plus explicit SNI for the CANONICAL
                // gateway host — never the display hostname.
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setServerNames(List.of(new javax.net.ssl.SNIHostName(endpoint.host)));
            }
        };
        if (endpoint.secure) {
            ws.setSocketFactory(gatewayTlsSocketFactory());
        }
        return ws;
    }

    /** Belt and braces over endpoint identification: after the handshake,
     * verify the negotiated session against the platform verifier for the
     * canonical gateway host. Android's SSLSocket support for endpoint
     * identification has varied by release; this check does not depend on it. */
    private void verifyTlsIdentity() throws GatewayTunnelException {
        if (!endpoint.secure) return;
        SSLSession session;
        try {
            session = client.getSSLSession();
        } catch (RuntimeException error) {
            throw new GatewayTunnelException(
                    GatewayTunnelException.CONNECT_FAILED, "The gateway TLS identity could not be verified.");
        }
        boolean verified = session != null
                && HttpsURLConnection.getDefaultHostnameVerifier().verify(endpoint.host, session);
        if (!verified) {
            close();
            throw new GatewayTunnelException(
                    GatewayTunnelException.CONNECT_FAILED, "The gateway TLS identity could not be verified.");
        }
    }

    /** Platform default trust (OS CA store), with explicit SNI on the wrapped
     * socket; the handshake happens on first use of the returned socket. */
    private SocketFactory gatewayTlsSocketFactory() {
        SSLSocketFactory base = (SSLSocketFactory) SSLSocketFactory.getDefault();
        return new SSLSocketFactory() {
            @Override public String[] getDefaultCipherSuites() { return base.getDefaultCipherSuites(); }
            @Override public String[] getSupportedCipherSuites() { return base.getSupportedCipherSuites(); }

            @Override
            public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                return base.createSocket(s, host, port, autoClose);
            }
            @Override public Socket createSocket(String host, int port) throws IOException { return base.createSocket(host, port); }
            @Override public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
                return base.createSocket(host, port, localHost, localPort);
            }
            @Override public Socket createSocket(InetAddress host, int port) throws IOException { return base.createSocket(host, port); }
            @Override public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
                return base.createSocket(address, port, localAddress, localPort);
            }
        };
    }

    // --- frame handling -----------------------------------------------------------

    private void onTextFrame(String message) {
        Phase current = phase.get();
        if (current == Phase.AUTHENTICATING && errorFrameAwaitingClose) {
            failIfOpen(new GatewayTunnelException(
                    GatewayTunnelException.PROTOCOL, "The gateway sent a second handshake message."));
            return;
        }
        if (current == Phase.AUTHENTICATING) {
            try {
                GatewayTargetPolicy.Handshake handshake = GatewayTargetPolicy.parseHandshakeFrame(message);
                if (!handshake.ready) {
                    // The error frame's code is machine vocabulary; its message
                    // text is never surfaced (a hostile gateway could write
                    // anything into it). A recognized code IS the verdict and
                    // maps to its documented close code at once; an
                    // unrecognized one waits for the close frame the gateway
                    // sends right after it (bounded by the handshake deadline).
                    int closeCode = GatewayTargetPolicy.closeCodeForErrorFrame(handshake.errorCode);
                    if (closeCode == GatewayTunnelException.NO_CLOSE_CODE) {
                        errorFrameAwaitingClose = true;
                        return;
                    }
                    throw verdict(closeCode);
                }
                if (!target.deviceId.equals(handshake.deviceId)) {
                    failIfOpen(new GatewayTunnelException(
                            GatewayTunnelException.PROTOCOL, "The gateway opened the wrong host route."));
                    return;
                }
                // ready.ssh_host_key is ADVISORY: read nowhere, trusted never.
                if (phase.compareAndSet(Phase.AUTHENTICATING, Phase.READY)) {
                    socket.markReady();
                }
                return;
            } catch (GatewayTargetPolicy.MalformedHandshakeException malformed) {
                failIfOpen(new GatewayTunnelException(
                        GatewayTunnelException.PROTOCOL, "The gateway sent a malformed handshake."));
                return;
            } catch (GatewayTunnelException failureToRaise) {
                failIfOpen(failureToRaise);
                return;
            }
        }
        if (current == Phase.READY) {
            // All post-handshake traffic is BINARY; a TEXT frame is a protocol
            // violation, not data.
            failIfOpen(new GatewayTunnelException(
                    GatewayTunnelException.PROTOCOL, "The gateway sent an unexpected control message."));
        }
    }

    private void onBinaryFrame(ByteBuffer bytes) {
        if (phase.get() != Phase.READY) {
            failIfOpen(new GatewayTunnelException(
                    GatewayTunnelException.PROTOCOL, "The gateway sent data before the handshake finished."));
            return;
        }
        // The draft already bounded this message at MAX_WS_MESSAGE_BYTES
        // BEFORE allocation; copy exactly its remaining bytes.
        byte[] payload = new byte[bytes.remaining()];
        bytes.get(payload);
        try {
            // Blocks when the buffer is full: read backpressure. close()
            // releases the semaphore so a shutdown cannot wedge the reader.
            inboundBytes.acquire(payload.length);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failIfOpen(new GatewayTunnelException(
                    GatewayTunnelException.CONNECT_FAILED, "The gateway connection was cancelled."));
            return;
        }
        if (phase.get() != Phase.READY) {
            inboundBytes.release(payload.length);
            return;
        }
        while (!inboundFrames.offer(payload)) {
            if (phase.get() != Phase.READY) {
                inboundBytes.release(payload.length);
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                inboundBytes.release(payload.length);
                failIfOpen(new GatewayTunnelException(
                        GatewayTunnelException.CONNECT_FAILED, "The gateway connection was cancelled."));
                return;
            }
        }
    }

    private void onClosed(int code, boolean remote) {
        Phase current = phase.get();
        if (current == Phase.CLOSED || current == Phase.FAILED) return;
        // Only a close frame FROM the gateway is its verdict; a close the
        // library initiated locally (e.g. 1009 for an oversized frame) is not.
        if (remote && GatewayTargetPolicy.isGatewayCloseCode(code)) {
            failIfOpen(verdict(code));
            return;
        }
        // No close frame (an abnormal drop is synthesized locally as 1006,
        // or -1 when the WebSocket never opened): no gateway verdict.
        GatewayTargetPolicy.CloseKind kind = GatewayTargetPolicy.classifyClose(code);
        failIfOpen(new GatewayTunnelException(toFailureCode(kind.kind), kind.userMessage));
    }

    /** The gateway's own verdict, carrying its close code. */
    private static GatewayTunnelException verdict(int closeCode) {
        GatewayTargetPolicy.CloseKind kind = GatewayTargetPolicy.classifyClose(closeCode);
        return new GatewayTunnelException(toFailureCode(kind.kind), kind.userMessage, closeCode);
    }

    private static String toFailureCode(String closeKind) {
        switch (closeKind) {
            case "protocol": return GatewayTunnelException.PROTOCOL;
            case "unauthorized": return GatewayTunnelException.UNAUTHORIZED;
            case "forbidden": return GatewayTunnelException.FORBIDDEN;
            case "not_found": return GatewayTunnelException.HOST_UNKNOWN;
            case "timeout": return GatewayTunnelException.TIMEOUT;
            case "quota": return GatewayTunnelException.QUOTA;
            case "host_offline": return GatewayTunnelException.HOST_OFFLINE;
            default: return GatewayTunnelException.UNREACHABLE;
        }
    }

    private void sendText(String text) throws GatewayTunnelException {
        try {
            client.send(text);
        } catch (WebsocketNotConnectedException | IllegalStateException error) {
            throw new GatewayTunnelException(
                    GatewayTunnelException.CONNECT_FAILED, "The gateway connection closed before authentication.");
        }
    }

    private GatewayTunnelException awaitHandshakeFailure(long remainingMs) {
        long deadline = System.nanoTime() + Math.max(0, remainingMs) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            GatewayTunnelException current = failure.get();
            if (current != null) return current;
            Phase currentPhase = phase.get();
            if (currentPhase == Phase.READY) return null;
            if (currentPhase == Phase.CLOSED || currentPhase == Phase.FAILED) {
                return failure.get() != null ? failure.get() : new GatewayTunnelException(
                        GatewayTunnelException.UNREACHABLE, "The connection to the gateway closed before it was ready.");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new GatewayTunnelException(
                        GatewayTunnelException.CONNECT_FAILED, "The gateway connection was cancelled.");
            }
        }
        return new GatewayTunnelException(
                GatewayTunnelException.TIMEOUT, "The gateway did not finish the handshake in time.");
    }

    private GatewayTunnelException failedOr(GatewayTunnelException fallback) {
        GatewayTunnelException current = failure.get();
        return current != null ? current : fallback;
    }

    private void failIfOpen(GatewayTunnelException error) {
        if (phase.get() == Phase.CLOSED || phase.get() == Phase.FAILED) return;
        if (failure.compareAndSet(null, error)) {
            phase.set(Phase.FAILED);
            inboundBytes.release(MAX_INBOUND_BUFFER_BYTES);
            WebSocketClient current = client;
            if (current != null) {
                try {
                    current.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // --- the Socket over the tunnel ------------------------------------------------

    /**
     * A {@link Socket} whose byte stream is the tunnel. sshj's
     * {@code SocketClient} is verified (0.40.0 bytecode) to touch exactly:
     * createSocket, isConnected, connect(address, timeout), bind, setSoTimeout,
     * getInputStream, getOutputStream, close, and the address getters — every
     * one is implemented here with socket semantics, and the connect path
     * never performs DNS or TCP to the display hostname: the factory hands
     * sshj an ALREADY-CONNECTED socket, so sshj skips the connect entirely and
     * the virtual peer address below exists only for its logging getters.
     */
    public final class GatewayTunnelSocket extends Socket {
        private volatile boolean readySeen;

        void markReady() {
            readySeen = true;
        }

        private void requireUsable() throws IOException {
            if (phase.get() == Phase.FAILED || phase.get() == Phase.CLOSED) {
                GatewayTunnelException error = failure.get();
                throw new IOException(error != null ? error.getMessage() : "Gateway tunnel is closed.");
            }
            if (!readySeen) throw new IOException("Gateway tunnel is not connected.");
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            // Defensive only: with the pre-connected socket sshj never calls
            // this. A virtual peer join, bounded by the given timeout.
            long deadline = monotonicMs() + Math.max(0, timeout);
            while (phase.get() != Phase.READY && monotonicMs() < deadline) {
                if (phase.get() == Phase.FAILED || phase.get() == Phase.CLOSED) {
                    throw new IOException("Gateway tunnel is closed.");
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Gateway tunnel connect was interrupted.");
                }
            }
            if (phase.get() != Phase.READY) throw new SocketTimeoutException("connect timed out");
        }

        @Override public boolean isConnected() { return readySeen; }
        @Override public boolean isBound() { return true; }
        @Override public boolean isClosed() { return phase.get() == Phase.CLOSED || phase.get() == Phase.FAILED; }
        @Override public void bind(SocketAddress localAddr) { /* sshj binds an ephemeral wildcard; nothing to do */ }

        @Override
        public void setSoTimeout(int timeout) {
            if (timeout < 0) throw new IllegalArgumentException("timeout < 0");
            socketSoTimeoutMs = timeout;
        }

        @Override public int getSoTimeout() { return socketSoTimeoutMs; }

        @Override
        public InputStream getInputStream() {
            return new TunnelInputStream();
        }

        @Override
        public OutputStream getOutputStream() {
            return new TunnelOutputStream();
        }

        @Override public InetAddress getInetAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getPort() { return 22; }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getLocalPort() { return 0; }
        @Override public SocketAddress getRemoteSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 22); }
        @Override public SocketAddress getLocalSocketAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0); }

        @Override
        public void close() {
            GatewayTunnel.this.close();
        }
    }

    /** Ordered byte stream over inbound binary frames, bounded and finite. */
    private final class TunnelInputStream extends InputStream {
        private byte[] current;
        private int offset;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read == -1 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] target, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (current == null || offset >= current.length) {
                current = takeFrame();
                if (current == null) return -1;
                offset = 0;
            }
            int count = Math.min(len, current.length - offset);
            System.arraycopy(current, offset, target, off, count);
            offset += count;
            inboundBytes.release(count);
            return count;
        }

        @Override
        public int available() {
            byte[] head = current;
            return head == null ? 0 : head.length - offset;
        }

        /** The next frame, or null once the tunnel is closed after having
         * been ready. Throws on failure or a closed-before-ready tunnel.
         * Honors the socket's read timeout in bounded slices so close() and
         * cancellation cannot wedge the sshj reader thread. */
        private byte[] takeFrame() throws IOException {
            int timeoutBudgetMs = socketSoTimeoutMs > 0 ? socketSoTimeoutMs : 0;
            long deadline = timeoutBudgetMs > 0 ? monotonicMs() + timeoutBudgetMs : 0;
            while (true) {
                if (phase.get() == Phase.FAILED) {
                    GatewayTunnelException error = failure.get();
                    throw new IOException(error != null ? error.getMessage() : "Gateway tunnel failed.");
                }
                byte[] frame = null;
                try {
                    frame = inboundFrames.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Gateway tunnel read was interrupted.");
                }
                if (frame != null) return frame;
                Phase now = phase.get();
                if (now == Phase.CLOSED) return null;
                if (now == Phase.FAILED) continue; // rethrow at loop top
                if (timeoutBudgetMs > 0 && monotonicMs() >= deadline) {
                    throw new SocketTimeoutException("Read timed out");
                }
            }
        }
    }

    /** SSH bytes out as binary frames, with a finite pending-bytes cap. */
    private final class TunnelOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buffer, int off, int len) throws IOException {
            requireUsableForWrite();
            int written = 0;
            while (written < len) {
                int chunk = Math.min(OUTBOUND_FRAME_BYTES, len - written);
                byte[] frame = new byte[chunk];
                System.arraycopy(buffer, off + written, frame, 0, chunk);
                awaitOutboundCapacity();
                requireUsableForWrite();
                try {
                    // WebSocket.send is void; a closed or closing connection
                    // surfaces as a runtime exception here.
                    client.send(frame);
                } catch (RuntimeException error) {
                    throw new IOException("Gateway tunnel is closed.");
                }
                written += chunk;
            }
        }

        /** Block while the library's outbound queue holds more than the hard
         * cap, with a finite stall deadline. This is the write backpressure:
         * nothing queues without bound, and a stopped gateway surfaces as an
         * IOException instead of silent growth. */
        private void awaitOutboundCapacity() throws IOException {
            long stallDeadline = monotonicMs() + WRITE_STALL_TIMEOUT_MS;
            while (outboundPendingBytes() > MAX_OUTBOUND_PENDING_BYTES) {
                if (phase.get() == Phase.FAILED || phase.get() == Phase.CLOSED) {
                    throw new IOException("Gateway tunnel is closed.");
                }
                if (monotonicMs() >= stallDeadline) {
                    throw new IOException("The gateway stopped accepting data.");
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Gateway tunnel write was interrupted.");
                }
            }
        }

        @Override
        public void close() {
            GatewayTunnel.this.close();
        }
    }

    private void requireUsableForWrite() throws IOException {
        if (phase.get() == Phase.FAILED || phase.get() == Phase.CLOSED) {
            GatewayTunnelException error = failure.get();
            throw new IOException(error != null ? error.getMessage() : "Gateway tunnel is closed.");
        }
        if (!socketReady()) throw new IOException("Gateway tunnel is not connected.");
    }

    private boolean socketReady() {
        return socket != null && socket.readySeen;
    }

    /** Total bytes queued for the wire in the library's outbound queue. The
     * queue is a public field on WebSocketImpl in 1.5.7; this observation is
     * the documented mechanism for bounded writes on this library. */
    long outboundPendingBytes() {
        org.java_websocket.WebSocket connection = client == null ? null : client.getConnection();
        if (!(connection instanceof org.java_websocket.WebSocketImpl)) return 0;
        long total = 0;
        for (ByteBuffer buffer : ((org.java_websocket.WebSocketImpl) connection).outQueue) {
            total += buffer.remaining();
            if (total > Integer.MAX_VALUE / 2) break;
        }
        return total;
    }

    /** The strict failure, when the tunnel failed; null while healthy. The
     * plugin reads it to report a gateway verdict that surfaced to sshj as
     * a plain stream error. */
    GatewayTunnelException failure() {
        return failure.get();
    }

    /** Test-only visibility: the current phase name. */
    String phaseForTesting() {
        return phase.get().name();
    }
}
