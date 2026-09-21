package com.pocketshell.next.ports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.DisclosureIcon
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

const val ADD_TUNNEL_SCREEN_TAG = "add_tunnel_screen"
const val ADD_TUNNEL_FORM_SCROLL_TAG = "add_tunnel_form_scroll"
const val ADD_TUNNEL_NAME_TAG = "add_tunnel_name"
const val ADD_TUNNEL_REMOTE_TAG = "add_tunnel_remote_port"
const val ADD_TUNNEL_LOCAL_TAG = "add_tunnel_local_port"
const val ADD_TUNNEL_OPTIONS_TAG = "add_tunnel_more_options"
const val ADD_TUNNEL_REMOTE_ADDRESS_TAG = "add_tunnel_remote_address"
const val ADD_TUNNEL_REMOTE_ADDRESS_CONTAINER_TAG = "add_tunnel_remote_address_container"
const val ADD_TUNNEL_SUBMIT_TAG = "add_tunnel_submit"

/**
 * The add-tunnel form, moved to the shared presentation module (#2636 D11).
 *
 * The research proposal's verbatim-move candidate: the screen already took
 * only primitives, so the move changes no signature and no wording. The route
 * that owns the form field state, the collision check against the view model
 * and the foreground-service resume stays in app2
 * (`app2/.../ports/AddTunnelRoute.kt`) — the D10 usage-seam shape with the
 * route file named for the route it holds (a same-named
 * `AddTunnelScreen.kt` on both sides of the seam would collide on the
 * `AddTunnelScreenKt` JVM facade). The route's former
 * `Dispatchers`/`withContext` handoff stays with it: this module deliberately
 * declares no coroutines dependency.
 */
@Composable
fun AddTunnelScreen(
    name: String,
    remotePort: String,
    localPort: String,
    valid: Boolean,
    localPortCollision: String? = null,
    onNameChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onLocalPortChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var moreOptionsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(ADD_TUNNEL_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = "Add tunnel",
            onBack = onBack,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // MainActivity is edge-to-edge with SOFT_INPUT_ADJUST_NOTHING
                // (#887/#2533), so the window never resizes for the keyboard:
                // without opting this scroll column into the IME inset the
                // keyboard overlays the form's bottom and the submit can never
                // scroll clear of it (issue #2551, pinned by J19).
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .testTag(ADD_TUNNEL_FORM_SCROLL_TAG)
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Name") },
                supportingText = { Text("Shown in the tunnel list") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_NAME_TAG),
            )
            OutlinedTextField(
                value = remotePort,
                onValueChange = onRemotePortChange,
                label = { Text("Remote port") },
                supportingText = { Text("The listening port on the host") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_REMOTE_TAG),
            )
            OutlinedTextField(
                value = localPort,
                onValueChange = onLocalPortChange,
                label = { Text("Local port") },
                supportingText = {
                    Text(localPortCollision ?: "127.0.0.1 only on this phone")
                },
                isError = localPortCollision != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_LOCAL_TAG),
            )
            Text(
                text = "The tunnel uses loopback-only exposure. A service is not started until you save this mapping.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.body,
            )
            ListRow(
                title = "More options",
                subtitle = "Remote target and exposure",
                onClick = { moreOptionsExpanded = !moreOptionsExpanded },
                trailing = { DisclosureIcon(expanded = moreOptionsExpanded) },
                // A disclosure row inside a gapped form, not a list (#2804).
                showDivider = false,
                modifier = Modifier.testTag(ADD_TUNNEL_OPTIONS_TAG),
            )
            if (moreOptionsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ADD_TUNNEL_REMOTE_ADDRESS_CONTAINER_TAG)
                        .padding(horizontal = PocketShellSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                ) {
                    OutlinedTextField(
                        value = "127.0.0.1",
                        onValueChange = {},
                        label = { Text("Remote address") },
                        supportingText = { Text("Fixed to the host loopback target by the current forwarding backend") },
                        readOnly = true,
                        enabled = false,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ADD_TUNNEL_REMOTE_ADDRESS_TAG),
                    )
                    Text(
                        text = "Changing the local bind address can expose this service to other devices. PocketShell keeps this tunnel on 127.0.0.1.",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.metadata,
                    )
                }
            }
            Text(
                text = "Valid ports are 1–65535.",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
            PocketShellButton(
                text = "Start tunnel",
                onClick = onSubmit,
                enabled = valid && name.trim().isNotEmpty() && localPortCollision == null,
                variant = ButtonVariant.Primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ADD_TUNNEL_SUBMIT_TAG),
            )
        }
    }
}
