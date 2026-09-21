package com.pocketshell.next.ports

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MAX_NAME_LENGTH = 80

private fun Int?.isValidPort(): Boolean = this != null && this in 1..65_535

@Composable
fun AddTunnelRoute(
    initialRemotePort: Int?,
    onDone: () -> Unit,
    viewModel: PortForwardViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val suggestedName = state.discoveredRows
        .firstOrNull { it.remotePort == initialRemotePort }
        ?.process
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: initialRemotePort?.let { "Port $it" }
        ?: ""
    var nameText by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var remoteText by rememberSaveable { mutableStateOf(initialRemotePort?.toString().orEmpty()) }
    var localText by rememberSaveable { mutableStateOf(initialRemotePort?.toString().orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var localPortCollision by rememberSaveable { mutableStateOf<String?>(null) }
    var collisionChecked by rememberSaveable { mutableStateOf(false) }
    val remotePort = remoteText.toIntOrNull()
    val localPort = localText.toIntOrNull()
    LaunchedEffect(suggestedName) {
        if (!nameEdited && nameText.isBlank() && suggestedName.isNotBlank()) {
            nameText = suggestedName
        }
    }
    // The collision belongs to the local bind, but both route fields are part
    // of the form's validation lifecycle. A remote-only edit must not leave a
    // previous check invalidated forever while the effect remains keyed only
    // by localPort.
    LaunchedEffect(remotePort, localPort) {
        if (!localPort.isValidPort()) {
            localPortCollision = null
            collisionChecked = true
        } else {
            collisionChecked = false
            localPortCollision = viewModel.localPortCollision(localPort!!)
            collisionChecked = true
        }
    }
    val valid = remotePort.isValidPort() &&
        localPort.isValidPort() &&
        nameText.trim().isNotEmpty() &&
        collisionChecked &&
        localPortCollision == null
    LaunchedEffect(submitted) {
        if (submitted && valid) {
            try {
                viewModel.addManualTunnel(remotePort!!, localPort!!, nameText.trim())
                // Remounting a live supervisor briefly publishes an empty snapshot;
                // the foreground service may stop itself during that transition.
                // Re-trigger the real service after the durable mount completes.
                ForwardService.resume(context)
                withContext(Dispatchers.Main.immediate) { onDone() }
            } catch (collision: LocalPortCollisionException) {
                localPortCollision = collision.message
                collisionChecked = true
                submitted = false
            }
        }
    }
    AddTunnelScreen(
        name = nameText,
        remotePort = remoteText,
        localPort = localText,
        valid = valid,
        localPortCollision = localPortCollision,
        onNameChange = {
            nameEdited = true
            nameText = it.take(MAX_NAME_LENGTH)
        },
        onRemotePortChange = {
            remoteText = it.filter(Char::isDigit).take(5)
            localPortCollision = null
            collisionChecked = false
        },
        onLocalPortChange = { localText = it.filter(Char::isDigit).take(5) },
        onSubmit = { submitted = true },
        onBack = onDone,
    )
}
