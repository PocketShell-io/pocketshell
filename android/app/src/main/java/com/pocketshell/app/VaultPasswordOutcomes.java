package com.pocketshell.app;

import androidx.credentials.exceptions.CreateCredentialCancellationException;
import androidx.credentials.exceptions.CreateCredentialException;
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException;
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.NoCredentialException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure rules for keeping the vault passphrase in the system password manager
 * (Google Password Manager) through Credential Manager. The outcome words are
 * the ones the shared vault UI speaks; none of them ever carries the
 * passphrase or the provider's raw message.
 */
final class VaultPasswordOutcomes {
    static final String SAVED = "saved";
    static final String CANCELLED = "cancelled";
    static final String UNAVAILABLE = "unavailable";
    static final String FAILED = "failed";
    static final String NONE = "none";
    static final String OTHER_ACCOUNT = "otherAccount";

    private static final String ID_PREFIX = "pocketshell-vault-";

    private VaultPasswordOutcomes() {
    }

    /**
     * The credential's user id: an opaque, stable hash of the verified account
     * id, so each account keeps its own saved passphrase (account switching)
     * and no email or Google subject is shown to or stored by the provider.
     */
    static String credentialId(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("A verified account is required.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(accountId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(ID_PREFIX);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String saveFailure(CreateCredentialException error) {
        if (error instanceof CreateCredentialCancellationException) return CANCELLED;
        if (error instanceof CreateCredentialProviderConfigurationException
                || error instanceof CreateCredentialNoCreateOptionException) {
            return UNAVAILABLE;
        }
        return FAILED;
    }

    static String retrieveFailure(GetCredentialException error) {
        if (error instanceof GetCredentialCancellationException) return CANCELLED;
        if (error instanceof NoCredentialException) return NONE;
        if (error instanceof GetCredentialProviderConfigurationException) return UNAVAILABLE;
        return FAILED;
    }

    /** A returned credential only counts when it is this account's; otherwise the caller reports otherAccount. */
    static boolean belongsTo(String returnedId, String accountId) {
        return returnedId != null && returnedId.equals(credentialId(accountId));
    }
}
