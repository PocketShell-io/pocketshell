package com.pocketshell.app;

import com.getcapacitor.JSObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.schmizz.sshj.common.Buffer;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * The JVM end-to-end fixture for the Android gateway dial (#3086 slice 2):
 * the REAL {@link SshCapabilityPlugin#connectNow} path — broker exchange,
 * {@link GatewayTunnel}, sshj, the pairing pin verifier and key userauth —
 * against a loopback gateway that speaks the v1 client handshake and then
 * relays opaque bytes to a REAL SSH server (Apache MINA sshd). Nothing here
 * routes the SSH client to the server directly: the only path is the tunnel.
 */
final class GatewayDialFixture implements AutoCloseable {
    static final String HANDLE = "01234567-89ab-cdef-0123-456789abcdef";
    static final String SUBJECT = "sub-1";
    static final String DEVICE = "host-1";

    final SshHost host;
    final FakeGateway gateway;
    final ScriptedBroker broker;
    final KeyPair clientKey;
    final GatewayPairingStore pairings;
    final SshCapabilityPlugin plugin;
    private final List<SshCapabilityPlugin.PluginFailure> failures = new ArrayList<>();
    /** Every stored-key failure code the plugin logged (codes only, by design). */
    final List<String> loggedFailureCodes = new CopyOnWriteArrayList<>();

    GatewayDialFixture() throws Exception {
        this(System::currentTimeMillis);
    }

    GatewayDialFixture(GatewayTokenBroker.LongSupplierNow clock) throws Exception {
        // RSA: sshj derives the public half from a PKCS#8 RSA key; a
        // JCE-encoded EC PKCS#8 key omits it.
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        clientKey = rsa.generateKeyPair();
        host = new SshHost(clientKey.getPublic());
        gateway = new FakeGateway(host);
        gateway.start();
        if (!gateway.started.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("fixture gateway did not bind");
        broker = new ScriptedBroker(clock);
        pairings = new GatewayPairingStore(new GatewayPairingStoreTest.MemoryRepository(), () -> 1_000L);
        String pem = pkcs8Pem(clientKey);
        plugin = new SshCapabilityPlugin(null, () -> 123L, broker, pairings,
                keyHandleId -> {
                    if (!HANDLE.equals(keyHandleId)) throw new IOException("unknown key handle");
                    return pem.getBytes(StandardCharsets.UTF_8);
                },
                (target, routingToken) -> new GatewayTunnel(target, routingToken, true),
                loggedFailureCodes::add);
    }

    String serverUrl() {
        return "ws://127.0.0.1:" + gateway.getPort();
    }

    /** The SHA-256 fingerprint of the key the SSH host actually presents. */
    String hostFingerprint() throws Exception {
        return fingerprint(host.hostKey.getPublic());
    }

    /** The {@code gateway show --host-key} line for the host's key. */
    String hostKeyLine() throws Exception {
        return keyLine(host.hostKey.getPublic());
    }

    static String keyLine(PublicKey key) throws Exception {
        byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
        String type = new Buffer.PlainBuffer(blob).readString();
        return type + " " + Base64.getEncoder().encodeToString(blob);
    }

    static String fingerprint(PublicKey key) throws Exception {
        byte[] blob = new Buffer.PlainBuffer().putPublicKey(key).getCompactData();
        return "SHA256:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
                .replaceAll("=+$", "");
    }

    JSObject connectOptions(String requestId) {
        return new JSObject()
                .put("requestId", requestId)
                .put("generationId", "generation-" + requestId)
                .put("hostId", "host-id-1")
                // Display labels only: nothing may dial them.
                .put("hostname", "display-label.invalid")
                .put("port", 22)
                .put("username", "alexey")
                .put("connectTimeoutMs", 15_000)
                .put("credential", new JSObject().put("kind", "key-handle").put("handleId", HANDLE))
                .put("gateway", new JSObject().put("serverUrl", serverUrl()).put("deviceId", DEVICE));
    }

    /** Run the real connect path; returns the result or records the typed failure. */
    JSObject connect(String requestId) throws Exception {
        return plugin.connectNow(connectOptions(requestId));
    }

    SshCapabilityPlugin.PluginFailure connectExpectingFailure(String requestId) throws Exception {
        try {
            JSObject result = connect(requestId);
            throw new AssertionError("expected the gateway dial to fail, got " + result);
        } catch (SshCapabilityPlugin.PluginFailure failure) {
            failures.add(failure);
            return failure;
        }
    }

    @Override
    public void close() throws Exception {
        try {
            gateway.stop(0);
        } finally {
            host.close();
        }
    }

    static KeyPair ecKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    static String pkcs8Pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    // --- the SSH host --------------------------------------------------------------

    /** A real sshd on loopback, reachable ONLY through the fake gateway's relay. */
    static final class SshHost implements AutoCloseable {
        final KeyPair hostKey;
        final SshServer server;
        /** Every userauth attempt the server saw, of any method. */
        final AtomicInteger userauthAttempts = new AtomicInteger();
        final AtomicInteger acceptedLogins = new AtomicInteger();

        SshHost(PublicKey authorizedKey) throws Exception {
            hostKey = ecKeyPair();
            server = SshServer.setUpDefaultServer();
            server.setHost("127.0.0.1");
            server.setPort(0);
            server.setKeyPairProvider(KeyPairProvider.wrap(hostKey));
            server.setPublickeyAuthenticator((username, key, session) -> {
                userauthAttempts.incrementAndGet();
                boolean ok = "alexey".equals(username) && key.equals(authorizedKey);
                if (ok) acceptedLogins.incrementAndGet();
                return ok;
            });
            server.setPasswordAuthenticator((username, password, session) -> {
                userauthAttempts.incrementAndGet();
                return false;
            });
            server.setKeyboardInteractiveAuthenticator(null);
            server.start();
        }

        int port() {
            return server.getPort();
        }

        @Override
        public void close() throws IOException {
            server.stop(true);
        }
    }

    // --- the gateway ------------------------------------------------------------------

    /** What the fake gateway does once a client sends its auth frame. */
    enum Mode { READY_RELAY, CLOSE, ERROR_FRAME_HOLD, ERROR_FRAME_THEN_CLOSE, READY_THEN_CLOSE }

    /**
     * A scripted v1 client-route gateway on loopback. In READY_RELAY it
     * answers {@code ready} (with a deliberately WRONG advisory host key) and
     * then relays binary frames to the SSH host's TCP port, both ways.
     */
    static final class FakeGateway extends WebSocketServer {
        final SshHost host;
        final CountDownLatch started = new CountDownLatch(1);
        final List<String> authFrames = new CopyOnWriteArrayList<>();
        final AtomicInteger clientConnections = new AtomicInteger();
        private final Map<WebSocket, Socket> relays = new ConcurrentHashMap<>();
        volatile Mode mode = Mode.READY_RELAY;
        /** Routing tokens this gateway now refuses with 4401 (rotated keys,
         * early revocation), whatever the mode. */
        final java.util.Set<String> rejectedTokens = ConcurrentHashMap.newKeySet();
        volatile int closeCode;
        volatile String errorCode;

        FakeGateway(SshHost host) {
            super(new InetSocketAddress("127.0.0.1", 0));
            this.host = host;
            setConnectionLostTimeout(0);
            setReuseAddr(true);
        }

        void script(Mode next, int code, String frameCode) {
            mode = next;
            closeCode = code;
            errorCode = frameCode;
        }

        @Override public void onStart() {
            started.countDown();
        }

        /** Everything the client put on the upgrade request: the request
         * URI (path + query) and every header name and value. */
        final List<String> upgradeRequests = new CopyOnWriteArrayList<>();

        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
            clientConnections.incrementAndGet();
            StringBuilder seen = new StringBuilder(handshake.getResourceDescriptor());
            java.util.Iterator<String> names = handshake.iterateHttpFields();
            while (names.hasNext()) {
                String name = names.next();
                seen.append('\n').append(name).append(": ").append(handshake.getFieldValue(name));
            }
            upgradeRequests.add(seen.toString());
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            if (relays.containsKey(conn)) return;
            authFrames.add(message);
            if (rejectedTokens.contains(tokenOf(message))) {
                conn.send(errorFrame("unauthorized"));
                conn.close(4401, "token verification failed");
                return;
            }
            switch (mode) {
                case CLOSE -> conn.close(closeCode, "fixture close");
                case ERROR_FRAME_HOLD -> conn.send(errorFrame(errorCode));
                case ERROR_FRAME_THEN_CLOSE -> {
                    conn.send(errorFrame(errorCode));
                    conn.close(closeCode, "fixture close");
                }
                case READY_THEN_CLOSE -> {
                    conn.send(readyFrame());
                    conn.close(closeCode, "fixture close");
                }
                case READY_RELAY -> startRelay(conn);
            }
        }

        /** The routing token a client auth frame carried. */
        static String tokenOf(String authFrame) {
            try {
                return new org.json.JSONObject(authFrame).getString("token");
            } catch (org.json.JSONException error) {
                return "";
            }
        }

        private static String errorFrame(String code) {
            return "{\"type\":\"error\",\"v\":1,\"code\":\"" + code + "\",\"message\":\"withheld\"}";
        }

        private static String readyFrame() {
            // An advisory key that is NOT the host's: it must never matter.
            return "{\"type\":\"ready\",\"v\":1,\"device_id\":\"" + DEVICE
                    + "\",\"ssh_host_key\":\"ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAdvisoryAdvisoryAdvisoryAdvisoryAdvisory\"}";
        }

        private void startRelay(WebSocket conn) {
            Socket upstream;
            try {
                upstream = new Socket("127.0.0.1", host.port());
            } catch (IOException error) {
                conn.close(4503, "host offline");
                return;
            }
            relays.put(conn, upstream);
            conn.send(readyFrame());
            Thread pump = new Thread(() -> {
                byte[] buffer = new byte[16 * 1024];
                try (InputStream in = upstream.getInputStream()) {
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        if (read > 0) conn.send(java.util.Arrays.copyOf(buffer, read));
                    }
                } catch (Exception ignored) {
                    // the relay ends with either side
                } finally {
                    try {
                        conn.close(1000, "relay done");
                    } catch (RuntimeException ignored) {
                    }
                }
            }, "fixture-gateway-relay");
            pump.setDaemon(true);
            pump.start();
        }

        @Override
        public void onMessage(WebSocket conn, ByteBuffer message) {
            Socket upstream = relays.get(conn);
            if (upstream == null) return;
            try {
                OutputStream out = upstream.getOutputStream();
                byte[] bytes = new byte[message.remaining()];
                message.get(bytes);
                out.write(bytes);
                out.flush();
            } catch (IOException error) {
                conn.close(1011, "relay write failed");
            }
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            Socket upstream = relays.remove(conn);
            if (upstream != null) {
                try {
                    upstream.close();
                } catch (IOException ignored) {
                }
            }
        }

        @Override public void onError(WebSocket conn, Exception error) { }
    }

    // --- the broker -----------------------------------------------------------------

    /**
     * The native sign-in seam with a scripted {@code POST /gateway/token}
     * answer. Each mint returns a distinct JWT-shaped token whose lifetime is
     * scripted, so tests can tell reuse from re-mint.
     */
    static final class ScriptedBroker extends GatewaySyncSession {
        static final String GOOGLE_BEARER = "google.ID-TOKEN-NEVER-FORWARDED.sig";
        final AtomicInteger mints = new AtomicInteger();
        final List<String> minted = Collections.synchronizedList(new ArrayList<>());
        private final GatewayTokenBroker.LongSupplierNow clock;
        volatile long lifetimeSeconds = 300;
        volatile String subject = SUBJECT;
        volatile boolean signedOut;
        /** Subject reads so far; {@link #onSubjectRead} sees the 1-based count. */
        final AtomicInteger subjectReads = new AtomicInteger();
        /** Runs AFTER the n-th subject read has answered (to switch the
         * account between the plan and anything later). */
        volatile java.util.function.IntConsumer onSubjectRead;
        /** Runs inside the exchange, after the broker answered (a switch
         * landing while the mint is in flight). */
        volatile Runnable duringExchange;

        void switchTo(String next) {
            subject = next;
            signedOut = false;
        }

        void signOut() {
            signedOut = true;
        }

        ScriptedBroker(GatewayTokenBroker.LongSupplierNow clock) {
            super((GoogleSyncSession) null);
            this.clock = clock;
        }

        @Override
        String subject() throws SyncAuthException {
            int read = subjectReads.incrementAndGet();
            boolean out = signedOut;
            String answer = subject;
            java.util.function.IntConsumer hook = onSubjectRead;
            if (hook != null) hook.accept(read);
            if (out) throw new SyncAuthException(SyncAuthException.NOT_SIGNED_IN, "Sign in with Google first.");
            return answer;
        }

        @Override
        GoogleSyncSession.GatewayBrokerExchange exchange() {
            int n = mints.incrementAndGet();
            String token = "eyJhbGciOiJSUzI1NiJ9.ROUTING-TOKEN-" + n + ".c2lnbmF0dXJl";
            minted.add(token);
            long nowSeconds = clock.getAsLong() / 1000L;
            String body = "{\"token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":" + lifetimeSeconds
                    + ",\"expires_at\":" + (nowSeconds + lifetimeSeconds) + "}";
            Runnable hook = duringExchange;
            if (hook != null) hook.run();
            return new GoogleSyncSession.GatewayBrokerExchange(new GoogleSyncSession.Response(200, body), GOOGLE_BEARER);
        }
    }
}
