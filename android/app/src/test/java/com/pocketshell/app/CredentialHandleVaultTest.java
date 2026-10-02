package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class CredentialHandleVaultTest {
    private File directory;
    private MemoryMetadata metadata;
    private TestProtector protector;
    private CredentialHandleVault vault;

    @Before public void setUp() throws Exception {
        directory = Files.createTempDirectory("pocketshell-key-vault-test").toFile();
        metadata = new MemoryMetadata();
        protector = new TestProtector();
        vault = new CredentialHandleVault(directory, metadata, protector);
    }

    @After public void tearDown() {
        erase(directory);
    }

    @Test public void rejectsMalformedKeyWithoutWritingMetadataOrFiles() throws Exception {
        try {
            vault.importBytes("not a private key".getBytes(StandardCharsets.UTF_8), "bad", new char[0], null, false);
            fail("Malformed private key must not be accepted.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("private key"));
        }
        assertTrue(metadata.records.isEmpty());
        assertEquals(0, visibleVaultFiles().length);
    }

    @Test public void rejectsWrongPassphraseAndImportsEncryptedKeyOnlyWithCorrectPassphrase() throws Exception {
        byte[] encrypted = resource("/encrypted_ed25519");
        try {
            try {
                vault.importBytes(encrypted, "encrypted fixture", "wrong-passphrase".toCharArray(), null, false);
                fail("Wrong passphrase must be rejected.");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().toLowerCase().contains("passphrase"));
            }
            assertTrue(metadata.records.isEmpty());
            CredentialHandleVault.KeyMetadata imported = vault.importBytes(
                encrypted, "encrypted fixture", "pocketshell-vault-test-passphrase".toCharArray(), null, false
            );
            assertTrue(imported.passphraseRequired);
            assertEquals("ssh-ed25519", imported.algorithm);
            assertEquals(1, metadata.records.size());
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    @Test public void rejectsDuplicatePublicKeyInsteadOfCreatingSecondHandle() throws Exception {
        byte[] key = resource("/docker_test_key");
        try {
            CredentialHandleVault.KeyMetadata first = vault.importBytes(key, "first", new char[0], null, false);
            try {
                vault.importBytes(key, "duplicate", new char[0], null, false);
                fail("Duplicate key must be rejected.");
            } catch (CredentialHandleVault.DuplicateKeyException expected) {
                assertTrue(expected.getMessage().contains("already stored"));
            }
            assertEquals(1, metadata.records.size());
            assertEquals(first.handleId, vault.list().get(0).handleId);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    @Test public void legacyEncryptedAliasesReuseOneOpaqueHandleByPublicFingerprint() throws Exception {
        byte[] encrypted = resource("/encrypted_ed25519");
        try {
            CredentialHandleVault.ParsedKey parsed = CredentialHandleVault.parseEncryptedLegacyMetadata(encrypted);
            CredentialHandleVault.KeyMetadata first = vault.storeOpaqueEncryptedLegacy(
                encrypted, "legacy one", "17:first", parsed
            );
            CredentialHandleVault.KeyMetadata second = vault.storeOpaqueEncryptedLegacy(
                encrypted, "legacy two", "18:second", parsed
            );

            assertEquals(first.handleId, second.handleId);
            assertEquals(1, metadata.records.size());
            assertEquals(2, metadata.records.get(0).legacySourceIdentities.size());
            assertEquals(1, vault.list().size());
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    @Test public void interruptedSealOrMetadataCommitLeavesNoSelectableKeyOrOrphanFile() throws Exception {
        byte[] key = resource("/docker_test_key");
        try {
            protector.failSeal = true;
            try {
                vault.importBytes(key, "interrupted", new char[0], null, false);
                fail("Injected protector failure must abort import.");
            } catch (IOException expected) {
                assertTrue(metadata.records.isEmpty());
                assertEquals(0, visibleVaultFiles().length);
            }

            protector.failSeal = false;
            metadata.failWrites = true;
            try {
                vault.importBytes(key, "interrupted", new char[0], null, false);
                fail("Injected metadata commit failure must abort import.");
            } catch (IOException expected) {
                assertTrue(metadata.records.isEmpty());
                assertEquals(0, visibleVaultFiles().length);
            }
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    @Test public void reopeningAfterInterruptedFileWritesRemovesPendingAndUnindexedCiphertext() throws Exception {
        File pending = new File(directory, ".00000000-0000-4000-8000-000000000001.pending");
        File orphan = new File(directory, "00000000-0000-4000-8000-000000000002.vault");
        Files.write(pending.toPath(), new byte[] {1, 2, 3});
        Files.write(orphan.toPath(), new byte[] {4, 5, 6});

        vault = new CredentialHandleVault(directory, metadata, protector);

        assertTrue(vault.list().isEmpty());
        assertEquals(0, visibleVaultFiles().length);
    }

    @Test public void interruptedGenerationDoesNotCreateAHandleAndSuccessfulGenerationStoresOnlyCiphertext() throws Exception {
        protector.failSeal = true;
        try {
            vault.generate("generated", "Ed25519");
            fail("Injected protector failure must abort generation.");
        } catch (IOException expected) {
            assertTrue(metadata.records.isEmpty());
            assertEquals(0, visibleVaultFiles().length);
        }

        protector.failSeal = false;
        CredentialHandleVault.KeyMetadata generated = vault.generate("generated", "Ed25519");
        assertEquals("ssh-ed25519", generated.algorithm);
        assertFalse(generated.passphraseRequired);
        assertNotNull(UUID.fromString(generated.handleId));
        assertEquals(1, vault.list().size());
        byte[] ciphertext = Files.readAllBytes(new File(directory, generated.handleId + ".vault").toPath());
        assertFalse(new String(ciphertext, StandardCharsets.UTF_8).contains("BEGIN PRIVATE KEY"));
        assertNotEquals(0, ciphertext.length);
    }

    @Test public void generatesAndReopensRsa3072InThePinnedOpenSshFormat() throws Exception {
        CredentialHandleVault.KeyMetadata generated = vault.generate("rsa test", "RSA-3072");
        assertEquals("ssh-rsa", generated.algorithm);
        assertFalse(generated.passphraseRequired);
        byte[] privateKey = vault.resolvePrivateKey(generated.handleId);
        try {
            String serialized = new String(privateKey, StandardCharsets.UTF_8);
            assertTrue(serialized.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"));
            assertTrue(serialized.contains("-----END OPENSSH PRIVATE KEY-----"));
        } finally {
            java.util.Arrays.fill(privateKey, (byte) 0);
        }
    }

    @Test public void deleteUsesFingerprintAndKeepsMetadataIfItsCommitFails() throws Exception {
        byte[] key = resource("/docker_test_key");
        try {
            CredentialHandleVault.KeyMetadata imported = vault.importBytes(key, "fixture", new char[0], null, false);
            metadata.failWrites = true;
            try {
                vault.delete(imported.handleId, imported.fingerprintSha256);
                fail("Metadata failure must preserve the selectable key.");
            } catch (IOException expected) {
                assertEquals(1, vault.list().size());
            }
            metadata.failWrites = false;
            try {
                vault.delete(imported.handleId, "SHA256:" + "B".repeat(43));
                fail("Fingerprint mismatch must not delete another key.");
            } catch (IOException expected) {
                assertEquals(1, vault.list().size());
            }
            assertTrue(vault.delete(imported.handleId, imported.fingerprintSha256));
            assertTrue(vault.list().isEmpty());
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    // #3021: paste import and OpenSSH public-line derivation.

    @Test public void pastedOpenSshKeyWithCollapsedBodyImportsAndDerivesItsPublicLine() throws Exception {
        String pem = new String(resource("/docker_test_key"), StandardCharsets.UTF_8);
        // A chat or notes app can turn the PEM body's newlines into spaces.
        String collapsed = pem.replace("\n", " ").replace("-----END", "\r\n-----END");
        byte[] normalized = CredentialHandleVault.normalizePastedKey("  " + collapsed + "\n\n");
        CredentialHandleVault.KeyMetadata imported = vault.importBytes(normalized, "Pasted\nfixture key", new char[0], null, false);
        assertEquals("SHA256:geJoGi64Up5pm2TGC6bdVNrvlIA1vuPIOtNKo2tLsuQ", imported.fingerprintSha256);
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk8IaZk040AFw1V+YDXgr8L+zQE/5k Pasted fixture key",
            vault.publicKeyLine(imported.handleId, new char[0]));
    }

    @Test public void pastedEncryptedOpenSshKeyNeedsPassphraseButItsPublicLineDoesNot() throws Exception {
        String pem = new String(resource("/encrypted_ed25519"), StandardCharsets.UTF_8).replace("\n", "\r\n");
        byte[] normalized = CredentialHandleVault.normalizePastedKey(pem);
        try {
            vault.importBytes(normalized, "encrypted paste", "wrong".toCharArray(), null, false);
            fail("A wrong passphrase must reject the paste.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("passphrase"));
        }
        try {
            vault.importBytes(normalized, "encrypted paste", new char[0], null, false);
            fail("A missing passphrase must reject the paste.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("passphrase"));
        }
        assertTrue(metadata.records.isEmpty());
        CredentialHandleVault.KeyMetadata imported = vault.importBytes(
            normalized, "encrypted paste", "pocketshell-vault-test-passphrase".toCharArray(), null, false);
        assertTrue(imported.passphraseRequired);
        // Drop the cache so the record looks like one stored before #3021.
        metadata.records.set(0, stripPublicBlob(metadata.records.get(0)));
        assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICOvqDO4DOoI9EDsD+upeyONJ40vO/z7oiZTFYZJU26S encrypted paste",
            vault.publicKeyLine(imported.handleId, new char[0]));
        assertNotNull("the derived public blob is cached for the next request", metadata.records.get(0).publicKeyBlob);
    }

    @Test public void pastedPkcs8RsaKeyImportsAndItsPublicLineMatchesTheFingerprint() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        String body = java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pasted = "-----BEGIN PRIVATE KEY----- " + body + " -----END PRIVATE KEY-----";
        CredentialHandleVault.KeyMetadata imported = vault.importBytes(
            CredentialHandleVault.normalizePastedKey(pasted), "rsa paste", new char[0], null, false);
        assertEquals("ssh-rsa", imported.algorithm);
        assertFalse(imported.passphraseRequired);
        metadata.records.set(0, stripPublicBlob(metadata.records.get(0)));
        String line = vault.publicKeyLine(imported.handleId, new char[0]);
        String[] fields = line.split(" ", 3);
        assertEquals("ssh-rsa", fields[0]);
        assertEquals("rsa paste", fields[2]);
        String fingerprint = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getDecoder().decode(fields[1])));
        assertEquals(imported.fingerprintSha256, fingerprint);
    }

    @Test public void encryptedLegacyPemKeyAsksForItsPassphraseBeforeShowingThePublicLine() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair pair = generator.generateKeyPair();
        java.io.StringWriter writer = new java.io.StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pemWriter = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer)) {
            pemWriter.writeObject(pair.getPrivate(), new org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder("AES-128-CBC")
                .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider())
                .build("legacy-pem-pass".toCharArray()));
        }
        CredentialHandleVault.KeyMetadata imported = vault.importBytes(
            CredentialHandleVault.normalizePastedKey(writer.toString()), "legacy pem", "legacy-pem-pass".toCharArray(), null, false);
        assertTrue(imported.passphraseRequired);
        metadata.records.set(0, stripPublicBlob(metadata.records.get(0)));
        try {
            vault.publicKeyLine(imported.handleId, new char[0]);
            fail("An encrypted non-OpenSSH key must ask for its passphrase.");
        } catch (CredentialHandleVault.PassphraseRequiredException expected) {
            assertTrue(expected.getMessage().toLowerCase().contains("passphrase"));
        }
        assertTrue(vault.publicKeyLine(imported.handleId, "legacy-pem-pass".toCharArray()).startsWith("ssh-rsa AAAA"));
    }

    @Test public void rejectsPublicKeyEmptyAndTruncatedPastesWithClearMessages() throws Exception {
        assertPasteRejected("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIN7osVCLDIy5aFOk8IaZk040AFw1V+YDXgr8L+zQE/5k me", "public key");
        assertPasteRejected("   \n", "paste a private key");
        assertPasteRejected("b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ", "begin and end");
        assertPasteRejected("-----BEGIN OPENSSH PRIVATE KEY-----\nnot*base64!\n-----END OPENSSH PRIVATE KEY-----", "damaged");
        byte[] truncated = CredentialHandleVault.normalizePastedKey(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ==\n-----END OPENSSH PRIVATE KEY-----");
        try {
            vault.importBytes(truncated, "truncated", new char[0], null, false);
            fail("A truncated key must not import.");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("b3BlbnNzaC1"));
        }
        assertTrue(metadata.records.isEmpty());
        assertEquals("one line label", CredentialHandleVault.publicKeyComment("one\r\nline\u0000 label"));
        assertEquals("pocketshell", CredentialHandleVault.publicKeyComment("\n"));
    }

    @Test public void aCachedPublicBlobThatDisagreesWithTheFingerprintIsRefused() throws Exception {
        CredentialHandleVault.KeyMetadata imported = vault.importBytes(resource("/docker_test_key"), "fixture", new char[0], null, false);
        CredentialHandleVault.StoredKey stored = metadata.records.get(0);
        // (org.json is an unmocked Android stub on the JVM, so serialisation of
        // the cache is covered by the packaged journey's reload instead.)
        // A cached blob that disagrees with the fingerprint is refused, not shown.
        String otherBlob = "AAAAC3NzaC1lZDI1NTE5AAAAICOvqDO4DOoI9EDsD+upeyONJ40vO/z7oiZTFYZJU26S";
        metadata.records.set(0, stored.withPublicKeyBlob(otherBlob));
        try {
            vault.publicKeyLine(imported.handleId, new char[0]);
            fail("A mismatched public blob must not be returned.");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("fingerprint"));
        }
    }

    private static void assertPasteRejected(String text, String expectedMessage) {
        try {
            CredentialHandleVault.normalizePastedKey(text);
            fail("Paste must be rejected: " + expectedMessage);
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().toLowerCase().contains(expectedMessage));
        }
    }

    private static CredentialHandleVault.StoredKey stripPublicBlob(CredentialHandleVault.StoredKey record) {
        assertNotNull("new imports cache their public blob", record.publicKeyBlob);
        return record.withPublicKeyBlob(null);
    }

    private File[] visibleVaultFiles() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".vault") || name.endsWith(".pending"));
        return files == null ? new File[0] : files;
    }

    private static byte[] resource(String name) throws IOException {
        try (java.io.InputStream stream = CredentialHandleVaultTest.class.getResourceAsStream(name)) {
            if (stream != null) return stream.readAllBytes();
        }
        if ("/docker_test_key".equals(name)) {
            File current = new File(System.getProperty("user.dir")).getCanonicalFile();
            while (current != null) {
                File fixture = new File(current, "tests/docker/test_key");
                if (fixture.isFile()) return Files.readAllBytes(fixture.toPath());
                current = current.getParentFile();
            }
        }
        throw new IOException("Test key fixture is unavailable.");
    }

    private static void erase(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) erase(child);
        file.delete();
    }

    private static final class MemoryMetadata implements CredentialHandleVault.MetadataRepository {
        List<CredentialHandleVault.StoredKey> records = new ArrayList<>();
        boolean failWrites;
        @Override public List<CredentialHandleVault.StoredKey> read() {
            return new ArrayList<>(records);
        }
        @Override public boolean write(List<CredentialHandleVault.StoredKey> next) {
            if (failWrites) return false;
            records = new ArrayList<>(next);
            return true;
        }
    }

    private static final class TestProtector implements CredentialHandleVault.KeyProtector {
        boolean failSeal;
        @Override public byte[] seal(byte[] plaintext) throws Exception {
            if (failSeal) throw new IOException("injected interrupted write");
            byte[] output = plaintext.clone();
            for (int index = 0; index < output.length; index++) output[index] ^= (byte) 0xa5;
            return output;
        }
        @Override public byte[] open(byte[] ciphertext) {
            byte[] output = ciphertext.clone();
            for (int index = 0; index < output.length; index++) output[index] ^= (byte) 0xa5;
            return output;
        }
    }
}
