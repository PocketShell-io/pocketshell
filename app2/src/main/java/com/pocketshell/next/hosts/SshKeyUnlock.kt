package com.pocketshell.next.hosts

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * True when Android can present the native strong-biometric/device prompt.
 *
 * This file is the app2 half of the SSH-key unlock surface (#2636 D9).
 * `SshKeyUnlockPanel` and the `SSH_KEYS_*` tags moved to the shared
 * presentation module (`shared:ui-screens`, same package, so every reference
 * in `SshKeysScreen.kt` is unchanged). What stays here is not presentation:
 * it drives the real `androidx.biometric` prompt against a `FragmentActivity`
 * and reads an Android `Context` — exactly the platform seam the presentation
 * boundary forbids.
 */
fun isSshKeyUnlockRequired(context: android.content.Context): Boolean {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    return BiometricManager.from(context).canAuthenticate(authenticators) ==
        BiometricManager.BIOMETRIC_SUCCESS
}

/** Small testable guard against launching two native prompts for one tap. */
internal class SshKeyUnlockInFlightGate {
    var isInFlight: Boolean = false
        private set

    fun tryMarkInFlight(): Boolean {
        if (isInFlight) return false
        isInFlight = true
        return true
    }

    fun clear() {
        isInFlight = false
    }
}

internal interface SshKeyUnlockPromptLauncher {
    fun launch(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        callback: BiometricPrompt.AuthenticationCallback,
    )
}

private object AndroidSshKeyUnlockPromptLauncher : SshKeyUnlockPromptLauncher {
    override fun launch(
        activity: FragmentActivity,
        promptInfo: BiometricPrompt.PromptInfo,
        callback: BiometricPrompt.AuthenticationCallback,
    ) {
        BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            callback,
        ).authenticate(promptInfo)
    }
}

/**
 * Launch the real Android prompt. The app never paints a fake biometric
 * dialog, reads biometric data, or treats a failed callback as success.
 */
internal fun launchSshKeyUnlock(
    activity: FragmentActivity?,
    title: String = "Unlock SSH keys",
    subtitle: String = "Confirm it is you before viewing local key details",
    promptLauncher: SshKeyUnlockPromptLauncher = AndroidSshKeyUnlockPromptLauncher,
    onSuccess: () -> Unit,
    onError: (String) -> Unit,
    onFailure: (String) -> Unit = onError,
) {
    if (activity == null) {
        onError("Device unlock is unavailable from this screen")
        return
    }

    val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    runCatching {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(authenticators)
            .build()
        promptLauncher.launch(
            activity = activity,
            promptInfo = promptInfo,
            callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    onFailure("Unlock failed")
                }
            },
        )
    }.onFailure { throwable ->
        val detail = throwable.message?.takeIf { it.isNotBlank() }
            ?: throwable::class.java.simpleName
        onError("Could not start device unlock: $detail")
    }
}
