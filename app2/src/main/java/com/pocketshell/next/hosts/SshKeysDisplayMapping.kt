package com.pocketshell.next.hosts

/** App-side exhaustive adapter for the shared key-generation display seam. */
internal fun SshKeyGenerationDisplayRequest.toDomain(): SshKeyGenerationRequest =
    SshKeyGenerationRequest(
        name = name,
        type = type.toDomain(),
        protection = protection.toDomain(),
        passphrase = passphrase,
    )

internal fun SshKeyGenerationTypeDisplay.toDomain(): SshKeyGenerationType = when (this) {
    SshKeyGenerationTypeDisplay.ED25519 -> SshKeyGenerationType.ED25519
    SshKeyGenerationTypeDisplay.RSA -> SshKeyGenerationType.RSA
}

internal fun SshKeyProtectionDisplay.toDomain(): SshKeyProtection = when (this) {
    SshKeyProtectionDisplay.NONE -> SshKeyProtection.NONE
    SshKeyProtectionDisplay.PASSPHRASE -> SshKeyProtection.PASSPHRASE
}

/** App-side cryptographic validation behind the shared screen's callback. */
internal fun validateSshKeyImportPem(pem: String): String? = when {
    pem.isBlank() -> null
    !SshKeyMaterial.looksLikePrivateKey(pem) -> "Enter a complete private key."
    else -> runCatching { SshKeyMaterial.validatePrivateKey(pem) }
        .exceptionOrNull()
        ?.let { "This key could not be parsed on this device." }
}
