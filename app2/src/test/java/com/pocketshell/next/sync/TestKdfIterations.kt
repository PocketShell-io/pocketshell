package com.pocketshell.next.sync

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs every test in the class with [SyncCrypto]'s KDF at [iterations] rounds
 * instead of the production 600k (issue #2778).
 *
 * The sync tests derive keys hundreds of times through the real envelope code,
 * and 600k rounds per derivation is what ground the JVM gate to a 45-minute
 * halt under memory pressure. The [SyncCrypto.kdfIterationsOverride] is set
 * for the whole statement and restored in `finally`, so no test can leak the
 * fast parameters into a later class. Exactly one canary in `SyncCryptoTest`
 * clears the override inside its own body and pins the real 600k parameters.
 *
 * Pair with `@ConscryptMode(ConscryptMode.Mode.OFF)` on the same classes: it
 * keeps `HmacSHA256` on SunJCE instead of Robolectric's Conscrypt, whose
 * per-`doFinal` native HMAC contexts were the other half of that halt.
 */
class TestKdfIterations(
    private val iterations: Int = DEFAULT_ITERATIONS,
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                SyncCrypto.kdfIterationsOverride = iterations
                try {
                    base.evaluate()
                } finally {
                    SyncCrypto.kdfIterationsOverride = null
                }
            }
        }

    companion object {
        /** Enough rounds to stay a real PBKDF2 loop, few enough to be instant. */
        const val DEFAULT_ITERATIONS: Int = 1_000
    }
}
