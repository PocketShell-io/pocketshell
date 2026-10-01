package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import net.schmizz.sshj.transport.TransportException;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyFormat;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.userauth.password.PasswordUtils;

import org.junit.Test;

/**
 * The stored-key connect classification is what keeps pocketshell-core's
 * reconnect loop from redialling a connection that cannot succeed: only
 * AUTH_FAILED, HOST_KEY_REJECTED and INVALID_ARGUMENT stop it.
 */
public final class KeyHandleConnectFailuresTest {
    private static final String PASSPHRASE = "pocketshell-vault-test-passphrase";
    /** Codes pocketshell-core's isRetryableDialError treats as terminal. */
    private static final List<String> TERMINAL = Arrays.asList("AUTH_FAILED", "HOST_KEY_REJECTED", "INVALID_ARGUMENT");

    @Test public void wrongPassphraseOnStoredEncryptedKeyIsTerminalAuthFailure() throws Exception {
        IOException keyFailure = realPublicKeyFailure("wrong-passphrase");
        KeyHandleConnectFailures.Classified classified = KeyHandleConnectFailures.classify(asSshjAuthFailure(keyFailure));
        assertEquals("AUTH_FAILED", classified.code);
        assertEquals(KeyHandleConnectFailures.KEY_LOCKED_MESSAGE, classified.message);
        assertTerminalAndSecretFree(classified);
    }

    @Test public void reconnectWithoutTheTransientPassphraseIsTerminalAuthFailure() throws Exception {
        // Core drops the passphrase from the retained host after connecting, so
        // a reconnect of an encrypted key dials with an empty passphrase.
        IOException keyFailure = realPublicKeyFailure("");
        KeyHandleConnectFailures.Classified classified = KeyHandleConnectFailures.classify(asSshjAuthFailure(keyFailure));
        assertEquals("AUTH_FAILED", classified.code);
        assertEquals(KeyHandleConnectFailures.KEY_LOCKED_MESSAGE, classified.message);
        assertTerminalAndSecretFree(classified);
    }

    @Test public void serverRejectedKeyIsTerminalAuthFailure() {
        UserAuthException exhausted = new UserAuthException("Exhausted available authentication methods",
            new UserAuthException("publickey auth failed"));
        KeyHandleConnectFailures.Classified classified = KeyHandleConnectFailures.classify(exhausted);
        assertEquals("AUTH_FAILED", classified.code);
        assertEquals(KeyHandleConnectFailures.KEY_REJECTED_MESSAGE, classified.message);
        assertTerminalAndSecretFree(classified);
    }

    @Test public void unreadableStoredKeyIsTerminalInvalidArgument() {
        IOException parse = new IOException("-----BEGIN OPENSSH PRIVATE KEY----- AAAA invalid structure");
        KeyHandleConnectFailures.Classified classified = KeyHandleConnectFailures.classify(
            asSshjAuthFailure(parse));
        assertEquals("INVALID_ARGUMENT", classified.code);
        assertEquals(KeyHandleConnectFailures.KEY_UNREADABLE_MESSAGE, classified.message);
        assertTerminalAndSecretFree(classified);
        assertEquals("INVALID_ARGUMENT",
            KeyHandleConnectFailures.classify(new IllegalArgumentException("bad key /data/user/0/x")).code);
        assertEquals("INVALID_ARGUMENT", KeyHandleConnectFailures.vaultUnavailable().code);
    }

    @Test public void transportFailuresStayRetryable() {
        KeyHandleConnectFailures.Classified refused = KeyHandleConnectFailures.classify(
            new ConnectException("Connection refused 10.0.2.2:2244"));
        assertEquals("CONNECTION_LOST", refused.code);
        assertFalse(TERMINAL.contains(refused.code));
        assertFalse(refused.message.contains("10.0.2.2"));
        KeyHandleConnectFailures.Classified reset = KeyHandleConnectFailures.classify(
            new TransportException("Broken transport; encountered EOF"));
        assertEquals("CONNECTION_LOST", reset.code);
        assertEquals("SSH_IO", KeyHandleConnectFailures.classify(new IllegalStateException("x")).code);
    }

    @Test public void selfReferentialCauseDoesNotLoop() {
        IOException looping = new IOException("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertEquals("CONNECTION_LOST", KeyHandleConnectFailures.classify(looping).code);
    }

    private static void assertTerminalAndSecretFree(KeyHandleConnectFailures.Classified classified) {
        assertEquals(classified.code + " must stop the core reconnect loop", true, TERMINAL.contains(classified.code));
        assertFalse(classified.message.contains("PRIVATE KEY"));
        assertFalse(classified.message.contains(PASSPHRASE));
        assertFalse(classified.message.contains("wrong-passphrase"));
    }

    /** Mirrors SSHJ 0.40: KeyedAuthMethod wraps the provider failure, SSHClient.auth wraps the last method failure. */
    private static UserAuthException asSshjAuthFailure(IOException providerFailure) {
        UserAuthException method = new UserAuthException("Problem getting public key from key provider", providerFailure);
        return new UserAuthException("Exhausted available authentication methods", method);
    }

    /** Drives the same SSHJ key-provider path the plugin uses and returns its real failure. */
    private static IOException realPublicKeyFailure(String passphrase) throws Exception {
        String pem;
        try (InputStream input = KeyHandleConnectFailuresTest.class.getResourceAsStream("/encrypted_ed25519")) {
            assertNotNull("encrypted fixture resource", input);
            pem = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        char[] secret = passphrase.toCharArray();
        KeyFormat format = KeyProviderUtil.detectKeyFileFormat(pem, secret.length > 0);
        FileKeyProvider provider = Factory.Named.Util.create(new DefaultConfig().getFileKeyProviderFactories(), format.toString());
        assertNotNull("SSHJ must support the fixture format " + format, provider);
        provider.init(pem, null, PasswordUtils.createOneOff(secret));
        try {
            provider.getPublic();
            provider.getPrivate();
            fail("the encrypted fixture must not unlock with passphrase '" + passphrase + "'");
            return null;
        } catch (IOException expected) {
            return expected;
        }
    }
}
