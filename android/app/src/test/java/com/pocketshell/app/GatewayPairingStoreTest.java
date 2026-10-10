package com.pocketshell.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;

/**
 * Issue #3060/#3086: the gateway pairing namespace — (account, canonical
 * gateway, device) — with an explicitly provisioned pin (the host-key line
 * or its SHA-256 fingerprint) and a key-handle
 * association. Sibling pins and keys can never be borrowed across the
 * namespace, and malformed data never passes for a pairing.
 */
public final class GatewayPairingStoreTest {
    private MemoryRepository repository;
    private GatewayPairingStore store;

    @Before public void setUp() throws IOException {
        repository = new MemoryRepository();
        store = new GatewayPairingStore(repository, () -> 1_000L);
    }

    @Test public void pairsWithACanonicalOriginAndFingerprint() throws IOException {
        GatewayPairingStore.Pairing pairing = store.pair(
                "sub-1", "https://gateway.example.io/", "host-1", "SHA256:" + "A".repeat(43), HANDLE);
        assertEquals("wss://gateway.example.io", pairing.serverUrl());
        assertEquals("host-1", pairing.deviceId());
        assertEquals("SHA256:" + "A".repeat(43), pairing.fingerprintSha256());
        assertEquals(HANDLE, pairing.keyHandleId());
        assertEquals(1_000L, pairing.pairedAtEpochMs());
    }

    @Test public void lookupsAreNamespacedByAccountGatewayAndDevice() throws IOException {
        String pin = "SHA256:" + "A".repeat(43);
        store.pair("sub-1", "wss://gateway.example.io", "host-1", pin, HANDLE);

        assertNotNull(store.lookup("sub-1", "wss://gateway.example.io", "host-1"));
        assertNull("another account cannot borrow the pin", store.lookup("sub-2", "wss://gateway.example.io", "host-1"));
        assertNull("another gateway origin cannot borrow the pin",
                store.lookup("sub-1", "wss://gateway.example.io:8443", "host-1"));
        assertNull("another device id cannot borrow the pin", store.lookup("sub-1", "wss://gateway.example.io", "host-2"));
        assertEquals(1, store.list("sub-1").size());
        assertEquals(0, store.list("sub-2").size());
    }

    @Test public void rePairingTheSameTripleReplacesFingerprintAndHandle() throws IOException {
        store.pair("sub-1", "wss://gateway.example.io", "host-1", "SHA256:" + "A".repeat(43), HANDLE);
        GatewayPairingStore.Pairing replaced = store.pair(
                "sub-1", "wss://gateway.example.io", "host-1", "SHA256:" + "B".repeat(43), HANDLE_OTHER);
        assertEquals("SHA256:" + "B".repeat(43), replaced.fingerprintSha256());
        assertEquals(HANDLE_OTHER, replaced.keyHandleId());
        assertEquals("a host key rotation is an explicit re-pair, never a second record",
                1, store.list("sub-1").size());
    }

    @Test public void malformedPairingDataRefuses() throws IOException {
        String okPin = "SHA256:" + "A".repeat(43);
        assertPairingRefused("sub-1", "wss://gateway.example.io/path", "host-1", okPin, HANDLE);
        assertPairingRefused("sub-1", "wss://user@host", "host-1", okPin, HANDLE);
        assertPairingRefused("sub-1", "wss://gateway.example.io", "no", okPin, HANDLE);
        assertPairingRefused("sub-1", "wss://gateway.example.io", "host-1", "md5:aa", HANDLE);
        assertPairingRefused("sub-1", "wss://gateway.example.io", "host-1", "SHA256:short", HANDLE);
        assertPairingRefused("sub-1", "wss://gateway.example.io", "host-1", okPin, "not-a-handle");
        assertPairingRefused("", "wss://gateway.example.io", "host-1", okPin, HANDLE);
        assertTrue("nothing was written by the refusals", repository.value == null);
    }

    private void assertPairingRefused(String subject, String url, String device, String pin, String handle) {
        try {
            store.pair(subject, url, device, pin, handle);
            fail("malformed pairing must refuse: " + url);
        } catch (GatewayPairingStore.InvalidPairingException expected) {
            assertNotNull(expected.getMessage());
        } catch (IOException failure) {
            throw new AssertionError("refused with the wrong error", failure);
        }
    }

    @Test public void removeDeletesOnlyTheExactTriple() throws IOException {
        store.pair("sub-1", "wss://gateway.example.io", "host-1", "SHA256:" + "A".repeat(43), HANDLE);
        store.pair("sub-1", "wss://gateway.example.io", "host-2", "SHA256:" + "B".repeat(43), HANDLE);
        assertTrue(store.remove("sub-1", "wss://gateway.example.io", "host-1"));
        assertFalse(store.remove("sub-1", "wss://gateway.example.io", "host-1"));
        assertFalse(store.remove("sub-2", "wss://gateway.example.io", "host-2"));
        assertEquals(1, store.list("sub-1").size());
    }

