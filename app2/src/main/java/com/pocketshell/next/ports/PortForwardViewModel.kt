package com.pocketshell.next.ports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.portfwd.AutoForwarderSupervisor.ConnectionState
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.PortRemappingDao
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The port-forward screen's state holder (rewrite task P-4).
 *
 * The state it publishes is the pure [PortForwardDisplayState] (#2636 D11):
 * the display half of the former core-typed `PortForwardUiState` moved to
 * `shared:ui-screens` with the screens that paint it, and this ViewModel is
 * the family's single core → display ingestion point — the controller
 * snapshot's `ConnectionState`/`TunnelInfo` values cross through the
 * `toDisplay()` adapters in [PortForwardDisplayMapping] and nothing
 * `com.pocketshell.core.portfwd` reaches the screens. (D10's usage seam,
 * same shape: `UsageFetcher` maps, `UsageScreenState` is what flows.)
 *
 * It owns no forwarding state: [ForwardingController] does, because a forward has
 * to outlive this ViewModel — leaving the screen must not kill a tunnel the user
 * just opened. So this class is a projection: it reads the controller snapshot and
 * turns user intent into controller calls.
 *
 * The durable `hosts.enabled` intent is written by the controller BEFORE the
 * supervisor mounts, so a process death between the two still leaves the next
 * resume knowing what should be running.
 */
@HiltViewModel
class PortForwardViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val hostDao: HostDao,
    private val remappingDao: PortRemappingDao,
    private val controller: ForwardingController,
    private val showAllPortsStore: ShowAllPortsStore,
    // The HTTP-verify sweep hits real sockets (LocalServiceVerifier), so it
    // must not ride Main. Injected per the rewrite plan's DI rule (#2498) —
    // no default value: a default-arg `@Inject` constructor generates a
    // second constructor at the bytecode level that Hilt refuses to bind.
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "PortForwardViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val _state = MutableStateFlow(PortForwardDisplayState())
    val state: StateFlow<PortForwardDisplayState> = _state.asStateFlow()

    /**
     * The latest UNFILTERED snapshot for this host. Kept off the UI state so the
     * screen cannot accidentally paint an unfiltered list, while the checkbox can
     * still re-filter immediately instead of waiting for the next emission.
     */
    private var allTunnels: List<TunnelInfo> = emptyList()
    private var verificationJob: Job? = null
    private var verificationKey: List<Pair<Int, Int>> = emptyList()

    init {
        viewModelScope.launch {
            // Observe the host row rather than reading it once (#2708): Add-tunnel
            // flips `hosts.enabled` in Room through the controller while this
            // screen is open, and only a live observer turns that write into the
            // toggle-on state — a stale init snapshot left the new tunnel behind
            // the discovery-off branch until the user toggled or re-entered.
            // The checkbox read stays ahead of the collect so `loading` still
            // covers both reads, and later emissions never re-write it (the
            // user's own checkbox change must survive host-row updates).
            val showAll = showAllPortsStore.isShowAll()
            _state.value = _state.value.copy(showAllPorts = showAll)
            hostDao.observeById(hostId).collect { host ->
                _state.value = _state.value.copy(
                    hostName = host?.name ?: "Port forwarding",
                    hostSubtitle = host?.let { "${it.username}@${it.hostname}:${it.port}" }.orEmpty(),
                    enabled = host?.enabled == true,
                    loading = false,
                ).reFiltered()
            }
        }
        viewModelScope.launch {
            remappingDao.getByHostId(hostId).collect { mappings ->
                _state.value = _state.value.copy(
                    manualRemotePorts = mappings.map { it.remotePort }.toSet(),
                    manualTunnelNames = mappings.associate { it.remotePort to it.name },
                )
            }
        }
        viewModelScope.launch {
            controller.snapshot.collect { snapshot ->
                val host = snapshot.firstOrNull { it.hostId == hostId }
                allTunnels = host?.tunnels.orEmpty()
                _state.value = _state.value
                    .copy(
                        connection = (host?.connection ?: ConnectionState.Idle).toDisplay(),
                        // The gated reason, so the screen cannot paint a reason
                        // that belongs to a state the host has already left
                        // (#2491) — and cannot disagree with the notification,
                        // which reads the very same accessor.
                        attention = host?.terminalAttention,
                    )
                    .reFiltered()
            }
        }
    }

    /** The Off/On control: records the durable intent and mounts/unmounts forwarding. */
    fun setEnabled(enabled: Boolean) {
        // Reflected optimistically so the toggle never looks stuck while the dial
        // is in flight; the controller snapshot fills in the rows.
        _state.value = _state.value.copy(enabled = enabled)
        viewModelScope.launch {
            if (enabled) controller.start(hostId) else controller.stop(hostId)
        }
    }

    /** A row's Start/Stop: a per-port opt-in that survives reconnects. */
    fun togglePort(remotePort: Int) {
        viewModelScope.launch { controller.togglePort(hostId, remotePort) }
    }

    /** Adds a real remote→loopback mapping and starts the host forwarder. */
    suspend fun addManualTunnel(remotePort: Int, localPort: Int, name: String = "") {
        controller.addManualTunnel(hostId, remotePort, localPort, name)
    }

    /** Checks both saved mappings and currently active device-local binds. */
    suspend fun localPortCollision(localPort: Int): String? =
        controller.localPortCollision(hostId, localPort)

    /** Removes a real persisted mapping. */
    fun removeManualTunnel(remotePort: Int) {
        viewModelScope.launch { controller.removeManualTunnel(hostId, remotePort) }
    }

    /** Removes a manual mapping and waits for the live supervisor to remount. */
    suspend fun removeManualTunnelNow(remotePort: Int) {
        controller.removeManualTunnel(hostId, remotePort)
    }

    /** The "Show hidden/noisy ports" checkbox. Persisted globally. */
    fun setShowAllPorts(showAll: Boolean) {
        _state.value = _state.value.copy(showAllPorts = showAll).reFiltered()
        viewModelScope.launch { showAllPortsStore.setShowAll(showAll) }
    }

    /**
     * Verifies active local forwards before the UI offers an external browser
     * handoff. The result is deliberately ephemeral: it must be checked again
     * when the forward or its local port changes.
     *
     * Since #2636 D11 the routes see only display rows, so they name the
     * tunnels to verify by remote port and the resolution back to the core
     * rows happens HERE, against [allTunnels] — the same rows the published
     * display state was mapped from, so a caller can never name a tunnel the
     * snapshot does not carry.
     */
    fun verifyHttpServices(remotePorts: Collection<Int>) {
        val candidates = allTunnels
            .filter { it.remotePort in remotePorts && it.status == TunnelInfo.Status.FORWARDING }
            .distinctBy { it.remotePort }
        val key = candidates.map { it.remotePort to it.localPort }
        if (key == verificationKey) return

        verificationKey = key
        verificationJob?.cancel()
        _state.value = _state.value.copy(verifiedHttpServices = emptyMap())
        verificationJob = viewModelScope.launch(ioDispatcher) {
            val verified = candidates.mapNotNull { tunnel ->
                LocalServiceVerifier.verify(tunnel)?.let { tunnel.remotePort to it }
            }.toMap()
            if (key == verificationKey) {
                _state.value = _state.value.copy(verifiedHttpServices = verified)
            }
        }
    }

    /**
     * Re-derives [PortForwardDisplayState.rows] and
     * [PortForwardDisplayState.hiddenCount] from [allTunnels] — the family's
     * single core → display crossing (#2636 D11): the core rows are filtered
     * core-side, then mapped onto the display mirrors. One helper so a
     * checkbox change and a fresh snapshot can never disagree about what is
     * visible.
     */
    private fun PortForwardDisplayState.reFiltered(): PortForwardDisplayState = copy(
        rows = InterestingPortFilter.filter(allTunnels, showAllPorts).map { it.toDisplay() },
        discoveredRows = allTunnels.map { it.toDisplay() },
        hiddenCount = InterestingPortFilter.hiddenCount(allTunnels),
    )
}
