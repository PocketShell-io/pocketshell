package com.pocketshell.testsupport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Issue #2707: the suite-wide successor of #2647's per-class
 * `attributeLeaksToThisTest` sponge, closing the seam a per-class guard leaves:
 * a leaked coroutine that surfaces AFTER a class's last `@After` can still
 * reach the next `runTest` — possibly in an unrelated class.
 *
 * The mechanism being guarded against is unchanged (#2647's analysis, verified
 * against kotlinx-coroutines-test 1.10.2): the library installs a process-global
 * exception collector as the fallback `CoroutineExceptionHandler`. An uncaught
 * exception from a coroutine outside the running test's scope tree (a
 * fire-and-forget job on a real dispatcher — the `pumpScope` shape) is
 * attributed to whichever test scope is active at that moment, or STORED if
 * none is; the next `runTest` anywhere in the JVM replays stored exceptions at
 * startup and fails with `UncaughtExceptionsBeforeTest`. Without a boundary at
 * every test, the blame wanders the suite as ordering and composition change.
 *
 * The rule provides four layers of attribution, in the order a leak meets them:
 *
 * 1. A leak surfacing while a test's own `runTest` is still active already
 *    fails that test — no guard needed (existing library behaviour).
 * 2. TEST boundary, after every test: an empty `runTest` sponge (#2647's exact
 *    mechanism, now on every class) replays anything stored since the previous
 *    boundary — the test's own execution window plus its `@After` — into THIS
 *    test, failing it. An exception out of a rule statement marks the test red.
 * 3. CLASS boundary, after the class's last test: a grace sponge holds an
 *    active scope for [CLASS_BOUNDARY_GRACE_MS] of real time, so a leak that
 *    surfaces after the final `@After` — the seam #2647 left open — is
 *    attributed while this class's boundary is still open and fails the CLASS
 *    (JUnit records a class-rule failure), not the next class. The window is
 *    best-effort: a leak slower than the grace falls through to layer 4.
 * 4. TEST boundary, before every test: the same sponge replays anything that
 *    escaped the previous class's grace into the first boundary of the next
 *    class, deterministically and immediately — instead of an arbitrary later
 *    `runTest` — so at most ONE further test is affected and its failure trace
 *    still carries the leaker's own frames as provenance.
 *
 * JUnit cannot retroactively fail a test that already completed, so a leak
 * slower than the class grace physically cannot turn its own class red; layers
 * 3+4 pin it at the closest reachable boundary with the culprit's stack trace
 * attached. Everything else is pinned to the class that leaked it.
 *
 * Usage (both declarations are required):
 *
 * ```
 * companion object {
 *     @JvmStatic
 *     @get:ClassRule
 *     val leakGuardClass = LeakGuard.classGuard()
 * }
 *
 * @get:Rule
 * val leakGuard = LeakGuard()
 * ```
 *
 * The sponges replay-only: they never catch or swallow anything, and when no
 * leak is stored they complete immediately and cannot fail a green test except
 * by replaying a genuinely stored uncaught exception. junit and
 * kotlinx-coroutines-test are `compileOnly` here; every consumer source set
 * already carries them on its own test classpath (the #1048 convention).
 *
 * No-gradle-coincidence note: the rule is inert without the collector, so
 * plain (non-`runTest`) test classes need not adopt it.
 */
class LeakGuard private constructor(private val classBoundary: Boolean) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            if (classBoundary) {
                try {
                    base.evaluate()
                } finally {
                    graceAtClassBoundary(description)
                }
            } else {
                replayStoredLeaks()
                try {
                    base.evaluate()
                } finally {
                    replayStoredLeaks()
                }
            }
        }
    }

    /**
     * The #2647 sponge: opening one more [TestScope] here makes THIS boundary
     * the newest active scope, so the collector replays anything stored since
     * the last boundary into it, failing the current test (or, at a class
     * boundary's grace, the current class). Deliberately un-wrapped so the
     * reported failure stays the raw leak — its trace is the provenance.
     */
    private fun replayStoredLeaks() {
        runTest { /* the sponge: replays stored uncaught exceptions into THIS boundary */ }
    }

    /**
     * Layer 3: keep an active scope alive across the class's teardown gap so
     * late-surfacing leaks are pinned HERE. The grace runs on a real
     * dispatcher inside the test scope, so `runTest` genuinely waits out the
     * wall-clock window. A caught leak is re-thrown as an [AssertionError]
     * naming the leaking class — the only wrapper in this file, because a
     * class-rule failure has no single test whose trace could carry it.
     */
    private fun graceAtClassBoundary(description: Description) {
        try {
            runTest {
                withContext(Dispatchers.Default) { delay(CLASS_BOUNDARY_GRACE_MS) }
            }
        } catch (t: Throwable) {
            throw AssertionError(
                "LeakGuard (#2707): ${description.testClass.simpleName} leaked an uncaught " +
                    "coroutine that surfaced after its last @After; pinned at the class " +
                    "boundary. Original failure below.",
                t,
            )
        }
    }

    companion object {
        /**
         * How long the class boundary stays open for stragglers. Covers the
         * `delay(250)` probe shape the #2647 review verified; leaks slower
         * than this are caught by the next class's test-boundary sponge
         * (layer 4) instead.
         */
        const val CLASS_BOUNDARY_GRACE_MS = 250L

        /** The per-test boundary. Required on every `runTest`-based test class. */
        operator fun invoke(): LeakGuard = LeakGuard(classBoundary = false)

        /** The per-class boundary with the end-of-class grace. */
        fun classGuard(): LeakGuard = LeakGuard(classBoundary = true)
    }
}