    @Test public void anUnreadableStoreRefusesInsteadOfReadingAsEmpty() {
        repository.value = "{not json";
        try {
            store.list("sub-1");
            fail("an unreadable store must refuse, not read as unpaired");
        } catch (IOException expected) {
            // and the broken content is left for the user to see, never wiped
            assertEquals("{not json", repository.value);
        }
    }

    @Test public void storedRowsRoundTripThroughTheRepository() throws IOException {
        String pin = "SHA256:" + "C".repeat(43);
        store.pair("sub-1", "wss://gateway.example.io", "host-1", pin, HANDLE);
        AtomicReference<String> persisted = new AtomicReference<>(repository.value);
        GatewayPairingStore reopened = new GatewayPairingStore(new GatewayPairingStore.Repository() {
            @Override public String read() { return persisted.get(); }
            @Override public void write(String json) { persisted.set(json); }
        }, () -> 2_000L);
        GatewayPairingStore.Pairing pairing = reopened.lookup("sub-1", "wss://gateway.example.io", "host-1");
        assertNotNull(pairing);
        assertEquals(pin, pairing.fingerprintSha256());
        assertEquals(1_000L, pairing.pairedAtEpochMs());
    }

    // --- the `gateway show --host-key` line (#3086) ---------------------------------

    /** A real `ssh-keygen -t ed25519` public key line and its `ssh-keygen -lf` fingerprint. */
    static final String ED25519_LINE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINsI+xMBCARP+feB9XlS5sazCADY9SpfgrwI+d4CNIW+";
    static final String ED25519_FINGERPRINT = "SHA256:0SaR+rP252ArUmO5WjbODysbWTHbHS8UYAzJYGGvzkM";
    static final String ECDSA_LINE = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBGIXCRaw/ZiT6WG7uAG6qZi6LVYt9KS7qB16Pix70Rh1PN9jdE6j2IwnMqtpNjn7B7kKxEbR+VIgkr2mrxQUSds=";

    @Test public void theHostKeyLinePinsTheExactKeyAndItsFingerprint() throws IOException {
        // A paste usually carries the trailing newline; surrounding
        // whitespace is the only thing trimmed.
        GatewayPairingStore.Pairing pairing = store.pair(
                "sub-1", "wss://gateway.example.io", "host-1", ED25519_LINE + "\n", HANDLE);
        assertEquals("ssh-ed25519", pairing.hostKeyType());
        assertEquals(ED25519_LINE.split(" ")[1], pairing.hostKeyB64());
        assertEquals("the fingerprint is ssh-keygen's, derived from the exact key",
                ED25519_FINGERPRINT, pairing.fingerprintSha256());
        GatewayPairingStore.Pairing reread = store.lookup("sub-1", "wss://gateway.example.io", "host-1");
        assertEquals(ED25519_LINE.split(" ")[1], reread.hostKeyB64());
        assertEquals("ecdsa-sha2-nistp256",
                store.pair("sub-1", "wss://gateway.example.io", "host-2", ECDSA_LINE, HANDLE).hostKeyType());
    }

    @Test public void malformedHostKeyLinesRefuse() {
        String blob = ED25519_LINE.split(" ")[1];
        String[] bad = {
            ED25519_LINE + " comment",                          // a trailing comment is refused, not dropped
            "@cert-authority " + ED25519_LINE,                  // known_hosts markers
            "host.example " + ED25519_LINE,                     // a known_hosts line, not the key line
            "ssh-ed25519  " + blob,                             // two spaces
            "ssh-ed25519\t" + blob,                             // a tab
            "ssh-dss " + blob,                                  // unsupported type
            "ecdsa-sha2-nistp256 " + blob,                      // blob names a different type
            "ssh-ed25519 " + blob.substring(0, blob.length() - 4), // truncated base64
            "ssh-ed25519 " + blob + "\n" + ED25519_LINE,        // a second line
            "ssh-ed25519 ",                                     // no key
        };
        for (String line : bad) {
            assertPairingRefused("sub-1", "wss://gateway.example.io", "host-1", line, HANDLE);
        }
        assertNull(GatewayTargetPolicy.parseHostKeyLine(null));
        assertTrue("nothing was written by the refusals", repository.value == null);
    }

