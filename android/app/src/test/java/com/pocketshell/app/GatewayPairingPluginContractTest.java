package com.pocketshell.app;

import static org.junit.Assert.*;
import com.getcapacitor.JSObject;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

/**
 * #3086: the GatewayPairing bridge surface. A pairing write is bound to the
 * account EMAIL the user saw (already visible to JS through GoogleSync
 * status), never to an account subject JS would have to be told; the reply
 * carries public pairing data only — no account subject, no token.
 */
public final class GatewayPairingPluginContractTest {
    private static final String HANDLE = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String URL = "wss://gateway.pocketshell.io";
    private static final String PIN = "SHA256:" + "A".repeat(43);

    private static final class Memory implements GatewayPairingStore.Repository {
        String value;
        public String read() { return value; }
        public void write(String json) { value = json; }
    }

    /** The signed-in account as the plugin sees it: subject + email, switchable. */
    static final class Account extends GatewaySyncSession {
        final AtomicReference<String[]> current;

        Account(String subject, String email) {
            super((GoogleSyncSession) null);
            current = new AtomicReference<>(new String[] {subject, email});
        }

        void switchTo(String subject, String email) {
            current.set(new String[] {subject, email});
        }

        @Override String subject() { return current.get()[0]; }

        @Override String email() { return current.get()[1]; }
    }

    private static Set<String> keys(JSONObject json) {
        Set<String> keys = new java.util.HashSet<>();
        json.keys().forEachRemaining(keys::add);
        return keys;
    }

    @Test public void theReplyIsPublicPairingDataWithoutTheAccountSubject() throws Exception {
        GatewayPairingStore store = new GatewayPairingStore(new Memory(), () -> 1000L);
        GatewayPairingStore.Pairing pairing = GatewayPairingPlugin.pairForAccount(store,
                new Account("sub-1", "me@example.com"), key -> true, "me@example.com", URL, "host-1", PIN, HANDLE);
        JSObject reply = GatewayPairingPlugin.pairReply("request-1", pairing);
        assertEquals(Set.of("requestId", "pairing"), keys(reply));
        JSONObject row = reply.getJSONObject("pairing");
        assertEquals(Set.of("serverUrl", "deviceId", "fingerprintSha256", "pinKind", "keyHandleId", "pairedAtEpochMs"),
                keys(row));
        assertEquals("fingerprint", row.getString("pinKind"));
        assertFalse("the account subject never crosses the bridge", reply.toString().contains("sub-1"));
    }

    @Test public void aHostKeyLinePairingReportsItsKeyTypeAndFingerprint() throws Exception {
        GatewayPairingStore store = new GatewayPairingStore(new Memory(), () -> 1000L);
        GatewayPairingStore.Pairing pairing = GatewayPairingPlugin.pairForAccount(store,
                new Account("sub-1", "me@example.com"), key -> true, "me@example.com", URL, "host-1",
                GatewayPairingStoreTest.ED25519_LINE, HANDLE);
        JSONObject row = GatewayPairingPlugin.pairReply("request-1", pairing).getJSONObject("pairing");
        assertEquals("host-key", row.getString("pinKind"));
        assertEquals("ssh-ed25519", row.getString("hostKeyType"));
        assertEquals(GatewayPairingStoreTest.ED25519_FINGERPRINT, row.getString("fingerprintSha256"));
    }

    @Test public void accountSwitchBeforeNativeExecutionNeverPersistsUnderTheNewAccount() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        try {
            GatewayPairingPlugin.pairForAccount(store, new Account("sub-2", "other@example.com"), key -> true,
                    "me@example.com", URL, "host-1", PIN, HANDLE);
            fail("account switch must refuse");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
    }

    @Test public void accountSwitchDuringVaultLookupRefusesBeforePersistence() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        Account account = new Account("sub-1", "me@example.com");
        try {
            GatewayPairingPlugin.pairForAccount(store, account,
                    key -> { account.switchTo("sub-2", "other@example.com"); return true; },
                    "me@example.com", URL, "host-1", PIN, HANDLE);
            fail("account switch must refuse");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
    }

    @Test public void aSubjectChangeBehindTheSameEmailStillRefuses() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        Account account = new Account("sub-1", "me@example.com");
        try {
            GatewayPairingPlugin.pairForAccount(store, account,
                    key -> { account.switchTo("sub-9", "me@example.com"); return true; },
                    "me@example.com", URL, "host-1", PIN, HANDLE);
            fail("a different account must refuse even with a recycled email");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
    }

    @Test public void switchAtPersistenceCannotRedirectTheApprovedAccountNamespace() throws Exception {
        Account account = new Account("sub-1", "me@example.com");
        GatewayPairingStore.Repository repository = new GatewayPairingStore.Repository() {
            String value;
            public String read() { return value; }
            public void write(String json) { account.switchTo("sub-2", "other@example.com"); value = json; }
        };
        GatewayPairingStore store = new GatewayPairingStore(repository, () -> 1000L);
        GatewayPairingPlugin.pairForAccount(store, account, key -> true, "me@example.com", URL, "host-1", PIN, HANDLE);
        assertEquals(1, store.list("sub-1").size());
        assertTrue("the new account never receives the prior user's pairing", store.list("sub-2").isEmpty());
    }

    @Test public void missingExpectedAccountRefusesBeforeVaultAndPersistence() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        try {
            GatewayPairingPlugin.pairForAccount(store, new Account("sub-1", "me@example.com"),
                    key -> { fail("vault must not be read"); return true; }, null, URL, "host-1", PIN, HANDLE);
            fail("missing account must refuse");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
    }

    /** #3086 review B1: JS cannot store a pairing (and so later a dial) for a foreign gateway. */
    @Test public void aPairingForAForeignGatewayOriginIsRefusedBeforeTheAccountVaultOrStore() throws Exception {
        for (String origin : SshCapabilityPluginGatewayRefusalTest.FOREIGN_ORIGINS) {
            Memory memory = new Memory();
            GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
            try {
                GatewayPairingPlugin.pairForAccount(store, new Account("sub-1", "me@example.com"),
                        key -> { fail(origin + ": the vault must not be probed"); return true; },
                        "me@example.com", origin, "host-1", PIN, HANDLE);
                fail(origin + ": a foreign gateway origin must not be paired");
            } catch (IOException refused) {
                assertTrue(origin + ": refused for its origin: " + refused.getMessage(),
                        refused.getMessage().contains("not allowed"));
            }
            assertNull(origin + ": nothing was stored", memory.value);
        }
    }
}
