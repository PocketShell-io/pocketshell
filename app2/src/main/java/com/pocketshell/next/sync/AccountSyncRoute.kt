package com.pocketshell.next.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Route-level entry point: binds the Hilt ViewModel to the stateless screen.
 *
 * Stays in app2 (#2636 D7): [AccountSyncScreen] and its pure state types moved
 * to the shared presentation module (`shared:ui-screens`), but this route and
 * the Hilt/ViewModel wiring behind it are app concerns — the module boundary
 * keeps DI, GoogleAuth and the sync repository out of the shared screens. The
 * app2-side `SyncOutcome`/`SyncSignInCoordinator.State` → display-shape
 * adapters live next to the types they map, in `AccountSyncViewModel.kt`, so
 * the route hands the shared screen already-pure state.
 */
@Composable
fun AccountSyncRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountSyncViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    AccountSyncScreen(
        state = state,
        onBack = onBack,
        onSignIn = { viewModel.signIn(context) },
        onSignOut = viewModel::signOut,
        onDismissSignInBanner = viewModel::acknowledgeSignIn,
        onHostChecked = viewModel::setHostChecked,
        onPush = viewModel::push,
        onPull = viewModel::pull,
        modifier = modifier,
    )
}
