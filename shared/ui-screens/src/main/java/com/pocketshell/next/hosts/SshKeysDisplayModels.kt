package com.pocketshell.next.hosts

/** Everything the shared SSH-key screen paints (#2636 D13). */
data class SshKeysUiState(
    val keys: List<SshKeyRow> = emptyList(),
    val loaded: Boolean = false,
    val generating: Boolean = false,
    val message: String? = null,
)

/** Pure display mirror of app2's locally generated key types. */
enum class SshKeyGenerationTypeDisplay(
    val label: String,
    val description: String,
) {
    ED25519(
        label = "ED25519",
        description = "Modern default for new servers",
    ),
    RSA(
        label = "RSA 3072",
        description = "Broad compatibility with older servers",
    ),
}

/** Pure display mirror of app2's generated-key protection choices. */
enum class SshKeyProtectionDisplay(
    val label: String,
    val description: String,
) {
    NONE(
        label = "No passphrase",
        description = "The key can be used without an unlock prompt",
    ),
    PASSPHRASE(
        label = "Passphrase protected",
        description = "Ask for a passphrase before the key is used",
    ),
}

/**
 * Transient UI hand-off for key generation. The app-side adapter copies the
 * passphrase into the domain request; neither model persists it.
 */
class SshKeyGenerationDisplayRequest(
    val name: String,
    val type: SshKeyGenerationTypeDisplay = SshKeyGenerationTypeDisplay.ED25519,
    val protection: SshKeyProtectionDisplay = SshKeyProtectionDisplay.NONE,
    val passphrase: CharArray? = null,
)
