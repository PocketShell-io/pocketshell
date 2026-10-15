package com.pocketshell.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Test;

/**
 * Key-vault W4: vault keys are held in process memory only, dialled by an
 * opaque {@code ephemeral-} handle, and zeroed when the vault locks.
 */
public final class EphemeralCredentialsTest {
    /** A freshly generated throwaway Ed25519 key in OpenSSH PEM form. */
    static byte[] throwawayKey() throws Exception {
        CredentialHandleVault.ensureBouncyCastle();
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME).generateKeyPair();
        return CredentialHandleVault.toPrivatePem(pair);
    }

    @Test public void holdsAValidKeyUnderAnEphemeralHandleAndResolvesACopy() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        byte[] key = throwawayKey();
        EphemeralCredentials.Held held = store.hold(key);
        assertTrue(EphemeralCredentials.isEphemeral(held.handleId));
        assertEquals("ssh-ed25519", held.algorithm);
        assertTrue(held.fingerprintSha256.startsWith("SHA256:"));
        byte[] resolved = store.resolve(held.handleId);
        assertArrayEquals(key, resolved);
        // A copy: the caller zeroing its array leaves the held key intact.
        java.util.Arrays.fill(resolved, (byte) 0);
        assertArrayEquals(key, store.resolve(held.handleId));
        java.util.Arrays.fill(key, (byte) 0);
        assertFalse("the store kept its own copy, not the caller's array", isZero(store.resolve(held.handleId)));
    }

    @Test public void wipeAllForgetsEveryKeyAndALockedHandleNoLongerResolves() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        String first = store.hold(throwawayKey()).handleId;
        String second = store.hold(throwawayKey()).handleId;
        assertEquals(2, store.wipeAll());
        assertEquals(0, store.size());
        for (String handle : new String[] {first, second}) {
            assertFalse(store.contains(handle));
            try {
                store.resolve(handle);
                fail("a wiped handle must not resolve");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("locked"));
            }
        }
    }

    @Test public void releaseZeroesOneKey() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        String kept = store.hold(throwawayKey()).handleId;
        String released = store.hold(throwawayKey()).handleId;
        assertTrue(store.release(released));
        assertFalse(store.release(released));
        assertTrue(store.contains(kept));
        assertFalse(store.contains(released));
    }

    @Test public void refusesAnythingButAnUnencryptedSupportedPrivateKey() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        byte[] encrypted = readResource("/encrypted_ed25519");
        for (byte[] bad : new byte[][] {
            new byte[0],
            "not a key".getBytes(StandardCharsets.UTF_8),
            readResource("/encrypted_ed25519.pub"),
            encrypted,
        }) {
            try {
                store.hold(bad);
                fail("must refuse");
            } catch (IOException expected) {
                assertFalse("never echoes key text", expected.getMessage().contains("BEGIN"));
            }
        }
        assertEquals(0, store.size());
    }

    @Test public void anEcdsaVaultKeySaysWhichKeyTypesWork() throws Exception {
        // An openssh-key-v1 container naming an ECDSA key: the web vault accepts
        // those, Android's dial path does not (yet), and says so plainly.
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream out = new java.io.DataOutputStream(raw);
        out.write("openssh-key-v1\u0000".getBytes(StandardCharsets.US_ASCII));
        for (String field : new String[] {"none", "none", ""}) writeString(out, field.getBytes(StandardCharsets.US_ASCII));
        out.writeInt(1);
        java.io.ByteArrayOutputStream publicBlob = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream blob = new java.io.DataOutputStream(publicBlob);
        writeString(blob, "ecdsa-sha2-nistp256".getBytes(StandardCharsets.US_ASCII));
        writeString(blob, "nistp256".getBytes(StandardCharsets.US_ASCII));
        writeString(blob, new byte[65]);
        writeString(out, publicBlob.toByteArray());
        writeString(out, new byte[64]);
        byte[] ecdsa = ("-----BEGIN OPENSSH PRIVATE KEY-----\n"
            + java.util.Base64.getMimeEncoder(70, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(raw.toByteArray())
            + "\n-----END OPENSSH PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
        assertEquals("ecdsa-sha2-nistp256", EphemeralCredentials.openSshKeyType(ecdsa));
        try {
            new EphemeralCredentials().hold(ecdsa);
            fail("an ECDSA key is refused");
        } catch (IOException expected) {
            assertEquals(EphemeralCredentials.UNSUPPORTED_TYPE, expected.getMessage());
        }
        assertEquals("ssh-ed25519", EphemeralCredentials.openSshKeyType(throwawayKey()));
    }

    private static void writeString(java.io.DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    @Test public void capsHowManyKeysAreHeld() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        byte[] key = throwawayKey();
        for (int index = 0; index < EphemeralCredentials.MAX_HELD; index++) store.hold(key);
        try {
            store.hold(key);
            fail("must cap");
        } catch (IOException expected) {
            assertEquals(EphemeralCredentials.MAX_HELD, store.size());
        }
    }

    @Test public void onlyPrefixedHandlesAreEphemeral() {
        assertTrue(EphemeralCredentials.isEphemeral("ephemeral-1"));
        assertFalse(EphemeralCredentials.isEphemeral("01234567-89ab-cdef-0123-456789abcdef"));
        assertFalse(EphemeralCredentials.isEphemeral(null));
    }

    private static boolean isZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static byte[] readResource(String name) throws IOException {
        try (java.io.InputStream input = EphemeralCredentialsTest.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing test resource " + name);
            return input.readAllBytes();
        }
    }
}
