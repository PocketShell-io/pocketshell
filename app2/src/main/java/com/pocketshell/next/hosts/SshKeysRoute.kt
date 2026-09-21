package com.pocketshell.next.hosts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * App route for the shared SSH-key screen (#2636 D13).
 *
 * Android file/clipboard/device-unlock work and cryptographic PEM inspection
 * stay here. The shared screen receives only display state, exhaustive mirror
 * requests and callbacks, keeping platform and key-material services out of
 * `:shared:ui-screens`.
 */
@Composable
fun SshKeysRoute(
    onBack: () -> Unit,
    onUseKey: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SshKeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val composeClipboard = LocalClipboardManager.current
    val platformClipboard = remember(context) {
        context.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }
    val deviceUnlockAvailable = remember(context) { isSshKeyUnlockRequired(context) }
    val protectedKeys = state.keys.filter { it.hasPassphrase }
    var unlocked by remember(context) {
        mutableStateOf(!deviceUnlockAvailable && protectedKeys.isEmpty())
    }
    var unlockError by remember { mutableStateOf<String?>(null) }
    var unlockInFlight by remember { mutableStateOf(false) }
    var fallbackKeyId by remember { mutableStateOf<Long?>(null) }
    var fallbackPassphrase by remember { mutableStateOf("") }
    var fallbackInFlight by remember { mutableStateOf(false) }
    var fallbackError by remember { mutableStateOf<String?>(null) }
    var fileImportCandidate by remember { mutableStateOf<SshKeyImportCandidate?>(null) }
    val unlockGate = remember { SshKeyUnlockInFlightGate() }

    LaunchedEffect(deviceUnlockAvailable, state.loaded, protectedKeys.map { it.id }) {
        if (!deviceUnlockAvailable && state.loaded) {
            unlocked = protectedKeys.isEmpty()
        }
        if (fallbackKeyId !in protectedKeys.map { it.id }) {
            fallbackKeyId = protectedKeys.firstOrNull()?.id
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        fileImportCandidate = SshKeyImportCandidate(name = name, pem = text.orEmpty())
    }

    if (!unlocked) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(PocketShellColors.Background),
        ) {
            ScreenHeader(title = "SSH keys", onBack = onBack)
        }
        SshKeysUnlockSheet(
            error = fallbackError ?: unlockError,
            inFlight = unlockInFlight,
            deviceUnlockAvailable = deviceUnlockAvailable,
            protectedKeys = protectedKeys,
            selectedKeyId = fallbackKeyId,
            fallbackPassphrase = fallbackPassphrase,
            fallbackInFlight = fallbackInFlight,
            onUnlock = {
                if (unlockGate.tryMarkInFlight()) {
                    unlockInFlight = true
                    unlockError = null
                    launchSshKeyUnlock(
                        activity = context as? androidx.fragment.app.FragmentActivity,
                        onSuccess = {
                            unlockGate.clear()
                            unlockInFlight = false
                            unlocked = true
                        },
                        onError = {
                            unlockGate.clear()
                            unlockInFlight = false
                            unlockError = it
                        },
                        onFailure = {
                            unlockGate.clear()
                            unlockInFlight = false
                            unlockError = it
                        },
                    )
                }
            },
            onSelectKey = {
                fallbackKeyId = it
                fallbackError = null
            },
            onPassphraseChange = {
                fallbackPassphrase = it
                fallbackError = null
            },
            onUnlockWithPassphrase = {
                fallbackKeyId?.let { keyId ->
                    val chars = fallbackPassphrase.toCharArray()
                    fallbackPassphrase = ""
                    fallbackError = null
                    fallbackInFlight = true
                    viewModel.unlockWithPassphrase(keyId, chars) { success, error ->
                        fallbackInFlight = false
                        if (success) {
                            unlockError = null
                            unlocked = true
                        } else {
                            fallbackError = error
                        }
                    }
                }
            },
            onDismiss = onBack,
        )
    } else {
        SshKeysScreen(
            state = state,
            onBack = onBack,
            onUseKey = onUseKey,
            onGenerate = { viewModel.generate(it.toDomain()) },
            onImportPasted = viewModel::import,
            onPickFile = { filePicker.launch("*/*") },
            onDelete = viewModel::delete,
            onLoadPublicKey = viewModel::loadPublicKey,
            onDismissMessage = viewModel::clearMessage,
            onCopyPublicKey = { value ->
                platformClipboard?.setPrimaryClip(ClipData.newPlainText("SSH public key", value))
                runCatching { composeClipboard.setText(AnnotatedString(value)) }
            },
            onCopyFingerprint = { value ->
                platformClipboard?.setPrimaryClip(ClipData.newPlainText("SSH key fingerprint", value))
                runCatching { composeClipboard.setText(AnnotatedString(value)) }
            },
            validateImportPem = ::validateSshKeyImportPem,
            isImportProtected = SshKeyMaterial::isEncrypted,
            initialImportCandidate = fileImportCandidate,
            onInitialImportConsumed = { fileImportCandidate = null },
            modifier = modifier,
        )
    }
}
