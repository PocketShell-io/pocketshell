package com.pocketshell.next.render

import com.pocketshell.testsupport.LeakGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Issue #2724: the record-mode leak-attribution contract.
 *
 * Running the whole app2 suite under Roborazzi record mode composes screens
 * that a plain `:app2:testDebugUnitTest` run never composes (`captureRoboImage`
 * early-returns before touching the content unless a roborazzi task type is
 * set). A record-only composition that leaks an uncaught coroutine therefore
 * has no boundary of its own to fail: kotlinx-coroutines-test stores the
 * exception process-globally and the NEXT `runTest` — whichever class runs
 * next — dies with `UncaughtExceptionsBeforeTest`. That is the failure this
 * issue was filed for, and why the blame "kept moving".
 *
 * These tests reproduce that mechanism deterministically at reduced scale —
 * a probe coroutine on a real dispatcher whose failure surfaces while no test
 * scope is active (the #2647/#2707 pump shape), i.e. exactly what a record
 * capture's stray `LaunchedEffect`/scope produces — and pin the fix: the
 * `*Renders*` classes adopt [LeakGuard], so a leak is replayed at the
 * capturing class's OWN boundary instead of landing on whichever class runs
 * next.
 *
 * JVM-only, no Robolectric: the collector is process-global, so the geometry
 * of stored-then-replayed exceptions does not depend on how the leak was
 * produced. The library's replay failure type is `internal`, so the symptom
 * is asserted through its public supertype and message text instead.
 */
class LeakGuardAttributionTest {

    // ------------------------------------------------------------------
    // The reported symptom (#2724), at reduced scale.
    // ------------------------------------------------------------------

    @Test
    fun unguardedLeakReplaysIntoTheNextRunTestAsUncaughtExceptionsBeforeTest() {
        leakProbeAfter(delayMs = 50)
        // The probe failure surfaces while NO test scope is active, so the
        // collector stores it; the next `runTest` anywhere in the JVM — in the
        // issue, whichever class ran next — replays it at startup. This is the
        // exact `UncaughtExceptionsBeforeTest` the unfiltered record run died
        // with, with the leaker's own frames attached as suppressed causes.
        val thrown = assertThrows(IllegalStateException::class.java) {
            runTest { /* the innocent next class's first test body */ }
        }
        assertTrue(
            "expected the stored-leak replay message, got: $thrown",
            thrown.message.orEmpty().contains(STORED_LEAK_MESSAGE_FRAGMENT),
        )
        assertTrue(
            "the stored exception should carry the leaker's own trace: $thrown",
            thrown.carriesProbe(),
        )
    }

    // ------------------------------------------------------------------
    // The fix: a class-guard boundary pins the leak to THIS class.
    // ------------------------------------------------------------------

    @Test
    fun classGuardPinsAStoredLeakToTheRenderClassThatLeakedIt() {
        leakProbeAfter(delayMs = 50)

        val statement = renderGuard().apply(emptyStatement(), Description.createSuiteDescription(FakeRenderHost::class.java))
        val thrown = assertThrows(AssertionError::class.java) { statement.evaluate() }
        assertTrue(
            "the class-boundary failure should name the leaking class: $thrown",
            thrown.message.orEmpty().contains("FakeRenderHost"),
        )
        assertTrue(
            "the class-boundary failure should carry the leaker's own trace: ${thrown.describeChain()}",
            thrown.carriesProbe() ||
                thrown.describeChain().contains(STORED_LEAK_MESSAGE_FRAGMENT),
        )
    }

    /**
     * Layer 2/4: the per-test boundary replays anything stored since the
     * previous boundary into THIS test, deterministically and immediately —
     * the property that keeps at most one further test red, with provenance.
     */
    @Test
    fun perTestGuardReplaysAStoredLeakIntoItsOwnTest() {
        leakProbeAfter(delayMs = 50)

        val statement = perTestGuard().apply(emptyStatement(), Description.createSuiteDescription(javaClass))
        val thrown = assertThrows(IllegalStateException::class.java) { statement.evaluate() }
        assertTrue(
            "expected the stored-leak replay message, got: $thrown",
            thrown.message.orEmpty().contains(STORED_LEAK_MESSAGE_FRAGMENT),
        )
        assertTrue(
            "the replayed exception should carry the leaker's own trace: $thrown",
            thrown.carriesProbe(),
        )
    }

    // ------------------------------------------------------------------

    /**
     * The record-capture-shaped leak: a fire-and-forget coroutine on a real
     * dispatcher whose failure surfaces AFTER the launching test's scope is
     * gone. The wait after the launch makes storage deterministic: the probe
     * fires (and is stored — nothing in this JVM holds a test scope while we
     * wait) before this test proceeds to the boundary being exercised.
     */
    private fun leakProbeAfter(delayMs: Long) {
        // The collector is installed lazily by the first runTest in the JVM;
        // without this warm-up a probe fired before any runTest lands on the
        // thread's default uncaught handler instead of being stored.
        runTest { /* warm-up */ }
        CoroutineScope(Dispatchers.Default).launch {
            delay(delayMs)
            throw RuntimeException(PROBE_MESSAGE)
        }
        runBlocking { delay(delayMs + 500) }
    }

    private fun renderGuard(): TestRule = LeakGuard.classGuard()

    private fun perTestGuard(): TestRule = LeakGuard()

    /**
     * Class + message of every node reachable via cause and suppressed links —
     * the diagnostic a provenance assertion needs when it fails.
     */
    private fun Throwable.describeChain(): String =
        generateSequence(this) { it.cause }.joinToString(" <- ") { t ->
            t::class.simpleName + ": " + t.message.orEmpty().take(140) +
                t.suppressed.joinToString(
                    prefix = " [suppressed: ", postfix = "]",
                    transform = { s -> s.describeChain() },
                )
        }

    /** True if this throwable, any cause, or any suppressed is the probe's own failure. */
    private fun Throwable.carriesProbe(): Boolean =
        generateSequence(this) { it.cause }.any { it.message.orEmpty().contains(PROBE_MESSAGE) } ||
            suppressed.any { it.carriesProbe() }

    private fun emptyStatement(): Statement = object : Statement() {
        override fun evaluate() = Unit
    }

    /** Stand-in for a `*Renders*` class name in class-boundary failure messages. */
    class FakeRenderHost

    private companion object {
        const val PROBE_MESSAGE = "i2724 render-class probe leak"

        /**
         * kotlinx-coroutines-test 1.10.2's stored-leak replay failure message;
         * the failure type itself is `internal`, so the message text is the
         * stable public contract (it is what the #2635 record run printed).
         */
        const val STORED_LEAK_MESSAGE_FRAGMENT = "uncaught exceptions before the test started"
    }
}
