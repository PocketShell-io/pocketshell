package com.pocketshell.next.settings

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.NavigationChevron
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * The Connections settings sub-page: reconnect behaviour plus the saved-host
 * list leading to workspace roots.
 *
 * Lives in the shared presentation module (#2636 D6). The route that collects
 * `SettingsViewModel` state and the host rows stays in app2
 * (`ConnectionSettingsRoute`): this composable only paints and fires the
 * caller's lambdas.
 */
@Composable
fun ConnectionSettingsScreen(
    settings: AppSettings,
    hosts: List<SettingsHostRow>,
    onBack: () -> Unit,
    onOpenGrace: () -> Unit,
    onReconnectWhenReturnChange: (Boolean) -> Unit = {},
    onOpenWorkspaceRoots: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graceLabel = AppSettings.BACKGROUND_GRACE_OPTIONS
        .firstOrNull { it.millis == settings.backgroundGraceMillis }
        ?.label
        ?: "Default"
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
                trailing = { NavigationChevron() },
                onClick = onOpenGrace,
                modifier = Modifier.testTag("settings-connection-grace"),
            )
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
