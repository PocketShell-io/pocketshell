package com.pocketshell.next.hosts

import com.hierynomus.sshj.userauth.keyprovider.bcrypt.BCrypt
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.PBEParametersGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * Shared #2842 regression helpers: the OpenSSH v1 private-key container parsed
 * the way ssh-keygen reads it (PROTOCOL.key), and a JDK-only sign/verify oracle
 * over the stored halves. Used by [SshKeyMaterialEd25519Test] and by the
 * store-level round-trip in [SshKeyStoreTest].
 */

/** The parts of an unencrypted `OPENSSH PRIVATE KEY` payload sshd relies on. */
internal class OpenSshEd25519Container(
    val publicKeyBlob: ByteArray,
    val checkInt1: Int,
    val checkInt2: Int,
    val privateKeyType: String,
    val embeddedPublic: ByteArray,
    val seedAndPublic: ByteArray,
    val comment: ByteArray,
    val padding: ByteArray,
) {
    /** The first 32 bytes of the 64-byte private half: the signing seed. */
    val seed: ByteArray by lazy { seedAndPublic.copyOfRange(0, 32) }
}

internal fun parseOpenSshEd25519Container(
    pem: String,
    decryptedPrivate: ByteArray? = null,
): OpenSshEd25519Container {
    val lines = pem.trim().lines()
    require(lines.first() == "-----BEGIN OPENSSH PRIVATE KEY-----") { "not an OpenSSH PEM" }
    require(lines.last() == "-----END OPENSSH PRIVATE KEY-----") { "truncated OpenSSH PEM" }
    val decoded = Base64.getMimeDecoder()
        .decode(lines.subList(1, lines.lastIndex).joinToString(""))
    require(decoded.size >= 15 && decoded.copyOfRange(0, 14).decodeToString() == "openssh-key-v1") {
        "bad container magic"
    }

    var offset = 15
    fun intAt(at: Int): Int = BigInteger(1, decoded.copyOfRange(at, at + 4)).toInt()
    fun readString(): ByteArray {
        val length = intAt(offset)
        offset += 4
        val value = decoded.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    readString() // cipher name ("none")
    readString() // kdf name ("none")
    readString() // kdf options
    require(intAt(offset) == 1) { "expected exactly one key in the container" }
    offset += 4
    val publicKeyBlob = readString()
    val privateSection = decryptedPrivate ?: readString()
    if (decryptedPrivate == null) {
        require(offset == decoded.size) { "trailing bytes after the private section" }
    }

    // The clear public blob is the authorized-keys wire encoding:
    // string type + string key bytes.
    val publicTypeLength = BigInteger(1, publicKeyBlob.copyOfRange(0, 4)).toInt()
    val publicType = publicKeyBlob.copyOfRange(4, 4 + publicTypeLength).decodeToString()
    require(publicType == "ssh-ed25519") { "unexpected public blob type $publicType" }
    val keyLength = BigInteger(1, publicKeyBlob.copyOfRange(4 + publicTypeLength, 8 + publicTypeLength)).toInt()
    val embeddedPublic = publicKeyBlob.copyOfRange(
        8 + publicTypeLength,
        8 + publicTypeLength + keyLength,
    )

    var privateOffset = 0
    fun readPrivateInt(): Int {
        val value = BigInteger(1, privateSection.copyOfRange(privateOffset, privateOffset + 4)).toInt()
        privateOffset += 4
        return value
    }
    fun readPrivateString(): ByteArray {
        val length = readPrivateInt()
        val value = privateSection.copyOfRange(privateOffset, privateOffset + length)
        privateOffset += length
        return value
    }

    val checkInt1 = readPrivateInt()
    val checkInt2 = readPrivateInt()
    val privateKeyType = readPrivateString().decodeToString()
    val embeddedPublicFromPrivate = readPrivateString()
    val seedAndPublic = readPrivateString()
    val comment = readPrivateString()
    val padding = privateSection.copyOfRange(privateOffset, privateSection.size)

    require(privateKeyType == "ssh-ed25519") { "unexpected private key type $privateKeyType" }
    require(embeddedPublicFromPrivate.contentEquals(embeddedPublic)) {
        "the private section's public half differs from the container's public blob"
    }
    require(seedAndPublic.size == 64) { "the Ed25519 private half must be 64 bytes" }
    require(checkInt1 == checkInt2) { "checkint mismatch" }
    require(padding.map(Byte::toInt) == (1..padding.size).toList()) { "bad padding" }

    return OpenSshEd25519Container(
        publicKeyBlob = publicKeyBlob,
        checkInt1 = checkInt1,
        checkInt2 = checkInt2,
        privateKeyType = privateKeyType,
        embeddedPublic = embeddedPublic,
        seedAndPublic = seedAndPublic,
        comment = comment,
        padding = padding,
    )
}

/** JDK Ed25519 private key built from a raw 32-byte seed (PKCS#8 per RFC 8410). */
internal fun ed25519PrivateKeyFromSeed(seed: ByteArray): PrivateKey {
    val pkcs8 = ByteArrayOutputStream().apply {
        // SEQ { INTEGER 0, SEQ { OID 1.3.101.112 }, OCTET STRING { seed } }
        write(HEX_PKCS8_PREFIX)
        write(seed)
    }.toByteArray()
    return KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
}

/** JDK Ed25519 public key built from a raw 32-byte key (X.509 per RFC 8410). */
internal fun ed25519PublicKeyFromRaw(raw: ByteArray): PublicKey {
    val x509 = ByteArrayOutputStream().apply {
        // SEQ { SEQ { OID 1.3.101.112 }, BIT STRING { 0x00, key } }
        write(HEX_X509_PREFIX)
        write(raw)
    }.toByteArray()
    return KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(x509))
}

