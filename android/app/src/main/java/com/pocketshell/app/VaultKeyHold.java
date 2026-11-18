package com.pocketshell.app;

import com.getcapacitor.JSObject;
import java.io.IOException;

/**
 * The hold-a-vault-key decision behind {@code SshKeyVault.holdVaultKey}, split
 * out so the cache path is testable without a bridge (#3141): an identical,
 * already-bound hold is answered from the held handle with no key parse and no
 * account-subject read; anything else is a cold hold that parses, verifies the
 * signed-in account, and binds.
 *
 * <p>A cache hit returns the SAME handle to a second caller, so releasing it
 * releases it for both. Today the JS vault serializes holds and releases per
 * key (one handle per keyId in androidVault.ts), so no live path releases a
 * shared handle while another connect uses it; a caller that starts sharing
 * handles must refcount first.
 */
final class VaultKeyHold {
    interface AccountSubject { String subject() throws Exception; }

    static final class Result {
        final EphemeralCredentials.Held held;
        /** "cache-hit" or "cold" - logged with its duration. */
        final String path;
        Result(EphemeralCredentials.Held held, String path) { this.held = held; this.path = path; }
    }

    private VaultKeyHold() {}

    static Result hold(EphemeralCredentials store, JSObject request, byte[] bytes, AccountSubject account) throws Exception {
        boolean bound = request.has("accountId");
        if (bound) {
            EphemeralCredentials.Held cached = store.findHeld(request.getString("accountId"), request.getString("vaultId"),
                request.getLong("epoch"), request.getString("keyId"), bytes);
            if (cached != null) return new Result(cached, "cache-hit");
        }
        EphemeralCredentials.Held held = store.hold(bytes);
        if (bound) {
            try {
                String accountId = request.getString("accountId");
                if (!("google:" + account.subject()).equals(accountId)) throw new IOException("The signed-in account changed.");
                store.bind(held.handleId, accountId, request.getString("vaultId"), request.getLong("epoch"), request.getString("keyId"));
                if (!("google:" + account.subject()).equals(accountId)) throw new IOException("The signed-in account changed.");
            } catch (Exception error) {
                store.release(held.handleId);
                throw error;
            }
        }
        return new Result(held, "cold");
    }
}