    @Test public void aTamperedStoredHostKeyIsRefusedNotTrusted() throws IOException {
        store.pair("sub-1", "wss://gateway.example.io", "host-1", ED25519_LINE, HANDLE);
        // Swap the stored key for another one while leaving its fingerprint:
        // the row no longer agrees with itself and must not become a pin.
        repository.value = repository.value.replace(ED25519_LINE.split(" ")[1], ECDSA_LINE.split(" ")[1])
                .replace("\"ssh-ed25519\"", "\"ecdsa-sha2-nistp256\"");
        try {
            store.lookup("sub-1", "wss://gateway.example.io", "host-1");
            fail("a stored key that disagrees with its fingerprint must refuse");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    // --- remove never wildcards (#3086 review round 1, blocker 3) -------------------

    /** Counts every repository access, to prove a refused remove touched nothing. */
    static final class CountingRepository implements GatewayPairingStore.Repository {
        String value;
        int reads;
        int writes;

        @Override public String read() { reads++; return value; }

        @Override public void write(String json) { writes++; value = json; }
    }

    @Test public void reviewerProbeANullUrlRemoveDeletesNothing() throws IOException {
        // finding3-remove-wildcard-probe: two origins for (sub-1, host-1);
        // remove("sub-1", null, "host-1") returned true and 2 -> 0.
        store.pair("sub-1", "wss://a.example", "host-1", "SHA256:" + "A".repeat(43), HANDLE);
        store.pair("sub-1", "wss://b.example", "host-1", "SHA256:" + "B".repeat(43), HANDLE);
        assertEquals(2, store.list("sub-1").size());
        try {
            store.remove("sub-1", null, "host-1");
            fail("a remove without a gateway origin must refuse");
        } catch (GatewayPairingStore.InvalidPairingException expected) {
            assertNotNull(expected.getMessage());
        }
        assertEquals("nothing was removed", 2, store.list("sub-1").size());
    }

    @Test public void aMalformedRemoveTargetRefusesBeforeAnyStoreAccess() throws IOException {
        CountingRepository counting = new CountingRepository();
        GatewayPairingStore twoOrigins = new GatewayPairingStore(counting, () -> 1_000L);
        twoOrigins.pair("sub-1", "wss://a.example", "host-1", "SHA256:" + "A".repeat(43), HANDLE);
        twoOrigins.pair("sub-1", "wss://b.example", "host-1", "SHA256:" + "B".repeat(43), HANDLE);
        String[][] bad = {
            {null, "host-1"}, {"", "host-1"}, {"   ", "host-1"}, {"not a url", "host-1"},
            {"wss://a.example/path", "host-1"}, {"ftp://a.example", "host-1"}, {"wss://user@a.example", "host-1"},
            {"wss://a.example", null}, {"wss://a.example", ""}, {"wss://a.example", "no"},
        };
        for (String[] target : bad) {
            int reads = counting.reads;
            int writes = counting.writes;
            try {
                twoOrigins.remove("sub-1", target[0], target[1]);
                fail("remove must refuse " + java.util.Arrays.toString(target));
            } catch (GatewayPairingStore.InvalidPairingException expected) {
                assertNotNull(expected.getMessage());
            }
            assertEquals("no store read for " + java.util.Arrays.toString(target), reads, counting.reads);
            assertEquals("no store write for " + java.util.Arrays.toString(target), writes, counting.writes);
        }
        assertEquals(2, twoOrigins.list("sub-1").size());
    }

    @Test public void aValidRemoveDeletesOnlyTheExactCanonicalTarget() throws IOException {
        store.pair("sub-1", "wss://a.example", "host-1", "SHA256:" + "A".repeat(43), HANDLE);
        store.pair("sub-1", "wss://b.example", "host-1", "SHA256:" + "B".repeat(43), HANDLE);
        store.pair("sub-2", "wss://a.example", "host-1", "SHA256:" + "C".repeat(43), HANDLE);
        // A non-canonical spelling of origin a names exactly origin a.
        assertTrue(store.remove("sub-1", "https://A.example/", "host-1"));
        assertNull(store.lookup("sub-1", "wss://a.example", "host-1"));
        assertNotNull("the other origin is untouched", store.lookup("sub-1", "wss://b.example", "host-1"));
        assertNotNull("the other account is untouched", store.lookup("sub-2", "wss://a.example", "host-1"));
        assertFalse(store.remove("sub-1", "wss://a.example:8443", "host-1"));
        assertEquals(1, store.list("sub-1").size());
    }

    private static final String HANDLE = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String HANDLE_OTHER = "ffffffff-ffff-ffff-ffff-ffffffffffff";

    static final class MemoryRepository implements GatewayPairingStore.Repository {
        String value;

        @Override public String read() { return value; }

        @Override public void write(String json) { value = json; }
    }
}
