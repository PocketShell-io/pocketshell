package com.pocketshell.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Process-memory SSH keys from the account key vault (key-vault W4). A vault
 * key is decrypted in the WebView only when a host needs it, handed here once,
 * and dialled by an opaque handle exactly like a stored key handle, so the
 * connect path, reconnect and the gateway plan treat both the same way.
 *
 * <p>Nothing here is ever written to disk, logged or returned over the bridge:
 * the bytes live in this map until the vault locks ({@link #wipeAll}), the
 * handle is released, or the process dies. Every removal zeroes the array.
 * Only unencrypted Ed25519/RSA keys are accepted, which is what the vault
 * stores (the web decrypts a key's own passphrase at upload).
 */
final class EphemeralCredentials {
    /** Handles carry this prefix so the dial path can route them without a lookup. */
    static final String HANDLE_PREFIX = "ephemeral-";
    /** A vault holds a handful of keys; this caps a misbehaving caller, not a user. */
    static final int MAX_HELD = 32;

    static final EphemeralCredentials INSTANCE = new EphemeralCredentials();

    /** Public facts about a held key; never the key. */
    static final class Held {
        final String handleId;
        final String algorithm;
        final String fingerprintSha256;
        Held(String handleId, String algorithm, String fingerprintSha256) {
            this.handleId = handleId;
            this.algorithm = algorithm;
            this.fingerprintSha256 = fingerprintSha256;
        }
    }

    private final Map<String, byte[]> keys = new LinkedHashMap<>();

    static boolean isEphemeral(String handleId) {
        return handleId != null && handleId.startsWith(HANDLE_PREFIX);
    }

    /**
     * Validate an unencrypted private key and hold a private copy of it. The
     * caller's array is left for the caller to zero.
     */
    Held hold(byte[] privateKey) throws IOException {
        if (privateKey == null || privateKey.length == 0 || privateKey.length > CredentialHandleVault.MAX_KEY_BYTES) {
            throw new IOException("The vault key is not a supported SSH private key.");
        }
        CredentialHandleVault.ParsedKey parsed;
        try {
            parsed = CredentialHandleVault.parseKey(privateKey, new char[0]);
        } catch (Exception error) {
            if (unsupportedType(privateKey)) throw new IOException(UNSUPPORTED_TYPE);
            throw new IOException("The vault key is not a supported SSH private key.");
        }
        if (parsed.passphraseRequired) throw new IOException("The vault key is still encrypted with its own passphrase.");
        String handleId = HANDLE_PREFIX + UUID.randomUUID();
        synchronized (keys) {
            if (keys.size() >= MAX_HELD) throw new IOException("Too many vault keys are open at once. Lock the vault and unlock it again.");
            keys.put(handleId, Arrays.copyOf(privateKey, privateKey.length));
        }
        return new Held(handleId, parsed.algorithm, parsed.fingerprintSha256);
    }

    /** A copy of the held key for one userauth; the caller zeroes it. */
    byte[] resolve(String handleId) throws IOException {
        synchronized (keys) {
            byte[] key = keys.get(handleId);
            if (key == null) throw new IOException("The vault is locked. Unlock it to connect.");
            return Arrays.copyOf(key, key.length);
        }
    }

    boolean contains(String handleId) {
        synchronized (keys) {
            return keys.containsKey(handleId);
        }
    }

    boolean release(String handleId) {
        synchronized (keys) {
            byte[] key = keys.remove(handleId);
            if (key == null) return false;
            Arrays.fill(key, (byte) 0);
            return true;
        }
    }

    /** Vault locked, account signed out: every held key is zeroed and forgotten. */
    int wipeAll() {
        synchronized (keys) {
            int count = keys.size();
            for (byte[] key : keys.values()) Arrays.fill(key, (byte) 0);
            keys.clear();
            return count;
        }
    }

    int size() {
        synchronized (keys) {
            return keys.size();
        }
    }

    static final String UNSUPPORTED_TYPE = "This vault key's type is not supported on Android yet: PocketShell for Android "
        + "connects with Ed25519 and RSA keys. Assign one of those to this host on the web.";

    /** An OpenSSH key whose public header names a type other than Ed25519 or RSA (e.g. ECDSA). */
    static boolean unsupportedType(byte[] privateKey) {
        String algorithm = openSshKeyType(privateKey);
        return algorithm != null && !"ssh-ed25519".equals(algorithm) && !"ssh-rsa".equals(algorithm);
    }

    /** The key type named in an openssh-key-v1 header (public data only), or null when it is not one. */
    static String openSshKeyType(byte[] privateKey) {
        String pem = new String(privateKey, StandardCharsets.US_ASCII);
        if (!pem.contains("-----BEGIN OPENSSH PRIVATE KEY-----")) return null;
        byte[] decoded = null;
        try {
            String body = pem.replace("-----BEGIN OPENSSH PRIVATE KEY-----", "")
                .replace("-----END OPENSSH PRIVATE KEY-----", "").replaceAll("\\s", "");
            decoded = java.util.Base64.getDecoder().decode(body);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(decoded);
            byte[] magic = "openssh-key-v1\u0000".getBytes(StandardCharsets.US_ASCII);
            for (byte expected : magic) if (buffer.get() != expected) return null;
            for (int skip = 0; skip < 3; skip++) skipString(buffer); // cipher, KDF, KDF options
            if (buffer.getInt() != 1) return null;
            int blobLength = buffer.getInt();
            int typeLength = buffer.getInt();
            if (blobLength < 4 || typeLength < 1 || typeLength > 64 || typeLength > blobLength - 4) return null;
            byte[] type = new byte[typeLength];
            buffer.get(type);
            return new String(type, StandardCharsets.US_ASCII);
        } catch (RuntimeException malformed) {
            return null;
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    private static void skipString(java.nio.ByteBuffer buffer) {
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) throw new IllegalArgumentException("malformed");
        buffer.position(buffer.position() + length);
    }

    /** Text form of a key the bridge delivered; the String itself cannot be wiped. */
    static byte[] utf8(String pem) {
        return pem == null ? new byte[0] : pem.getBytes(StandardCharsets.UTF_8);
    }
}
