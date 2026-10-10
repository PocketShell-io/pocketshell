package com.pocketshell.app;

import static org.junit.Assert.*;
import com.getcapacitor.JSObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;

public final class GatewayPairingPluginContractTest {
    private static final String HANDLE = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String URL = "wss://gateway.example.io";
    private static final String PIN = "SHA256:" + "A".repeat(43);
    private static final class Memory implements GatewayPairingStore.Repository {
        String value;
        public String read() { return value; }
        public void write(String json) { value = json; }
    }

    private static java.util.Set<String> keys(JSONObject json) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        java.util.Iterator<String> iterator = json.keys();
        while (iterator.hasNext()) keys.add(iterator.next());
        return keys;
    }

    @Test public void actualNativeReplyMatchesTheSharedBridgeFixture() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        GatewayPairingStore.Pairing pairing = GatewayPairingPlugin.pairForAccount(store, () -> "sub-1", key -> true,
                "sub-1", URL, "host-1", PIN, HANDLE);
        JSObject reply = GatewayPairingPlugin.pairReply("request-1", pairing);
        File cursor = new File(System.getProperty("user.dir"));
        File fixture = null;
        while (cursor != null) {
            File candidate = new File(cursor, "tests/fixtures/gateway-pairing-reply.json");
            if (candidate.isFile()) { fixture = candidate; break; }
            cursor = cursor.getParentFile();
        }
        assertNotNull("real native and JS parser share one reply fixture", fixture);
        JSONObject expected = new JSONObject(new String(Files.readAllBytes(fixture.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(keys(expected), keys(reply));
        for (String key : keys(expected)) assertEquals(key, expected.get(key).toString(), reply.get(key).toString());
    }

    @Test public void accountSwitchBeforeNativeExecutionNeverPersistsUnderTheNewAccount() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        try {
            GatewayPairingPlugin.pairForAccount(store, () -> "sub-2", key -> true,
                    "sub-1", URL, "host-1", PIN, HANDLE);
            fail("account switch must refuse");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
        assertTrue(store.list("sub-1").isEmpty());
        assertTrue(store.list("sub-2").isEmpty());
    }

    @Test public void accountSwitchDuringVaultLookupRefusesBeforePersistence() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        AtomicReference<String> account = new AtomicReference<>("sub-1");
        try {
            GatewayPairingPlugin.pairForAccount(store, account::get, key -> { account.set("sub-2"); return true; },
                    "sub-1", URL, "host-1", PIN, HANDLE);
            fail("account switch must refuse");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
    }

    @Test public void switchAtPersistenceCannotRedirectTheApprovedAccountNamespace() throws Exception {
        AtomicReference<String> account = new AtomicReference<>("sub-1");
        GatewayPairingStore.Repository repository = new GatewayPairingStore.Repository() {
            String value;
            public String read() { return value; }
            public void write(String json) { account.set("sub-2"); value = json; }
        };
        GatewayPairingStore store = new GatewayPairingStore(repository, () -> 1000L);
        GatewayPairingPlugin.pairForAccount(store, account::get, key -> true,
                "sub-1", URL, "host-1", PIN, HANDLE);
        assertEquals("sub-2", account.get());
        assertEquals(1, store.list("sub-1").size());
        assertTrue("the new account never receives the prior user's pairing", store.list("sub-2").isEmpty());
    }

    @Test public void missingExpectedAccountRefusesBeforeVaultAndPersistence() throws Exception {
        Memory memory = new Memory();
        GatewayPairingStore store = new GatewayPairingStore(memory, () -> 1000L);
        try {
            GatewayPairingPlugin.pairForAccount(store, () -> "sub-1", key -> { fail("vault must not be read"); return true; },
                    null, URL, "host-1", PIN, HANDLE);
            fail("missing account must refuse");
        } catch (SyncAuthException expected) { assertEquals("GATEWAY_PAIRING_ACCOUNT_CHANGED", expected.code); }
        assertNull(memory.value);
    }
}
