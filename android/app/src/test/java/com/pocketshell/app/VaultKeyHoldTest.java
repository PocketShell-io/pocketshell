package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.getcapacitor.JSObject;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** The plugin-side holdVaultKey decision: cold hold, then cache hit, with the invalidations (#3141). */
public final class VaultKeyHoldTest {
    private static final String VAULT = "A".repeat(22);
    private static final String KEY = "B".repeat(22);

    private static JSObject request() throws Exception {
        return new JSObject().put("accountId", "google:sub-1").put("vaultId", VAULT).put("epoch", 3L).put("keyId", KEY);
    }

    @Test
    public void coldThenCacheHitSharesTheHandleAndSkipsTheAccountSession() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        byte[] key = EphemeralCredentialsTest.throwawayKey();
        AtomicInteger subjectReads = new AtomicInteger();
        VaultKeyHold.AccountSubject account = () -> { subjectReads.incrementAndGet(); return "sub-1"; };

        VaultKeyHold.Result cold = VaultKeyHold.hold(store, request(), key, account);
        assertEquals("cold", cold.path);
        assertEquals("cold hold verifies the account before and after binding", 2, subjectReads.get());
        assertTrue(store.matches(cold.held.handleId, "google:sub-1", VAULT, 3L, KEY));

        VaultKeyHold.Result hit = VaultKeyHold.hold(store, request(), key, () -> { throw new AssertionError("cache hit must not read the account"); });
        assertEquals("cache-hit", hit.path);
        assertEquals(cold.held.handleId, hit.held.handleId);
        assertEquals("no second copy of the key", 1, store.size());
    }

    @Test
    public void signOutWipeMakesTheNextHoldColdAgain() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        byte[] key = EphemeralCredentialsTest.throwawayKey();
        VaultKeyHold.hold(store, request(), key, () -> "sub-1");
        store.wipeAll();
        assertEquals("cold", VaultKeyHold.hold(store, request(), key, () -> "sub-1").path);
    }

    @Test
    public void aChangedAccountOrKeyNeverHitsAndAFailedColdHoldLeavesNothing() throws Exception {
        EphemeralCredentials store = new EphemeralCredentials();
        byte[] key = EphemeralCredentialsTest.throwawayKey();
        VaultKeyHold.hold(store, request(), key, () -> "sub-1");
        assertEquals("a different key under the same identity is a cold hold", "cold",
            VaultKeyHold.hold(store, request(), EphemeralCredentialsTest.throwawayKey(), () -> "sub-1").path);
        EphemeralCredentials other = new EphemeralCredentials();
        try {
            VaultKeyHold.hold(other, request(), key, () -> "someone-else");
            fail("signed-in account differs from the requested one");
        } catch (IOException expected) { /* ok */ }
        assertEquals("a refused hold must not leave a key behind", 0, other.size());
        assertFalse(other.contains("anything"));
    }
}
