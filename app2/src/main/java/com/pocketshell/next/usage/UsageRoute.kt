package com.pocketshell.next.usage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.usage.UsageProviderRecord
import com.pocketshell.core.usage.UsageResetCredit
import com.pocketshell.core.usage.UsageResetCredits
import com.pocketshell.core.usage.UsageStatus
import com.pocketshell.core.usage.UsageWindow

/**
 * Route-level entry point for `usage` (rewrite task P-5, journey J12).
 *
 * Fetch-on-view: `ON_START` triggers exactly one refresh pass, which is one
 * `pocketshell usage --json` exec per CONNECTED host. There is no poll loop, no
 * scheduler and no stale-while-revalidate tier — the pre-rewrite client's
 * `UsageScheduler` (564 lines of cadence, active-host tracking and lease
 * fan-out) is deliberately not ported. What is on screen is what the host said
 * when the panel was opened or when the user pulled Refresh.
 *
 * Stays in app2 (#2636 D10): [UsageScreen] and its pure state/format family
 * moved to the shared presentation module (`shared:ui-screens`), but this
 * route, the Hilt view model and the core → display mapping below are app
 * concerns — the module boundary keeps `com.pocketshell.core.usage` out of the
 * shared screens, exactly like the D3 release-check seam
 * (`SettingsRoute` + `settingsUpdateCheckState`). The file is named for the
 * route it holds (the D3 file-naming rule): a same-named `UsageScreen.kt` on
 * both sides of the seam would collide on the `UsageScreenKt` JVM facade.
 */
@Composable
fun UsageRoute(
    onBack: () -> Unit,
    selectedHostId: Long? = null,
    modifier: Modifier = Modifier,
    viewModel: UsageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh(selectedHostId) }
    UsageScreen(
        state = state,
        onBack = onBack,
        onRefresh = { viewModel.refresh(selectedHostId) },
        modifier = modifier,
    )
}

/**
 * Maps one core `UsageProviderRecord` onto the pure
 * [UsageProviderRecordDisplay] the shared panel paints (#2636 D10).
 *
 * This is the usage family's whole core → display seam: [UsageFetcher] runs it
 * once per parsed record, so no `com.pocketshell.core.usage` type ever crosses
 * into `shared:ui-screens`. The display values are pre-spelled from the core
 * record's own derivations — [UsageProviderRecord.displayName] and
 * [UsageWindow.percent] are copied, never re-implemented, so the panel cannot
 * drift from the parser — while the small threshold derivations are re-derived
 * on the mirror and pinned equal by `UsageDisplayMappingTest`.
 */
internal fun UsageProviderRecord.toDisplay(): UsageProviderRecordDisplay =
    UsageProviderRecordDisplay(
        provider = provider,
        status = status.toDisplay(),
        rawStatus = rawStatus,
        displayName = displayName,
        windows = windows.map { it.toDisplay() },
        blockReason = blockReason,
        lastError = lastError,
        resetCredits = resetCredits?.toDisplay(),
    )

/** The display reads only the derived percent; `used`/`limit`/`unit` stay core-side. */
internal fun UsageWindow.toDisplay(): UsageWindowDisplay =
    UsageWindowDisplay(
        name = name,
        percent = percent,
        resetAt = resetAt,
    )

internal fun UsageResetCredits.toDisplay(): UsageResetCreditsDisplay =
    UsageResetCreditsDisplay(
        availableCount = availableCount,
        credits = credits.map { it.toDisplay() },
        unavailable = unavailable,
    )

private fun UsageResetCredit.toDisplay(): UsageResetCreditDisplay =
    UsageResetCreditDisplay(
        title = title,
        expiresAt = expiresAt,
    )

/**
 * Exhaustive on purpose: a NEW core `UsageStatus` constant fails this compile
 * instead of silently rendering through a `valueOf` hole — the parser's
 * tolerance for unknown HOST strings is core's job, not the mirror's.
 */
internal fun UsageStatus.toDisplay(): UsageStatusDisplay = when (this) {
    UsageStatus.Ok -> UsageStatusDisplay.Ok
    UsageStatus.Warn -> UsageStatusDisplay.Warn
    UsageStatus.Blocked -> UsageStatusDisplay.Blocked
    UsageStatus.Error -> UsageStatusDisplay.Error
    UsageStatus.Unsupported -> UsageStatusDisplay.Unsupported
    UsageStatus.Unknown -> UsageStatusDisplay.Unknown
}
