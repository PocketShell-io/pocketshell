package com.pocketshell.next.usage

import kotlinx.coroutines.runBlocking
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives [UsageFetcher] against [TestUsageStack] — a real in-memory Room
 * database and the real [com.pocketshell.next.connect.ConnectionsRegistry],
 * with only the sshj dial swapped for a fake. Everything from "which hosts
 * are connected" to "did the NDJSON parse" is production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UsageFetcherTest {

    private val stack = TestUsageStack()

    @After
    fun tearDown() {
        stack.close()
    }

    @Test
    fun `a host with no live connection is not asked and does not count as connected`() = runBlocking {
        stack.seedHost()

        val result = stack.fetcher.fetchAll()

        assertEquals(0, result.connectedHostCount)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `a connected host's records land as a Records snapshot`() = runBlocking {
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        assertEquals(1, result.connectedHostCount)
        val snapshot = result.snapshots.getValue(hostId)
        check(snapshot is UsageSnapshot.Records) { "expected Records, got $snapshot" }
        assertEquals(listOf("claude"), snapshot.records.map { it.provider })
    }

    @Test
    fun `exit 127 is tool-missing, not a failure`() = runBlocking {
        val hostId = stack.seedHost()
        stack.scriptUsage(stdout = "", exitCode = 127, stderr = "sh: pocketshell: not found")
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        val snapshot = result.snapshots.getValue(hostId)
        assertTrue("expected ToolMissing, got $snapshot", snapshot is UsageSnapshot.ToolMissing)
    }

    @Test
    fun `a response that does not parse is a Failed snapshot, not a crash`() = runBlocking {
        val hostId = stack.seedHost()
        stack.scriptUsage("not usage json at all")
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        val snapshot = result.snapshots.getValue(hostId)
        assertTrue("expected Failed, got $snapshot", snapshot is UsageSnapshot.Failed)
    }

    @Test
    fun `a timed-out usage read is not reported as a parse failure`() = runBlocking {
        // #2498: a wall-clock overrun reaches the fetcher as
        // ExecResult(timedOut=true) — "the provider path was slow or
        // unreachable" — which must be distinguishable from "the response
        // format changed" (a parse failure). RED on base: it collapsed into
        // the same Failed state (reason "usage command exited -1").
        val hostId = stack.seedHost()
        stack.scriptUsageTimedOut()
        stack.connect(hostId)

        val result = stack.fetcher.fetchAll()

        val snapshot = result.snapshots.getValue(hostId)
        assertTrue(
            "a timeout must be its own state, got $snapshot",
            snapshot is UsageSnapshot.TimedOut,
        )
    }

    @Test
    fun `a timed-out read is never mistaken for records, even when the partial output parses`() =
        runBlocking {
            // #2498: the exec returned before the host finished, so whatever
            // stdout was drained is a TRUNCATED read. Parsing it into records
            // would paint a stale partial answer as a successful read.
            // RED on base: the parseable partial output landed as Records.
            val hostId = stack.seedHost()
            stack.scriptUsageTimedOut(CLAUDE_NDJSON)
            stack.connect(hostId)

            val result = stack.fetcher.fetchAll()

            val snapshot = result.snapshots.getValue(hostId)
            assertTrue(
                "a timed-out exec is a slow provider, not a successful read, got $snapshot",
                snapshot is UsageSnapshot.TimedOut,
            )
        }

    @Test
    fun `a timed-out host folds into failedHosts with a distinct reason`() {
        // #2498: the screen state must be able to say "slow/unreachable" —
        // and a timed-out host must NOT silently vanish from every bucket,
        // which would render the panel as "hosts answered, no providers".
        val state = usageScreenState(
            snapshots = listOf(
                UsageSnapshot.TimedOut(1L, "box", Instant.EPOCH),
            ),
            connectedHostCount = 1,
        )

        assertEquals(listOf("usage read timed out"), state.failedHosts.map { it.reason })
        assertEquals(emptyList<UsageMissingToolHost>(), state.missingToolHosts)
    }

    @Test
    fun `every connected host is asked, independently`() = runBlocking {
        val hostA = stack.seedHost("a")
        val hostB = stack.seedHost("b")
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(hostA)
        stack.connect(hostB)

        val result = stack.fetcher.fetchAll()

        assertEquals(2, result.connectedHostCount)
        assertEquals(setOf(hostA, hostB), result.snapshots.keys)
    }

    @Test
    fun `host scoped fetch does not include another connected host`() = runBlocking {
        val selected = stack.seedHost("selected")
        val other = stack.seedHost("other")
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(selected)
        stack.connect(other)

        val result = stack.fetcher.fetchHost(selected)

        assertEquals(1, result.connectedHostCount)
        assertEquals("selected", result.selectedHostName)
        assertEquals(setOf(selected), result.snapshots.keys)
    }

    private companion object {
        const val CLAUDE_NDJSON =
            "{\"provider\":\"claude\",\"status\":\"ok\"," +
                "\"windows\":{\"5h\":{\"percent_remaining\":80.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}"
    }
}
