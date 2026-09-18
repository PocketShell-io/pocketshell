package com.pocketshell.next.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pocketshell.next.release.ReleaseCheckResult

/**
 * Route-level entry point for the categorized Quiet Settings index.
 *
 * Stays in app2 (#2636 D3): [SettingsScreen] and its pure state types moved to
 * the shared presentation module (`shared:ui-screens`), but this route and the
 * release-check adapter below are app concerns — the module boundary keeps
 * `com.pocketshell.next.release` service/result types out of the shared
 * screens.
 */
@Composable
fun SettingsRoute(
    navigation: SettingsNavigation,
    modifier: Modifier = Modifier,
) {
    SettingsScreen(navigation = navigation, modifier = modifier)
}

/**
 * Maps one update-check poll onto the settings UI state. This is the
 * release-check service/result adaptation the #2636 audit keeps outside the
 * UI module: [ReleaseCheckResult.UpdateAvailable] is flattened onto the pure
 * [ReleaseUpdateDisplay] display shape here, so `ReleaseInfo` never crosses
 * into `shared:ui-screens`.
 */
internal fun settingsUpdateCheckState(
    checking: Boolean,
    lastResult: ReleaseCheckResult?,
): SettingsUpdateCheckState = when {
    checking -> SettingsUpdateCheckState.Checking
    lastResult is ReleaseCheckResult.UpdateAvailable ->
        SettingsUpdateCheckState.UpdateAvailable(
            ReleaseUpdateDisplay(
                tagName = lastResult.info.tagName,
                htmlUrl = lastResult.info.htmlUrl,
                apkUrl = lastResult.info.apkUrl,
                publishedDateLabel = lastResult.info.publishedDateLabel,
            ),
        )
    lastResult is ReleaseCheckResult.UpToDate -> SettingsUpdateCheckState.UpToDate
    lastResult is ReleaseCheckResult.Failed -> SettingsUpdateCheckState.Failed(lastResult.reason)
    else -> SettingsUpdateCheckState.Idle
}
