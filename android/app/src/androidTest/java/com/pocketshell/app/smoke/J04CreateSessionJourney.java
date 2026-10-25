package com.pocketshell.app.smoke;

import org.junit.Test;

/** Runs the complete key-vault, host authentication and session oracle. */
public class J04CreateSessionJourney extends SshKeyVaultDockerJourneyTest {
    @Test
    @Override
    public void importsEncryptedDocumentConnectsAndKeepsSecretsOutOfWebViewState() throws Exception {
        super.importsEncryptedDocumentConnectsAndKeepsSecretsOutOfWebViewState();
    }
}
