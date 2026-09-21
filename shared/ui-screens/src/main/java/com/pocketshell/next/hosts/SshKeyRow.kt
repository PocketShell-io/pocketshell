package com.pocketshell.next.hosts

/**
 * One rendered SSH-key row — the picker row of [AddEditHostScreen] and the
 * list row of the shared [SshKeysScreen].
 *
 * Presentation-only by construction (#2636 C1/D1): the Room entity
 * (`SshKeyEntity`, core-storage) stops at app2's route/ViewModel boundary and
 * is projected onto this row type, so the screens never see the storage
 * schema.
 */
data class SshKeyRow(
    val id: Long,
    val name: String,
    val fingerprint: String,
    val hasPassphrase: Boolean = false,
    /** Complete authorized-keys line derived on demand; never a private PEM. */
    val publicKey: String? = null,
    val publicKeyLoading: Boolean = false,
    val publicKeyError: String? = null,
    /** Friendly algorithm label, populated when the public half is available. */
    val algorithm: String? = null,
    /** OpenSSH SHA-256 fingerprint, populated with the public half. */
    val publicFingerprint: String? = null,
    /** Configured hosts that would be removed by the key's cascade delete. */
    val dependentHostNames: List<String> = emptyList(),
)
