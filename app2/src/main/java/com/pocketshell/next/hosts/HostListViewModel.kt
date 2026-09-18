package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.next.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Host-list screen state, derived from `core-storage`'s [HostDao].
 *
 * [HostRow] and [HostListUiState] live in the shared presentation module
 * (`shared:ui-screens`, #2636 D1); this ViewModel is the app-side Room→row
 * projection that feeds them.
 *
 * app2 reads the same `hosts` table the shipping client writes (plan §U-1);
 * it does not add, edit or delete rows yet. The whole ViewModel is therefore
 * one `Flow` mapping: `getAll()` → UI rows. Room owns the invalidation, so an
 * edit made elsewhere in the process re-emits here with no refresh plumbing.
 *
 * [dispatcher] is injected rather than hard-coded so a unit test can run the
 * mapping on its own scheduler and stay deterministic; it is where the row
 * projection runs, not where the query runs (Room already dispatches its own
 * queries off the main thread).
 */
@HiltViewModel
class HostListViewModel @Inject constructor(
    private val hostDao: HostDao,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : ViewModel() {

    /**
     * Remove a host (task P-6). Deliberately the only write on this ViewModel:
     * the row's other management actions are navigations, and add/edit belongs
     * to [AddEditHostViewModel] where the form state lives.
     *
     * No optimistic update — Room's invalidation re-emits the list, so the row
     * disappearing IS the confirmation, and there is no local copy that could
     * disagree with the table.
     */
    fun delete(hostId: Long) {
        viewModelScope.launch { hostDao.deleteById(hostId) }
    }

    val state: StateFlow<HostListUiState> =
        hostDao.getAll()
            .map { hosts -> HostListUiState(hosts = hosts.map { toRow(it) }, loaded = true) }
            .flowOn(dispatcher)
            .stateIn(
                scope = viewModelScope,
                // Keeps the Room query alive across a configuration change /
                // brief backgrounding, and cancels it when the screen is gone
                // for good — the list must not hold a cursor open forever.
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = HostListUiState(),
            )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        /**
         * `HostEntity` → row. The display name falls back to the hostname when
         * the stored label is blank, so a row imported without a name is still
         * tappable and identifiable rather than rendering as an empty line.
         */
        fun toRow(host: HostEntity): HostRow = HostRow(
            id = host.id,
            name = host.name.ifBlank { host.hostname },
            subtitle = "${host.username}@${host.hostname}",
        )
    }
}
