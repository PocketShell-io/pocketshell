package com.pocketshell.next.hosts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Native biometric/device-credential action used by the production key gate.
 *
 * The tags and the panel below live in the shared presentation module
 * (#2636 D9), split out of app2's `SshKeyUnlock.kt`: they are pure
 * presentation over [SshKeyRow]. What stayed app2-side in that file is the
 * half that cannot cross this boundary — `isSshKeyUnlockRequired`,
 * `launchSshKeyUnlock`, the prompt launcher and the in-flight gate all reach
 * for `androidx.biometric` / `androidx.fragment` and an Android `Context`.
 * The package name is the same on both sides, so app2's `SshKeysScreen.kt`
 * keeps referencing these tags without an import edit.
 */
const val SSH_KEYS_UNLOCK_BUTTON_TAG: String = "ssh-keys-unlock-button"
const val SSH_KEYS_PASSPHRASE_FALLBACK_TAG: String = "ssh-keys-passphrase-fallback"
const val SSH_KEYS_FALLBACK_FIELD_TAG: String = "ssh-keys-fallback-field"
const val SSH_KEYS_FALLBACK_SUBMIT_TAG: String = "ssh-keys-fallback-submit"

fun sshKeyFallbackRowTag(keyId: Long): String = "ssh-keys-fallback-key-$keyId"

@Composable
fun SshKeyUnlockPanel(
    error: String?,
    inFlight: Boolean,
    onUnlock: () -> Unit,
    deviceUnlockAvailable: Boolean,
    protectedKeys: List<SshKeyRow>,
    selectedKeyId: Long?,
    fallbackPassphrase: String,
    fallbackInFlight: Boolean,
    onSelectKey: (Long) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onUnlockWithPassphrase: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(PocketShellSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        ScreenHeader(title = "Unlock SSH keys")
        Banner(
            text = "Device authentication protects private-key details. " +
                "If device unlock is unavailable, canceled, or fails, verify the passphrase " +
                "for one encrypted key below.",
            role = BannerRole.Info,
        )
        if (deviceUnlockAvailable) {
            PocketShellButton(
                text = if (inFlight) "Waiting for device unlock…" else "Unlock with device",
                onClick = onUnlock,
                enabled = !inFlight,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SSH_KEYS_UNLOCK_BUTTON_TAG),
                variant = ButtonVariant.Primary,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SSH_KEYS_PASSPHRASE_FALLBACK_TAG),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            Text(text = "Passphrase fallback")
            if (protectedKeys.isEmpty()) {
                Text(
                    text = "No passphrase-protected SSH key is available for fallback.",
                )
            } else {
                Text(
                    text = "Choose the encrypted key whose passphrase you want to verify.",
                )
                protectedKeys.forEach { key ->
                    ListRow(
                        title = key.name,
                        subtitle = "Passphrase protected",
                        trailing = {
                            RadioButton(
                                selected = selectedKeyId == key.id,
                                onClick = { onSelectKey(key.id) },
                            )
                        },
                        onClick = { onSelectKey(key.id) },
                        modifier = Modifier.testTag(sshKeyFallbackRowTag(key.id)),
                    )
                }
                OutlinedTextField(
                    value = fallbackPassphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("Key passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = selectedKeyId != null && !fallbackInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SSH_KEYS_FALLBACK_FIELD_TAG),
                )
                PocketShellButton(
                    text = if (fallbackInFlight) "Checking passphrase…" else "Unlock with passphrase",
                    onClick = onUnlockWithPassphrase,
                    enabled = selectedKeyId != null && fallbackPassphrase.isNotEmpty() && !fallbackInFlight,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SSH_KEYS_FALLBACK_SUBMIT_TAG),
                )
            }
        }
        error?.let {
            Banner(text = it, role = BannerRole.Warning)
        }
    }
}
