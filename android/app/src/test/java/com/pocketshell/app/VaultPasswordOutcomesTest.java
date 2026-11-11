package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.credentials.exceptions.CreateCredentialCancellationException;
import androidx.credentials.exceptions.CreateCredentialCustomException;
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException;
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialCustomException;
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException;
import androidx.credentials.exceptions.NoCredentialException;
import org.junit.Test;

/** Synthetic accounts only: the provider outcomes and per-account credential ids of the vault passphrase save. */
public final class VaultPasswordOutcomesTest {
    @Test public void credentialIdIsStableOpaqueAndPerAccount() {
        String a = VaultPasswordOutcomes.credentialId("https://accounts.google.com|synthetic-subject-1");
        assertEquals(a, VaultPasswordOutcomes.credentialId("https://accounts.google.com|synthetic-subject-1"));
        assertNotEquals(a, VaultPasswordOutcomes.credentialId("https://accounts.google.com|synthetic-subject-2"));
        assertTrue(a.matches("pocketshell-vault-[0-9a-f]{16}"));
        assertFalse(a.contains("synthetic"));
    }

    @Test(expected = IllegalArgumentException.class) public void blankAccountIsRefused() {
        VaultPasswordOutcomes.credentialId("  ");
    }

    @Test public void saveFailuresMapToTheSharedOutcomeWords() {
        assertEquals("cancelled", VaultPasswordOutcomes.saveFailure(new CreateCredentialCancellationException("x")));
        assertEquals("unavailable", VaultPasswordOutcomes.saveFailure(new CreateCredentialProviderConfigurationException("x")));
        assertEquals("unavailable", VaultPasswordOutcomes.saveFailure(new CreateCredentialNoCreateOptionException("x")));
        assertEquals("failed", VaultPasswordOutcomes.saveFailure(new CreateCredentialCustomException("t", "secret-provider-detail")));
    }

    @Test public void retrieveFailuresMapToTheSharedOutcomeWords() {
        assertEquals("cancelled", VaultPasswordOutcomes.retrieveFailure(new GetCredentialCancellationException("x")));
        assertEquals("none", VaultPasswordOutcomes.retrieveFailure(new NoCredentialException("x")));
        assertEquals("unavailable", VaultPasswordOutcomes.retrieveFailure(new GetCredentialProviderConfigurationException("x")));
        assertEquals("failed", VaultPasswordOutcomes.retrieveFailure(new GetCredentialCustomException("t", "x")));
    }

    @Test public void onlyTheCurrentAccountsCredentialCounts() {
        String mine = VaultPasswordOutcomes.credentialId("synthetic-a");
        assertTrue(VaultPasswordOutcomes.belongsTo(mine, "synthetic-a"));
        assertFalse(VaultPasswordOutcomes.belongsTo(VaultPasswordOutcomes.credentialId("synthetic-b"), "synthetic-a"));
        assertFalse(VaultPasswordOutcomes.belongsTo(null, "synthetic-a"));
        assertFalse(VaultPasswordOutcomes.belongsTo("someone@example.com", "synthetic-a"));
    }
}
