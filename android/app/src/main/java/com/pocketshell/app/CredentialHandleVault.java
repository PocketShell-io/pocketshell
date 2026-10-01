package com.pocketshell.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import net.schmizz.sshj.common.Buffer;
import net.schmizz.sshj.common.KeyType;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyFormat;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil;
import net.schmizz.sshj.userauth.password.PasswordUtils;
import net.schmizz.sshj.common.Factory;
import net.schmizz.sshj.common.SecurityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Android-private SSH credential vault. Only encrypted key files and public
 * metadata are persisted; plaintext key material is consumed inside native
 * SSH operations and is never returned over the Capacitor bridge.
 */
public final class CredentialHandleVault {
    static final int MAX_KEY_BYTES = 1024 * 1024;
    private static final String PREFERENCES_NAME = "pocketshell_ssh_key_vault_v1";
    private static final String RECORDS_KEY = "records";
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    interface MetadataRepository {
        List<StoredKey> read() throws IOException;
        boolean write(List<StoredKey> records) throws IOException;
    }

    interface KeyProtector {
        byte[] seal(byte[] plaintext) throws Exception;
        byte[] open(byte[] ciphertext) throws Exception;
    }

    private final File directory;
    private final MetadataRepository metadata;
    private final KeyProtector protector;
    private final SecureRandom random = new SecureRandom();

