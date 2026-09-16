package com.pocketshell.next.mockapp

/**
 * Pure state machine of the mock app: `(MockAppState, MockAppEvent) -> MockAppState`.
 *
 * The single place fake state changes happen. Purity is the point — the same
 * function drives the JVM unit tests, the fixture scenarios (a scenario is
 * just `reduce(populated(), event)` folded onto itself) and, in phase 2+, any
 * interactive driver of the extracted presentation module. No clock, no I/O,
 * no randomness: the same event sequence always yields the same state.
 */
fun reduce(state: MockAppState, event: MockAppEvent): MockAppState = when (event) {
    is MockAppEvent.Navigate -> when (event.to) {
        MockDestination.HostForm -> state.copy(
            destination = MockDestination.HostForm,
            hostForm = state.hostForm.copy(editing = false, saved = false),
        )
        MockDestination.SshKeys -> state.copy(
            destination = MockDestination.SshKeys,
            sshKeysLoaded = true,
            sshKeyMessage = null,
        )
        MockDestination.Usage -> state.copy(
            destination = MockDestination.Usage,
            usageRefreshing = false,
        )
        else -> state.copy(destination = event.to)
    }

    is MockAppEvent.Back -> state.destination.parent
        ?.let { parent -> state.copy(destination = parent) }
        ?: state

    is MockAppEvent.OpenHost -> state.copy(destination = MockDestination.Workspaces)

    is MockAppEvent.EditHost -> {
        val host = state.hosts.firstOrNull { it.id == event.hostId }
        state.copy(
            destination = MockDestination.HostForm,
            hostForm = if (host == null) {
                state.hostForm.copy(editing = false)
            } else {
                state.hostForm.copy(
                    name = host.name,
                    hostname = host.subtitle.substringAfter('@'),
                    username = host.subtitle.substringBefore('@'),
                    editing = true,
                )
            },
        )
    }

    is MockAppEvent.HostFormChange -> state.copy(hostForm = event.next)

    is MockAppEvent.ComposerDraftChange -> state.copy(composerDraft = event.text)

    is MockAppEvent.ComposerSend -> if (state.composerDraft.isNotBlank()) {
        state.copy(
            composerDraft = "",
            composerHistory = state.composerHistory + state.composerDraft,
        )
    } else {
        state
    }

    is MockAppEvent.ComposerHistoryRestore ->
        state.composerDraftHistoryEntry(event.id)?.let { entry ->
            state.copy(composerDraft = entry)
        } ?: state

    is MockAppEvent.WorkspaceSearchChange -> state.copy(workspaceSearchQuery = event.query)

    is MockAppEvent.ServicesDiscoveryChange -> state.copy(
        servicesEnabled = event.enabled,
        servicesLoading = false,
    )

    is MockAppEvent.UsageRefreshStart -> state.copy(usageRefreshing = true)

    is MockAppEvent.UsageRefreshComplete -> state.copy(usageRefreshing = false)

    is MockAppEvent.SessionRetry -> state.copy(
        sessionPhase = MockSessionPhase.CONNECTING,
        sessionMessage = "",
    )

    is MockAppEvent.SessionAttached -> state.copy(
        sessionPhase = MockSessionPhase.LIVE,
        sessionMessage = "",
    )

    is MockAppEvent.SessionFailed -> state.copy(
        sessionPhase = MockSessionPhase.FAILED,
        sessionMessage = "Could not reach the session. Tap Retry to try again.",
    )

    is MockAppEvent.SshKeysMessageDismiss -> state.copy(sshKeyMessage = null)
}

/** History entry bodies by list position — ids are 1-based list indexes. */
fun MockAppState.composerDraftHistoryEntry(id: Long): String? =
    composerHistory.getOrNull(id.toInt() - 1)
