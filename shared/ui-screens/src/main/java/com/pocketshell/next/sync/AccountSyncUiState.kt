package com.pocketshell.next.sync

/**
 * The pure UI state of the Account & sync page: what the screen paints and
 * nothing else.
 *
 * Lives in the shared presentation module (#2636 D7); the Hilt ViewModel that
 * produces it (Room, GoogleAuth, the sync repository) stays in app2's
 * `AccountSyncViewModel.kt` — together with [SyncOutcome] and
 * `SyncSignInCoordinator.State`, the two app-side types this state deliberately
 * does NOT carry. The app2-side adapters map each of those onto the pure
 * display shapes below (`syncOutcomeDisplay` / `syncSignInPhase`), the same
 * seam `ReleaseInfo` → `ReleaseUpdateDisplay` uses for the settings pages
 * (#2636 D3): no app service or result type crosses into `shared:ui-screens`.
 */

/** One tickable row in the Account & sync host picker. */
data class SyncHostRow(
    val name: String,
    val subtitle: String,
    val checked: Boolean,
    /** True for an alias that exists only in the account, not on this device. */
    val accountOnly: Boolean = false,
)

/**
 * Pure display shape of a Google sign-in attempt (#2636 D7). Mirrors
 * `SyncSignInCoordinator.State` one-to-one; the app-side adapter in app2's
 * `AccountSyncViewModel.kt` maps between them.
 */
sealed interface SyncSignInPhase {

    data object Idle : SyncSignInPhase

    /** The Custom Tab is open; we are waiting for the redirect. */
    data object AwaitingRedirect : SyncSignInPhase

    /** The code came back and is being exchanged for tokens. */
    data object Exchanging : SyncSignInPhase

    data class Failed(val message: String) : SyncSignInPhase

    data class SignedIn(val email: String?) : SyncSignInPhase
}

/**
 * Pure display shape of the last thing a sync did (#2636 D7). Mirrors
 * `SyncOutcome` one-to-one; `SyncOutcome` itself stays in app2's
 * `AccountSyncViewModel.kt` — it is the ViewModel/repository's language, and
 * the shared screen paints the display shape, never the result type.
 */
sealed interface SyncOutcomeDisplay {

    data object None : SyncOutcomeDisplay

    data object Running : SyncOutcomeDisplay

    data class Pushed(val uploaded: Int, val version: Int) : SyncOutcomeDisplay

    data class Pulled(val hosts: Int) : SyncOutcomeDisplay

    data object AccountEmpty : SyncOutcomeDisplay

    data class Failed(val message: String) : SyncOutcomeDisplay
}

/**
 * Everything [AccountSyncScreen] renders.
 *
 * Note what is NOT here: an ID token, a refresh token, or the passphrase. The
 * tokens never leave app2's `GoogleAuth`/the Keystore-backed store, and the
 * passphrase lives in the text field's own state and is handed to a call as a
 * parameter. `AccountSyncViewModelTest` asserts that containment rather than
 * trusting this comment.
 *
 * [clientConfigured] carries no app-side default (`SyncConfig` stays in app2):
 * the ViewModel always sets it explicitly from the auth status.
 */
data class AccountSyncUiState(
    val clientConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val signInPhase: SyncSignInPhase = SyncSignInPhase.Idle,
    val hosts: List<SyncHostRow> = emptyList(),
    val outcome: SyncOutcomeDisplay = SyncOutcomeDisplay.None,
)
