package com.pocketshell.next.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Route-level entry point: binds the Hilt ViewModel to the stateless screen.
 *
 * Stays in app2 (#2636 D4): [SharePickerScreen] and its pure state types moved
 * to the shared presentation module (`shared:ui-screens`), but this route and
 * the Hilt/ViewModel wiring behind it are app concerns — the module boundary
 * keeps DI and the upload transport out of the shared screens.
 */
@Composable
fun ShareRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SharePickerScreen(
        state = state,
        onPickHost = viewModel::uploadTo,
        onRetry = viewModel::retry,
        onPickAnother = viewModel::backToPicker,
        onFinished = onFinished,
        modifier = modifier,
    )
}
