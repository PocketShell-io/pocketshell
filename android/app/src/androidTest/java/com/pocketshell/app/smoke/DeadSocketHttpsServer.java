package com.pocketshell.app.smoke;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Issue #3189: a sync endpoint whose pooled keep-alive socket silently dies.
 *
 * <p>Every connection answers its first request (HTTP/1.1, keep-alive), so the
 * client pools it. A later request on that same socket is read and never
 * answered — exactly what a carrier NAT that forgot the mapping, or a network
 * the phone already left, does: no reset, no reply, nothing. A new connection
 * is answered normally. TLS, so the app's real network-security policy and
 * Android's real HTTP connection pool are both in play; the certificate is a
 * test-only self-signed key that only this server and the test trust.
 */
final class DeadSocketHttpsServer implements AutoCloseable {
    /** Test-only PKCS12 (EC P-256, self-signed, CN=pocketshell-3189-dead-socket-test). Not a secret. */
    private static final String KEYSTORE_B64 = "MIIDqAIBAzCCA2EGCSqGSIb3DQEHAaCCA1IEggNOMIIDSjCB5wYJKoZIhvcNAQcBoIHZBIHWMIHTMIHQBgsqhkiG9w0BDAoBAqB3MHUwKQYKKoZIhvcNAQwBAzAbBBQYV4F2T45NJD/Q1l268KU08nDJnAIDAMNQBEiLG8qfU5kukPCNzgkcP9HErOU+QiKRMWBekNh+84nz7UhdNpWmVlyIyv6VkD0Ypct6v+zUrM9aRClWX2rOQPbcJHeh2sFkDVgxSDAjBgkqhkiG9w0BCRQxFh4UAGQAZQBhAGQAcwBvAGMAawBlAHQwIQYJKoZIhvcNAQkVMRQEElRpbWUgMTc5NTAyNDYxMDc0ODCCAlwGCSqGSIb3DQEHBqCCAk0wggJJAgEAMIICQgYJKoZIhvcNAQcBMCkGCiqGSIb3DQEMAQYwGwQUfcQ+qbM8E02kjfYAoGrQynC0fCwCAwDDUICCAghFPsfyDDMRB29O9aBS4ELM5Th305bVG+e8Yb3oCBoCVOkcs5aqBifoKGvAgWk5C3OHOswGuDA3Az82uNTHJqrL3nRv7mfyq9MQtB7GyBNLCy4ZAezKlYM0clF9cWvAE7vXh0GXgqS9aZ31L0oMIPYi9AbO3sKex7H8HHG0YfydQXhY16a84mwlO9Bx8/ArRT5OLJVW8hn6/Q2CMAAHrEmO/+uMpyK/NjjKeOrx+EgBWJ6OLdIgNV8Uzouw+O6HypEyEST7wi1T+JvTashodrsM2TG/QxQh4E2uxra4e/2LqD/VeHEfnmvKrBmk1c8AKha2iHNhw5h11QNB9Ap/pQ3AKJ6TzooN49b026tcmYqzIvF7QM+QjFMZEa7+TZbpfwZMsSlU3MV9GpEnEfp+RoOx9U2TJIw3d5q7Epf1IctvkmAUReXDtHpddA4KbpqLDIa3WxDLqaPsa5NInun/hcGezcNG2Yh1q6lGQdX4bLZDyEobhaMw0V2sAxUx33sb32m48/Gsh4FoG+1Xxgp21EGbhYCK5pYeRVWIA1lKhRT6GpV8eS8MsrpgdaVga1yK1jgfDG7JY9srFbdetXoP6yTqp0x4XS/hjpDSTE+iYmWJ96D9qZaguc5gyP/mKGWT4/AXevmc9OW42axQTIwsQEWK4qL0XIGDm27lilqK/P8J0SogCBZ0kpXlMD4wITAJBgUrDgMCGgUABBSgzZwa3mtxCPNv2CoisamIxihZGgQUtjnxgErjUdBMLmlqqJMbvpOt7MoCAwGGoA==";
    private static final char[] PASSWORD = "deadsocket3189".toCharArray();

    final SSLContext context;
    private final SSLServerSocket server;
    final AtomicInteger connections = new AtomicInteger();
    final AtomicInteger answered = new AtomicInteger();
    final AtomicInteger swallowed = new AtomicInteger();
    private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();

    DeadSocketHttpsServer() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(new ByteArrayInputStream(Base64.getDecoder().decode(KEYSTORE_B64)), PASSWORD);
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("dead-socket", keys.getCertificate("deadsocket"));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trust.getTrustManagers(), null);
        server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0, 50, InetAddress.getByName("0.0.0.0"));
        Thread acceptor = new Thread(this::accept, "dead-socket-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                sockets.add(socket);
                connections.incrementAndGet();
                Thread worker = new Thread(() -> serve(socket), "dead-socket-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket held = socket) {
            InputStream in = new BufferedInputStream(held.getInputStream());
            OutputStream out = held.getOutputStream();
            int requests = 0;
            while (readRequest(in)) {
                requests += 1;
                if (requests > 1) {
                    // The pooled socket is dead: swallow this request and everything after it.
                    swallowed.incrementAndGet();
                    while (in.read() != -1) { /* nothing ever comes back */ }
                    return;
                }
                byte[] body = "{\"sub\":\"dead-socket\"}".getBytes(StandardCharsets.UTF_8);
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                        + "\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                answered.incrementAndGet();
            }
        } catch (IOException ignored) {
            // The client gave up on this socket.
        }
    }

    /** Reads one request head (a GET has no body); false at end of stream. */
    private static boolean readRequest(InputStream in) throws IOException {
        int matched = 0;
        int read;
        while ((read = in.read()) != -1) {
            matched = (read == '\r' || read == '\n') ? matched + 1 : 0;
            if (matched == 4) return true;
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { /* already gone */ }
        }
    }
}
