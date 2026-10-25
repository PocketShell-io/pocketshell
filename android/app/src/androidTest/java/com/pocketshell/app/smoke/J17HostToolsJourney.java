package com.pocketshell.app.smoke;

import org.junit.Test;

/** Runs the complete key-vault, host authentication and session oracle. */
public class J17HostToolsJourney extends SshKeyVaultDockerJourneyTest {
    @Test
    @Override
    public void pastesKeySharesPublicKeyAndInstallsGeneratedKeyOnHost() throws Exception {
        super.pastesKeySharesPublicKeyAndInstallsGeneratedKeyOnHost();
    }
}
