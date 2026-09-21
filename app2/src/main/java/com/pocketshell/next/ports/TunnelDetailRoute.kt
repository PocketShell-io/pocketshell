package com.pocketshell.next.ports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TunnelDetailRoute(
    remotePort: Int,
    onBack: () -> Unit,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Services deliberately exposes the complete discovery snapshot, including
    // ports hidden by the legacy "interesting ports" filter used by the old
    // port-forward screen. Resolve detail from that same source or a service
    // such as sshd:22 would open a false "unavailable" state after navigation.
    val discovered = state.discoveredRows.ifEmpty { state.rows }
    val tunnel = discovered.firstOrNull { it.remotePort == remotePort }
    val manualName = state.manualTunnelNames[remotePort]
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(tunnel?.remotePort, tunnel?.localPort, tunnel?.status) {
        viewModel.verifyHttpServices(listOfNotNull(tunnel?.remotePort))
    }
    TunnelDetailScreen(
        hostName = state.hostName,
        tunnel = tunnel,
        manual = remotePort in state.manualRemotePorts,
        manualName = manualName,
        verifiedUrl = state.verifiedHttpServices[remotePort],
        onBack = onBack,
        onCopyAddress = { localPort -> clipboard.setText(AnnotatedString("127.0.0.1:$localPort")) },
        onOpenBrowser = { launchServiceUrl(context, it) },
        onStop = {
            if (remotePort in state.manualRemotePorts) {
                coroutineScope.launch {
                    viewModel.removeManualTunnelNow(remotePort)
                    ForwardService.resume(context)
                    withContext(Dispatchers.Main.immediate) { onBack() }
                }
            } else {
                viewModel.togglePort(remotePort)
                onBack()
            }
        },
    )
}
