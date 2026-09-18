package com.pocketshell.next.hosts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.next.release.UpdateCheckViewModel
import com.pocketshell.next.release.launchUpdateUrl
import com.pocketshell.next.release.updateAvailableBannerText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Route-level entry point: binds the Hilt-provided [HostListViewModel] to the
 * stateless [HostListScreen].
 *
 * The split exists so the screen can be rendered from a test (or a design
 * render) with a hand-built state and no DI graph, which is what keeps the
 * screen itself free of `remember`-ed side state.
 *
 * Stays in app2 (#2636 D1): the screen and its state types moved to the
 * shared presentation module (`shared:ui-screens`), but the Room→row
 * projection in [HostListViewModel] and the app's update-check plumbing are
 * app concerns — the module boundary keeps transport/storage/DI out of the
 * shared screens.
 */
@Composable
fun HostListRoute(
    onOpenHost: (Long) -> Unit,
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSshKeys: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: HostListViewModel = hiltViewModel(),
    updateCheckViewModel: UpdateCheckViewModel? = null,
) {
    val state by viewModel.state.collectAsState()
    val available by collectOrNull(updateCheckViewModel?.available)
    val failed by collectOrNull(updateCheckViewModel?.failed)
    val context = LocalContext.current
    val info = available
    val failure = failed
    val notice = when {
        info != null -> HostListUpdateNotice.Available(
            text = updateAvailableBannerText(
                info,
                updateCheckViewModel?.installedVersionLabel() ?: "",
            ),
            apkUrl = info.apkUrl,
            htmlUrl = info.htmlUrl,
        )
        failure != null -> HostListUpdateNotice.Failed(failure)
        else -> null
    }
    HostListScreen(
        state = state,
        onOpenHost = onOpenHost,
        onAddHost = onAddHost,
        onEditHost = onEditHost,
        onOpenSettings = onOpenSettings,
        onOpenSshKeys = onOpenSshKeys,
        onDeleteHost = viewModel::delete,
        modifier = modifier,
        updateNotice = notice,
        onDownloadUpdate = { url -> launchUpdateUrl(context, url) },
        onOpenReleaseNotes = { url -> launchUpdateUrl(context, url) },
        onDismissUpdate = { updateCheckViewModel?.dismissUpdate() },
        onRetryUpdateCheck = { updateCheckViewModel?.refreshNow() },
        onDismissUpdateFailure = { updateCheckViewModel?.dismissFailure() },
    )
}

@Composable
private fun <T> collectOrNull(flow: StateFlow<T?>?): androidx.compose.runtime.State<T?> {
    val fallback = remember { MutableStateFlow(null as T?) }
    return (flow ?: fallback).collectAsState()
}
