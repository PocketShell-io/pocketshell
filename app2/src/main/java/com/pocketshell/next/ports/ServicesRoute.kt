package com.pocketshell.next.ports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

/** The host-scoped Quiet entry point for forwarding and discovered services. */
@Composable
fun ServicesRoute(
    onBack: () -> Unit,
    onOpenTunnel: (Int) -> Unit = {},
    onAddTunnel: (Int?) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val discovered = state.discoveredRows.ifEmpty { state.rows }
    val activeForVerification = discovered.filter { it.status == TunnelStatusDisplay.FORWARDING }
    // Keep the Quiet route on the same real foreground-service lifecycle as the
    // original port-forward route. The Room-backed enabled flag is read again
    // after process death, so reopening this destination remounts every enabled
    // host through ForwardService.resume -> ForwardingController.resumeEnabled.
    LaunchedEffect(state.enabled) {
        if (state.enabled) ForwardService.resume(context)
    }
    LaunchedEffect(activeForVerification.map { it.remotePort to it.localPort }) {
        viewModel.verifyHttpServices(activeForVerification.map { it.remotePort })
    }
    ServicesScreen(
        state = state,
        onBack = onBack,
        onSetDiscovery = viewModel::setEnabled,
        onOpenTunnel = onOpenTunnel,
        onAddTunnel = onAddTunnel,
        verifiedHttpServices = state.verifiedHttpServices,
        onOpenBrowser = { launchServiceUrl(context, it) },
        modifier = modifier,
    )
}
