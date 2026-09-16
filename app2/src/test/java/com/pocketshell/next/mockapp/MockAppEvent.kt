package com.pocketshell.next.mockapp

import com.pocketshell.next.hosts.HostFormState

/**
 * What the (mock) user just did, in UI terms only.
 *
 * These are the boundary callbacks the issue describes: navigation, typing,
 * toggles and refreshes that mutate ONLY [MockAppState]. Nothing here dials
 * SSH, touches Room, starts a recording or opens a browser — acceptance 3's
 * "without side effects", made structural by the reducer's purity.
 */
sealed interface MockAppEvent {

    /** Navigate forward to a destination (a row tap, a tools entry). */
    data class Navigate(val to: MockDestination) : MockAppEvent

    /** System back. */
    data object Back : MockAppEvent

    /** A host row tap: open that host's workspaces. */
    data class OpenHost(val hostId: Long) : MockAppEvent

    /** Tools → edit host: open the form loaded with that host's values. */
    data class EditHost(val hostId: Long) : MockAppEvent

    /**
     * The host form edited its draft. The production screen edits through a
     * `(HostFormState) -> HostFormState` transform, so the event carries the
     * resulting snapshot: still pure data, still replayable, and no field
     * change (including the key picker) can fall on the floor.
     */
    data class HostFormChange(val next: HostFormState) : MockAppEvent

    /** Composer typing: the draft changes; nothing is sent anywhere. */
    data class ComposerDraftChange(val text: String) : MockAppEvent

    /**
     * Composer send: the draft moves to history and clears. The mock always
     * "delivers" (returns true from the production `onSend` contract); the
     * undelivered notice states are fixture territory, not reducer state.
     */
    data object ComposerSend : MockAppEvent

    /** Composer history: reuse a previously sent message as the draft. */
    data class ComposerHistoryRestore(val id: Long) : MockAppEvent

    /** Workspace screen search typing. */
    data class WorkspaceSearchChange(val query: String) : MockAppEvent

    /** Services & tunnels: the discovery toggle flipped. */
    data class ServicesDiscoveryChange(val enabled: Boolean) : MockAppEvent

    /** Usage pull-to-refresh started; completes when [UsageRefreshComplete] lands. */
    data object UsageRefreshStart : MockAppEvent

    /** The mock fetch finished — instant, deterministic. */
    data object UsageRefreshComplete : MockAppEvent

    /** Session retry from [MockSessionPhase.FAILED]. */
    data object SessionRetry : MockAppEvent

    /** Session reached the terminal (mock attach succeeded). */
    data object SessionAttached : MockAppEvent

    /** The reconnect ladder gave up. */
    data object SessionFailed : MockAppEvent

    /** SSH keys screen dismissed its one-shot message. */
    data object SshKeysMessageDismiss : MockAppEvent
}
