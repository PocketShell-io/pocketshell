package com.pocketshell.app;

import static org.junit.Assert.*;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import java.io.IOException;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Native-only directory credentials, fixed destination and account-switch boundaries. */
public final class GatewayDirectoryClientTest {
    private static final String EMAIL = "orin@example.test";
    private static final String ORIGIN = "wss://gateway.pocketshell.io";
    private static final String ROUTING = "header.scopedRouting.signature";
    private static final String GOOGLE = "header.googleIdentity.signature";
    private static final long NOW = 1_800_000_000_000L;

    private static final class Account extends GatewaySyncSession {
        String currentSubject = "private-subject";
        String currentEmail = EMAIL;
        int exchanges;
        Runnable duringExchange = () -> {};

        Account() { super((GoogleSyncSession) null); }
        @Override String subject() { return currentSubject; }
        @Override String email() { return currentEmail; }
        @Override GoogleSyncSession.GatewayBrokerExchange exchange() {
            exchanges++;
            duringExchange.run();
            return new GoogleSyncSession.GatewayBrokerExchange(new GoogleSyncSession.Response(200,
                    "{\"token\":\"" + ROUTING + "\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                            + "\"expires_at\":" + (NOW / 1000 + 300) + "}"), GOOGLE);
        }
    }

    private static final class Network implements GoogleSyncSession.Transport {
        int requests;
        int status = 200;
        String response = "{\"devices\":[]}";
        String url;
        Map<String, String> headers;
        Runnable duringRequest = () -> {};
        boolean fail;

        @Override public GoogleSyncSession.Response send(String method, String url,
                Map<String, String> headers, String body) throws IOException {
            requests++;
            assertEquals("GET", method);
            assertNull(body);
            this.url = url;
            this.headers = headers;
            duringRequest.run();
            if (fail) throw new IOException("unsafe bearer " + ROUTING);
            return new GoogleSyncSession.Response(status, response);
        }
    }

    private static GatewayDirectoryClient client(Account account, Network network, String origin) {
        return new GatewayDirectoryClient(account, new GatewayRoutingTokens(() -> NOW), network,
                GatewayOriginAllowlist.of(""), origin);
    }

    @Test public void bridgeMethodsExistAndServerOriginComesOnlyFromTheBuild() throws Exception {
        assertNotNull(GatewayPairingPlugin.class.getMethod("devices", PluginCall.class)
                .getAnnotation(PluginMethod.class));
        assertNotNull(GatewayPairingPlugin.class.getMethod("serverUrl", PluginCall.class)
                .getAnnotation(PluginMethod.class));
        assertEquals(ORIGIN, GatewayDirectoryClient.serverUrlForBuild());
    }

    @Test public void scopedCredentialStaysNativeAndOnlyPublicRowsCrossTheBoundary() throws Exception {
        Account account = new Account();
        Network network = new Network();
        network.response = "{\"devices\":[{\"id\":\"phone-host\",\"revoked\":false,"
                + "\"account_id\":\"private-subject\",\"public_key\":\"device-identity-key\","
                + "\"ssh_host_key\":\"" + GatewayPairingStoreTest.ED25519_LINE + "\","
                + "\"unknown\":\"" + GOOGLE + "\",\"presence\":{\"online\":true,"
                + "\"observed_at\":\"2026-11-05T14:00:00.000Z\","
                + "\"connected_since\":\"2026-11-05T13:00:00.000Z\",\"unknown\":\"secret\"}}]}";
        JSONArray rows = client(account, network, ORIGIN).devices(EMAIL);
        assertEquals("https://gateway.pocketshell.io/identity/v1/devices", network.url);
        assertEquals("Bearer " + ROUTING, network.headers.get("Authorization"));
        assertEquals(1, account.exchanges);
        assertEquals(1, network.requests);
        JSONObject row = rows.getJSONObject(0);
        assertEquals("phone-host", row.getString("id"));
        assertTrue(row.getJSONObject("presence").getBoolean("online"));
        assertEquals(GatewayPairingStoreTest.ED25519_LINE, row.getString("ssh_host_key"));
        for (String forbidden : new String[] {"account_id", "private-subject", "public_key", "device-identity-key",
                "unknown", "secret", GOOGLE, ROUTING}) assertFalse(rows.toString().contains(forbidden));
    }

