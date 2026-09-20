package com.pocketshell.next.usage

import java.time.Instant

/**
 * Pure display mirrors of the `core.usage` model family (#2636 D10).
 *
 * The usage panel's presentation layer ([UsageScreen], [UsageSnapshot],
 * [UsageScreenState], the format family) moved into this module, but the
 * boundary design forbids a `:shared:core-usage` dependency — this module's
 * only project dependency stays `:shared:ui-kit`. So the D3 release-check
 * seam applies at family scale: the six core-usage package types
 * the panel paints (`UsageProviderRecord`, `UsageWindow`, `UsageResetCredits`,
 * `UsageResetCredit`, `UsageStatus`, `UsageThresholdState`) get pure display
 * mirrors here, and app2 maps core → mirror at its one ingestion point (the
 * `toDisplay()` adapters next to `UsageRoute`), exactly like
 * `ReleaseInfo` → `ReleaseUpdateDisplay` (#2636 D3) and
 * `SyncOutcome` → `SyncOutcomeDisplay` (#2636 D7).
 *
 * Field-by-field decisions (the extraction's "decide per field" list):
 *
 *  - [UsageWindowDisplay] narrows to what the panel actually paints — `name`,
 *    `percent`, `resetAt`. The core `used`/`limit`/`unit` triple never crosses:
 *    the mapper reads the core record's own `percent` derivation, so the
 *    percent formula exists exactly once, in core.
 *  - [UsageProviderRecordDisplay.displayName] is a plain value PRE-SPELLED by
 *    the app-side mapper (it copies `core.displayName`), not a re-implemented
 *    provider-name translation table — a future provider alias added to core
 *    can never silently miss the mirror. Same pre-spelling rule D3 used for
 *    `ReleaseUpdateDisplay.publishedDateLabel` and D9 for
 *    `TransfersUiState.subtitle`.
 *  - The small threshold derivations ([UsageProviderRecordDisplay]
 *    `.mostConstrainedWindow` / `.isBlocked` / `.isNearLimit` /
 *    `.thresholdState(warnPercent)`) are re-derived here from the raw material
 *    the mirror carries (`status` + per-window `percent`) because callers pass
 *    the user-configurable `warnPercent` at render time. Their equivalence to
 *    the core derivations is pinned by app2's `UsageDisplayMappingTest` sweep,
 *    which is what lets the panel's PNGs be compared byte-for-byte across the
 *    move.
 *  - The threshold constants are copied verbatim; the same test asserts the
 *    mirror and core values stay equal.
 */
enum class UsageStatusDisplay {
    Ok,
    Warn,
    Blocked,
    Error,
    Unsupported,
    Unknown,
}

/**
 * Threshold-aware display state derived from a [UsageProviderRecordDisplay].
 * Mirrors core's `UsageThresholdState` one-to-one, ordered by severity so
 * callers can compare with `>=` for "at least amber-level".
 */
enum class UsageThresholdStateDisplay {
    Ok,
    Approaching,
    Critical,
    Exceeded,
    ;

    /** True when the state warrants an in-app warning surface. */
    val warrantsWarning: Boolean
        get() = this != Ok
}

/**
 * One usage window for a provider, narrowed to the display-read fields: the
 * producer's own window key (`5h`, `7d`, `weekly`, `monthly`), the derived
 * percent (pre-spelled by the app-side mapper), and the reset deadline.
 */
data class UsageWindowDisplay(
    val name: String,
    val percent: Double,
    val resetAt: Instant?,
)

/** One already-available Codex reset credit; expiry information only. */
data class UsageResetCreditDisplay(
    val title: String,
    val expiresAt: Instant?,
)

/**
 * Codex's supplementary reset-credit inventory, mirrored verbatim:
 * [availableCount] is authoritative when the fetch succeeded, including zero;
 * null only when [unavailable] is true.
 */
data class UsageResetCreditsDisplay(
    val availableCount: Int?,
    val credits: List<UsageResetCreditDisplay>,
    val unavailable: Boolean,
)

/**
 * One provider record as the usage panel paints it. The raw fields arrive
 * pre-mapped from core (see the module KDoc); the derived quota properties
 * re-derive from that raw material with core's formulas.
 */
data class UsageProviderRecordDisplay(
    val provider: String,
    val status: UsageStatusDisplay,
    val rawStatus: String,
    /** Pre-spelled by the app-side mapper from `core.displayName`. */
    val displayName: String,
    val windows: List<UsageWindowDisplay> = emptyList(),
    val blockReason: String? = null,
    val lastError: String? = null,
    val resetCredits: UsageResetCreditsDisplay? = null,
) {
    /** The window with the highest percent; ties keep the producer's order. */
    val mostConstrainedWindow: UsageWindowDisplay?
        get() = windows.maxByOrNull { it.percent }

    val isBlocked: Boolean
        get() = status == UsageStatusDisplay.Blocked || windows.any { it.percent >= EXCEEDED_PERCENT }

    val isNearLimit: Boolean
        get() = !isBlocked && windows.any { it.percent >= WARN_PERCENT }

    /**
     * Threshold-aware state for the in-app warning surfaces, derived with
     * core's bands: status-blocked maps straight to [UsageThresholdStateDisplay.Exceeded],
     * then the most-constrained window's percent is thresholded at 100% /
     * [CRITICAL_PERCENT] / [warnPercent]. Records with no windows resolve to
     * [UsageThresholdStateDisplay.Ok].
     */
    fun thresholdState(warnPercent: Double = DEFAULT_WARN_PERCENT): UsageThresholdStateDisplay {
        if (status == UsageStatusDisplay.Blocked) return UsageThresholdStateDisplay.Exceeded
        val worst = mostConstrainedWindow?.percent ?: return UsageThresholdStateDisplay.Ok
        return when {
            worst >= EXCEEDED_PERCENT -> UsageThresholdStateDisplay.Exceeded
            worst >= CRITICAL_PERCENT -> UsageThresholdStateDisplay.Critical
            worst >= warnPercent -> UsageThresholdStateDisplay.Approaching
            else -> UsageThresholdStateDisplay.Ok
        }
    }

    companion object {
        /** Provider status at or above this percent counts as near-limit. */
        const val WARN_PERCENT: Double = 85.0

        /** Default "approaching limit" threshold when the caller passes none. */
        const val DEFAULT_WARN_PERCENT: Double = 80.0

        /** Threshold above which a provider is classified as "critical". */
        const val CRITICAL_PERCENT: Double = 95.0

        /** Threshold above which a provider is hard-blocked / exceeded. */
        const val EXCEEDED_PERCENT: Double = 100.0
    }
}
