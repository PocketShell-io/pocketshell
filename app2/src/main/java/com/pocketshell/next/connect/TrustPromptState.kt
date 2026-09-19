package com.pocketshell.next.connect

import com.pocketshell.core.transport.TrustDecision

// The TrustPromptState data class and its display properties live in the
// shared presentation module (#2636 D8, `shared:ui-screens`, same package) —
// same FQCN, so every reference is unchanged. What stayed here is the one
// transport-facing piece: the `TrustDecision` mapping, because `core.transport`
// types cannot enter the presentation module. It is declared as a `Companion`
// extension, so `TrustPromptState.from(...)` call sites are unchanged too.

/**
 * Maps a [TrustDecision] to a prompt, or null for
 * [TrustDecision.Trusted] — an already-trusted key must never raise a
 * prompt, so "nothing to ask" is a first-class result rather than an
 * empty-string prompt.
 */
fun TrustPromptState.Companion.from(hostId: Long, decision: TrustDecision): TrustPromptState? =
    when (decision) {
        is TrustDecision.Trusted -> null

        is TrustDecision.Unknown -> TrustPromptState(
            hostId = hostId,
            fingerprintSha256 = decision.fingerprintSha256,
            isMismatch = false,
            previousFingerprintSha256 = null,
        )

        is TrustDecision.Mismatch -> TrustPromptState(
            hostId = hostId,
            fingerprintSha256 = decision.presentedSha256,
            isMismatch = true,
            previousFingerprintSha256 = decision.storedSha256,
        )
    }