    @Test public void wrongOriginAndStaleAccountRefuseBeforeMintingOrSendingAnything() throws Exception {
        for (String origin : new String[] {"wss://gateway.pocketshell.io.attacker.test", "ws://gateway.pocketshell.io",
                "https://attacker.test", "wss://gateway.pocketshell.io/other", "wss://gateway.pocketshell.io:444"}) {
            Account account = new Account();
            Network network = new Network();
            try { client(account, network, origin).devices(EMAIL); fail("origin must refuse"); }
            catch (GatewayDirectoryClient.DirectoryException expected) {
                assertEquals(GatewayOriginAllowlist.NOT_ALLOWED, expected.code);
            }
            assertEquals(0, account.exchanges);
            assertEquals(0, network.requests);
        }
        Account account = new Account();
        Network network = new Network();
        try { client(account, network, ORIGIN).devices("another@example.test"); fail("stale account must refuse"); }
        catch (GatewayDirectoryClient.DirectoryException expected) { assertEquals("GATEWAY_ACCOUNT_CHANGED", expected.code); }
        assertEquals(0, account.exchanges);
        assertEquals(0, network.requests);
    }

    @Test public void accountSwitchDuringMintOrFetchNeverDisclosesThePreviousAccountsList() throws Exception {
        Account account = new Account();
        Network network = new Network();
        account.duringExchange = () -> account.currentSubject = "changed-subject";
        try { client(account, network, ORIGIN).devices(EMAIL); fail("mint race must refuse"); }
        catch (GatewayTokenBroker.GatewayBrokerException expected) { assertEquals("GATEWAY_ACCOUNT_CHANGED", expected.code); }
        assertEquals(0, network.requests);

        Account fetchedAccount = new Account();
        Network fetchedNetwork = new Network();
        fetchedNetwork.duringRequest = () -> fetchedAccount.currentSubject = "changed-subject";
        try { client(fetchedAccount, fetchedNetwork, ORIGIN).devices(EMAIL); fail("fetch race must refuse"); }
        catch (GatewayTokenBroker.GatewayBrokerException expected) { assertEquals("GATEWAY_ACCOUNT_CHANGED", expected.code); }
        assertEquals(1, fetchedNetwork.requests);
    }

    @Test public void httpFailuresAndNetworkErrorsNeverEchoCredentialBearingBodies() throws Exception {
        for (int status : new int[] {302, 401, 403, 500}) {
            Account account = new Account();
            Network network = new Network();
            network.status = status;
            network.response = ROUTING + GOOGLE;
            try { client(account, network, ORIGIN).devices(EMAIL); fail("HTTP status must refuse"); }
            catch (GatewayDirectoryClient.DirectoryException expected) {
                assertFalse(expected.getMessage().contains(ROUTING));
                assertFalse(expected.getMessage().contains(GOOGLE));
            }
            assertEquals("no redirect/retry at the directory layer", 1, network.requests);
        }
        Network network = new Network();
        network.fail = true;
        try { client(new Account(), network, ORIGIN).devices(EMAIL); fail("network error must refuse"); }
        catch (GatewayDirectoryClient.DirectoryException expected) {
            assertEquals("GATEWAY_DIRECTORY_UNAVAILABLE", expected.code);
            assertFalse(expected.getMessage().contains(ROUTING));
        }
    }

    @Test public void malformedOversizedDuplicateAndCredentialEchoResponsesRefuse() throws Exception {
        JSONArray excessive = new JSONArray();
        for (int i = 0; i <= GatewayDirectoryClient.MAX_DEVICES; i++) {
            excessive.put(new JSONObject().put("id", "host-" + i).put("revoked", false));
        }
        for (String body : new String[] {"[]", "{}", "{\"devices\":[]} trailing",
                "{\"devices\":[{\"id\":\"host-1\",\"revoked\":\"false\"}]}",
                "{\"devices\":[{\"id\":\"host-1\",\"revoked\":false},{\"id\":\"host-1\",\"revoked\":false}]}",
                new JSONObject().put("devices", excessive).toString(),
                new JSONObject().put("devices", new JSONArray()).put("padding", "\u20ac".repeat(25_000)).toString(),
                new JSONObject().put("devices", new JSONArray()).put("token", ROUTING).toString()}) {
            Network network = new Network();
            network.response = body;
            try { client(new Account(), network, ORIGIN).devices(EMAIL); fail("bad body must refuse"); }
            catch (GatewayDirectoryClient.DirectoryException expected) {
                assertEquals("GATEWAY_DIRECTORY_BAD_RESPONSE", expected.code);
                assertFalse(expected.getMessage().contains(body));
            }
        }
    }
}