/**
 * The D20 differential, JVM edition: sign with the stored seed, verify with the
 * stored public half, using only the JDK's Ed25519 implementation — independent
 * of both the generation code under test and BouncyCastle.
 */
internal fun signatureVerifiesAgainstStoredPublic(
    seed: ByteArray,
    embeddedPublic: ByteArray,
): Boolean {
    val message = "pocketshell-i2842-regression".toByteArray(Charsets.UTF_8)
    val signer = Signature.getInstance("Ed25519").apply {
        initSign(ed25519PrivateKeyFromSeed(seed))
        update(message)
    }
    val signature = signer.sign()
    val verifier = Signature.getInstance("Ed25519").apply {
        initVerify(ed25519PublicKeyFromRaw(embeddedPublic))
        update(message)
    }
    return verifier.verify(signature)
}

/**
 * Decrypt an OpenSSH-encrypted private section — bcrypt KDF + AES-256-CTR, the
 * scheme [SshKeyMaterial.generatePrivateKeyPem] writes for protected keys — so
 * the passphrase-protected branch gets the same structural oracle. Uses the
 * same sshj-shipped BCrypt the production encryption path uses, because the
 * cost parameter semantics are implementation-specific.
 */
internal fun decryptOpenSshPrivateSection(
    pem: String,
    passphrase: CharArray,
): ByteArray {
    val lines = pem.trim().lines()
    val decoded = Base64.getMimeDecoder()
        .decode(lines.subList(1, lines.lastIndex).joinToString(""))
    var offset = 15
    fun readString(): ByteArray {
        val length = BigInteger(1, decoded.copyOfRange(offset, offset + 4)).toInt()
        offset += 4
        val value = decoded.copyOfRange(offset, offset + length)
        offset += length
        return value
    }

    val cipherName = readString().decodeToString()
    val kdfName = readString().decodeToString()
    val kdfOptions = readString()
    require(cipherName == "aes256-ctr") { "unexpected cipher $cipherName" }
    require(kdfName == "bcrypt") { "unexpected kdf $kdfName" }

    // kdfOptions = sshString(salt) + uint32(rounds).
    val saltLength = BigInteger(1, kdfOptions.copyOfRange(0, 4)).toInt()
    val salt = kdfOptions.copyOfRange(4, 4 + saltLength)
    val rounds = BigInteger(1, kdfOptions.copyOfRange(4 + saltLength, 8 + saltLength)).toInt()

    check(BigInteger(1, decoded.copyOfRange(offset, offset + 4)).toInt() == 1) { "expected one key" }
    offset += 4
    readString() // clear public blob
    val encryptedPrivate = readString()

    val password = PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(passphrase)
    val keyAndIv = ByteArray(32 + 16)
    BCrypt().pbkdf(password, salt, rounds, keyAndIv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(
        Cipher.ENCRYPT_MODE, // CTR: the forward cipher is applied both directions
        SecretKeySpec(keyAndIv.copyOfRange(0, 32), "AES"),
        IvParameterSpec(keyAndIv.copyOfRange(32, 48)),
    )
    return cipher.doFinal(encryptedPrivate)
}

/** `SEQ { INTEGER 0, SEQ { OID id-Ed25519 }, OCTET STRING { OCTET STRING { seed } } }` DER prefix. */
private val HEX_PKCS8_PREFIX = byteArrayOf(
    0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
)

/** `SEQ { SEQ { OID id-Ed25519 }, BIT STRING }` DER prefix. */
private val HEX_X509_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)

