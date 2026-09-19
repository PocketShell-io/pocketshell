package com.pocketshell.next.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The About sub-page: installed build identity plus the update-check entry
 * point.
 *
 * Lives in the shared presentation module (#2636 D6). The route that reads the
 * build info from the Android package manager and maps the update-check poll
 * onto [SettingsUpdateCheckState] stays in app2 (`AboutRoute`): this
 * composable only paints the rows and fires the caller's lambdas.
 */
@Composable
fun AboutScreen(
    buildInfo: AppBuildInfo,
    updateCheckState: SettingsUpdateCheckState,
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit,
    onOpenLicenses: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = "About PocketShell",
        pageTag = SETTINGS_ABOUT_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item {
            SettingsDescription(
                title = "PocketShell",
                description = "Workspaces first. Terminals stay terminals.",
            )
        }
        item {
            ListRow(
                title = "Installed version",
                subtitle = buildInfo.displayText(),
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Info, null, tint = PocketShellColors.TextSecondary) },
                modifier = Modifier.testTag(SETTINGS_VERSION_TAG),
            )
        }
        item {
            ListRow(
                title = "Check for updates",
                subtitle = aboutUpdateSummary(updateCheckState),
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Refresh, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenUpdate,
                modifier = Modifier.testTag(SETTINGS_UPDATE_CHECK_TAG),
            )
        }
        item {
            ListRow(
                title = "Open-source licenses",
                leading = { androidx.compose.material3.Icon(PocketShellIcons.File, null, tint = PocketShellColors.TextSecondary) },
                trailing = { NavigationChevron() },
                onClick = onOpenLicenses,
            )
        }
    }
}

private fun aboutUpdateSummary(state: SettingsUpdateCheckState): String = when (state) {
    SettingsUpdateCheckState.Idle -> "Check the latest verified release"
    SettingsUpdateCheckState.Checking -> "Checking GitHub releases…"
    SettingsUpdateCheckState.UpToDate -> "This build is up to date"
    is SettingsUpdateCheckState.Failed -> "Check failed · tap to retry"
    is SettingsUpdateCheckState.UpdateAvailable -> "${state.info.tagName} is available"
}
