package com.pocketshell.next.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.QuietChoiceRow
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The Connections settings sub-page: reconnect behaviour, the background-grace
 * choice group, and the saved-host list leading to workspace roots.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state and the host rows stays in app2
 * (`ConnectionSettingsRoute`): this composable only paints and fires the
 * caller's lambdas.
 *
 * ## The grace group expands in place (#2814 N-3)
 *
 * `Destination.GraceSettings` was a full route whose entire payload was one
 * five-option [QuietChoiceRow] group plus a "Done" button — see
 * [VoiceSettingsScreen] for the same argument and the same hard cut (D22). The
 * page's own "Connection lifetime" description already carried the sentence
 * the deleted page explained itself with, so nothing moved here but the rows.
 *
 * @param initiallyExpanded render/test seam: start with the grace group open.
 *   Production always starts collapsed; the design renders need the open state
 *   without a click, because their frame clock is frozen (#2733).
 */
@Composable
fun ConnectionSettingsScreen(
    settings: AppSettings,
    hosts: List<SettingsHostRow>,
    onBack: () -> Unit,
    onBackgroundGraceChange: (Long) -> Unit,
    onReconnectWhenReturnChange: (Boolean) -> Unit = {},
    onOpenWorkspaceRoots: (Long) -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    val graceLabel = AppSettings.BACKGROUND_GRACE_OPTIONS
        .firstOrNull { it.millis == settings.backgroundGraceMillis }
        ?.label
        ?: "Default"
    var graceExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    SettingsPageScaffold(
        title = "Connections",
        pageTag = SETTINGS_CONNECTIONS_PAGE_TAG,
        onBack = onBack,
        modifier = modifier,
    ) {
        item { SectionHeader(label = "Recovery") }
        item {
            ListRow(
                title = "Keep connection after leaving",
                subtitle = graceLabel,
                leading = { androidx.compose.material3.Icon(PocketShellIcons.History, null, tint = PocketShellColors.TextSecondary) },
                trailing = { DisclosureIcon(expanded = graceExpanded) },
                onClick = { graceExpanded = !graceExpanded },
                modifier = Modifier.testTag(SETTINGS_CONNECTION_GRACE_TAG),
            )
        }
        item {
            AnimatedVisibility(visible = graceExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SETTINGS_CONNECTION_GRACE_GROUP_TAG),
                ) {
                    AppSettings.BACKGROUND_GRACE_OPTIONS.forEach { option ->
                        QuietChoiceRow(
                            title = option.label,
                            subtitle = graceDescription(option.millis),
                            selected = settings.backgroundGraceMillis == option.millis,
                            onClick = {
                                onBackgroundGraceChange(option.millis)
                                graceExpanded = false
                            },
                            modifier = Modifier.testTag(backgroundGraceOptionTag(option.millis)),
                        )
                    }
                }
            }
        }
        item {
            ListRow(
                title = "Reconnect when I return",
                subtitle = "Retry a dropped session automatically when PocketShell comes back.",
                leading = { androidx.compose.material3.Icon(PocketShellIcons.Refresh, null, tint = PocketShellColors.TextSecondary) },
                trailing = {
                    Switch(
                        checked = settings.reconnectWhenReturn,
                        onCheckedChange = onReconnectWhenReturnChange,
                    )
                },
                modifier = Modifier.testTag(SETTINGS_CONNECTION_RECONNECT_TAG),
            )
        }
        item {
            SettingsDescription(
                title = "Connection lifetime",
                description = "This controls the phone’s connection. Remote sessions are not deliberately ended when the app leaves the foreground.",
            )
        }
        item { SectionHeader(label = "Manage saved hosts") }
        if (hosts.isEmpty()) {
            item {
                EmptyState(
                    title = "No saved hosts",
                    description = "Add a host first to manage its workspace roots.",
                    icon = PocketShellIcons.Server,
                    modifier = Modifier
                        .height(180.dp)
                        .testTag(SETTINGS_WORKSPACE_EMPTY_TAG),
                )
            }
        } else {
            items(hosts, key = { it.id }) { host ->
                ListRow(
                    title = host.name,
                    subtitle = host.subtitle,
                    leading = { androidx.compose.material3.Icon(PocketShellIcons.Server, null, tint = PocketShellColors.TextSecondary) },
                    trailing = { NavigationChevron() },
                    onClick = { onOpenWorkspaceRoots(host.id) },
                    modifier = Modifier.testTag(settingsHostRowTag(host.id)),
                )
            }
        }
    }
}

/**
 * Was `GraceSettingsScreen`'s private helper; it moved here with the rows when
 * the leaf route was deleted (#2814 N-3).
 */
private fun graceDescription(millis: Long): String = when (millis) {
    AppSettings.BACKGROUND_GRACE_30_SECONDS_MS -> "Good for switching apps"
    AppSettings.BACKGROUND_GRACE_1_MINUTE_MS -> "More time between app switches"
    AppSettings.BACKGROUND_GRACE_90_SECONDS_MS -> "Default recovery window"
    AppSettings.BACKGROUND_GRACE_5_MINUTES_MS -> "Longer app switches"
    AppSettings.BACKGROUND_GRACE_10_MINUTES_MS -> "Extended recovery window"
    else -> "Custom recovery window"
}
