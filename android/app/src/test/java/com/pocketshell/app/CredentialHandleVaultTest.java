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
