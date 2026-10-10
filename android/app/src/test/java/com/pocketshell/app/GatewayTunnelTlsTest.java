package com.pocketshell.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.junit.After;
import org.junit.Test;

/**
 * #3086 slice 3, found by the emulator gateway lane: the production wss://
 * path. Every slice-2 tunnel test dialled plaintext ws:// (no socket
 * factory), so the TLS socket factory was never exercised — and
 * Java-WebSocket asks a configured factory for an UNCONNECTED socket
 * ({@code createSocket()}), which the wrapper did not implement, so every
 * real gateway dial failed before TLS started.
 *
 * <p>Here the fixture gateway serves real TLS with a certificate for
 * {@code localhost}; the client trusts only that certificate (the JVM has
 * neither the app's trust store nor Android's hostname verifier, so both are
 * injected through the test constructor — production uses the platform's).
 * The routing token must never be sent unless the TLS identity checks out.
 */
public final class GatewayTunnelTlsTest {
    private static final String ROUTING_TOKEN = "routing-token-secret-tls";
    private static final String DEVICE = "host-1";
    private static final char[] PASSWORD = "fixture".toCharArray();

    private GatewayTunnelTest.FakeGateway gateway;

    @After public void tearDown() throws InterruptedException {
        if (gateway != null) gateway.stop(0);
    }

    @Test public void aWssTunnelCompletesTlsAndStreamsBothWays() throws Exception {
        Identity server = identity("localhost");
        startTlsGateway(server, GatewayTunnelTest.FakeGateway.Script.READY_ECHO);
        try (GatewayTunnel tunnel = tunnel(trusting(server.certificate))) {
            tunnel.open(15_000);
            assertTrue(gateway.authReceived.await(10, TimeUnit.SECONDS));
            assertEquals(ROUTING_TOKEN, new org.json.JSONObject(gateway.receivedAuthFrame).getString("token"));
            byte[] payload = "ssh bytes over wss".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            OutputStream out = tunnel.socket().getOutputStream();
            out.write(payload);
            out.flush();
            byte[] echoed = new byte[payload.length];
            InputStream in = tunnel.socket().getInputStream();
            int read = 0;
            while (read < payload.length) {
                int n = in.read(echoed, read, payload.length - read);
                if (n < 0) break;
                read += n;
            }
            assertArrayEquals(payload, echoed);
        }
    }

    @Test public void aGatewayWhoseCertificateIsNotTrustedIsRefusedBeforeTheTokenIsSent() throws Exception {
        Identity server = identity("localhost");
        Identity stranger = identity("localhost");
        startTlsGateway(server, GatewayTunnelTest.FakeGateway.Script.READY_ECHO);
        assertRefusedWithoutToken(tunnel(trusting(stranger.certificate)));
    }

    @Test public void aTrustedCertificateForAnotherHostIsRefusedBeforeTheTokenIsSent() throws Exception {
        Identity other = identity("gateway.example");
        startTlsGateway(other, GatewayTunnelTest.FakeGateway.Script.READY_ECHO);
        assertRefusedWithoutToken(tunnel(trusting(other.certificate)));
    }

    private void assertRefusedWithoutToken(GatewayTunnel tunnel) throws Exception {
        try {
            tunnel.open(10_000);
            fail("a TLS identity that does not check out must refuse the dial");
        } catch (GatewayTunnel.GatewayTunnelException refused) {
            // A TLS refusal is never a gateway verdict: core keeps its default.
            assertTrue("refused as a failed dial, not a verdict: " + refused.code,
                    refused.code.equals(GatewayTunnel.GatewayTunnelException.CONNECT_FAILED)
                            || refused.code.equals(GatewayTunnel.GatewayTunnelException.UNREACHABLE));
            assertTrue("no gateway close code", !refused.hasGatewayCloseCode());
        } finally {
            tunnel.close();
        }
        assertNull("the routing token never left the phone", gateway.receivedAuthFrame);
    }

    // --- fixture -------------------------------------------------------------------

    private static final class Identity {
        final KeyPair keys;
        final X509Certificate certificate;

        Identity(KeyPair keys, X509Certificate certificate) {
            this.keys = keys;
            this.certificate = certificate;
        }
    }

    /** A self-signed P-256 certificate whose only name is {@code host}. */
    private static Identity identity(String host) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair keys = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=" + host);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name,
                BigInteger.valueOf(now), new Date(now - 3_600_000L), new Date(now + 86_400_000L), name, keys.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, host)));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
        return new Identity(keys, certificate);
    }

    private void startTlsGateway(Identity identity, GatewayTunnelTest.FakeGateway.Script script) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("gateway", identity.keys.getPrivate(), PASSWORD, new X509Certificate[] {identity.certificate});
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        gateway = new GatewayTunnelTest.FakeGateway(script);
        gateway.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(context));
        gateway.start();
        assertTrue("the TLS fixture gateway must bind", gateway.started.await(10, TimeUnit.SECONDS));
    }

    private static SSLContext trusting(X509Certificate certificate) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("anchor", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }

    /** What Android's default verifier checks for these certificates: a DNS SAN equal to the host. */
    private static final HostnameVerifier SAN_VERIFIER = (host, session) -> {
        try {
            X509Certificate peer = (X509Certificate) session.getPeerCertificates()[0];
            for (List<?> name : peer.getSubjectAlternativeNames()) {
                if (((Integer) name.get(0)) == 2 && host.equalsIgnoreCase((String) name.get(1))) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    };

    private GatewayTunnel tunnel(SSLContext client) {
        GatewayTargetPolicy.Target target = GatewayTargetPolicy.normalizeTarget(
                "wss://localhost:" + gateway.getPort(), DEVICE);
        return new GatewayTunnel(target, ROUTING_TOKEN, false, client.getSocketFactory(), SAN_VERIFIER);
    }
}