/**
 * #2842 regression: a key produced by the in-app generator must authenticate.
 *
 * The D20 evidence showed a generated key whose OpenSSH container carried a
 * 64-byte private half of public||public — the seed had been recovered by
 * slicing the JCA `private.encoded` PKCS#8, whose trailing RFC 5958 `[1]` field
 * holds the PUBLIC key. Signatures from such a key verify against a different
 * public key than the container carries, so sshd rejects them. These tests
 * parse the container exactly the way ssh-keygen does and run the differential
 * through the JDK's Ed25519 implementation.
 */
class SshKeyMaterialEd25519Test {

    @Test
    fun `a generated ED25519 key's seed signs under its own container's public half`() {
        val pem = SshKeyMaterial.generatePrivateKeyPem(SshKeyGenerationType.ED25519)

        val container = parseOpenSshEd25519Container(pem)

        // The exact #2842 corruption signature: seed must not be the public key.
        assertTrue(
            "the private half's seed must differ from the public half",
            !container.seed.contentEquals(container.embeddedPublic),
        )
        // OpenSSH's own invariant: the 64-byte private half ends with its public key.
        assertTrue(
            "the private half must end with the container's public key",
            container.seedAndPublic.copyOfRange(32, 64).contentEquals(container.embeddedPublic),
        )
        // And the differential that failed in the field: seed signs, public verifies.
        assertTrue(
            "a signature from the stored seed must verify against the stored public half",
            signatureVerifiesAgainstStoredPublic(container.seed, container.embeddedPublic),
        )
    }

    @Test
    fun `the derived public line is a parseable ssh-ed25519 blob matching the container`() {
        val pem = SshKeyMaterial.generatePrivateKeyPem(SshKeyGenerationType.ED25519)
        val container = parseOpenSshEd25519Container(pem)

        val publicLine = requireNotNull(SshKeyMaterial.publicKeyLine(pem)) {
            "the generated key must expose its public half"
        }
        val fields = publicLine.trim().split(Regex("\\s+"))
        assertEquals("ssh-ed25519", fields[0])

        val blob = Base64.getDecoder().decode(fields[1])
        val typeLength = BigInteger(1, blob.copyOfRange(0, 4)).toInt()
        val type = blob.copyOfRange(4, 4 + typeLength).decodeToString()
        val keyLength = BigInteger(1, blob.copyOfRange(4 + typeLength, 8 + typeLength)).toInt()
        val rawKey = blob.copyOfRange(8 + typeLength, 8 + typeLength + keyLength)

        assertEquals("ssh-ed25519", type)
        assertEquals(32, rawKey.size)
        assertTrue(
            "the derived public bytes must equal the private container's public half",
            rawKey.contentEquals(container.embeddedPublic),
        )
        assertTrue(SshKeyMaterial.publicKeyFingerprint(publicLine).startsWith("SHA256:"))
    }

    @Test
    fun `a passphrase-protected generated key decrypts to a consistent keypair`() {
        val passphrase = "correct horse battery staple".toCharArray()
        val pem = SshKeyMaterial.generatePrivateKeyPem(
            SshKeyGenerationType.ED25519,
            passphrase,
        )
        assertTrue(SshKeyMaterial.isEncrypted(pem))

        val container = parseOpenSshEd25519Container(
            pem,
            decryptedPrivate = decryptOpenSshPrivateSection(pem, passphrase),
        )
        val seed = container.seed
        val seedAndPublic = container.seedAndPublic

        assertEquals(64, seedAndPublic.size)
        assertTrue(
            "the decrypted private half must end with the container's public key",
            seedAndPublic.copyOfRange(32, 64).contentEquals(container.embeddedPublic),
        )
        assertTrue(
            "the decrypted seed must differ from the public half",
            !seed.contentEquals(container.embeddedPublic),
        )
        assertTrue(
            "a signature from the decrypted seed must verify against the stored public half",
            signatureVerifiesAgainstStoredPublic(seed, container.embeddedPublic),
        )
    }
}
