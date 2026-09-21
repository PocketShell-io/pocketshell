package com.pocketshell.next.hosts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Route-level entry point for the shared add/edit host form (#2636 D13).
 *
 * Navigation, Hilt and Room ingestion stay app-side. [hostId] is bound on
 * every change, including `null`, so entering Add clears a previous edit
 * target rather than silently retaining it.
 */
@Composable
fun AddEditHostRoute(
    hostId: Long?,
    onDone: () -> Unit,
    onAddKey: () -> Unit,
    onTestConnection: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AddEditHostViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val keys by viewModel.sshKeys.collectAsState()
    val selectedKeyResult by viewModel.selectedKeyResult.collectAsState()

    LaunchedEffect(hostId) { viewModel.bind(hostId) }
    LaunchedEffect(selectedKeyResult) {
        selectedKeyResult?.let { keyId ->
            viewModel.selectKey(keyId)
            viewModel.consumeSelectedKeyResult()
        }
    }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            onDone()
        }
    }
    LaunchedEffect(state.testConnectionHostId) {
        val connectionHostId = state.testConnectionHostId ?: return@LaunchedEffect
        viewModel.consumeTestConnection()
        onTestConnection(connectionHostId)
    }

    AddEditHostScreen(
        state = state,
        keys = keys.map {
            SshKeyRow(id = it.id, name = it.name, fingerprint = it.fingerprint)
        },
        onChange = viewModel::update,
        onSave = viewModel::save,
        onTestConnection = viewModel::testConnection,
        onCancel = onDone,
        onAddKey = onAddKey,
        modifier = modifier,
    )
}