    public static CredentialHandleVault forContext(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File directory = new File(app.getFilesDir(), "credential-vault");
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Secure SSH key storage is unavailable.");
        }
        return new CredentialHandleVault(directory, new SharedPreferenceMetadata(app), new AndroidKeyProtector());
    }

    CredentialHandleVault(File directory, MetadataRepository metadata, KeyProtector protector) throws IOException {
        this.directory = directory;
        this.metadata = metadata;
        this.protector = protector;
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Secure SSH key storage is unavailable.");
        }
        cleanupInterruptedWrites();
    }

    public synchronized List<KeyMetadata> list() throws IOException {
        List<StoredKey> records = metadata.read();
        cleanupOrphans(records);
        List<KeyMetadata> result = new ArrayList<>();
        for (StoredKey record : records) result.add(record.publicMetadata());
        return Collections.unmodifiableList(result);
    }

    public synchronized KeyMetadata generate(String label, String algorithm) throws Exception {
        KeyPair pair;
        if ("Ed25519".equals(algorithm)) {
            ensureBouncyCastle();
            pair = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
                .generateKeyPair();
        } else if ("RSA-3072".equals(algorithm)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(3072, random);
            pair = generator.generateKeyPair();
        } else {
            throw new IOException("Supported generated SSH keys are Ed25519 and RSA 3072.");
        }
        byte[] encoded = toPrivatePem(pair);
        try {
            return importBytes(encoded, label, new char[0], null, false);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    public synchronized KeyMetadata importBytes(
        byte[] privateKey,
        String label,
        char[] passphrase,
        String legacySourceIdentity,
        boolean allowExistingLegacySource
    ) throws Exception {
        if (privateKey == null || privateKey.length < 1 || privateKey.length > MAX_KEY_BYTES) {
            throw new IOException("The selected SSH private key is empty or exceeds the 1 MiB limit.");
        }
        String safeLabel = validateLabel(label);
        char[] secret = passphrase == null ? new char[0] : passphrase.clone();
        byte[] ownedBytes = privateKey.clone();
        try {
            List<StoredKey> existing = metadata.read();
            if (legacySourceIdentity != null) {
                for (StoredKey candidate : existing) {
                    if (candidate.legacySourceIdentities.contains(legacySourceIdentity)) {
                        return candidate.publicMetadata();
                    }
                }
            }

            ParsedKey parsed = parseKey(ownedBytes, secret);
            for (int index = 0; index < existing.size(); index++) {
                StoredKey candidate = existing.get(index);
                if (!candidate.fingerprintSha256.equals(parsed.fingerprintSha256)) continue;
                if (legacySourceIdentity == null || !allowExistingLegacySource) {
                    throw new DuplicateKeyException("This SSH key is already stored.");
                }
                if (!candidate.legacySourceIdentities.contains(legacySourceIdentity)) {
                    candidate.legacySourceIdentities.add(legacySourceIdentity);
                    if (!metadata.write(existing)) throw new IOException("SSH key metadata could not be saved.");
                }
                return candidate.publicMetadata();
            }

            String handleId = UUID.randomUUID().toString();
            String fileName = handleId + ".vault";
            File pending = new File(directory, "." + handleId + ".pending");
            File target = new File(directory, fileName);
            byte[] ciphertext = protector.seal(ownedBytes);
            try (FileOutputStream output = new FileOutputStream(pending)) {
                output.write(ciphertext);
                output.getFD().sync();
            } finally {
                java.util.Arrays.fill(ciphertext, (byte) 0);
            }
            if (!pending.renameTo(target)) {
                pending.delete();
                throw new IOException("The SSH key could not be safely stored.");
            }

            List<String> legacyIds = new ArrayList<>();
            if (legacySourceIdentity != null) legacyIds.add(legacySourceIdentity);
            StoredKey stored = new StoredKey(
                handleId,
                safeLabel,
                parsed.algorithm,
                parsed.fingerprintSha256,
                parsed.passphraseRequired,
                System.currentTimeMillis(),
                fileName,
                legacyIds
            );
            existing.add(stored);
            try {
                if (!metadata.write(existing)) throw new IOException("SSH key metadata could not be saved.");
            } catch (Exception error) {
                target.delete();
                throw error;
            }
            return stored.publicMetadata();
        } finally {
            java.util.Arrays.fill(secret, '\0');
            java.util.Arrays.fill(ownedBytes, (byte) 0);
        }
    }

    public synchronized KeyMetadata importLegacyKey(
        Context context,
        long legacyKeyId,
        String expectedSha256,
        String label,
        boolean hasPassphrase
    ) throws Exception {
        String sourceIdentity = legacyKeyId + ":" + expectedSha256;
        for (StoredKey candidate : metadata.read()) {
            if (candidate.legacySourceIdentities.contains(sourceIdentity)) return candidate.publicMetadata();
        }
        byte[] bytes = LegacyPrivateKeyResolver.readPrivateKeyBytes(
            context, legacyKeyId, expectedSha256
        );
        try {
            char[] noPassphrase = new char[0];
            if (hasPassphrase) {
                // The legacy key remains encrypted. OpenSSH v1 keeps its public
                // key blob outside the encrypted private section; importing it
                // without a passphrase preserves usability without prompting at
                // upgrade time. If its format cannot be inspected safely, the
                // native resolver remains available as a compatibility path.
                ParsedKey parsed = parseEncryptedLegacyMetadata(bytes);
                return storeOpaqueEncryptedLegacy(bytes, label, sourceIdentity, parsed);
            }
            return importBytes(bytes, label, noPassphrase, sourceIdentity, true);
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Returns private bytes only to the native SSH adapter. */
    synchronized byte[] resolvePrivateKey(String handleId) throws IOException {
        StoredKey record = find(handleId);
        File file = checkedVaultFile(record.fileName);
        byte[] ciphertext = readBounded(file, MAX_KEY_BYTES + 64);
        try {
            byte[] privateBytes = protector.open(ciphertext);
            if (privateBytes.length < 1 || privateBytes.length > MAX_KEY_BYTES) {
                java.util.Arrays.fill(privateBytes, (byte) 0);
                throw new IOException("The stored SSH key is outside the supported size range.");
            }
            return privateBytes;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("The stored SSH key could not be unlocked safely.");
        } finally {
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    public synchronized boolean delete(String handleId, String expectedFingerprint) throws IOException {
        List<StoredKey> records = metadata.read();
        StoredKey record = null;
        for (StoredKey candidate : records) if (candidate.handleId.equals(handleId)) record = candidate;
        if (record == null) return false;
        if (expectedFingerprint == null || !record.fingerprintSha256.equals(expectedFingerprint)) {
            throw new IOException("SSH key changed before deletion; refresh the key list and retry.");
        }
        records.remove(record);
        if (!metadata.write(records)) throw new IOException("SSH key metadata could not be updated; the key was left in place.");
        File file = new File(directory, record.fileName);
        // Metadata is the authoritative selection index. If this unlink is
        // interrupted, list() removes the orphan on the next vault operation.
        if (file.exists()) file.delete();
        return true;
    }

    KeyMetadata storeOpaqueEncryptedLegacy(
        byte[] bytes,
        String label,
        String sourceIdentity,
        ParsedKey parsed
    ) throws Exception {
        List<StoredKey> records = metadata.read();
        for (StoredKey candidate : records) {
            if (!candidate.fingerprintSha256.equals(parsed.fingerprintSha256)) continue;
            if (!candidate.legacySourceIdentities.contains(sourceIdentity)) {
                candidate.legacySourceIdentities.add(sourceIdentity);
                if (!metadata.write(records)) throw new IOException("Legacy SSH key metadata could not be saved.");
            }
            return candidate.publicMetadata();
        }
        String handleId = UUID.randomUUID().toString();
        String fileName = handleId + ".vault";
        File pending = new File(directory, "." + handleId + ".pending");
        File target = new File(directory, fileName);
        byte[] ciphertext = protector.seal(bytes);
        try (FileOutputStream output = new FileOutputStream(pending)) {
            output.write(ciphertext);
            output.getFD().sync();
        } finally {
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
        if (!pending.renameTo(target)) {
            pending.delete();
            throw new IOException("The legacy SSH key could not be safely imported.");
        }
        StoredKey stored = new StoredKey(
            handleId,
            validateLabel(label),
            parsed.algorithm,
            parsed.fingerprintSha256,
            true,
            System.currentTimeMillis(),
            fileName,
            new ArrayList<>(Collections.singletonList(sourceIdentity))
        );
        records.add(stored);
        if (!metadata.write(records)) {
            target.delete();
            throw new IOException("Legacy SSH key metadata could not be saved.");
        }
        return stored.publicMetadata();
    }

    static ParsedKey parseEncryptedLegacyMetadata(byte[] bytes) throws IOException {
        String pem = new String(bytes, StandardCharsets.UTF_8);
        if (!pem.contains("BEGIN OPENSSH PRIVATE KEY")) {
            throw new IOException("This legacy encrypted key format needs an unlock before it can be imported.");
        }
        byte[] decoded;
        try {
            String body = pem.replaceAll("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replaceAll("-----END OPENSSH PRIVATE KEY-----", "").replaceAll("\\s", "");
            decoded = java.util.Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException error) {
            throw new IOException("The legacy SSH key has invalid encoding.");
        }
        try {
            byte[] magic = "openssh-key-v1\u0000".getBytes(StandardCharsets.US_ASCII);
            if (decoded.length < magic.length + 20) throw new IOException("The legacy SSH key is malformed.");
            for (int index = 0; index < magic.length; index++) {
                if (decoded[index] != magic[index]) throw new IOException("The legacy SSH key format is unsupported.");
            }
            SshBinaryReader reader = new SshBinaryReader(decoded, magic.length);
            reader.readString(); // cipher
            reader.readString(); // KDF
            reader.readString(); // KDF options
            int keyCount = reader.readInt();
            if (keyCount != 1) throw new IOException("The legacy SSH key contains an unsupported key count.");
            byte[] publicBlob = reader.readStringBytes();
            Buffer<?> publicKeyReader = new Buffer<>(publicBlob);
            String algorithm = publicKeyReader.readString();
            if (!"ssh-ed25519".equals(algorithm) && !"ssh-rsa".equals(algorithm)) {
                throw new IOException("Only Ed25519 and RSA SSH keys are supported.");
            }
            String fingerprint = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicBlob)
            );
            return new ParsedKey(algorithm, fingerprint, true);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("The legacy SSH key could not be inspected safely.");
        } finally {
            java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    private static ParsedKey parseKey(byte[] bytes, char[] passphrase) throws Exception {
        ensureBouncyCastle();
        String pem = new String(bytes, StandardCharsets.UTF_8);
        if (pem.indexOf('\u0000') >= 0 || pem.trim().isEmpty()) throw new IOException("The selected file is not a supported SSH private key.");
        KeyFormat format;
        try {
            format = KeyProviderUtil.detectKeyFileFormat(pem, passphrase.length > 0);
        } catch (Exception error) {
            throw new IOException("The selected file is not a supported SSH private key.");
        }
        SSHClient parser = new SSHClient();
        FileKeyProvider provider = Factory.Named.Util.create(
            parser.getTransport().getConfig().getFileKeyProviderFactories(), format.toString()
        );
        if (provider == null) throw new IOException("The selected SSH private key format is not supported.");
        try {
            provider.init(pem, null, PasswordUtils.createOneOff(passphrase));
            KeyType type = provider.getType();
            String algorithm = type.toString();
            if (!"ssh-ed25519".equals(algorithm) && !"ssh-rsa".equals(algorithm)) {
                throw new IOException("Only Ed25519 and RSA SSH keys are supported by PocketShell.");
            }
            Buffer<?> publicKey = new Buffer<>();
            publicKey.putPublicKey(provider.getPublic());
            byte[] publicBlob = publicKey.getCompactData();
            String fingerprint = "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(publicBlob)
            );
            java.util.Arrays.fill(publicBlob, (byte) 0);
            return new ParsedKey(algorithm, fingerprint, encryptedKey(bytes));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException(passphrase.length > 0
                ? "Could not unlock this SSH key. Check the passphrase and try again."
                : "This SSH key may require a passphrase. Enter it and try again.");
        }
    }

    private static boolean encryptedKey(byte[] bytes) {
        String pem = new String(bytes, StandardCharsets.UTF_8);
        if (pem.contains("-----BEGIN ENCRYPTED PRIVATE KEY-----") || pem.contains("Proc-Type: 4,ENCRYPTED")) return true;
        if (!pem.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) return false;
        byte[] decoded = null;
        try {
            String body = pem.replaceAll("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replaceAll("-----END OPENSSH PRIVATE KEY-----", "").replaceAll("\\s", "");
            decoded = java.util.Base64.getMimeDecoder().decode(body);
            byte[] magic = "openssh-key-v1\u0000".getBytes(StandardCharsets.US_ASCII);
            if (decoded.length < magic.length) return true;
            SshBinaryReader reader = new SshBinaryReader(decoded, magic.length);
            return !"none".equals(reader.readString());
        } catch (Exception error) {
            return true;
        } finally {
            if (decoded != null) java.util.Arrays.fill(decoded, (byte) 0);
        }
    }

    private static byte[] toPrivatePem(KeyPair pair) throws IOException {
        byte[] privateBlockBytes = null;
        byte[] publicBlob = null;
        try {
            Buffer<?> publicKey = new Buffer<>();
            publicKey.putPublicKey(pair.getPublic());
            publicBlob = publicKey.getCompactData();

            Buffer<?> privateBlock = new Buffer<>();
            SecureRandom random = new SecureRandom();
            long check = Integer.toUnsignedLong(random.nextInt());
            privateBlock.putUInt32(check);
            privateBlock.putUInt32(check);
            if (pair.getPrivate().getAlgorithm().equalsIgnoreCase("Ed25519")) {
                byte[] privateEncoding = pair.getPrivate().getEncoded();
                byte[] seed = null;
                byte[] publicBytes = null;
                try {
                    Ed25519PrivateKeyParameters privateKey = (Ed25519PrivateKeyParameters) PrivateKeyFactory.createKey(privateEncoding);
                    seed = privateKey.getEncoded();
                    publicBytes = SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded())
                        .getPublicKeyData().getBytes();
                    byte[] privateBytes = new byte[seed.length + publicBytes.length];
                    System.arraycopy(seed, 0, privateBytes, 0, seed.length);
                    System.arraycopy(publicBytes, 0, privateBytes, seed.length, publicBytes.length);
                    privateBlock.putString("ssh-ed25519")
                        .putString(publicBytes)
                        .putString(privateBytes)
                        .putString("");
                    java.util.Arrays.fill(privateBytes, (byte) 0);
                } catch (Exception error) {
                    throw new IOException("Ed25519 key generation could not be encoded safely.");
                } finally {
                    java.util.Arrays.fill(privateEncoding, (byte) 0);
                    if (seed != null) java.util.Arrays.fill(seed, (byte) 0);
                    if (publicBytes != null) java.util.Arrays.fill(publicBytes, (byte) 0);
                }
            } else if (pair.getPrivate() instanceof RSAPrivateCrtKey && pair.getPublic() instanceof RSAPublicKey) {
                RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) pair.getPrivate();
                RSAPublicKey publicKeyPair = (RSAPublicKey) pair.getPublic();
                privateBlock.putString("ssh-rsa")
                    .putMPInt(publicKeyPair.getModulus())
                    .putMPInt(publicKeyPair.getPublicExponent())
                    .putMPInt(privateKey.getPrivateExponent())
                    .putMPInt(privateKey.getCrtCoefficient())
                    .putMPInt(privateKey.getPrimeP())
                    .putMPInt(privateKey.getPrimeQ())
                    .putString("");
            } else {
                throw new IOException("The generated SSH key format is not supported.");
            }
            int paddingLength = (8 - privateBlock.available() % 8) % 8;
            for (int index = 1; index <= paddingLength; index++) privateBlock.putByte((byte) index);
            privateBlockBytes = privateBlock.getCompactData();
            java.util.Arrays.fill(privateBlock.array(), (byte) 0);

            Buffer<?> container = new Buffer<>();
            container.putString("none").putString("none").putString(new byte[0]).putUInt32(1)
                .putString(publicBlob).putString(privateBlockBytes);
            byte[] contents = container.getCompactData();
            WipingByteArrayOutputStream pem = new WipingByteArrayOutputStream(8192);
            try {
                pem.write("-----BEGIN OPENSSH PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII));
                try (OutputStream encoded = java.util.Base64.getMimeEncoder(70, new byte[] {'\n'}).wrap(pem)) {
                    encoded.write("openssh-key-v1\u0000".getBytes(StandardCharsets.US_ASCII));
                    encoded.write(contents);
                }
                pem.write("\n-----END OPENSSH PRIVATE KEY-----\n".getBytes(StandardCharsets.US_ASCII));
                return pem.toByteArrayAndWipe();
            } finally {
                pem.wipe();
                java.util.Arrays.fill(contents, (byte) 0);
                java.util.Arrays.fill(container.array(), (byte) 0);
            }
        } finally {
            if (publicBlob != null) java.util.Arrays.fill(publicBlob, (byte) 0);
            if (privateBlockBytes != null) java.util.Arrays.fill(privateBlockBytes, (byte) 0);
        }
    }

    private static String validateLabel(String value) throws IOException {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 80 || value.indexOf('\u0000') >= 0) {
            throw new IOException("Enter a key label between 1 and 80 characters.");
        }
        return value.trim();
    }

    private StoredKey find(String handleId) throws IOException {
        if (handleId == null || !handleId.matches("[0-9a-fA-F-]{36}")) throw new IOException("SSH key handle is invalid.");
        for (StoredKey record : metadata.read()) if (record.handleId.equals(handleId)) return record;
        throw new IOException("The selected SSH key is no longer available.");
    }

    private File checkedVaultFile(String name) throws IOException {
        if (name == null || !name.matches("[0-9a-fA-F-]{36}\\.vault")) throw new IOException("Stored SSH key reference is invalid.");
        File file = new File(directory, name);
        if (java.nio.file.Files.isSymbolicLink(file.toPath()) || !file.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile())
            || !file.isFile() || !file.canRead() || file.length() < GCM_NONCE_BYTES + 16 || file.length() > MAX_KEY_BYTES + 64L) {
            throw new IOException("Stored SSH key is missing or unreadable; the original imported source remains unchanged.");
        }
        return file;
    }

    private void cleanupInterruptedWrites() throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Secure SSH key storage could not be inspected.");
        for (File file : files) if (file.getName().startsWith(".") && file.getName().endsWith(".pending")) file.delete();
        cleanupOrphans(metadata.read());
    }

    private void cleanupOrphans(List<StoredKey> records) throws IOException {
        Set<String> indexed = new HashSet<>();
        for (StoredKey record : records) indexed.add(record.fileName);
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Secure SSH key storage could not be inspected.");
        for (File file : files) {
            if (file.getName().endsWith(".vault") && !indexed.contains(file.getName())) file.delete();
            else if (file.getName().startsWith(".") && file.getName().endsWith(".pending")) file.delete();
        }
    }

    private static byte[] readBounded(File file, int maximumBytes) throws IOException {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > maximumBytes) throw new IOException("Stored SSH key exceeds the supported size.");
                output.write(buffer, 0, count);
            }
            java.util.Arrays.fill(buffer, (byte) 0);
            return output.toByteArray();
        }
    }

    private static void ensureBouncyCastle() throws IOException {
        try {
            Provider installed = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME);
            if (installed == null || !installed.getClass().equals(BouncyCastleProvider.class)) {
                if (installed != null) Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
                Security.insertProviderAt(new BouncyCastleProvider(), 1);
            }
            SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME);
        } catch (Exception error) {
            throw new IOException("SSH key cryptography could not be initialized.");
        }
    }

    static final class KeyMetadata {
        final String handleId;
        final String label;
        final String algorithm;
        final String fingerprintSha256;
        final boolean passphraseRequired;
        final long createdAt;

        KeyMetadata(String handleId, String label, String algorithm, String fingerprintSha256, boolean passphraseRequired, long createdAt) {
            this.handleId = handleId;
            this.label = label;
            this.algorithm = algorithm;
            this.fingerprintSha256 = fingerprintSha256;
            this.passphraseRequired = passphraseRequired;
            this.createdAt = createdAt;
        }

        JSONObject asJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("handleId", handleId);
                json.put("label", label);
                json.put("algorithm", algorithm);
                json.put("fingerprintSha256", fingerprintSha256);
                json.put("passphraseRequired", passphraseRequired);
                json.put("createdAt", createdAt);
                return json;
            } catch (JSONException error) {
                throw new IllegalStateException("Could not serialize public SSH key metadata.", error);
            }
        }
    }

    static final class DuplicateKeyException extends IOException {
        DuplicateKeyException(String message) { super(message); }
    }

    static final class StoredKey {
        final String handleId;
        final String label;
        final String algorithm;
        final String fingerprintSha256;
        final boolean passphraseRequired;
        final long createdAt;
        final String fileName;
        final List<String> legacySourceIdentities;

        StoredKey(String handleId, String label, String algorithm, String fingerprintSha256,
                  boolean passphraseRequired, long createdAt, String fileName, List<String> legacySourceIdentities) {
            this.handleId = handleId;
            this.label = label;
            this.algorithm = algorithm;
            this.fingerprintSha256 = fingerprintSha256;
            this.passphraseRequired = passphraseRequired;
            this.createdAt = createdAt;
            this.fileName = fileName;
            this.legacySourceIdentities = new ArrayList<>(legacySourceIdentities);
        }

        KeyMetadata publicMetadata() {
            return new KeyMetadata(handleId, label, algorithm, fingerprintSha256, passphraseRequired, createdAt);
        }

        JSONObject serialize() {
            JSONArray legacy = new JSONArray();
            for (String identity : legacySourceIdentities) legacy.put(identity);
            JSONObject json = new JSONObject();
            try {
                json.put("handleId", handleId);
                json.put("label", label);
                json.put("algorithm", algorithm);
                json.put("fingerprintSha256", fingerprintSha256);
                json.put("passphraseRequired", passphraseRequired);
                json.put("createdAt", createdAt);
                json.put("fileName", fileName);
                json.put("legacySourceIdentities", legacy);
                return json;
            } catch (JSONException error) {
                throw new IllegalStateException("Could not serialize SSH key metadata.", error);
            }
        }

        static StoredKey parse(JSONObject value) throws IOException {
            try {
                String handleId = value.getString("handleId");
                String fileName = value.getString("fileName");
                String label = value.getString("label");
                String algorithm = value.getString("algorithm");
                String fingerprint = value.getString("fingerprintSha256");
                long created = value.getLong("createdAt");
                boolean passphrase = value.getBoolean("passphraseRequired");
                if (!handleId.matches("[0-9a-fA-F-]{36}") || !fileName.equals(handleId + ".vault")
                    || !fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}") || created < 0
                    || (!"ssh-ed25519".equals(algorithm) && !"ssh-rsa".equals(algorithm))) {
                    throw new IOException("Saved SSH key metadata is malformed.");
                }
                JSONArray legacy = value.optJSONArray("legacySourceIdentities");
                List<String> identities = new ArrayList<>();
                if (legacy != null) for (int index = 0; index < legacy.length(); index++) identities.add(legacy.getString(index));
                return new StoredKey(handleId, label, algorithm, fingerprint, passphrase, created, fileName, identities);
            } catch (JSONException error) {
                throw new IOException("Saved SSH key metadata is malformed.");
            }
        }
    }

    static final class ParsedKey {
        final String algorithm;
        final String fingerprintSha256;
        final boolean passphraseRequired;
        ParsedKey(String algorithm, String fingerprintSha256, boolean passphraseRequired) {
            this.algorithm = algorithm;
            this.fingerprintSha256 = fingerprintSha256;
            this.passphraseRequired = passphraseRequired;
        }
    }

    private static final class SharedPreferenceMetadata implements MetadataRepository {
        private final SharedPreferences preferences;
        SharedPreferenceMetadata(Context context) {
            preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        }
        @Override public synchronized List<StoredKey> read() throws IOException {
            String raw = preferences.getString(RECORDS_KEY, "[]");
            try {
                JSONArray rows = new JSONArray(raw);
                List<StoredKey> result = new ArrayList<>();
                for (int index = 0; index < rows.length(); index++) result.add(StoredKey.parse(rows.getJSONObject(index)));
                return result;
            } catch (JSONException error) {
                throw new IOException("Saved SSH key metadata is unreadable; no keys were changed.");
            }
        }
        @Override public synchronized boolean write(List<StoredKey> records) throws IOException {
            JSONArray rows = new JSONArray();
            for (StoredKey record : records) rows.put(record.serialize());
            return preferences.edit().putString(RECORDS_KEY, rows.toString()).commit();
        }
    }

    private static final class AndroidKeyProtector implements KeyProtector {
        private static final String KEY_ALIAS = "pocketshell.ssh.credential-vault.v1";
        private SecretKey key() throws Exception {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            android.security.keystore.KeyGenParameterSpec spec = new android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
            generator.init(spec);
            return generator.generateKey();
        }
        @Override public byte[] seal(byte[] plaintext) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] nonce = cipher.getIV();
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] output = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, output, 0, nonce.length);
            System.arraycopy(encrypted, 0, output, nonce.length, encrypted.length);
            java.util.Arrays.fill(encrypted, (byte) 0);
            return output;
        }
        @Override public byte[] open(byte[] ciphertext) throws Exception {
            if (ciphertext.length <= GCM_NONCE_BYTES) throw new IOException("Stored SSH key ciphertext is malformed.");
            byte[] nonce = java.util.Arrays.copyOfRange(ciphertext, 0, GCM_NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(ciphertext, GCM_NONCE_BYTES, ciphertext.length);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, nonce));
                return cipher.doFinal(encrypted);
            } finally {
                java.util.Arrays.fill(nonce, (byte) 0);
                java.util.Arrays.fill(encrypted, (byte) 0);
            }
        }
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        WipingByteArrayOutputStream(int capacity) { super(capacity); }
        byte[] toByteArrayAndWipe() {
            byte[] result = toByteArray();
            wipe();
            return result;
        }
        void wipe() {
            java.util.Arrays.fill(buf, (byte) 0);
            reset();
        }
    }

    private static final class SshBinaryReader {
        private final byte[] bytes;
        private int offset;
        SshBinaryReader(byte[] bytes, int offset) { this.bytes = bytes; this.offset = offset; }
        int readInt() throws IOException {
            if (bytes.length - offset < 4) throw new IOException("SSH key is malformed.");
            int value = ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
            offset += 4;
            return value;
        }
        byte[] readStringBytes() throws IOException {
            int length = readInt();
            if (length < 0 || length > bytes.length - offset) throw new IOException("SSH key is malformed.");
            byte[] value = java.util.Arrays.copyOfRange(bytes, offset, offset + length);
            offset += length;
            return value;
        }
        String readString() throws IOException { return new String(readStringBytes(), StandardCharsets.UTF_8); }
    }
}
