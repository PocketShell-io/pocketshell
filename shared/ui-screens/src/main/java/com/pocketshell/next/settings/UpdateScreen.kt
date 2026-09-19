package com.pocketshell.next.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The Updates sub-page: check-for-updates flow across all
 * [SettingsUpdateCheckState] branches.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `UpdateCheckViewModel` state and opens URLs stays in app2 (`UpdateRoute`):
 * this composable only paints the state and fires the caller's lambdas.
 */
@Composable
fun UpdateScreen(
    state: SettingsUpdateCheckState,
    onBack: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(
        title = when (state) {
            is SettingsUpdateCheckState.UpdateAvailable -> "Update available"
            else -> "Updates"
        },
        pageTag = SETTINGS_UPDATE_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Build and updates") }
        when (state) {
            SettingsUpdateCheckState.Idle -> {
                item {
                    SettingsDescription(
                        title = "Check GitHub for the latest verified release.",
                        description = "PocketShell opens the system browser or download manager for the release.",
                    )
                }
                item {
                    PocketShellButton(
                        text = "Check for updates",
                        onClick = onCheckForUpdates,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }

            SettingsUpdateCheckState.Checking -> {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(PocketShellSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                    ) {
                        LoadingIndicator.Spinner(
                            size = com.pocketshell.uikit.components.SpinnerSize.Medium,
                            label = "Checking for updates…",
                        )
                        Text(
                            text = "Contacting GitHub releases.",
                            color = PocketShellColors.TextSecondary,
                            style = PocketShellType.body,
                        )
                    }
                }
            }

            SettingsUpdateCheckState.UpToDate -> {
                item {
                    SettingsDescription(
                        title = "Up to date",
                        description = "This installed build is the latest verified release.",
                    )
                }
                item {
                    PocketShellButton(
                        text = "Check again",
                        onClick = onCheckForUpdates,
                        variant = ButtonVariant.Text,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }

            is SettingsUpdateCheckState.Failed -> {
                item {
                    Banner(
                        text = "Couldn't check for updates: ${state.reason}",
                        role = BannerRole.Error,
                        leadingIcon = PocketShellIcons.Warning,
                        modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
                    )
                }
                item {
                    PocketShellButton(
                        text = "Retry update check",
                        onClick = onCheckForUpdates,
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }

            is SettingsUpdateCheckState.UpdateAvailable -> {
                item {
                    SettingsDescription(
                        title = "A newer PocketShell build is available.",
                        description = buildString {
                            append("Release ${state.info.tagName}. Keep the app and host helper on compatible versions.")
                            if (state.info.publishedDateLabel.isNotBlank()) {
                                append(" Published ${state.info.publishedDateLabel}.")
                            }
                        },
                    )
                }
                item {
                    ListRow(
                        title = "Release notes",
                        subtitle = "Review changes before updating",
                        leading = { androidx.compose.material3.Icon(PocketShellIcons.External, null, tint = PocketShellColors.TextSecondary) },
                        trailing = { NavigationChevron() },
                        onClick = { onOpenUrl(state.info.htmlUrl) },
                        modifier = Modifier.testTag("settings-release-notes"),
                    )
                }
                item {
                    PocketShellButton(
                        text = "Open release",
                        onClick = { onOpenUrl(state.info.apkUrl) },
                        variant = ButtonVariant.Primary,
                        modifier = Modifier
                            .padding(horizontal = PocketShellDensity.rowPadH)
                            .testTag(SETTINGS_UPDATE_CHECK_TAG),
                    )
                }
            }
        }
    }
}
