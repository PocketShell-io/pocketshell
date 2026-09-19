package com.pocketshell.next.settings

/**
 * One saved host as the Connections settings page displays it (#2636 D6).
 *
 * Lives in the shared presentation module: [ConnectionSettingsScreen] paints
 * these rows directly. The view model that loads them from the host repository
 * stays in app2 (`SettingsViewModel`).
 */
data class SettingsHostRow(val id: Long, val name: String, val subtitle: String)
