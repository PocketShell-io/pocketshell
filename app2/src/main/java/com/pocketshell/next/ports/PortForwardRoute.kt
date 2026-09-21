package com.pocketshell.next.ports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Route-level entry point: binds the Hilt-provided [PortForwardViewModel] to the
 * stateless shared [PortForwardScreen].
 *
 * Stays in app2 (#2636 D11): the screen composable moved to the shared
 * presentation module (`shared:ui-screens`) painting the pure
 * [PortForwardDisplayState] / display mirrors, but this route, the Hilt view
 * model and the core → display mapping next to it are app concerns — the
 * module boundary keeps `com.pocketshell.core.portfwd` out of the shared
 * screens, exactly like the D10 usage seam (`UsageRoute` +
 * `UsageFetcher`'s ingestion-point mapping). The file is named for the route
 * it holds (the D3 file-naming rule): a same-named `PortForwardScreen.kt` on
 * both sides of the seam would collide on the `PortForwardScreenKt` JVM
 * facade.
 *
 * The one thing that cannot live in the ViewModel is starting the foreground
 * service, which needs a `Context`. Doing it here — keyed on the enabled flag —
 * keeps the ViewModel Android-free and testable.
 *
 * Re-triggering `resume` for an already-enabled host is deliberate, not merely
 * harmless: the controller asks an already-mounted supervisor to retry now, and
 * arriving on this screen is exactly the recovery path for a host parked in the
 * terminal needs-attention state after its key was confirmed from the host list
 * (#2491). For a healthy host it changes nothing.
 */
@Composable
fun PortForwardRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(state.enabled) {
        if (state.enabled) ForwardService.resume(context)
    }
    PortForwardScreen(
        state = state,
        onSetEnabled = viewModel::setEnabled,
        onTogglePort = viewModel::togglePort,
        onSetShowAllPorts = viewModel::setShowAllPorts,
        onBack = onBack,
        modifier = modifier,
    )
}
