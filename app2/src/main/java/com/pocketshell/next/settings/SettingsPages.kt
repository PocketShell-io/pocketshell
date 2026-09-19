package com.pocketshell.next.settings

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketshell.next.release.UpdateCheckViewModel
import com.pocketshell.next.release.launchUpdateUrl

/**
 * Route-level entry points for the categorized Settings sub-pages.
 *
 * Everything visual (the `*Screen` composables, `AppSettings`, the shared
 * scaffold and slider) lives in the shared presentation module (#2636 D6);
 * these routes stay in app2 because they own the Hilt view-model wiring, the
 * Android build-info read and the update-check URL hand-off.
 */

@Composable
internal fun TerminalSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    TerminalSettingsScreen(
        settings = settings,
        onBack = onBack,
        onTerminalTextSizeChange = viewModel::setTerminalTextSizePx,
        onShowCommonKeysChange = viewModel::setShowCommonKeys,
        modifier = modifier,
    )
}

@Composable
internal fun VoiceSettingsRoute(
    onBack: () -> Unit,
    onOpenLanguage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    VoiceSettingsScreen(
        settings = settings,
        onBack = onBack,
        onOpenLanguage = onOpenLanguage,
        modifier = modifier,
    )
}

@Composable
internal fun LanguageSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    LanguageSettingsScreen(
        settings = settings,
        onBack = onBack,
        onVoiceLanguageChange = viewModel::setVoiceLanguage,
        modifier = modifier,
    )
}

@Composable
internal fun ConnectionSettingsRoute(
    onBack: () -> Unit,
    onOpenGrace: () -> Unit,
    onOpenWorkspaceRoots: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val hosts by viewModel.hosts.collectAsStateWithLifecycle()
    ConnectionSettingsScreen(
        settings = settings,
        hosts = hosts,
        onBack = onBack,
        onOpenGrace = onOpenGrace,
        onReconnectWhenReturnChange = viewModel::setReconnectWhenReturn,
        onOpenWorkspaceRoots = onOpenWorkspaceRoots,
        modifier = modifier,
    )
}

@Composable
internal fun GraceSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    GraceSettingsScreen(
        settings = settings,
        onBack = onBack,
        onBackgroundGraceChange = viewModel::setBackgroundGraceMillis,
        modifier = modifier,
    )
}

@Composable
internal fun AdvancedSettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    AdvancedSettingsScreen(
        settings = settings,
        onBack = onBack,
        onVoiceSilenceChange = viewModel::setVoiceSilenceThresholdSeconds,
        onUsageWarnThresholdChange = viewModel::setUsageWarnThresholdPercent,
        onAgentSubmitEnterDelayChange = viewModel::setAgentSubmitEnterDelayMs,
        onResetAdvancedDefaults = viewModel::resetAdvancedDefaults,
        modifier = modifier,
    )
}

@Composable
internal fun AboutRoute(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit,
    modifier: Modifier = Modifier,
    updateCheckViewModel: UpdateCheckViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val checking by updateCheckViewModel.checking.collectAsStateWithLifecycle()
    val lastResult by updateCheckViewModel.lastResult.collectAsStateWithLifecycle()
    val buildInfo = remember(context) { readBuildInfo(context) }
    AboutScreen(
        buildInfo = buildInfo,
        updateCheckState = settingsUpdateCheckState(checking, lastResult),
        onBack = onBack,
        onOpenUpdate = onOpenUpdate,
        onOpenLicenses = {
            Toast.makeText(
                context,
                "Open licenses in the Android implementation",
                Toast.LENGTH_LONG,
            ).show()
        },
        modifier = modifier,
    )
}

@Composable
internal fun UpdateRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateCheckViewModel: UpdateCheckViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val checking by updateCheckViewModel.checking.collectAsStateWithLifecycle()
    val lastResult by updateCheckViewModel.lastResult.collectAsStateWithLifecycle()
    val state = settingsUpdateCheckState(checking, lastResult)

    LaunchedEffect(updateCheckViewModel) {
        if (state is SettingsUpdateCheckState.Idle) {
            updateCheckViewModel.refreshNow()
        }
    }

    UpdateScreen(
        state = state,
        onBack = onBack,
        onCheckForUpdates = updateCheckViewModel::refreshNow,
        onOpenUrl = { url -> launchUpdateUrl(context, url) },
        modifier = modifier,
    )
}

private fun readBuildInfo(context: Context): AppBuildInfo = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    AppBuildInfo(
        versionName = info.versionName ?: UNKNOWN_VERSION,
        versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        },
    )
}.getOrElse { AppBuildInfo(versionName = UNKNOWN_VERSION, versionCode = null) }

private const val UNKNOWN_VERSION = "unknown"
